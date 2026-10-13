package com.inboxiq.app.sms

import android.content.Context
import android.content.SharedPreferences

/**
 * Where one provider source (content://sms or content://mms) has got to.
 *
 * Provider `_id` values only ever grow, so two numbers describe everything:
 *  - [highWater]: every row with `_id <= highWater` has been looked at (or is being backfilled
 *    below). New rows are always `_id > highWater`, so later launches read only those.
 *  - [low]: while [backfillDone] is false, rows with `_id >= low` (and <= highWater) are done and
 *    the next page is `_id < low`, newest first. This is the resume point after a kill.
 */
data class SourceState(
    val started: Boolean = false,
    val highWater: Long = 0,
    val low: Long = 0,
    val backfillDone: Boolean = false,
    val processed: Long = 0,
    val total: Long = 0,
)

/**
 * Small SharedPreferences file, deliberately not a Room table: no schema change, nothing that can
 * touch existing messages. Writes use commit() so the resume point survives the process being killed
 * right after a page lands.
 */
class ImportState(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
    )

    fun load(source: String): SourceState = SourceState(
        started = prefs.getBoolean("$source.started", false),
        highWater = prefs.getLong("$source.hw", 0),
        low = prefs.getLong("$source.low", 0),
        backfillDone = prefs.getBoolean("$source.done", false),
        processed = prefs.getLong("$source.processed", 0),
        total = prefs.getLong("$source.total", 0),
    )

    fun save(source: String, s: SourceState) {
        prefs.edit()
            .putBoolean("$source.started", s.started)
            .putLong("$source.hw", s.highWater)
            .putLong("$source.low", s.low)
            .putBoolean("$source.done", s.backfillDone)
            .putLong("$source.processed", s.processed)
            .putLong("$source.total", s.total)
            .commit()
    }

    /** True when the very first pass found messages already in our DB (an upgrade): no progress UI. */
    var quietFirstPass: Boolean
        get() = prefs.getBoolean("quiet", false)
        set(v) { prefs.edit().putBoolean("quiet", v).commit() }

    var lastRepairAt: Long
        get() = prefs.getLong("repairAt", 0)
        set(v) { prefs.edit().putLong("repairAt", v).commit() }

    /** True once both sources have finished their first full pass. */
    fun isBackfillComplete(): Boolean =
        SOURCES.all { load(it).let { s -> s.started && s.backfillDone } }

    companion object {
        const val FILE = "inboxiq_import"
        const val SMS = "sms"
        const val MMS = "mms"
        val SOURCES = listOf(SMS, MMS)
    }
}
