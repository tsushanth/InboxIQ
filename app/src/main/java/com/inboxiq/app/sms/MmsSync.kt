package com.inboxiq.app.sms

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.room.withTransaction
import com.inboxiq.app.data.MessageEntity

/**
 * Once the platform (as our default-handler role grants) auto-downloads an
 * incoming MMS, it lands in content://mms. We don't hand-parse WSP/PDU bytes
 * (that requires internal AOSP classes not in the public SDK) — instead we
 * read the already-decoded row + its parts back from the MMS provider, same
 * approach the historical SMS backfill takes for content://sms.
 *
 * Bulk history goes through [pageBelow]/[pageAbove] (bounded pages keyed on `_id`, driven by
 * [HistoryImport]); [syncRecent] is the small "anything new in the last while" refresh used after
 * a download completes, and is what heals a "[MMS message]" placeholder once its image arrives.
 */
object MmsSync {

    internal data class Parts(val text: String, val imagePartUri: String?)
    private data class Row(val id: Long, val timestamp: Long, val isIncoming: Boolean, val read: Boolean, val threadId: Long)

    /** Addresses of one provider MMS: the FROM header (type 137) and the first real recipient (type 151). */
    internal data class Addresses(val from: String?, val firstTo: String?)

    /** The provider's placeholder for "this device" in the FROM header of outgoing MMS. */
    private const val OWN_ADDRESS_TOKEN = "insert-address-token"

    private const val RECENT_PAGE = 100

    suspend fun syncRecent(context: Context, sinceMillis: Long) {
        val env = ImportEnv.from(context, batchParts = false)
        // MMS DATE is in seconds, not millis, unlike SMS.
        val sinceSeconds = (sinceMillis / 1000).toString()
        var below = Long.MAX_VALUE
        while (true) {
            val page = page(env, "${Telephony.Mms.DATE} >= ? AND ${Telephony.Mms._ID} < ?", arrayOf(sinceSeconds, below.toString()), "${Telephony.Mms._ID} DESC", RECENT_PAGE)
            if (page.read < RECENT_PAGE || page.minId >= below) break
            below = page.minId
        }
    }

    fun maxId(env: ImportEnv): Long? {
        val c = env.resolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, "${Telephony.Mms._ID} DESC")
            ?: throw ProviderUnavailableException("mms provider returned no cursor")
        c.use { return if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun count(env: ImportEnv): Int {
        val c = env.resolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, null)
            ?: throw ProviderUnavailableException("mms provider returned no cursor")
        c.use { return it.count }
    }

    suspend fun pageBelow(env: ImportEnv, below: Long, limit: Int): PageResult =
        page(env, "${Telephony.Mms._ID} < ?", arrayOf(below.toString()), "${Telephony.Mms._ID} DESC", limit)

    suspend fun pageAbove(env: ImportEnv, above: Long, limit: Int): PageResult =
        page(env, "${Telephony.Mms._ID} > ?", arrayOf(above.toString()), "${Telephony.Mms._ID} ASC", limit)

