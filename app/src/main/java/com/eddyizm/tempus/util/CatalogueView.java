package com.eddyizm.tempus.util;

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

    /** Items across: rows three across, full art five, big art three, medium art four. */
    public static int spanCount(String view) {
        switch (view) {
            case FULL_ART: return 5;
            case ART_TITLE_BIG: return 3;
            case ART_TITLE_MEDIUM: return 4;
            default: return 3;
        }
    }
}
