package com.eddyizm.tempus.helper.recyclerview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eddyizm.tempus.util.AlphabetSort;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ScrubberSectionsTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static Date date(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance(UTC, Locale.US);
        calendar.clear();
        calendar.set(year, month - 1, day, 12, 0);
        return calendar.getTime();
    }

    private static ScrubberSections months(Date... dates) {
        List<Date> list = Arrays.asList(dates);
        return ScrubberSections.months(list.size(), list::get, Locale.US, UTC);
    }

    private static List<String> labels(List<ScrubberSections.Section> sections) {
        List<String> labels = new ArrayList<>();
        for (ScrubberSections.Section section : sections) labels.add(section.label);
        return labels;
    }

    private static List<Integer> positions(List<ScrubberSections.Section> sections) {
        List<Integer> positions = new ArrayList<>();
        for (ScrubberSections.Section section : sections) positions.add(section.firstPosition);
        return positions;
    }

    @Test
    public void letters_isTheFixedHashToZStripWithEmptyLetters() {
        List<String> names = new ArrayList<>(Arrays.asList("2Pac", "ABBA", "The Beatles", "beck", "Zappa"));
        AlphabetSort.sort(names, name -> name, null);
        ScrubberSections sections = ScrubberSections.letters(names.size(), names::get);

        assertTrue(sections.fixedLabels);
        assertFalse(sections.zoomable);
        assertEquals(27, sections.sections.size());
        assertEquals("#", sections.sections.get(0).label);
        assertEquals("Z", sections.sections.get(26).label);
        assertEquals(0, sections.sections.get(0).firstPosition);
        assertEquals(1, sections.sections.get(1).firstPosition);
        assertEquals(2, sections.sections.get(2).firstPosition);
        assertEquals(-1, sections.sections.get(3).firstPosition);
        assertEquals(4, sections.sections.get(26).firstPosition);
        assertEquals(4, sections.filledCount());
    }

    @Test
    public void years_followTheListOrderWithUnknownYearsGrouped() {
        int[] years = {0, 0, 1969, 1969, 1971, 1994, 1999, 2003};
        ScrubberSections sections = ScrubberSections.years(years.length, i -> years[i]);

        assertFalse(sections.fixedLabels);
        assertEquals(Arrays.asList("?", "1969", "1971", "1994", "1999", "2003"), labels(sections.sections));
        assertEquals(Arrays.asList(0, 2, 4, 5, 6, 7), positions(sections.sections));
        assertEquals("1994", sections.sections.get(3).title);
        assertNull(sections.sections.get(3).detail);
        // Decades lead when labels are thinned out.
        assertTrue(sections.sections.get(1).major);
        assertTrue(sections.sections.get(2).major);
        assertTrue(sections.sections.get(3).major);
        assertFalse(sections.sections.get(4).major);
        assertTrue(sections.sections.get(5).major);
    }

    @Test
    public void years_missingAtTheEndWhenTheSortPutsThemThere() {
        int[] years = {2001, 1990, 0};
        ScrubberSections sections = ScrubberSections.years(years.length, i -> years[i]);
        assertEquals(Arrays.asList("2001", "1990", "?"), labels(sections.sections));
        assertEquals(Arrays.asList(0, 1, 2), positions(sections.sections));
    }

    @Test
    public void months_newestFirstWithDaysToZoomInto() {
        ScrubberSections sections = months(
                date(2026, 10, 30), date(2026, 10, 30), date(2026, 10, 14),
                date(2026, 9, 2),
                date(2025, 12, 25), date(2025, 12, 1));

        assertTrue(sections.zoomable);
        assertEquals(Arrays.asList("Oct 26", "Sep 26", "Dec 25"), labels(sections.sections));
        assertEquals(Arrays.asList(0, 3, 4), positions(sections.sections));

        ScrubberSections.Section october = sections.sections.get(0);
        assertEquals("October", october.title);
        assertEquals("2026", october.detail);
        assertEquals(Arrays.asList("30", "14"), labels(october.days));
        assertEquals(Arrays.asList(0, 2), positions(october.days));
        assertEquals("Fri 30", october.days.get(0).title);
        assertEquals("October 2026", october.days.get(0).detail);

        // A new year starts a major label.
        assertTrue(sections.sections.get(0).major);
        assertFalse(sections.sections.get(1).major);
        assertTrue(sections.sections.get(2).major);
    }

    @Test
    public void months_missingDatesGroupedWhereTheSortPutsThem() {
        // The model defaults "played" to the epoch for albums that were never played.
        ScrubberSections sections = months(date(2026, 10, 1), date(2026, 8, 3), null, new Date(0));
        assertEquals(Arrays.asList("Oct 26", "Aug 26", "?"), labels(sections.sections));
        assertEquals(Arrays.asList(0, 1, 2), positions(sections.sections));
        assertTrue(sections.sections.get(2).days.isEmpty());
        assertEquals(3, sections.filledCount());
    }

    @Test
    public void months_allMissingIsASingleSection() {
        ScrubberSections sections = months(null, new Date(0), null);
        assertEquals(Collections.singletonList("?"), labels(sections.sections));
        assertEquals(1, sections.filledCount());
        assertFalse(sections.zoomable);
    }

    @Test
    public void months_singleMonthStartsAtItsDays() {
        ScrubberSections sections = months(date(2026, 10, 30), date(2026, 10, 12), date(2026, 10, 12), null);
        assertEquals(Arrays.asList("30", "12", "?"), labels(sections.sections));
        assertEquals(Arrays.asList(0, 1, 3), positions(sections.sections));
        assertFalse(sections.zoomable);
    }

    @Test
    public void months_keepTheFirstRowOfAMonthThatComesBackLater() {
        ScrubberSections sections = months(date(2026, 10, 5), date(2026, 9, 5), date(2026, 10, 4));
        assertEquals(Arrays.asList("Oct 26", "Sep 26"), labels(sections.sections));
        assertEquals(Arrays.asList(0, 1), positions(sections.sections));
        assertEquals(Arrays.asList("5", "4"), labels(sections.sections.get(0).days));
        assertEquals(Arrays.asList(0, 2), positions(sections.sections.get(0).days));
    }

    @Test
    public void restLabels_allFitWhenThereIsRoom() {
        int[] years = {1990, 1991, 1992};
        ScrubberSections sections = ScrubberSections.years(years.length, i -> years[i]);
        boolean[] rest = ScrubberSections.restLabels(sections.sections, 30f, 16f, null);
        assertTrue(rest[0] && rest[1] && rest[2]);
    }

    @Test
    public void restLabels_seventyYearsAreThinnedWithoutOverlapAndKeepDecades() {
        int[] years = new int[70];
        for (int i = 0; i < years.length; i++) years[i] = 1955 + i;
        ScrubberSections sections = ScrubberSections.years(years.length, i -> years[i]);
        float cell = 4.3f;
        float spacing = 16.8f;
        boolean[] rest = ScrubberSections.restLabels(sections.sections, cell, spacing, null);

        int last = -1000;
        int shown = 0;
        for (int i = 0; i < years.length; i++) {
            if (!rest[i]) continue;
            assertTrue("labels " + last + " and " + i + " overlap", (i - last) * cell >= spacing);
            last = i;
            shown++;
        }
        assertTrue(shown >= 10);
        // 1960, 1970 … 2020 each start a decade and get a label.
        for (int decade = 1960; decade <= 2020; decade += 10) assertTrue(String.valueOf(decade), rest[decade - 1955]);
    }
}
