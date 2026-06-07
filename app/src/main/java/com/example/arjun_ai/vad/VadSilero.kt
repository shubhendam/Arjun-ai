package com.example.arjun_ai.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.reflect.safeCast

// ============================================================================
// Silero VAD engine — detects speech vs silence in real-time audio frames.
// Ported verbatim from the POC. Requires assets/silero_vad.onnx.
// ============================================================================

class VadSilero(
    context: Context,
    sampleRate: VadSampleRate,
    frameSize: VadFrameSize,
    mode: VadMode,
    speechDurationMs: Int = 0,
    silenceDurationMs: Int = 0,
) : Closeable {

    private val supportedParameters: Map<VadSampleRate, Set<VadFrameSize>> = mapOf(
        VadSampleRate.SAMPLE_RATE_8K to setOf(
            VadFrameSize.FRAME_SIZE_256,
            VadFrameSize.FRAME_SIZE_512,
            VadFrameSize.FRAME_SIZE_768,
        ),
        VadSampleRate.SAMPLE_RATE_16K to setOf(
            VadFrameSize.FRAME_SIZE_512,
            VadFrameSize.FRAME_SIZE_1024,
            VadFrameSize.FRAME_SIZE_1536,
        ),
    )

    private val env: OrtEnvironment
    private val session: OrtSession
    private var isInitiated: Boolean = false

    private var h = FloatArray(128)
    private var c = FloatArray(128)

    private var speechFramesCount = 0
    private var silenceFramesCount = 0
    private var maxSpeechFramesCount = 0
    private var maxSilenceFramesCount = 0

    var sampleRate: VadSampleRate = sampleRate
        set(value) {
            require(supportedParameters.containsKey(value)) {
                "VAD doesn't support Sample Rate: $value"
            }
            field = value
        }

    var frameSize: VadFrameSize = frameSize
        set(value) {
            require(supportedParameters[sampleRate]?.contains(value) == true) {
                "VAD doesn't support Sample Rate: $sampleRate with Frame Size: $value"
            }
            field = value
        }

    var mode: VadMode = mode

    var speechDurationMs: Int = speechDurationMs
        set(value) {
            require(value in 0..300_000) { "speechDurationMs must be in 0..300000, got $value" }
            field = value
            maxSpeechFramesCount = VadAudioUtils.getFramesCount(sampleRate.value, frameSize.value, value)
        }

    var silenceDurationMs: Int = silenceDurationMs
        set(value) {
            require(value in 0..300_000) { "silenceDurationMs must be in 0..300000, got $value" }
            field = value
            maxSilenceFramesCount = VadAudioUtils.getFramesCount(sampleRate.value, frameSize.value, value)
        }

    // ========================================================================
    // Public API
    // ========================================================================

    fun isSpeech(audioData: ShortArray): Boolean =
        isContinuousSpeech(predict(VadAudioUtils.toFloatArray(audioData)))

    fun isSpeech(audioData: ByteArray): Boolean =
        isContinuousSpeech(predict(VadAudioUtils.toFloatArray(audioData)))

    fun isSpeech(audioData: FloatArray): Boolean =
        isContinuousSpeech(predict(audioData))

    /** Reset the internal speech/silence counters and hidden state. */
    fun reset() {
        h = FloatArray(128)
        c = FloatArray(128)
        speechFramesCount = 0
        silenceFramesCount = 0
    }

    override fun close() {
        checkState()
        isInitiated = false
        session.close()
        env.close()
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private fun isContinuousSpeech(isSpeech: Boolean): Boolean {
        if (isSpeech) {
            if (speechFramesCount <= maxSpeechFramesCount) speechFramesCount++
            if (speechFramesCount > maxSpeechFramesCount) {
                silenceFramesCount = 0
                return true
            }
        } else {
            if (silenceFramesCount <= maxSilenceFramesCount) silenceFramesCount++
            if (silenceFramesCount > maxSilenceFramesCount) {
                speechFramesCount = 0
                return false
            } else if (speechFramesCount > maxSpeechFramesCount) {
                return true
            }
        }
        return false
    }

    private fun predict(audioData: FloatArray): Boolean {
        checkState()
        return createInputTensors(audioData).use { tensors ->
            session.run(tensors).use { result ->
                extractResult(result) > threshold()
            }
        }
    }

    private fun extractResult(result: OrtSession.Result): Float {
        val confidence: Array<FloatArray>? = unpack(result, OUTPUT_INDEX)
        flattenArray(unpack(result, HN_INDEX))?.let { h = it }
        flattenArray(unpack(result, CN_INDEX))?.let { c = it }
        return confidence?.getOrNull(0)?.getOrNull(0) ?: 0f
    }

    private inline fun <reified T> unpack(output: OrtSession.Result, index: Int): Array<T>? {
        return try {
            Array<T>::class.safeCast(output.get(index).value)
        } catch (e: OrtException) {
            null
        }
    }

    private fun flattenArray(array: Array<Array<FloatArray>>?): FloatArray? {
        return array?.flatten()?.flatMap { it.asIterable() }?.toFloatArray()
    }

    private fun createInputTensors(audioData: FloatArray): TensorMap {
        return TensorMap().apply {
            put(INPUT_TENSOR, OnnxTensor.createTensor(
                env, FloatBuffer.wrap(audioData), longArrayOf(1, frameSize.value.toLong())
            ))
            put(SR_TENSOR, OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(sampleRate.value.toLong())), longArrayOf(1)
            ))
            put(H_TENSOR, OnnxTensor.createTensor(env, FloatBuffer.wrap(h), longArrayOf(2, 1, 64)))
            put(C_TENSOR, OnnxTensor.createTensor(env, FloatBuffer.wrap(c), longArrayOf(2, 1, 64)))
        }
    }

    private fun threshold(): Float = when (mode) {
        VadMode.NORMAL -> 0.5f
        VadMode.AGGRESSIVE -> 0.8f
        VadMode.VERY_AGGRESSIVE -> 0.95f
        else -> 0f
    }

    private fun checkState() {
        require(isInitiated) { "Cannot use VadSilero after session is closed!" }
    }

    companion object {
        private const val INPUT_TENSOR = "input"
        private const val SR_TENSOR = "sr"
        private const val H_TENSOR = "h"
        private const val C_TENSOR = "c"
        private const val OUTPUT_INDEX = 0
        private const val HN_INDEX = 1
        private const val CN_INDEX = 2
    }

    init {
        this.sampleRate = sampleRate
        this.frameSize = frameSize
        this.mode = mode
        this.silenceDurationMs = silenceDurationMs
        this.speechDurationMs = speechDurationMs

        val sessionOptions = SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
        }
        val modelBytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
        this.env = OrtEnvironment.getEnvironment()
        this.session = env.createSession(modelBytes, sessionOptions)
        this.isInitiated = true
    }

    private class TensorMap : LinkedHashMap<String, OnnxTensor>(), Closeable {
        override fun close() {
            values.forEach { it.close() }
        }
    }
}
