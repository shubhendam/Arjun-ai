package com.example.arjun_ai.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.arjun_ai.ConversationAudioBus
import com.example.arjun_ai.tts.PhoneAudioTrackSink
import com.example.arjun_ai.tts.TtsOutputSink
import com.example.arjun_ai.tts.WatchTtsSink
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live conversation audio I/O. Picks the input device with the Phase-1 priority
 * (BT mic > watch mic > phone mic), streams 16 kHz PCM frames to a callback for
 * the VAD, and builds the matching output sink so TTS mirrors the input device.
 *
 * One conversation at a time → singleton object.
 */
object ConversationAudioIO {

    private const val TAG = "ConvAudioIO"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val SCO_TIMEOUT_MS = 4_000L

    enum class Source { BT_HEADSET_MIC, WATCH_MIC, PHONE_MIC }
    enum class Trigger { WATCH, PHONE_APP }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val capturing = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var usingSco = false
    @Volatile private var source: Source? = null

    val currentSource: Source? get() = source

    /**
     * Decide source by Phase-1 priority and begin streaming PCM frames to [onPcm].
     * Returns the chosen source, or null if it couldn't start (no mic permission).
     */
    @SuppressLint("MissingPermission")
    fun startCapture(context: Context, trigger: Trigger, onPcm: (ShortArray) -> Unit): Source? {
        if (capturing.get()) {
            Log.d(TAG, "already capturing")
            return source
        }
        val src = decideSource(context, trigger)
        source = src
        Log.d(TAG, "TRIGGER=$trigger -> SOURCE=$src")

        return when (src) {
            Source.WATCH_MIC -> { startWatchCapture(context, onPcm); src }
            Source.BT_HEADSET_MIC -> if (startPhoneCapture(context, useSco = true, onPcm)) src else null
            Source.PHONE_MIC -> if (startPhoneCapture(context, useSco = false, onPcm)) src else null
        }
    }

    fun stopCapture(context: Context) {
        capturing.set(false)
        captureJob?.cancel()
        captureJob = null
        if (source == Source.WATCH_MIC) {
            ConversationAudioBus.end()
            scope.launch { sendWatchControl(context, "stop") }
        }
        if (usingSco) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { am.stopBluetoothSco() } catch (_: Exception) {}
            am.mode = AudioManager.MODE_NORMAL
            am.isBluetoothScoOn = false
            scoReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
            scoReceiver = null
            usingSco = false
        }
        source = null
    }

    /** Build the TTS output sink that mirrors the captured input device. */
    fun buildOutputSink(context: Context, src: Source): TtsOutputSink = when (src) {
        Source.BT_HEADSET_MIC ->
            PhoneAudioTrackSink(AudioAttributes.USAGE_VOICE_COMMUNICATION) // out the SCO headset
        Source.PHONE_MIC ->
            PhoneAudioTrackSink(AudioAttributes.USAGE_MEDIA)               // earbuds / speaker
        Source.WATCH_MIC ->
            WatchTtsSink(context)                                          // stream to watch
    }

    /** Preferred Android-TTS usage matching the device (so Android TTS routes to SCO on BT). */
    fun preferredTtsUsage(src: Source): Int = when (src) {
        Source.BT_HEADSET_MIC -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        else -> AudioAttributes.USAGE_MEDIA
    }

    // ========================================================================
    // Source decision (Phase-1 priority)
    // ========================================================================

    @SuppressLint("MissingPermission")
    private fun decideSource(context: Context, trigger: Trigger): Source {
        if (hasBtHeadsetMic(context)) return Source.BT_HEADSET_MIC
        return if (trigger == Trigger.WATCH) Source.WATCH_MIC else Source.PHONE_MIC
    }

    private fun hasBtHeadsetMic(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = try { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }
        catch (e: Exception) { emptyArray() }
        val hasScoMic = inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        return hasScoMic && am.isBluetoothScoAvailableOffCall
    }

    // ========================================================================
    // Phone / BT capture (AudioRecord on the phone)
    // ========================================================================

    @SuppressLint("MissingPermission")
    private fun startPhoneCapture(context: Context, useSco: Boolean, onPcm: (ShortArray) -> Unit): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            return false
        }
        capturing.set(true)
        captureJob = scope.launch {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                if (useSco) {
                    usingSco = true
                    val ok = waitForScoConnected(context, am)
                    Log.d(TAG, if (ok) "SCO connected" else "SCO not ready (continuing)")
                }
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, FORMAT)
                val bufSize = maxOf(minBuf, 4096)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL_IN, FORMAT, bufSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed")
                    capturing.set(false)
                    return@launch
                }
                recorder.startRecording()
                val buf = ShortArray(bufSize / 2)
                while (capturing.get()) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) onPcm(buf.copyOf(n))
                }
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
            } catch (e: Exception) {
                Log.e(TAG, "phone capture failed", e)
            } finally {
                capturing.set(false)
            }
        }
        return true
    }

    private suspend fun waitForScoConnected(context: Context, am: AudioManager): Boolean {
        return withTimeoutOrNull(SCO_TIMEOUT_MS) {
            val connected = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val state = i?.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED ->
                            if (!connected.isCompleted) connected.complete(true)
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                        AudioManager.SCO_AUDIO_STATE_ERROR ->
                            if (!connected.isCompleted) connected.complete(false)
                    }
                }
            }
            scoReceiver = receiver
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isBluetoothScoOn = true
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            connected.await()
        } ?: false
    }

    // ========================================================================
    // Watch capture (watch streams mic over /arjun/audio → bus → onPcm)
    // ========================================================================

    private fun startWatchCapture(context: Context, onPcm: (ShortArray) -> Unit) {
        capturing.set(true)
        ConversationAudioBus.begin(onPcm)
        scope.launch { sendWatchControl(context, "stream") }
    }

    private suspend fun sendWatchControl(context: Context, cmd: String) {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: run {
                Log.w(TAG, "no watch to send '$cmd'"); return
            }
            Wearable.getMessageClient(context)
                .sendMessage(target.id, "/arjun/control", cmd.toByteArray()).await()
            Log.d(TAG, "sent '$cmd' to ${target.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "sendWatchControl failed: ${e.message}", e)
        }
    }
}
