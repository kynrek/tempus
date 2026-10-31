package com.eddyizm.tempus.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Name ordering shared by the sorted lists and the A–Z scrubber, so that the letter a row is
 * filed under always matches where the sort put it: a leading "The " is ignored, case and
 * accents are ignored, and anything that doesn't start with A–Z is grouped under "#" first.
 */
public final class AlphabetSort {
    public static final String OTHER = "#";

    private static final Pattern LEADING_ARTICLE = Pattern.compile("^the\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    public static final Comparator<String> COMPARATOR = (a, b) -> {
        String ka = sortKey(a);
        String kb = sortKey(b);
        int bucket = Boolean.compare(isLetter(ka), isLetter(kb));
        return bucket != 0 ? bucket : ka.compareTo(kb);
    };

    private AlphabetSort() {
    }

    @NonNull
    public static String sortKey(@Nullable String name) {
        if (name == null) return "";
        String key = name.trim();
        String stripped = LEADING_ARTICLE.matcher(key).replaceFirst("");
        if (!stripped.isEmpty()) key = stripped;
        key = DIACRITICS.matcher(Normalizer.normalize(key, Normalizer.Form.NFD)).replaceAll("");
        return key.toLowerCase(Locale.ROOT);
    }

    /** "A".."Z", or {@link #OTHER} for digits, symbols and non-Latin names. */
    @NonNull
    public static String indexLetter(@Nullable String name) {
        String key = sortKey(name);
        return isLetter(key) ? String.valueOf(Character.toUpperCase(key.charAt(0))) : OTHER;
    }

    private static boolean isLetter(String key) {
        return !key.isEmpty() && key.charAt(0) >= 'a' && key.charAt(0) <= 'z';
    }
}
