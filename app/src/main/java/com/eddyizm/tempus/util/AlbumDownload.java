package com.eddyizm.tempus.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.repository.AlbumRepository;
import com.eddyizm.tempus.repository.ArtistRepository;
import com.eddyizm.tempus.subsonic.models.Child;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Downloads a whole album: the album page's download button and the one on each release of an
 * artist's page. Tracks come from the server, not from the shown list, so the songs left out
 * by "Downloaded only" are downloaded too.
 */
@OptIn(markerClass = UnstableApi.class)
public final class AlbumDownload {
    /** One artist at a time, in the order asked, so artists queued back to back keep their order. */
    private static final ExecutorService ARTIST_QUEUER = Executors.newSingleThreadExecutor();

    private AlbumDownload() {
    }

    /**
     * Every album by an artist: the download button on the artist page. Building a request takes
     * a few ms a track, so an artist with a thousand tracks is queued off the main thread.
     * {@code done} runs on the main thread once the track list is in (or failed).
     */
    public static void artist(Context context, String artistId, @Nullable Runnable done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(app, R.string.artist_bottom_sheet_loading_tracks, Toast.LENGTH_SHORT).show();
        new ArtistRepository().getArtistAllSongs(artistId, songs -> main.post(() -> {
            if (done != null) done.run();
            if (songs == null || songs.isEmpty()) return;
            List<Child> batch = new ArrayList<>(songs);
            ARTIST_QUEUER.execute(() -> store(app, batch));
            Toast.makeText(app, app.getResources().getQuantityString(
                    R.plurals.songs_download_started, batch.size(), batch.size()), Toast.LENGTH_SHORT).show();
        }));
    }

    public static void download(Context context, String albumId) {
        Context app = context.getApplicationContext();
        new AlbumRepository().getAlbumTracks(albumId, songs -> download(app, songs));
    }

    /** Into the app's downloads, or the folder picked in Settings; says how many started. */
    public static void download(Context context, List<Child> songs) {
        if (songs == null || songs.isEmpty()) return;
        store(context, songs);
        Toast.makeText(context, context.getResources().getQuantityString(
                R.plurals.songs_download_started, songs.size(), songs.size()), Toast.LENGTH_SHORT).show();
    }

    private static void store(Context context, List<Child> songs) {
        if (Preferences.getDownloadDirectoryUri() == null) {
            DownloadUtil.getDownloadTracker(context).download(
                    MappingUtil.mapDownloads(songs),
                    songs.stream().map(Download::new).collect(Collectors.toList()));
        } else {
            songs.forEach(child -> ExternalAudioWriter.downloadToUserDirectory(context, child));
        }
    }
}
