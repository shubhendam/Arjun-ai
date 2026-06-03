package com.example.arjun_ai.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Watch-side. Phone sends commands on /arjun/control:
 *   "stream"  -> start sending mic audio over /arjun/audio (we ARE the source)
 *   "stop"    -> stop everything
 */
class ControlListener : WearableListenerService() {

    companion object {
        private const val TAG = "ControlListener"
        const val PATH = "/arjun/control"
        const val ACTION_REMOTE_CMD = "com.example.arjun_ai.wear.REMOTE_CMD"
        const val EXTRA_CMD = "cmd"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH) return
        val cmd = String(event.data)
        Log.d(TAG, "phone sent: '$cmd'")
        sendBroadcast(Intent(ACTION_REMOTE_CMD).apply {
            setPackage(packageName)
            putExtra(EXTRA_CMD, cmd)
        })
    }
}