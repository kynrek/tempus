package com.eddyizm.tempus.database.index;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.util.AlphabetSort;

import java.util.Date;
import java.util.Objects;

/**
 * One album of the library index: the fields the Albums tab shows and sorts by (the same ones
 * {@link AlbumID3#strippedForNav} keeps, less the disc titles), with the A–Z sort keys stored.
 * The album page fetches the full album itself.
 */
@Entity(tableName = "indexed_album")
public class IndexedAlbum {
    @PrimaryKey
    @NonNull
    public String id = "";
    public String name;
    @ColumnInfo(name = "name_key")
    @NonNull
    public String nameKey = "";
    @ColumnInfo(name = "name_bucket")
    public int nameBucket;
    public String artist;
    @ColumnInfo(name = "artist_key")
    @NonNull
    public String artistKey = "";
    @ColumnInfo(name = "artist_id")
    public String artistId;
    @ColumnInfo(name = "display_artist")
    public String displayArtist;
    @ColumnInfo(name = "cover_art_id")
    public String coverArtId;
    @ColumnInfo(name = "song_count")
    public Integer songCount;
    public Integer duration;
    @ColumnInfo(name = "play_count")
    public Long playCount;
    /** Dates in milliseconds. */
    public Long created;
    public Long starred;
    public Long played;
    public int year;
    public String genre;
    @ColumnInfo(name = "user_rating")
    public Integer userRating;
    @ColumnInfo(name = "sort_name")
    public String sortName;
    @ColumnInfo(name = "music_brainz_id")
    public String musicBrainzId;
    @ColumnInfo(name = "is_compilation")
    public Boolean isCompilation;

    public static IndexedAlbum from(@NonNull AlbumID3 album) {
        IndexedAlbum row = new IndexedAlbum();
        row.id = album.getId();
        row.name = album.getName();
        row.nameKey = AlphabetSort.sortKey(album.getName());
        row.nameBucket = AlphabetSort.isLetterKey(row.nameKey) ? 1 : 0;
        row.artist = album.getArtist();
        row.artistKey = AlphabetSort.sortKey(album.getArtist());
        row.artistId = album.getArtistId();
        row.displayArtist = album.getDisplayArtist();
        row.coverArtId = album.getCoverArtId();
        row.songCount = album.getSongCount();
        row.duration = album.getDuration();
        row.playCount = album.getPlayCount();
        row.created = millis(album.getCreated());
        row.starred = millis(album.getStarred());
        row.played = millis(album.getPlayed());
        row.year = album.getYear();
        row.genre = album.getGenre();
        row.userRating = album.getUserRating();
        row.sortName = album.getSortName();
        row.musicBrainzId = album.getMusicBrainzId();
        row.isCompilation = album.isCompilation();
        return row;
    }

    public AlbumID3 toAlbum() {
        AlbumID3 album = new AlbumID3();
        album.setId(id);
        album.setName(name);
        album.setArtist(artist);
        album.setArtistId(artistId);
        album.setDisplayArtist(displayArtist);
        album.setCoverArtId(coverArtId);
        album.setSongCount(songCount);
        album.setDuration(duration);
        album.setPlayCount(playCount);
        album.setCreated(date(created));
        album.setStarred(date(starred));
        album.setPlayed(date(played));
        album.setYear(year);
        album.setGenre(genre);
        album.setUserRating(userRating);
        album.setSortName(sortName);
        album.setMusicBrainzId(musicBrainzId);
        album.setCompilation(isCompilation);
        return album;
    }

    private static Long millis(Date date) {
        return date != null ? date.getTime() : null;
    }

    private static Date date(Long millis) {
        return millis != null ? new Date(millis) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexedAlbum)) return false;
        IndexedAlbum that = (IndexedAlbum) o;
        return nameBucket == that.nameBucket && year == that.year && id.equals(that.id)
                && Objects.equals(name, that.name) && nameKey.equals(that.nameKey)
                && Objects.equals(artist, that.artist) && artistKey.equals(that.artistKey)
                && Objects.equals(artistId, that.artistId) && Objects.equals(displayArtist, that.displayArtist)
                && Objects.equals(coverArtId, that.coverArtId) && Objects.equals(songCount, that.songCount)
                && Objects.equals(duration, that.duration) && Objects.equals(playCount, that.playCount)
                && Objects.equals(created, that.created) && Objects.equals(starred, that.starred)
                && Objects.equals(played, that.played) && Objects.equals(genre, that.genre)
                && Objects.equals(userRating, that.userRating) && Objects.equals(sortName, that.sortName)
                && Objects.equals(musicBrainzId, that.musicBrainzId) && Objects.equals(isCompilation, that.isCompilation);
    }

    @Override
    public String toString() {
        return "{" + id + " " + name + " / " + artist + " " + year + " songs=" + songCount + " dur=" + duration
                + " plays=" + playCount + " created=" + created + " starred=" + starred + " played=" + played
                + " genre=" + genre + " rating=" + userRating + " sort=" + sortName + " mbid=" + musicBrainzId
                + " comp=" + isCompilation + " cover=" + coverArtId + " artistId=" + artistId + " display=" + displayArtist + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, artist, coverArtId, year, played, playCount);
    }
}
