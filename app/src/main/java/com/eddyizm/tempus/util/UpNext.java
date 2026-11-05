package com.eddyizm.tempus.util;

import androidx.annotation.MainThread;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;

import com.eddyizm.tempus.lan.LanRemoteSession;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.subsonic.models.Child;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Queue mode. Songs added with Q are remembered here, in the order added. With queue mode on
 * they sit right after the current song, so Next (and the end of a song) plays them before the
 * rest of the album or playlist; turned off, they move to the end and Next follows the album
 * again; turned back on, they move up again. A song leaves the list once it starts playing.
 *
 * Kept for this run of the app only: after a restart the songs are still in the queue, just no
 * longer told apart from the rest.
 */
@OptIn(markerClass = UnstableApi.class)
public final class UpNext {
    private static final Set<String> QUEUED = Collections.synchronizedSet(new LinkedHashSet<>());

    private UpNext() {
    }

    public static int count() {
        return QUEUED.size();
    }

    /** The song started playing: it's no longer waiting. */
    public static void played(String mediaId) {
        if (mediaId != null) QUEUED.remove(mediaId);
    }

    /** Remembers songs added to the end by the regular "add to queue" (queue mode off). */
    public static void remember(List<Child> songs) {
        for (Child song : songs) if (song != null && song.getId() != null) QUEUED.add(song.getId());
    }

    /** Queue mode on: after the current song and the songs queued before these. */
    public static void add(ListenableFuture<MediaBrowser> future, List<Child> songs) {
        if (LanRemoteSession.routeSongs(songs, "next", 0)) return;
        future.addListener(() -> {
            try {
                MediaBrowser browser = future.get();
                int current = browser.getCurrentMediaItemIndex();
                if (current == C.INDEX_UNSET || browser.getMediaItemCount() == 0) {
                    MediaManager.enqueue(future, songs, false);
                    return;
                }
                int pos = current + 1;
                while (pos < browser.getMediaItemCount() && QUEUED.contains(browser.getMediaItemAt(pos).mediaId)) pos++;
                remember(songs);
                MediaManager.insertQueued(browser, songs, pos);
            } catch (Exception ignored) {
                // Browser gone: nothing to add to.
            }
        }, MoreExecutors.directExecutor());
    }

    /** Queue mode switched: the waiting songs move up next (on) or to the end (off). */
    public static void apply(ListenableFuture<MediaBrowser> future, boolean on) {
        if (future == null || LanRemoteSession.isActive()) return;
        future.addListener(() -> {
            try {
                reorder(future.get(), on);
            } catch (Exception ignored) {
                // Browser gone: the order is applied next time the mode changes.
            }
        }, MoreExecutors.directExecutor());
    }

    @MainThread
    private static void reorder(MediaBrowser browser, boolean on) {
        int current = browser.getCurrentMediaItemIndex();
        int count = browser.getMediaItemCount();
        if (current == C.INDEX_UNSET || QUEUED.isEmpty()) return;

        List<String> queued = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (int i = current + 1; i < count; i++) {
            String id = browser.getMediaItemAt(i).mediaId;
            (QUEUED.contains(id) ? queued : rest).add(id);
        }
        if (queued.isEmpty()) return;
        // Up next in the order they were added, not the order they happen to sit in now.
        List<String> addedOrder = new ArrayList<>(QUEUED);
        queued.sort((a, b) -> Integer.compare(addedOrder.indexOf(a), addedOrder.indexOf(b)));

        List<String> wanted = new ArrayList<>(count - current - 1);
        if (on) {
            wanted.addAll(queued);
            wanted.addAll(rest);
        } else {
            wanted.addAll(rest);
            wanted.addAll(queued);
        }
        for (int k = 0; k < wanted.size(); k++) {
            int target = current + 1 + k;
            for (int j = target; j < count; j++) {
                if (wanted.get(k).equals(browser.getMediaItemAt(j).mediaId)) {
                    if (j != target) browser.moveMediaItem(j, target);
                    break;
                }
            }
        }

        List<String> order = new ArrayList<>(count);
        for (int i = 0; i < browser.getMediaItemCount(); i++) order.add(browser.getMediaItemAt(i).mediaId);
        MediaManager.saveOrder(order);
    }
}
