package com.eddyizm.tempus.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.repository.LibraryIndex;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.IndexID3;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;

/**
 * Artists tab: shows the library index at once, then the server's list when it arrives, but only
 * if it differs; the server's list is stored back into the index.
 */
public class ArtistCatalogueViewModel extends AndroidViewModel {
    private final MutableLiveData<List<ArtistID3>> artistList = new MutableLiveData<>(new ArrayList<>());
    private boolean indexRead;
    private boolean fromServer;

    public ArtistCatalogueViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<List<ArtistID3>> getArtistList() {
        return artistList;
    }

    public void loadArtists() {
        if (!indexRead) {
            indexRead = true;
            LibraryIndex.artists(stored -> {
                List<ArtistID3> shown = artistList.getValue();
                if (stored != null && !fromServer && (shown == null || shown.isEmpty())) {
                    artistList.setValue(stored);
                }
            });
        }

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getArtists()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull retrofit2.Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse().getArtists() != null) {
                            List<ArtistID3> artists = new ArrayList<>();

                            List<IndexID3> indices = response.body().getSubsonicResponse().getArtists().getIndices();
                            if (indices != null) {
                                for (IndexID3 index : indices) {
                                    if (index.getArtists() != null) artists.addAll(index.getArtists());
                                }
                            }

                            fromServer = true;
                            if (!LibraryIndex.sameArtists(artistList.getValue(), artists)) {
                                artistList.setValue(artists);
                                LibraryIndex.storeArtists(artists);
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }
}
