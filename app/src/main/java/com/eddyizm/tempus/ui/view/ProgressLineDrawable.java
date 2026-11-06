package com.eddyizm.tempus.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A SeekBar progress drawable that draws {@link ProgressLine}; the bar's level is the progress. */
public final class ProgressLineDrawable extends Drawable {
    private final ProgressLine line;
    private boolean playing;
    private final Runnable tick = this::invalidateSelf;
    private final Rect visible = new Rect();

    public ProgressLineDrawable(Context context, int playedColor, int restColor) {
        line = new ProgressLine(context);
        line.setColors(playedColor, restColor);
    }

    /** Drift the wave while playing, hold still when paused. */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        invalidateSelf();
    }

    public void setColors(int playedColor, int restColor) {
        line.setColors(playedColor, restColor);
        invalidateSelf();
    }

    public void reload() {
        line.reload();
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        float x = b.left + b.width() * (getLevel() / 10000f);
        line.draw(canvas, b.left, b.right, b.exactCenterY(), x, playing);
        if (playing && line.isWavy() && onScreen()) {
            unscheduleSelf(tick);
            scheduleSelf(tick, ProgressLine.nextWaveFrameAt());
        }
    }

    /** Whether the view drawing this is showing; off screen, the drift waits for the next progress update. */
    private boolean onScreen() {
        Callback cb = getCallback();
        if (cb == null) return false;
        return !(cb instanceof View) || ((View) cb).getGlobalVisibleRect(visible);
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) Math.ceil(line.height());
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
