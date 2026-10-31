package com.eddyizm.tempus.ui.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.queue.QueueSongItem

/**
 * Puts [PlaylistEditorScreen] in the editor fragment's ComposeView. The rows here mirror the view
 * model's list: every move and remove is made on both, so the fragment's save reads the list as it
 * stands and a rotation rebuilds the rows from it.
 */
class PlaylistEditorBridge(
    private val composeView: ComposeView,
    private val listener: Listener
) {
    interface Listener {
        fun onBack()
        fun onSave()
        fun onRename()
        fun onMove(from: Int, to: Int)
        fun onRemove(index: Int)
        fun hasUnsavedChanges(): Boolean
    }

    private val rows = mutableStateListOf<QueueSongItem>()

    // A playlist can hold a song twice, so a row's key is its own, not the song id.
    private var nextKey = 0L
    private var shownName by mutableStateOf("")
    private var isLoaded by mutableStateOf(false)
    private var saveInProgress by mutableStateOf(false)
    private var hasChanges by mutableStateOf(false)

    fun init(name: String) {
        shownName = name
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            PlaylistEditorScreen(
                name = shownName,
                rows = rows,
                isLoaded = isLoaded,
                isSaving = saveInProgress,
                hasChanges = hasChanges,
                onBack = listener::onBack,
                onRename = listener::onRename,
                onSave = listener::onSave,
                onMove = ::move,
                onRemove = ::remove
            )
        }
    }

    fun setSongs(songs: List<Child>) {
        rows.clear()
        rows.addAll(songs.map { QueueSongItem(stableId = nextKey++, song = it) })
        isLoaded = true
        refreshChanges()
    }

    fun setName(name: String) {
        shownName = name
        refreshChanges()
    }

    /** Puts back a removed track at the index the view model used. */
    fun restore(index: Int, song: Child) {
        rows.add(index.coerceIn(0, rows.size), QueueSongItem(stableId = nextKey++, song = song))
        refreshChanges()
    }

    fun setSaving(saving: Boolean) {
        saveInProgress = saving
    }

    private fun move(from: Int, to: Int) {
        if (saveInProgress || from !in rows.indices || to !in rows.indices) return
        rows.add(to, rows.removeAt(from))
        listener.onMove(from, to)
        refreshChanges()
    }

    private fun remove(index: Int) {
        if (saveInProgress || index !in rows.indices) return
        rows.removeAt(index)
        listener.onRemove(index)
        refreshChanges()
    }

    private fun refreshChanges() {
        hasChanges = listener.hasUnsavedChanges()
    }
}
