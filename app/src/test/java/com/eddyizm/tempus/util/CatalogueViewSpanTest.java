package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CatalogueViewSpanTest {
    private static int[] spans(int widthDp) {
        return new int[]{
                CatalogueView.spanCount(CatalogueView.CONDENSED, widthDp),
                CatalogueView.spanCount(CatalogueView.ROWS_LARGE, widthDp),
                CatalogueView.spanCount(CatalogueView.FULL_ART, widthDp),
                CatalogueView.spanCount(CatalogueView.ART_TITLE_BIG, widthDp),
                CatalogueView.spanCount(CatalogueView.ART_TITLE_MEDIUM, widthDp),
        };
    }

    private static void assertSpans(int widthDp, int... expected) {
        int[] actual = spans(widthDp);
        for (int i = 0; i < expected.length; i++) assertEquals("width " + widthDp + " style " + i, expected[i], actual[i]);
    }

    @Test
    public void carPaneKeepsItsCounts() {
        assertSpans(833, 3, 3, 5, 3, 4);
    }

    @Test
    public void phoneOnItsSideMatchesTheCar() {
        assertSpans(869, 3, 3, 5, 3, 4);
    }

    @Test
    public void phoneUprightGetsPhoneCounts() {
        assertSpans(400, 1, 1, 3, 2, 3);
        assertSpans(360, 1, 1, 3, 2, 3);
    }

    @Test
    public void unknownWidthFallsBackToCarCounts() {
        assertSpans(0, 3, 3, 5, 3, 4);
    }
}
