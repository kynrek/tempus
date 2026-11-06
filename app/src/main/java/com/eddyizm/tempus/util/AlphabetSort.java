package com.eddyizm.tempus.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.function.Function;

/**
 * Name ordering shared by the sorted lists and the A–Z scrubber, so that the letter a row is
 * filed under always matches where the sort put it: a leading "The " is ignored, case and
 * accents are ignored, and anything that doesn't start with A–Z is grouped under "#" first.
 */
public final class AlphabetSort {
    public static final String OTHER = "#";


    public static final Comparator<String> COMPARATOR = (a, b) -> {
        String ka = sortKey(a);
        String kb = sortKey(b);
        int bucket = Boolean.compare(isLetter(ka), isLetter(kb));
        return bucket != 0 ? bucket : ka.compareTo(kb);
    };

    private AlphabetSort() {
    }

    /**
     * Sorts {@code items} into this order by the name {@code name} gives each one, then by
     * {@code tieBreak} when it isn't null. Same result as sorting with {@link #COMPARATOR}, but
     * each name's key is worked out once instead of on every comparison, which on a few thousand
     * albums is the difference between a few milliseconds and a few seconds.
     */
    public static <T> void sort(@NonNull List<T> items, @NonNull Function<? super T, String> name,
                                @Nullable Comparator<? super T> tieBreak) {
        @SuppressWarnings("unchecked")
        Keyed<T>[] keyed = new Keyed[items.size()];
        int i = 0;
        for (T item : items) keyed[i++] = new Keyed<>(item, sortKey(name.apply(item)));

        Comparator<Keyed<T>> order = (a, b) -> {
            int bucket = Boolean.compare(a.letter, b.letter);
            return bucket != 0 ? bucket : a.key.compareTo(b.key);
        };
        if (tieBreak != null) order = order.thenComparing((a, b) -> tieBreak.compare(a.item, b.item));
        Arrays.sort(keyed, order);

        ListIterator<T> it = items.listIterator();
        for (Keyed<T> k : keyed) {
            it.next();
            it.set(k.item);
        }
    }

    private static final class Keyed<T> {
        final T item;
        final String key;
        final boolean letter;

        Keyed(T item, String key) {
            this.item = item;
            this.key = key;
            this.letter = isLetter(key);
        }
    }

    /**
     * Same as stripping a leading {@code the\s+} with a regex and the accents with NFD, but
     * without the regex, and plain-ASCII names (most of a library) skip the normalizer.
     */
    @NonNull
    public static String sortKey(@Nullable String name) {
        if (name == null) return "";
        String key = name.trim();
        int articleEnd = leadingArticleEnd(key);
        if (articleEnd > 0 && articleEnd < key.length()) key = key.substring(articleEnd);
        if (!isAscii(key)) key = stripMarks(Normalizer.normalize(key, Normalizer.Form.NFD));
        return key.toLowerCase(Locale.ROOT);
    }

    /** End of a leading "the" and the whitespace after it, or 0 when the name doesn't start with one. */
    private static int leadingArticleEnd(String key) {
        if (key.length() < 4 || !key.regionMatches(true, 0, "the", 0, 3)) return 0;
        int end = 3;
        while (end < key.length() && isRegexSpace(key.charAt(end))) end++;
        return end > 3 ? end : 0;
    }

    /** Drops what {@code \p{M}} matches (combining marks), the accents NFD splits off letters. */
    private static String stripMarks(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            int type = Character.getType(c);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK
                    && type != Character.COMBINING_SPACING_MARK) {
                out.appendCodePoint(c);
            }
            i += Character.charCount(c);
        }
        return out.toString();
    }

    /** {@code \s} in a Java regex: space, tab, newline, vertical tab, form feed, carriage return. */
    private static boolean isRegexSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) return false;
        }
        return true;
    }

    /** "A".."Z", or {@link #OTHER} for digits, symbols and non-Latin names. */
    @NonNull
    public static String indexLetter(@Nullable String name) {
        String key = sortKey(name);
        return isLetter(key) ? String.valueOf(Character.toUpperCase(key.charAt(0))) : OTHER;
    }

    /** Whether a {@link #sortKey} files under A–Z (true) or under {@link #OTHER}, which sorts first. */
    public static boolean isLetterKey(@NonNull String key) {
        return isLetter(key);
    }

    private static boolean isLetter(String key) {
        return !key.isEmpty() && key.charAt(0) >= 'a' && key.charAt(0) <= 'z';
    }
}
