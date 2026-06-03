package com.example.arjun_ai

import android.Manifest
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
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

    companion object {
        var btPermissionRequester: (() -> Unit)? = null
        var micPermissionRequester: (() -> Unit)? = null
    }

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* next poll picks it up */ }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user can retry start */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, PhoneAudioReceiverService.RECORDINGS_SUBDIR).apply { mkdirs() }
        AudioSink.loadFromDir(dir)
        WatchConnection.start(this, lifecycleScope)
        BluetoothDevices.start(this, lifecycleScope)

        btPermissionRequester = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        micPermissionRequester = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothDevices.hasBtPermission(this)) {
            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { PhoneScreen() }
            }
        }
    }

    override fun onDestroy() {
        WatchConnection.stop()
        BluetoothDevices.stop()
        btPermissionRequester = null
        micPermissionRequester = null
        super.onDestroy()
    }
}

@Composable
fun PhoneScreen() {
    val live by AudioSink.live.collectAsState()
    val recordings by AudioSink.recordings.collectAsState()
    val connection by WatchConnection.status.collectAsState()
    val btDevices by BluetoothDevices.devices.collectAsState()
    val btPermGranted by BluetoothDevices.permissionGranted.collectAsState()
    val inputState by InputSourceManager.state.collectAsState()
    val context = LocalContext.current
    var routingStatus by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Arjun-AI", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Watch ↔ Phone audio bridge", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WatchIndicator(connection)
        }

        // Session card with phone-trigger buttons
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = if (inputState.recording) "● Recording from ${inputState.source}"
                    else "○ Idle",
                    fontWeight = FontWeight.SemiBold,
                    color = if (inputState.recording) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(inputState.message, fontSize = 12.sp)
                if (inputState.totalBytes > 0) {
                    Text("${inputState.totalBytes / 1024} KB", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            InputSourceManager.startSession(context,
                                InputSourceManager.TriggeredFrom.PHONE_APP)
                        },
                        enabled = !inputState.recording
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start from phone")
                    }
                    OutlinedButton(
                        onClick = { InputSourceManager.stopSession(context) },
                        enabled = inputState.recording
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }
                if (routingStatus.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(routingStatus, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        ConnectedDevicesCard(btDevices, btPermGranted)

        Text("Recordings (${recordings.size})",
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No recordings yet — press ▶ on watch or 'Start from phone'",
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
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Text(label, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConnectedDevicesCard(devices: List<BluetoothDevices.Device>, permissionGranted: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Connected devices (${devices.size})",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            if (!permissionGranted) {
                Box(Modifier.fillMaxWidth().clickable {
                    MainActivity.btPermissionRequester?.invoke()
                }.padding(vertical = 6.dp)) {
                    Text("⚠ Nearby-devices permission needed — tap to grant",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            } else if (devices.isEmpty()) {
                Text("None detected", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                devices.forEach { d -> DeviceRow(d) }
            }
        }
    }
}

@Composable
fun DeviceRow(d: BluetoothDevices.Device) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape)
            .background(if (d.isActiveAudio) Color(0xFF22C55E) else Color(0xFF9CA3AF)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(d.name, fontSize = 13.sp,
                fontWeight = if (d.isActiveAudio) FontWeight.SemiBold else FontWeight.Normal)
            Text(d.subtitle, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (d.isActiveAudio) {
            Icon(Icons.Default.Speaker, contentDescription = "Active audio",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
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
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rec.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("${rec.sizeBytes / 1024} KB • ${formatTime(rec.createdAtMillis)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                if (isPlaying) {
                    try { player.stop() } catch (_: Exception) {}
                    try { player.reset() } catch (_: Exception) {}
                    isPlaying = false
                } else {
                    try {
                        player.reset(); player.setDataSource(rec.path)
                        player.setOnCompletionListener { isPlaying = false }
                        player.setOnErrorListener { _, _, _ -> isPlaying = false; true }
                        player.prepare(); player.start(); isPlaying = true
                    } catch (e: Exception) { e.printStackTrace(); isPlaying = false }
                }
            }) {
                Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Stop" else "Play on phone")
            }
            IconButton(onClick = {
                scope.launch {
                    val dest = OutputRouter.decide(context)
                    onStatus("Routing to $dest")
                    when (dest) {
                        OutputRouter.Destination.WATCH ->
                            WatchPlayer.streamWavToWatch(context, File(rec.path)) { onStatus(it) }
                        OutputRouter.Destination.EARBUDS_VIA_PHONE,
                        OutputRouter.Destination.PHONE_SPEAKER -> {
                            try {
                                player.reset(); player.setDataSource(rec.path)
                                player.setOnCompletionListener { isPlaying = false }
                                player.prepare(); player.start(); isPlaying = true
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