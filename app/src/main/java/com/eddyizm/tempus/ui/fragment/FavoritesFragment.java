package com.eddyizm.tempus.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Starred2;
import com.eddyizm.tempus.repository.AlbumRepository;
import com.eddyizm.tempus.repository.ArtistRepository;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.Preferences;
import com.google.android.material.button.MaterialButtonToggleGroup;

/**
 * The Favorites Home tab: favorite albums, tracks or artists, picked with a toggle that sits in
 * the empty left side of the list's own header row so it costs no height. The pick is remembered.
 */
@UnstableApi
public class FavoritesFragment extends Fragment {
    private static final String ALBUMS = "albums";
    private static final String TRACKS = "tracks";
    private static final String ARTISTS = "artists";

    private MaterialButtonToggleGroup toggle;
    private View shuffleButton;
    private boolean shuffling;
    private int shuffleAttempt;
    @Nullable private List<Child> gathered;   // tracks collected so far by the running shuffle
    private String type;
    // The list was just built with this view, so the first resume doesn't need to load it again.
    private boolean freshView;

    private final FragmentManager.FragmentLifecycleCallbacks headerHook = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v, @Nullable Bundle savedInstanceState) {
            if (f.getParentFragment() == FavoritesFragment.this) placeToggle(v);
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(requireContext());
        root.setId(R.id.favorites_container);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        toggle = (MaterialButtonToggleGroup) inflater.inflate(R.layout.view_favorites_type_toggle, root, false);
        shuffleButton = inflater.inflate(R.layout.view_favorites_shuffle_button, root, false);
        shuffleButton.setOnClickListener(v -> shuffleFavorites());
        type = Preferences.getFavoritesType();
        toggle.check(buttonFor(type));
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String picked = typeFor(checkedId);
            if (picked.equals(type)) return;
            type = picked;
            Preferences.setFavoritesType(type);
            showList();
        });
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        getChildFragmentManager().registerFragmentLifecycleCallbacks(headerHook, false);
        Fragment current = getChildFragmentManager().findFragmentById(R.id.favorites_container);
        if (current == null || !type.equals(current.getTag())) showList();
        freshView = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Back from another tab: what's a favorite may have changed meanwhile (hearts on album, artist and player pages).
        if (!freshView) showList();
        freshView = false;
    }

    @Override
    public void onDestroyView() {
        getChildFragmentManager().unregisterFragmentLifecycleCallbacks(headerHook);
        if (toggle != null && toggle.getParent() instanceof ViewGroup) ((ViewGroup) toggle.getParent()).removeView(toggle);
        detach(shuffleButton);
        toggle = null;
        shuffleButton = null;
        super.onDestroyView();
    }

    private void showList() {
        if (!isAdded() || getChildFragmentManager().isStateSaved()) return;
        if (toggle != null && toggle.getParent() instanceof ViewGroup) ((ViewGroup) toggle.getParent()).removeView(toggle);
        detach(shuffleButton);
        getChildFragmentManager().beginTransaction()
                .replace(R.id.favorites_container, newList(type), type)
                .commitNow();
    }

    private Fragment newList(String type) {
        Fragment list;
        Bundle args = new Bundle();
        args.putBoolean(Constants.EMBEDDED_IN_HOME, true);
        switch (type) {
            case TRACKS:
                list = new SongListPageFragment();
                args.putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED);
                break;
            case ARTISTS:
                list = new ArtistListPageFragment();
                args.putString(Constants.ARTIST_STARRED, Constants.ARTIST_STARRED);
                break;
            default:
                list = new AlbumListPageFragment();
                args.putString(Constants.ALBUM_STARRED, Constants.ALBUM_STARRED);
                break;
        }
        list.setArguments(args);
        return list;
    }

    /** Song and album lists call their header album_info_sector, the artist list artist_info_sector. */
    private void placeToggle(View listView) {
        if (toggle == null) return;
        View header = listView.findViewById(R.id.album_info_sector);
        if (header == null) header = listView.findViewById(R.id.artist_info_sector);
        if (!(header instanceof ConstraintLayout)) return;

        if (toggle.getParent() instanceof ViewGroup) ((ViewGroup) toggle.getParent()).removeView(toggle);
        ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        lp.setMarginStart(Math.round(16 * getResources().getDisplayMetrics().density));
        ((ConstraintLayout) header).addView(toggle, lp);

        // Track lists have their own shuffle button; albums and artists get this one, left of their sort button.
        if (shuffleButton != null && !TRACKS.equals(type)) {
            detach(shuffleButton);
            View sort = header.findViewById(ALBUMS.equals(type) ? R.id.album_list_sort_image_view : R.id.artist_list_sort_image_view);
            int px = Math.round(getResources().getDisplayMetrics().density);
            ConstraintLayout.LayoutParams slp = new ConstraintLayout.LayoutParams(52 * px, 52 * px);
            slp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            slp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            if (sort != null) slp.endToStart = sort.getId();
            else slp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            slp.setMarginEnd(8 * px);
            ((ConstraintLayout) header).addView(shuffleButton, slp);
        }
    }

    private static void detach(@Nullable View view) {
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    /** Plays every track of the favorite albums, or the favorite artists' own tracks, in random order. */
    private void shuffleFavorites() {
        if (shuffling || !(getActivity() instanceof MainActivity)) return;
        shuffling = true;
        int attempt = ++shuffleAttempt;
        Toast.makeText(requireContext(), R.string.favorites_shuffle_loading, Toast.LENGTH_SHORT).show();
        // A lookup that never answers (offline, no favorites) mustn't leave the button dead.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (shuffling && attempt == shuffleAttempt) play(gathered != null ? gathered : new ArrayList<>());
        }, 30_000);
        boolean albums = ALBUMS.equals(type);
        App.getSubsonicClientInstance(false).getAlbumSongListClient().getStarred2().enqueue(new Callback<ApiResponse>() {
            @Override
            public void onResponse(@NonNull Call<ApiResponse> call, @NonNull Response<ApiResponse> response) {
                Starred2 starred = response.isSuccessful() && response.body() != null && response.body().getSubsonicResponse() != null
                        ? response.body().getSubsonicResponse().getStarred2() : null;
                List<String> ids = new ArrayList<>();
                if (starred != null && albums && starred.getAlbums() != null) {
                    for (AlbumID3 album : starred.getAlbums()) ids.add(album.getId());
                } else if (starred != null && !albums && starred.getArtists() != null) {
                    for (ArtistID3 artist : starred.getArtists()) ids.add(artist.getId());
                }
                if (!isAdded() || getView() == null) { shuffling = false; return; }
                if (albums) gather(ids, (id, done) -> once(new AlbumRepository().getAlbumTracks(id), done::accept));
                else gather(ids, (id, done) -> new ArtistRepository().getArtistAllSongs(id, done::accept));
            }

            @Override
            public void onFailure(@NonNull Call<ApiResponse> call, @NonNull Throwable t) {
                play(new ArrayList<>());
            }
        });
    }

    private interface TrackSource {
        void fetch(String id, Consumer<List<Child>> done);
    }

    /** Collects the tracks of every id, then plays them shuffled (up to the queue's size limit). */
    private void gather(List<String> ids, TrackSource source) {
        if (ids.isEmpty()) {
            play(new ArrayList<>());
            return;
        }
        List<Child> all = new ArrayList<>();
        gathered = all;
        int attempt = shuffleAttempt;
        int[] remaining = {ids.size()};
        for (String id : ids) {
            source.fetch(id, tracks -> {
                if (attempt != shuffleAttempt || !shuffling) return;   // timed out and already played
                if (tracks != null) all.addAll(tracks);
                if (--remaining[0] == 0) play(all);
            });
        }
    }

    private void play(List<Child> tracks) {
        if (!shuffling) return;
        shuffling = false;
        gathered = null;
        MainActivity activity = getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
        if (activity == null) return;
        if (tracks.isEmpty()) {
            Toast.makeText(activity, R.string.favorites_shuffle_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Child> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        int limit = Preferences.getQueueLimit();
        if (shuffled.size() > limit) shuffled = new ArrayList<>(shuffled.subList(0, limit));
        MediaManager.startQueue(activity.getMediaBrowserListenableFuture(), shuffled, 0);
        activity.setBottomSheetInPeek(true);
    }

    /** The first value a live result delivers. */
    private <T> void once(LiveData<T> data, Consumer<T> use) {
        data.observe(getViewLifecycleOwner(), new Observer<T>() {
            @Override
            public void onChanged(T value) {
                data.removeObserver(this);
                use.accept(value);
            }
        });
    }

    private static int buttonFor(String type) {
        switch (type) {
            case TRACKS:
                return R.id.favorites_type_tracks;
            case ARTISTS:
                return R.id.favorites_type_artists;
            default:
                return R.id.favorites_type_albums;
        }
    }

    private static String typeFor(int buttonId) {
        if (buttonId == R.id.favorites_type_tracks) return TRACKS;
        if (buttonId == R.id.favorites_type_artists) return ARTISTS;
        return ALBUMS;
    }
}
