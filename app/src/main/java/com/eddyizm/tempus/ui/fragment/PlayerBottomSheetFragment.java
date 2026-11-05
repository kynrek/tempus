package com.eddyizm.tempus.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.util.AppColors;
import com.eddyizm.tempus.ui.view.ProgressLineDrawable;
import com.eddyizm.tempus.databinding.FragmentPlayerBottomSheetBinding;
import com.eddyizm.tempus.glide.CustomGlideRequest;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.subsonic.models.PlayQueue;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.queue.PlayerQueueBridge;
import com.eddyizm.tempus.viewmodel.PlaybackViewModel;
import com.bumptech.glide.Glide;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.MusicUtil;
import com.eddyizm.tempus.util.RadioCoverArtDownloader;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Objects;
import java.util.stream.IntStream;

@OptIn(markerClass = UnstableApi.class)
public class PlayerBottomSheetFragment extends Fragment {
    private static final String TAG = "PlayerBottomSheet";
    private boolean remoteVisible = false;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener lanSwitchListener = (prefs, key) -> {
        if (Preferences.LAN_REMOTE_ENABLED.equals(key)) renderRemote(com.eddyizm.tempus.lan.LanRemoteSession.current());
    };
    private String remoteCover = null;
    private String remoteMediaSignature = null;
    private FragmentPlayerBottomSheetBinding bind;

    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private PlaybackViewModel playbackViewModel;
    private PlayerQueueBridge playerQueueBridge;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private Handler progressBarHandler;
    private Runnable progressBarRunnable;
    private MediaBrowser progressBarBrowser;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        bind = FragmentPlayerBottomSheetBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);
        playbackViewModel = new ViewModelProvider(requireActivity()).get(PlaybackViewModel.class);

        customizeBottomSheetAction();
        initPlayerController();
        initQueueBridge(playbackViewModel);
        setHeaderBookmarksButton();
        com.eddyizm.tempus.lan.LanRemoteSession.state().observe(getViewLifecycleOwner(), this::renderRemote);
        // Settings is a fragment in this activity, so a flipped switch is heard here rather than on resume.
        com.eddyizm.tempus.App.getInstance().getPreferences().registerOnSharedPreferenceChangeListener(lanSwitchListener);

        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (activity.isBottomSheetExpanded()) {
                bind.playerHeaderLayout.getRoot().setAlpha(0f);
                bind.playerHeaderLayout.getRoot().setVisibility(View.GONE);
            }
        }

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (headerLine != null) headerLine.reload();   // the line style may have changed in Settings
        applyPanelColors();
    }

    /** Settings > Colors: the control panel's background and text (and its icons and line). */
    private void applyPanelColors() {
        if (bind == null) return;
        View root = bind.playerHeaderLayout.getRoot();
        Integer bg = AppColors.color(AppColors.PANEL_BG);
        Integer text = AppColors.color(AppColors.PANEL_TEXT);
        if (bg != null) {
            root.setBackgroundColor(bg);
            bind.playerHeaderLayout.playerHeaderSeekBar.setBackground(null);
        }
        if (text != null) {
            android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(text);
            for (TextView t : new TextView[]{bind.playerHeaderLayout.playerHeaderMediaTitleLabel,
                    bind.playerHeaderLayout.playerHeaderMediaArtistLabel,
                    bind.playerHeaderLayout.playerHeaderElapsedText,
                    bind.playerHeaderLayout.playerHeaderDurationText}) {
                t.setTextColor(text);
            }
            for (View b : new View[]{bind.playerHeaderLayout.playerHeaderRewindMediaButton,
                    bind.playerHeaderLayout.playerHeaderButton,
                    bind.playerHeaderLayout.playerHeaderNextMediaButton,
                    bind.playerHeaderLayout.playerHeaderFastForwardMediaButton,
                    bind.playerHeaderLayout.playerHeaderBookmarkMediaButton}) {
                b.setBackgroundTintList(tint);
            }
            if (headerLine != null) {
                android.util.TypedValue tv = new android.util.TypedValue();
                requireContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
                headerLine.setColors(tv.data, (text & 0x00FFFFFF) | 0x55000000);
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        initializeMediaBrowser();
        bindMediaController();
    }

    @Override
    public void onStop() {
        releaseMediaBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (playerQueueBridge != null && playerQueueBridge.isQueueOpen()) {
            playerQueueBridge.closeQueue();
        }
        playerQueueBridge = null;
        // #777: cancel the self-reposting progress updater. Otherwise its pending
        // Handler message keeps this destroyed fragment alive — and, via the
        // MediaBrowser the runnable captures, the whole MainActivity and its
        // cover-art bitmaps — leaking ~3MB per Activity recreation until OOM.
        if (progressBarHandler != null) {
            progressBarHandler.removeCallbacks(progressBarRunnable);
        }
        progressBarBrowser = null;
        com.eddyizm.tempus.App.getInstance().getPreferences().unregisterOnSharedPreferenceChangeListener(lanSwitchListener);
        remoteVisible = false;
        remoteCover = null;
        remoteMediaSignature = null;
        bind = null;
    }

    private void customizeBottomSheetAction() {
        bind.playerHeaderLayout.playerHeaderRemoteButton.setOnClickListener(v -> com.eddyizm.tempus.lan.LanDevicePicker.toggle(requireActivity()));
        bind.playerHeaderLayout.getRoot().setOnClickListener(view -> ((MainActivity) requireActivity()).expandBottomSheet());
        initHeaderSeekBar();
        if (!"bottom".equals(Preferences.getPanelPosition())) layoutHeaderAsColumn("left".equals(Preferences.getPanelPosition()));
        // The queue opens over whatever is on screen, so Back returns there.
        bind.playerHeaderLayout.playerHeaderQueueButton.setOnClickListener(view -> goToQueuePage());
        initQueueSlide();
    }

    private void initPlayerController() {
        if (getChildFragmentManager().findFragmentById(R.id.player_controller_container) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.player_controller_container, new PlayerControllerFragment(), "PlayerControllerFragment")
                    .commit();
        }
    }

    private void initQueueBridge(PlaybackViewModel playbackViewModel) {
        playerQueueBridge = new PlayerQueueBridge(
                bind.playerBodyLayout.playerQueueComposeView,
                this,
                playerBottomSheetViewModel,
                playbackViewModel
        );
        playerQueueBridge.init();
    }

    private void initializeMediaBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseMediaBrowser() {
        if (mediaBrowserListenableFuture != null && mediaBrowserListenableFuture.isDone()) {
            try {
                MediaManager.onBrowserReleased(mediaBrowserListenableFuture.get());
            } catch (Exception ignored) {
            }
        }
        MediaController.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();

                mediaBrowser.setShuffleModeEnabled(Preferences.isShuffleModeEnabled());
                mediaBrowser.setRepeatMode(Preferences.getRepeatMode());

                setMediaControllerListener(mediaBrowser);
                if (playbackViewModel != null) {
                    MediaManager.registerPlaybackObserver(mediaBrowserListenableFuture, playbackViewModel);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaControllerListener(MediaBrowser mediaBrowser) {
        defineProgressBarHandler(mediaBrowser);
        setMediaControllerUI(mediaBrowser);
        setMetadata(mediaBrowser.getMediaMetadata());
        setContentDuration(mediaBrowser.getContentDuration());
        setPlayingState(mediaBrowser.isPlaying());
        setHeaderMediaController();
        setHeaderNextButtonState(mediaBrowser.hasNextMediaItem());

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaBrowser.getMediaMetadata());
                setContentDuration(mediaBrowser.getContentDuration());
                setProgress(mediaBrowser);
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaBrowser.getMediaMetadata());
                setContentDuration(mediaBrowser.getContentDuration());
                // Seeks and track changes while paused don't tick the timer, so redraw now.
                setProgress(mediaBrowser);
            }

            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaMetadata);
                setContentDuration(mediaBrowser.getContentDuration());
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                setPlayingState(isPlaying);
            }

            @Override
            public void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
                Player.Listener.super.onSkipSilenceEnabledChanged(skipSilenceEnabled);
            }

            @Override
            public void onEvents(Player player, Player.Events events) {
                setHeaderNextButtonState(mediaBrowser.hasNextMediaItem());
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                    setQueueCount(mediaBrowser);
                    if (!com.eddyizm.tempus.lan.LanRemoteSession.isActive() && getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).syncBottomSheetWithQueue(mediaBrowser.getMediaItemCount());
                    }
                }
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                Preferences.setShuffleModeEnabled(shuffleModeEnabled);
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                Preferences.setRepeatMode(repeatMode);
            }
        });
    }

    private void setMetadata(MediaMetadata mediaMetadata) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || bind == null) return;
        if (mediaMetadata.extras != null) {
            playerBottomSheetViewModel.setLiveMedia(getViewLifecycleOwner(), mediaMetadata.extras.getString("type"), mediaMetadata.extras.getString("id"));
            playerBottomSheetViewModel.setLiveAlbum(getViewLifecycleOwner(), mediaMetadata.extras.getString("type"), mediaMetadata.extras.getString("albumId"));
            playerBottomSheetViewModel.setLiveArtist(getViewLifecycleOwner(), mediaMetadata.extras.getString("type"), mediaMetadata.extras.getString("artistId"));
            playerBottomSheetViewModel.setLiveDescription(mediaMetadata.extras.getString("description", null));

            String type = mediaMetadata.extras.getString("type");

            if (Objects.equals(type, Constants.MEDIA_TYPE_RADIO)) {
                // For radio: keep header consistent with full player
                String stationName = mediaMetadata.extras.getString(
                        "stationName",
                        mediaMetadata.artist != null ? String.valueOf(mediaMetadata.artist) : ""
                );

                String artist = mediaMetadata.extras.getString("radioArtist", "");
                String title = mediaMetadata.extras.getString("radioTitle", "");

                String mainTitle;
                if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(title)) {
                    mainTitle = artist + " - " + title;
                } else if (!TextUtils.isEmpty(title)) {
                    mainTitle = title;
                } else if (!TextUtils.isEmpty(artist)) {
                    mainTitle = artist;
                } else {
                    mainTitle = stationName;
                }

                bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setText(mainTitle);
                bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setText(stationName);

                bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setVisibility(!TextUtils.isEmpty(mainTitle) ? View.VISIBLE : View.GONE);
                bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setVisibility(!TextUtils.isEmpty(stationName) ? View.VISIBLE : View.GONE);
            } else {
                // Default (music, podcast, etc.)
                bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setText(mediaMetadata.extras.getString("title"));
                bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setText(
                        mediaMetadata.artist != null
                                ? mediaMetadata.artist
                                : ""
                );

                bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setVisibility(mediaMetadata.extras.getString("title") != null && !Objects.equals(mediaMetadata.extras.getString("title"), "") ? View.VISIBLE : View.GONE);
                bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setVisibility(
                        mediaMetadata.extras.getString("artist") != null && !Objects.equals(mediaMetadata.extras.getString("artist"), "")
                                ? View.VISIBLE
                                : View.GONE);
            }

            if (Constants.MEDIA_TYPE_RADIO.equals(mediaMetadata.extras.getString("type"))) {
                com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> request = Glide.with(requireContext())
                        .load(mediaMetadata.artworkUri)
                        .apply(CustomGlideRequest.createRequestOptions(requireContext(),
                                mediaMetadata.extras.getString("coverArtId"), CustomGlideRequest.ResourceType.Radio));
                request = RadioCoverArtDownloader.applyLocalFileSignature(request, mediaMetadata.artworkUri);
                request.into(bind.playerHeaderLayout.playerHeaderMediaCoverImage);
            } else {
                CustomGlideRequest.Builder
                        .from(requireContext(), mediaMetadata.extras.getString("coverArtId"), CustomGlideRequest.ResourceType.Song)
                        .build()
                        .into(bind.playerHeaderLayout.playerHeaderMediaCoverImage);
            }
        }
    }


    private int lastQueueCount;

    private void setQueueCount(MediaBrowser mediaBrowser) {
        if (bind == null) return;
        lastQueueCount = mediaBrowser.getMediaItemCount();
        refreshQueueButton();
    }

    /**
     * The Queue button is a switch you can slide: slide it sideways to turn the queue on or off, tap it to
     * view or edit the queue. The button follows the finger a little while sliding, so it feels like a switch.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void initQueueSlide() {
        View button = bind.playerHeaderLayout.playerHeaderQueueButton;
        float d = getResources().getDisplayMetrics().density;
        float slop = android.view.ViewConfiguration.get(requireContext()).getScaledTouchSlop();
        float toggleAt = 28 * d, maxTravel = 22 * d;
        float[] downX = new float[1];
        boolean[] sliding = new boolean[1];
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = e.getRawX();
                    sliding[0] = false;
                    return false;   // let the button press and click run as usual
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX[0];
                    if (!sliding[0] && Math.abs(dx) > slop * 2) {
                        sliding[0] = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        v.setPressed(false);
                    }
                    if (sliding[0]) v.setTranslationX(Math.max(-maxTravel, Math.min(maxTravel, dx)));
                    return sliding[0];
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!sliding[0]) return false;
                    v.animate().translationX(0).setDuration(120).start();
                    float moved = e.getRawX() - downX[0];
                    // Like a switch: slide right turns the queue on, slide left turns it off.
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && Math.abs(moved) >= toggleAt) {
                        boolean want = moved > 0;
                        if (want != Preferences.isQueueEnabled()) setQueueEnabled(want);
                    }
                    sliding[0] = false;
                    return true;   // a slide isn't a tap
                default:
                    return sliding[0];
            }
        });
    }

    /** Turns the queue on or off from either place (this button or the queue screen's switch). */
    public void setQueueEnabled(boolean on) {
        Preferences.setQueueEnabled(on);
        if (playerQueueBridge != null) playerQueueBridge.syncQueueEnabled();
        refreshQueueButton();
        if (getContext() != null) {
            android.widget.Toast.makeText(getContext(), on ? R.string.player_queue_on_toast : R.string.player_queue_off_toast,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** "Queue 47", or "Queue off" when Next follows the album/playlist (switch on the queue screen). */
    public void refreshQueueButton() {
        if (bind == null) return;
        int count = lastQueueCount;
        // Off looks off: outlined instead of filled, like a switch that's down.
        com.google.android.material.button.MaterialButton qb = bind.playerHeaderLayout.playerHeaderQueueButton;
        boolean on = Preferences.isQueueEnabled();
        qb.setAlpha(on ? 1f : 0.75f);
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                on ? com.google.android.material.R.attr.colorSecondaryContainer : android.R.attr.colorBackground, tv, true);
        qb.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tv.data));
        requireContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutline, tv, true);
        qb.setStrokeColor(android.content.res.ColorStateList.valueOf(tv.data));
        qb.setStrokeWidth(on ? 0 : Math.round(getResources().getDisplayMetrics().density));
        bind.playerHeaderLayout.playerHeaderQueueButton.setText(!Preferences.isQueueEnabled()
                ? getString(R.string.player_header_queue_off)
                : count > 0 ? getString(R.string.player_header_queue_count, count) : getString(R.string.player_header_queue));
    }

    private void setMediaControllerUI(MediaBrowser mediaBrowser) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || bind == null) return;
        setQueueCount(mediaBrowser);
        if (mediaBrowser.getMediaMetadata().extras != null) {
            switch (mediaBrowser.getMediaMetadata().extras.getString("type", Constants.MEDIA_TYPE_MUSIC)) {
                case Constants.MEDIA_TYPE_PODCAST:
                    bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setVisibility(View.VISIBLE);
                    bind.playerHeaderLayout.playerHeaderRewindMediaButton.setVisibility(View.VISIBLE);
                    bind.playerHeaderLayout.playerHeaderNextMediaButton.setVisibility(View.GONE);
                    break;
                case Constants.MEDIA_TYPE_MUSIC:
                default:
                    // Seek back/forward by the Settings amount sit around Play for music too.
                    bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setVisibility(View.VISIBLE);
                    bind.playerHeaderLayout.playerHeaderRewindMediaButton.setVisibility(View.VISIBLE);
                    bind.playerHeaderLayout.playerHeaderNextMediaButton.setVisibility(View.VISIBLE);
                    break;
            }
        }
    }

    /** True while a finger is on the mini player timeline, so playback updates don't fight it. */
    private boolean scrubbing;

    private ProgressLineDrawable headerLine;

    /**
     * Side column (Settings > control panel position): cover on top, then title, artist, the progress
     * line and the buttons, stacked down a full-height column. The bar marks the edge facing the library.
     */
    private void layoutHeaderAsColumn(boolean panelOnLeft) {
        androidx.constraintlayout.widget.ConstraintLayout root =
                (androidx.constraintlayout.widget.ConstraintLayout) bind.playerHeaderLayout.getRoot();
        ViewGroup.LayoutParams rootLp = root.getLayoutParams();
        rootLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        root.setLayoutParams(rootLp);
        float d = getResources().getDisplayMetrics().density;
        int p = androidx.constraintlayout.widget.ConstraintSet.PARENT_ID;
        int m = Math.round(12 * d);
        var h = bind.playerHeaderLayout;
        androidx.constraintlayout.widget.ConstraintSet set = new androidx.constraintlayout.widget.ConstraintSet();
        set.clone(root);
        int[] all = {h.playerHeaderTopBar.getId(), h.playerHeaderMediaCoverImage.getId(), h.playerHeaderMediaTitleLabel.getId(),
                h.playerHeaderMediaArtistLabel.getId(), h.playerHeaderQueueButton.getId(), h.playerHeaderRemoteButton.getId(),
                h.placeholderLeftView.getId(), h.placeholderRightView.getId(), h.playerHeaderBookmarkMediaButton.getId(),
                h.playerHeaderRewindMediaButton.getId(), h.playerHeaderButton.getId(), h.playerHeaderFastForwardMediaButton.getId(),
                h.playerHeaderNextMediaButton.getId(), h.playerHeaderElapsedText.getId(), h.playerHeaderSeekBar.getId(),
                h.playerHeaderDurationText.getId()};
        // clear() also forgets visibility; keep what each view has now (e.g. the podcast bookmark stays hidden).
        int[] visibility = new int[all.length];
        for (int i = 0; i < all.length; i++) visibility[i] = root.findViewById(all[i]).getVisibility();
        for (int id : all) set.clear(id);

        // Bar down the inner edge.
        int bar = h.playerHeaderTopBar.getId();
        set.constrainWidth(bar, Math.round(3 * d));
        set.constrainHeight(bar, 0);
        set.connect(bar, androidx.constraintlayout.widget.ConstraintSet.TOP, p, androidx.constraintlayout.widget.ConstraintSet.TOP);
        set.connect(bar, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, p, androidx.constraintlayout.widget.ConstraintSet.BOTTOM);
        set.connect(bar, panelOnLeft ? androidx.constraintlayout.widget.ConstraintSet.END : androidx.constraintlayout.widget.ConstraintSet.START,
                p, panelOnLeft ? androidx.constraintlayout.widget.ConstraintSet.END : androidx.constraintlayout.widget.ConstraintSet.START);

        int cover = h.playerHeaderMediaCoverImage.getId();
        set.constrainWidth(cover, 0);
        set.constrainHeight(cover, 0);
        set.setDimensionRatio(cover, "1:1");
        set.connect(cover, androidx.constraintlayout.widget.ConstraintSet.TOP, p, androidx.constraintlayout.widget.ConstraintSet.TOP, m);
        set.connect(cover, androidx.constraintlayout.widget.ConstraintSet.START, p, androidx.constraintlayout.widget.ConstraintSet.START, m + Math.round(4 * d));
        set.connect(cover, androidx.constraintlayout.widget.ConstraintSet.END, p, androidx.constraintlayout.widget.ConstraintSet.END, m + Math.round(4 * d));

        int title = h.playerHeaderMediaTitleLabel.getId(), artist = h.playerHeaderMediaArtistLabel.getId();
        for (int id : new int[]{title, artist}) {
            set.constrainWidth(id, 0);
            set.constrainHeight(id, androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT);
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.START, p, androidx.constraintlayout.widget.ConstraintSet.START, Math.round(6 * d));
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.END, p, androidx.constraintlayout.widget.ConstraintSet.END, Math.round(6 * d));
        }
        set.connect(title, androidx.constraintlayout.widget.ConstraintSet.TOP, cover, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, Math.round(8 * d));
        set.connect(artist, androidx.constraintlayout.widget.ConstraintSet.TOP, title, androidx.constraintlayout.widget.ConstraintSet.BOTTOM);
        h.playerHeaderMediaTitleLabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        h.playerHeaderMediaArtistLabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        int el = h.playerHeaderElapsedText.getId(), seek = h.playerHeaderSeekBar.getId(), du = h.playerHeaderDurationText.getId();
        set.constrainWidth(seek, 0);
        set.constrainHeight(seek, Math.round(22 * d));
        set.connect(seek, androidx.constraintlayout.widget.ConstraintSet.TOP, artist, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, Math.round(6 * d));
        set.connect(seek, androidx.constraintlayout.widget.ConstraintSet.START, el, androidx.constraintlayout.widget.ConstraintSet.END);
        set.connect(seek, androidx.constraintlayout.widget.ConstraintSet.END, du, androidx.constraintlayout.widget.ConstraintSet.START);
        for (int id : new int[]{el, du}) {
            set.constrainWidth(id, Math.round(40 * d));
            set.constrainHeight(id, 0);
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.TOP, seek, androidx.constraintlayout.widget.ConstraintSet.TOP);
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, seek, androidx.constraintlayout.widget.ConstraintSet.BOTTOM);
        }
        set.connect(el, androidx.constraintlayout.widget.ConstraintSet.START, p, androidx.constraintlayout.widget.ConstraintSet.START);
        set.connect(du, androidx.constraintlayout.widget.ConstraintSet.END, p, androidx.constraintlayout.widget.ConstraintSet.END);

        int size = getResources().getDimensionPixelSize(R.dimen.mini_player_button_size);
        int[] row = {h.playerHeaderRewindMediaButton.getId(), h.playerHeaderButton.getId(),
                h.playerHeaderFastForwardMediaButton.getId(), h.playerHeaderNextMediaButton.getId()};
        for (int id : row) {
            set.constrainWidth(id, size);
            set.constrainHeight(id, size);
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.TOP, seek, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, Math.round(6 * d));
        }
        set.createHorizontalChain(p, androidx.constraintlayout.widget.ConstraintSet.LEFT, p, androidx.constraintlayout.widget.ConstraintSet.RIGHT,
                row, null, androidx.constraintlayout.widget.ConstraintSet.CHAIN_SPREAD_INSIDE);
        for (int id : row) {
            set.setMargin(id, androidx.constraintlayout.widget.ConstraintSet.LEFT, Math.round(8 * d));
            set.setMargin(id, androidx.constraintlayout.widget.ConstraintSet.RIGHT, Math.round(8 * d));
        }

        int queue = h.playerHeaderQueueButton.getId(), remote = h.playerHeaderRemoteButton.getId(), book = h.playerHeaderBookmarkMediaButton.getId();
        set.constrainWidth(queue, androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT);
        set.constrainHeight(queue, size);
        set.connect(queue, androidx.constraintlayout.widget.ConstraintSet.TOP, row[0], androidx.constraintlayout.widget.ConstraintSet.BOTTOM, Math.round(8 * d));
        set.connect(queue, androidx.constraintlayout.widget.ConstraintSet.START, p, androidx.constraintlayout.widget.ConstraintSet.START);
        set.connect(queue, androidx.constraintlayout.widget.ConstraintSet.END, p, androidx.constraintlayout.widget.ConstraintSet.END);
        set.constrainWidth(remote, Math.round(48 * d));
        set.constrainHeight(remote, Math.round(48 * d));
        set.connect(remote, androidx.constraintlayout.widget.ConstraintSet.TOP, queue, androidx.constraintlayout.widget.ConstraintSet.TOP);
        set.connect(remote, androidx.constraintlayout.widget.ConstraintSet.START, queue, androidx.constraintlayout.widget.ConstraintSet.END, Math.round(6 * d));
        set.constrainWidth(book, size);
        set.constrainHeight(book, size);
        set.connect(book, androidx.constraintlayout.widget.ConstraintSet.TOP, queue, androidx.constraintlayout.widget.ConstraintSet.TOP);
        set.connect(book, androidx.constraintlayout.widget.ConstraintSet.END, queue, androidx.constraintlayout.widget.ConstraintSet.START, Math.round(6 * d));
        for (int id : new int[]{h.placeholderLeftView.getId(), h.placeholderRightView.getId()}) {
            set.constrainWidth(id, 0);
            set.constrainHeight(id, 0);
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.TOP, p, androidx.constraintlayout.widget.ConstraintSet.TOP);
            set.connect(id, androidx.constraintlayout.widget.ConstraintSet.START, p, androidx.constraintlayout.widget.ConstraintSet.START);
            set.setVisibility(id, View.GONE);
        }
        for (int i = 0; i < all.length; i++) {
            if (all[i] != h.placeholderLeftView.getId() && all[i] != h.placeholderRightView.getId()) set.setVisibility(all[i], visibility[i]);
        }
        set.applyTo(root);
    }

    private void initHeaderSeekBar() {
        // The wavy (or straight) line from Settings, in place of the platform bar.
        android.widget.SeekBar bar = bind.playerHeaderLayout.playerHeaderSeekBar;
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
        int played = tv.data;
        requireContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
        int rest = (tv.data & 0x00FFFFFF) | 0x66000000;
        headerLine = new ProgressLineDrawable(requireContext(), played, rest);
        int progress = bar.getProgress();
        bar.setProgressDrawable(headerLine);
        bar.setProgress(progress);
        bind.playerHeaderLayout.playerHeaderSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) setHeaderTimes(progress, seekBar.getMax());
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                scrubbing = false;
                long positionMs = seekBar.getProgress() * 1000L;
                if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || mediaBrowserListenableFuture == null) return;
                mediaBrowserListenableFuture.addListener(() -> {
                    try {
                        mediaBrowserListenableFuture.get().seekTo(positionMs);
                    } catch (Exception e) {
                        Log.e(TAG, "Seek failed", e);
                    }
                }, MoreExecutors.directExecutor());
            }
        });
    }

    /** Jumps by the Settings amount (5–60 s, as in MiniCarHome's widget), clamped to the track. */
    private void seekBy(int seconds) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || mediaBrowserListenableFuture == null) return;
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser browser = mediaBrowserListenableFuture.get();
                long target = browser.getCurrentPosition() + seconds * 1000L;
                long duration = browser.getDuration();
                if (duration > 0) target = Math.min(target, duration - 500);
                browser.seekTo(Math.max(0, target));
            } catch (Exception e) {
                Log.e(TAG, "Seek failed", e);
            }
        }, MoreExecutors.directExecutor());
    }

    private void setHeaderTimes(int positionSeconds, int durationSeconds) {
        if (bind == null) return;
        bind.playerHeaderLayout.playerHeaderElapsedText.setText(MusicUtil.getReadableDurationString((long) positionSeconds, false));
        bind.playerHeaderLayout.playerHeaderDurationText.setText(MusicUtil.getReadableDurationString((long) durationSeconds, false));
    }

    private void setContentDuration(long duration) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || bind == null) return;
        // Before the track is prepared (e.g. after a restart) the player doesn't know the length yet:
        // use the length the server gave for the track rather than the "unknown" value.
        if (duration <= 0 || duration == androidx.media3.common.C.TIME_UNSET) duration = knownDurationMs();
        if (duration <= 0) return;
        bind.playerHeaderLayout.playerHeaderSeekBar.setMax((int) (duration / 1000));
        setHeaderTimes(bind.playerHeaderLayout.playerHeaderSeekBar.getProgress(), (int) (duration / 1000));
    }

    private long knownDurationMs() {
        MediaBrowser browser = progressBarBrowser;
        if (browser == null || browser.getCurrentMediaItem() == null) return 0;
        androidx.media3.common.MediaMetadata m = browser.getCurrentMediaItem().mediaMetadata;
        if (m.durationMs != null && m.durationMs > 0) return m.durationMs;
        android.os.Bundle extras = m.extras;
        return extras != null ? extras.getInt("duration", 0) * 1000L : 0;
    }

    private void setProgress(MediaBrowser mediaBrowser) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) {
            if (bind != null) bind.playerHeaderLayout.playerHeaderSeekBar.setProgress((int)
                    (com.eddyizm.tempus.lan.LanRemoteSession.current().positionAt(android.os.SystemClock.elapsedRealtime()) / 1000), true);
            return;
        }
        if (bind != null && !scrubbing) {
            int position = (int) (mediaBrowser.getCurrentPosition() / 1000);
            bind.playerHeaderLayout.playerHeaderSeekBar.setProgress(position, true);
            setHeaderTimes(position, bind.playerHeaderLayout.playerHeaderSeekBar.getMax());
        }
    }

    private void setPlayingState(boolean isPlaying) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || bind == null) return;
        bind.playerHeaderLayout.playerHeaderButton.setChecked(isPlaying);
        runProgressBarHandler(isPlaying);
    }

    private void setHeaderMediaController() {
        bind.playerHeaderLayout.playerHeaderButton.setOnClickListener(view -> {
            if (com.eddyizm.tempus.lan.LanRemoteSession.isActive())
                com.eddyizm.tempus.lan.LanRemoteSession.command(com.eddyizm.tempus.lan.LanRemoteSession.current().getPlayWhenReady() ? "pause" : "play");
            else bind.getRoot().findViewById(R.id.exo_play_pause).performClick();
        });
        bind.playerHeaderLayout.playerHeaderNextMediaButton.setOnClickListener(view -> {
            if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) com.eddyizm.tempus.lan.LanRemoteSession.command("next");
            else bind.getRoot().findViewById(R.id.exo_next).performClick();
        });
        bind.playerHeaderLayout.playerHeaderRewindMediaButton.setOnClickListener(view -> seekBy(-Preferences.getSeekSeconds()));
        bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setOnClickListener(view -> seekBy(Preferences.getSeekSeconds()));
    }

    private void setHeaderNextButtonState(boolean isEnabled) {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive() || bind == null) return;
        bind.playerHeaderLayout.playerHeaderNextMediaButton.setEnabled(isEnabled);
        bind.playerHeaderLayout.playerHeaderNextMediaButton.setAlpha(isEnabled ? (float) 1.0 : (float) 0.3);
    }

    public View getPlayerHeader() {
        return bind != null ? bind.playerHeaderLayout.getRoot() : null;
    }

    private void renderRemote(com.eddyizm.tempus.lan.LanRemoteState state) {
        if (bind == null) return;
        bind.playerHeaderLayout.playerHeaderRemoteButton.setVisibility(
                com.eddyizm.tempus.lan.LanDevicePicker.isOffered() ? View.VISIBLE : View.GONE);
        bind.playerHeaderLayout.playerHeaderRemoteButton.setSelected(state.getActive());
        bind.playerHeaderLayout.playerHeaderRemoteButton.setContentDescription(
                state.getActive() ? getString(R.string.lan_controlling, state.getName()) : getString(R.string.lan_play_on));
        androidx.appcompat.widget.TooltipCompat.setTooltipText(bind.playerHeaderLayout.playerHeaderRemoteButton,
                bind.playerHeaderLayout.playerHeaderRemoteButton.getContentDescription());
        boolean wasRemote = remoteVisible;
        remoteVisible = state.getActive();
        if (remoteVisible) {
            if (!wasRemote) ((MainActivity) requireActivity()).setBottomSheetInPeek(true);
            com.eddyizm.tempus.subsonic.models.Child song = com.eddyizm.tempus.lan.LanRemoteSession.currentSong();
            String signature = song.getId() + ":" + song.getAlbumId() + ":" + song.getArtistId();
            if (!Objects.equals(remoteMediaSignature, signature)) {
                remoteMediaSignature = signature;
                String type = song.getId().isEmpty() ? null : Constants.MEDIA_TYPE_MUSIC;
                playerBottomSheetViewModel.setLiveMedia(getViewLifecycleOwner(), type, song.getId());
                playerBottomSheetViewModel.setLiveAlbum(getViewLifecycleOwner(), TextUtils.isEmpty(song.getAlbumId()) ? null : type, song.getAlbumId());
                playerBottomSheetViewModel.setLiveArtist(getViewLifecycleOwner(), TextUtils.isEmpty(song.getArtistId()) ? null : type, song.getArtistId());
                playerBottomSheetViewModel.setLiveDescription(null);
            }
            bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setText(state.getTitle().isEmpty() ? getString(R.string.lan_no_track) : state.getTitle());
            bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setVisibility(View.VISIBLE);
            bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setText(getString(R.string.lan_device_status, state.getName(),
                    getString(state.getError() != 0 ? state.getError() : state.getConnected() ? R.string.lan_remote_active : R.string.lan_connecting)));
            bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setVisibility(View.VISIBLE);
            bind.playerHeaderLayout.playerHeaderButton.setChecked(state.getPlayWhenReady());
            bind.playerHeaderLayout.playerHeaderButton.setEnabled(state.getConnected() && state.getCount() > 0);
            bind.playerHeaderLayout.playerHeaderNextMediaButton.setVisibility(View.VISIBLE);
            bind.playerHeaderLayout.playerHeaderNextMediaButton.setEnabled(state.getConnected() && state.getHasNext());
            bind.playerHeaderLayout.playerHeaderNextMediaButton.setAlpha(state.getConnected() ? 1f : .3f);
            bind.playerHeaderLayout.playerHeaderRewindMediaButton.setVisibility(View.GONE);
            bind.playerHeaderLayout.playerHeaderFastForwardMediaButton.setVisibility(View.GONE);
            bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
            bind.playerHeaderLayout.playerHeaderSeekBar.setMax((int) (state.getDuration() / 1000));
            bind.playerHeaderLayout.playerHeaderSeekBar.setProgress((int) (state.getPosition() / 1000));
            if (!Objects.equals(remoteCover, state.getCoverId())) {
                remoteCover = state.getCoverId();
                CustomGlideRequest.Builder.from(requireContext(), remoteCover.isEmpty() ? null : remoteCover,
                        CustomGlideRequest.ResourceType.Song).build().into(bind.playerHeaderLayout.playerHeaderMediaCoverImage);
            }
        } else if (wasRemote) {
            remoteCover = null;
            remoteMediaSignature = null;
            bind.playerHeaderLayout.playerHeaderButton.setEnabled(true);
            if (mediaBrowserListenableFuture != null && mediaBrowserListenableFuture.isDone()) {
                try {
                    MediaBrowser player = mediaBrowserListenableFuture.get();
                    setMediaControllerUI(player); setMetadata(player.getMediaMetadata());
                    setPlayingState(player.isPlaying()); setContentDuration(player.getContentDuration());
                    setHeaderNextButtonState(player.hasNextMediaItem()); setProgress(player);
                    if (player.getMediaItemCount() == 0) ((MainActivity) requireActivity()).setBottomSheetInPeek(false);
                } catch (Exception ignored) { }
            }
        }
    }

    public void goBackToFirstPage() {
        if (getContext() == null || !isAdded() || getView() == null) return;
        if (playerQueueBridge != null && playerQueueBridge.isQueueOpen()) {
            playerQueueBridge.closeQueue();
        }
        goToControllerPage();
    }

    public void goToControllerPage() {
        if (getContext() == null || !isAdded() || getView() == null) return;
        PlayerControllerFragment playerControllerFragment = (PlayerControllerFragment) getChildFragmentManager()
                .findFragmentByTag("PlayerControllerFragment");
        if (playerControllerFragment != null) {
            playerControllerFragment.goToControllerPage();
        }
    }

    public void goToLyricsPage() {
        if (getContext() == null || !isAdded() || getView() == null) return;
        PlayerControllerFragment playerControllerFragment = (PlayerControllerFragment) getChildFragmentManager()
                .findFragmentByTag("PlayerControllerFragment");
        if (playerControllerFragment != null) {
            playerControllerFragment.goToLyricsPage();
        }
    }

    public void goToQueuePage() {
        if (getContext() == null || !isAdded() || getView() == null) return;
        if (playerQueueBridge != null) {
            playerQueueBridge.openQueue();
        }
    }

    public boolean isLyricsPage() {
        PlayerControllerFragment playerControllerFragment = (PlayerControllerFragment) getChildFragmentManager()
                .findFragmentByTag("PlayerControllerFragment");
        return playerControllerFragment != null && playerControllerFragment.isLyricsPage();
    }

    public void updateBottomSheetDraggableState() {
        boolean isQueueOpen = playerQueueBridge != null && playerQueueBridge.isQueueOpen();
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            if (isQueueOpen) {
                activity.setBottomSheetDraggableState(false);
            } else {
                activity.setBottomSheetDraggableState(!isLyricsPage());
            }
        }
        PlayerControllerFragment playerControllerFragment = (PlayerControllerFragment) getChildFragmentManager()
                .findFragmentByTag("PlayerControllerFragment");
        if (playerControllerFragment != null) {
            playerControllerFragment.updateQueueButtonTint(isQueueOpen);
        }
    }

    public ListenableFuture<MediaBrowser> getMediaBrowserListenableFuture() {
        return mediaBrowserListenableFuture;
    }

    private void defineProgressBarHandler(MediaBrowser mediaBrowser) {
        progressBarHandler = new Handler();
        progressBarBrowser = mediaBrowser;
        progressBarRunnable = () -> {
            setProgress(mediaBrowser);
            progressBarHandler.postDelayed(progressBarRunnable, 1000);
        };
    }

    /** Ticks once a second while playing; on pause, shows where it stopped instead of the last tick. */
    private void runProgressBarHandler(boolean isPlaying) {
        progressBarHandler.removeCallbacks(progressBarRunnable);
        if (headerLine != null) headerLine.setPlaying(isPlaying);
        if (isPlaying) {
            progressBarHandler.post(progressBarRunnable);
        } else if (progressBarBrowser != null) {
            setProgress(progressBarBrowser);
        }
    }

    private void setHeaderBookmarksButton() {
        if (Preferences.isSyncronizationEnabled()) {
            playerBottomSheetViewModel.getPlayQueue().observeForever(new Observer<PlayQueue>() {
                @Override
                public void onChanged(PlayQueue playQueue) {
                    playerBottomSheetViewModel.getPlayQueue().removeObserver(this);

                    if (bind == null || com.eddyizm.tempus.lan.LanRemoteSession.isActive()) return;

                    if (playQueue != null && playQueue.getEntries() != null && !playQueue.getEntries().isEmpty()) {
                        int index = IntStream.range(0, playQueue.getEntries().size()).filter(ix -> playQueue.getEntries().get(ix).getId().equals(playQueue.getCurrent())).findFirst().orElse(-1);

                        if (index != -1) {
                            bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.VISIBLE);
                            bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setOnClickListener(v -> {
                                Long savedPosition = playQueue.getPosition();
                                long startPositionMs = savedPosition != null ? savedPosition : 0L;
                                MediaManager.startQueue(mediaBrowserListenableFuture, playQueue.getEntries(), index, startPositionMs);
                                bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
                            });
                        }
                    } else {
                        bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
                        bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setOnClickListener(null);
                    }
                }
            });

            bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setOnLongClickListener(v -> {
                bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
                return true;
            });

            new Handler().postDelayed(() -> {
                if (bind != null)
                    bind.playerHeaderLayout.playerHeaderBookmarkMediaButton.setVisibility(View.GONE);
            }, Constants.LOAD_BUTTON_VISIBLE_MS);
        }
    }
}
