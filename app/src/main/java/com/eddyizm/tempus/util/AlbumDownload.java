package com.eddyizm.tempus.util;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.repository.AlbumRepository;
import com.eddyizm.tempus.subsonic.models.Child;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Downloads a whole album: the album page's download button and the one on each release of an
 * artist's page. Tracks come from the server, not from the shown list, so the songs left out
 * by "Downloaded only" are downloaded too.
 */
@OptIn(markerClass = UnstableApi.class)
public final class AlbumDownload {
    private AlbumDownload() {
    }

    public static void download(Context context, String albumId) {
        Context app = context.getApplicationContext();
        new AlbumRepository().getAlbumTracks(albumId, songs -> download(app, songs));
    }

    /** Into the app's downloads, or the folder picked in Settings; says how many started. */
    public static void download(Context context, List<Child> songs) {
        if (songs == null || songs.isEmpty()) return;
        if (Preferences.getDownloadDirectoryUri() == null) {
            DownloadUtil.getDownloadTracker(context).download(
                    MappingUtil.mapDownloads(songs),
                    songs.stream().map(Download::new).collect(Collectors.toList()));
        } else {
            songs.forEach(child -> ExternalAudioWriter.downloadToUserDirectory(context, child));
        }
        Toast.makeText(context, context.getResources().getQuantityString(
                R.plurals.songs_download_started, songs.size(), songs.size()), Toast.LENGTH_SHORT).show();
    }
}
