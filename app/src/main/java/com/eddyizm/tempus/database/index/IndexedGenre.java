package com.eddyizm.tempus.database.index;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.eddyizm.tempus.subsonic.models.Genre;

import java.util.Objects;

/** One genre of the library index. */
@Entity(tableName = "indexed_genre")
public class IndexedGenre {
    @PrimaryKey
    @NonNull
    public String genre = "";
    @ColumnInfo(name = "song_count")
    public int songCount;
    @ColumnInfo(name = "album_count")
    public int albumCount;

    public static IndexedGenre from(@NonNull Genre genre) {
        IndexedGenre row = new IndexedGenre();
        row.genre = genre.getGenre();
        row.songCount = genre.getSongCount();
        row.albumCount = genre.getAlbumCount();
        return row;
    }

    public Genre toGenre() {
        return new Genre(genre, songCount, albumCount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexedGenre)) return false;
        IndexedGenre that = (IndexedGenre) o;
        return songCount == that.songCount && albumCount == that.albumCount && genre.equals(that.genre);
    }

    @Override
    public int hashCode() {
        return Objects.hash(genre, songCount, albumCount);
    }
}
