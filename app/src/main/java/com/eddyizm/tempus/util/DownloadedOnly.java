package com.eddyizm.tempus.util;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.database.AppDatabase;
import com.eddyizm.tempus.model.DownloadedKeys;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.Genre;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The "downloaded only" switch: while it's on, the library lists keep just the music that has been
 * downloaded to this device. The lists still come from the server; each one is wrapped here and
 * filtered against the download table, and re-filtered when the switch flips or a download lands.
 */
@MainThread
public final class DownloadedOnly {
    /** What is downloaded, by the keys the lists are filtered on. */
    public static final class Index {
        final Set<String> songIds = new HashSet<>();
        final Set<String> albumIds = new HashSet<>();
        final Set<String> artistIds = new HashSet<>();
        final Set<String> artistNames = new HashSet<>();
        final Set<String> genres = new HashSet<>();

        Index(List<DownloadedKeys> rows) {
            for (DownloadedKeys row : rows) {
                add(songIds, row.id);
                add(albumIds, row.albumId);
                add(artistIds, row.artistId);
                add(artistNames, lower(row.albumArtist));
                add(artistNames, lower(row.artist));
                add(genres, lower(row.genre));
            }
        }

        private static void add(Set<String> set, @Nullable String key) {
            if (key != null && !key.isEmpty()) set.add(key);
        }
    }

    /** Stands for "switch off": lists pass through untouched. */
    private static final Index ALL = new Index(Collections.emptyList());

    private static MutableLiveData<Boolean> enabled;
    /** ALL while off, the download index while on; no value while on and the index is still loading. */
    private static MediatorLiveData<Index> active;

    private DownloadedOnly() {
    }

    public static boolean isEnabled() {
        return Boolean.TRUE.equals(enabled().getValue());
    }

    public static LiveData<Boolean> getEnabled() {
        return enabled();
    }

    public static void setEnabled(boolean on) {
        Preferences.setDownloadedOnly(on);
        enabled().setValue(on);
    }

    public static LiveData<List<AlbumID3>> albums(LiveData<List<AlbumID3>> source) {
        return filter(source, (index, album) -> album.getId() != null && index.albumIds.contains(album.getId()));
    }

    public static LiveData<List<ArtistID3>> artists(LiveData<List<ArtistID3>> source) {
        return filter(source, (index, artist) -> (artist.getId() != null && index.artistIds.contains(artist.getId()))
                || index.artistNames.contains(lower(artist.getName())));
    }

    public static LiveData<List<Genre>> genres(LiveData<List<Genre>> source) {
        return filter(source, (index, genre) -> index.genres.contains(lower(genre.getGenre())));
    }

    public static LiveData<List<Child>> songs(LiveData<List<Child>> source) {
        return filter(source, (index, song) -> index.songIds.contains(song.getId()));
    }

    /** The songs of a list that are downloaded, or the list itself while the switch is off. */
    public static List<Child> songs(List<Child> list) {
        Index index = active().getValue();
        if (list == null || index == null || index == ALL) return list;
        return keep(list, index, (i, song) -> i.songIds.contains(song.getId()));
    }

    private static <T> LiveData<List<T>> filter(LiveData<List<T>> source, BiPredicate<Index, T> keep) {
        MediatorLiveData<List<T>> out = new MediatorLiveData<>();
        boolean[] arrived = {false};
        Runnable update = () -> {
            Index index = active().getValue();
            if (index == null || !arrived[0]) return;
            List<T> list = source.getValue();
            out.setValue(index == ALL || list == null ? list : keep(list, index, keep));
        };
        out.addSource(source, list -> {
            arrived[0] = true;
            update.run();
        });
        out.addSource(active(), index -> update.run());
        return out;
    }

    private static <T> List<T> keep(List<T> list, Index index, BiPredicate<Index, T> keep) {
        List<T> kept = new ArrayList<>();
        for (T item : list) {
            if (item != null && keep.test(index, item)) kept.add(item);
        }
        return kept;
    }

    private static MutableLiveData<Boolean> enabled() {
        if (enabled == null) enabled = new MutableLiveData<>(Preferences.isDownloadedOnly());
        return enabled;
    }

    private static MediatorLiveData<Index> active() {
        if (active == null) {
            MediatorLiveData<Index> state = new MediatorLiveData<>();
            // Only watched while the switch is on, so the download table isn't read for nothing.
            LiveData<List<DownloadedKeys>> downloads = AppDatabase.getInstance().downloadDao().getDownloadedKeys();
            boolean[] watching = {false};
            state.addSource(enabled(), on -> {
                if (Boolean.TRUE.equals(on)) {
                    if (watching[0]) return;
                    watching[0] = true;
                    state.addSource(downloads, rows -> {
                        if (rows != null) state.setValue(new Index(rows));
                    });
                } else {
                    watching[0] = false;
                    state.removeSource(downloads);
                    if (state.getValue() != ALL) state.setValue(ALL);
                }
            });
            active = state;
        }
        return active;
    }

    private static String lower(@Nullable String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
