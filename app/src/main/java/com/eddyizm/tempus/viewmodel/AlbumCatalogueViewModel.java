package com.eddyizm.tempus.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.interfaces.MediaCallback;
import com.eddyizm.tempus.repository.LibraryIndex;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.AlbumID3;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;

/**
 * Albums tab. The server hands albums out 500 at a time, so a whole library takes several round
 * trips. With a library index (or a list already on screen) that list stays up while the pages
 * are fetched in the background, and is swapped for the server's only once all of them are in,
 * and only if something changed. Without one, pages show as they arrive, with the spinner.
 */
public class AlbumCatalogueViewModel extends AndroidViewModel {
    private static final int PAGE_SIZE = 500;

    private final MutableLiveData<List<AlbumID3>> albumList = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(true);

    private int page = 0;
    private Status status = Status.STOPPED;
    /** Bumped by every load and stop, so replies to an older load are dropped. */
    private int generation = 0;
    /** The pages of the load in progress. */
    private List<AlbumID3> fetched = new ArrayList<>();
    private boolean indexRead;
    /** A complete list (stored or from an earlier load) is on screen: refresh it out of sight. */
    private boolean showingComplete;

    public AlbumCatalogueViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<List<AlbumID3>> getAlbumList() {
        return albumList;
    }

    public LiveData<Boolean> getLoadingStatus() {
        return loading;
    }

    public void loadAlbums() {
        int load = ++generation;
        page = 0;
        status = Status.RUNNING;
        fetched = new ArrayList<>();

        if (!indexRead) {
            indexRead = true;
            loading.setValue(true);
            LibraryIndex.albums(stored -> {
                List<AlbumID3> shown = albumList.getValue();
                if (stored != null && load == generation && (shown == null || shown.isEmpty())) {
                    showingComplete = true;
                    albumList.setValue(stored);
                    loading.setValue(false);
                }
            });
        } else if (!showingComplete) {
            // An earlier load stopped half way: start over from an empty list, as before.
            albumList.setValue(new ArrayList<>());
            loading.setValue(true);
        }

        loadPage(load);
    }

    public void stopLoading() {
        status = Status.STOPPED;
        generation++;
        loading.setValue(false);
    }

    private void loadPage(int load) {
        retrieveAlbums(new MediaCallback() {
            @Override
            public void onError(Exception exception) {
                if (load != generation) return;
                status = Status.STOPPED;
                loading.setValue(false);
            }

            @Override
            public void onLoadMedia(List<?> media) {
                if (load != generation || status == Status.STOPPED) {
                    return;
                }

                //noinspection unchecked
                fetched.addAll((List<AlbumID3>) media);
                boolean more = media.size() == PAGE_SIZE;

                if (!showingComplete) {
                    albumList.setValue(new ArrayList<>(fetched));
                }

                if (more) {
                    loadPage(load);
                    return;
                }

                status = Status.STOPPED;
                loading.setValue(false);
                List<AlbumID3> complete = fetched;
                if (showingComplete) {
                    // Compared with the index off the main thread; swapped only if it changed.
                    LibraryIndex.storeAlbums(complete, changed -> {
                        if (changed && load == generation) albumList.setValue(complete);
                    });
                } else {
                    showingComplete = true;
                    LibraryIndex.storeAlbums(complete, null);
                }
            }
        }, PAGE_SIZE, PAGE_SIZE * page++);
    }

    private void retrieveAlbums(MediaCallback callback, int size, int offset) {
        App.getSubsonicClientInstance(false)
                .getAlbumSongListClient()
                .getAlbumList2("alphabeticalByName", size, offset, null, null)
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull retrofit2.Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getAlbumList2() != null) {
                            // A page past the end comes back with no "album" at all.
                            List<AlbumID3> albums = response.body().getSubsonicResponse().getAlbumList2().getAlbums();
                            callback.onLoadMedia(albums != null ? new ArrayList<>(albums) : new ArrayList<>());
                        } else {
                            callback.onError(new Exception("No album list in the response"));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                        callback.onError(new Exception(t.getMessage()));
                    }
                });
    }

    private enum Status {
        RUNNING,
        STOPPED
    }
}
