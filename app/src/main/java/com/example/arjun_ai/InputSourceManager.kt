package com.example.arjun_ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object InputSourceManager {

    private const val TAG = "InputSource"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val SCO_START_TIMEOUT_MS = 4_000L

    enum class Source { BT_HEADSET_MIC, WATCH_MIC, PHONE_MIC }
    enum class TriggeredFrom { WATCH, PHONE_APP }

    data class State(
        val source: Source? = null,
        val recording: Boolean = false,
        val totalBytes: Long = 0L,
        val message: String = "Idle"
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var phoneRecordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private var scoReceiver: BroadcastReceiver? = null

    fun startSession(context: Context, trigger: TriggeredFrom) {
        if (isRecording.get()) {
            Log.d(TAG, "already recording, ignoring start")
            return
        }
        val source = decideSource(context, trigger)
        Log.d(TAG, "TRIGGER=$trigger -> SOURCE=$source")

        when (source) {
            Source.BT_HEADSET_MIC -> startPhoneRecording(context, source, useSco = true)
            Source.PHONE_MIC     -> startPhoneRecording(context, source, useSco = false)
            Source.WATCH_MIC     -> startWatchRecording(context)
        }
    }

    fun stopSession(context: Context) {
        Log.d(TAG, "stopSession; current source=${_state.value.source}")
        when (_state.value.source) {
            Source.BT_HEADSET_MIC, Source.PHONE_MIC -> stopPhoneRecording(context)
            Source.WATCH_MIC -> stopWatchRecording(context)
            null -> { /* idle */ }
        }
    }

    @SuppressLint("MissingPermission")
    private fun decideSource(context: Context, trigger: TriggeredFrom): Source {
        if (hasBtHeadsetMic(context)) {
            Log.d(TAG, "decideSource: BT headset with mic detected")
            return Source.BT_HEADSET_MIC
        }
        if (trigger == TriggeredFrom.WATCH) {
            Log.d(TAG, "decideSource: no BT mic -> WATCH_MIC")
            return Source.WATCH_MIC
        }
        Log.d(TAG, "decideSource: no BT mic -> PHONE_MIC")
        return Source.PHONE_MIC
    }

    private fun hasBtHeadsetMic(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = try { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }
        catch (e: Exception) { emptyArray() }
        val hasScoMic = inputs.any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        Log.d(TAG, "BT mic check: scoAvailable=${am.isBluetoothScoAvailableOffCall} hasScoInput=$hasScoMic")
        return hasScoMic && am.isBluetoothScoAvailableOffCall
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneRecording(context: Context, source: Source, useSco: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _state.value = State(source, false, 0L, "RECORD_AUDIO not granted")
            return
        }

        _state.value = State(source, false, 0L, "Starting…")
        isRecording.set(true)

        phoneRecordingJob = scope.launch {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                if (useSco) {
                    Log.d(TAG, "starting Bluetooth SCO…")
                    val scoReady = waitForScoConnected(context, am)
                    if (!scoReady) {
                        Log.w(TAG, "SCO didn't come up — recording will still try but mic may be phone")
                        _state.value = _state.value.copy(message = "SCO not ready, fallback to phone mic")
                    } else {
                        Log.d(TAG, "SCO connected")
                    }
                }

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf, 4096)
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _state.value = State(source, false, 0L, "AudioRecord init failed")
                    isRecording.set(false)
                    return@launch
                }

                // Now that AudioRecord is initialized, inspect what we actually got.
                logActualAudioFormat(recorder, source)

                val recordingsDir = File(context.filesDir,
                    PhoneAudioReceiverService.RECORDINGS_SUBDIR).apply { mkdirs() }
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val tag = when (source) {
                    Source.BT_HEADSET_MIC -> "btmic"
                    Source.PHONE_MIC -> "phonemic"
                    else -> "mic"
                }
                val pcmFile = File(recordingsDir, "session_${timestamp}_$tag.pcm")
                val wavFile = File(recordingsDir, "session_${timestamp}_$tag.wav")
                val out = FileOutputStream(pcmFile)

                recorder.startRecording()
                _state.value = State(source, true, 0L, "Recording from $source")
                AudioSink.onConnected()

                val buf = ByteArray(bufSize)
                var total = 0L
                while (isRecording.get() && isActive) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        total += n
                        AudioSink.onBytes(total)
                    }
                }
                recorder.stop()
                recorder.release()
                out.flush(); out.close()

                writeWav(pcmFile, wavFile, SAMPLE_RATE)
                pcmFile.delete()
                Log.d(TAG, "Saved ${wavFile.absolutePath} (${wavFile.length()} bytes)")
                AudioSink.onFinished(wavFile)
                _state.value = State(null, false, total, "Saved ${wavFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "phone recording failed", e)
                AudioSink.onError(e.message ?: "unknown")
                _state.value = State(null, false, 0L, "Error: ${e.message}")
            } finally {
                isRecording.set(false)
                if (useSco) {
                    try { am.stopBluetoothSco() } catch (_: Exception) {}
                    am.mode = AudioManager.MODE_NORMAL
                    am.isBluetoothScoOn = false
                    scoReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
                    scoReceiver = null
                }
            }
        }
    }

    private fun stopPhoneRecording(context: Context) { isRecording.set(false) }

    /**
     * Logs the actual sample rate and codec mode after AudioRecord is initialized.
     * For BT SCO, this tells us whether wideband (16 kHz, mSBC) or narrowband
     * (8 kHz, CVSD upsampled) was negotiated.
     */
    @SuppressLint("MissingPermission")
    private fun logActualAudioFormat(recorder: AudioRecord, source: Source) {
        val requested = SAMPLE_RATE
        val actualSampleRate = recorder.sampleRate
        val actualChannels = recorder.channelCount
        val actualEncoding = recorder.audioFormat
        val routedDevice = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) recorder.routedDevice else null
        } catch (e: Exception) { null }

        val routedSrName = routedDevice?.sampleRates?.joinToString(",") ?: "n/a"
        val routedType = routedDevice?.type?.toString() ?: "n/a"
        val routedProduct = routedDevice?.productName?.toString() ?: "n/a"

        // BT SCO codec heuristic:
        //  - mSBC (wideband): the BT chip natively delivers 16 kHz audio
        //  - CVSD (narrowband): chip delivers 8 kHz, Android upsamples to 16 kHz
        // Android doesn't expose the codec directly, but routedDevice.sampleRates
        // for a BLUETOOTH_SCO sink usually lists either [8000] (CVSD) or [16000] (mSBC).
        val codecGuess = when {
            source != Source.BT_HEADSET_MIC -> "n/a (not BT)"
            routedDevice?.sampleRates?.contains(16000) == true -> "WIDEBAND (mSBC, true 16 kHz)"
            routedDevice?.sampleRates?.contains(8000) == true -> "NARROWBAND (CVSD, 8 kHz upsampled)"
            else -> "unknown"
        }

        Log.d(TAG, "=== AUDIO FORMAT ===")
        Log.d(TAG, "  requested:    ${requested} Hz mono PCM16")
        Log.d(TAG, "  AudioRecord:  ${actualSampleRate} Hz ch=${actualChannels} fmt=${actualEncoding}")
        Log.d(TAG, "  routed type:  $routedType")
        Log.d(TAG, "  product:      $routedProduct")
        Log.d(TAG, "  device rates: [$routedSrName]")
        Log.d(TAG, "  BT codec:     $codecGuess")
        Log.d(TAG, "====================")
    }

    private suspend fun waitForScoConnected(context: Context, am: AudioManager): Boolean {
        return withTimeoutOrNull(SCO_START_TIMEOUT_MS) {
            val connected = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val state = i?.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    Log.d(TAG, "SCO state changed: $state")
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
            context.registerReceiver(
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isBluetoothScoOn = true
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            connected.await()
        } ?: false
    }

    private fun startWatchRecording(context: Context) {
        _state.value = State(Source.WATCH_MIC, true, 0L, "Asking watch to stream…")
        scope.launch { sendWatchCommand(context, "stream") }
    }

    private fun stopWatchRecording(context: Context) {
        scope.launch {
            sendWatchCommand(context, "stop")
            _state.value = State(null, false, 0L, "Watch stop sent")
        }
    }

    private suspend fun sendWatchCommand(context: Context, cmd: String) {
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: run {
                Log.w(TAG, "no watch to send '$cmd'"); return
            }
            val msg: MessageClient = Wearable.getMessageClient(context)
            msg.sendMessage(target.id, "/arjun/control", cmd.toByteArray()).await()
            Log.d(TAG, "sent '$cmd' to ${target.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "sendWatchCommand failed: ${e.message}", e)
        }
    }

    fun onWatchStreamFinished() {
        if (_state.value.source == Source.WATCH_MIC) {
            _state.value = State(null, false, 0L, "Watch session finished")
        }
    }

    private fun writeWav(pcm: File, wav: File, sampleRate: Int) {
        val pcmBytes = pcm.readBytes()
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2
        FileOutputStream(wav).use { fos ->
            val h = ByteArray(44)
            "RIFF".toByteArray().copyInto(h, 0)
            writeInt(h, 4, totalDataLen)
            "WAVE".toByteArray().copyInto(h, 8)
            "fmt ".toByteArray().copyInto(h, 12)
            writeInt(h, 16, 16); writeShort(h, 20, 1); writeShort(h, 22, 1)
            writeInt(h, 24, sampleRate); writeInt(h, 28, byteRate)
            writeShort(h, 32, 2); writeShort(h, 34, 16)
            "data".toByteArray().copyInto(h, 36)
            writeInt(h, 40, pcmBytes.size)
            fos.write(h); fos.write(pcmBytes)
        }
    }
    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte(); b[o+1] = ((v shr 8) and 0xff).toByte()
        b[o+2] = ((v shr 16) and 0xff).toByte(); b[o+3] = ((v shr 24) and 0xff).toByte()
    }
    private fun writeShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte(); b[o+1] = ((v shr 8) and 0xff).toByte()
    }
}