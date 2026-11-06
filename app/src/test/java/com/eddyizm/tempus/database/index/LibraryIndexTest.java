package com.eddyizm.tempus.database.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.Genre;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class LibraryIndexTest {
    private static AlbumID3 album(String id, String name, String artist, int year) {
        AlbumID3 a = new AlbumID3();
        a.setId(id);
        a.setName(name);
        a.setArtist(artist);
        a.setArtistId("ar-" + artist);
        a.setCoverArtId("al-" + id);
        a.setYear(year);
        a.setSongCount(12);
        a.setDuration(2400);
        a.setPlayCount(3L);
        a.setCreated(new Date(1_700_000_000_000L));
        a.setPlayed(new Date(1_710_000_000_000L));
        a.setGenre("Rock");
        a.setCompilation(false);
        return a;
    }

    @Test
    public void albumRoundTripKeepsTheCatalogueFields() {
        AlbumID3 a = album("1", "The Wall", "Pink Floyd", 1979);
        AlbumID3 back = IndexedAlbum.from(a).toAlbum();

        assertEquals("1", back.getId());
        assertEquals("The Wall", back.getName());
        assertEquals("Pink Floyd", back.getArtist());
        assertEquals("ar-Pink Floyd", back.getArtistId());
        assertEquals("al-1", back.getCoverArtId());
        assertEquals(1979, back.getYear());
        assertEquals(Integer.valueOf(12), back.getSongCount());
        assertEquals(Long.valueOf(3L), back.getPlayCount());
        assertEquals(a.getCreated(), back.getCreated());
        assertEquals(a.getPlayed(), back.getPlayed());
        assertNull(back.getStarred());
        assertEquals("Rock", back.getGenre());
        assertEquals(Boolean.FALSE, back.isCompilation());
    }

    @Test
    public void albumSortKeysAreStored() {
        IndexedAlbum row = IndexedAlbum.from(album("1", "The Wall", "Éric Serra", 1979));
        assertEquals("wall", row.nameKey);
        assertEquals(1, row.nameBucket);
        assertEquals("eric serra", row.artistKey);
        assertEquals(0, IndexedAlbum.from(album("2", "1999", "Prince", 1982)).nameBucket);
    }

    @Test
    public void artistRoundTrip() {
        ArtistID3 a = new ArtistID3("7", "The Beatles", "ar-7", 13, new Date(5));
        ArtistID3 back = IndexedArtist.from(a).toArtist();
        assertEquals("7", back.getId());
        assertEquals("The Beatles", back.getName());
        assertEquals("ar-7", back.getCoverArtId());
        assertEquals(13, back.getAlbumCount());
        assertEquals(new Date(5), back.getStarred());
        assertEquals("beatles", IndexedArtist.from(a).nameKey);
    }

    private static List<IndexedAlbum> rows(AlbumID3... albums) {
        List<IndexedAlbum> rows = new ArrayList<>();
        for (AlbumID3 a : albums) rows.add(IndexedAlbum.from(a));
        return rows;
    }

    private static LibraryIndexDao.Changes sync(List<IndexedAlbum> stored, List<IndexedAlbum> fresh,
                                                List<IndexedAlbum> written, List<String> deleted) {
        return LibraryIndexDao.sync(stored, fresh, r -> r.id, written::addAll, deleted::addAll);
    }

    @Test
    public void syncWritesNothingWhenOnlyTheOrderDiffers() {
        List<IndexedAlbum> written = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        LibraryIndexDao.Changes c = sync(rows(album("1", "A", "X", 2000), album("2", "B", "Y", 2001)),
                rows(album("2", "B", "Y", 2001), album("1", "A", "X", 2000)), written, deleted);
        assertFalse(c.any());
        assertTrue(written.isEmpty());
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void syncWritesChangedAndNewRowsAndDeletesGoneOnes() {
        AlbumID3 changed = album("1", "A", "X", 2000);
        changed.setPlayCount(4L);
        List<IndexedAlbum> written = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        LibraryIndexDao.Changes c = sync(rows(album("1", "A", "X", 2000), album("2", "B", "Y", 2001)),
                rows(changed, album("3", "C", "Z", 2002)), written, deleted);
        assertTrue(c.any());
        assertEquals(2, c.written);
        assertEquals(1, c.deleted);
        assertEquals(Arrays.asList("1", "3"), Arrays.asList(written.get(0).id, written.get(1).id));
        assertEquals(Collections.singletonList("2"), deleted);
    }

    @Test
    public void syncCountsARowListedTwiceOnce() {
        List<IndexedAlbum> written = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        LibraryIndexDao.Changes c = sync(rows(album("1", "A", "X", 2000)),
                rows(album("1", "A", "X", 2000), album("1", "A", "X", 2000)), written, deleted);
        assertFalse(c.any());
        assertEquals(1, c.repeated);
    }

    @Test
    public void genreRoundTrip() {
        Genre back = IndexedGenre.from(new Genre("Jazz", 10, 2)).toGenre();
        assertEquals("Jazz", back.getGenre());
        assertEquals(10, back.getSongCount());
        assertEquals(2, back.getAlbumCount());
    }
}
