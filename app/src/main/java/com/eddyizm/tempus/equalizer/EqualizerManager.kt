package com.eddyizm.tempus.equalizer

import android.content.Context
import android.util.Log

/**
 * The service attaches and releases the effect on its playback thread, while the equalizer screens
 * read and set bands from the main thread: every call holds this manager's lock, and a screen that
 * reaches an effect already released (the equalizer was just reloaded) gets nothing instead of a crash.
 */
class EqualizerManager(
    private var backend: EqualizerBackend,
    private var context: Context
) {

    @Synchronized
    fun attach(audioSessionId: Int): Boolean {
        return backend.attach(audioSessionId, context.applicationContext)
    }

    @Synchronized
    fun release(audioSessionId: Int) {
        backend.release(audioSessionId, context.applicationContext)
    }

    @Synchronized
    fun setBandLevel(band: Short, level: Short) {
        guarded(Unit) { backend.setBandLevel(band, level) }
    }

    @Synchronized
    fun getNumberOfBands(): Short = guarded(0.toShort()) { backend.getNumberOfBands() }

    @Synchronized
    fun getBandLevelRange(): ShortArray? = guarded(null) { backend.getBandLevelRange() }

    @Synchronized
    fun getCenterFreq(band: Short): Int? = guarded(null) { backend.getCenterFreq(band) }

    @Synchronized
    fun getBandLevel(band: Short): Short? = guarded(null) { backend.getBandLevel(band) }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        guarded(Unit) { backend.setEnabled(enabled) }
    }

    private inline fun <T> guarded(fallback: T, call: () -> T): T = try {
        call()
    } catch (e: IllegalStateException) {
        Log.w("EqualizerManager", "Equalizer effect not available", e)
        fallback
    } catch (e: UnsupportedOperationException) {
        Log.w("EqualizerManager", "Equalizer effect not available", e)
        fallback
    }
}
