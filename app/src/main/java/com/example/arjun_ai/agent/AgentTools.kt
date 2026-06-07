package com.example.arjun_ai.agent

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

private const val TAG = "AgentTools"

/**
 * Tool callbacks delivered to [AgentSession]. Trimmed from the POC's
 * AgentToolCallback — vision (observe/photo/video) was dropped for Arjun.
 */
interface AgentToolCallback {
    /** Media control on the phone. action ∈ {play, pause, next, previous}. */
    fun onMediaControl(action: String)

    /** The user asked to stop/end the conversation. */
    fun onStop()

    /** Delivered after make_call fuzzy-matched the name (for logging/UI). */
    fun onMakeCall(matches: List<ContactMatch>, originalQuery: String): String

    /** The user confirmed a contact — open the dialer. Returns a string for the model. */
    fun onConfirmCall(contactName: String): String

    /** Lock the SMS recipient by name (resolve + store). Returns a string for the model. */
    fun onSmsSetRecipient(name: String): String

    /** Draft the SMS from the user's core message; stores final text. Returns the final text. */
    fun onSmsDraft(message: String): String

    /** Send the drafted SMS to the locked recipient. Returns a result string. */
    fun onSmsSend(): String

    /** Abandon the in-progress SMS. */
    fun onSmsCancel()
}

/**
 * ToolSet registered directly with Gemma 4 E4B via litertlm's tool() wrapper.
 * The model sees the @Tool descriptions at init and calls them during inference —
 * one model, no second function-calling model.
 *
 * Ported from the POC's AgentModeTools, keeping only call + media + stop.
 */
class AgentTools : ToolSet {

    var callback: AgentToolCallback? = null

    // ---- Media control (performed on the phone) ----

    @Tool(
        description = "Plays or resumes music. Use when the user asks to play music, " +
                "play a song, resume, or start playback."
    )
    fun play_song(): Map<String, String> {
        Log.d(TAG, "play_song")
        callback?.onMediaControl("play")
        return mapOf("result" to "success", "action" to "play_song")
    }

    @Tool(description = "Pauses music. Use when the user asks to pause or stop the music.")
    fun pause_song(): Map<String, String> {
        Log.d(TAG, "pause_song")
        callback?.onMediaControl("pause")
        return mapOf("result" to "success", "action" to "pause_song")
    }

    @Tool(description = "Skips to the next song/track. Use when the user asks for the next song.")
    fun next_song(): Map<String, String> {
        Log.d(TAG, "next_song")
        callback?.onMediaControl("next")
        return mapOf("result" to "success", "action" to "next_song")
    }

    @Tool(description = "Goes to the previous song/track. Use when the user asks for the previous song.")
    fun previous_song(): Map<String, String> {
        Log.d(TAG, "previous_song")
        callback?.onMediaControl("previous")
        return mapOf("result" to "success", "action" to "previous_song")
    }

    @Tool(
        description = "Stops and ends the current conversation / assistant session. " +
                "Use this when the user says stop, stop conversation, end, that's all, " +
                "goodbye, bye, never mind, or otherwise wants to end the chat."
    )
    fun stop(): Map<String, String> {
        Log.d(TAG, "stop")
        callback?.onStop()
        return mapOf("result" to "success", "action" to "stop")
    }

    // ---- Call tools (self-contained) ----

    @Tool(
        description = "Initiates a phone call to a contact. " +
                "Use this when the user asks to call someone, phone someone, " +
                "ring someone, dial someone, or make a call. " +
                "Extract the person's name and pass it as the 'name' parameter. " +
                "The tool searches the contact list and returns matching contacts for confirmation. " +
                "IMPORTANT: After presenting the matches, wait for the user's response. " +
                "If the user says 'no', 'none', 'none of these', 'cancel', 'never mind', " +
                "'wrong', or rejects all options, use the cancel_call tool instead of confirm_call. " +
                "If the user says a different name, call make_call again with the new name. " +
                "Only use confirm_call when the user clearly picks one of the listed contacts."
    )
    fun make_call(
        @ToolParam(description = "The name of the person to call")
        name: String,
    ): Map<String, String> {
        Log.d(TAG, "make_call — name='$name'")

        if (!ContactsHelper.isLoaded) {
            return mapOf(
                "result" to "error",
                "message" to "Contact list is not available. Please grant contacts permission first.",
            )
        }

        val matches = ContactsHelper.findMatches(name, limit = 3)

        if (matches.isEmpty()) {
            return mapOf(
                "result" to "no_match",
                "message" to "No contacts found matching '$name'. " +
                        "Ask the user to try a different name or spell it out. " +
                        "If the user wants to give up, use the cancel_call tool.",
            )
        }

        callback?.onMakeCall(matches, name)

        val topMatch = matches[0]
        val isStrongMatch = topMatch.score >= 0.95 && matches.size == 1

        if (isStrongMatch) {
            return mapOf(
                "result" to "strong_match",
                "contact_name" to topMatch.contact.displayName,
                "contact_number" to topMatch.contact.phoneNumber,
                "message" to "Found a strong match: ${topMatch.contact.displayName}. " +
                        "Ask the user to confirm before calling. " +
                        "If the user says yes, use confirm_call with this name. " +
                        "If the user says no or wants someone else, use cancel_call.",
            )
        }

        val candidateList = matches.mapIndexed { idx, match ->
            "${idx + 1}. ${match.contact.displayName}"
        }.joinToString(", ")

        return mapOf(
            "result" to "multiple_matches",
            "candidates" to candidateList,
            "count" to matches.size.toString(),
            "message" to "Found ${matches.size} possible contacts: $candidateList. " +
                    "Ask the user which one they want to call by listing the names. " +
                    "If the user picks one by name or number, use confirm_call with that contact name. " +
                    "If the user says 'no', 'none of these', 'cancel', or rejects all options, use cancel_call. " +
                    "If the user says a completely different name, call make_call again with that new name.",
        )
    }

