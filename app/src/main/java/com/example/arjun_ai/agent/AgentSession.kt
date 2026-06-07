package com.example.arjun_ai.agent

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import com.example.arjun_ai.ConversationService
import com.example.arjun_ai.tts.RemoteTtsClient
import com.example.arjun_ai.tts.TtsServiceNotInstalledException
import com.example.arjun_ai.tts.VoiceConfig
import com.example.arjun_ai.vad.VadFrameSize
import com.example.arjun_ai.vad.VadMode
import com.example.arjun_ai.vad.VadSampleRate
import com.example.arjun_ai.vad.VadSilero
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AgentSession"

const val DEFAULT_ARJUN_SYSTEM_PROMPT =
    "You are a helpful AI assistant named Arjun. " +
            "Be helpful, don't use any emoji, be concise, and friendly. " +
            "Use play_song, pause_song, next_song or previous_song to control music. " +
            "If the user asks to call, phone, ring or dial someone, use the make_call tool with " +
            "the person's name, present the matches, and confirm before calling. " +
            "IMPORTANT: If the user says anything that means they want to stop, end, finish, " +
            "or are done with the conversation — for example 'stop', 'stop conversation', " +
            "'stop talking', 'that's all', 'enough', 'bye', 'goodbye', 'end', 'done', 'exit' — " +
            "you MUST call the stop tool immediately and MUST NOT reply with any text. " +
            "Use the appropriate tool immediately without asking for confirmation (calls still need confirmation). " +
            "For all other questions, answer directly."

// Audio / VAD constants (match the POC agent mode)
private const val SAMPLE_RATE = 16_000
private const val VAD_FRAME_SIZE = 512
private const val BYTES_PER_SAMPLE = 2
private const val SPEECH_DURATION_MS = 150
private const val SILENCE_DURATION_MS = 1200
private const val PRE_SPEECH_FRAMES = 6

enum class AgentState { IDLE, LOADING, READY, LISTENING, PROCESSING, SPEAKING, ERROR }

data class AgentUiState(
    val state: AgentState = AgentState.IDLE,
    val systemPrompt: String = DEFAULT_ARJUN_SYSTEM_PROMPT,
    val errorMessage: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val totalTokensUsed: Int = 0,
    val selectedVoice: VoiceConfig.Voice = VoiceConfig.DEFAULT_VOICE,
)

/**
 * Singleton orchestrator for the Gemma agent conversation. Lives outside any
 * Activity/ViewModel because the watch trigger arrives via a WearableListenerService
 * that can run with the app backgrounded. Owns the engine, VAD, TTS and the
 * capture/turn pipeline (ported from the POC's AgentModeViewModel, minus glasses
 * routing and vision).
 */
object AgentSession {

    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val engine = AgentModelEngine()
    private val tools = AgentTools()
    private var vad: VadSilero? = null
    private var tts: RemoteTtsClient? = null

    @Volatile private var appContext: Context? = null

    val isReady: Boolean get() = _ui.value.state.let {
        it == AgentState.READY || it == AgentState.LISTENING ||
                it == AgentState.PROCESSING || it == AgentState.SPEAKING
    }
    @Volatile var isActive = false
        private set

    // --- VAD accumulators ---
    private val accumulator = mutableListOf<Short>()
    private val ringBuffer = ArrayDeque<ShortArray>(PRE_SPEECH_FRAMES + 1)
    private val speechBuffer = mutableListOf<Short>()
    private var wasSpeaking = false

    @Volatile private var inConversation = false
    @Volatile private var stopRequested = false

    // Bumped on every reset/new turn. In-flight pipeline tails no-op if it changed.
    @Volatile private var generation = 0

    // ==========================================================================
    // Tool callback
    // ==========================================================================
    private val toolCallback = object : AgentToolCallback {
        override fun onMediaControl(action: String) {
            val keyCode = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            try {
                val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (e: Exception) {
                Log.e(TAG, "media key dispatch failed: ${e.message}")
            }
        }

        override fun onStop() {
            Log.d(TAG, "stop tool -> end conversation after this turn")
            stopRequested = true
            inConversation = false
        }

        override fun onMakeCall(matches: List<ContactMatch>, originalQuery: String): String {
            return if (matches.isEmpty()) "No contacts found matching '$originalQuery'."
            else "Found ${matches.size} matching contact(s)."
        }

        override fun onConfirmCall(contactName: String): String {
            val contact = ContactsHelper.getContactByName(contactName)
                ?: ContactsHelper.findMatches(contactName, limit = 1).firstOrNull()?.contact
                ?: return "Could not find contact '$contactName'."
            return try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${contact.phoneNumber}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext?.startActivity(dialIntent)
                "Opening dialer for ${contact.displayName}."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open dialer", e)
                "Failed to open dialer: ${e.message}"
            }
        }
    }

