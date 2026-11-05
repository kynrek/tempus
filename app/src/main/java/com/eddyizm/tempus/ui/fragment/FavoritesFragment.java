package com.eddyizm.tempus.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.R;
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
        toggle = null;
        super.onDestroyView();
    }

    private void showList() {
        if (!isAdded() || getChildFragmentManager().isStateSaved()) return;
        if (toggle != null && toggle.getParent() instanceof ViewGroup) ((ViewGroup) toggle.getParent()).removeView(toggle);
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
