package com.eddyizm.tempus.model;

import androidx.room.ColumnInfo;

/** The columns of a finished download that say what it belongs to, without the rest of the row. */
public class DownloadedKeys {
    @ColumnInfo(name = "id")
    public String id;
    @ColumnInfo(name = "album_id")
    public String albumId;
    @ColumnInfo(name = "artist_id")
    public String artistId;
    @ColumnInfo(name = "album_artist")
    public String albumArtist;
    @ColumnInfo(name = "artist")
    public String artist;
    @ColumnInfo(name = "genre")
    public String genre;
}
