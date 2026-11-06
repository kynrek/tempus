package com.eddyizm.tempus.database.index;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.io.File;

/**
 * The library index: artists, albums and genres as last seen on the server, so the catalogue
 * opens straight away and works offline. It is a cache with its own file, separate from
 * tempo_db (queue, downloads, playlists), so that it can live on internal storage or the SD
 * card and can be thrown away and rebuilt from the server at any time.
 */
@Database(
        version = 1,
        entities = {IndexedArtist.class, IndexedAlbum.class, IndexedGenre.class, IndexMeta.class}
)
public abstract class LibraryIndexDatabase extends RoomDatabase {
    public static final String FILE_NAME = "library_index.db";

    public abstract LibraryIndexDao dao();

    /**
     * Opens the index at {@code file}. On the SD card the journal is a plain rollback journal:
     * WAL needs a shared-memory file, which FUSE-mounted exFAT cards handle poorly.
     */
    @NonNull
    public static LibraryIndexDatabase open(@NonNull Context context, @NonNull File file, boolean removable) {
        return Room.databaseBuilder(context.getApplicationContext(), LibraryIndexDatabase.class, file.getAbsolutePath())
                .setJournalMode(removable ? JournalMode.TRUNCATE : JournalMode.WRITE_AHEAD_LOGGING)
                // Only a cache: a schema change rebuilds it from the server.
                .fallbackToDestructiveMigration(true)
                .build();
    }
}
