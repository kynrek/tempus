package com.eddyizm.tempus.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.database.index.IndexMeta;
import com.eddyizm.tempus.database.index.IndexedAlbum;
import com.eddyizm.tempus.database.index.IndexedArtist;
import com.eddyizm.tempus.database.index.IndexedGenre;
import com.eddyizm.tempus.database.index.LibraryIndexDao;
import com.eddyizm.tempus.database.index.LibraryIndexDatabase;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.Genre;
import com.eddyizm.tempus.util.Preferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The local library index (stale-while-revalidate): the catalogue screens show what was stored
 * here last time at once, fetch the server in the background as before, and store the fresh
 * list back, writing only the rows that changed.
 *
 * All database work runs on one background thread, in order. Results come back on the main
 * thread. The index belongs to one server and music folder; reading it for another returns
 * nothing, and the next store replaces it.
 */
public final class LibraryIndex {
    private static final String TAG = "LibraryIndex";
    public static final String LOCATION_INTERNAL = "internal";
    public static final String LOCATION_SD = "sd";
    private static final String META_SCOPE = "scope";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LibraryIndex");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Touched only on the IO thread. */
    private static Boolean disabled;
    private static LibraryIndexDatabase db;
    private static File dbFile;

    private LibraryIndex() {
    }

    // ---- reading ---------------------------------------------------------------------------

    /** The stored artists in A–Z order, or null when there are none for this server. */
    @MainThread
    public static void artists(@NonNull Consumer<List<ArtistID3>> onMain) {
        read("artists", LibraryIndexDao::artists, IndexedArtist::toArtist, onMain);
    }

    @MainThread
    public static void albums(@NonNull Consumer<List<AlbumID3>> onMain) {
        read("albums", LibraryIndexDao::albums, IndexedAlbum::toAlbum, onMain);
    }

    @MainThread
    public static void genres(@NonNull Consumer<List<Genre>> onMain) {
        read("genres", LibraryIndexDao::genres, IndexedGenre::toGenre, onMain);
    }

    private static <R, M> void read(String what, Function<LibraryIndexDao, List<R>> query,
                                    Function<R, M> convert, Consumer<List<M>> onMain) {
        String scope = scope();
        IO.execute(() -> {
            List<M> result = null;
            try {
                long start = SystemClock.elapsedRealtime();
                LibraryIndexDao dao = disabled() ? null : dao();
                if (dao != null && scope.equals(dao.meta(META_SCOPE))) {
                    List<R> rows = query.apply(dao);
                    if (!rows.isEmpty()) {
                        result = new ArrayList<>(rows.size());
                        for (R row : rows) result.add(convert.apply(row));
                    }
                }
                Log.i(TAG, what + ": " + (result != null ? result.size() : 0) + " from index in "
                        + (SystemClock.elapsedRealtime() - start) + " ms");
            } catch (RuntimeException e) {
                Log.w(TAG, "Reading " + what + " failed", e);
            }
            List<M> out = result;
            MAIN.post(() -> onMain.accept(out));
        });
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * Stores the artists the server just returned, a complete list, then tells {@code changedOnMain}
     * (if given) whether they differ from what the index held, which is what the screen shows when
     * it came from the index. With the index switched off it is always told they differ.
     */
    public static void storeArtists(@NonNull List<ArtistID3> artists, @Nullable Consumer<Boolean> changedOnMain) {
        store("artists", artists, IndexedArtist::from, LibraryIndexDao::syncArtists, a -> a.getId() != null, changedOnMain);
    }

    public static void storeAlbums(@NonNull List<AlbumID3> albums, @Nullable Consumer<Boolean> changedOnMain) {
        store("albums", albums, IndexedAlbum::from, LibraryIndexDao::syncAlbums, a -> a.getId() != null, changedOnMain);
    }

    public static void storeGenres(@NonNull List<Genre> genres, @Nullable Consumer<Boolean> changedOnMain) {
        store("genres", genres, IndexedGenre::from, LibraryIndexDao::syncGenres, g -> g.getGenre() != null, changedOnMain);
    }

    private interface Sync<R> {
        LibraryIndexDao.Changes apply(LibraryIndexDao dao, List<R> rows);
    }

    private static <M, R> void store(String what, List<M> items, Function<M, R> toRow, Sync<R> sync,
                                     java.util.function.Predicate<M> valid, @Nullable Consumer<Boolean> changedOnMain) {
        String scope = scope();
        List<M> copy = new ArrayList<>(items);
        IO.execute(() -> {
            boolean changed = true;
            try {
                if (disabled()) return;
                long start = SystemClock.elapsedRealtime();
                List<R> rows = new ArrayList<>(copy.size());
                for (M item : copy) if (valid.test(item)) rows.add(toRow.apply(item));
                LibraryIndexDatabase database = database();
                LibraryIndexDao dao = database.dao();
                LibraryIndexDao.Changes changes = database.runInTransaction(() -> {
                    if (!scope.equals(dao.meta(META_SCOPE))) {
                        dao.clearAll();
                        dao.putMeta(new IndexMeta(META_SCOPE, scope));
                    }
                    LibraryIndexDao.Changes c = sync.apply(dao, rows);
                    dao.putMeta(new IndexMeta(what + "_synced", String.valueOf(System.currentTimeMillis())));
                    return c;
                });
                changed = changes.any();
                Log.i(TAG, what + ": stored " + rows.size() + " (" + changes + ") in "
                        + (SystemClock.elapsedRealtime() - start) + " ms");
            } catch (RuntimeException e) {
                Log.w(TAG, "Storing " + what + " failed", e);
            } finally {
                if (changedOnMain != null) {
                    boolean result = changed;
                    MAIN.post(() -> changedOnMain.accept(result));
                }
            }
        });
    }

    /**
     * For measuring: {@code adb shell setprop debug.music.noindex 1} makes the next start behave as
     * if there were no index (nothing read or stored); {@code setprop debug.music.noindex 0} undoes it.
     * IO thread only; read once per process.
     */
    private static boolean disabled() {
        if (disabled == null) {
            disabled = false;
            try {
                Process p = new ProcessBuilder("getprop", "debug.music.noindex").start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    disabled = "1".equals(String.valueOf(r.readLine()).trim());
                }
                p.destroy();
            } catch (IOException | RuntimeException ignored) {
            }
            if (disabled) Log.i(TAG, "Index switched off (debug.music.noindex=1)");
        }
        return disabled;
    }