    // ==========================================================================
    // Public config
    // ==========================================================================
    fun updateSystemPrompt(prompt: String) = _ui.update { it.copy(systemPrompt = prompt) }
    fun selectVoice(voice: VoiceConfig.Voice) = _ui.update { it.copy(selectedVoice = voice) }

    // ==========================================================================
    // Load / unload
    // ==========================================================================
    fun loadModel(context: Context) {
        if (engine.isLoaded) return
        appContext = context.applicationContext
        _ui.update { it.copy(state = AgentState.LOADING, errorMessage = null) }

        scope.launch {
            try {
                if (!isAgentModelPresent()) {
                    _ui.update { it.copy(state = AgentState.ERROR, errorMessage =
                        "Model not found at $AGENT_MODEL_PATH.\nPush gemma-4-E4B-it.litertlm to /sdcard/Download/.") }
                    return@launch
                }

                val voice = _ui.value.selectedVoice

                tools.callback = toolCallback
                ContactsHelper.loadContacts(context)
                Log.d(TAG, "Contacts loaded: ${ContactsHelper.isLoaded}")

                Log.d(TAG, "Loading Gemma 4 E4B with agent tools...")
                engine.load(context, _ui.value.systemPrompt, listOf(tools))

                Log.d(TAG, "Warming up with welcome.wav...")
                engine.warmup(context)

                vad = VadSilero(
                    context = context, sampleRate = VadSampleRate.SAMPLE_RATE_16K,
                    frameSize = VadFrameSize.FRAME_SIZE_512, mode = VadMode.AGGRESSIVE,
                    speechDurationMs = SPEECH_DURATION_MS, silenceDurationMs = SILENCE_DURATION_MS,
                )
                Log.d(TAG, "Silero VAD initialized")

                try {
                    val client = RemoteTtsClient(onAllFinished = { onAllTtsFinished() })
                    if (!client.initialize(context, voice)) throw IllegalStateException("TTS init failed")
                    tts = client
                } catch (e: TtsServiceNotInstalledException) {
                    cleanupAfterLoadFailure()
                    _ui.update { it.copy(state = AgentState.ERROR, errorMessage =
                        "Ajna AI TTS app is not installed.\nInstall it for Kokoro/Piper voices, or select Android TTS.") }
                    return@launch
                } catch (e: Exception) {
                    cleanupAfterLoadFailure()
                    _ui.update { it.copy(state = AgentState.ERROR, errorMessage = "TTS load failed: ${e.message}") }
                    return@launch
                }

                _ui.update { it.copy(state = AgentState.READY) }
                Log.d(TAG, "Arjun READY — Gemma 4 + VAD + TTS (${voice.displayName})")
            } catch (e: Exception) {
                Log.e(TAG, "Load failed", e)
                _ui.update { it.copy(state = AgentState.ERROR, errorMessage = e.message ?: "Failed to load") }
            }
        }
    }

    private fun cleanupAfterLoadFailure() {
        try { engine.unload() } catch (_: Exception) {}
        try { vad?.close() } catch (_: Exception) {}
        vad = null
    }

    fun unloadModel(context: Context) {
        stopConversation(context)
        scope.launch {
            try { vad?.close() } catch (_: Exception) {}
            vad = null
            tts?.destroy(); tts = null
            if (engine.isLoaded) engine.unload()
            tools.callback = null
            _ui.value = AgentUiState()
            Log.d(TAG, "Arjun unloaded")
        }
    }

    // ==========================================================================
    // Conversation lifecycle
    // ==========================================================================
    fun startConversation(context: Context, trigger: ConversationAudioIO.Trigger) {
        if (!isReady) { Log.w(TAG, "startConversation but not ready"); return }
        if (isActive) { Log.d(TAG, "already active"); return }
        appContext = context.applicationContext
        Log.d(TAG, "startConversation trigger=$trigger")

        hardReset()
        isActive = true
        inConversation = true
        stopRequested = false

        // Keep mic capture alive when backgrounded.
        try { ConversationService.start(context) } catch (e: Exception) { Log.w(TAG, "fg service start failed: ${e.message}") }

        val src = ConversationAudioIO.startCapture(context, trigger) { shorts -> onCaptureFrames(shorts) }
        if (src == null) {
            _ui.update { it.copy(state = AgentState.ERROR, errorMessage = "Mic permission/capture failed") }
            stopConversation(context)
            return
        }
        // Output mirrors input.
        tts?.outputSink = ConversationAudioIO.buildOutputSink(context, src)
        tts?.setPreferredUsage(ConversationAudioIO.preferredTtsUsage(src))

        _ui.update { it.copy(state = AgentState.LISTENING) }
    }

