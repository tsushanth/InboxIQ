package com.inboxiq.app.sms

import android.provider.Telephony
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One-time repair for installs that imported history before the import carried the provider's `read`
 * flag: those installs hold incoming rows marked unread (MMS) that Android itself says were read.
 *
 * The rule, and why it is safe:
 *  - The provider is consulted in ONE direction only. A provider row with `read = 1` is evidence that
 *    somebody (this app's mark-read mirror, another SMS app, the platform) read that text, so the
 *    matching row of ours is set to read. A provider `read = 0` changes nothing, and nothing is ever
 *    marked unread, so a row the user already read in InboxIQ stays read.
 *  - Rows we received while default and have not read yet are untouched: when we store them we write
 *    them to the provider with `read = 0`, and the provider only flips to 1 when they are read.
 *  - A row of ours is matched only by its natural key (SMS: address, time, body; MMS: address and
 *    provider time). A row whose provider counterpart cannot be found is left exactly as it is.
 *  - Only `isRead` of incoming unread rows is written. No other column, no inserts, no deletes.
 *  - It runs once: [ImportState.readReconcileVersion] is set only after a complete pass; any failure
 *    (permission, null cursor) leaves the flag unset and the pass is retried by the next import run.
 *    The pass is idempotent and monotonic, so being interrupted and re-run cannot do harm.
 */
object ReadReconcile {

    const val VERSION = 1
    private const val PAGE = 1000

    /** Returns how many rows were marked read. Returns 0 immediately when this version already ran. */
    suspend fun runOnce(env: ImportEnv, state: ImportState): Int {
        if (state.readReconcileVersion >= VERSION) return 0
        val dao = env.db.messageDao()
        val candidates = dao.unreadIncomingTimestamps().toHashSet()
        var marked = 0
        if (candidates.isNotEmpty()) {
            marked += reconcileSms(env, candidates)
            marked += reconcileMms(env, candidates)
        }
        state.readReconcileVersion = VERSION
        return marked
    }

    private suspend fun reconcileSms(env: ImportEnv, candidates: Set<Long>): Int {
        var marked = 0
        var below = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val hits = ArrayList<Triple<String, Long, String>>()
            var read = 0
            var minId = Long.MAX_VALUE
            val cursor = env.resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                "${Telephony.Sms._ID} < ?", arrayOf(below.toString()), "${Telephony.Sms._ID} DESC",
            ) ?: throw ProviderUnavailableException("sms provider returned no cursor")
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = it.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (read < PAGE && it.moveToNext()) {
                    read++
                    minId = minOf(minId, it.getLong(idIdx))
                    if (it.getInt(typeIdx) != Telephony.Sms.MESSAGE_TYPE_INBOX || it.getInt(readIdx) != 1) continue
                    val date = it.getLong(dateIdx)
                    if (date !in candidates) continue
                    hits += Triple(env.normalize(it.getString(addressIdx) ?: "unknown"), date, it.getString(bodyIdx) ?: "")
                }
            }
            if (read == 0 || minId >= below) break
            if (hits.isNotEmpty()) marked += env.db.withTransaction {
                hits.sumOf { (a, t, b) -> env.db.messageDao().markIncomingReadByKey(a, t, b) }
            }
            below = minId
            if (read < PAGE) break
        }
        return marked
    }

    private suspend fun reconcileMms(env: ImportEnv, candidates: Set<Long>): Int {
        var marked = 0
        var below = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val hits = ArrayList<Pair<Long, Long>>() // provider id, our timestamp
            var read = 0
            var minId = Long.MAX_VALUE
            val cursor = env.resolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ),
                "${Telephony.Mms._ID} < ?", arrayOf(below.toString()), "${Telephony.Mms._ID} DESC",
            ) ?: throw ProviderUnavailableException("mms provider returned no cursor")
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val boxIdx = it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val readIdx = it.getColumnIndexOrThrow(Telephony.Mms.READ)
                while (read < PAGE / 5 && it.moveToNext()) {
                    read++
                    val id = it.getLong(idIdx)
                    minId = minOf(minId, id)
                    if (it.getInt(boxIdx) != Telephony.Mms.MESSAGE_BOX_INBOX || it.getInt(readIdx) != 1) continue
                    val ts = it.getLong(dateIdx) * 1000
                    if (ts in candidates) hits += id to ts
                }
            }
            if (read == 0 || minId >= below) break
            // Sender lookups are provider reads: do them before the DB transaction.
            val resolved = hits.map { (id, ts) -> env.normalize(MmsSync.addresses(env.resolver, id).from ?: "unknown") to ts }
            if (resolved.isNotEmpty()) marked += env.db.withTransaction {
                resolved.sumOf { (a, t) -> env.db.messageDao().markIncomingReadByAddressTimestamp(a, t) }
            }
            below = minId
            if (read < PAGE / 5) break
        }
        return marked
    }
}
