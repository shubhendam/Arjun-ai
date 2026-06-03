package com.example.arjun_ai.wear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*

class MainActivity : ComponentActivity() {

    private lateinit var streamer: AudioStreamer
    private var onRemoteCmd: ((String) -> Unit)? = null

    private val remoteCmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cmd = intent?.getStringExtra(ControlListener.EXTRA_CMD) ?: return
            onRemoteCmd?.invoke(cmd)
        }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* nothing */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamer = AudioStreamer(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        val filter = IntentFilter(ControlListener.ACTION_REMOTE_CMD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteCmdReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(remoteCmdReceiver, filter)
        }

        setContent {
            ArjunWatchApp(
                streamer = streamer,
                registerRemoteCmd = { handler -> onRemoteCmd = handler }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamer.stopMicStreaming()
        try { unregisterReceiver(remoteCmdReceiver) } catch (_: Exception) {}
    }
}

@Composable
fun ArjunWatchApp(
    streamer: AudioStreamer,
    registerRemoteCmd: ((String) -> Unit) -> Unit
) {
    var status by remember { mutableStateOf("Idle") }
    var sessionActive by remember { mutableStateOf(false) }
    var isStreaming by remember { mutableStateOf(false) }   // we are the audio src
    var isMuted by remember { mutableStateOf(false) }

    // Phone-driven commands
    LaunchedEffect(Unit) {
        registerRemoteCmd { cmd ->
            when (cmd) {
                "stream" -> {
                    status = "Phone asked us to stream"
                    streamer.startMicStreaming { s ->
                        status = s
                        if (s.startsWith("Stopped") || s.startsWith("Error")) {
                            isStreaming = false
                        }
                    }
                    isStreaming = true
                }
                "stop" -> {
                    streamer.stopMicStreaming()
                    isStreaming = false
                    sessionActive = false
                    isMuted = false
                    status = "Stopped by phone"
                }
            }
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Text("Arjun-AI", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(status, fontSize = 10.sp, color = Color(0xFFAAAAAA))
            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start
                Button(
                    onClick = {
                        if (!sessionActive) {
                            streamer.sendTrigger("start") { status = it }
                            sessionActive = true
                            isMuted = false
                        }
                    },
                    enabled = !sessionActive,
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                }
                // Mute (only meaningful if WE are the source)
                Button(
                    onClick = {
                        isMuted = !isMuted
                        streamer.setMuted(isMuted)
                        status = if (isMuted) "Muted (mic only)" else "Unmuted"
                    },
                    enabled = isStreaming,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(
                        if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute"
                    )
                }
                // Stop
                Button(
                    onClick = {
                        streamer.sendTrigger("stop") { status = it }
                        streamer.stopMicStreaming()
                        sessionActive = false
                        isStreaming = false
                        isMuted = false
                    },
                    enabled = sessionActive,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}