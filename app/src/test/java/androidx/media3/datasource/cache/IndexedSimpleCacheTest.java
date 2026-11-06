package androidx.media3.datasource.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IndexedSimpleCacheTest {
    @Test
    public void subdirFollowsFromTheFileName() {
        String fromParts = IndexedSimpleCache.subdirFor(1013, 15728640L);
        assertEquals(fromParts, IndexedSimpleCache.subdirFor("1013.15728640.1793555249040.v3.exo"));
        // The touch timestamp is not part of it, so a file keeps its folder.
        assertEquals(fromParts, IndexedSimpleCache.subdirFor("1013.15728640.1.v3.exo"));
    }

    @Test
    public void namesThatAreNotSpansHaveNoSubdir() {
        assertEquals("", IndexedSimpleCache.subdirFor("6f1ce72f75236ed3.uid"));
        assertEquals("", IndexedSimpleCache.subdirFor("cached_content_index.exi"));
        assertEquals("", IndexedSimpleCache.subdirFor("x.0.1.v3.exo"));
    }

    @Test
    public void filesSpreadOverTheTenFoldersLikeRandomOnes() {
        int[] counts = new int[10];
        // A library like the box's: ids up to 3,000, one file per song plus some 5 MiB pieces.
        for (int id = 0; id < 3000; id++) {
            for (int piece = 0; piece < 8; piece++) {
                int dir = Integer.parseInt(IndexedSimpleCache.subdirFor(id, piece * 5242880L));
                counts[dir]++;
            }
        }
        for (int c : counts) assertTrue("uneven: " + java.util.Arrays.toString(counts), c > 2100 && c < 2700);
    }
}
