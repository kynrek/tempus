package com.eddyizm.tempus.service

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl

/**
 * Until playback first starts in this process, buffers only [limitUs] of the restored song.
 *
 * At every app start the player restores the queue and, paused, fills the whole preload buffer
 * (Settings, 60 s by default): for a downloaded FLAC on the box's SD card that is ~2 s of CPU and
 * a stream of reads through MediaProvider, whether or not anything is played. 10 s is plenty to
 * start at once. From the first play on, [delegate] decides as before, so a paused stream still
 * fills its buffer while there is signal.
 */
@UnstableApi
class StartupLoadLimit(
    private val delegate: LoadControl,
    private val limitUs: Long = 10_000_000L,
) : LoadControl by delegate {
    @Volatile
    private var played = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (parameters.playWhenReady) played = true
        if (!played && parameters.bufferedDurationUs >= limitUs) return false
        return delegate.shouldContinueLoading(parameters)
    }
}