    @Tool(
        description = "Confirms and places a phone call to the specified contact. " +
                "Use this ONLY after the user has explicitly confirmed which contact to call " +
                "by saying the contact's name, saying 'yes', or picking a number from the list. " +
                "Pass the exact contact name that the user confirmed. " +
                "This opens the phone dialer with that contact's number. " +
                "NEVER use this if the user said 'no', 'none', 'cancel', 'wrong', " +
                "'none of these', or rejected the options — use cancel_call instead."
    )
    fun confirm_call(
        @ToolParam(description = "The exact confirmed contact name to call")
        name: String,
    ): Map<String, String> {
        Log.d(TAG, "confirm_call — name='$name'")

        val nameLower = name.trim().lowercase()
        val rejectionWords = setOf(
            "no", "none", "cancel", "stop", "never mind", "nevermind",
            "none of these", "wrong", "nope", "nah", "don't", "dont",
            "not", "nothing", "nobody", "no one", "noone",
        )
        if (nameLower in rejectionWords || nameLower.startsWith("no ") ||
            nameLower.startsWith("none ") || nameLower.startsWith("cancel")
        ) {
            return mapOf(
                "result" to "cancelled",
                "message" to "The user does not want to call anyone. Acknowledge this and move on.",
            )
        }

        if (!ContactsHelper.isLoaded) {
            return mapOf("result" to "error", "message" to "Contact list is not available.")
        }

        var contact = ContactsHelper.getContactByName(name)
        if (contact == null) {
            val fuzzy = ContactsHelper.findMatches(name, limit = 1)
            if (fuzzy.isNotEmpty() && fuzzy[0].score >= 0.85) {
                contact = fuzzy[0].contact
            }
        }

        if (contact == null) {
            return mapOf(
                "result" to "error",
                "message" to "Could not find contact '$name'. " +
                        "Ask the user to try again or use cancel_call if they want to stop.",
            )
        }

        val callbackResult = callback?.onConfirmCall(contact.displayName)
            ?: "Callback not set — cannot open dialer"

        return mapOf(
            "result" to "success",
            "action" to "dialer_opened",
            "contact_name" to contact.displayName,
            "contact_number" to contact.phoneNumber,
            "message" to callbackResult,
        )
    }

    @Tool(
        description = "Cancels the phone call process. " +
                "Use this when the user says 'no', 'none of these', 'cancel', " +
                "'never mind', 'wrong person', 'stop', or otherwise rejects " +
                "all the suggested contacts and does not want to call anyone. " +
                "Also use this if the user provides a different name — cancel first, " +
                "then acknowledge, and wait for the user to ask again."
    )
    fun cancel_call(): Map<String, String> {
        Log.d(TAG, "cancel_call")
        return mapOf(
            "result" to "cancelled",
            "message" to "Call cancelled. Tell the user the call has been cancelled. " +
                    "If they want to try a different name, they can ask again.",
        )
    }

    // ---------------------------------------------------------------------------
    // SMS tools (hands-free, multi-step):
    //   send_sms(name)            -> search contacts, present matches
    //   confirm_sms_recipient(n)  -> lock the recipient, then ask for the message
    //   draft_sms(message)        -> wrap with the fixed prefix, read it back
    //   send_sms_now()            -> actually send the drafted message
    //   cancel_sms()              -> abort
    // ---------------------------------------------------------------------------