    fun stopConversation(context: Context) {
        if (!isActive && _ui.value.state == AgentState.READY) return
        Log.d(TAG, "stopConversation")
        generation++
        isActive = false
        inConversation = false
        try { engine.stopGeneration() } catch (_: Exception) {}
        tts?.stop()
        try { ConversationAudioIO.stopCapture(context) } catch (_: Exception) {}
        try { ConversationService.stop(context) } catch (_: Exception) {}
        resetVadState()
        if (_ui.value.state != AgentState.IDLE && _ui.value.state != AgentState.ERROR) {
            _ui.update { it.copy(state = AgentState.READY, isGenerating = false) }
        }
    }

    private fun hardReset() {
        generation++
        try { engine.stopGeneration() } catch (_: Exception) {}
        tts?.stop()
        resetVadState()
    }

    // ==========================================================================
    // Capture -> VAD -> turn
    // ==========================================================================
    private fun onCaptureFrames(shorts: ShortArray) {
        // Only listen when LISTENING — avoids self-triggering on our own TTS (SCO echo).
        if (_ui.value.state != AgentState.LISTENING) return
        feedShortsToVad(shorts)
    }

    private fun feedShortsToVad(shorts: ShortArray) {
        val v = vad ?: return
        synchronized(accumulator) {
            for (s in shorts) accumulator.add(s)
            while (accumulator.size >= VAD_FRAME_SIZE) {
                val frame = ShortArray(VAD_FRAME_SIZE)
                for (i in 0 until VAD_FRAME_SIZE) frame[i] = accumulator.removeAt(0)
                processVadFrame(v, frame)
                if (_ui.value.state != AgentState.LISTENING) break
            }
        }
    }

    private fun processVadFrame(v: VadSilero, frame: ShortArray) {
        val isSpeech = v.isSpeech(frame)
        if (isSpeech) {
            if (!wasSpeaking) {
                wasSpeaking = true
                for (preFrame in ringBuffer) for (s in preFrame) speechBuffer.add(s)
                ringBuffer.clear()
            }
            for (s in frame) speechBuffer.add(s)
        } else {
            if (wasSpeaking) {
                wasSpeaking = false
                if (speechBuffer.isNotEmpty()) {
                    val audioBytes = buildWavBytesInMemory(speechBuffer)
                    speechBuffer.clear()
                    ringBuffer.clear()
                    synchronized(accumulator) { accumulator.clear() }
                    vad?.reset()
                    _ui.update { it.copy(state = AgentState.PROCESSING) }
                    launchTurn(audioBytes)
                }
            } else {
                ringBuffer.addLast(frame.copyOf())
                if (ringBuffer.size > PRE_SPEECH_FRAMES) ringBuffer.removeFirst()
            }
        }
    }

