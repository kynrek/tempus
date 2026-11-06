package com.eddyizm.tempus.glide;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.repository.DownloadRepository;
import com.eddyizm.tempus.util.Preferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cover art for downloaded tracks, so a downloaded track shows its cover with no connection.
 * Glide's own cache is size-capped and only holds what has been shown, so it can't promise that.
 *
 * A cover is saved when its track finishes downloading, and tracks downloaded before this existed
 * are caught up in the background on Wi-Fi. {@link IPv6StringLoader} reads a saved cover instead
 * of asking the server for it.
 *
 * The covers live in app-private internal storage, even when the downloads are on an SD card:
 * an SD card goes through the system's FUSE layer and MediaProvider, and thousands of small files
 * in one exFAT folder made every lookup slow and kept MediaProvider busy. Which covers are saved is
 * kept in memory (one directory listing per start), so showing or checking a cover costs no disk
 * lookup. Covers saved beside the downloads by older versions are moved over once.
 */
public final class OfflineCovers {
    private static final String TAG = "OfflineCovers";
    private static final String DIRECTORY = "covers";
    private static final String PARTIAL = ".part";
    // Full (the player on the car screen), half (art tiles) and quarter (small rows). Smaller copies
    // are much less to read and decode while scrolling; Settings can skip the full size.
    private static final int FULL = 600, HALF = 300, QUARTER = 150;
    private static final int[] ALL_SIZES = {FULL, HALF, QUARTER};
    private static final int[] SMALL_SIZES = {HALF, QUARTER};

