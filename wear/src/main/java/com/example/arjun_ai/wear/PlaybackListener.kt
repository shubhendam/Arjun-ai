package com.example.arjun_ai.wear

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives PCM streamed from the phone on /arjun/tts and plays it via AudioTrack.
 * Reads a 16-byte header first: "ARJN" + sampleRate(LE) + channels(LE) + bitsPerSample(LE).
 */
class PlaybackListener : WearableListenerService() {

    companion object { private const val TAG = "ArjunPlayback" }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != "/arjun/tts") return
        Log.d(TAG, "playback channel opened")
        scope.launch {
            val client = Wearable.getChannelClient(applicationContext)
            try {
                val input: InputStream = client.getInputStream(channel).await()
                val headerBytes = readExact(input, 16) ?: run {
                    Log.e(TAG, "short header"); return@launch
                }
                if (String(headerBytes, 0, 4) != "ARJN") {
                    Log.e(TAG, "bad magic"); return@launch
                }
                val hb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val sampleRate = hb.getInt(4)
                val channelsCount = hb.getInt(8)
                val bits = hb.getInt(12)
                Log.d(TAG, "stream: $sampleRate Hz, $channelsCount ch, $bits-bit")

                val channelCfg = if (channelsCount == 1)
                    AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val encoding = if (bits == 16) AudioFormat.ENCODING_PCM_16BIT
                else AudioFormat.ENCODING_PCM_8BIT

                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelCfg, encoding)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelCfg)
                            .setEncoding(encoding)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()

                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    track.write(buf, 0, n)
                }
                // drain
                try { track.stop() } catch (_: Exception) {}
                track.release()
                Log.d(TAG, "playback finished")
            } catch (e: Exception) {
                Log.e(TAG, "playback failed", e)
            }
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r <= 0) return null
            read += r
        }
        return out
    }
}