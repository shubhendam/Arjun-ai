package com.example.arjun_ai.vad

// ============================================================================
// Silero VAD configuration enums and audio utilities.
// Ported from the POC. Adapted from: https://github.com/gkonovalov/android-vad
// ============================================================================

enum class VadSampleRate(val value: Int) {
    SAMPLE_RATE_8K(8000),
    SAMPLE_RATE_16K(16000);
}

/**
 * Frame sizes supported by Silero VAD.
 *   For  8000Hz → 256, 512, 768
 *   For 16000Hz → 512, 1024, 1536
 */
enum class VadFrameSize(val value: Int) {
    FRAME_SIZE_256(256),
    FRAME_SIZE_512(512),
    FRAME_SIZE_768(768),
    FRAME_SIZE_1024(1024),
    FRAME_SIZE_1536(1536);
}

/** Detection aggressiveness modes. Higher = stricter threshold. */
enum class VadMode(val value: Int) {
    OFF(0),
    NORMAL(1),
    AGGRESSIVE(2),
    VERY_AGGRESSIVE(3);
}

/** Audio conversion and frame-count utilities used by the VAD. */
object VadAudioUtils {

    /** Convert 16-bit PCM ByteArray → FloatArray normalised to [-1, 1]. */
    fun toFloatArray(audio: ByteArray): FloatArray {
        return FloatArray(audio.size / 2) { i ->
            ((audio[2 * i].toInt() and 0xFF) or (audio[2 * i + 1].toInt() shl 8)) / 32767.0f
        }
    }

    /** Convert 16-bit PCM ShortArray → FloatArray normalised to [-1, 1]. */
    fun toFloatArray(audio: ShortArray): FloatArray {
        return FloatArray(audio.size) { i ->
            audio[i] / 32767.0f
        }
    }

    /** How many frames fit in [durationMs] given [sampleRate] and [frameSize]. */
    fun getFramesCount(sampleRate: Int, frameSize: Int, durationMs: Int): Int {
        return durationMs / (frameSize / (sampleRate / 1000))
    }
}
