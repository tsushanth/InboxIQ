package com.inboxiq.app.sms

import android.util.Log

/**
 * The one non-personal event for an MMS download that ends badly (or ends well after trouble). Carries the
 * provider row id, a result-code name, a count and a flag, never an address or any message content. The core app
 * has no network code, so this goes to the log only.
 */
object MmsDownloadReport {
    enum class Kind { GAVE_UP_PERMANENT, GAVE_UP_EXHAUSTED, RESOLVED_AFTER_RETRY }

    data class Event(val kind: Kind, val id: Long, val codeName: String, val attempts: Int, val imageArrived: Boolean)

    fun toLogLine(e: Event): String =
        "mms_download ${e.kind.name.lowercase()} id=${e.id} code=${e.codeName} attempts=${e.attempts} imageArrived=${e.imageArrived}"

    /** The default sink. */
    fun log(e: Event) {
        if (e.kind == Kind.RESOLVED_AFTER_RETRY) Log.i(TAG, toLogLine(e)) else Log.w(TAG, toLogLine(e))
    }

    private const val TAG = "MmsDownloadReport"
}
