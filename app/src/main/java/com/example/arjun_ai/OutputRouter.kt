package com.example.arjun_ai

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides where audio output should go, based on priority:
 *   1. Earbuds (BT or wired) connected to phone -> play on phone (OS routes to them)
 *   2. Watch reachable                          -> stream to watch
 *   3. Fallback                                 -> phone speaker
 */
object OutputRouter {

    private const val TAG = "OutputRouter"

    enum class Destination { EARBUDS_VIA_PHONE, WATCH, PHONE_SPEAKER }

    /**
     * Returns where the next playback should go.
     * Checks earbuds first (fast), then probes watch reachability with a 1.5s timeout.
     */
    suspend fun decide(context: Context): Destination {
        if (hasEarbuds(context)) {
            Log.d(TAG, "Earbuds detected -> route via phone")
            return Destination.EARBUDS_VIA_PHONE
        }
        val watchReachable = withTimeoutOrNull(1500) {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                nodes.any { it.isNearby }
            } catch (e: Exception) {
                Log.w(TAG, "node lookup failed: ${e.message}")
                false
            }
        } ?: false

        return if (watchReachable) Destination.WATCH else Destination.PHONE_SPEAKER
    }

    private fun hasEarbuds(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { dev ->
            when (dev.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID -> true
                else -> false
            }
        }
    }
}