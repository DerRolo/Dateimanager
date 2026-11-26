package de.dateimanager.app

import android.view.SurfaceView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** Kleine Leiste unten, solange Musik läuft. Antippen öffnet den großen Player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(ps: PlayerState, onExpand: () -> Unit) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ps.title,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2000)
                )
                Text(
                    ps.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { ps.toggle() }) {
                Icon(
                    if (ps.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (ps.isPlaying) "Pause" else "Abspielen"
                )
            }
            IconButton(onClick = { ps.next() }) { Icon(Icons.Default.SkipNext, "Nächster Titel") }
            IconButton(onClick = { ps.stop() }) { Icon(Icons.Default.Close, "Beenden") }
        }
    }
}

@Composable
private fun PositionBar(ps: PlayerState, light: Boolean) {
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(ps.hasMedia, ps.ready) {
        while (true) {
            val c = ps.ctrl()
            if (c != null && !dragging) {
                pos = c.currentPosition.coerceAtLeast(0L)
                dur = c.duration.coerceAtLeast(0L)
            }
            delay(400)
        }
    }
    val textColor = if (light) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = pos.toFloat().coerceAtMost(dur.toFloat().coerceAtLeast(1f)),
            onValueChange = {
                dragging = true
                pos = it.toLong()
            },
            onValueChangeFinished = {
                ps.seekTo(pos)
                dragging = false
            },
            valueRange = 0f..dur.toFloat().coerceAtLeast(1f)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Fs.formatDuration(pos), style = MaterialTheme.typography.labelSmall, color = textColor)
            Text(Fs.formatDuration(dur), style = MaterialTheme.typography.labelSmall, color = textColor)
        }
    }
}

/** Großer Musik-Player als halbes Fenster von unten. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(ps: PlayerState, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.MusicNote, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                ps.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2000)
            )
            Text(
                ps.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            PositionBar(ps, false)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { ps.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle, "Zufall",
                        tint = if (ps.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { ps.prev() }) { Icon(Icons.Default.SkipPrevious, "Zurück", Modifier.size(32.dp)) }
                IconButton(onClick = { ps.toggle() }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (ps.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (ps.isPlaying) "Pause" else "Abspielen",
                        Modifier.size(48.dp)
                    )
                }
                IconButton(onClick = { ps.next() }) { Icon(Icons.Default.SkipNext, "Weiter", Modifier.size(32.dp)) }
                IconButton(onClick = { ps.cycleRepeat() }) {
                    Icon(
                        if (ps.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Wiederholen",
                        tint = if (ps.repeat == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "Titel ${ps.index + 1} von ${ps.count}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** Video im Vollbild. Tippen blendet die Bedienung ein und aus. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoDialog(ps: PlayerState, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        var controls by remember { mutableStateOf(true) }
        val ratio = if (ps.videoW > 0 && ps.videoH > 0) ps.videoW.toFloat() / ps.videoH.toFloat() else 16f / 9f
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controls = !controls }
        ) {
            AndroidView(
                factory = { c ->
                    val sv = SurfaceView(c)
                    sv.keepScreenOn = true
                    sv
                },
                update = { sv -> ps.ctrl()?.setVideoSurfaceView(sv) },
                onRelease = { sv -> ps.controller?.clearVideoSurfaceView(sv) },
                modifier = Modifier.align(Alignment.Center).aspectRatio(ratio)
            )
            if (controls) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x66000000))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Schließen", tint = Color.White) }
                    Text(
                        ps.title,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f).basicMarquee(iterations = 1, initialDelayMillis = 2000)
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x66000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    PositionBar(ps, true)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { ps.prev() }) {
                            Icon(Icons.Default.SkipPrevious, "Zurück", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { ps.toggle() }, modifier = Modifier.size(64.dp)) {
                            Icon(
                                if (ps.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (ps.isPlaying) "Pause" else "Abspielen",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        IconButton(onClick = { ps.next() }) {
                            Icon(Icons.Default.SkipNext, "Weiter", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}
