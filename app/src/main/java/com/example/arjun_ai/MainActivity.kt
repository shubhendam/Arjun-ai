package com.example.arjun_ai

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
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
import com.example.arjun_ai.agent.AgentSession
import com.example.arjun_ai.agent.AgentState
import com.example.arjun_ai.agent.ChatMessage
import com.example.arjun_ai.agent.Role
import com.example.arjun_ai.agent.isAgentModelPresent
import com.example.arjun_ai.theme.ArjunTheme
import com.example.arjun_ai.theme.StatusOrb
import com.example.arjun_ai.theme.jarvisBackground
import com.example.arjun_ai.tts.VoiceConfig
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        var btPermissionRequester: (() -> Unit)? = null
        var micPermissionRequester: (() -> Unit)? = null
        var contactsPermissionRequester: (() -> Unit)? = null
    }

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    private val contactsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(filesDir, PhoneAudioReceiverService.RECORDINGS_SUBDIR).apply { mkdirs() }
        AudioSink.loadFromDir(dir)
        WatchConnection.start(this, lifecycleScope)
        BluetoothDevices.start(this, lifecycleScope)
        MemoryMeter.start(this, lifecycleScope)

        btPermissionRequester = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        micPermissionRequester = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        contactsPermissionRequester = { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !BluetoothDevices.hasBtPermission(this))
            btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)

        setContent {
            ArjunTheme {
                Box(modifier = Modifier.fillMaxSize().jarvisBackground()) { AppNav() }
            }
        }
    }

    override fun onDestroy() {
        WatchConnection.stop()
        BluetoothDevices.stop()
        MemoryMeter.stop()
        btPermissionRequester = null
        micPermissionRequester = null
        contactsPermissionRequester = null
        super.onDestroy()
    }
}

private enum class Screen { SETUP, CHAT, AUDIO_TEST }

@Composable
fun AppNav() {
    var screen by remember { mutableStateOf(Screen.SETUP) }
    val agent by AgentSession.ui.collectAsState()

    // When the model starts loading / is live, show the chat screen.
    LaunchedEffect(agent.state) {
        when (agent.state) {
            AgentState.LOADING, AgentState.READY, AgentState.LISTENING,
            AgentState.PROCESSING, AgentState.SPEAKING ->
                if (screen == Screen.SETUP) screen = Screen.CHAT
            AgentState.IDLE -> if (screen == Screen.CHAT) screen = Screen.SETUP
            AgentState.ERROR -> { /* stay; chat screen shows the error */ }
        }
    }

    when (screen) {
        Screen.SETUP -> SetupScreen(onOpenAudioTest = { screen = Screen.AUDIO_TEST })
        Screen.CHAT -> ChatScreen(onExitToSetup = { screen = Screen.SETUP })
        Screen.AUDIO_TEST -> AudioTestScreen(onBack = { screen = Screen.SETUP })
    }
}

