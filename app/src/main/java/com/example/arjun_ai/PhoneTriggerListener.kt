package com.example.arjun_ai

import android.util.Log
import com.example.arjun_ai.agent.AgentSession
import com.example.arjun_ai.agent.ConversationAudioIO
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives "start" / "stop" trigger messages from the watch on /arjun/trigger.
 *
 * Routing depends on mode:
 *  - If the Gemma agent model is loaded ([AgentSession.isReady]) → drive the
 *    live conversation.
 *  - Otherwise → fall back to the Phase-1 audio-test recording flow.
 */
class PhoneTriggerListener : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneTrigger"
        const val PATH = "/arjun/trigger"
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH) return
        val cmd = String(event.data)
        val agentMode = AgentSession.isReady
        Log.d(TAG, "received from watch: '$cmd' (agentMode=$agentMode)")
        when (cmd) {
            "start" ->
                if (agentMode) AgentSession.startConversation(applicationContext, ConversationAudioIO.Trigger.WATCH)
                else InputSourceManager.startSession(applicationContext, InputSourceManager.TriggeredFrom.WATCH)
            "stop" ->
                if (AgentSession.isActive) AgentSession.stopConversation(applicationContext)
                else InputSourceManager.stopSession(applicationContext)
            else -> Log.w(TAG, "unknown command: $cmd")
        }
    }
}