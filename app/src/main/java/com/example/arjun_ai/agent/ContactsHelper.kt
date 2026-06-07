package com.example.arjun_ai.agent

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

private const val TAG = "ContactsHelper"

/** A single contact entry: display name + phone number. */
data class ContactEntry(
    val displayName: String,
    val phoneNumber: String,
)

/** A fuzzy-match result: the original contact + similarity score (0.0–1.0). */
data class ContactMatch(
    val contact: ContactEntry,
    val score: Double,
)

/**
 * Manages the device contact list for the call tool. Ported from the POC's
 * agentmode/ContactsHelper.
 *
 * Usage:
 *  1. [loadContacts] once after READ_CONTACTS permission is granted.
 *  2. [findMatches] with the (possibly garbled) name from the model.
 *  3. [getContactByName] for exact lookup after the user confirms.
 */
object ContactsHelper {

    @Volatile
    private var contacts: List<ContactEntry> = emptyList()

    val isLoaded: Boolean get() = contacts.isNotEmpty()

    /** Fetch all contacts that have at least one phone number. Call off the main thread. */
    fun loadContacts(context: Context) {
        val entries = mutableListOf<ContactEntry>()
        val seen = mutableSetOf<String>() // dedupe by "name|number"

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim() ?: continue
                    val number = cursor.getString(numberIdx)?.trim() ?: continue
                    if (name.isBlank() || number.isBlank()) continue

                    val key = "${name.lowercase()}|${number.replace("\\s".toRegex(), "")}"
                    if (seen.add(key)) {
                        entries.add(ContactEntry(displayName = name, phoneNumber = number))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contacts", e)
        }

        contacts = entries
        Log.d(TAG, "Loaded ${entries.size} contacts")
    }

    /**
     * Find the top [limit] contacts whose names best match [query].
     * Matching tolerates Indian-name phonetic mismatches via vowel-stripping +
     * Levenshtein. Results sorted by score descending.
     */
    fun findMatches(query: String, limit: Int = 3): List<ContactMatch> {
        if (contacts.isEmpty() || query.isBlank()) return emptyList()

        val queryLower = query.trim().lowercase()
        val queryNorm = normalize(queryLower)

        return contacts.map { contact ->
            val nameLower = contact.displayName.lowercase()
            val nameNorm = normalize(nameLower)
            val score = computeScore(queryLower, queryNorm, nameLower, nameNorm)
            ContactMatch(contact = contact, score = score)
        }
            .filter { it.score > 0.15 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Exact lookup by display name (case-insensitive). */
    fun getContactByName(name: String): ContactEntry? {
        val target = name.trim().lowercase()
        return contacts.firstOrNull { it.displayName.lowercase() == target }
    }

    fun getAllContactNames(): List<String> = contacts.map { it.displayName }

    // ========================================================================
    // Scoring
    // ========================================================================

    private fun computeScore(
        queryLower: String,
        queryNorm: String,
        nameLower: String,
        nameNorm: String,
    ): Double {
        if (queryLower == nameLower) return 1.0

        val nameParts = nameLower.split("\\s+".toRegex())
        if (nameParts.any { it == queryLower }) return 0.95

        if (nameLower.startsWith(queryLower)) return 0.90
        if (nameParts.any { it.startsWith(queryLower) }) return 0.88

        if (nameLower.contains(queryLower)) return 0.85

        if (queryNorm == nameNorm && queryNorm.length >= 3) return 0.82
        val normParts = nameNorm.split("\\s+".toRegex())
        if (normParts.any { it == queryNorm && queryNorm.length >= 3 }) return 0.80

        var bestPartScore = 0.0
        for (part in nameParts) {
            val dist = levenshtein(queryLower, part)
            val maxLen = maxOf(queryLower.length, part.length)
            if (maxLen == 0) continue
            val partScore = 1.0 - (dist.toDouble() / maxLen)
            if (partScore > bestPartScore) bestPartScore = partScore
        }

        val fullDist = levenshtein(queryLower, nameLower)
        val fullMaxLen = maxOf(queryLower.length, nameLower.length)
        val fullScore = if (fullMaxLen > 0) 1.0 - (fullDist.toDouble() / fullMaxLen) else 0.0

        var bestNormScore = 0.0
        for (part in normParts) {
            val dist = levenshtein(queryNorm, part)
            val maxLen = maxOf(queryNorm.length, part.length)
            if (maxLen == 0) continue
            val normScore = 1.0 - (dist.toDouble() / maxLen)
            if (normScore > bestNormScore) bestNormScore = normScore
        }

        return maxOf(bestPartScore * 0.75, fullScore * 0.70, bestNormScore * 0.78)
    }

    // ========================================================================
    // String utilities
    // ========================================================================

    /** lowercase → strip vowels → collapse repeats. "deepanshu"/"dipanshu" → "dpnsh". */
    private fun normalize(s: String): String {
        return s.lowercase()
            .replace("[aeiou]".toRegex(), "")
            .replace("(.)\\1+".toRegex(), "$1")
            .trim()
    }

    /** Standard Levenshtein edit distance. */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
