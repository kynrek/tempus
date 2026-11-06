package com.eddyizm.tempus.util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.util.ArrayDeque;

/**
 * Holds work that can wait until the app has drawn its first frame, then runs it one item per
 * frame. The library index uses it so that lists read from disk during start-up are laid out
 * after the window is on screen, not inside the first frame, and the visible tab before the
 * pages beside it.
 */
@MainThread
public final class FirstFrame {
    /** If no frame is drawn by then (started in the background), run the work anyway. */
    private static final long FALLBACK_MS = 2000;

    private static final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean drawn;
    private static boolean draining;
    private static boolean fallbackArmed;

    private FirstFrame() {
    }

    /** Runs {@code work} now if the first frame is out, otherwise queues it. */
    public static void run(@NonNull Runnable work) {
        if (drawn && !draining) {
            work.run();
            return;
        }
        waiting.add(work);
        if (!fallbackArmed) {
            fallbackArmed = true;
            MAIN.postDelayed(FirstFrame::release, FALLBACK_MS);
        }
        if (drawn) drain();
    }

    /** Call from the activity's onCreate: the first draw of its window releases the queue. */
    public static void watch(@NonNull Activity activity) {
        if (drawn) return;
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver.OnDrawListener[] listener = new ViewTreeObserver.OnDrawListener[1];
        listener[0] = () -> MAIN.postAtFrontOfQueue(() -> {
            if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnDrawListener(listener[0]);
            release();
        });
        decor.getViewTreeObserver().addOnDrawListener(listener[0]);
    }

    private static void release() {
        if (drawn) return;
        drawn = true;
        drain();
    }

    /** One queued item per frame, so each list gets its own layout pass. */
    private static void drain() {
        if (draining) return;
        draining = true;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                Runnable next = waiting.poll();
                if (next != null) next.run();
                if (waiting.isEmpty()) {
                    draining = false;
                } else {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        });
    }
}
