package com.eddyizm.tempus.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import com.eddyizm.tempus.util.Preferences;

/**
 * The playback progress line: the played part as a gentle wave, drawn still and redrawn only as the
 * playhead moves (or a straight line, per Settings), the rest as a thin straight line. Shared by the mini player and
 * the full player so they always match.
 */
public final class ProgressLine {
    public static final String WAVY = "wavy";
    public static final String STRAIGHT = "straight";

    private static final float WAVELENGTH_DP = 32f;   // long, gentle waves

    private final float density;
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean wavy;
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

    /** Height the line needs, wave included. */
    public float height() {
        return stroke + (wavy ? 2 * amplitude() : 0);
    }

    private float amplitude() {
        // Low peaks: a little over half the line thickness either side of the centre.
        return Math.max(1.5f * density, stroke * 0.6f);
    }

    /**
     * Draws from {@code left} to {@code right} at {@code cy}, played up to {@code x}. The wave is fixed
     * to the line (it doesn't drift), so nothing redraws it between playhead moves.
     */
    public void draw(Canvas canvas, float left, float right, float cy, float x) {
        x = Math.max(left, Math.min(right, x));
        if (x < right) canvas.drawLine(x, cy, right, cy, rest);
        if (x <= left) return;
        if (!wavy) {
            canvas.drawLine(left, cy, x, cy, played);
            return;
        }
        float wavelength = WAVELENGTH_DP * density;
        float a = amplitude();
        path.rewind();
        float step = Math.max(1f, density * 1.5f);
        boolean first = true;
        for (float px = left; ; px += step) {
            if (px > x) px = x;
            // Eases in over the first half wave so the line starts on the centre.
            float ease = Math.min(1f, (px - left) / (wavelength / 2));
            float y = cy + (float) (a * ease * Math.sin(2 * Math.PI * ((px - left) / wavelength)));
            if (first) { path.moveTo(px, y); first = false; } else path.lineTo(px, y);
            if (px >= x) break;
        }
        canvas.drawPath(path, played);
    }
}
