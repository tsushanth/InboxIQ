package com.inboxiq.app.sms

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Remembers what the user deleted inside InboxIQ so a later import of the Android provider does not
 * bring it back. The provider itself is never touched: deleting there would be destructive and is a
 * separate, explicit owner decision.
 *
 * A deleted conversation is remembered as "address + the newest timestamp it contained". Any provider
 * row for that address at or before that moment is skipped; a genuinely newer text imports normally.
 * A deleted single message is remembered by a hash of address+timestamp+body (no text is stored).
 */
class ImportTombstones(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    @Volatile private var threads: Map<String, Long> = loadThreads()
    @Volatile private var messages: Set<String> = loadMessages()

    private fun loadThreads(): Map<String, Long> =
        prefs.all.entries.filter { it.key.startsWith(T) }.associate { it.key.removePrefix(T) to (it.value as Long) }

    private fun loadMessages(): Set<String> =
        prefs.all.keys.filter { it.startsWith(M) }.map { it.removePrefix(M) }.toHashSet()

    fun markThreadDeleted(address: String, newestTimestamp: Long) {
        val key = T + address
        val cur = prefs.getLong(key, Long.MIN_VALUE)
        if (newestTimestamp > cur) prefs.edit().putLong(key, newestTimestamp).commit()
        threads = loadThreads()
    }

    /** Undo: the conversation is back, so imports may carry it again. */
    fun clearThread(address: String) {
        prefs.edit().remove(T + address).commit()
        threads = loadThreads()
    }

    fun markMessageDeleted(address: String, timestamp: Long, body: String) {
        prefs.edit().putBoolean(M + digest(address, timestamp, body), true).commit()
        messages = loadMessages()
    }

    fun clearMessage(address: String, timestamp: Long, body: String) {
        prefs.edit().remove(M + digest(address, timestamp, body)).commit()
        messages = loadMessages()
    }

    /** True when a provider row should be skipped. */
    fun isSuppressed(address: String, timestamp: Long, body: String): Boolean {
        threads[address]?.let { if (timestamp <= it) return true }
        return messages.isNotEmpty() && digest(address, timestamp, body) in messages
    }

    private fun digest(address: String, timestamp: Long, body: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(address.toByteArray())
        md.update(timestamp.toString().toByteArray())
        md.update(body.toByteArray())
        return md.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FILE = "inboxiq_import_tombstones"
        private const val T = "t:"
        private const val M = "m:"
    }
}
