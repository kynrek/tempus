package com.eddyizm.tempus.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;

import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.DownloadUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Songs being downloaded right now, and how far along each is, for the progress circles on track
 * rows, albums and the download queue. Both download paths report here: the app's own downloads
 * (Media3, read from its download manager twice a second while any are running) and downloads to
 * a folder picked in Settings (ExternalAudioWriter, which reports as it copies).
 *
 * Songs are grouped by album as they start, so an album shows how much of what was asked for it is
 * done. Nothing is kept once the downloads finish: a finished song shows the usual downloaded icon.
 *
 * Any thread may report; {@link #changes()} is posted on the main thread.
 */
@UnstableApi
public final class DownloadProgress {
    /** Waiting its turn, or running without a known size: a spinning circle. */
    public static final float WAITING = -1f;

    /** A song in the queue. */
    public static final class Item {
        public final Child song;
        public final boolean toFolder;
        /** 0–1, or {@link #WAITING}. */
        public volatile float fraction = WAITING;

        Item(Child song, boolean toFolder) {
            this.song = song;
            this.toFolder = toFolder;
        }
    }

    private static final class AlbumBatch {
        final Set<String> songs = new LinkedHashSet<>();
        final Set<String> done = new HashSet<>();
    }

    private static final long POLL_MS = 500;

    private static final Object lock = new Object();
    private static final Map<String, Item> active = new LinkedHashMap<>();
    private static final Map<String, AlbumBatch> albums = new HashMap<>();
    private static final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int version;
    @Nullable private static Context appContext;
    private static boolean polling;

    private DownloadProgress() {
    }

    /** Bumped whenever a song starts, moves on or finishes. */
    public static LiveData<Integer> changes() {
        return changes;
    }

    /** A song was handed to a download path. */
    public static void started(Context context, Child song, boolean toFolder) {
        if (song == null || song.getId() == null) return;
        synchronized (lock) {
            appContext = context.getApplicationContext();
            active.put(song.getId(), new Item(song, toFolder));
            String albumId = song.getAlbumId();
            if (albumId != null) {
                AlbumBatch batch = albums.get(albumId);
                if (batch == null) albums.put(albumId, batch = new AlbumBatch());
                batch.songs.add(song.getId());
                batch.done.remove(song.getId());
            }
        }
        changed();
        if (!toFolder) main.post(DownloadProgress::startPolling);
    }

    /** How far a folder download has copied, 0–1. */
    public static void progress(String songId, float fraction) {
        Item item;
        synchronized (lock) {
            item = active.get(songId);
        }
        if (item == null) return;
        float rounded = Math.round(fraction * 100) / 100f;
        if (item.fraction == rounded) return;
        item.fraction = rounded;
        changed();
    }

    /** Done, failed or cancelled: either way no longer in the queue. */
    public static void finished(String songId) {
        synchronized (lock) {
            Item item = active.remove(songId);
            if (item == null) return;
            String albumId = item.song.getAlbumId();
            AlbumBatch batch = albumId != null ? albums.get(albumId) : null;
            if (batch != null) {
                batch.done.add(songId);
                boolean anyLeft = false;
                for (String id : batch.songs) {
                    if (active.containsKey(id)) {
                        anyLeft = true;
                        break;
                    }
                }
                if (!anyLeft) albums.remove(albumId);
            }
        }
        changed();
    }

    /** The song's progress, 0–1 or {@link #WAITING}; null when it isn't downloading. */
    @Nullable
    public static Float song(String songId) {
        Item item;
        synchronized (lock) {
            item = active.get(songId);
        }
        return item != null ? item.fraction : null;
    }

    /**
     * The album's progress over the songs asked for it, 0–1 (a song still waiting counts as
     * nothing yet); null when none of its songs are downloading.
     */
    @Nullable
    public static Float album(String albumId) {
        if (albumId == null) return null;
        synchronized (lock) {
            AlbumBatch batch = albums.get(albumId);
            if (batch == null || batch.songs.isEmpty()) return null;
            float sum = batch.done.size();
            for (String id : batch.songs) {
                Item item = active.get(id);
                if (item != null && item.fraction > 0) sum += item.fraction;
            }
            return Math.min(1f, sum / batch.songs.size());
        }
    }

    /** Everything waiting or downloading, in the order asked for. */
    public static List<Item> queue() {
        synchronized (lock) {
            return new ArrayList<>(active.values());
        }
    }

    public static boolean isEmpty() {
        synchronized (lock) {
            return active.isEmpty();
        }
    }

    private static void changed() {
        synchronized (lock) {
            version++;
        }
        changes.postValue(version);
    }

    /**
     * Picks up downloads Media3 is still working through from before the app was last opened, so
     * the queue and circles show them too. Looks the songs up off the main thread.
     */
    public static void resume(Context context) {
        Context app = context.getApplicationContext();
        main.post(() -> {
            List<String> ids = new ArrayList<>();
            for (Download d : DownloadUtil.getDownloadManager(app).getCurrentDownloads()) {
                if (d.state != Download.STATE_COMPLETED && d.state != Download.STATE_FAILED
                        && d.state != Download.STATE_REMOVING && song(d.request.id) == null) ids.add(d.request.id);
            }
            if (ids.isEmpty()) return;
            new Thread(() -> {
                com.eddyizm.tempus.repository.DownloadRepository repository = new com.eddyizm.tempus.repository.DownloadRepository();
                for (String id : ids) {
                    com.eddyizm.tempus.model.Download song = repository.getDownload(id);
                    started(app, song != null ? song : new Child(id), false);
                }
            }, "DownloadProgress.resume").start();
        });
    }

    // ---- The app's own downloads: read from Media3 while any are running ----

    private static void startPolling() {
        if (polling) return;
        polling = true;
        main.post(DownloadProgress::poll);
    }

    private static void poll() {
        Context context;
        List<Item> media3 = new ArrayList<>();
        synchronized (lock) {
            context = appContext;
            for (Item item : active.values()) if (!item.toFolder) media3.add(item);
        }
        if (context == null || media3.isEmpty()) {
            polling = false;
            return;
        }
        DownloadManager manager = DownloadUtil.getDownloadManager(context);
        Map<String, Download> current = new HashMap<>();
        for (Download d : manager.getCurrentDownloads()) current.put(d.request.id, d);
        DownloaderManager tracker = DownloadUtil.getDownloadTracker(context);
        boolean moved = false;
        for (Item item : media3) {
            String id = item.song.getId();
            Download d = current.get(id);
            if (d != null) {
                float f = d.state == Download.STATE_DOWNLOADING && d.getPercentDownloaded() != C.PERCENTAGE_UNSET
                        ? Math.round(d.getPercentDownloaded()) / 100f : WAITING;
                if (d.state == Download.STATE_COMPLETED) {
                    finished(id);
                } else if (f != item.fraction) {
                    item.fraction = f;
                    moved = true;
                }
            } else if (tracker.isDownloaded(id) || !tracker.isRequested(id)) {
                // Finished (or failed, or was removed) between two looks.
                finished(id);
            }
        }
        if (moved) changed();
        main.postDelayed(DownloadProgress::poll, POLL_MS);
    }
}