    // ==========================================================================
    // One agent turn
    // ==========================================================================
    private fun launchTurn(audioBytes: ByteArray) {
        scope.launch {
            val gen = generation
            val startTime = System.currentTimeMillis()

            val userMsg = ChatMessage(role = Role.USER, text = "🎤 (voice)")
            val modelMsg = ChatMessage(role = Role.MODEL, text = "", isStreaming = true)
            _ui.update {
                it.copy(messages = it.messages + userMsg + modelMsg, isGenerating = true, state = AgentState.SPEAKING)
            }

            val firstTokenTime = longArrayOf(-1L)
            val tokenCount = intArrayOf(0)
            val displayed = StringBuilder()
            val ttsBatch = StringBuilder()
            val utteranceCounter = intArrayOf(0)
            val cut = java.util.concurrent.atomic.AtomicBoolean(false)
            val done = CompletableDeferred<Unit>()

            engine.sendMessage(
                text = "",
                audioClips = listOf(audioBytes),
                onToken = { token ->
                    if (gen != generation) return@sendMessage
                    // stop tool fired — cut generation, speak nothing.
                    if (stopRequested && !cut.get()) {
                        cut.set(true)
                        engine.stopGeneration()
                        return@sendMessage
                    }
                    if (cut.get()) return@sendMessage

                    if (firstTokenTime[0] < 0) firstTokenTime[0] = System.currentTimeMillis()
                    tokenCount[0]++
                    displayed.append(token)
                    _ui.update { st ->
                        val msgs = st.messages.toMutableList()
                        if (msgs.isNotEmpty()) msgs[msgs.size - 1] = msgs.last().copy(text = displayed.toString())
                        st.copy(messages = msgs)
                    }
                    ttsBatch.append(token)
                    flushTtsBatchIfReady(ttsBatch, utteranceCounter)
                },
                onComplete = {
                    if (gen == generation && !cut.get()) {
                        val rem = ttsBatch.toString().trim()
                        if (rem.length >= 2) {
                            tts?.speakBatch(rem, "arjun_final_${utteranceCounter[0]}")
                            ttsBatch.clear()
                        }
                    }
                    if (!done.isCompleted) done.complete(Unit)
                },
                onFailure = { err ->
                    Log.e(TAG, "inference error: $err")
                    if (!cut.get()) {
                        _ui.update { st ->
                            val msgs = st.messages.toMutableList()
                            if (msgs.isNotEmpty()) msgs[msgs.size - 1] = msgs.last().copy(text = "Error: $err", isStreaming = false)
                            st.copy(messages = msgs, isGenerating = false)
                        }
                    }
                    if (!done.isCompleted) done.complete(Unit)
                },
            )

            done.await()
            if (gen != generation) return@launch

            val stats = InferenceStats(
                timeToFirstTokenMs = if (firstTokenTime[0] > 0) firstTokenTime[0] - startTime else 0L,
                totalTimeMs = System.currentTimeMillis() - startTime,
                tokenCount = tokenCount[0],
            )
            _ui.update { st ->
                val msgs = st.messages.toMutableList()
                if (msgs.isNotEmpty()) msgs[msgs.size - 1] = msgs.last().copy(isStreaming = false, stats = stats)
                st.copy(messages = msgs, isGenerating = false, totalTokensUsed = st.totalTokensUsed + tokenCount[0])
            }

            // stop tool ended the conversation this turn.
            if (stopRequested) {
                appContext?.let { stopConversation(it) }
                return@launch
            }

            // If nothing was queued for TTS, resume listening immediately; otherwise
            // the TTS onAllFinished callback will.
            val client = tts
            if (client == null || client.pendingUtterances.get() <= 0) onAllTtsFinished()
        }
    }

    private fun flushTtsBatchIfReady(buffer: StringBuilder, counter: IntArray) {
        val batch = buffer.toString()
        val sentenceEndCount = batch.count { it == '.' || it == '?' || it == '!' || it == '।' }
        val wordCount = batch.trim().split("\\s+".toRegex()).size
        val shouldFlush = sentenceEndCount >= 2 || wordCount >= 20 || (sentenceEndCount >= 1 && wordCount >= 12)
        if (shouldFlush) {
            val s = batch.trim()
            if (s.length >= 10) {
                tts?.speakBatch(s, "arjun_${counter[0]++}")
                buffer.clear()
            }
        }
    }

    private fun onAllTtsFinished() {
        val st = _ui.value.state
        if (st == AgentState.IDLE || st == AgentState.ERROR) return
        resetVadState()
        if (inConversation && isActive) {
            _ui.update { it.copy(state = AgentState.LISTENING) }
        } else {
            appContext?.let { stopConversation(it) }
        }
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================
    private fun resetVadState() {
        wasSpeaking = false
        speechBuffer.clear()
        ringBuffer.clear()
        synchronized(accumulator) { accumulator.clear() }
        vad?.reset()
    }

    private fun buildWavBytesInMemory(samples: List<Short>): ByteArray {
        val numSamples = samples.size
        val pcmDataSize = numSamples * BYTES_PER_SAMPLE
        val buffer = ByteBuffer.allocate(44 + pcmDataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('R'.code.toByte()); buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte()); buffer.put('F'.code.toByte())
        buffer.putInt(36 + pcmDataSize)
        buffer.put('W'.code.toByte()); buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte()); buffer.put('E'.code.toByte())
        buffer.put('f'.code.toByte()); buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte()); buffer.put(' '.code.toByte())
        buffer.putInt(16); buffer.putShort(1); buffer.putShort(1)
        buffer.putInt(SAMPLE_RATE); buffer.putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
        buffer.putShort(BYTES_PER_SAMPLE.toShort()); buffer.putShort((BYTES_PER_SAMPLE * 8).toShort())
        buffer.put('d'.code.toByte()); buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte()); buffer.put('a'.code.toByte())
        buffer.putInt(pcmDataSize)
        for (sample in samples) buffer.putShort(sample)
        return buffer.array()
    }
}
