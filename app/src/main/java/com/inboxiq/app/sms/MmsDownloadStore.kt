package com.inboxiq.app.sms

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's own record of download attempts per provider MMS row (`content://mms` `_id`), in a small private
 * preferences file. Holds ids, counts, times and result codes only: no addresses, no message content.
 */
class MmsDownloadStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    data class Entry(val id: Long, val attempts: Int, val firstSeenAt: Long, val lastCode: Int?)

    fun entry(id: Long): Entry? =
        if (!prefs.contains("$id.n")) null
        else Entry(id, prefs.getInt("$id.n", 0), prefs.getLong("$id.first", 0), if (prefs.contains("$id.code")) prefs.getInt("$id.code", 0) else null)

    fun attempts(id: Long): Int = prefs.getInt("$id.n", 0)

    /** When this row was first handled; [now] if never, so a fresh row starts its 48 hours now. */
    fun firstSeenAt(id: Long, now: Long): Long = if (prefs.contains("$id.first")) prefs.getLong("$id.first", now) else now

    fun isInFlight(id: Long, now: Long): Boolean = prefs.getLong("$id.until", 0L) > now

    /** Earliest time the next attempt for [id] may start (0 = no limit). Backoff after a failed download. */
    fun notBefore(id: Long): Long = prefs.getLong("$id.next", 0L)

    /** Counts one download attempt for [id] and marks it running until [MmsDownloadPolicy.IN_FLIGHT_MS] pass. */
    fun recordAttempt(id: Long, now: Long, firstSeenAt: Long = now): Int {
        val n = attempts(id) + 1
        val e = prefs.edit()
        if (!prefs.contains("$id.first")) e.putLong("$id.first", minOf(firstSeenAt, now))
        e.putInt("$id.n", n).putLong("$id.until", now + MmsDownloadPolicy.IN_FLIGHT_MS).remove("$id.gave").remove("$id.next")
        e.putStringSet(IDS, ids() + id.toString())
        e.apply()
        return n
    }

    /** The platform answered (or the trigger threw): the download is no longer running. */
    fun recordResult(id: Long, code: Int, notBefore: Long = 0L) {
        val e = prefs.edit().putInt("$id.code", code).remove("$id.until")
        if (notBefore > 0L) e.putLong("$id.next", notBefore) else e.remove("$id.next")
        e.apply()
    }

    /** True once the give-up has been reported for [id]; keeps the report to one per row. */
    fun gaveUpReported(id: Long): Boolean = prefs.getBoolean("$id.gave", false)

    fun markGaveUpReported(id: Long) {
        prefs.edit().putBoolean("$id.gave", true).putStringSet(IDS, ids() + id.toString()).apply()
    }

    /** Forgets the attempt count so a user-requested retry starts fresh (keeps the first-seen time and age cap). */
    fun resetAttempts(id: Long) {
        prefs.edit().putInt("$id.n", 0).remove("$id.until").remove("$id.next").remove("$id.gave").apply()
    }

    fun forget(id: Long) {
        prefs.edit().remove("$id.n").remove("$id.first").remove("$id.until").remove("$id.code").remove("$id.next").remove("$id.gave")
            .putStringSet(IDS, ids() - id.toString()).apply()
    }

    /** Every id this store holds a record for. */
    fun knownIds(): Set<Long> = ids().mapNotNull { it.toLongOrNull() }.toSet()

    private fun ids(): Set<String> = prefs.getStringSet(IDS, emptySet()) ?: emptySet()

    companion object {
        const val FILE = "inboxiq_mms_download"
        private const val IDS = "ids"
    }
}
