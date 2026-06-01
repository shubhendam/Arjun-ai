package com.example.arjun_ai

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Periodically checks whether the Arjun watch app is reachable.
 * Backed by Wearable.getNodeClient().connectedNodes — same call the
 * watch and OutputRouter use, so the indicator reflects real reachability.
 */
object WatchConnection {

    private const val TAG = "WatchConn"
    private const val POLL_MS = 3000L

    enum class State { UNKNOWN, CONNECTED, DISCONNECTED }

    data class Status(
        val state: State = State.UNKNOWN,
        val nodeName: String? = null
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private var job: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appCtx = context.applicationContext
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val nodes = Wearable.getNodeClient(appCtx).connectedNodes.await()
                    val nearby = nodes.firstOrNull { it.isNearby }
                    _status.value = if (nearby != null) {
                        Status(State.CONNECTED, nearby.displayName)
                    } else if (nodes.isNotEmpty()) {
                        // Paired but not nearby — treat as disconnected for our purposes
                        Status(State.DISCONNECTED, nodes.first().displayName)
                    } else {
                        Status(State.DISCONNECTED, null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed: ${e.message}")
                    _status.value = Status(State.DISCONNECTED, null)
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}