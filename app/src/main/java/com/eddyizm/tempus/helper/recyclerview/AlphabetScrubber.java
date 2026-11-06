package com.eddyizm.tempus.helper.recyclerview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
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

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.eddyizm.tempus.R;
import com.google.android.material.color.MaterialColors;

import java.util.Collections;
import java.util.List;

/**
 * Vertical index strip for jumping through a sorted list, sized for a car screen: "# A–Z" for a
 * list in name order, years or months for one in date order (see {@link ScrubberSections}).
 * Dragging a finger over the strip scrolls the list to that section, magnifies the labels around
 * the finger and shows the current section in a large bubble beside it. Holding still on a month
 * zooms the strip into that month's days.
 *
 * <p>The view is meant to be laid over the list (wider than the strip, so the bubble has room).
 * Touches outside the strip, or in the top/bottom padding, fall through to the views below. The
 * labels stop above the mini player (R.id.player_bottom_sheet) when it is showing.
 */
public class AlphabetScrubber extends View {
    public interface SectionProvider {
        /**
         * The sections of the rows as they are now, or null for an order that has none (random,
         * most played), which hides the scrubber. Called once each time the adapter's data
         * changes, never while drawing or scrubbing.
         */
        @Nullable
        ScrubberSections buildSections();
    }

    private static final float MAX_LETTER_SCALE = 3.2f;
    private static final float MAGNIFY_SPREAD_CELLS = 4.5f;
    /** Most a crowded strip (70 years, years of months) magnifies by to reach a legible size. */
    private static final float MAX_DENSE_SCALE = 12f;
    private static final long HOLD_TO_ZOOM_MS = 500;
    /** The hold only starts to show in the bubble after this, so passing over a month stays calm. */
    private static final long HOLD_HINT_DELAY_MS = 120;

    /** One column of labels: the sections, or the days of the month zoomed into. */
    private static final class Level {
        List<ScrubberSections.Section> sections = Collections.emptyList();
        float[] scale = new float[0];
        float[] y = new float[0];
        float[] size = new float[0];
        boolean[] label = new boolean[0];
        @Nullable
        boolean[] rest;
        /** Cell height {@link #rest} was worked out for. */
        float restCell = -1f;

        void set(List<ScrubberSections.Section> sections) {
            this.sections = sections;
            int n = sections.size();
            if (scale.length < n) {
                scale = new float[n];
                y = new float[n];
                size = new float[n];
                label = new boolean[n];
            }
            restCell = -1f;
        }

        int count() {
            return sections.size();
        }
    }

