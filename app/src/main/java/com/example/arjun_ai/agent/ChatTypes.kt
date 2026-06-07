package com.example.arjun_ai.agent

/** Who authored a chat message. */
enum class Role { USER, MODEL }

/** Timing/throughput stats for one model turn. */
data class InferenceStats(
    val timeToFirstTokenMs: Long,
    val totalTimeMs: Long,
    val tokenCount: Int,
) {
    val tokensPerSecond: Float
        get() = if (totalTimeMs > 0) tokenCount * 1000f / totalTimeMs else 0f
}

/**
 * One bubble in the conversation. No image field — Arjun has no vision path
 * (FastVLM / observe tools were dropped from the POC port).
 */
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
    val stats: InferenceStats? = null,
    /** For USER voice turns: path to the captured WAV so it can be replayed. */
    val audioPath: String? = null,
)
