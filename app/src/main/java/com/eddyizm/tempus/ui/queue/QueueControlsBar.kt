package com.eddyizm.tempus.ui.queue

import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.fragment.PlayerBottomSheetFragment
import com.eddyizm.tempus.util.MusicUtil
import kotlinx.coroutines.delay

/**
 * Playback controls along the bottom of the queue screen. The queue is drawn in its own window
 * over the player, which hides the mini player; this bar stands in for it. It reads what the
 * mini player shows and presses its buttons, so it acts exactly like the mini player does.
 */
@OptIn(UnstableApi::class)
@Composable
fun QueueControlsBar(fragment: PlayerBottomSheetFragment, modifier: Modifier = Modifier) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var seekButtons by remember { mutableStateOf(true) }
    var nextEnabled by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    // The mini player keeps itself current (track changes, the progress ticker, the LAN remote);
    // follow it twice a second, and right after a press.
    LaunchedEffect(refresh) {
        while (true) {
            title = fragment.barTitle()?.toString().orEmpty()
            artist = fragment.barArtist()?.toString().orEmpty()
            playing = fragment.barPlaying()
            position = fragment.barPositionSeconds()
            duration = fragment.barDurationSeconds()
            seekButtons = fragment.barHasSeekButtons()
            nextEnabled = fragment.barNextEnabled()
            delay(500)
        }
    }

    fun press(button: String) {
        fragment.barPress(button)
        refresh++
    }

    val haptics = LocalHapticFeedback.current
    fun longPress(button: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        fragment.barLongPress(button)
        refresh++
    }

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (seekButtons) {
                    LongPressIconButton(onClick = { press("rewind") }, onLongClick = { longPress("rewind") }) {
                        Icon(painterResource(R.drawable.ic_replay), stringResource(R.string.queue_bar_rewind), Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = { press("play") }) {
                    Icon(
                        painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                        stringResource(if (playing) R.string.queue_bar_pause else R.string.queue_bar_play),
                        Modifier.size(32.dp)
                    )
                }
                if (seekButtons) {
                    LongPressIconButton(onClick = { press("forward") }, onLongClick = { longPress("forward") }) {
                        Icon(painterResource(R.drawable.ic_forward), stringResource(R.string.queue_bar_forward), Modifier.size(28.dp))
                    }
                } else {
                    // Only while the seek buttons are hidden (the LAN remote); otherwise a long
                    // press on fast forward is Next, as on the mini player.
                    IconButton(onClick = { press("next") }, enabled = nextEnabled) {
                        Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.queue_bar_next), Modifier.size(28.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = dragging?.toInt() ?: position
                Text(
                    text = MusicUtil.getReadableDurationString(shown.toLong(), false),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(min = 36.dp)
                )
                Slider(
                    value = (dragging ?: position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { fragment.barSeekTo(it.toInt()) }
                        dragging = null
                        refresh++
                    },
                    enabled = duration > 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = MusicUtil.getReadableDurationString(duration.toLong(), false),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(min = 36.dp)
                )
            }
        }
    }
}

/** An icon button that also takes a long press (IconButton has no long press). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressIconButton(onClick: () -> Unit, onLongClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(
                role = Role.Button,
                interactionSource = null,
                indication = androidx.compose.material3.ripple(bounded = false, radius = 24.dp),
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}
