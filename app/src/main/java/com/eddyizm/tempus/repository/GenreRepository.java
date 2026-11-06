package com.eddyizm.tempus.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Genre;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class GenreRepository {
    /** All genres sorted by name, on the main thread; not called back when the request fails. */
    public void fetchGenres(java.util.function.Consumer<List<Genre>> onMain) {
        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getGenres()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse() != null && response.body().getSubsonicResponse().getGenres() != null) {
                            List<Genre> genreList = response.body().getSubsonicResponse().getGenres().getGenres();
                            if (genreList == null) {
                                onMain.accept(Collections.emptyList());
                                return;
                            }
                            onMain.accept(genreList.stream()
                                    .filter(genre -> genre.getGenre() != null)
                                    .sorted(Comparator.comparing(Genre::getGenre))
                                    .collect(Collectors.toList()));
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });
    }

    public MutableLiveData<List<Genre>> getGenres(boolean random, int size) {
        MutableLiveData<List<Genre>> genres = new MutableLiveData<>();

        App.getSubsonicClientInstance(false)
                .getBrowsingClient()
                .getGenres()
                .enqueue(new Callback<ApiResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                        if (response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse() != null && response.body().getSubsonicResponse().getGenres() != null) {
                            List<Genre> genreList = response.body().getSubsonicResponse().getGenres().getGenres();

                            if (genreList == null || genreList.isEmpty()) {
                                genres.setValue(Collections.emptyList());
                                return;
                            }

                            if (random) {
                                Collections.shuffle(genreList);
                            }

                            if (size != -1) {
                                genres.setValue(genreList.subList(0, Math.min(size, genreList.size())));
                            } else {
                                genres.setValue(genreList.stream().sorted(Comparator.comparing(Genre::getGenre)).collect(Collectors.toList()));
                            }
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {

                    }
                });

        return genres;
    }
}
