package com.inboxiq.app.sms

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable, bounded list of our own message rows whose write to the Android SMS provider failed or is
 * unfinished (not the default app at that instant, storage full, provider down...). It holds only
 * `messages.id` values and attempt counts, never addresses or text: the retry rebuilds everything
 * from our row. SharedPreferences, not Room, on purpose: no schema change, nothing that can touch
 * existing messages (same choice as [ImportState]).
 *
 * Because entries are added only by the receive and send paths, membership is a reliable marker for
 * "originated locally"; rows imported FROM the provider can never be in it.
 */
class ProviderWriteQueue(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    fun ids(): List<Long> = prefs.all.keys.filter { it.startsWith(P) }.mapNotNull { it.removePrefix(P).toLongOrNull() }.sorted()

    fun isEmpty(): Boolean = prefs.all.keys.none { it.startsWith(P) }

    /** Adds [id] (keeps an existing attempt count). Returns false when the queue is full. */
    @Synchronized
    fun add(id: Long): Boolean {
        if (id <= 0) return false
        if (prefs.contains(P + id)) return true
        if (ids().size >= MAX_ENTRIES) return false
        prefs.edit().putInt(P + id, 0).commit()
        return true
    }

    @Synchronized
    fun remove(id: Long) { prefs.edit().remove(P + id).commit() }

    /** Counts a failed attempt. Returns the new count; at [MAX_ATTEMPTS] the entry is dropped (the message stays in our DB). */
    @Synchronized
    fun recordFailure(id: Long): Int {
        val n = prefs.getInt(P + id, 0) + 1
        if (n >= MAX_ATTEMPTS) prefs.edit().remove(P + id).commit() else prefs.edit().putInt(P + id, n).commit()
        return n
    }

    companion object {
        const val FILE = "inboxiq_provider_writes"
        const val MAX_ENTRIES = 500
        const val MAX_ATTEMPTS = 6
        private const val P = "q:"
    }
}
