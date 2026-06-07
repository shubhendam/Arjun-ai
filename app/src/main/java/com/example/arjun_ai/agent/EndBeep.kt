package com.example.arjun_ai.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.arjun_ai.tts.WatchTtsSink
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short "your turn" beep played after the model finishes speaking, so a hands-free
 * user knows Arjun has stopped and they can talk. Routed to the same device as the
 * conversation (SCO headset / phone / watch).
 */
object EndBeep {

    private const val TAG = "EndBeep"
    private const val SR = 16_000
    private const val FREQ = 880.0
    private const val DURATION_MS = 140

    // Raw 16-bit mono PCM (no WAV header) — generated once.
    private val pcm: ByteArray by lazy { generate() }

    /** Blocking: plays the beep and returns once it has finished sounding. */
    fun play(context: Context, source: ConversationAudioIO.Source) {
        try {
            when (source) {
                ConversationAudioIO.Source.WATCH_MIC -> {
                    val sink = WatchTtsSink(context)
                    sink.write(pcm, SR)
                    sink.endUtterance()
                    sink.release()
                }
                ConversationAudioIO.Source.BT_HEADSET_MIC ->
                    playLocal(AudioAttributes.USAGE_VOICE_COMMUNICATION) // out the SCO headset
                ConversationAudioIO.Source.PHONE_MIC ->
                    playLocal(AudioAttributes.USAGE_MEDIA)
            }
        } catch (e: Exception) {
            Log.w(TAG, "beep failed: ${e.message}")
        }
    }

    private fun playLocal(usage: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        try { Thread.sleep((DURATION_MS + 60).toLong()) } catch (_: InterruptedException) {}
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    private fun generate(): ByteArray {
        val n = SR * DURATION_MS / 1000
        val fade = 200 // samples of linear fade in/out
        val amp = 0.22 * Short.MAX_VALUE
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            var g = 1.0
            if (i < fade) g = i.toDouble() / fade
            else if (i > n - fade) g = (n - i).toDouble() / fade
            val s = (amp * g * sin(2.0 * PI * FREQ * i / SR)).toInt().toShort()
            out[2 * i] = (s.toInt() and 0xFF).toByte()
            out[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
