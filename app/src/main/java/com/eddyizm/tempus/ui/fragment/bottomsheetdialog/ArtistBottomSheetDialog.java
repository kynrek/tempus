package com.eddyizm.tempus.ui.fragment.bottomsheetdialog;

import android.content.ComponentName;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.util.QueueAdder;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.model.Download;
import com.eddyizm.tempus.repository.ArtistRepository;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.subsonic.models.ArtistID3;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.dialog.PlaylistChooserDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.ExternalAudioWriter;
import com.eddyizm.tempus.util.MappingUtil;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.util.MusicUtil;
import com.eddyizm.tempus.viewmodel.ArtistBottomSheetViewModel;
import com.eddyizm.tempus.util.FavoriteRegistry;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.common.util.concurrent.ListenableFuture;


@UnstableApi
public class ArtistBottomSheetDialog extends CarBottomSheetDialogFragment implements View.OnClickListener {
    private static final String TAG = "AlbumBottomSheetDialog";

    private ArtistBottomSheetViewModel artistBottomSheetViewModel;
    private ArtistID3 artist;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private boolean isFirstBatch = true;
    // Guard to prevent concurrent taps that register multiple observers on the cached LiveData
    private boolean isLoadingTracks = false;
    /** One at a time, in the order asked, so artists queued back to back keep their order. */
    private static final java.util.concurrent.ExecutorService DOWNLOAD_QUEUER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_artist_dialog, container, false);

        Bundle args = getArguments();
        if (args == null || (artist = args.getParcelable(Constants.ARTIST_OBJECT)) == null) {
            dismissAllowingStateLoss();
            return view;
        }
        artist = artist.strippedForNav();

        artistBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(ArtistBottomSheetViewModel.class);
        artistBottomSheetViewModel.setArtist(artist);

        init(view);

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (artist != null) {
            initializeMediaBrowser();
        }
    }

    @Override
    public void onStop() {
        if (mediaBrowserListenableFuture != null) {
            releaseMediaBrowser();
        }
        super.onStop();
    }

    // TODO Use the viewmodel as a conduit and avoid direct calls
    private void init(View view) {
        ImageView coverArtist = view.findViewById(R.id.artist_cover_image_view);
        CustomGlideRequest.Builder
                .from(requireContext(), artistBottomSheetViewModel.getArtist().getCoverArtId(), CustomGlideRequest.ResourceType.Artist)
                .build()
                .into(coverArtist);

        TextView nameArtist = view.findViewById(R.id.song_title_text_view);
        nameArtist.setText(artistBottomSheetViewModel.getArtist().getName());
        nameArtist.setSelected(true);

        ToggleButton favoriteToggle = view.findViewById(R.id.button_favorite);
        favoriteToggle.setChecked(FavoriteRegistry.resolve(FavoriteRegistry.Kind.ARTIST, artistBottomSheetViewModel.getArtist().getId(), artistBottomSheetViewModel.getArtist().getStarred() != null));
        favoriteToggle.setOnClickListener(v -> {
            artistBottomSheetViewModel.setFavorite(requireContext());
        });

        TextView playRadio = view.findViewById(R.id.play_radio_text_view);
        playRadio.setOnClickListener(v -> {
            MainActivity activity = (MainActivity) getActivity();
            if (activity == null) return;

            ListenableFuture<MediaBrowser> activityBrowserFuture = activity.getMediaBrowserListenableFuture();
            if (activityBrowserFuture == null) return;

            isFirstBatch = true;
            Toast.makeText(requireContext(), R.string.bottom_sheet_generating_instant_mix, Toast.LENGTH_SHORT).show();

            artistBottomSheetViewModel.getArtistInstantMix(activity, artist).observe(activity, media -> {
                if (media == null || media.isEmpty()) return;
                if (getActivity() == null) return;

                MusicUtil.ratingFilter(media);

                if (isFirstBatch) {
                    isFirstBatch = false;
                    MediaManager.startQueue(activityBrowserFuture, media, 0);
                    activity.setBottomSheetInPeek(true);
                    if (isAdded()) {
                        dismissBottomSheet();
                    }
                } else {
                    MediaManager.enqueue(activityBrowserFuture, media, true);
                }
            });
        });

        TextView playRandom = view.findViewById(R.id.play_random_text_view);
        playRandom.setOnClickListener(v -> {
            ArtistRepository artistRepository = new ArtistRepository();
            artistRepository.getRandomSong(artist, Preferences.getQueueLimit()).observe(getViewLifecycleOwner(), songs -> {
                MusicUtil.ratingFilter(songs);
                if (!songs.isEmpty()) {
                    MediaManager.startQueue(mediaBrowserListenableFuture, songs, 0);
                    ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
                }
                dismissBottomSheet();
            });
        });

        TextView playNext = view.findViewById(R.id.play_next_text_view);
        TextView addToQueue = view.findViewById(R.id.add_to_queue_text_view);
        TextView addToPlaylist = view.findViewById(R.id.add_to_playlist_text_view);
        TextView downloadAll = view.findViewById(R.id.download_all_text_view);

        // Helper to restore UI state after loading completes or fails
        Runnable restoreButtons = () -> {
            isLoadingTracks = false;
            if (playNext != null) playNext.setEnabled(true);
            if (addToQueue != null) addToQueue.setEnabled(true);
            if (addToPlaylist != null) addToPlaylist.setEnabled(true);
            if (downloadAll != null) downloadAll.setEnabled(true);
        };

        playNext.setOnClickListener(v -> {
            if (isLoadingTracks) return;
            isLoadingTracks = true;
            if (playNext != null) playNext.setEnabled(false);
            if (addToQueue != null) addToQueue.setEnabled(false);
            if (addToPlaylist != null) addToPlaylist.setEnabled(false);

            Toast.makeText(requireContext(), R.string.artist_bottom_sheet_loading_tracks, Toast.LENGTH_SHORT).show();

            androidx.lifecycle.Observer<java.util.List<com.eddyizm.tempus.subsonic.models.Child>> observer = new androidx.lifecycle.Observer<java.util.List<com.eddyizm.tempus.subsonic.models.Child>>() {
                @Override
                public void onChanged(java.util.List<com.eddyizm.tempus.subsonic.models.Child> songs) {
                    // Remove observer immediately to avoid duplicate handling
                    artistBottomSheetViewModel.getArtistAllTracks().removeObserver(this);
                    try {
                        if (songs == null || songs.isEmpty()) return;
                        MusicUtil.ratingFilter(songs);
                        MediaManager.enqueue(mediaBrowserListenableFuture, songs, true);
                        ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
                        dismissBottomSheet();
                    } finally {
                        restoreButtons.run();
                    }
                }
            };

            artistBottomSheetViewModel.getArtistAllTracks().observe(getViewLifecycleOwner(), observer);
        });

        addToQueue.setOnClickListener(v -> {
            if (isLoadingTracks) return;
            isLoadingTracks = true;
            if (playNext != null) playNext.setEnabled(false);
            if (addToQueue != null) addToQueue.setEnabled(false);
            if (addToPlaylist != null) addToPlaylist.setEnabled(false);

            Toast.makeText(requireContext(), R.string.artist_bottom_sheet_loading_tracks, Toast.LENGTH_SHORT).show();

            androidx.lifecycle.Observer<java.util.List<com.eddyizm.tempus.subsonic.models.Child>> observer = new androidx.lifecycle.Observer<java.util.List<com.eddyizm.tempus.subsonic.models.Child>>() {
                @Override
                public void onChanged(java.util.List<com.eddyizm.tempus.subsonic.models.Child> songs) {
                    artistBottomSheetViewModel.getArtistAllTracks().removeObserver(this);
                    try {
                        if (songs == null || songs.isEmpty()) return;
                        MusicUtil.ratingFilter(songs);
                        QueueAdder.add(requireActivity(), mediaBrowserListenableFuture, songs);
                        ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
                        dismissBottomSheet();
                    } finally {
                        restoreButtons.run();
                    }
                }
            };

            artistBottomSheetViewModel.getArtistAllTracks().observe(getViewLifecycleOwner(), observer);
        });

        addToPlaylist.setOnClickListener(v -> {
            if (isLoadingTracks) return;
            isLoadingTracks = true;
            if (playNext != null) playNext.setEnabled(false);
            if (addToQueue != null) addToQueue.setEnabled(false);
            if (addToPlaylist != null) addToPlaylist.setEnabled(false);

            Toast.makeText(requireContext(), R.string.artist_bottom_sheet_loading_tracks, Toast.LENGTH_SHORT).show();

            androidx.lifecycle.Observer<java.util.List<com.eddyizm.tempus.subsonic.models.Child>> observer = new androidx.lifecycle.Observer<java.util.List<com.eddyizm.tempus.subsonic.models.Child>>() {
                @Override
                public void onChanged(java.util.List<com.eddyizm.tempus.subsonic.models.Child> songs) {
                    artistBottomSheetViewModel.getArtistAllTracks().removeObserver(this);
                    try {
                        if (songs == null || songs.isEmpty()) return;
                        Bundle bundle = new Bundle();
                        bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(songs));

                        PlaylistChooserDialog dialog = new PlaylistChooserDialog();
                        dialog.setArguments(bundle);
                        dialog.show(requireActivity().getSupportFragmentManager(), null);

                        dismissBottomSheet();
                    } finally {
                        restoreButtons.run();
                    }
                }
            };

            artistBottomSheetViewModel.getArtistAllTracks().observe(getViewLifecycleOwner(), observer);
        });

        downloadAll.setOnClickListener(v -> {
            if (isLoadingTracks) return;
            isLoadingTracks = true;
            downloadAll.setEnabled(false);

            Toast.makeText(requireContext(), R.string.artist_bottom_sheet_loading_tracks, Toast.LENGTH_SHORT).show();

            // The download keeps going after the sheet closes, so it hangs off the app context.
            android.content.Context appContext = requireContext().getApplicationContext();
            androidx.lifecycle.Observer<List<com.eddyizm.tempus.subsonic.models.Child>> observer = new androidx.lifecycle.Observer<List<com.eddyizm.tempus.subsonic.models.Child>>() {
                @Override
                public void onChanged(List<com.eddyizm.tempus.subsonic.models.Child> songs) {
                    artistBottomSheetViewModel.getArtistAllTracks().removeObserver(this);
                    try {
                        if (songs == null || songs.isEmpty()) return;
                        // Building a request per track takes a few ms each; for an artist with a
                        // thousand tracks that froze the screen long enough for Android to close it.
                        List<com.eddyizm.tempus.subsonic.models.Child> batch = new ArrayList<>(songs);
                        DOWNLOAD_QUEUER.execute(() -> {
                            if (Preferences.getDownloadDirectoryUri() == null) {
                                List<MediaItem> mediaItems = MappingUtil.mapDownloads(batch);
                                List<Download> downloads = batch.stream().map(Download::new).collect(Collectors.toList());
                                DownloadUtil.getDownloadTracker(appContext).download(mediaItems, downloads);
                            } else {
                                batch.forEach(child -> ExternalAudioWriter.downloadToUserDirectory(appContext, child));
                            }
                        });
                        Toast.makeText(appContext, getResources().getQuantityString(
                                R.plurals.artist_bottom_sheet_downloading_tracks, songs.size(), songs.size()), Toast.LENGTH_SHORT).show();
                        dismissBottomSheet();
                    } finally {
                        restoreButtons.run();
                    }
                }
            };

            artistBottomSheetViewModel.getArtistAllTracks().observe(getViewLifecycleOwner(), observer);
        });
    }

    @Override
    public void onClick(View v) {
        dismissBottomSheet();
    }

    private void dismissBottomSheet() {
        dismiss();
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

}
