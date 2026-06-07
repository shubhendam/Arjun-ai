package com.example.arjun_ai.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AgentSession"

val DEFAULT_ARJUN_SYSTEM_PROMPT = """
You are Arjun, a personal AI assistant in the spirit of a calm, capable butler — think J.A.R.V.I.S. from Iron Man. You serve and report to Shubhendam, whom you address as "sir" (and occasionally "boss"). You were built to run entirely on his phone, fully offline, and you are spoken to and heard through an earpiece.

# Persona
- Composed, quick-witted, and quietly confident. Warm but never sycophantic; efficient, never long-winded.
- A light touch of dry charm is welcome, but being genuinely useful always comes first.
- You refer to yourself as Arjun and speak in the first person. You stay in character as Arjun at all times.

# Voice and style (you are heard, not read)
- Everything you say is spoken aloud by a text-to-speech voice, so write the way people actually talk.
- Keep replies short and natural — usually one to three sentences. Expand only when the task genuinely needs it.
- Never use emoji, markdown, asterisks, bullet points, headings, or special symbols. They sound wrong when read aloud.
- Speak numbers, times, and units naturally in your replies (say "ten minutes", not "10 mins"; "three thirty in the afternoon").
- At the very start of a conversation, open with one brief, warm greeting such as "Hello sir, how can I help?" or "At your service, boss." Greet only on your first reply of a session, then simply get on with the task.
- When you are about to do something, acknowledge it in a few words rather than narrating every step.

# Tools — use them, never just describe them
You have real, working tools. The moment the user's intent matches one, call it and act. Do not ask for permission except where noted below, and never claim you have done something without actually calling the matching tool.

## Music
Use play_song, pause_song, next_song, or previous_song for any request to control playback.

## Calling someone
When the user wants to call, phone, ring, or dial a person:
1. Call make_call with the person's name.
2. If there is a single strong match, simply confirm it ("I have <name> — shall I call?"). If there are several, read out the options briefly and ask which one.
3. Only after the user confirms, call confirm_call. If they reject everyone, call cancel_call. If they give a different name, call make_call again with the new name.

## Sending a message (SMS)
When the user wants to text or message someone:
1. Call send_sms with the name. A single strong match — just ask them to confirm that contact; several — read the options and ask which one.
2. After they confirm the contact, call confirm_sms_recipient, then ask the user to say their message.
3. Take only the core of what they say and drop the filler ("tell him", "send", "message that"). For example, "tell him ETA ten minutes" becomes the message "ETA 10 mins". Call draft_sms with that core text.
4. Read the returned final message back to the user word for word, then ask whether to send it, change it, or cancel.
5. To change it, call draft_sms again with the new text. To send, call send_sms_now. To drop it, call cancel_sms.

## Ending the conversation
If the user signals they are finished — "stop", "stop talking", "that's all", "enough", "bye", "goodbye", "done", "exit", "thanks, that's it" — call the stop tool immediately and do NOT reply with any text.

# How you behave
- Always confirm before placing a call and before sending a message. Act immediately for everything else.
- If you are unsure who or what the user means, ask one short clarifying question instead of guessing.
- Answer general questions directly and briefly from your own knowledge.
- One thing at a time: finish the current request before moving on.
""".trimIndent()

// Audio / VAD constants (match the POC agent mode)
private const val SAMPLE_RATE = 16_000
private const val VAD_FRAME_SIZE = 512
private const val BYTES_PER_SAMPLE = 2
private const val SPEECH_DURATION_MS = 150
private const val SILENCE_DURATION_MS = 1200
private const val PRE_SPEECH_FRAMES = 6

/** Per-session captured-audio dir under filesDir. Cleared on load + unload. */
private const val SESSION_AUDIO_DIR = "session_audio"

/** Fixed prefix prepended to every outgoing SMS. */
private const val SMS_PREFIX = "Arjun here on behalf of shubhendam- "

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

    // I/O device of the current conversation (for the end-of-turn beep routing).
    @Volatile private var currentSource: ConversationAudioIO.Source? = null

    // In-progress SMS (persists across turns within one conversation).
    private var smsContact: ContactEntry? = null
    private var smsFinalText: String? = null

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

        override fun onSmsSetRecipient(name: String): String {
            val contact = ContactsHelper.getContactByName(name)
                ?: ContactsHelper.findMatches(name, limit = 1).firstOrNull()?.contact
                ?: return "Could not find contact '$name'. Ask the user to try again or cancel."
            smsContact = contact
            smsFinalText = null
            return "Recipient set to ${contact.displayName}. Ask the user to say their message."
        }

        override fun onSmsDraft(message: String): String {
            val finalText = SMS_PREFIX + message.trim()
            smsFinalText = finalText
            return finalText
        }

        override fun onSmsSend(): String {
            val contact = smsContact ?: return "No recipient set. Ask the user who to message."
            val text = smsFinalText ?: return "No message drafted. Ask the user for the message."
            return try {
                sendSms(contact.phoneNumber, text)
                smsContact = null
                smsFinalText = null
                "Message sent to ${contact.displayName}."
            } catch (e: Exception) {
                Log.e(TAG, "SMS send failed", e)
                "Failed to send the message: ${e.message}"
            }
        }

        override fun onSmsCancel() {
            smsContact = null
            smsFinalText = null
        }
    }

    private fun sendSms(number: String, text: String) {
        val ctx = appContext ?: throw IllegalStateException("no context")
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("SEND_SMS permission not granted")
        }
        @Suppress("DEPRECATION")
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
        else sms.sendTextMessage(number, null, text, null, null)
        Log.d(TAG, "SMS sent to $number (${text.length} chars, ${parts.size} part(s))")
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
        clearSessionAudio() // drop any leftovers from a previous/crashed session
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
            clearSessionAudio() // session over → delete all captured turn audio
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
        currentSource = src
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
        currentSource = null
        smsContact = null
        smsFinalText = null
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

            val audioPath = saveTurnAudio(audioBytes)
            val userMsg = ChatMessage(role = Role.USER, text = "🎤 (voice)", audioPath = audioPath)
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
            // "Your turn" beep, routed to the conversation device, BEFORE we start
            // listening again so it can't self-trigger the VAD.
            val ctx = appContext; val src = currentSource
            if (ctx != null && src != null) try { EndBeep.play(ctx, src) } catch (_: Exception) {}
            _ui.update { it.copy(state = AgentState.LISTENING) }
        } else {
            appContext?.let { stopConversation(it) }
        }
    }

    // ==========================================================================
    // Helpers
    // ==========================================================================
    /** Write a turn's WAV bytes to the session dir; returns its path (or null). */
    private fun saveTurnAudio(wav: ByteArray): String? {
        val ctx = appContext ?: return null
        return try {
            val dir = File(ctx.filesDir, SESSION_AUDIO_DIR).apply { mkdirs() }
            val f = File(dir, "turn_${System.currentTimeMillis()}.wav")
            f.writeBytes(wav)
            f.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveTurnAudio failed: ${e.message}")
            null
        }
    }

    /** Delete every captured turn-audio file for the session. */
    private fun clearSessionAudio() {
        val ctx = appContext ?: return
        try { File(ctx.filesDir, SESSION_AUDIO_DIR).listFiles()?.forEach { it.delete() } }
        catch (_: Exception) {}
    }

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
