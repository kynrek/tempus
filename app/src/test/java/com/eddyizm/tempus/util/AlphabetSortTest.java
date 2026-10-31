package com.eddyizm.tempus.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
}