    private final Level topLevel = new Level();
    private final Level dayLevel = new Level();
    @Nullable
    private ScrubberSections sections;
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleDetailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF bubbleRect = new RectF();
    private final RectF bandRect = new RectF();
    private final RectF shadeRect = new RectF();
    private final int[] location = new int[2];
    private final int[] playerLocation = new int[2];
    private float lastVisibleBottom = -1f;
    /** The mini player, found once rather than searched for through the whole window on every draw. */
    @Nullable
    private View player;
    /**
     * Redraws when the mini player appears, hides or resizes, since that moves the bottom edge.
     * Only then: global layouts also fire for every row bound while the list scrolls.
     */
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
        float bottom = visibleBottom();
        if (bottom != lastVisibleBottom) {
            lastVisibleBottom = bottom;
            invalidate();
        }
    };

    private final float stripWidth = dp(52);
    private final float maxLetterSize = sp(17);
    private final float magnifyShift = dp(44);
    private final float bubbleRadius = dp(46);
    private final float bubbleGap = dp(56);
    /** How far left of the strip the panel behind the magnified labels reaches, up to the bubble. */
    private final float shadeReach = dp(54);
    /** Years and months: the smallest label drawn, and the size at rest when they don't all fit. */
    private final float minLabelSize = sp(10);
    private final float restLabelSize = sp(12);
    private final float maxMagnifiedSize = sp(22);
    /** Room a crowded strip opens up for the label under the finger. */
    private final float magnifiedSlot = dp(26);
    private final float restLabelWidth = dp(42);
    private final float magnifiedLabelWidth = dp(64);
    /** Zoomed into a month: the room per day, and how far past the days the finger goes to zoom out. */
    private final float dayCellHeight = dp(30);
    private final float zoomExitMargin = dp(36);
    private final float holdSlop = dp(8);

    private final int letterColor;
    private final int activeLetterColor;
    private final int trackColor;
    private final int bandColor;
    private final int surfaceColor;

    private RecyclerView recyclerView;
    private SectionProvider provider;
    private int listPaddingEnd;
    @Nullable
    private Runnable onScrubStart;
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

    /**
     * Covers only load where the list comes to rest: none start for the rows flashing past
     * during a fling or a scrub, so a jump across the library doesn't queue hundreds of them.
     */
    private final RecyclerView.OnScrollListener pauseImagesWhileFlinging = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
            if (!touching) setImagesPaused(newState == RecyclerView.SCROLL_STATE_SETTLING);
        }
    };
    private boolean imagesPaused;

    private void setImagesPaused(boolean paused) {
        if (paused == imagesPaused || getContext() == null) return;
        imagesPaused = paused;
        // Glide refuses a destroyed activity, and its requests are being cleared anyway.
        Activity activity = findActivity(getContext());
        if (activity != null && activity.isDestroyed()) return;
        RequestManager glide = Glide.with(getContext());
        if (paused) glide.pauseRequests();
        else glide.resumeRequests();
    }

    /**
     * While scrubbing, covers load once the list rests under the finger: this long after the last
     * jump. Racing through letters they stay held back, so the rows flashing past queue nothing.
     */
    private static final long IMAGES_AFTER_REST_MS = 220;
    /** Letters changing slower than this are read as browsing, not racing: covers keep loading. */
    private static final long SLOW_SCRUB_MS = 350;
    private long lastScrubJumpAt;
    private final Runnable showImagesAtRest = () -> {
        if (this.touching) setImagesPaused(false);
    };

    /** The scrub moved the list: hold covers back if it's racing, and load them once it rests. */
    private void onScrubJump() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScrubJumpAt < SLOW_SCRUB_MS) setImagesPaused(true);
        lastScrubJumpAt = now;
        removeCallbacks(showImagesAtRest);
        postDelayed(showImagesAtRest, IMAGES_AFTER_REST_MS);
    }

    private boolean touching;
    private float touchY;
    private int currentLetter = -1;
    /** 0 → 1 while a finger is down; drives the bubble size and the magnification. */
    private float press;
    /** Brief extra growth of the bubble each time the letter changes. */
    private float pop;
    private ValueAnimator pressAnimator;
    private ValueAnimator popAnimator;

    /** Zoomed into the days of section {@link #currentLetter}; stays true while the strip fades after a lift. */
    private boolean zoomed;
    private int currentDay = -1;
    /** 0 → 1 as the days unfold from where the finger was held. */
    private float zoom;
    private ValueAnimator zoomAnimator;
    private float bandTop;
    private float bandBottom;
    private float zoomOriginY;
    private float holdStartY;
    /** When the finger came to rest on a month, or -1 when it isn't resting. */
    private long holdStartTime = -1;
    private final Runnable zoomOnHold = () -> {
        holdStartTime = -1;
        zoomIn();
    };

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
        bandColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, container);
        surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE);

        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bubblePaint.setColor(primary);
        bubblePaint.setShadowLayer(dp(6), 0, dp(2), 0x66000000);
        bubbleTextPaint.setColor(onPrimary);
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bubbleTextPaint.setTextSize(sp(46));
        bubbleDetailPaint.setColor(ColorUtils.setAlphaComponent(onPrimary, 0xD9));
        bubbleDetailPaint.setTextAlign(Paint.Align.CENTER);
        holdPaint.setColor(ColorUtils.setAlphaComponent(onPrimary, 0xCC));
        holdPaint.setStrokeWidth(dp(3));
        holdPaint.setStrokeCap(Paint.Cap.ROUND);
        // The bubble's shadow needs a software layer before Android 9; since then the GPU draws
        // it, and a full-height software layer would be re-rendered and uploaded on every redraw.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription("A to Z index");
    }

    public void attach(@NonNull RecyclerView recyclerView, @NonNull SectionProvider provider) {
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) throw new IllegalStateException("Set the adapter before attaching the scrubber");
        this.recyclerView = recyclerView;
        this.provider = provider;
        listPaddingEnd = recyclerView.getPaddingEnd();
        adapter.registerAdapterDataObserver(dataObserver);
        recyclerView.addOnScrollListener(pauseImagesWhileFlinging);
        rebuildIndex();
    }

    /** Runs when a finger lands on the strip, e.g. to collapse a header and show more rows. */
    public void setOnScrubStartListener(@Nullable Runnable listener) {
        onScrubStart = listener;
    }

    public void detach() {
        if (recyclerView != null && recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().unregisterAdapterDataObserver(dataObserver);
        }
        if (recyclerView != null) {
            recyclerView.removeOnScrollListener(pauseImagesWhileFlinging);
            recyclerView.removeCallbacks(applyPendingScroll);
        }
        pendingScroll = RecyclerView.NO_POSITION;
        setImagesPaused(false);
        cancelHold();
        resetZoom();
        recyclerView = null;
        provider = null;
        onScrubStart = null;
    }

    /** Re-reads the section of every row; call after the sort order changes without a notify. */
    public void rebuildIndex() {
        sections = recyclerView != null && provider != null ? provider.buildSections() : null;
        topLevel.set(sections != null ? sections.sections : Collections.emptyList());
        if (currentLetter >= topLevel.count()) currentLetter = -1;
        // The days zoomed into belong to the old rows.
        if (zoomed || zoom > 0f) resetZoom();

        // One section's worth of rows doesn't need an index.
        boolean visible = sections != null && sections.filledCount() >= 2;
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

    private float stripLeft() {
        return getWidth() - getPaddingRight() - stripWidth;
    }

    /**
     * Bottom of the on-screen part of the view. Inside a pager under a scrolling toolbar the view
     * can be laid out taller than the window, and letters past the edge would be unreachable.
     */
    private float visibleBottom() {
        getLocationInWindow(location);
        float bottom = Math.min(getHeight(), getRootView().getHeight() - location[1]);
        // Stop above the mini player, whatever its height (it grows with the timeline and insets).
        if (player == null || !player.isAttachedToWindow()) player = getRootView().findViewById(R.id.player_bottom_sheet);
        if (player != null && player.isShown()) {
            player.getLocationInWindow(playerLocation);
            if (playerLocation[1] > location[1]) bottom = Math.min(bottom, playerLocation[1] - location[1]);
        }
        return bottom;
    }

    private float lettersBottom() {
        return visibleBottom() - getPaddingBottom();
    }

    private float cellHeight() {
        return (lettersBottom() - getPaddingTop()) / topLevel.count();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        cancelHold();
        // Leaving mid-scrub: don't leave the app's covers held back.
        removeCallbacks(showImagesAtRest);
        touching = false;
        setImagesPaused(false);
        player = null;
        lastVisibleBottom = -1f;
        super.onDetachedFromWindow();
    }

    @Nullable
    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        int n = topLevel.count();
        if (n == 0 || sections == null) return;
        float cell = cellHeight();
        if (cell <= 0) return;

        float top = getPaddingTop();
        float left = stripLeft();
        float centerX = left + stripWidth / 2f;

        if (press > 0f) {
            // Magnified labels reach out over the list; a panel in the list's own colour keeps
            // row text and ⋮ buttons from showing through them.
            shadeRect.set(left - shadeReach, top - dp(6), left + stripWidth, top + cell * n + dp(6));
            shadePaint.setColor(withAlpha(surfaceColor, 0.94f * Math.min(1f, press)));
            canvas.drawRoundRect(shadeRect, dp(20), dp(20), shadePaint);
        }

        trackRect.set(left + dp(6), top, left + stripWidth - dp(6), top + cell * n);
        // press overshoots past 1 as the finger lands (OvershootInterpolator); an alpha over 255 throws.
        trackPaint.setColor(ColorUtils.setAlphaComponent(trackColor, (int) (0x99 + 0x66 * Math.min(1f, press))));
        canvas.drawRoundRect(trackRect, stripWidth / 2f, stripWidth / 2f, trackPaint);

        if (sections.fixedLabels) {
            drawLetters(canvas, top, cell, centerX);
        } else {
            // Zoomed in, the months stay dimly in view around the days as the way back out.
            drawLabels(canvas, topLevel, top, top + cell * n, currentLetter, 1f - 0.65f * zoom, centerX, 0f, 1f);
            if (zoom > 0f && dayLevel.count() > 0) {
                float bandStart = zoomOriginY + (bandTop - zoomOriginY) * zoom;
                float bandEnd = zoomOriginY + (bandBottom - zoomOriginY) * zoom;
                bandRect.set(left + dp(3), bandStart - dp(6), left + stripWidth - dp(3), bandEnd + dp(6));
                bandPaint.setColor(withAlpha(bandColor, zoom));
                canvas.drawRoundRect(bandRect, dp(18), dp(18), bandPaint);
                drawLabels(canvas, dayLevel, bandTop, bandBottom, currentDay, zoom, centerX, zoomOriginY, zoom);
            }
        }

        if (press > 0f && currentLetter >= 0 && currentLetter < n) {
            boolean showDay = zoomed && currentDay >= 0 && currentDay < dayLevel.count();
            ScrubberSections.Section shown = showDay ? dayLevel.sections.get(currentDay) : topLevel.sections.get(currentLetter);
            drawBubble(canvas, shown, left, !showDay && !shown.days.isEmpty() ? holdProgress() : -1f);
        }
    }

    /** The A–Z strip, drawn as it always has been: every letter, the empty ones faded. */
    private void drawLetters(Canvas canvas, float top, float cell, float centerX) {
        Level level = topLevel;
        int n = level.count();
        float baseSize = Math.min(cell * 0.8f, maxLetterSize);
        layout(level, top, top + cell * n, currentLetter, MAX_LETTER_SCALE);

        for (int i = 0; i < n; i++) {
            float magnify = (level.scale[i] - 1f) / (MAX_LETTER_SCALE - 1f);
            letterPaint.setTextSize(Math.min(baseSize * level.scale[i], Math.max(baseSize * 0.6f, slot(level, i) * 1.1f)));
            int color = i == currentLetter && touching ? activeLetterColor : letterColor;
            int alpha = level.sections.get(i).firstPosition == -1 ? 0x55 : 0xFF;
            letterPaint.setColor(ColorUtils.setAlphaComponent(color, Color.alpha(color) * alpha / 0xFF));

            float baseline = level.y[i] - (letterPaint.descent() + letterPaint.ascent()) / 2f;
            canvas.drawText(level.sections.get(i).label, centerX - magnifyShift * magnify, baseline, letterPaint);
        }
    }

    /**
     * Years, months or days, of which there can be more than fit at a legible size. At rest only
     * evenly spread labels are drawn (decades and years first); near the finger the magnification
     * opens up enough room to read each one. Labels never overlap: working outward from the one
     * under the finger, a label that would touch the last one drawn is left out. {@code expand}
     * unfolds the column from {@code originY} as the strip zooms in.
     */
    private void drawLabels(Canvas canvas, Level level, float top, float bottom, int current, float alpha,
                            float centerX, float originY, float expand) {
        int n = level.count();
        float cell = (bottom - top) / n;
        if (cell <= 0 || alpha <= 0f) return;
        float restSize = Math.max(restLabelSize, Math.min(cell * 0.8f, maxLetterSize));
        float maxScale = Math.max(MAX_LETTER_SCALE, Math.min(MAX_DENSE_SCALE, magnifiedSlot / cell));
        layout(level, top, bottom, current, maxScale);
        if (level.rest == null || level.restCell != cell) {
            level.rest = ScrubberSections.restLabels(level.sections, cell, restSize * 1.4f, level.rest);
            level.restCell = cell;
        }

        for (int i = 0; i < n; i++) {
            float magnify = Math.min(1f, (level.scale[i] - 1f) / (maxScale - 1f));
            float magnified = Math.min(slot(level, i) * 0.8f, restSize + (maxMagnifiedSize - restSize) * magnify);
            level.size[i] = level.rest[i] ? Math.max(restSize, magnified) : magnified;
            level.label[i] = level.rest[i] || magnified >= minLabelSize;
        }
        int anchor = current >= 0 && current < n ? current : -1;
        if (anchor >= 0 && press > 0f) level.label[anchor] = true;
        if (anchor >= 0 && !level.label[anchor]) anchor = -1;

        float gap = dp(2);
        if (anchor < 0) {
            float edge = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!level.label[i]) continue;
                if (level.y[i] - level.size[i] * 0.45f < edge + gap) level.label[i] = false;
                else edge = level.y[i] + level.size[i] * 0.45f;
            }
        } else {
            float edge = level.y[anchor] - level.size[anchor] * 0.45f;
            for (int i = anchor - 1; i >= 0; i--) {
                if (!level.label[i]) continue;
                if (level.y[i] + level.size[i] * 0.45f > edge - gap) level.label[i] = false;
                else edge = level.y[i] - level.size[i] * 0.45f;
            }
            edge = level.y[anchor] + level.size[anchor] * 0.45f;
            for (int i = anchor + 1; i < n; i++) {
                if (!level.label[i]) continue;
                if (level.y[i] - level.size[i] * 0.45f < edge + gap) level.label[i] = false;
                else edge = level.y[i] + level.size[i] * 0.45f;
            }
        }

        dotPaint.setColor(withAlpha(letterColor, alpha * 0.5f));
        for (int i = 0; i < n; i++) {
            float y = originY + (level.y[i] - originY) * expand;
            if (!level.label[i]) {
                // A tick for each section left unlabelled, where there's room to tell them apart.
                if (slot(level, i) * expand >= dp(5)) canvas.drawCircle(centerX, y, dp(1.5f), dotPaint);
                continue;
            }
            String label = level.sections.get(i).label;
            float magnify = (level.scale[i] - 1f) / (maxScale - 1f);
            letterPaint.setTextSize(level.size[i]);
            float maxWidth = restLabelWidth + (magnifiedLabelWidth - restLabelWidth) * Math.min(1f, magnify);
            float width = letterPaint.measureText(label);
            if (width > maxWidth) letterPaint.setTextSize(level.size[i] * maxWidth / width);
            letterPaint.setColor(withAlpha(i == current && touching ? activeLetterColor : letterColor, alpha));

            float baseline = y - (letterPaint.descent() + letterPaint.ascent()) / 2f;
            canvas.drawText(label, centerX - magnifyShift * magnify, baseline, letterPaint);
        }
    }

    /** Distance to the nearer neighbour, as last laid out. */
    private static float slot(Level level, int i) {
        return Math.min(i > 0 ? level.y[i] - level.y[i - 1] : Float.MAX_VALUE,
                i < level.count() - 1 ? level.y[i + 1] - level.y[i] : Float.MAX_VALUE);
    }

    /**
     * A circle for a letter; a pill, growing to the left, for a year or a month and year on two
     * lines. {@code hold} (0–1, or -1 for a section with nothing to zoom into) fills a bar along
     * the bottom while the finger rests on a month.
     */
    private void drawBubble(Canvas canvas, ScrubberSections.Section section, float left, float hold) {
        float grow = press * (1f + 0.12f * pop);
        float right = left - bubbleGap;
        boolean twoLines = section.detail != null;
        float titleSize = twoLines ? sp(26) : section.title.length() <= 2 ? sp(46) : sp(34);
        float detailSize = sp(17);

        bubbleTextPaint.setTextSize(titleSize);
        float textWidth = bubbleTextPaint.measureText(section.title);
        if (twoLines) {
            bubbleDetailPaint.setTextSize(detailSize);
            textWidth = Math.max(textWidth, bubbleDetailPaint.measureText(section.detail));
        }
        float width = Math.min(Math.max(bubbleRadius * 2f, right - dp(8)),
                Math.max(bubbleRadius * 2f, textWidth + dp(28)));
        float fit = Math.min(1f, (width - dp(20)) / textWidth);

        float bubbleX = right - width / 2f;
        float bubbleY = Math.max(bubbleRadius, Math.min(visibleBottom() - bubbleRadius, touchY));
        float halfWidth = width / 2f * grow;
        float halfHeight = bubbleRadius * grow;
        bubbleRect.set(bubbleX - halfWidth, bubbleY - halfHeight, bubbleX + halfWidth, bubbleY + halfHeight);
        canvas.drawRoundRect(bubbleRect, halfHeight, halfHeight, bubblePaint);

        bubbleTextPaint.setTextSize(titleSize * fit * grow);
        if (!twoLines) {
            float baseline = bubbleY - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f;
            canvas.drawText(section.title, bubbleX, baseline, bubbleTextPaint);
        } else {
            float titleY = bubbleY - dp(9) * grow;
            canvas.drawText(section.title, bubbleX,
                    titleY - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f, bubbleTextPaint);
            bubbleDetailPaint.setTextSize(detailSize * fit * grow);
            float detailY = bubbleY + dp(15) * grow;
            canvas.drawText(section.detail, bubbleX,
                    detailY - (bubbleDetailPaint.descent() + bubbleDetailPaint.ascent()) / 2f, bubbleDetailPaint);
        }

        if (hold >= 0f && holdStartTime >= 0 && touching && !zoomed) {
            if (hold > 0f) {
                float barHalf = Math.max(dp(12), halfWidth - dp(30) * grow);
                float barY = bubbleY + halfHeight * 0.72f;
                canvas.drawLine(bubbleX - barHalf, barY, bubbleX - barHalf + 2f * barHalf * hold, barY, holdPaint);
            }
            postInvalidateOnAnimation();
        }
    }

    /**
     * Dock-style magnification: labels near the finger grow and take more room, the labels
     * further away squeeze together, and the label under the finger stays under the finger.
     * Hit testing still uses the even cells, so what you see under your finger is what you get.
     */
    private void layout(Level level, float top, float bottom, int current, float maxScale) {
        int n = level.count();
        float[] scale = level.scale;
        float[] y = level.y;
        float cell = (bottom - top) / n;
        float fingerCell = (touchY - top) / cell;
        for (int i = 0; i < n; i++) {
            float t = press > 0f ? Math.max(0f, 1f - Math.abs(i + 0.5f - fingerCell) / MAGNIFY_SPREAD_CELLS) : 0f;
            scale[i] = 1f + (maxScale - 1f) * press * t * t * (3f - 2f * t);
        }

        int anchor = current >= 0 && current < n ? current : 0;
        float evenY = top + cell * (anchor + 0.5f);
        float anchorY = evenY + (Math.max(top, Math.min(bottom, touchY)) - evenY) * press;
        y[anchor] = anchorY;

        // Share the room above and below the anchor in proportion to each label's size.
        float above = scale[anchor] / 2f;
        for (int i = 0; i < anchor; i++) above += scale[i];
        float unit = (anchorY - top) / above;
        float at = anchorY - unit * scale[anchor] / 2f;
        for (int i = anchor - 1; i >= 0; i--) {
            y[i] = at - unit * scale[i] / 2f;
            at -= unit * scale[i];
        }

        float below = scale[anchor] / 2f;
        for (int i = anchor + 1; i < n; i++) below += scale[i];
        unit = (bottom - anchorY) / below;
        at = anchorY + unit * scale[anchor] / 2f;
        for (int i = anchor + 1; i < n; i++) {
            y[i] = at + unit * scale[i] / 2f;
            at += unit * scale[i];
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getX() < stripLeft() - dp(8)
                        || event.getY() < getPaddingTop()
                        || event.getY() > lettersBottom()) {
                    return false;
                }
                touching = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (recyclerView != null) recyclerView.stopScroll();
                if (onScrubStart != null) onScrubStart.run();
                setImagesPaused(true);
                lastScrubJumpAt = SystemClock.uptimeMillis();
                resetZoom();
                animatePress(1f);
                holdStartY = event.getY();
                onFingerAt(event.getY());
                restartHold(event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touching) return false;
                onFingerAt(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!touching) return false;
                touching = false;
                removeCallbacks(showImagesAtRest);
                cancelHold();
                getParent().requestDisallowInterceptTouchEvent(false);
                setImagesPaused(false);
                animatePress(0f);
                // The days fold away with the bubble, which keeps showing the date until it's gone.
                if (zoomed) animateZoom(0f, 260);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void onFingerAt(float y) {
        touchY = y;
        if (zoomed) {
            if (y >= bandTop - zoomExitMargin && y <= bandBottom + zoomExitMargin) {
                selectDay(dayAt(y), true);
                invalidate();
                return;
            }
            // Well past the days: back out to the months, on whichever one is under the finger.
            zoomed = false;
            currentDay = -1;
            currentLetter = -1;
            animateZoom(0f, 220);
            restartHold(y);
        }

        int n = topLevel.count();
        if (n == 0) return;
        int section = sectionAt(y, n);
        if (section != currentLetter) {
            currentLetter = section;
            int position = positionFor(section);
            if (position >= 0) {
                scrollTo(position);
                onScrubJump();
            }
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            animatePop();
        }
        if (Math.abs(y - holdStartY) > holdSlop) restartHold(y);
        invalidate();
    }

    private int sectionAt(float y, int n) {
        float top = getPaddingTop();
        float cell = cellHeight();
        int section = Math.max(0, Math.min(n - 1, (int) ((y - top) / cell)));
        if (sections == null || sections.fixedLabels || currentLetter < 0 || currentLetter >= n
                || section == currentLetter) {
            return section;
        }
        // A crowded strip has cells a few pixels tall: a resting finger shouldn't flicker between two.
        float margin = Math.min(dp(3), cell * 0.45f);
        float shifted = y + (section > currentLetter ? -margin : margin);
        return Math.max(0, Math.min(n - 1, (int) ((shifted - top) / cell)));
    }

    private int dayAt(float y) {
        int n = dayLevel.count();
        float cell = (bandBottom - bandTop) / n;
        return Math.max(0, Math.min(n - 1, (int) ((y - bandTop) / cell)));
    }

    private void selectDay(int day, boolean feedback) {
        if (day == currentDay) return;
        currentDay = day;
        scrollTo(dayLevel.sections.get(day).firstPosition);
        onScrubJump();
        if (feedback) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        animatePop();
    }

    /** Starts timing a hold at {@code y}, for zooming into the month under the finger. */
    private void restartHold(float y) {
        holdStartY = y;
        cancelHold();
        if (!touching || zoomed || sections == null || !sections.zoomable) return;
        holdStartTime = SystemClock.uptimeMillis();
        postDelayed(zoomOnHold, HOLD_TO_ZOOM_MS);
    }

    private void cancelHold() {
        removeCallbacks(zoomOnHold);
        holdStartTime = -1;
    }

    private float holdProgress() {
        if (holdStartTime < 0) return 0f;
        long held = SystemClock.uptimeMillis() - holdStartTime - HOLD_HINT_DELAY_MS;
        return Math.max(0f, Math.min(1f, held / (float) (HOLD_TO_ZOOM_MS - HOLD_HINT_DELAY_MS)));
    }

    /**
     * Swaps the months for the days of the one under the finger, laid out in a band that starts
     * at the finger (so it rests on the month's newest day, where the list already is) and moves
     * up if it would run off the bottom.
     */
    private void zoomIn() {
        if (!touching || zoomed || currentLetter < 0 || currentLetter >= topLevel.count()) return;
        List<ScrubberSections.Section> days = topLevel.sections.get(currentLetter).days;
        if (days.isEmpty()) return;

        dayLevel.set(days);
        float top = getPaddingTop();
        float bottom = lettersBottom();
        float height = Math.min(days.size() * dayCellHeight, (bottom - top) * 0.7f);
        float cell = height / days.size();
        bandTop = Math.max(top, Math.min(bottom - height, touchY - cell / 2f));
        bandBottom = bandTop + height;
        zoomOriginY = touchY;
        zoomed = true;
        currentDay = -1;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        selectDay(dayAt(touchY), false);
        animateZoom(1f, 220);
    }

    private void resetZoom() {
        if (zoomAnimator != null) zoomAnimator.cancel();
        zoom = 0f;
        zoomed = false;
        currentDay = -1;
    }

    private void animateZoom(float target, long duration) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        zoomAnimator = ValueAnimator.ofFloat(zoom, target);
        zoomAnimator.setDuration(duration);
        zoomAnimator.setInterpolator(new DecelerateInterpolator());
        zoomAnimator.addUpdateListener(a -> {
            zoom = (float) a.getAnimatedValue();
            invalidate();
        });
        zoomAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || target > 0f) return;
                zoomed = false;
                currentDay = -1;
                invalidate();
            }
        });
        zoomAnimator.start();
    }

    /** First row of the section, or of the nearest following section that has rows. */
    private int positionFor(int section) {
        List<ScrubberSections.Section> all = topLevel.sections;
        for (int i = section; i < all.size(); i++) {
            if (all.get(i).firstPosition != -1) return all.get(i).firstPosition;
        }
        for (int i = section - 1; i >= 0; i--) {
            if (all.get(i).firstPosition != -1) return all.get(i).firstPosition;
        }
        return -1;
    }

    /**
     * Jumps at most once a frame. A fast drag crosses several sections between frames, and each
     * jump lays out a whole page of new tiles; only the last one is ever seen.
     */
    private void scrollTo(int position) {
        if (recyclerView == null) return;
        boolean posted = pendingScroll != RecyclerView.NO_POSITION;
        pendingScroll = position;
        if (!posted) recyclerView.postOnAnimation(applyPendingScroll);
    }

    private int pendingScroll = RecyclerView.NO_POSITION;
    private final Runnable applyPendingScroll = () -> {
        int position = pendingScroll;
        pendingScroll = RecyclerView.NO_POSITION;
        if (position != RecyclerView.NO_POSITION) jumpTo(position);
    };

    private void jumpTo(int position) {
        if (recyclerView == null) return;
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            // 1px past the padding edge: with the row above ending exactly on that edge, each
            // later layout (e.g. a cover arriving) re-anchors to that row and the list creeps
            // back one row at a time.
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(position, -1);
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

    private static int withAlpha(int color, float alpha) {
        return ColorUtils.setAlphaComponent(color, (int) (Color.alpha(color) * Math.max(0f, Math.min(1f, alpha))));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
