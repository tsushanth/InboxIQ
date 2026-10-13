package com.inboxiq.app.sms

import android.provider.Telephony
import androidx.room.withTransaction
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One-time, conservative repair of sent MMS rows written by earlier builds (see docs/READ_STATE.md).
 *
 * Earlier builds imported a sent MMS under the FROM header (this phone / a placeholder) instead of the
 * recipient, and stamped InboxIQ's own sent MMS in milliseconds while the provider keeps seconds. So a
 * message sent from InboxIQ existed twice: the real row in the recipient's conversation, and an imported
 * copy in a bogus conversation. The importer now files sent MMS under the recipient and matches them by
 * whole second ([MmsSync.sentMatch]); this pass fixes rows already in the database:
 *
 *  1. For each provider sent MMS whose old-style row exists (bogus address, provider time, same text/part),
 *     - if the recipient's conversation already holds that same message (exact key, or a sent MMS with
 *       the same text/attachment in the same second), the bogus copy is an exact duplicate and is
 *       removed; the row that stays is the one the user had, with its own label and state;
 *     - otherwise the row is re-filed under the recipient in place (same id, label, state, image).
 *     A row whose provider counterpart cannot be found is left alone.
 *  2. [dedupe]: inside one conversation, a sent MMS imported from the provider (its image is a
 *     content://mms/part uri) that has a local twin (own image file) with the same text in the same
 *     second is removed. Two local rows, or two provider rows, are never merged with each other.
 *
 * Idempotent; the flag in [ImportState] is set only after a complete pass.
 */
object SentMmsRepair {

    const val VERSION = 1
    private const val PAGE = 200

    data class Result(val moved: Int, val merged: Int)

    suspend fun runOnce(env: ImportEnv, state: ImportState): Result {
        if (state.sentMmsRepairVersion >= VERSION) return Result(0, 0)
        val dao = env.db.messageDao()
        var moved = 0
        var merged = 0
        if (dao.count() > 0) {
            val sentTimes = dao.outgoingTimestamps().toHashSet()
            if (sentTimes.isNotEmpty()) {
                val r = repairFromProvider(env, sentTimes)
                moved += r.moved
                merged += r.merged
            }
            merged += dedupe(env)
        }
        state.sentMmsRepairVersion = VERSION
        return Result(moved, merged)
    }

    private suspend fun repairFromProvider(env: ImportEnv, sentTimes: Set<Long>): Result {
        var moved = 0
        var merged = 0
        var below = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = ArrayList<Long>()
            val times = HashMap<Long, Long>()
            var read = 0
            var minId = Long.MAX_VALUE
            val cursor = env.resolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms._ID} < ?", arrayOf(below.toString()), "${Telephony.Mms._ID} DESC",
            ) ?: throw ProviderUnavailableException("mms provider returned no cursor")
            cursor.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val boxIdx = it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                while (read < PAGE && it.moveToNext()) {
                    read++
                    val id = it.getLong(idIdx)
                    minId = minOf(minId, id)
                    if (it.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_INBOX) continue
                    val ts = it.getLong(dateIdx) * 1000
                    if (ts in sentTimes) { ids += id; times[id] = ts }
                }
            }
            if (read == 0 || minId >= below) break
            if (ids.isNotEmpty()) {
                // Provider reads first, then one DB transaction for the page.
                val addrs = ids.map { MmsSync.addresses(env.resolver, it) }
                val parts = MmsSync.partsFor(env, ids)
                val r = env.db.withTransaction {
                    var mv = 0
                    var mg = 0
                    for ((i, id) in ids.withIndex()) {
                        val to = addrs[i].firstTo ?: continue
                        val oldAddress = env.normalize(addrs[i].from ?: "unknown")
                        val newAddress = env.normalize(to)
                        if (oldAddress == newAddress) continue
                        when (refile(env, oldAddress, newAddress, times.getValue(id), parts[i])) {
                            Outcome.MOVED -> mv++
                            Outcome.MERGED -> mg++
                            Outcome.NONE -> {}
                        }
                    }
                    Result(mv, mg)
                }
                moved += r.moved
                merged += r.merged
            }
            below = minId
            if (read < PAGE) break
        }
        return Result(moved, merged)
    }

    private enum class Outcome { NONE, MOVED, MERGED }

    private suspend fun refile(env: ImportEnv, oldAddress: String, newAddress: String, ts: Long, p: MmsSync.Parts): Outcome {
        val dao = env.db.messageDao()
        // The old-style copy: bogus conversation, provider time exactly, same text, imported (provider part or no image).
        val ghost = dao.outgoingBetween(oldAddress, ts, ts).firstOrNull {
            it.body == p.text && (it.imagePartUri == null || it.imagePartUri == p.imagePartUri || it.imagePartUri.startsWith(PROVIDER_PART))
        } ?: return Outcome.NONE

        val exact = dao.findByKey(newAddress, ts, ghost.body)
        if (exact != null) {
            if (exact.isIncoming) return Outcome.NONE // a received text with the same key is not this message: leave both alone
            keepRicher(dao, exact, ghost)
            dao.deleteMessage(ghost.id)
            return Outcome.MERGED
        }
        val twin = MmsSync.sentMatch(dao.outgoingBetween(newAddress, ts / 1000 * 1000, ts / 1000 * 1000 + 999), p)
        if (twin != null) {
            keepRicher(dao, twin, ghost)
            dao.deleteMessage(ghost.id)
            return Outcome.MERGED
        }
        dao.updateAddress(ghost.id, newAddress)
        return Outcome.MOVED
    }

    /** The surviving row keeps its own state; it only borrows a label if it has none. */
    private suspend fun keepRicher(dao: com.inboxiq.app.data.MessageDao, keeper: MessageEntity, dropped: MessageEntity) {
        if (keeper.label == MessageLabel.UNLABELED && dropped.label != MessageLabel.UNLABELED) {
            dao.updateLabel(keeper.id, dropped.label, dropped.labelConfidence)
        }
    }

    /** Step 2: local-vs-imported twins inside one conversation. Returns how many rows were removed. */
    suspend fun dedupe(env: ImportEnv): Int {
        val dao = env.db.messageDao()
        val rows = dao.outgoingWithAttachment()
        val groups = rows.groupBy { Triple(it.address, it.timestamp / 1000, it.body) }
        var removed = 0
        env.db.withTransaction {
            for (group in groups.values) {
                if (group.size < 2) continue
                val imported = group.filter { it.imagePartUri!!.startsWith(PROVIDER_PART) }
                val local = group.filterNot { it.imagePartUri!!.startsWith(PROVIDER_PART) }
                if (local.size != 1 || imported.isEmpty()) continue
                for (dup in imported) {
                    keepRicher(dao, local.single(), dup)
                    dao.deleteMessage(dup.id)
                    removed++
                }
            }
        }
        return removed
    }

    private const val PROVIDER_PART = "content://mms/part/"
}
