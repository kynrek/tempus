package com.eddyizm.tempus.util;

import android.content.Context;

/** The Albums and Artists tabs' view style (Settings > UI > Albums and Artists view). */
public final class CatalogueView {
    public static final String CONDENSED = "condensed";
    public static final String ROWS_LARGE = "rows_large";
    public static final String FULL_ART = "full_art";
    public static final String ART_TITLE_BIG = "art_title_big";
    public static final String ART_TITLE_MEDIUM = "art_title_medium";

    private CatalogueView() {
    }

    public static String current() {
        return Preferences.getCatalogueView();
    }

    /** Small cover beside the title, several rows across. */
    public static boolean isRows(String view) {
        return CONDENSED.equals(view) || ROWS_LARGE.equals(view);
    }

    /** Cover size in rows: large rows are twice as tall as condensed ones. */
    public static int rowCoverDp(String view) {
        return ROWS_LARGE.equals(view) ? 104 : 52;
    }

    public static boolean showsTitles(String view) {
        return !FULL_ART.equals(view);
    }

    /**
     * Items across for a window {@code widthDp} wide. The counts were picked on the car pane
     * (833dp: rows three across, full art five, big art three, medium art four) and scale with
     * the width from there, so a phone on its side (~870dp) gets the same and a phone upright
     * (~400dp) gets one row, three full-art covers, two big or three medium tiles. Each style
     * keeps a floor so covers on a narrow screen don't shrink to stamps or grow to one per row.
     */
    public static int spanCount(String view, int widthDp) {
        int perCar;
        int min;
        switch (view) {
            case FULL_ART: perCar = 5; min = 3; break;
            case ART_TITLE_BIG: perCar = 3; min = 2; break;
            case ART_TITLE_MEDIUM: perCar = 4; min = 3; break;
            default: perCar = 3; min = 1; break;   // rows
        }
        if (widthDp <= 0) return perCar;
        int span = (int) Math.floor(perCar * widthDp / (float) CAR_WIDTH_DP + 0.15f);
        return Math.max(min, span);
    }

    /** Items across for the window this context is shown in (follows rotation and split screen). */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    public static int spanCount(String view, Context context) {
        int widthDp = context.getResources().getConfiguration().screenWidthDp;
        // The side mini player (landscape) takes its width from the page.
        if (context instanceof com.eddyizm.tempus.ui.activity.MainActivity
                && ((com.eddyizm.tempus.ui.activity.MainActivity) context).usesSidePlayer()) {
            widthDp -= Math.round(context.getResources().getDimension(com.eddyizm.tempus.R.dimen.side_player_width)
                    / context.getResources().getDisplayMetrics().density);
        }
        return spanCount(view, widthDp);
    }

    /** Width the per-style counts were tuned on: the car display pane. */
    private static final int CAR_WIDTH_DP = 833;
}
