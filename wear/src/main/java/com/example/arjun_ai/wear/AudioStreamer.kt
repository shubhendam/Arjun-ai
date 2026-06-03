package com.example.arjun_ai.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch-side. Two responsibilities:
 *  1. sendTrigger(cmd) — tells the phone the user pressed start/stop.
 *  2. startMicStreaming() — if the phone decides the watch should be the audio
 *     source, the phone tells us via /arjun/control "stream", and we open
 *     /arjun/audio and pump mic bytes. Stopped by /arjun/control "stop".
 */
class AudioStreamer(private val context: Context) {

    companion object {
        private const val TAG = "AudioStreamer"
        const val TRIGGER_PATH = "/arjun/trigger"
        const val AUDIO_CHANNEL_PATH = "/arjun/audio"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var channel: ChannelClient.Channel? = null
    private var output: OutputStream? = null
    private var recorder: AudioRecord? = null
    private val streaming = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private var job: Job? = null

    private suspend fun findPhoneNode(): Node? {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    /**
     * Called when user taps a button on the watch UI.
     * Sends a small message to the phone; phone decides what to do.
     */
    fun sendTrigger(cmd: String, onStatus: (String) -> Unit) {
        scope.launch {
            try {
                val node = findPhoneNode()
                if (node == null) {
                    onStatus("No phone connected")
                    return@launch
                }
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, TRIGGER_PATH, cmd.toByteArray())
                    .await()
                Log.d(TAG, "trigger '$cmd' sent to ${node.displayName}")
                onStatus("Sent '$cmd' to phone")
            } catch (e: Exception) {
                Log.e(TAG, "trigger send failed", e)
                onStatus("Trigger error: ${e.message}")
            }
        }
    }

    /**
     * Phone has asked us to stream mic audio.
     * Opens /arjun/audio and pumps until stopMicStreaming() is called.
     */
    @SuppressLint("MissingPermission")
    fun startMicStreaming(onStatus: (String) -> Unit) {
        if (streaming.get()) {
            onStatus("Already streaming")
            return
        }
        job = scope.launch {
            try {
                val node = findPhoneNode() ?: run {
                    onStatus("No phone for streaming"); return@launch
                }
                val client = Wearable.getChannelClient(context)
                channel = client.openChannel(node.id, AUDIO_CHANNEL_PATH).await()
                output = client.getOutputStream(channel!!).await()
                onStatus("Streaming mic to phone…")

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf, 4096)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
                )
                if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
                    onStatus("AudioRecord init failed"); cleanup(); return@launch
                }
                recorder!!.startRecording()
                streaming.set(true)

                val buf = ByteArray(bufSize)
                var total = 0L
                while (streaming.get() && isActive) {
                    val n = recorder!!.read(buf, 0, buf.size)
                    if (n > 0 && !muted.get()) {
                        try {
                            output!!.write(buf, 0, n)
                            total += n
                            if (total % (SAMPLE_RATE * 2) < bufSize) {
                                onStatus("Streaming… ${total / 1024} KB")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "write failed", e)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startMicStreaming failed", e)
                onStatus("Stream error: ${e.message}")
            } finally {
                cleanup()
                onStatus("Stopped")
            }
        }
    }

    fun setMuted(value: Boolean) { muted.set(value) }
    fun isMuted(): Boolean = muted.get()

    fun stopMicStreaming() {
        streaming.set(false)
        job?.cancel()
    }

    private fun cleanup() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { output?.flush() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        output = null
        try { channel?.let { Wearable.getChannelClient(context).close(it) } } catch (_: Exception) {}
        channel = null
        streaming.set(false)
        muted.set(false)
    }
}