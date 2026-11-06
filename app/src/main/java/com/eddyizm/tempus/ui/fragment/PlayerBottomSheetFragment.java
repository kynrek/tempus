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

    @Nullable private ViewGroup sideHost;

    // --- For the queue screen's control bar, which is drawn in the queue's own window over the
    // player: it mirrors the mini player and presses its buttons, so remote (LAN) control,
    // seek-button visibility and the rest behave exactly the same. ---

    @Nullable
    public CharSequence barTitle() {
        return bind != null ? bind.playerHeaderLayout.playerHeaderMediaTitleLabel.getText() : null;
    }

    @Nullable
    public CharSequence barArtist() {
        return bind != null ? bind.playerHeaderLayout.playerHeaderMediaArtistLabel.getText() : null;
    }

    public boolean barPlaying() {
        return bind != null && bind.playerHeaderLayout.playerHeaderButton.isChecked();
    }

    public int barPositionSeconds() {
        return bind != null ? bind.playerHeaderLayout.playerHeaderSeekBar.getProgress() : 0;
    }

    public int barDurationSeconds() {
        return bind != null ? bind.playerHeaderLayout.playerHeaderSeekBar.getMax() : 0;
    }

    public boolean barHasSeekButtons() {
        return bind != null && bind.playerHeaderLayout.playerHeaderRewindMediaButton.getVisibility() == View.VISIBLE;
    }

    public boolean barNextEnabled() {
        return bind != null && bind.playerHeaderLayout.playerHeaderNextMediaButton.getVisibility() == View.VISIBLE
                && bind.playerHeaderLayout.playerHeaderNextMediaButton.isEnabled();
    }

    /** "rewind", "play", "forward" or "next": the same as tapping that mini player button. */
    public void barPress(String button) {
        if (bind == null) return;
        View target;
        switch (button) {
            case "rewind": target = bind.playerHeaderLayout.playerHeaderRewindMediaButton; break;
            case "forward": target = bind.playerHeaderLayout.playerHeaderFastForwardMediaButton; break;
            case "next": target = bind.playerHeaderLayout.playerHeaderNextMediaButton; break;
            default: target = bind.playerHeaderLayout.playerHeaderButton; break;
        }
        target.performClick();
    }

    public void barSeekTo(int seconds) {
        if (bind == null || com.eddyizm.tempus.lan.LanRemoteSession.isActive() || mediaBrowserListenableFuture == null) return;
        bind.playerHeaderLayout.playerHeaderSeekBar.setProgress(seconds);
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                mediaBrowserListenableFuture.get().seekTo(seconds * 1000L);
            } catch (Exception e) {
                Log.e(TAG, "Seek failed", e);
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Landscape with Settings > UI > Mini player when sideways = side: move the mini player's bar
     * into the activity's side panel and rearrange it into a column (cover, title, transport,
     * timeline, Queue). The views and their wiring stay the same, only their constraints change;
     * the sheet itself then collapses to nothing and still opens the full player on a tap.
     */
    private void moveHeaderToSidePanel() {
        if (!(getActivity() instanceof MainActivity) || !((MainActivity) getActivity()).usesSidePlayer()) return;
        ViewGroup host = getActivity().findViewById(R.id.side_player_container);
        androidx.constraintlayout.widget.ConstraintLayout header = bind.playerHeaderLayout.getRoot();
        if (host == null) return;
        if (header.getParent() instanceof ViewGroup) ((ViewGroup) header.getParent()).removeView(header);
        host.removeAllViews();
        host.addView(header, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sideHost = host;

        // The constraint set would also apply its own visibilities; the code decides those (remote, bookmark, seek buttons).
        int[] visibility = new int[header.getChildCount()];
        for (int i = 0; i < visibility.length; i++) visibility[i] = header.getChildAt(i).getVisibility();
        androidx.constraintlayout.widget.ConstraintSet column = new androidx.constraintlayout.widget.ConstraintSet();
        column.clone(requireContext(), R.layout.player_header_side_constraints);
        column.applyTo(header);
        for (int i = 0; i < visibility.length; i++) header.getChildAt(i).setVisibility(visibility[i]);

        bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setMaxLines(2);
        bind.playerHeaderLayout.playerHeaderMediaTitleLabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        bind.playerHeaderLayout.playerHeaderMediaArtistLabel.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        ((MainActivity) getActivity()).updateSidePlayer();
    }

    /**
     * A phone on its side puts the mini player's bottom row (elapsed time, timeline, total) right
     * on the screen's rounded bottom corners, which cut off the times. Pad the bar's sides by
     * what the corners take at that row's height. Square screens (the car) report no corners.
     */
    private void keepHeaderClearOfRoundedCorners(View header) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyCornerPadding(v));
    }

    @androidx.annotation.RequiresApi(31)
    private void applyCornerPadding(View header) {
        android.view.WindowInsets insets = header.getRootWindowInsets();
        View content = getActivity() != null ? getActivity().findViewById(android.R.id.content) : null;
        if (insets == null || content == null) return;
        int[] at = new int[2];
        content.getLocationOnScreen(at);
        // Where the collapsed bar's bottom row sits: the bottom of the content, above any navigation bar.
        float rowY = at[1] + content.getHeight() - content.getPaddingBottom() - 6 * getResources().getDisplayMetrics().density;
        header.getLocationOnScreen(at);
        int left = 0;
        int right = 0;
        android.view.RoundedCorner bl = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT);
        if (bl != null && rowY > bl.getCenter().y) {
            double dy = rowY - bl.getCenter().y;
            double edge = bl.getCenter().x - Math.sqrt(Math.max(0, (double) bl.getRadius() * bl.getRadius() - dy * dy));
            left = (int) Math.max(0, Math.ceil(edge - at[0]));
        }
        android.view.RoundedCorner br = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT);
        if (br != null && rowY > br.getCenter().y) {
            double dy = rowY - br.getCenter().y;
            double edge = br.getCenter().x + Math.sqrt(Math.max(0, (double) br.getRadius() * br.getRadius() - dy * dy));
            right = (int) Math.max(0, Math.ceil(at[0] + header.getWidth() - edge));
        }
        if (header.getPaddingLeft() != left || header.getPaddingRight() != right) {
            int fl = left, fr = right;
            header.post(() -> header.setPadding(fl, header.getPaddingTop(), fr, header.getPaddingBottom()));
        }
    }

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
        keepHeaderClearOfRoundedCorners(bind.playerHeaderLayout.getRoot());
        com.eddyizm.tempus.lan.LanRemoteSession.state().observe(getViewLifecycleOwner(), this::renderRemote);
        // Settings is a fragment in this activity, so a flipped switch is heard here rather than on resume.
        com.eddyizm.tempus.App.getInstance().getPreferences().registerOnSharedPreferenceChangeListener(lanSwitchListener);

        moveHeaderToSidePanel();

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
        // The bar may be living in the activity's side panel; take it back out with the rest of this view.
        if (bind != null && sideHost != null && bind.playerHeaderLayout.getRoot().getParent() == sideHost) {
            sideHost.removeView(bind.playerHeaderLayout.getRoot());
        }
        sideHost = null;
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
        // The queue opens over whatever is on screen, so Back returns there.
        bind.playerHeaderLayout.playerHeaderQueueButton.setOnClickListener(view -> goToQueuePage());
        // Slide it like a switch: right turns queue mode on, left turns it off; a tap still opens
        // the queue (the same switch is at the top of the queue screen).
        bind.playerHeaderLayout.playerHeaderQueueButton.setOnTouchListener(new QueueSlide());
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


    private int queueCount;

    private void setQueueCount(MediaBrowser mediaBrowser) {
        queueCount = mediaBrowser.getMediaItemCount();
        renderQueueButton();
    }

    /** "Queue 12" with queue mode on; "Queue off", dimmed, with it off. */
    private void renderQueueButton() {
        if (bind == null) return;
        boolean on = Preferences.isQueueMode();
        bind.playerHeaderLayout.playerHeaderQueueButton.setText(!on
                ? getString(R.string.player_header_queue_off)
                : queueCount > 0 ? getString(R.string.player_header_queue_count, queueCount)
                : getString(R.string.player_header_queue));
        bind.playerHeaderLayout.playerHeaderQueueButton.setAlpha(on ? 1f : 0.6f);
    }

    /**
     * Horizontal slide on the Queue button: past a short distance it sets queue mode (right on,
     * left off) instead of opening the queue. The button follows the finger a little and snaps back.
     */
    private final class QueueSlide implements View.OnTouchListener {
        private float downX;
        private float downY;
        private boolean sliding;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            float density = v.getResources().getDisplayMetrics().density;
            float threshold = 24 * density;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    sliding = false;
                    return false;   // let the button show its press and handle a tap
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!sliding && Math.abs(dx) > 8 * density && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        sliding = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        v.setPressed(false);
                        v.cancelLongPress();
                    }
                    if (sliding) {
                        v.setTranslationX(Math.max(-threshold, Math.min(threshold, dx * 0.5f)));
                        return true;
                    }
                    return false;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (!sliding) return false;
                    float dx = e.getRawX() - downX;
                    v.animate().translationX(0).setDuration(150).start();
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    sliding = false;
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && Math.abs(dx) >= threshold) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                        setQueueMode(dx > 0);
                    }
                    return true;   // a slide is never also a tap
                default:
                    return false;
            }
        }
    }

    /** Queue mode switched (a slide on the Queue button, or the switch on the queue screen). */
    public void setQueueMode(boolean on) {
        if (Preferences.isQueueMode() == on) return;
        Preferences.setQueueMode(on);
        com.eddyizm.tempus.util.UpNext.apply(mediaBrowserListenableFuture, on);
        renderQueueButton();
        if (getContext() != null) {
            android.widget.Toast.makeText(getContext(), on ? R.string.queue_mode_now_on : R.string.queue_mode_now_off,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
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

    /** The mini player line's position circle, {@code sizeDp} across. */
    private android.graphics.drawable.Drawable positionThumb(int color, int sizeDp) {
        int size = Math.round(sizeDp * getResources().getDisplayMetrics().density);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(size, size);
        return dot;
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
        // A circle marks the position; drag it (or anywhere on the line) to scrub. It grows while held.
        bar.setThumb(positionThumb(played, 12));
        bar.setSplitTrack(false);   // the line runs under the circle, no gap around it
        bind.playerHeaderLayout.playerHeaderSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) setHeaderTimes(progress, seekBar.getMax());
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                scrubbing = true;
                seekBar.setThumb(positionThumb(played, 18));
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                scrubbing = false;
                seekBar.setThumb(positionThumb(played, 12));
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
