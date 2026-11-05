package com.eddyizm.tempus.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.subsonic.models.Child;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.List;

/**
 * "Add to queue" everywhere: puts the songs at the bottom of the queue, at the top (next up), or asks,
 * per Settings > Queue. "Play next" menu items keep inserting next on their own.
 */
@UnstableApi
public final class QueueAdder {
    public static final String BOTTOM = "bottom";
    public static final String TOP = "top";
    public static final String ASK = "ask";

    private QueueAdder() {
    }

    public static void add(Context context, ListenableFuture<MediaBrowser> browser, Child song) {
        add(context, browser, song, null);
    }

    public static void add(Context context, ListenableFuture<MediaBrowser> browser, Child song, @Nullable Runnable onAdded) {
        if (song != null) add(context, browser, Collections.singletonList(song), onAdded);
    }

    public static void add(Context context, ListenableFuture<MediaBrowser> browser, List<Child> songs) {
        add(context, browser, songs, null);
    }

    /** {@code onAdded} runs once the songs are queued (after the choice when asking; not on Cancel). */
    public static void add(Context context, ListenableFuture<MediaBrowser> browser, List<Child> songs, @Nullable Runnable onAdded) {
        if (browser == null || songs == null || songs.isEmpty()) return;
        String where = Preferences.getQueueAddPosition();
        Activity activity = ASK.equals(where) ? activityOf(context) : null;
        if (activity == null || activity.isFinishing()) {
            MediaManager.enqueueAsQueued(browser, songs, TOP.equals(where));
            if (onAdded != null) onAdded.run();
            return;
        }
        CharSequence[] choices = {
                activity.getString(R.string.queue_add_top),
                activity.getString(R.string.queue_add_bottom)
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getResources().getQuantityString(R.plurals.queue_add_ask_title, songs.size(), songs.size()))
                .setItems(choices, (dialog, which) -> {
                    MediaManager.enqueueAsQueued(browser, songs, which == 0);
                    if (onAdded != null) onAdded.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Nullable
    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
