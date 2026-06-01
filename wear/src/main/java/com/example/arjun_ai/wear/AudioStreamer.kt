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
 * Records 16 kHz / mono / 16-bit PCM from the watch mic and streams the bytes
 * over a Data Layer Channel to the connected phone.
 *
 * Phone listens by registering ChannelClient.ChannelCallback on the same path.
 */
class AudioStreamer(private val context: Context) {

    companion object {
        private const val TAG = "AudioStreamer"
        const val CHANNEL_PATH = "/arjun/audio"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channelClient: ChannelClient = Wearable.getChannelClient(context)

    private var channel: ChannelClient.Channel? = null
    private var output: OutputStream? = null
    private var recorder: AudioRecord? = null

    private val streaming = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private var job: Job? = null

    /** Find the first connected node (the phone). */
    private suspend fun findPhoneNode(): Node? {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        Log.d(TAG, "Connected nodes: ${nodes.map { it.displayName }}")
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    @SuppressLint("MissingPermission")
    fun start(onStatus: (String) -> Unit) {
        if (streaming.get()) {
            onStatus("Already streaming")
            return
        }

        job = scope.launch {
            try {
                val node = findPhoneNode()
                if (node == null) {
                    onStatus("No phone connected")
                    return@launch
                }
                onStatus("Opening channel to ${node.displayName}…")

                channel = channelClient.openChannel(node.id, CHANNEL_PATH).await()
                output = channelClient.getOutputStream(channel!!).await()
                onStatus("Channel open. Recording…")

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf, 4096)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufSize
                )
                if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
                    onStatus("AudioRecord failed to init")
                    cleanup()
                    return@launch
                }

                recorder!!.startRecording()
                streaming.set(true)

                val buf = ByteArray(bufSize)
                var totalSent = 0L

                while (streaming.get() && isActive) {
                    val read = recorder!!.read(buf, 0, buf.size)
                    if (read > 0 && !muted.get()) {
                        try {
                            output!!.write(buf, 0, read)
                            totalSent += read
                            if (totalSent % (SAMPLE_RATE * 2) < bufSize) {
                                // roughly once per second
                                onStatus("Streaming… ${totalSent / 1024} KB sent")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "write failed", e)
                            onStatus("Channel write error: ${e.message}")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "start() failed", e)
                onStatus("Error: ${e.message}")
            } finally {
                cleanup()
                onStatus("Stopped")
            }
        }
    }

    fun setMuted(value: Boolean) {
        muted.set(value)
    }

    fun isMuted(): Boolean = muted.get()

    fun stop() {
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
        try { channel?.let { channelClient.close(it) } } catch (_: Exception) {}
        channel = null
        streaming.set(false)
        muted.set(false)
    }
}