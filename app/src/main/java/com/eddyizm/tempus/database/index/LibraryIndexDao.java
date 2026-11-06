package com.eddyizm.tempus.database.index;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Dao
public abstract class LibraryIndexDao {
    /** Below SQLite's bound-variable limit on every Android version. */
    private static final int CHUNK = 500;

    @Query("SELECT * FROM indexed_artist ORDER BY name_bucket, name_key")
    public abstract List<IndexedArtist> artists();

    @Query("SELECT * FROM indexed_album ORDER BY name_bucket, name_key")
    public abstract List<IndexedAlbum> albums();

    @Query("SELECT * FROM indexed_genre ORDER BY genre")
    public abstract List<IndexedGenre> genres();

    @Query("SELECT value FROM index_meta WHERE `key` = :key")
    public abstract String meta(String key);

    @Upsert
    public abstract void putMeta(IndexMeta meta);

    @Upsert
    abstract void upsertArtists(List<IndexedArtist> rows);

    @Upsert
    abstract void upsertAlbums(List<IndexedAlbum> rows);

    @Upsert
    abstract void upsertGenres(List<IndexedGenre> rows);

    @Query("DELETE FROM indexed_artist WHERE id IN (:ids)")
    abstract void deleteArtists(List<String> ids);

    @Query("DELETE FROM indexed_album WHERE id IN (:ids)")
    abstract void deleteAlbums(List<String> ids);

    @Query("DELETE FROM indexed_genre WHERE genre IN (:ids)")
    abstract void deleteGenres(List<String> ids);

    @Query("DELETE FROM indexed_artist")
    abstract void clearArtists();

    @Query("DELETE FROM indexed_album")
    abstract void clearAlbums();

    @Query("DELETE FROM indexed_genre")
    abstract void clearGenres();

    @Query("DELETE FROM index_meta")
    abstract void clearMeta();

    /** Empties the index, for a different server or music folder. */
    @Transaction
    public void clearAll() {
        clearArtists();
        clearAlbums();
        clearGenres();
        clearMeta();
    }

    /** Makes the artist table equal to {@code fresh}, writing only the rows that changed. */
    @Transaction
    public Changes syncArtists(List<IndexedArtist> fresh) {
        return sync(artists(), fresh, r -> r.id, this::upsertArtists, this::deleteArtists);
    }

    @Transaction
    public Changes syncAlbums(List<IndexedAlbum> fresh) {
        return sync(albums(), fresh, r -> r.id, this::upsertAlbums, this::deleteAlbums);
    }

    @Transaction
    public Changes syncGenres(List<IndexedGenre> fresh) {
        return sync(genres(), fresh, r -> r.genre, this::upsertGenres, this::deleteGenres);
    }

    public static final class Changes {
        public final int written;
        public final int deleted;

        Changes(int written, int deleted) {
            this.written = written;
            this.deleted = deleted;
        }

        @Override
        public String toString() {
            return written + " written, " + deleted + " deleted";
        }
    }

    private interface Batch<T> {
        void run(List<T> items);
    }

    private static <R> Changes sync(List<R> stored, List<R> fresh, Function<R, String> key,
                                    Batch<R> upsert, Batch<String> delete) {
        Map<String, R> old = new HashMap<>(stored.size() * 2);
        for (R row : stored) old.put(key.apply(row), row);

        List<R> changed = new ArrayList<>();
        for (R row : fresh) {
            R before = old.remove(key.apply(row));
            if (!row.equals(before)) changed.add(row);
        }
        List<String> gone = new ArrayList<>(old.keySet());

        for (int i = 0; i < changed.size(); i += CHUNK) {
            upsert.run(changed.subList(i, Math.min(i + CHUNK, changed.size())));
        }
        for (int i = 0; i < gone.size(); i += CHUNK) {
            delete.run(gone.subList(i, Math.min(i + CHUNK, gone.size())));
        }
        return new Changes(changed.size(), gone.size());
    }
}
