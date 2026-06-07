package com.example.arjun_ai.tts

/**
 * Voice configuration for Arjun TTS. Ported from the POC.
 *
 * Kokoro + Piper voices run in the remote Ajna AI TTS service app (com.example.ajnaaitts).
 * Android TTS voices run locally via the system TextToSpeech engine.
 *
 * Voice IDs 0-5 MUST match the IDs in the TTS service app's VoiceConfig.
 * Voice IDs 6-7 are Android TTS (local, no service needed).
 */
object VoiceConfig {

    enum class EngineType { KOKORO, PIPER, ANDROID_TTS }

    data class Voice(
        val id: Int,
        val displayName: String,
        val engine: EngineType,
        val language: String,
        val gender: String,
        val qualityLabel: String,
        val speedLabel: String,
        /** If true, prefer premium Hindi neural voice from Google TTS */
        val premiumHindi: Boolean = false,
    ) {
        val label: String get() = "$displayName ($language $gender)"
    }

    val ALL_VOICES: List<Voice> = listOf(
        Voice(0, "Alpha", EngineType.KOKORO, "Hindi", "Female", "Best voice", "Slow processing"),
        Voice(1, "Beta", EngineType.KOKORO, "Hindi", "Female", "Best voice", "Slow processing"),
        Voice(2, "Omega", EngineType.KOKORO, "Hindi", "Male", "Best voice", "Slow processing"),
        Voice(3, "Psi", EngineType.KOKORO, "Hindi", "Male", "Best voice", "Slow processing"),
        Voice(4, "Pratham", EngineType.PIPER, "Hindi", "Male", "OK voice", "Very fast processing"),
        Voice(5, "Priyamvada", EngineType.PIPER, "Hindi", "Female", "OK voice", "Very fast processing"),
        Voice(6, "Google Premium", EngineType.ANDROID_TTS, "Hindi", "Default", "Great voice", "Fastest processing", premiumHindi = true),
        Voice(7, "Android TTS", EngineType.ANDROID_TTS, "English", "Default", "Basic voice", "Fastest processing", premiumHindi = false),
    )

    val DEFAULT_VOICE: Voice = ALL_VOICES[6] // Google Premium Hindi as default

    fun getById(id: Int): Voice? = ALL_VOICES.firstOrNull { it.id == id }
}