    private static final ExecutorService FETCHER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "OfflineCovers");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static volatile OkHttpClient client;
    private static volatile File directory;
    private static volatile boolean catchingUp;

    /** File names in {@link #directory}; only trusted once {@link #indexed}. */
    private static final Set<String> saved = ConcurrentHashMap.newKeySet();
    private static volatile boolean indexed;
    /** An older version's folder still being moved over, and what's left in it. */
    @Nullable private static volatile File legacy;
    private static final Set<String> legacySaved = ConcurrentHashMap.newKeySet();

    private OfflineCovers() {
    }

    /**
     * The saved cover for a getCoverArt URL: the smallest saved copy at least as big as the image
     * being shown ({@code width} x {@code height}, or the URL's size), else the biggest one saved.
     * Null to fetch it from the server.
     */
    @Nullable
    static File savedFor(String url, int width, int height) {
        if (url == null || !url.contains("getCoverArt")) return null;
        String id;
        int urlSize = 0;
        try {
            Uri uri = Uri.parse(url);
            id = uri.getQueryParameter("id");
            String s = uri.getQueryParameter("size");
            if (s != null) urlSize = Integer.parseInt(s);
        } catch (RuntimeException e) {
            return null;
        }
        File dir = directory;
        if (id == null || dir == null) return null;
        int wanted = Math.max(width, height);
        if (wanted <= 0) wanted = urlSize > 0 ? urlSize : FULL;   // Glide's "original size", or unknown
        File old = legacy;
        File best = null, biggest = null;
        for (int size : usableSizes()) {   // largest first
            String name = fileName(id, size);
            File file;
            if (isSaved(dir, name)) file = new File(dir, name);
            else if (old != null && legacySaved.contains(name)) file = new File(old, name);
            else continue;
            if (biggest == null) biggest = file;
            if (size >= wanted) best = file;
        }
        return best != null ? best : biggest;
    }

    private static boolean isSaved(File dir, String name) {
        // Until the listing is in, look at the disk; internal storage makes that cheap.
        return indexed ? saved.contains(name) : new File(dir, name).isFile();
    }

    private static int[] usableSizes() {
        return smallOnly() ? SMALL_SIZES : ALL_SIZES;
    }

    private static boolean smallOnly() {
        return "small".equals(Preferences.getOfflineCoverSizes());
    }

    /** Saves the cover of a finished download, off the calling thread. */
    public static void saveFor(Context context, String downloadId) {
        Context app = context.getApplicationContext();
        FETCHER.execute(() -> {
            Download download = new DownloadRepository().getDownload(downloadId);
            if (download != null) save(app, download.getCoverArtId());
        });
    }

    /** Saves the covers of everything already downloaded that doesn't have one yet; Wi-Fi only. */
    public static void catchUp(Context context) {
        if (catchingUp) return;
        catchingUp = true;
        Context app = context.getApplicationContext();
        directoryFor(app);
        FETCHER.execute(() -> {
            try {
                List<Download> downloads = new DownloadRepository().getAllDownloads();
                Set<String> seen = new HashSet<>();
                boolean small = smallOnly();
                int saved = 0;
                for (Download download : downloads) {
                    String id = download.getCoverArtId();
                    if (id == null || !seen.add(id)) continue;
                    if (small) delete(app, fileName(id, FULL));   // free the space
                    if (allSaved(app, id)) continue;
                    if (isMetered(app)) {
                        Log.d(TAG, "Stopping catch-up off Wi-Fi after " + saved + " covers");
                        return;
                    }
                    if (save(app, id)) saved++;
                }
                Log.d(TAG, "Catch-up done: " + saved + " covers saved, " + seen.size() + " downloaded covers in all");
            } catch (RuntimeException e) {
                Log.w(TAG, "Catch-up failed", e);
            } finally {
                catchingUp = false;
            }
        });
    }

    /**
     * Points the loader at the saved covers, so they're used from the first image on, then lists
     * them and moves over any an older version kept beside the downloads (off the calling thread;
     * the catch-up is queued behind it, so it doesn't fetch what's about to be moved).
     */
    public static void init(Context context) {
        Context app = context.getApplicationContext();
        File dir = directoryFor(app);
        FETCHER.execute(() -> {
            index(dir);
            moveLegacy(app, dir);
        });
    }

    private static void index(File dir) {
        String[] names = dir.list();
        if (names != null) {
            for (String name : names) {
                if (name.endsWith(PARTIAL)) new File(dir, name).delete();   // an interrupted save
                else saved.add(name);
            }
        }
        indexed = true;
    }

    /** Older versions kept the covers beside the downloads (possibly on the SD card); move them here. */
    private static void moveLegacy(Context context, File dir) {
        for (File root : legacyRoots(context)) {
            File old = new File(root, DIRECTORY);
            if (old.equals(dir) || !old.isDirectory()) continue;
            String[] names = old.list();
            if (names == null) continue;
            for (String name : names) if (!name.endsWith(PARTIAL)) legacySaved.add(name);
            legacy = old;
            int moved = 0;
            for (String name : names) {
                File from = new File(old, name);
                if (!name.endsWith(PARTIAL) && !saved.contains(name) && copy(from, new File(dir, name))) {
                    saved.add(name);
                    moved++;
                }
                legacySaved.remove(name);
                from.delete();
            }
            legacy = null;
            legacySaved.clear();
            if (!old.delete()) Log.w(TAG, "Couldn't remove " + old);
            Log.d(TAG, "Moved " + moved + " covers from " + old);
        }
    }

    private static Set<File> legacyRoots(Context context) {
        Set<File> roots = new HashSet<>();
        try {
            for (File f : context.getExternalFilesDirs(null)) if (f != null) roots.add(f);
        } catch (RuntimeException ignored) {
        }
        roots.add(context.getFilesDir());   // the download fallback when external storage isn't writable
        return roots;
    }

    private static boolean copy(File from, File to) {
        File partial = new File(to.getPath() + PARTIAL);
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[16384];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            if (total == 0) {
                partial.delete();
                return false;
            }
        } catch (IOException e) {
            partial.delete();
            return false;
        }
        return partial.renameTo(to);
    }

    private static boolean allSaved(Context context, String id) {
        File dir = directoryFor(context);
        for (int size : usableSizes()) {
            if (!isSaved(dir, fileName(id, size))) return false;
        }
        return true;
    }

    private static void delete(Context context, String name) {
        File dir = directoryFor(context);
        if (indexed && !saved.contains(name)) return;   // nothing to delete: skip the lookup
        new File(dir, name).delete();
        saved.remove(name);
    }

    /** Saves each size (per Settings) of a cover that isn't saved yet; true if any was. */
    private static boolean save(Context context, @Nullable String id) {
        if (id == null || id.isEmpty()) return false;
        boolean any = false;
        for (int size : usableSizes()) any |= saveSize(context, id, size);
        return any;
    }

    private static boolean saveSize(Context context, String id, int size) {
        File dir = directoryFor(context);
        String name = fileName(id, size);
        if (isSaved(dir, name)) return false;
        File file = new File(dir, name);

        String url;
        try {
            url = CustomGlideRequest.createUrl(id, size);
        } catch (RuntimeException e) {
            return false; // not signed in to a server
        }
        File partial = new File(dir, name + PARTIAL);
        try (Response response = client().newCall(new Request.Builder().url(url).build()).execute()) {
            ResponseBody body = response.body();
            String type = body != null && body.contentType() != null ? body.contentType().type() : "";
            if (!response.isSuccessful() || body == null || !"image".equals(type)) return false;
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            if (!partial.renameTo(file)) return false;
            saved.add(name);
            return true;
        } catch (IOException e) {
            partial.delete();
            return false;
        }
    }

    private static File directoryFor(Context context) {
        File dir = directory;
        if (dir == null) {
            dir = new File(context.getNoBackupFilesDir(), DIRECTORY);
            if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Can't create " + dir);
            directory = dir;
        }
        return dir;
    }

    /** Full size keeps the original name, so covers saved before the smaller sizes still count. */
    private static String fileName(String id, int size) {
        StringBuilder base = new StringBuilder(id.length() + 9);
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            base.append(ok ? c : '_');
        }
        return size == FULL ? base.append(".img").toString() : base.append('_').append(size).append(".img").toString();
    }

    private static boolean isMetered(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        return cm == null || cm.isActiveNetworkMetered();
    }

    private static OkHttpClient client() {
        OkHttpClient c = client;
        if (c == null) {
            c = IPv6StringLoader.buildClient().newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build();
            client = c;
        }
        return c;
    }
}
