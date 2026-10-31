package com.eddyizm.tempus.helper.recyclerview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.eddyizm.tempus.util.AlphabetSort;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * What the {@link AlphabetScrubber} strip shows for a sorted list: one section per letter, year
 * or month, the row each one starts at, and for a month the days the strip can zoom into. Built
 * once each time the rows or their order change; the scrubber only reads it.
 *
 * <p>Sections come in the list's own order, so a jump always lands where the sort put them.
 */
public final class ScrubberSections {
    /** The group for rows with no year or date, wherever the sort put them. */
    public static final String MISSING = "?";

    private static final String[] LETTERS = {
            AlphabetSort.OTHER, "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};

    public static final class Section {
        /** Shown on the strip: "A", "1994", "Oct 26", "14". */
        @NonNull
        public final String label;
        /** Shown big in the bubble: "A", "1994", "October", "Tue 14". */
        @NonNull
        public final String title;
        /** Second, smaller line in the bubble ("2026", "October 2026"), or null. */
        @Nullable
        public final String detail;
        /** First row filed here, or -1 when there is none (only letters can be empty). */
        public final int firstPosition;
        /** Starts a decade or a year: labelled first when not every label fits on the strip. */
        public final boolean major;
        /** The days to zoom into, for a month; empty otherwise. */
        @NonNull
        public final List<Section> days;

        Section(@NonNull String label, @NonNull String title, @Nullable String detail, int firstPosition,
                boolean major, @NonNull List<Section> days) {
            this.label = label;
            this.title = title;
            this.detail = detail;
            this.firstPosition = firstPosition;
            this.major = major;
            this.days = days;
        }
    }

    @NonNull
    public final List<Section> sections;
    /**
     * The A–Z strip: all 27 letters are always drawn at the size that fits, as they always have
     * been. Otherwise there can be too many labels to fit, and the strip thins them out.
     */
    public final boolean fixedLabels;
    /** Some sections have days to zoom into. */
    public final boolean zoomable;
    private final int filled;

    private ScrubberSections(@NonNull List<Section> sections, boolean fixedLabels) {
        this.sections = sections;
        this.fixedLabels = fixedLabels;
        int filled = 0;
        boolean zoomable = false;
        for (Section section : sections) {
            if (section.firstPosition >= 0) filled++;
            if (!section.days.isEmpty()) zoomable = true;
        }
        this.filled = filled;
        this.zoomable = zoomable;
    }

    /** Sections that have rows; with fewer than two the list doesn't need an index. */
    public int filledCount() {
        return filled;
    }

    /** "#", A–Z by the name each row is filed under (see {@link AlphabetSort}), empty letters included. */
    @NonNull
    public static ScrubberSections letters(int count, @NonNull IntFunction<String> name) {
        int[] first = new int[LETTERS.length];
        Arrays.fill(first, -1);
        for (int position = 0; position < count; position++) {
            String letter = AlphabetSort.indexLetter(name.apply(position));
            int index = letter.equals(AlphabetSort.OTHER) ? 0 : letter.charAt(0) - 'A' + 1;
            if (first[index] == -1) first[index] = position;
        }

        List<Section> sections = new ArrayList<>(LETTERS.length);
        for (int i = 0; i < LETTERS.length; i++) {
            sections.add(new Section(LETTERS[i], LETTERS[i], null, first[i], false, Collections.emptyList()));
        }
        return new ScrubberSections(sections, true);
    }

    /** One section per year in list order; a year of 0 or less (unknown) goes under {@link #MISSING}. */
    @NonNull
    public static ScrubberSections years(int count, @NonNull IntUnaryOperator year) {
        List<Section> sections = new ArrayList<>();
        Map<Integer, Boolean> seen = new HashMap<>();
        int lastDecade = Integer.MIN_VALUE;
        for (int position = 0; position < count; position++) {
            int y = Math.max(0, year.applyAsInt(position));
            if (seen.put(y, Boolean.TRUE) != null) continue;
            if (y == 0) {
                sections.add(new Section(MISSING, MISSING, null, position, true, Collections.emptyList()));
                continue;
            }
            String label = String.valueOf(y);
            boolean major = y / 10 != lastDecade;
            lastDecade = y / 10;
            sections.add(new Section(label, label, null, position, major, Collections.emptyList()));
        }
        return new ScrubberSections(sections, false);
    }

    /**
     * One section per month ("Oct 26") in list order, each holding the days that have rows.
     * A missing date (null, or the epoch the model uses for "never") goes under {@link #MISSING}.
     * When every dated row falls in one month, the days themselves become the sections.
     */
    @NonNull
    public static ScrubberSections months(int count, @NonNull IntFunction<Date> date,
                                          @NonNull Locale locale, @NonNull TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, locale);
        SimpleDateFormat monthLabel = format("LLL yy", locale, zone);
        SimpleDateFormat monthTitle = format("LLLL", locale, zone);
        SimpleDateFormat yearDetail = format("yyyy", locale, zone);
        SimpleDateFormat dayTitle = format("EEE d", locale, zone);
        SimpleDateFormat dayDetail = format("LLLL yyyy", locale, zone);

        List<MonthBuilder> months = new ArrayList<>();
        Map<Integer, MonthBuilder> byKey = new HashMap<>();
        int missing = -1;
        int missingIndex = -1;
        for (int position = 0; position < count; position++) {
            Date d = date.apply(position);
            if (d == null || d.getTime() <= 0) {
                if (missing == -1) {
                    missing = position;
                    missingIndex = months.size();
                }
                continue;
            }
            calendar.setTime(d);
            int key = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH);
            MonthBuilder month = byKey.get(key);
            if (month == null) {
                month = new MonthBuilder(calendar.get(Calendar.YEAR), position,
                        monthLabel.format(d), monthTitle.format(d), yearDetail.format(d), dayDetail.format(d));
                byKey.put(key, month);
                months.add(month);
            }
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            if (month.dayIndex[day] == -1) {
                month.dayIndex[day] = month.days.size();
                month.days.add(new Section(String.valueOf(day), dayTitle.format(d), month.dayDetail, position,
                        false, Collections.emptyList()));
            }
        }

