package com.example.arjun_ai

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, PhoneAudioReceiverService.RECORDINGS_SUBDIR).apply { mkdirs() }
        AudioSink.loadFromDir(dir)
        WatchConnection.start(this, lifecycleScope)

        setContent {
            MaterialTheme {
                // Surface fills the whole screen; the content inside respects
                // status/nav bars via systemBarsPadding().
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhoneScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        WatchConnection.stop()
        super.onDestroy()
    }
}

@Composable
fun PhoneScreen() {
    val live by AudioSink.live.collectAsState()
    val recordings by AudioSink.recordings.collectAsState()
    val connection by WatchConnection.status.collectAsState()
    var routingStatus by remember { mutableStateOf("") }

    // systemBarsPadding() = insets for both status bar (top) and nav bar (bottom).
    // imePadding() pushes content above the keyboard if it ever opens.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Arjun-AI", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Watch ↔ Phone audio bridge", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WatchIndicator(connection)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = if (live.state == AudioSink.LiveState.STREAMING) "● Live"
                    else "○ Idle",
                    fontWeight = FontWeight.SemiBold,
                    color = if (live.state == AudioSink.LiveState.STREAMING)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(live.message, fontSize = 13.sp)
                if (routingStatus.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(routingStatus, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        Text("Recordings (${recordings.size})",
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No recordings yet — press ▶ on your watch",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(recordings, key = { it.path }) { rec ->
                    RecordingRow(rec, onStatus = { routingStatus = it })
                }
            }
        }
    }
}

@Composable
fun WatchIndicator(status: WatchConnection.Status) {
    val (dotColor, label) = when (status.state) {
        WatchConnection.State.CONNECTED -> Color(0xFF22C55E) to (status.nodeName ?: "Watch")
        WatchConnection.State.DISCONNECTED -> Color(0xFF9CA3AF) to "No watch"
        WatchConnection.State.UNKNOWN -> Color(0xFFFBBF24) to "Checking…"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(label, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecordingRow(rec: AudioSink.Recording, onStatus: (String) -> Unit) {
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(rec.path) {
        onDispose {
            try { player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rec.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${rec.sizeBytes / 1024} KB • ${formatTime(rec.createdAtMillis)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = {
                if (isPlaying) {
                    try { player.stop() } catch (_: Exception) {}
                    try { player.reset() } catch (_: Exception) {}
                    isPlaying = false
                } else {
                    try {
                        player.reset()
                        player.setDataSource(rec.path)
                        player.setOnCompletionListener { isPlaying = false }
                        player.setOnErrorListener { _, _, _ -> isPlaying = false; true }
                        player.prepare()
                        player.start()
                        isPlaying = true
                    } catch (e: Exception) { e.printStackTrace(); isPlaying = false }
                }
            }) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play on phone"
                )
            }

            IconButton(onClick = {
                scope.launch {
                    val dest = OutputRouter.decide(context)
                    onStatus("Routing to $dest")
                    when (dest) {
                        OutputRouter.Destination.WATCH -> {
                            WatchPlayer.streamWavToWatch(context, File(rec.path)) {
                                onStatus(it)
                            }
                        }
                        OutputRouter.Destination.EARBUDS_VIA_PHONE,
                        OutputRouter.Destination.PHONE_SPEAKER -> {
                            try {
                                player.reset()
                                player.setDataSource(rec.path)
                                player.setOnCompletionListener { isPlaying = false }
                                player.prepare()
                                player.start()
                                isPlaying = true
                            } catch (e: Exception) {
                                onStatus("Playback error: ${e.message}")
                            }
                        }
                    }
                }
            }) {
                Icon(Icons.Default.Watch, contentDescription = "Play with routing",
                    tint = MaterialTheme.colorScheme.primary)
            }

            IconButton(onClick = {
                if (isPlaying) { try { player.stop() } catch (_: Exception) {}; isPlaying = false }
                File(rec.path).takeIf { it.exists() }?.delete()
                AudioSink.removeRecording(rec.path)
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatTime(epochMs: Long): String {
    return SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}