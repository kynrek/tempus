package com.eddyizm.tempus.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eddyizm.tempus.model.Queue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The queue LiveData passes on a list only when its songs or their order change. */
public class QueueRepositorySameSongsTest {

    private static Queue row(String id, int order, long lastPlay, long position) {
        return new Queue(id, order, lastPlay, position, null);
    }

    private static List<Queue> rows(String... ids) {
        List<Queue> list = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) list.add(row(ids[i], i, 0, 0));
        return list;
    }

    @Test
    public void resumePointWriteIsNotAChange() {
        List<Queue> before = rows("a", "b", "c");
        List<Queue> after = rows("a", "b", "c");
        after.set(1, row("b", 1, 123456L, 42_000L));
        assertTrue(QueueRepository.sameSongs(before, after));
    }

    @Test
    public void reorderIsAChange() {
        assertFalse(QueueRepository.sameSongs(rows("a", "b", "c"), rows("a", "c", "b")));
    }

    @Test
    public void addedOrRemovedSongIsAChange() {
        assertFalse(QueueRepository.sameSongs(rows("a", "b"), rows("a", "b", "c")));
        assertFalse(QueueRepository.sameSongs(rows("a", "b", "c"), rows("a", "c")));
    }

    @Test
    public void sameSongQueuedTwiceKeepsBothPositions() {
        assertTrue(QueueRepository.sameSongs(rows("a", "b", "a"), rows("a", "b", "a")));
        assertFalse(QueueRepository.sameSongs(rows("a", "b", "a"), rows("a", "a", "b")));
    }

    @Test
    public void nullListsDifferFromLists() {
        assertFalse(QueueRepository.sameSongs(null, rows("a")));
        assertFalse(QueueRepository.sameSongs(Arrays.asList(), null));
    }
}
