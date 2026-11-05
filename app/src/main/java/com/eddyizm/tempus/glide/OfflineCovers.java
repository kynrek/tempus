package com.eddyizm.tempus.glide;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.repository.DownloadRepository;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.Preferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cover art kept next to the downloads, so a downloaded track shows its cover with no connection.
 * Glide's own cache is size-capped and only holds what has been shown, so it can't promise that.
 *
 * A cover is saved when its track finishes downloading, and tracks downloaded before this existed
 * are caught up in the background on Wi-Fi. {@link IPv6StringLoader} reads a saved cover instead
 * of asking the server for it.
 */
public final class OfflineCovers {
    private static final String TAG = "OfflineCovers";
    private static final String DIRECTORY = "covers";
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
        File best = null, biggest = null;
        for (int size : usableSizes()) {   // largest first
            File file = new File(dir, fileName(id, size));
            if (!file.isFile() || file.length() == 0) continue;
            if (biggest == null) biggest = file;
            if (size >= wanted) best = file;
        }
        return best != null ? best : biggest;
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
                int saved = 0;
                for (Download download : downloads) {
                    String id = download.getCoverArtId();
                    if (id == null || !seen.add(id)) continue;
                    if (smallOnly()) new File(directoryFor(app), fileName(id, FULL)).delete();   // free the space
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

    /** Points the loader at the saved covers, so they're used from the first image on. */
    public static void init(Context context) {
        directoryFor(context.getApplicationContext());
    }

    private static boolean allSaved(Context context, String id) {
        for (int size : usableSizes()) {
            if (!new File(directoryFor(context), fileName(id, size)).isFile()) return false;
        }
        return true;
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
        File file = new File(dir, fileName(id, size));
        if (file.isFile() && file.length() > 0) return false;

        String url;
        try {
            url = CustomGlideRequest.createUrl(id, size);
        } catch (RuntimeException e) {
            return false; // not signed in to a server
        }
        File partial = new File(dir, file.getName() + ".part");
        try (Response response = client().newCall(new Request.Builder().url(url).build()).execute()) {
            ResponseBody body = response.body();
            String type = body != null && body.contentType() != null ? body.contentType().type() : "";
            if (!response.isSuccessful() || body == null || !"image".equals(type)) return false;
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            return partial.renameTo(file);
        } catch (IOException e) {
            partial.delete();
            return false;
        }
    }

    private static File directoryFor(Context context) {
        File dir = directory;
        if (dir == null) {
            dir = new File(DownloadUtil.getDownloadRoot(context), DIRECTORY);
            if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Can't create " + dir);
            directory = dir;
        }
        return dir;
    }

    /** Full size keeps the original name, so covers saved before the smaller sizes still count. */
    private static String fileName(String id, int size) {
        String base = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return size == FULL ? base + ".img" : base + "_" + size + ".img";
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
