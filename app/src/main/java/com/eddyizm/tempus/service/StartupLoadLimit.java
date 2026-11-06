package com.eddyizm.tempus.service;

import androidx.annotation.NonNull;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

/**
 * Until playback first starts in this process, buffers only {@link #LIMIT_US} of the restored song.
 *
 * <p>At every app start the player restores the queue and, paused, fills the whole preload buffer
 * (Settings, 60 s by default): for a downloaded FLAC on the box's SD card that is ~2 s of CPU,
 * whether or not anything is played. 10 s is plenty to start at once. From the first play on the
 * wrapped control decides as before, so a paused stream still fills its buffer while there is
 * signal.
 *
 * <p>Every method ExoPlayer calls is forwarded explicitly: LoadControl's Java default methods
 * throw "not implemented", and Kotlin interface delegation does not forward them.
 */
@UnstableApi
public final class StartupLoadLimit implements LoadControl {
    private static final long LIMIT_US = 10_000_000L;

    private final LoadControl delegate;
    private volatile boolean played;

    public StartupLoadLimit(@NonNull LoadControl delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean shouldContinueLoading(Parameters parameters) {
        if (parameters.playWhenReady) played = true;
        if (!played && parameters.bufferedDurationUs >= LIMIT_US) return false;
        return delegate.shouldContinueLoading(parameters);
    }

    @Override
    public void onPrepared(PlayerId playerId) {
        delegate.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups,
                                 @NullableType ExoTrackSelection[] trackSelections) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        delegate.onStopped(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        delegate.onReleased(playerId);
    }

    @Override
    public Allocator getAllocator(PlayerId playerId) {
        return delegate.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return delegate.getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return delegate.retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline,
                                            MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        return delegate.shouldStartPlayback(parameters);
    }
}