    private suspend fun page(env: ImportEnv, selection: String, args: Array<String>, order: String, limit: Int): PageResult {
        val rows = ArrayList<Row>(limit)
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ, Telephony.Mms.THREAD_ID)
        val cursor = env.resolver.query(Telephony.Mms.CONTENT_URI, projection, selection, args, order)
            ?: throw ProviderUnavailableException("mms provider returned no cursor")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val boxIdx = it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            val readIdx = it.getColumnIndexOrThrow(Telephony.Mms.READ)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            while (rows.size < limit && it.moveToNext()) {
                rows += Row(
                    it.getLong(idIdx), it.getLong(dateIdx) * 1000, it.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_INBOX,
                    read = it.getInt(readIdx) == 1, threadId = it.getLong(threadIdx),
                )
            }
        }
        if (rows.isEmpty()) return PageResult.EMPTY

        // Provider reads (outside any DB transaction): sender per message, parts for the whole page.
        val ids = rows.map { it.id }
        val addresses = rows.map { env.normalize(conversationAddress(env.resolver, it.id, it.isIncoming) ?: "unknown") }
        val parts = partsFor(env, ids)

        val db = env.db
        val dao = db.messageDao()
        val inserted = db.withTransaction {
            val pending = ArrayList<MessageEntity>()
            val pendingIndex = HashMap<Triple<String, Long, Boolean>, Int>()
            var updated = 0
            for ((i, row) in rows.withIndex()) {
                val address = addresses[i]
                val p = parts[i]
                if (row.isIncoming && db.blockedNumberDao().isBlocked(address)) continue
                if (env.tombstones.isSuppressed(address, row.timestamp, p.text)) continue

                // A prior sync can have run before this MMS's image part finished
                // downloading, leaving a "[MMS message]" placeholder row behind. Plain
                // insert-ignore can never fix that: the unique index is on
                // address+timestamp+body, so a later sync with the real image text is a
                // *different* body and inserts a second row instead of being recognized as
                // the same message. Look the row up by address+timestamp (ignoring body) and
                // update it in place once we have real content.
                val key = Triple(address, row.timestamp, row.isIncoming)
                val queued = pendingIndex[key]
                if (queued != null) {
                    if (p.imagePartUri != null && pending[queued].imagePartUri == null) {
                        pending[queued] = pending[queued].copy(body = p.text, imagePartUri = p.imagePartUri)
                    }
                    continue
                }
                val existing = if (row.isIncoming) {
                    dao.findByAddressTimestamp(address, row.timestamp, true)
                } else {
                    // MmsSender stamps its local row from the clock; the provider's `date` is whole seconds.
                    sentMatch(dao.outgoingBetween(address, row.timestamp, row.timestamp + 999), p)
                }
                if (existing != null) {
                    if (p.imagePartUri != null && existing.imagePartUri == null) {
                        dao.update(existing.copy(body = p.text, imagePartUri = p.imagePartUri))
                        updated++
                    }
                } else {
                    pendingIndex[key] = pending.size
                    pending += MessageEntity(
                        threadId = row.threadId,
                        address = address,
                        body = p.text,
                        timestamp = row.timestamp,
                        isIncoming = row.isIncoming,
                        // The provider's `read` flag decides; sent MMS are read by definition.
                        isRead = !row.isIncoming || row.read,
                        imagePartUri = p.imagePartUri,
                    )
                }
            }
            (if (pending.isEmpty()) 0 else dao.insertAll(pending).count { id -> id != -1L }) + updated
        }
        return PageResult(rows.size, ids.min(), ids.max(), inserted)
    }

    /**
     * A local row for the same sent MMS: same conversation, a clock-stamped time within the provider's
     * second, and either the same text or an attachment on both sides. A plain SMS sent in the same second
     * never matches (it has no attachment and a different body).
     */
    internal fun sentMatch(candidates: List<MessageEntity>, p: Parts): MessageEntity? =
        candidates.firstOrNull { it.body == p.text || (p.imagePartUri != null && it.imagePartUri != null) }

    /**
     * Which address a provider MMS belongs to in our model: the sender for received MMS, the recipient
     * for sent MMS (the FROM header of a sent MMS is this phone, not the conversation). Sent MMS with no
     * recipient row fall back to the FROM header, which is what earlier builds stored.
     */
    private fun conversationAddress(resolver: ContentResolver, mmsId: Long, incoming: Boolean): String? {
        val a = addresses(resolver, mmsId)
        return if (incoming) a.from else (a.firstTo ?: a.from)
    }

    internal fun addresses(resolver: ContentResolver, mmsId: Long): Addresses {
        var from: String? = null
        var to: String? = null
        val uri = Uri.parse("content://mms/$mmsId/addr")
        resolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow("address")
            val typeIdx = cursor.getColumnIndexOrThrow("type")
            while (cursor.moveToNext()) {
                // 137 = PDU_HEADERS_FROM, 151 = PDU_HEADERS_TO in the MMS spec
                when (cursor.getInt(typeIdx)) {
                    137 -> if (from == null) from = cursor.getString(addressIdx)
                    151 -> if (to == null) to = cursor.getString(addressIdx)?.takeIf { it.isNotBlank() && it != OWN_ADDRESS_TOKEN }
                }
            }
        }
        return Addresses(from, to)
    }

    /** Text and first image of each given provider MMS (page-batched, with the per-message fallback). */
    internal fun partsFor(env: ImportEnv, ids: List<Long>): List<Parts> {
        val byId = (if (env.batchParts) batchParts(env.resolver, ids) else null) ?: HashMap()
        return ids.map { byId[it] ?: messageParts(env.resolver, it) }
    }

    /**
     * One query for a whole page of messages instead of one per message. Returns null if the
     * provider rejects it, in which case callers fall back to [messageParts] per message.
     */
    private fun batchParts(resolver: ContentResolver, ids: List<Long>): Map<Long, Parts>? {
        val cursor = try {
            resolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "mid", "ct", "text"),
                "mid IN (${ids.joinToString(",")})",
                null,
                "_id ASC",
            )
        } catch (e: SecurityException) {
            throw e
        } catch (e: RuntimeException) {
            null
        } ?: return null

        val texts = HashMap<Long, MutableList<String>>()
        val images = HashMap<Long, String>()
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("_id")
            val midIdx = it.getColumnIndexOrThrow("mid")
            val ctIdx = it.getColumnIndexOrThrow("ct")
            val textIdx = it.getColumnIndexOrThrow("text")
            while (it.moveToNext()) {
                val mid = it.getLong(midIdx)
                val contentType = it.getString(ctIdx)
                when {
                    contentType == "text/plain" -> it.getString(textIdx)?.let { t -> texts.getOrPut(mid) { mutableListOf() }.add(t) }
                    contentType?.startsWith("image/") == true && mid !in images -> images[mid] = "content://mms/part/${it.getLong(idIdx)}"
                }
            }
        }
        return ids.associateWith { assemble(texts[it].orEmpty(), images[it]) }
    }

    private fun messageParts(resolver: ContentResolver, mmsId: Long): Parts {
        val uri = Uri.parse("content://mms/$mmsId/part")
        val textParts = mutableListOf<String>()
        var imagePartUri: String? = null

        resolver.query(uri, arrayOf("_id", "ct", "text"), null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            val ctIdx = cursor.getColumnIndexOrThrow("ct")
            val textIdx = cursor.getColumnIndexOrThrow("text")
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(ctIdx)
                when {
                    contentType == "text/plain" -> cursor.getString(textIdx)?.let { textParts.add(it) }
                    contentType?.startsWith("image/") == true && imagePartUri == null -> {
                        // Only keep the first image part per message for v1 — most MMS have one.
                        imagePartUri = "content://mms/part/${cursor.getLong(idIdx)}"
                    }
                }
            }
        }
        return assemble(textParts, imagePartUri)
    }

    private fun assemble(textParts: List<String>, imagePartUri: String?): Parts {
        val text = textParts.joinToString("\n").ifBlank {
            if (imagePartUri != null) "" else "[MMS message]"
        }
        return Parts(text, imagePartUri)
    }
}