        List<Section> sections = new ArrayList<>(months.size() + 1);
        if (months.size() == 1 && months.get(0).days.size() >= 2) {
            // A single month: zooming would be the only way to get anywhere, so start at the days.
            sections.addAll(months.get(0).days);
            if (missing != -1) sections.add(missingIndex == 0 ? 0 : sections.size(), missingSection(missing));
            return new ScrubberSections(sections, false);
        }

        int lastYear = Integer.MIN_VALUE;
        for (int i = 0; i <= months.size(); i++) {
            if (i == missingIndex) sections.add(missingSection(missing));
            if (i == months.size()) break;
            MonthBuilder month = months.get(i);
            sections.add(new Section(month.label, month.title, month.yearDetail, month.firstPosition,
                    month.year != lastYear, Collections.unmodifiableList(month.days)));
            lastYear = month.year;
        }
        return new ScrubberSections(sections, false);
    }

    private static Section missingSection(int position) {
        return new Section(MISSING, MISSING, null, position, true, Collections.emptyList());
    }

    private static SimpleDateFormat format(String pattern, Locale locale, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(zone);
        return format;
    }

    private static final class MonthBuilder {
        final int year;
        final int firstPosition;
        final String label;
        final String title;
        final String yearDetail;
        final String dayDetail;
        final List<Section> days = new ArrayList<>();
        /** Where each day of the month (1–31) is in {@link #days}, or -1. */
        final int[] dayIndex = new int[32];

        MonthBuilder(int year, int firstPosition, String label, String title, String yearDetail, String dayDetail) {
            this.year = year;
            this.firstPosition = firstPosition;
            this.label = label;
            this.title = title;
            this.yearDetail = yearDetail;
            this.dayDetail = dayDetail;
            Arrays.fill(dayIndex, -1);
        }
    }

    /**
     * Which of {@code sections} keep a label when the strip is at rest, with the sections spread
     * evenly {@code cell} apart and labels needing {@code spacing} between them. Majors (decades,
     * years) are placed first, then the gaps are filled, so with 70 years the strip reads
     * "1960 … 1970 …" instead of whichever years happened to come first.
     */
    @NonNull
    public static boolean[] restLabels(@NonNull List<Section> sections, float cell, float spacing,
                                       @Nullable boolean[] reuse) {
        int n = sections.size();
        boolean[] keep = reuse != null && reuse.length >= n ? reuse : new boolean[n];
        Arrays.fill(keep, false);
        if (n == 0) return keep;
        int step = cell >= spacing ? 1 : (int) Math.ceil(spacing / cell);
        if (step == 1) {
            Arrays.fill(keep, 0, n, true);
            return keep;
        }

        int last = Integer.MIN_VALUE / 2;
        for (int i = 0; i < n; i++) {
            if (sections.get(i).major && i - last >= step) {
                keep[i] = true;
                last = i;
            }
        }

        // Fill the gaps between majors, keeping clear of the next major as well as the last label.
        int nextKept = Integer.MAX_VALUE / 2;
        int[] next = new int[n];
        for (int i = n - 1; i >= 0; i--) {
            if (keep[i]) nextKept = i;
            next[i] = nextKept;
        }
        last = Integer.MIN_VALUE / 2;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                last = i;
            } else if (i - last >= step && next[i] - i >= step) {
                keep[i] = true;
                last = i;
            }
        }
        return keep;
    }
}
