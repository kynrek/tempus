package com.eddyizm.tempus.viewmodel;


import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.interfaces.StarCallback;
import com.eddyizm.tempus.repository.AlbumRepository;
import com.eddyizm.tempus.repository.ArtistRepository;
import com.eddyizm.tempus.repository.FavoriteRepository;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.ArtistInfo2;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.MappingUtil;
import com.eddyizm.tempus.util.NetworkUtil;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.util.FavoriteRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ArtistPageViewModel extends AndroidViewModel {
    private final AlbumRepository albumRepository;
    private final ArtistRepository artistRepository;
    private final FavoriteRepository favoriteRepository;

    private ArtistID3 artist;

    private final MutableLiveData<List<AlbumID3>> appearsOn = new MutableLiveData<>();
    private final MutableLiveData<List<AlbumID3>> releasesInOrder = new MutableLiveData<>();

    public ArtistPageViewModel(@NonNull Application application) {
        super(application);

        albumRepository = new AlbumRepository();
        artistRepository = new ArtistRepository();
        favoriteRepository = new FavoriteRepository();
    }

    public void fetchCategorizedAlbums(androidx.lifecycle.LifecycleOwner owner) {
        artistRepository.getArtist(artist.getId()).observe(owner, artistWithAlbums -> {
            if (artistWithAlbums != null && artistWithAlbums instanceof com.eddyizm.tempus.subsonic.models.ArtistWithAlbumsID3) {
                java.util.function.Predicate<AlbumID3> sameArtist = a -> Objects.equals(a.getArtistId(), Objects.requireNonNull(artist.getId()));
                com.eddyizm.tempus.subsonic.models.ArtistWithAlbumsID3 fullArtist = (com.eddyizm.tempus.subsonic.models.ArtistWithAlbumsID3) artistWithAlbums;
                List<AlbumID3> allAlbums = fullArtist.getAlbums();
                if (allAlbums != null) {
                    allAlbums.sort(Comparator.comparing(AlbumID3::getYear).reversed());

                    List<AlbumID3> primaryAlbums = allAlbums.stream()
                            .filter(sameArtist)
                            .collect(Collectors.toList());
                    // Oldest first; releases without a year go last.
                    List<AlbumID3> ordered = new ArrayList<>(primaryAlbums);
                    ordered.sort(Comparator.comparing((AlbumID3 a) -> a.getYear() <= 0)
                            .thenComparingInt(AlbumID3::getYear)
                            .thenComparing(a -> a.getName() != null ? a.getName() : "", String.CASE_INSENSITIVE_ORDER));
                    releasesInOrder.setValue(ordered);

                } else {
                    releasesInOrder.setValue(Collections.emptyList());
                    appearsOn.setValue(Collections.emptyList());
                }
                if (allAlbums != null) {
                    allAlbums.sort(Comparator.comparing(AlbumID3::getYear).reversed());

                    appearsOn.setValue(allAlbums.stream()
                            .filter(a -> !sameArtist.test(a))
                            .collect(Collectors.toList())
                    );
                }
            }
        });
    }

    /**
     * The kind of release (album, ep, single, live, ...), never blank: the artist page shows it
     * beside each release, capitalised from the first character of this value.
     */
    public static String sectionType(AlbumID3 album) {
        List<String> releaseTypes = album.getReleaseTypes();

        if (releaseTypes != null && !releaseTypes.isEmpty() && releaseTypes.get(0) != null) {
            String releaseType = releaseTypes.get(0).trim().toLowerCase();
            if (!releaseType.isEmpty()) {
                return releaseType;
            }
        }

        return getAutoType(album);
    }

    private static String getAutoType(AlbumID3 album) {
        // Fallback to song count if releaseTypes is not available
        int songCount = album.getSongCount() != null ? album.getSongCount() : 0;
        if (songCount >= 8) {
            return "album";
        } else if (songCount <= 2) {
            return "single";
        } else {
            return "ep";
        }
    }

    public LiveData<List<AlbumID3>> getReleasesInOrder() { return releasesInOrder; }
    public LiveData<List<AlbumID3>> getAppearsOn() { return appearsOn; }

    public LiveData<ArtistInfo2> getArtistInfo(String id) {
        return artistRepository.getArtistFullInfo(id);
    }

    public LiveData<List<Child>> getArtistTopSongList() {
        return artistRepository.getTopSongs(artist.getName(), 20);
    }

    /** What the Top songs section shows: the server's list, or a stand-in built from the artist's own tracks. */
    public static final class TopSongs {
        public final List<Child> songs;
        public final boolean fromServer;

        TopSongs(List<Child> songs, boolean fromServer) {
            this.songs = songs;
            this.fromServer = fromServer;
        }
    }

    /** How many stand-in tracks to list when the server has no top songs. */
    private static final int FALLBACK_TOP_SONGS = 5;
    /** Top songs per artist for this run of the app, so turning the phone or coming back is instant. */
    private static final android.util.LruCache<String, TopSongs> TOP_SONGS = new android.util.LruCache<>(40);

    private MutableLiveData<TopSongs> topSongs;
    private String topSongsArtistId;

    /**
     * Top songs for the current artist, worked out once per artist and kept. The page used to ask
     * the server on every build (each rotation, each visit): a server without them (Navidrome
     * without Last.fm) answered with nothing, the page waited three seconds to be sure, then
     * fetched every track of the artist to rank its own. Null until the answer is in.
     */
    public LiveData<TopSongs> getTopSongs() {
        String id = artist.getId();
        if (topSongs != null && id.equals(topSongsArtistId)) return topSongs;
        topSongsArtistId = id;
        topSongs = new MutableLiveData<>(TOP_SONGS.get(id));
        if (topSongs.getValue() != null) return topSongs;

        MutableLiveData<TopSongs> target = topSongs;
        String name = artist.getName();
        com.eddyizm.tempus.App.getSubsonicClientInstance(false).getBrowsingClient().getTopSongs(name, 20)
                .enqueue(new retrofit2.Callback<com.eddyizm.tempus.subsonic.base.ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull retrofit2.Call<com.eddyizm.tempus.subsonic.base.ApiResponse> call,
                                           @NonNull retrofit2.Response<com.eddyizm.tempus.subsonic.base.ApiResponse> response) {
                        List<Child> fetched = null;
                        if (response.isSuccessful() && response.body() != null
                                && response.body().getSubsonicResponse() != null
                                && response.body().getSubsonicResponse().getTopSongs() != null) {
                            fetched = response.body().getSubsonicResponse().getTopSongs().getSongs();
                        }
                        if (fetched != null && !fetched.isEmpty()) publishTopSongs(id, target, new TopSongs(fetched, true));
                        else rankOwnTracks(id, target);
                    }

                    @Override
                    public void onFailure(@NonNull retrofit2.Call<com.eddyizm.tempus.subsonic.base.ApiResponse> call, @NonNull Throwable t) {
                        rankOwnTracks(id, target);
                    }
                });
        return topSongs;
    }

    /** No top songs from the server: the artist's favourite, best rated and most played tracks (a random few if none yet). */
    private void rankOwnTracks(String artistId, MutableLiveData<TopSongs> target) {
        artistRepository.getArtistAllSongs(artistId, songs -> {
            List<Child> played = new ArrayList<>(songs != null ? songs : Collections.emptyList());
            played.sort((a, b) -> Long.compare(topScore(b), topScore(a)));
            if (!played.isEmpty() && topScore(played.get(0)) == 0) Collections.shuffle(played);
            List<Child> top = played.stream().limit(FALLBACK_TOP_SONGS).collect(Collectors.toList());
            publishTopSongs(artistId, target, new TopSongs(top, false));
        });
    }

    private void publishTopSongs(String artistId, MutableLiveData<TopSongs> target, TopSongs result) {
        // Kept only when there's something to show, so an artist whose tracks failed to load is tried again.
        if (!result.songs.isEmpty()) TOP_SONGS.put(artistId, result);
        target.postValue(result);
    }

    private static long topScore(Child c) {
        long plays = c.getPlayCount() != null ? c.getPlayCount() : 0;
        int rating = c.getUserRating() != null ? c.getUserRating() : 0;
        return (c.getStarred() != null ? 1_000_000L : 0) + rating * 10_000L + plays;
    }

    public LiveData<List<Child>> getArtistShuffleList() {
        return artistRepository.getRandomSong(artist, Preferences.getQueueLimit());
    }

    public LiveData<List<Child>> getArtistInstantMix() {
        return artistRepository.getInstantMix(artist, 30);
    }

    public ArtistID3 getArtist() {
        return artist;
    }

    public void setArtist(ArtistID3 artist) {
        this.artist = artist;
    }

    public void setFavorite(Context context) {
        if (FavoriteRegistry.resolve(FavoriteRegistry.Kind.ARTIST, artist.getId(), artist.getStarred() != null)) {
            if (NetworkUtil.isOffline()) {
                removeFavoriteOffline();
            } else {
                removeFavoriteOnline();
            }
        } else {
            if (NetworkUtil.isOffline()) {
                setFavoriteOffline();
            } else {
                setFavoriteOnline(context);
            }
        }
    }

    private void removeFavoriteOffline() {
        favoriteRepository.starLater(null, null, artist.getId(), false);
        artist.setStarred(null);
    }

    private void removeFavoriteOnline() {
        favoriteRepository.unstar(null, null, artist.getId(), new StarCallback() {
            @Override
            public void onError() {
                favoriteRepository.starLater(null, null, artist.getId(), false);
            }
        });

        artist.setStarred(null);
    }

        private void setFavoriteOffline() {
        favoriteRepository.starLater(null, null, artist.getId(), true);
        artist.setStarred(new Date());
    }

    private void setFavoriteOnline(Context context) {
        favoriteRepository.star(null, null, artist.getId(), new StarCallback() {
            @Override
            public void onError() {
                favoriteRepository.starLater(null, null, artist.getId(), true);
            }
        });

        artist.setStarred(new Date());

        if (Preferences.isStarredArtistsSyncEnabled()) {
            artistRepository.getArtistAllSongs(artist.getId(), new ArtistRepository.ArtistSongsCallback() {
                @OptIn(markerClass = UnstableApi.class)
                @Override
                public void onSongsCollected(List<Child> songs) {
                    if (songs != null && !songs.isEmpty()) {
                        DownloadUtil.getDownloadTracker(context).download(
                                MappingUtil.mapDownloads(songs),
                                songs.stream().map(Download::new).collect(Collectors.toList())
                        );
                    }
                }
            });
        } else {
            Log.d("ArtistSync", "Artist sync preference is disabled");
        }
    }

}
