package com.example.arjun_ai.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "AgentModelEngine"
private const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"

/** Asset filename used for warmup. Must exist at app/src/main/assets/welcome.wav */
private const val WARMUP_ASSET = "welcome.wav"

val AGENT_MODEL_PATH: String
    get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        .absolutePath + "/$MODEL_FILENAME"

/** True if the Gemma model file exists in /sdcard/Download/. */
fun isAgentModelPresent(): Boolean = java.io.File(AGENT_MODEL_PATH).exists()

/**
 * Self-contained Gemma 4 E4B engine with native tool support, ported from the POC.
 * Registers a [ToolSet] as a [ToolProvider] so the model can call @Tool methods
 * during inference. Accepts audio bytes directly (no Whisper step).
 *
 * NOT a singleton — one instance owned by [AgentSession].
 */
class AgentModelEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var toolProviders: List<ToolProvider>? = null
    private var activeSystemPrompt: String = ""

    val isLoaded: Boolean get() = engine != null

    @OptIn(ExperimentalApi::class)
    fun load(context: Context, systemPrompt: String, toolSets: List<ToolSet>) {
        if (isLoaded) {
            Log.d(TAG, "Already loaded")
            return
        }

        val numThreads = Runtime.getRuntime().availableProcessors()
        val config = EngineConfig(
            modelPath = AGENT_MODEL_PATH,
            backend = Backend.CPU(numOfThreads = numThreads),
            visionBackend = Backend.CPU(numOfThreads = numThreads),
            audioBackend = Backend.CPU(numOfThreads = numThreads),
            maxNumTokens = 4096,
        )

        val e = Engine(config)
        e.initialize()
        Log.d(TAG, "Engine initialized from $AGENT_MODEL_PATH")

        val wrappedTools = toolSets.map { tool(it) }
        toolProviders = wrappedTools

        activeSystemPrompt = systemPrompt
        val conv = createConversation(e, systemPrompt, wrappedTools)
        engine = e
        conversation = conv
        Log.d(TAG, "Agent engine loaded with tools registered")
    }

    /**
     * One-time warmup using assets/welcome.wav so the first real query doesn't pay
     * the cold-start cost. The warmup turn is KEPT in context (no resetConversation)
     * so the system prompt has already conditioned the model. Reply tokens are
     * consumed silently.
     */
    fun warmup(context: Context, timeoutMs: Long = 60_000L) {
        val conv = conversation ?: run {
            Log.w(TAG, "warmup called but conversation is null")
            return
        }

        val wavBytes: ByteArray = try {
            context.assets.open(WARMUP_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $WARMUP_ASSET from assets — skipping warmup", e)
            return
        }
        Log.d(TAG, "Warmup using $WARMUP_ASSET (${wavBytes.size} bytes)")

        val startTime = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        val tokenCount = intArrayOf(0)
        val replyBuffer = StringBuilder()

        try {
            conv.sendMessageAsync(
                Contents.of(listOf(Content.AudioBytes(wavBytes))),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        tokenCount[0]++
                        replyBuffer.append(message.toString())
                    }
                    override fun onDone() { latch.countDown() }
                    override fun onError(throwable: Throwable) {
                        if (throwable is CancellationException) Log.d(TAG, "Warmup cancelled")
                        else Log.w(TAG, "Warmup inference error: ${throwable.message}")
                        latch.countDown()
                    }
                },
                emptyMap(),
            )

            val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = System.currentTimeMillis() - startTime
            if (finished) {
                Log.d(TAG, "Warmup done in ${elapsed}ms (${tokenCount[0]} tokens).")
            } else {
                Log.w(TAG, "Warmup timed out after ${timeoutMs}ms — cancelling")
                conv.cancelProcess()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Warmup failed", e)
        }
        Log.d(TAG, "Warmup complete — model is warm AND primed on welcome.wav turn")
    }

    fun unload() {
        try { conversation?.close() } catch (e: Exception) { Log.e(TAG, "conversation close failed: ${e.message}") }
        conversation = null
        try { engine?.close() } catch (e: Exception) { Log.e(TAG, "engine close failed: ${e.message}") }
        engine = null
        toolProviders = null
        activeSystemPrompt = ""
        System.gc()
        Log.d(TAG, "Agent engine unloaded — native memory released, GC requested")
    }

    fun stopGeneration() {
        conversation?.cancelProcess()
    }

    /** Close the current conversation and open a fresh one (wipes KV cache / history). */
    fun resetConversation() {
        val e = engine ?: return
        val tools = toolProviders ?: return
        try { conversation?.close() } catch (ex: Exception) {
            Log.w(TAG, "resetConversation — close failed: ${ex.message}")
        }
        conversation = createConversation(e, activeSystemPrompt, tools)
        Log.d(TAG, "resetConversation — fresh conversation created (with tools)")
    }

    /**
     * Send a message. The model may generate text (onToken), call a @Tool method
     * (executed on the inference thread, blocks until it returns), or both.
     */
    fun sendMessage(
        text: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val conv = conversation ?: run { onFailure("Agent model not loaded"); return }

        val contents = mutableListOf<Content>()
        for (image in images) contents.add(Content.ImageBytes(image.toJpegBytes()))
        for (audio in audioClips) contents.add(Content.AudioBytes(audio))
        if (text.isNotBlank()) contents.add(Content.Text(text))

        conv.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
                override fun onMessage(message: Message) = onToken(message.toString())
                override fun onDone() = onComplete()
                override fun onError(throwable: Throwable) {
                    if (throwable is CancellationException) onComplete()
                    else onFailure(throwable.message ?: "Agent inference error")
                }
            },
            emptyMap(),
        )
    }

    // ------------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------------

    @OptIn(ExperimentalApi::class)
    private fun createConversation(
        e: Engine,
        systemPrompt: String,
        tools: List<ToolProvider>,
    ): Conversation {
        val sysInstruction = if (systemPrompt.isBlank()) null
        else Contents.of(listOf(Content.Text(systemPrompt)))
        return e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                systemInstruction = sysInstruction,
                tools = tools,
            )
        )
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
