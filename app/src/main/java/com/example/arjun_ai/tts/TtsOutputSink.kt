package com.example.arjun_ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where the remote-TTS PCM stream goes during a conversation. The session picks
 * one based on the locked I/O device so output mirrors input:
 *   - BT headset  -> [PhoneAudioTrackSink] with VOICE_COMMUNICATION usage (SCO)
 *   - phone       -> [PhoneAudioTrackSink] with MEDIA usage (OS routes to earbuds/speaker)
 *   - watch       -> [WatchTtsSink] (streams PCM to /arjun/tts; the watch plays it)
 *
 * Only the remote Kokoro/Piper PCM path uses these sinks. Android-TTS voices play
 * through the system engine locally (see RemoteTtsClient).
 */
interface TtsOutputSink {
    /** Append a PCM chunk (16-bit mono) at [sampleRate] for the current utterance. */
    fun write(pcm: ByteArray, sampleRate: Int)

    /** The current utterance's chunks are all delivered. */
    fun endUtterance() {}

    /** Barge-in / stop: drop any buffered-but-unplayed audio immediately. */
    fun bargeIn() {}

    /** Permanent teardown. */
    fun release() {}
}

/**
 * Streams PCM to a phone-side AudioTrack. [usage] selects routing:
 *   AudioAttributes.USAGE_VOICE_COMMUNICATION -> out the SCO BT headset
 *   AudioAttributes.USAGE_MEDIA               -> earbuds / speaker (OS decides)
 */
class PhoneAudioTrackSink(
    private val usage: Int = AudioAttributes.USAGE_MEDIA,
) : TtsOutputSink {

    private companion object { const val TAG = "PhoneTtsSink" }

    @Volatile private var track: AudioTrack? = null
    private var trackSampleRate = 0
    @Volatile private var cancelCurrent = false

    private fun ensureTrack(sampleRate: Int) {
        if (track != null && trackSampleRate == sampleRate) return
        releaseTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track!!.play()
        trackSampleRate = sampleRate
        Log.d(TAG, "AudioTrack created sr=$sampleRate usage=$usage")
    }

    override fun write(pcm: ByteArray, sampleRate: Int) {
        cancelCurrent = false
        ensureTrack(sampleRate)
        val t = track ?: return
        var offset = 0
        while (offset < pcm.size && !cancelCurrent) {
            val n = minOf(4096, pcm.size - offset)
            val written = t.write(pcm, offset, n)
            if (written > 0) offset += written else break
        }
    }

    override fun bargeIn() {
        cancelCurrent = true
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    private fun releaseTrack() {
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
        trackSampleRate = 0
    }

    override fun release() {
        cancelCurrent = true
        releaseTrack()
    }
}

/**
 * Buffers a whole utterance and streams it to the watch on /arjun/tts using the
 * same 16-byte header WatchPlayer uses ("ARJN" + sr + channels + bits). The watch
 * PlaybackListener reads the header and plays the PCM. One channel open per utterance.
 */
class WatchTtsSink(context: Context) : TtsOutputSink {

    private companion object {
        const val TAG = "WatchTtsSink"
        const val CHANNEL_PATH = "/arjun/tts"
    }

    private val appCtx = context.applicationContext
    private val buffer = ByteArrayOutputStream()
    private var sampleRate = 16_000
    @Volatile private var cancelled = false

    override fun write(pcm: ByteArray, sampleRate: Int) {
        this.sampleRate = sampleRate
        buffer.write(pcm)
    }

    override fun bargeIn() {
        cancelled = true
        buffer.reset()
    }

    override fun endUtterance() {
        val pcm = buffer.toByteArray()
        buffer.reset()
        if (pcm.isEmpty() || cancelled) { cancelled = false; return }
        try {
            runBlocking {
                val nodes = Wearable.getNodeClient(appCtx).connectedNodes.await()
                val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: run {
                    Log.w(TAG, "no watch to stream TTS"); return@runBlocking
                }
                val client = Wearable.getChannelClient(appCtx)
                val channel: ChannelClient.Channel = client.openChannel(target.id, CHANNEL_PATH).await()
                val out: OutputStream = client.getOutputStream(channel).await()

                val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                header.put('A'.code.toByte()); header.put('R'.code.toByte())
                header.put('J'.code.toByte()); header.put('N'.code.toByte())
                header.putInt(sampleRate); header.putInt(1); header.putInt(16)
                out.write(header.array())

                var off = 0
                while (off < pcm.size) {
                    val n = minOf(4096, pcm.size - off)
                    out.write(pcm, off, n)
                    off += n
                }
                out.flush(); out.close()
                client.close(channel).await()
                Log.d(TAG, "streamed ${pcm.size / 1024} KB to watch")
            }
        } catch (e: Exception) {
            Log.e(TAG, "watch TTS stream failed: ${e.message}", e)
        }
    }

    override fun release() {
        buffer.reset()
    }
}
