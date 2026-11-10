package com.inboxiq.app.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony

/**
 * Looks in the platform's MMS provider for evidence that a message InboxIQ believes failed was in fact sent,
 * so an automatic retry never sends a picture the recipient already has.
 *
 * What it can and cannot prove: a row in the sent box for the same recipient, stamped within a few seconds after
 * the local message was created (the provider keeps whole seconds, and the row is written after the local one),
 * is the platform's own record of that send. The absence of such a row proves nothing, because on some Android
 * versions the row is never written or is removed (that is the case that caused the false failures), so this is a
 * second line of defence behind the result code, not a replacement for it.
 */
internal object SentMmsLookup {
    /** How long after the local row the provider row may be stamped. */
    private const val WINDOW_SECONDS = 10L

    fun hasSentCopy(context: Context, address: String, timestampMs: Long): Boolean = runCatching {
        val resolver = context.contentResolver
        val from = timestampMs / 1000
        val rows = ArrayList<Long>()
        resolver.query(
            Telephony.Mms.Sent.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.DATE} >= ? AND ${Telephony.Mms.DATE} <= ?",
            arrayOf(from.toString(), (from + WINDOW_SECONDS).toString()),
            null,
        )?.use { c -> while (c.moveToNext() && rows.size < 20) rows += c.getLong(0) }
        val wanted = digits(PhoneNumberNormalizer.normalize(context, address))
        rows.any { id ->
            val a = MmsSync.addresses(resolver, id)
            listOfNotNull(a.firstTo).any { sameNumber(digits(PhoneNumberNormalizer.normalize(context, it)), wanted) }
        }
    }.getOrDefault(false)

    private fun digits(s: String) = s.filter { it.isDigit() }

    /** Same subscriber ignoring a country prefix one side lacks (compares the last 10 digits when both are long enough). */
    internal fun sameNumber(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length >= 10 && b.length >= 10 && a.takeLast(10) == b.takeLast(10)
    }
}
