package com.example.arjun_ai

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams PCM audio bytes to the watch over Data Layer channel /arjun/tts.
 *
 * Wire format on the channel:
 *   [4 bytes BE magic = 0x41524A4E "ARJN"]
 *   [4 bytes LE int = sample rate]
 *   [4 bytes LE int = channels  (1 = mono)]
 *   [4 bytes LE int = bits per sample (16)]
 *   [...PCM bytes until channel close...]
 */
object WatchPlayer {

    private const val TAG = "WatchPlayer"
    private const val CHANNEL_PATH = "/arjun/tts"

    /**
     * Streams a WAV file to the watch.
     * Returns true on success, false if no watch reachable / channel failed.
     */
    suspend fun streamWavToWatch(
        context: Context,
        wavFile: File,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parsed = parseWav(wavFile) ?: run {
                onStatus("Bad WAV file"); return@withContext false
            }
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (target == null) {
                onStatus("No watch connected")
                return@withContext false
            }

            onStatus("Opening watch channel…")
            val client = Wearable.getChannelClient(context)
            val channel = client.openChannel(target.id, CHANNEL_PATH).await()
            val out: OutputStream = client.getOutputStream(channel).await()

            // Header
            val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            // magic "ARJN" stored big-endian-readable as 4 bytes
            header.put('A'.code.toByte()); header.put('R'.code.toByte())
            header.put('J'.code.toByte()); header.put('N'.code.toByte())
            header.putInt(parsed.sampleRate)
            header.putInt(parsed.channels)
            header.putInt(parsed.bitsPerSample)
            out.write(header.array())

            // Payload — stream the file's PCM body
            val pcm = parsed.pcmBytes
            val chunk = 4096
            var offset = 0
            while (offset < pcm.size) {
                val n = minOf(chunk, pcm.size - offset)
                out.write(pcm, offset, n)
                offset += n
                if (offset % (chunk * 8) == 0) onStatus("Streaming ${offset / 1024} KB…")
            }
            out.flush()
            out.close()
            client.close(channel).await()
            onStatus("Sent ${pcm.size / 1024} KB to watch")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "stream failed", e)
            onStatus("Watch stream error: ${e.message}")
            return@withContext false
        }
    }

    private data class WavData(
        val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val pcmBytes: ByteArray
    )

    private fun parseWav(file: File): WavData? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Search for "fmt " and "data" chunks (not always at fixed offsets)
        var i = 12
        var sampleRate = 0; var channels = 0; var bits = 0
        var pcm: ByteArray? = null
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val size = bb.getInt(i + 4)
            val payloadStart = i + 8
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(payloadStart + 2).toInt()
                    sampleRate = bb.getInt(payloadStart + 4)
                    bits = bb.getShort(payloadStart + 14).toInt()
                }
                "data" -> {
                    pcm = bytes.copyOfRange(payloadStart, payloadStart + size)
                }
            }
            i = payloadStart + size + (size and 1) // chunks are word-aligned
            if (pcm != null && sampleRate > 0) break
        }
        return pcm?.let { WavData(sampleRate, channels, bits, it) }
    }
}