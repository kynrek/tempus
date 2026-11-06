package com.eddyizm.tempus.database.index;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.util.AlphabetSort;

import java.util.Date;
import java.util.Objects;

/** One artist of the library index, with its A–Z sort key worked out once when it is stored. */
@Entity(tableName = "indexed_artist")
public class IndexedArtist {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String name;
    /** {@link AlphabetSort#sortKey} of the name; rows are read back in (bucket, key) order. */
    @ColumnInfo(name = "name_key")
    @NonNull
    public String nameKey = "";
    /** 0 for names filed under "#", 1 for A–Z, so "#" comes first as in {@link AlphabetSort}. */
    @ColumnInfo(name = "name_bucket")
    public int nameBucket;
    @ColumnInfo(name = "cover_art_id")
    public String coverArtId;
    @ColumnInfo(name = "album_count")
    public int albumCount;
    /** Milliseconds, or null when not starred. */
    public Long starred;

    public static IndexedArtist from(@NonNull ArtistID3 artist) {
        IndexedArtist row = new IndexedArtist();
        row.id = artist.getId();
        row.name = artist.getName();
        row.nameKey = AlphabetSort.sortKey(artist.getName());
        row.nameBucket = AlphabetSort.isLetterKey(row.nameKey) ? 1 : 0;
        row.coverArtId = artist.getCoverArtId();
        row.albumCount = artist.getAlbumCount();
        row.starred = artist.getStarred() != null ? artist.getStarred().getTime() : null;
        return row;
    }

    public ArtistID3 toArtist() {
        return new ArtistID3(id, name, coverArtId, albumCount, starred != null ? new Date(starred) : null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexedArtist)) return false;
        IndexedArtist that = (IndexedArtist) o;
        return nameBucket == that.nameBucket && albumCount == that.albumCount && id.equals(that.id)
                && Objects.equals(name, that.name) && nameKey.equals(that.nameKey)
                && Objects.equals(coverArtId, that.coverArtId) && Objects.equals(starred, that.starred);
    }

    @Override
    public String toString() {
        return "{" + id + " " + name + " albums=" + albumCount + " starred=" + starred + " cover=" + coverArtId + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, coverArtId, albumCount, starred);
    }
}