// =============================================================================
// Setup screen
// =============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onOpenAudioTest: () -> Unit) {
    val context = LocalContext.current
    val agent by AgentSession.ui.collectAsState()
    val connection by WatchConnection.status.collectAsState()
    val mem by MemoryMeter.mem.collectAsState()
    var voiceMenuOpen by remember { mutableStateOf(false) }
    var modelPresent by remember { mutableStateOf(isAgentModelPresent()) }

    val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Environment.isExternalStorageManager() else true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top bar: hamburger | title + watch | RAM
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenAudioTest) {
                Icon(Icons.Default.Menu, contentDescription = "Audio test")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("ARJUN", fontSize = 24.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp, color = MaterialTheme.colorScheme.primary)
                Text("// LOCAL AI ASSISTANT", fontSize = 9.sp, letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                WatchIndicator(connection)
            }
            RamMeter(mem)
        }

        Text("System prompt", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = agent.systemPrompt,
            onValueChange = { AgentSession.updateSystemPrompt(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
        )

        Text("Voice", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { voiceMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(agent.selectedVoice.label + " — " + agent.selectedVoice.engine.name)
            }
            DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                VoiceConfig.ALL_VOICES.forEach { v ->
                    DropdownMenuItem(
                        text = { Text("${v.label} • ${v.qualityLabel}") },
                        onClick = { AgentSession.selectVoice(v); voiceMenuOpen = false }
                    )
                }
            }
        }

        if (!modelPresent) {
            Text("⚠ Gemma model not found in /sdcard/Download/. Push gemma-4-E4B-it.litertlm there.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { modelPresent = isAgentModelPresent() })
        }
        if (!storageOk) {
            Text("⚠ Grant 'All files access' so the model can be read — tap to open settings.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable {
                    try {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                })
        }

        Button(
            onClick = { AgentSession.loadModel(context) },
            enabled = agent.state == AgentState.IDLE || agent.state == AgentState.ERROR,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start Arjun AI  (load model)")
        }

        if (agent.errorMessage != null) {
            Text(agent.errorMessage!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun RamMeter(mem: MemoryMeter.Mem) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.Memory, contentDescription = "RAM",
            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(horizontalAlignment = Alignment.End) {
            Text("${mem.deviceUsedMb}/${mem.deviceTotalMb} MB", fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold)
            Text("app ${mem.appUsedMb} MB", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// =============================================================================
// Chat screen
// =============================================================================
@Composable
fun ChatScreen(onExitToSetup: () -> Unit) {
    val context = LocalContext.current
    val agent by AgentSession.ui.collectAsState()
    val mem by MemoryMeter.mem.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusOrb(
                    color = stateColor(agent.state),
                    active = agent.state == AgentState.LISTENING ||
                            agent.state == AgentState.PROCESSING ||
                            agent.state == AgentState.SPEAKING,
                    size = 16.dp,
                )
                Column {
                    Text("ARJUN", fontSize = 20.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp, color = MaterialTheme.colorScheme.primary)
                    Text(stateLabel(agent.state), fontSize = 11.sp, color = stateColor(agent.state))
                }
            }
            RamMeter(mem)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                AgentSession.unloadModel(context)
                onExitToSetup()
            }) { Text("Unload") }
        }

        when (agent.state) {
            AgentState.LOADING -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Loading + warming up…", fontSize = 13.sp)
                }
            }
            AgentState.ERROR -> Column {
                Text(agent.errorMessage ?: "Error", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { AgentSession.unloadModel(context); onExitToSetup() }) { Text("Back to setup") }
            }
            else -> Text("Press ▶ on the watch to talk. Say \"stop\" or press ⏹ to end.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(agent.messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp)
            ) {
                Column {
                    Text(msg.text.ifBlank { if (msg.isStreaming) "…" else "" }, fontSize = 14.sp)
                    msg.stats?.let {
                        Text("${it.tokenCount} tok • ${"%.1f".format(it.tokensPerSecond)} tok/s",
                            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (msg.audioPath != null) {
                Spacer(Modifier.height(3.dp))
                AudioMiniPlayer(msg.audioPath)
            }
        }
    }
}

@Composable
fun AudioMiniPlayer(path: String) {
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    DisposableEffect(path) {
        onDispose {
            try { player.stop() } catch (_: Exception) {}
            try { player.release() } catch (_: Exception) {}
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable {
                if (isPlaying) {
                    try { player.stop() } catch (_: Exception) {}
                    try { player.reset() } catch (_: Exception) {}
                    isPlaying = false
                } else if (File(path).exists()) {
                    try {
                        player.reset(); player.setDataSource(path)
                        player.setOnCompletionListener { isPlaying = false }
                        player.setOnErrorListener { _, _, _ -> isPlaying = false; true }
                        player.prepare(); player.start(); isPlaying = true
                    } catch (e: Exception) { isPlaying = false }
                }
            }
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = "Play your audio",
            modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Text("your voice", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun stateLabel(s: AgentState): String = when (s) {
    AgentState.IDLE -> "Idle"
    AgentState.LOADING -> "Loading…"
    AgentState.READY -> "Ready — press ▶ on watch"
    AgentState.LISTENING -> "● Listening"
    AgentState.PROCESSING -> "Thinking…"
    AgentState.SPEAKING -> "Speaking…"
    AgentState.ERROR -> "Error"
}

@Composable
private fun stateColor(s: AgentState): Color = when (s) {
    AgentState.LISTENING -> Color(0xFF22C55E)
    AgentState.PROCESSING -> Color(0xFF2196F3)
    AgentState.SPEAKING -> Color(0xFFFF9800)
    AgentState.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// =============================================================================
// Audio Test screen (Phase-1 flow, moved behind the hamburger)
// =============================================================================
@Composable
fun AudioTestScreen(onBack: () -> Unit) {
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("AUDIO TEST", fontSize = 20.sp, fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp, color = MaterialTheme.colorScheme.primary)
                Text("// phase-1 watch ↔ phone bridge", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WatchIndicator(connection)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = if (inputState.recording) "● Recording from ${inputState.source}" else "○ Idle",
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
                        onClick = { InputSourceManager.startSession(context, InputSourceManager.TriggeredFrom.PHONE_APP) },
                        enabled = !inputState.recording
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start from phone")
                    }
                    OutlinedButton(
                        onClick = { InputSourceManager.stopSession(context) },
                        enabled = inputState.recording
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop")
                    }
                }
                if (routingStatus.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(routingStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        ConnectedDevicesCard(btDevices, btPermGranted)

        Text("Recordings (${recordings.size})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No recordings yet — press ▶ on watch or 'Start from phone'",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConnectedDevicesCard(devices: List<BluetoothDevices.Device>, permissionGranted: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Connected devices (${devices.size})", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            if (!permissionGranted) {
                Box(Modifier.fillMaxWidth().clickable { MainActivity.btPermissionRequester?.invoke() }
                    .padding(vertical = 6.dp)) {
                    Text("⚠ Nearby-devices permission needed — tap to grant",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            } else if (devices.isEmpty()) {
                Text("None detected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(d.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (d.isActiveAudio) {
            Icon(Icons.Default.Speaker, contentDescription = "Active audio",
                modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
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
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rec.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("${rec.sizeBytes / 1024} KB • ${formatTime(rec.createdAtMillis)}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            } catch (e: Exception) { onStatus("Playback error: ${e.message}") }
                        }
                    }
                }
            }) {
                Icon(Icons.Default.Speaker, contentDescription = "Play with routing",
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = {
                if (isPlaying) { try { player.stop() } catch (_: Exception) {}; isPlaying = false }
                File(rec.path).takeIf { it.exists() }?.delete()
                AudioSink.removeRecording(rec.path)
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
