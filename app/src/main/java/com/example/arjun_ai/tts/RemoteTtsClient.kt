package com.example.arjun_ai.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.ajnaaitts.ipc.ITtsCallback
import com.example.ajnaaitts.ipc.ITtsService
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "RemoteTtsClient"

private const val SERVICE_PACKAGE = "com.example.ajnaaitts"
private const val SERVICE_ACTION = "com.example.ajnaaitts.ipc.BIND"
private const val BIND_TIMEOUT_MS = 30_000L

class TtsServiceNotInstalledException(
    message: String = "Ajna AI TTS app is not installed on this device.",
) : Exception(message)

/**
 * Ported from the POC. Refactored so the PCM player thread writes to a swappable
 * [TtsOutputSink] (phone AudioTrack via SCO/media, or watch streaming) instead of
 * a hard-coded local AudioTrack — this is what makes TTS output mirror the
 * conversation's input device.
 *
 * Android-TTS voices play through the system engine locally (sink not used), but
 * honour [setPreferredUsage] so a BT session routes them to SCO.
 */
class RemoteTtsClient(
    private val onAllFinished: () -> Unit,
) {
    // ── Remote service ───────────────────────────────────────────────────
    private var serviceBinder: ITtsService? = null
    private var serviceConnection: ServiceConnection? = null
    private var appContext: Context? = null
    @Volatile private var serviceBound = false

    // ── Android TTS ──────────────────────────────────────────────────────
    private var textToSpeech: TextToSpeech? = null
    private var androidTtsReady = false
    @Volatile private var preferredUsage = AudioAttributes.USAGE_MEDIA

    // ── Output sink (set per conversation by AgentSession) ────────────────
    @Volatile var outputSink: TtsOutputSink? = null

    // ── PCM playback queue + player thread ───────────────────────────────
    data class PcmChunk(
        val pcmData: ByteArray,
        val sampleRate: Int,
        val utteranceId: String,
        val isLast: Boolean = false,
    )

    private val POISON_PILL = PcmChunk(ByteArray(0), 0, "", false)
    private val pcmQueue = LinkedBlockingQueue<PcmChunk>()
    private var playerThread: Thread? = null

    // ── Shared state ─────────────────────────────────────────────────────
    val pendingUtterances = AtomicInteger(0)

    private val isCancelled = AtomicBoolean(false)   // terminal (destroy)
    private val cancelCurrent = AtomicBoolean(false) // transient (stop / barge-in)

    private var activeVoice: VoiceConfig.Voice? = null

    private val deathRecipient = IBinder.DeathRecipient {
        Log.e(TAG, "TTS service process died")
        serviceBound = false
        serviceBinder = null
    }

    // =====================================================================
    // Initialization
    // =====================================================================

    fun initialize(context: Context, voice: VoiceConfig.Voice): Boolean {
        isCancelled.set(false)
        cancelCurrent.set(false)
        activeVoice = voice

        return if (voice.engine == VoiceConfig.EngineType.ANDROID_TTS) {
            initAndroidTts(context)
        } else {
            val ok = initRemoteService(context, voice)
            if (ok) startPlayerThread()
            ok
        }
    }

    /** Route Android-TTS / future audio to this usage (e.g. VOICE_COMMUNICATION for SCO). */
    fun setPreferredUsage(usage: Int) {
        preferredUsage = usage
        try {
            textToSpeech?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {}
    }

    private fun initAndroidTts(context: Context): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        val voice = activeVoice

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ttsEngine = textToSpeech!!
                    if (voice?.premiumHindi == true) {
                        val hindiVoices = (ttsEngine.voices ?: emptySet())
                            .filter { it.locale.language == "hi" }
                            .sortedWith(
                                compareByDescending<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                                    .thenBy { it.name }
                            )
                        if (hindiVoices.isNotEmpty()) {
                            ttsEngine.voice = hindiVoices.first()
                            Log.d(TAG, "Android TTS: selected ${hindiVoices.first().name}")
                        } else {
                            ttsEngine.language = Locale("hi", "IN")
                        }
                    } else {
                        ttsEngine.language = Locale.US
                    }
                    androidTtsReady = true
                    installAndroidTtsListener()
                    success = true
                    Log.d(TAG, "Android TTS ready")
                } else {
                    Log.e(TAG, "Android TTS init failed")
                }
                latch.countDown()
            }
        }

        latch.await(10_000, TimeUnit.MILLISECONDS)
        return success
    }

    private fun installAndroidTtsListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { decAndMaybeFinish() }
            @Deprecated("deprecated in API level 21")
            override fun onError(utteranceId: String?) { decAndMaybeFinish() }
        })
    }

    private fun decAndMaybeFinish() {
        if (pendingUtterances.decrementAndGet() <= 0) {
            pendingUtterances.set(0)
            onAllFinished()
        }
    }

    private fun initRemoteService(context: Context, voice: VoiceConfig.Voice): Boolean {
        val ctx = context.applicationContext
        appContext = ctx

        try {
            ctx.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw TtsServiceNotInstalledException()
        }

        val bindLatch = CountDownLatch(1)
        val bindResult = AtomicReference<ITtsService?>(null)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val proxy = ITtsService.Stub.asInterface(binder)
                try { binder.linkToDeath(deathRecipient, 0) } catch (_: RemoteException) {}
                bindResult.set(proxy)
                bindLatch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                serviceBound = false; serviceBinder = null
            }
            override fun onBindingDied(name: ComponentName) {
                serviceBound = false; serviceBinder = null; bindLatch.countDown()
            }
            override fun onNullBinding(name: ComponentName) { bindLatch.countDown() }
        }

        serviceConnection = conn
        val intent = Intent(SERVICE_ACTION).apply { setPackage(SERVICE_PACKAGE) }
        val started = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)

        if (!started) {
            ctx.unbindService(conn); serviceConnection = null
            throw IllegalStateException("bindService() returned false for TTS service")
        }

        if (!bindLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out waiting for TTS service bind")
        }

        val proxy = bindResult.get()
            ?: throw IllegalStateException("TTS service bound with null binder")

        serviceBinder = proxy
        serviceBound = true

        val loadOk = try { proxy.loadVoice(voice.id) }
        catch (e: RemoteException) { Log.e(TAG, "Remote loadVoice failed", e); false }

        if (!loadOk) {
            unbindInternal()
            throw IllegalStateException("TTS service failed to load voice: ${voice.displayName}.")
        }

        Log.d(TAG, "Remote TTS ready: ${voice.displayName}")
        return true
    }

    // =====================================================================
    // Player thread — plays PCM chunks SEQUENTIALLY via the active sink
    // =====================================================================

    private fun startPlayerThread() {
        playerThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d(TAG, "Player thread started")
            try {
                while (!isCancelled.get()) {
                    val chunk = pcmQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (chunk.pcmData.isEmpty()) break  // poison pill
                    if (isCancelled.get()) break

                    // utterance done-marker (1-byte payload)
                    if (chunk.isLast && chunk.pcmData.size == 1) {
                        try { outputSink?.endUtterance() } catch (_: Exception) {}
                        decAndMaybeFinish()
                        continue
                    }

                    cancelCurrent.set(false)
                    try {
                        outputSink?.write(chunk.pcmData, chunk.sampleRate)
                    } catch (e: Exception) {
                        Log.e(TAG, "sink write failed", e)
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Player thread interrupted")
            } finally {
                try { outputSink?.release() } catch (_: Exception) {}
                Log.d(TAG, "Player thread finished")
            }
        }.also {
            it.name = "TTS-Player"
            it.isDaemon = true
            it.start()
        }
    }

    // =====================================================================
    // Public API
    // =====================================================================

    fun speakBatch(text: String, utteranceId: String) {
        if (isCancelled.get()) return
        val voice = activeVoice ?: return
        if (voice.engine == VoiceConfig.EngineType.ANDROID_TTS) {
            if (!androidTtsReady) return
            pendingUtterances.incrementAndGet()
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, usageToStream(preferredUsage))
            }
            textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        } else {
            speakViaRemoteService(text, utteranceId)
        }
    }

    fun speakImmediate(text: String, utteranceId: String) = speakBatch(text, utteranceId)

    private fun usageToStream(usage: Int): Int = when (usage) {
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> android.media.AudioManager.STREAM_VOICE_CALL
        else -> android.media.AudioManager.STREAM_MUSIC
    }

    private fun speakViaRemoteService(text: String, utteranceId: String) {
        val proxy = serviceBinder ?: run { Log.w(TAG, "speakBatch: service not bound"); return }
        pendingUtterances.incrementAndGet()

        val cb = object : ITtsCallback.Stub() {
            override fun onAudioChunk(pcmData: ByteArray, sampleRate: Int, audioDurationMs: Long) {
                if (isCancelled.get()) return
                pcmQueue.offer(PcmChunk(pcmData, sampleRate, utteranceId))
            }
            override fun onComplete() {
                pcmQueue.offer(PcmChunk(ByteArray(1), 0, utteranceId, isLast = true))
            }
            override fun onError(message: String) {
                Log.e(TAG, "speakChunk error: $message")
                decAndMaybeFinish()
            }
        }

        try {
            proxy.speakChunk(text, cb)
        } catch (e: RemoteException) {
            Log.e(TAG, "speakChunk RemoteException", e)
            decAndMaybeFinish()
        }
    }

    // =====================================================================
    // Stop / Destroy
    // =====================================================================

    fun stop() {
        // Cancel current utterance(s); keep the client usable for the next turn.
        cancelCurrent.set(true)
        pendingUtterances.set(0)
        if (androidTtsReady) textToSpeech?.stop()
        try { serviceBinder?.cancelGeneration() } catch (_: RemoteException) {}
        pcmQueue.clear()
        try { outputSink?.bargeIn() } catch (_: Exception) {}
    }

    fun destroy() {
        isCancelled.set(true)
        cancelCurrent.set(true)
        pendingUtterances.set(0)
        try { serviceBinder?.cancelGeneration() } catch (_: RemoteException) {}

        if (androidTtsReady) { textToSpeech?.stop(); textToSpeech?.shutdown() }
        textToSpeech = null; androidTtsReady = false

        try { serviceBinder?.unloadModel() } catch (_: RemoteException) {}
        unbindInternal()

        pcmQueue.offer(POISON_PILL)
        playerThread?.interrupt()
        playerThread = null
        pcmQueue.clear()
        try { outputSink?.release() } catch (_: Exception) {}
        outputSink = null
        activeVoice = null
        Log.d(TAG, "RemoteTtsClient destroyed")
    }

    private fun unbindInternal() {
        serviceBound = false
        try { serviceBinder?.asBinder()?.unlinkToDeath(deathRecipient, 0) } catch (_: Exception) {}
        val ctx = appContext; val conn = serviceConnection
        if (ctx != null && conn != null) { try { ctx.unbindService(conn) } catch (_: Exception) {} }
        serviceConnection = null; serviceBinder = null; appContext = null
    }
}
