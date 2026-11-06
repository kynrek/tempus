package com.eddyizm.tempus.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import com.eddyizm.tempus.util.Preferences;

/**
 * The playback progress line: the played part as a gentle wave that drifts while playing, or is drawn
 * still and grows with the playhead (Settings > Animate the wave), or a straight line (per Settings);
 * the rest as a thin straight line. Shared by the mini player and the full player so they always match.
 */
public final class ProgressLine {
    public static final String WAVY = "wavy";
    public static final String STRAIGHT = "straight";

    private static final float WAVELENGTH_DP = 32f;   // long, gentle waves
    // One wavelength of drift when animated: about 14 dp a second.
    private static final long WAVE_PERIOD_MS = 2300;
    /**
     * How often a drifting wave is redrawn: 10 a second. The drift is slow (about 1.4 dp a step), and
     * every frame drawn is CPU and compositor time.
     */
    public static final long WAVE_FRAME_MS = 100;

    /** When the next drift frame is due, on a clock every line shares, so they redraw in the same frame. */
    public static long nextWaveFrameAt() {
        long now = SystemClock.uptimeMillis();
        return now - now % WAVE_FRAME_MS + WAVE_FRAME_MS;
    }

    private final float density;
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean wavy;
    private boolean animated;
    private float stroke;

    public ProgressLine(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[]{played, rest}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        reload();
    }

    /** Re-reads the style and thickness settings. */
    public void reload() {
        wavy = !STRAIGHT.equals(Preferences.getProgressStyle());
        animated = Preferences.isProgressAnimated();
        stroke = Preferences.getProgressThicknessDp() * density;
        played.setStrokeWidth(stroke);
        rest.setStrokeWidth(Math.max(density, stroke * 0.6f));
    }

    public void setColors(int playedColor, int restColor) {
        played.setColor(playedColor);
        rest.setColor(restColor);
    }

    /** Overrides the app's line style, for a place with a setting of its own (the home-screen widget). */
    public void setWavy(boolean wavy) {
        this.wavy = wavy;
    }

    public boolean isWavy() {
        return wavy;
    }

    /** Whether a playing line drifts, so its view must keep redrawing (Settings > Animate the wave). */
    public boolean isAnimated() {
        return wavy && animated;
    }

    /** Height the line needs, wave included. */
    public float height() {
        return stroke + (wavy ? 2 * amplitude() : 0);
    }

    private float amplitude() {
        // Low peaks: a little over half the line thickness either side of the centre.
        return Math.max(1.5f * density, stroke * 0.6f);
    }

    /**
     * Draws from {@code left} to {@code right} at {@code cy}, played up to {@code x}. {@code moving} is
     * whether it's playing: the wave drifts then if {@link #isAnimated}; otherwise it holds still.
     */
    public void draw(Canvas canvas, float left, float right, float cy, float x, boolean moving) {
        x = Math.max(left, Math.min(right, x));
        if (x < right) canvas.drawLine(x, cy, right, cy, rest);
        if (x <= left) return;
        if (!wavy) {
            canvas.drawLine(left, cy, x, cy, played);
            return;
        }
        float wavelength = WAVELENGTH_DP * density;
        float phase = moving && animated ? (SystemClock.uptimeMillis() % WAVE_PERIOD_MS) / (float) WAVE_PERIOD_MS : 0f;
        float a = amplitude();
        path.rewind();
        float step = Math.max(1f, density * 1.5f);
        boolean first = true;
        for (float px = left; ; px += step) {
            if (px > x) px = x;
            // Eases in over the first half wave so the line starts on the centre.
            float ease = Math.min(1f, (px - left) / (wavelength / 2));
            float y = cy + (float) (a * ease * Math.sin(2 * Math.PI * ((px - left) / wavelength - phase)));
            if (first) { path.moveTo(px, y); first = false; } else path.lineTo(px, y);
            if (px >= x) break;
        }
        canvas.drawPath(path, played);
    }
}
