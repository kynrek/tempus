package com.eddyizm.tempus.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.eddyizm.tempus.R

/** What the queue screen's playback buttons do; the bridge wires them to the player. */
class QueueControlActions(
    val previous: () -> Unit,
    val seekBack: () -> Unit,
    val playPause: () -> Unit,
    val seekForward: () -> Unit,
    val next: () -> Unit,
)

/** Playback controls along the bottom of the queue screen, so the queue never hides them. */
@Composable
fun QueueControls(isPlaying: Boolean, actions: QueueControlActions, modifier: Modifier = Modifier) {
    HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlButton(R.drawable.ic_skip_previous, R.string.player_queue_previous, actions.previous)
        ControlButton(R.drawable.ic_replay, R.string.player_queue_seek_back, actions.seekBack)
        ControlButton(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            if (isPlaying) R.string.player_queue_pause else R.string.player_queue_play,
            actions.playPause, big = true
        )
        ControlButton(R.drawable.ic_forward, R.string.player_queue_seek_forward, actions.seekForward)
        ControlButton(R.drawable.ic_skip_next, R.string.player_queue_next, actions.next)
    }
}

@Composable
private fun ControlButton(icon: Int, label: Int, onClick: () -> Unit, big: Boolean = false) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (big) 56.dp else 48.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = androidx.compose.ui.res.stringResource(label),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(if (big) 34.dp else 28.dp)
        )
    }
}
