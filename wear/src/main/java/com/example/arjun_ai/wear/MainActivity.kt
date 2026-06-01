package com.example.arjun_ai.wear

import android.Manifest
import android.content.pm.PackageManager
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

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled by recomposition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        streamer = AudioStreamer(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent { ArjunWatchApp(streamer) }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamer.stop()
    }
}

@Composable
fun ArjunWatchApp(streamer: AudioStreamer) {
    var status by remember { mutableStateOf("Idle") }
    var isStreaming by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = "Arjun-AI",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = status,
                fontSize = 10.sp,
                color = Color(0xFFAAAAAA)
            )

            Spacer(Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Start
                Button(
                    onClick = {
                        if (!isStreaming) {
                            isMuted = false
                            streamer.setMuted(false)
                            streamer.start { s ->
                                status = s
                                if (s.startsWith("Stopped") || s.startsWith("Error") ||
                                    s.startsWith("No phone")) {
                                    isStreaming = false
                                }
                            }
                            isStreaming = true
                        }
                    },
                    enabled = !isStreaming,
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                }

                // Mute / Unmute
                Button(
                    onClick = {
                        isMuted = !isMuted
                        streamer.setMuted(isMuted)
                        status = if (isMuted) "Muted" else "Streaming…"
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
                        streamer.stop()
                        isStreaming = false
                        isMuted = false
                        status = "Idle"
                    },
                    enabled = isStreaming,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}