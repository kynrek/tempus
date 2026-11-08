package com.eddyizm.tempus.database.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.model.DownloadedKeys;

import java.util.List;

@Dao
public interface DownloadDao {
    @Query("SELECT * FROM download WHERE download_state = 1 ORDER BY IFNULL(NULLIF(album_artist, ''), album), album, album_id, disc_number, track ASC")
    LiveData<List<Download>> getAll();

    @Query("SELECT * FROM download WHERE download_state = 1 ORDER BY IFNULL(NULLIF(album_artist, ''), album), album, album_id, disc_number, track ASC")
    List<Download> getAllSync();

    /** Just the ids of finished downloads, for "is it downloaded" marks. */
    @Query("SELECT id FROM download WHERE download_state = 1")
    LiveData<List<String>> getDownloadedIds();

    @Query("SELECT id, album_id, artist_id, album_artist, artist, genre FROM download WHERE download_state = 1")
    LiveData<List<DownloadedKeys>> getDownloadedKeys();

    @Query("SELECT * FROM download WHERE download_state = 1 AND (artist_id = :artistId OR album_artist = :name COLLATE NOCASE OR artist = :name COLLATE NOCASE)")
    LiveData<List<Download>> getByArtist(String artistId, String name);

    @Query("SELECT * FROM download WHERE id = :id")
    Download getOne(String id);

    @Query("SELECT * FROM download WHERE download_state = 0")
    List<Download> getFailedSync();

    @Query("SELECT COUNT(*) FROM download WHERE download_state = 1")
    int countDownloaded();

    @Query("SELECT DISTINCT album_id FROM download WHERE NULLIF(album_artist, '') IS NULL AND NULLIF(album_id, '') IS NOT NULL AND download_state = 1")
    List<String> getAlbumIdsWithoutAlbumArtist();

    @Query("SELECT COUNT(*) FROM download WHERE album_id = :albumId AND NULLIF(album_artist, '') IS NULL AND NULLIF(album, '') <> :albumTitle AND download_state = 1")
    int countUnfilledUnderAnotherTitle(String albumId, String albumTitle);

    // No download_state here on purpose. A song still downloading has no album artist yet, and
    // skipping it would leave it to finish later and sort away from its own album.
    @Query("UPDATE download SET album_artist = :albumArtist WHERE album_id = :albumId AND album = :albumTitle AND NULLIF(album_artist, '') IS NULL")
    int setAlbumArtist(String albumId, String albumTitle, String albumArtist);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Download download);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Download> downloads);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAllKeepingExisting(List<Download> downloads);

    @Query("UPDATE download SET download_state = 1 WHERE id = :id")
    void update(String id);

    @Query("DELETE FROM download WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM download WHERE id = :id AND download_state = 0")
    void deleteFailed(String id);

    @Query("DELETE FROM download WHERE id IN (:ids)")
    void deleteByIds(List<String> ids);

    @Query("DELETE FROM download")
    void deleteAll();
}