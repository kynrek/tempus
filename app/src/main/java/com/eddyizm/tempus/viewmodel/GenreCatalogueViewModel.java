package com.eddyizm.tempus.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.eddyizm.tempus.repository.GenreRepository;
import com.eddyizm.tempus.repository.LibraryIndex;
import com.eddyizm.tempus.subsonic.models.Genre;

import java.util.List;

/** Genres tab: the library index at once, then the server's list if it differs. */
public class GenreCatalogueViewModel extends AndroidViewModel {
    private final GenreRepository genreRepository;
    private MutableLiveData<List<Genre>> genres;
    private boolean fromServer;

    public GenreCatalogueViewModel(@NonNull Application application) {
        super(application);

        genreRepository = new GenreRepository();
    }

    public void loadGenreList() {
        if (genres != null) return;
        genres = new MutableLiveData<>();

        LibraryIndex.genres(stored -> {
            if (stored != null && !fromServer && genres.getValue() == null) genres.setValue(stored);
        });
        genreRepository.fetchGenres(fresh -> {
            fromServer = true;
            if (genres.getValue() == null) {
                genres.setValue(fresh);
                LibraryIndex.storeGenres(fresh, null);
            } else {
                LibraryIndex.storeGenres(fresh, changed -> {
                    if (changed) genres.setValue(fresh);
                });
            }
        });
    }

    public LiveData<List<Genre>> getGenreList() {
        return genres;
    }
}
