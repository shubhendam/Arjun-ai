package com.example.arjun_ai

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives "start" / "stop" trigger messages from the watch on /arjun/trigger.
 * The watch sends this when the user presses ▶ or ⏹ on the watch.
 * We then ask InputSourceManager to decide where to actually record from.
 */
class PhoneTriggerListener : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneTrigger"
        const val PATH = "/arjun/trigger"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH) return
        val cmd = String(event.data)
        Log.d(TAG, "received from watch: '$cmd'")
        when (cmd) {
            "start" -> InputSourceManager.startSession(applicationContext,
                InputSourceManager.TriggeredFrom.WATCH)
            "stop" -> InputSourceManager.stopSession(applicationContext)
            else -> Log.w(TAG, "unknown command: $cmd")
        }
    }
}