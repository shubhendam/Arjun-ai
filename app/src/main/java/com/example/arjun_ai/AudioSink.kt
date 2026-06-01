package com.example.arjun_ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Shared state between the receiver service and the UI.
 * Tracks the live session (if any) plus the list of saved recordings.
 */
object AudioSink {

    enum class LiveState { IDLE, STREAMING }

    data class Live(
        val state: LiveState = LiveState.IDLE,
        val bytes: Long = 0L,
        val message: String = "Waiting for watch…"
    )

    data class Recording(
        val name: String,        // e.g. "session_2025-06-01_14-32-08.wav"
        val path: String,        // absolute file path
        val sizeBytes: Long,
        val createdAtMillis: Long
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings

    /** Call once at app startup to populate the list from disk. */
    fun loadFromDir(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        _recordings.value = files.map {
            Recording(
                name = it.name,
                path = it.absolutePath,
                sizeBytes = it.length(),
                createdAtMillis = it.lastModified()
            )
        }
    }

    fun onConnected() {
        _live.value = Live(LiveState.STREAMING, 0L, "Watch connected, streaming…")
    }

    fun onBytes(total: Long) {
        _live.update {
            it.copy(
                state = LiveState.STREAMING, bytes = total,
                message = "Streaming… ${total / 1024} KB"
            )
        }
    }

    fun onFinished(savedFile: File) {
        val rec = Recording(
            name = savedFile.name,
            path = savedFile.absolutePath,
            sizeBytes = savedFile.length(),
            createdAtMillis = savedFile.lastModified()
        )
        _recordings.update { listOf(rec) + it }
        _live.value = Live(LiveState.IDLE, 0L, "Waiting for watch…")
    }

    fun onError(msg: String) {
        _live.value = Live(LiveState.IDLE, 0L, "Error: $msg")
    }

    fun onDisconnected() {
        if (_live.value.state == LiveState.STREAMING) {
            _live.value = Live(LiveState.IDLE, 0L, "Watch disconnected")
        }
    }

    fun removeRecording(path: String) {
        _recordings.update { list -> list.filterNot { it.path == path } }
    }
}