package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

public class AlphabetSortTest {

    @Test
    public void indexLetter_ignoresLeadingTheCaseAndAccents() {
        assertEquals("B", AlphabetSort.indexLetter("The Beatles"));
        assertEquals("B", AlphabetSort.indexLetter("the beatles"));
        assertEquals("T", AlphabetSort.indexLetter("The"));
        assertEquals("T", AlphabetSort.indexLetter("Therapy?"));
        assertEquals("E", AlphabetSort.indexLetter("Édith Piaf"));
        assertEquals("A", AlphabetSort.indexLetter("  abba"));
    }

    @Test
    public void indexLetter_groupsDigitsSymbolsAndEmptyUnderHash() {
        assertEquals("#", AlphabetSort.indexLetter("2Pac"));
        assertEquals("#", AlphabetSort.indexLetter("!!!"));
        assertEquals("#", AlphabetSort.indexLetter("坂本龍一"));
        assertEquals("#", AlphabetSort.indexLetter(""));
        assertEquals("#", AlphabetSort.indexLetter(null));
    }

    @Test
    public void comparator_ordersLikeTheIndex() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "the Who", "ABBA", "Zappa", "坂本龍一", "2Pac", "The Beatles", "beck", "Édith Piaf", "~tilde"));
        names.sort(AlphabetSort.COMPARATOR);

        assertEquals(Arrays.asList(
                "2Pac", "~tilde", "坂本龍一", "ABBA", "The Beatles", "beck", "Édith Piaf", "the Who", "Zappa"), names);

        // Sections never go backwards, so the first row of each letter is where the scrubber jumps.
        String previous = "";
        for (String name : names) {
            String letter = AlphabetSort.indexLetter(name);
            if (!letter.equals(previous)) {
                assertEquals(true, previous.isEmpty() || previous.equals(AlphabetSort.OTHER) || previous.compareTo(letter) < 0);
                previous = letter;
            }
        }
    }

    /** The original regex version of sortKey, kept here to check the fast one against. */
    private static String regexSortKey(String name) {
        if (name == null) return "";
        String key = name.trim();
        String stripped = Pattern.compile("^the\\s+", Pattern.CASE_INSENSITIVE).matcher(key).replaceFirst("");
        if (!stripped.isEmpty()) key = stripped;
        key = Pattern.compile("\\p{M}+").matcher(Normalizer.normalize(key, Normalizer.Form.NFD)).replaceAll("");
        return key.toLowerCase(Locale.ROOT);
    }

    private static final List<String> SAMPLES = Arrays.asList(
            null, "", " ", "the", "The", "the ", "The  Who", "the\tWho", "THE\u000BEnd", "Thee Oh Sees",
            "Therapy?", "theatre", "The\u00A0Band", "  The Beatles  ", "Édith Piaf", "The Édith", "Beyoncé",
            "Sigur Rós", "Mötley Crüe", "坂本龍一", "2Pac", "~tilde", "ÅMB", "the The", "Ωmega", "ǅemal");

    @Test
    public void sortKey_matchesTheRegexVersion() {
        for (String name : SAMPLES) {
            assertEquals(String.valueOf(name), regexSortKey(name), AlphabetSort.sortKey(name));
        }
    }

    @Test
    public void sort_matchesSortingWithTheComparator() {
        List<String> names = new ArrayList<>();
        for (String name : SAMPLES) if (name != null) names.add(name);
        for (int i = 0; i < 6; i++) names.addAll(new ArrayList<>(names));
        Collections.shuffle(names, new Random(7));

        List<String> expected = new ArrayList<>(names);
        expected.sort(AlphabetSort.COMPARATOR);
        List<String> actual = new ArrayList<>(names);
        AlphabetSort.sort(actual, name -> name, null);
        assertEquals(expected, actual);

        // With a tie-break: same as Comparator.comparing(...).thenComparing(...).
        Comparator<String> byLength = Comparator.comparingInt(String::length);
        expected.sort(Comparator.comparing((String name) -> name, AlphabetSort.COMPARATOR).thenComparing(byLength));
        AlphabetSort.sort(actual, name -> name, byLength);
        assertEquals(expected, actual);
    }
}
