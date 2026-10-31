package com.eddyizm.tempus.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.queue.PlayerQueueItem
import com.eddyizm.tempus.ui.queue.QueueSongItem
import com.eddyizm.tempus.ui.queue.rememberAppColorScheme
import com.eddyizm.tempus.util.Preferences
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The playlist editor, laid out like the full-panel queue: a back arrow, the name and count, and
 * one row per track with a trash button and a drag handle. Nothing reaches the server until Save.
 */
@Composable
fun PlaylistEditorScreen(
    name: String,
    rows: List<QueueSongItem>,
    isLoaded: Boolean,
    isSaving: Boolean,
    hasChanges: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (Preferences.getTheme()) {
        "light" -> false
        "dark" -> true
        else -> isSystemDark
    }
    val isAmoled = isDark && Preferences.isDarkThemeBlack()

    val colorScheme = rememberAppColorScheme()
    val containerColor = if (isAmoled) Color.Black else colorScheme.surfaceContainerLow

    // A move leaves the total alone, so the header is not recomposed on every step of a drag.
    val totalDurationSeconds by remember(rows) {
        derivedStateOf { rows.sumOf { (it.song.duration ?: 0).toLong() } }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = containerColor,
            contentColor = colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PlaylistEditorHeader(
                    name = name,
                    songCount = rows.size,
                    totalDurationSeconds = totalDurationSeconds,
                    hasChanges = hasChanges,
                    canEdit = isLoaded && !isSaving,
                    isSaving = isSaving,
                    onBack = onBack,
                    onRename = onRename,
                    onSave = onSave
                )

                when {
                    !isLoaded -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    rows.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.playlist_page_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> PlaylistEditorList(
                        rows = rows,
                        isSaving = isSaving,
                        itemContainerColor = containerColor,
                        onMove = onMove,
                        onRemove = onRemove
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistEditorList(
    rows: List<QueueSongItem>,
    isSaving: Boolean,
    itemContainerColor: Color,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current

    // This screen sits under the player, so the end of the list clears the mini player, and a
    // drag scrolls before the handle reaches it.
    val bottomPadding = dimensionResource(R.dimen.bottom_sheet_behavior_peek_height) +
        WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = PaddingValues(bottom = bottomPadding)
    ) { from, to ->
        onMove(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    val density = LocalDensity.current
    val cornerRadius = remember(density) {
        with(density) {
            if (Preferences.isCornerRoundingEnabled()) {
                Preferences.getRoundedCornerSize().toDp()
            } else {
                1.toDp()
            }
        }
    }
    val showRating = Preferences.showItemRating()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        state = lazyListState,
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row -> row.stableId }
        ) { index, row ->
            ReorderableItem(
                state = reorderableState,
                key = row.stableId
            ) { isDragging ->
                PlayerQueueItem(
                    index = index,
                    item = row,
                    isCurrent = false,
                    isPlaying = false,
                    isPrior = false,
                    isDragging = isDragging,
                    itemContainerColor = itemContainerColor,
                    cornerRadius = cornerRadius,
                    showRating = showRating,
                    onClick = { _, _ -> },
                    onRemove = { removeIndex, _ -> onRemove(removeIndex) },
                    dragHandleModifier = Modifier.draggableHandle(
                        enabled = !isSaving,
                        onDragStarted = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    ),
                    // A playlist keeps at least one track, the server has no way to empty one.
                    canDismiss = !isSaving && rows.size > 1,
                    removeLabel = R.string.playlist_editor_remove_track
                )
            }
        }
    }
}

@Composable
private fun PlaylistEditorHeader(
    name: String,
    songCount: Int,
    totalDurationSeconds: Long,
    hasChanges: Boolean,
    canEdit: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
) {
    val hours = (totalDurationSeconds / 3600).toInt()
    val minutes = ((totalDurationSeconds % 3600) / 60).toInt()
    val seconds = (totalDurationSeconds % 60).toInt()
    val countText = pluralStringResource(R.plurals.player_queue_song_count, songCount, songCount)
    val durationText = when {
        hours > 0 && minutes > 0 -> "${pluralStringResource(R.plurals.player_queue_duration_hours, hours, hours)} ${pluralStringResource(R.plurals.player_queue_duration_minutes, minutes, minutes)}"
        hours > 0 -> pluralStringResource(R.plurals.player_queue_duration_hours, hours, hours)
        minutes > 0 -> pluralStringResource(R.plurals.player_queue_duration_minutes, minutes, minutes)
        else -> pluralStringResource(R.plurals.player_queue_duration_seconds, seconds, seconds)
    }
    val subtitle = buildString {
        append(countText).append(" • ").append(durationText)
        if (hasChanges) append(" • ").append(stringResource(R.string.playlist_editor_unsaved))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.player_queue_back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // The name renames on a tap, like the pencil beside it.
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canEdit, onClick = onRename)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasChanges) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onRename, enabled = canEdit, modifier = Modifier.size(48.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = stringResource(R.string.playlist_editor_rename),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onSave,
            enabled = canEdit && hasChanges,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
            modifier = Modifier.height(48.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.playlist_editor_dialog_positive_button),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