    // ---- where it lives --------------------------------------------------------------------

    /** Whether there is a removable card (the SD card) to keep the index on. */
    public static boolean hasRemovableStorage(@NonNull Context context) {
        return removableDir(context) != null;
    }

    /**
     * Moves the index to the location now set in the preferences (internal storage or the SD
     * card), keeping its contents. If the copy fails the index starts empty there and fills in
     * from the server.
     */
    public static void relocate() {
        IO.execute(() -> {
            File target = targetFile();
            if (dbFile == null) {
                // Not opened yet: look for it at the other location.
                File other = LOCATION_SD.equals(Preferences.getLibraryIndexLocation())
                        ? internalFile() : sdFileOrNull();
                if (other == null || !other.exists() || other.equals(target)) return;
                move(other, target);
                return;
            }
            if (dbFile.equals(target)) return;
            File from = dbFile;
            db.close();
            db = null;
            dbFile = null;
            move(from, target);
        });
    }

    private static void move(File from, File to) {
        long start = SystemClock.elapsedRealtime();
        try {
            //noinspection ResultOfMethodCallIgnored
            to.getParentFile().mkdirs();
            deleteDatabaseFiles(to);
            copy(from, to);
            Log.i(TAG, "Moved index " + from + " -> " + to + " in " + (SystemClock.elapsedRealtime() - start) + " ms");
        } catch (IOException e) {
            Log.w(TAG, "Moving the index failed; it will be rebuilt", e);
            deleteDatabaseFiles(to);
        }
        deleteDatabaseFiles(from);
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private static void deleteDatabaseFiles(File file) {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(file.getPath() + suffix).delete();
        }
    }

    /** IO thread only. */
    private static LibraryIndexDatabase database() {
        if (db == null) {
            dbFile = targetFile();
            db = LibraryIndexDatabase.open(App.getContext(), dbFile, !dbFile.equals(internalFile()));
        }
        return db;
    }

    private static LibraryIndexDao dao() {
        return database().dao();
    }

    /** Where the preference says the index goes; internal storage if the card is missing. */
    private static File targetFile() {
        if (LOCATION_SD.equals(Preferences.getLibraryIndexLocation())) {
            File sd = sdFileOrNull();
            if (sd != null) return sd;
        }
        return internalFile();
    }

    private static File internalFile() {
        return App.getContext().getDatabasePath(LibraryIndexDatabase.FILE_NAME);
    }

    @Nullable
    private static File sdFileOrNull() {
        File dir = removableDir(App.getContext());
        return dir != null ? new File(dir, LibraryIndexDatabase.FILE_NAME) : null;
    }

    /** The app's folder on the first removable volume, the same one downloads use for "External". */
    @Nullable
    private static File removableDir(Context context) {
        try {
            File[] dirs = context.getExternalFilesDirs(null);
            return dirs.length > 1 ? dirs[1] : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The server and music folder the catalogue lists come from. */
    private static String scope() {
        return Preferences.getServerId() + "|" + Preferences.getActiveMusicFolderId();
    }
}