    @Tool(
        description = "Starts sending a text message / SMS to a contact. " +
                "Use this when the user asks to text, message, SMS, or send a message to someone. " +
                "Extract the person's name and pass it as 'name'. The tool searches contacts and " +
                "returns matches for confirmation. " +
                "If there is a single strong match, do NOT list options — just ask the user to confirm " +
                "that one contact. If there are multiple matches, list them and ask which one. " +
                "After the user confirms a contact, call confirm_sms_recipient. " +
                "If the user rejects all options, use cancel_sms. If the user says a different name, " +
                "call send_sms again with the new name."
    )
    fun send_sms(
        @ToolParam(description = "The name of the person to message")
        name: String,
    ): Map<String, String> {
        Log.d(TAG, "send_sms — name='$name'")
        if (!ContactsHelper.isLoaded) {
            return mapOf("result" to "error",
                "message" to "Contact list is not available. Grant contacts permission first.")
        }
        val matches = ContactsHelper.findMatches(name, limit = 3)
        if (matches.isEmpty()) {
            return mapOf("result" to "no_match",
                "message" to "No contacts found matching '$name'. Ask the user for a different name, " +
                        "or use cancel_sms if they want to stop.")
        }
        val top = matches[0]
        if (top.score >= 0.95 && matches.size == 1) {
            return mapOf(
                "result" to "strong_match",
                "contact_name" to top.contact.displayName,
                "message" to "Found a strong match: ${top.contact.displayName}. " +
                        "Ask the user to confirm this is the right contact. If yes, call " +
                        "confirm_sms_recipient with this name. If no, use cancel_sms.",
            )
        }
        val candidateList = matches.mapIndexed { i, m -> "${i + 1}. ${m.contact.displayName}" }
            .joinToString(", ")
        return mapOf(
            "result" to "multiple_matches",
            "candidates" to candidateList,
            "message" to "Found ${matches.size} possible contacts: $candidateList. " +
                    "Ask the user which one to message. When they pick one, call confirm_sms_recipient " +
                    "with that name. If they reject all, use cancel_sms. If they say a new name, call " +
                    "send_sms again with it.",
        )
    }

    @Tool(
        description = "Confirms and locks the SMS recipient after the user has chosen a contact. " +
                "Pass the exact confirmed contact name. After this succeeds, ask the user to say " +
                "the message they want to send."
    )
    fun confirm_sms_recipient(
        @ToolParam(description = "The exact confirmed contact name")
        name: String,
    ): Map<String, String> {
        Log.d(TAG, "confirm_sms_recipient — name='$name'")
        val result = callback?.onSmsSetRecipient(name) ?: "Callback not set."
        return mapOf("result" to "recipient_set", "message" to result)
    }

    @Tool(
        description = "Drafts the SMS body from the user's spoken message. " +
                "Pass ONLY the core message the user wants to send — strip filler words like " +
                "'tell him', 'send', 'message that', 'say'. For example if the user says " +
                "'tell him ETA 10 mins', pass 'ETA 10 mins'. " +
                "The tool wraps it with the required prefix and returns the FINAL text. " +
                "Read the final text back to the user EXACTLY, then ask whether to send it, modify it, " +
                "or cancel. To modify, call draft_sms again with the new message. Never invent content."
    )
    fun draft_sms(
        @ToolParam(description = "The core message text to send (filler removed)")
        message: String,
    ): Map<String, String> {
        Log.d(TAG, "draft_sms — message='$message'")
        val finalText = callback?.onSmsDraft(message) ?: "Callback not set."
        return mapOf(
            "result" to "drafted",
            "final_text" to finalText,
            "message" to "The message to send is: \"$finalText\". Read this back to the user exactly " +
                    "and ask whether to send, modify, or cancel.",
        )
    }

    @Tool(
        description = "Sends the drafted SMS to the confirmed recipient. " +
                "Use ONLY after the user explicitly confirms they want to send (says 'send', 'yes send it'). " +
                "Do not use if the user wants to modify or cancel."
    )
    fun send_sms_now(): Map<String, String> {
        Log.d(TAG, "send_sms_now")
        val result = callback?.onSmsSend() ?: "Callback not set."
        return mapOf("result" to "sent", "message" to result)
    }

    @Tool(
        description = "Cancels the in-progress SMS. Use when the user says 'no', 'cancel', " +
                "'never mind', or otherwise does not want to send a message."
    )
    fun cancel_sms(): Map<String, String> {
        Log.d(TAG, "cancel_sms")
        callback?.onSmsCancel()
        return mapOf("result" to "cancelled",
            "message" to "Message cancelled. Tell the user the message has been cancelled.")
    }
}
