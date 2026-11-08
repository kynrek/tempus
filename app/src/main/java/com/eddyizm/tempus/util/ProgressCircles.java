package com.eddyizm.tempus.util;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.service.DownloadProgress;
import com.google.android.material.progressindicator.CircularProgressIndicator;

/** Shows a download's progress on a circle: hidden, spinning while it waits, or filling up. */
@UnstableApi
public final class ProgressCircles {
    private ProgressCircles() {
    }

    /** {@code progress}: null hides it, {@link DownloadProgress#WAITING} spins, 0–1 fills. */
    public static void show(CircularProgressIndicator circle, @Nullable Float progress) {
        if (progress == null) {
            circle.setVisibility(View.GONE);
            return;
        }
        boolean waiting = progress < 0;
        if (circle.isIndeterminate() != waiting) {
            // Switching mode on a showing circle can be refused; hide it for the switch.
            circle.setVisibility(View.GONE);
            circle.setIndeterminate(waiting);
        }
        circle.setMax(100);
        if (!waiting) circle.setProgressCompat(Math.round(progress * 100), circle.getVisibility() == View.VISIBLE);
        circle.setVisibility(View.VISIBLE);
    }
}
