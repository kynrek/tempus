package com.eddyizm.tempus.ui.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.TimeBar;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The full player's time bar, drawn with {@link ProgressLine} (wavy or straight, per Settings) and
 * a round scrubber. Drop-in for Media3's DefaultTimeBar at {@code @id/exo_progress}.
 */
@UnstableApi
public class ProgressLineTimeBar extends View implements TimeBar {
    private static final long MOVING_GRACE_MS = 1500;   // position updates this recent count as playing

    private final ProgressLine line;
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final CopyOnWriteArraySet<OnScrubListener> listeners = new CopyOnWriteArraySet<>();
    private final float density;

    private long duration = C.TIME_UNSET;
    private long position;
    private long lastAdvanceAt;
    private boolean scrubbing;
    private long scrubPosition;

    public ProgressLineTimeBar(Context context) {
        this(context, null);
    }

    public ProgressLineTimeBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        line = new ProgressLine(context);
        int played = themeColor(com.google.android.material.R.attr.colorPrimary, 0xFF6FD3C0);
        int rest = (themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888) & 0x00FFFFFF) | 0x66000000;
        line.setColors(played, rest);
        thumb.setColor(played);
        setFocusable(true);
    }

    private int themeColor(int attr, int fallback) {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int wanted = (int) Math.ceil(Math.max(line.height(), 16 * density) + 12 * density);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(wanted + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            line.reload();   // settings may have changed meanwhile
            requestLayout();
        }
    }

    private float left() { return getPaddingLeft() + 8 * density; }

    private float right() { return getWidth() - getPaddingRight() - 8 * density; }

    private float xFor(long pos) {
        if (duration <= 0 || duration == C.TIME_UNSET) return left();
        return left() + (right() - left()) * Math.min(1f, pos / (float) duration);
    }

    private long positionAt(float x) {
        if (duration <= 0 || duration == C.TIME_UNSET) return 0;
        float f = (x - left()) / Math.max(1f, right() - left());
        return (long) (Math.max(0f, Math.min(1f, f)) * duration);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
        long shown = scrubbing ? scrubPosition : position;
        boolean moving = !scrubbing && SystemClock.uptimeMillis() - lastAdvanceAt < MOVING_GRACE_MS;
        float x = xFor(shown);
        line.draw(canvas, left(), right(), cy, x, moving);
        if (isEnabled()) canvas.drawCircle(x, cy, (scrubbing ? 8 : 6) * density, thumb);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled() || duration <= 0 || duration == C.TIME_UNSET) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                scrubbing = true;
                scrubPosition = positionAt(e.getX());
                for (OnScrubListener l : listeners) l.onScrubStart(this, scrubPosition);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scrubbing) return false;
                scrubPosition = positionAt(e.getX());
                for (OnScrubListener l : listeners) l.onScrubMove(this, scrubPosition);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!scrubbing) return false;
                scrubbing = false;
                boolean canceled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                position = scrubPosition;
                for (OnScrubListener l : listeners) l.onScrubStop(this, scrubPosition, canceled);
                invalidate();
                return true;
            default:
                return false;
        }
    }

    // ---- TimeBar ----

    @Override public void addListener(@NonNull OnScrubListener listener) { listeners.add(listener); }

    @Override public void removeListener(@NonNull OnScrubListener listener) { listeners.remove(listener); }

    @Override public void setKeyTimeIncrement(long time) { }

    @Override public void setKeyCountIncrement(int count) { }

    @Override
    public void setPosition(long position) {
        if (position != this.position) {
            if (position > this.position && position - this.position < 5000) lastAdvanceAt = SystemClock.uptimeMillis();
            this.position = position;
            invalidate();
        }
    }

    @Override public void setBufferedPosition(long bufferedPosition) { }

    @Override
    public void setDuration(long duration) {
        if (duration != this.duration) {
            this.duration = duration;
            invalidate();
        }
    }

    @Override
    public long getPreferredUpdateDelay() {
        float width = right() - left();
        if (duration <= 0 || duration == C.TIME_UNSET || width <= 0) return Long.MAX_VALUE;
        return Math.max(100, (long) (duration / width));
    }

    @Override public void setAdGroupTimesMs(@Nullable long[] adGroupTimesMs, @Nullable boolean[] playedAdGroups, int adGroupCount) { }
}
