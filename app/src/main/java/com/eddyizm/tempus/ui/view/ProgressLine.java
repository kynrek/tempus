package com.eddyizm.tempus.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import com.eddyizm.tempus.util.Preferences;

/**
 * The playback progress line: the played part as a gentle wave, drawn still and growing with the
 * playhead (or a straight line, per Settings), the rest as a thin straight line. Shared by the mini
 * player and the full player so they always match.
 */
public final class ProgressLine {
    public static final String WAVY = "wavy";
    public static final String STRAIGHT = "straight";

    private static final float WAVELENGTH_DP = 44f;

    private final float density;
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean wavy;
    private boolean waveWholeLine;
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

    public boolean isWavy() {
        return wavy;
    }

    /** Height the line needs, wave included. */
    public float height() {
        return stroke + (wavy ? 2 * amplitude() : 0);
    }

    private float amplitude() {
        return Math.max(1.5f * density, stroke * 0.6f);
    }

    /** Whether the unplayed part waves too while playing (the narrow side panel's line), not just the played part. */
    public void setWaveWholeLine(boolean waveWholeLine) {
        this.waveWholeLine = waveWholeLine;
    }

    /**
     * Draws from {@code left} to {@code right} at {@code cy}, played up to {@code x}.
     * {@code moving} is whether it's playing: see {@link #setWaveWholeLine}. The wave itself never moves.
     */
    public void draw(Canvas canvas, float left, float right, float cy, float x, boolean moving) {
        x = Math.max(left, Math.min(right, x));
        boolean waveRest = wavy && waveWholeLine && moving;
        if (x < right) {
            if (waveRest) canvas.drawPath(wave(left, x, right, cy), rest);
            else canvas.drawLine(x, cy, right, cy, rest);
        }
        if (x <= left) return;
        if (!wavy) {
            canvas.drawLine(left, cy, x, cy, played);
            return;
        }
        canvas.drawPath(wave(left, left, x, cy), played);
    }

    /** The wave between {@code from} and {@code to}, in phase with one starting at {@code left}. */
    private Path wave(float left, float from, float to, float cy) {
        // The same waves on every line, the side panel's included, so they all look alike.
        float wavelength = WAVELENGTH_DP * density;
        float a = amplitude();
        path.rewind();
        float step = Math.max(1f, density * 1.5f);
        boolean first = true;
        for (float px = from; ; px += step) {
            if (px > to) px = to;
            // Eases in over the first quarter wave so the line starts on the centre.
            float ease = Math.min(1f, (px - left) / (wavelength / 4));
            float y = cy + (float) (a * ease * Math.sin(2 * Math.PI * ((px - left) / wavelength)));
            if (first) { path.moveTo(px, y); first = false; } else path.lineTo(px, y);
            if (px >= to) break;
        }
        return path;
    }
}
