package com.example.arjun_ai

/**
 * Tiny bridge between [PhoneAudioReceiverService] (which receives watch-mic PCM on
 * /arjun/audio) and a live agent conversation.
 *
 * When [liveCapture] is true, the receiver forwards decoded 16-bit PCM frames to
 * [onWatchPcm] for the VAD pipeline instead of writing a Phase-1 WAV file. When
 * false, the receiver keeps its original Phase-1 file-write behavior untouched.
 */
object ConversationAudioBus {
    @Volatile var liveCapture: Boolean = false
    @Volatile var onWatchPcm: ((ShortArray) -> Unit)? = null

    fun begin(onPcm: (ShortArray) -> Unit) {
        onWatchPcm = onPcm
        liveCapture = true
    }

    fun end() {
        liveCapture = false
        onWatchPcm = null
    }
}
