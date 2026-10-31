package com.eddyizm.tempus.helper.recyclerview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.util.AlphabetSort;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.color.MaterialColors;

import java.util.Arrays;

/**
 * Vertical "# A–Z" strip for jumping through a sorted list, sized for a car screen. Dragging a
 * finger over the strip scrolls the list to that letter, magnifies the letters around the finger
 * and shows the current letter in a large bubble beside it.
 *
 * <p>The view is meant to be laid over the list (wider than the strip, so the bubble has room).
 * Touches outside the strip, or in the top/bottom padding, fall through to the views below; the
 * padding is how callers keep the letters clear of a collapsing header or the mini player.
 */
public class AlphabetScrubber extends View {
    public interface SectionProvider {
        /** False when the list isn't in name order, which hides the scrubber. */
        boolean isAlphabetical();

        /** The name the row at {@code position} is filed under (see {@link AlphabetSort}). */
        @Nullable
        String getSectionName(int position);
    }

    private static final String[] LETTERS = {
            AlphabetSort.OTHER, "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};
    private static final float MAX_LETTER_SCALE = 1.9f;
    private static final float MAGNIFY_SPREAD_CELLS = 3.5f;

    private final int[] firstPosition = new int[LETTERS.length];
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private final float stripWidth = dp(52);
    private final float maxLetterSize = sp(17);
    private final float magnifyShift = dp(30);
    private final float bubbleRadius = dp(46);
    private final float bubbleGap = dp(40);

    private final int letterColor;
    private final int activeLetterColor;
    private final int trackColor;

    private RecyclerView recyclerView;
    private SectionProvider provider;
    private int listPaddingEnd;
    private final RecyclerView.AdapterDataObserver dataObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            rebuildIndex();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            rebuildIndex();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            rebuildIndex();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            rebuildIndex();
        }
    };

    private boolean touching;
    private float touchY;
    private int currentLetter = -1;
    /** 0 → 1 while a finger is down; drives the bubble size and the magnification. */
    private float press;
    /** Brief extra growth of the bubble each time the letter changes. */
    private float pop;
    private ValueAnimator pressAnimator;
    private ValueAnimator popAnimator;

    public AlphabetScrubber(Context context) {
        this(context, null);
    }

    public AlphabetScrubber(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        int onPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary);
        int container = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest, onSurface);

        letterColor = ColorUtils.setAlphaComponent(onSurface, 0xCC);
        activeLetterColor = primary;
        trackColor = container;

        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bubblePaint.setColor(primary);
        bubblePaint.setShadowLayer(dp(6), 0, dp(2), 0x66000000);
        bubbleTextPaint.setColor(onPrimary);
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleTextPaint.setTextSize(sp(46));

        Arrays.fill(firstPosition, -1);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // for the bubble's shadow
        setContentDescription("A to Z index");
    }

    public void attach(@NonNull RecyclerView recyclerView, @NonNull SectionProvider provider) {
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) throw new IllegalStateException("Set the adapter before attaching the scrubber");
        this.recyclerView = recyclerView;
        this.provider = provider;
        listPaddingEnd = recyclerView.getPaddingEnd();
        adapter.registerAdapterDataObserver(dataObserver);
        rebuildIndex();
    }

    /** Keeps the letters below the visible part of a collapsing header. */
    public void keepBelow(@NonNull AppBarLayout appBarLayout) {
        appBarLayout.addOnOffsetChangedListener((bar, verticalOffset) -> setPadding(
                getPaddingLeft(), bar.getHeight() + verticalOffset + (int) dp(4), getPaddingRight(), getPaddingBottom()));
    }

    public void detach() {
        if (recyclerView != null && recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().unregisterAdapterDataObserver(dataObserver);
        }
        recyclerView = null;
        provider = null;
    }

    /** Re-reads the section of every row; call after the sort order changes without a notify. */
    public void rebuildIndex() {
        Arrays.fill(firstPosition, -1);
        int letters = 0;

        if (recyclerView != null && provider != null && provider.isAlphabetical()) {
            int count = recyclerView.getAdapter() != null ? recyclerView.getAdapter().getItemCount() : 0;
            for (int position = 0; position < count; position++) {
                int letter = letterIndex(AlphabetSort.indexLetter(provider.getSectionName(position)));
                if (firstPosition[letter] == -1) {
                    firstPosition[letter] = position;
                    letters++;
                }
            }
        }

        // One letter's worth of rows doesn't need an index.
        boolean visible = letters >= 2;
        setVisibility(visible ? VISIBLE : GONE);
        if (recyclerView != null) {
            // Keep the rows clear of the strip so they stay tappable along the right edge.
            int paddingEnd = visible ? Math.max(listPaddingEnd, (int) (stripWidth + getPaddingRight())) : listPaddingEnd;
            if (paddingEnd != recyclerView.getPaddingEnd()) {
                recyclerView.setPaddingRelative(recyclerView.getPaddingStart(), recyclerView.getPaddingTop(),
                        paddingEnd, recyclerView.getPaddingBottom());
            }
        }
        invalidate();
    }

    private static int letterIndex(String letter) {
        for (int i = 0; i < LETTERS.length; i++) {
            if (LETTERS[i].equals(letter)) return i;
        }
        return 0;
    }

    private float stripLeft() {
        return getWidth() - getPaddingRight() - stripWidth;
    }

    private float cellHeight() {
        return (getHeight() - getPaddingTop() - getPaddingBottom()) / (float) LETTERS.length;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float cell = cellHeight();
        if (cell <= 0) return;

        float top = getPaddingTop();
        float left = stripLeft();
        float centerX = left + stripWidth / 2f;

        trackRect.set(left + dp(6), top, left + stripWidth - dp(6), top + cell * LETTERS.length);
        trackPaint.setColor(ColorUtils.setAlphaComponent(trackColor, (int) (0x99 + 0x66 * press)));
        canvas.drawRoundRect(trackRect, stripWidth / 2f, stripWidth / 2f, trackPaint);

        float baseSize = Math.min(cell * 0.8f, maxLetterSize);

        for (int i = 0; i < LETTERS.length; i++) {
            float y = top + cell * (i + 0.5f);
            float magnify = 0f;
            if (press > 0f) {
                float t = Math.max(0f, 1f - Math.abs(y - touchY) / (cell * MAGNIFY_SPREAD_CELLS));
                magnify = press * t * t * (3f - 2f * t);
            }

            letterPaint.setTextSize(baseSize * (1f + (MAX_LETTER_SCALE - 1f) * magnify));
            int color = i == currentLetter && touching ? activeLetterColor : letterColor;
            int alpha = firstPosition[i] == -1 ? 0x55 : 0xFF;
            letterPaint.setColor(ColorUtils.setAlphaComponent(color, Color.alpha(color) * alpha / 0xFF));

            float baseline = y - (letterPaint.descent() + letterPaint.ascent()) / 2f;
            canvas.drawText(LETTERS[i], centerX - magnifyShift * magnify, baseline, letterPaint);
        }

        if (press > 0f && currentLetter >= 0) {
            float radius = bubbleRadius * press * (1f + 0.12f * pop);
            float bubbleX = left - bubbleGap - bubbleRadius;
            float bubbleY = Math.max(bubbleRadius, Math.min(getHeight() - bubbleRadius, touchY));
            canvas.drawCircle(bubbleX, bubbleY, radius, bubblePaint);

            bubbleTextPaint.setTextSize(sp(46) * press * (1f + 0.12f * pop));
            float baseline = bubbleY - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f;
            canvas.drawText(LETTERS[currentLetter], bubbleX, baseline, bubbleTextPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getX() < stripLeft() - dp(8)
                        || event.getY() < getPaddingTop()
                        || event.getY() > getHeight() - getPaddingBottom()) {
                    return false;
                }
                touching = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (recyclerView != null) recyclerView.stopScroll();
                animatePress(1f);
                onFingerAt(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touching) return false;
                onFingerAt(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!touching) return false;
                touching = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                animatePress(0f);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void onFingerAt(float y) {
        touchY = y;
        float cell = cellHeight();
        int letter = (int) ((y - getPaddingTop()) / cell);
        letter = Math.max(0, Math.min(LETTERS.length - 1, letter));

        if (letter != currentLetter) {
            currentLetter = letter;
            int position = positionFor(letter);
            if (position >= 0) scrollTo(position);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            animatePop();
        }
        invalidate();
    }

    /** First row of the letter, or of the nearest following letter that has rows. */
    private int positionFor(int letter) {
        for (int i = letter; i < LETTERS.length; i++) {
            if (firstPosition[i] != -1) return firstPosition[i];
        }
        for (int i = letter - 1; i >= 0; i--) {
            if (firstPosition[i] != -1) return firstPosition[i];
        }
        return -1;
    }

    private void scrollTo(int position) {
        if (recyclerView == null) return;
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, 0);
        } else {
            recyclerView.scrollToPosition(position);
        }
    }

    private void animatePress(float target) {
        if (pressAnimator != null) pressAnimator.cancel();
        pressAnimator = ValueAnimator.ofFloat(press, target);
        pressAnimator.setDuration(target > press ? 180 : 260);
        pressAnimator.setInterpolator(target > press ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator());
        pressAnimator.addUpdateListener(a -> {
            press = Math.max(0f, (float) a.getAnimatedValue());
            invalidate();
        });
        pressAnimator.start();
    }

    private void animatePop() {
        if (popAnimator != null) popAnimator.cancel();
        popAnimator = ValueAnimator.ofFloat(1f, 0f);
        popAnimator.setDuration(160);
        popAnimator.addUpdateListener(a -> {
            pop = (float) a.getAnimatedValue();
            invalidate();
        });
        popAnimator.start();
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
