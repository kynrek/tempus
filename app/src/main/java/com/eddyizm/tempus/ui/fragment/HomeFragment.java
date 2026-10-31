package com.eddyizm.tempus.ui.fragment;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.FragmentHomeBinding;
import com.eddyizm.tempus.navigation.NavigationController;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.fragment.pager.HomePager;
import com.eddyizm.tempus.util.Constants;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.Objects;

@UnstableApi
public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";

    private FragmentHomeBinding bind;
    private MainActivity activity;
    private NavigationController navigationController;

    private MaterialToolbar materialToolbar;
    private TabLayout tabLayout;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();
        navigationController = activity.getNavigationController();
        bind = FragmentHomeBinding.inflate(inflater, container, false);
        return bind.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initAppBar();
        initHomePager();
    }

    @Override
    public void onStart() {
        super.onStart();

        activity.toggleBottomNavigationBarVisibilityOnOrientationChange();
        activity.setBottomSheetVisibility(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initAppBar() {
        materialToolbar = bind.getRoot().findViewById(R.id.toolbar);

        activity.setSupportActionBar(materialToolbar);
        navigationController.setHamburgerMenuForLandscape(activity, materialToolbar);

        Objects.requireNonNull(materialToolbar.getOverflowIcon()).setTint(requireContext().getResources().getColor(R.color.titleTextColor, null));

        tabLayout = new TabLayout(requireContext());
        tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
        tabLayout.setTabMode(TabLayout.MODE_FIXED);

        // The tabs sit in the toolbar row itself, beside the drawer button, to keep the header to one row.
        materialToolbar.addView(tabLayout, new Toolbar.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
    }

    private void initHomePager() {
        HomePager pager = new HomePager(this);

        // Car layout: browse the library straight from Home instead of Music / Podcast / Radio.
        pager.addFragment(embedded(new AlbumCatalogueFragment()), getString(R.string.album_catalogue_title), R.drawable.ic_home);
        pager.addFragment(embedded(new ArtistCatalogueFragment()), getString(R.string.artist_catalogue_title), R.drawable.ic_home);
        pager.addFragment(embedded(new GenreCatalogueFragment()), getString(R.string.library_title_genre), R.drawable.ic_home);
        Fragment playlists = embedded(new PlaylistCatalogueFragment());
        playlists.requireArguments().putString(Constants.PLAYLIST_ALL, Constants.PLAYLIST_ALL);
        pager.addFragment(playlists, getString(R.string.library_title_playlist), R.drawable.ic_home);

        Fragment favorites = embedded(new SongListPageFragment());
        favorites.requireArguments().putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED);
        pager.addFragment(favorites, getString(R.string.home_tab_favorites), R.drawable.ic_home);

        Fragment recent = embedded(new AlbumListPageFragment());
        recent.requireArguments().putString(Constants.ALBUM_RECENTLY_PLAYED, Constants.ALBUM_RECENTLY_PLAYED);
        pager.addFragment(recent, getString(R.string.home_tab_recent), R.drawable.ic_home);

        bind.homeViewPager.setAdapter(pager);
        bind.homeViewPager.setOffscreenPageLimit(6);
        bind.homeViewPager.setUserInputEnabled(false);

        new TabLayoutMediator(tabLayout, bind.homeViewPager,
                (tab, position) -> {
                    tab.setText(pager.getPageTitle(position));
                    // tab.setIcon(pager.getPageIcon(position));
                }
        ).attach();
    }

    private static Fragment embedded(Fragment fragment) {
        Bundle args = new Bundle();
        args.putBoolean(Constants.EMBEDDED_IN_HOME, true);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * The music library line in the drawer is a filter for library-scoped screens. The Home
     * tabs (Albums, Artists, Genres, Playlists, Favorites, Recent) list the whole server, so the line would name a filter that isn't applied.
     *
     * The toolbar reads this on every update instead of being pushed the page once.
     */
    public boolean isLibraryScopedTab() {
        return false;
    }
}
