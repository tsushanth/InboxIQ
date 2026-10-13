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

    private data class Parts(val text: String, val imagePartUri: String?)
    private data class Row(val id: Long, val timestamp: Long, val isIncoming: Boolean)

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
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
        val cursor = env.resolver.query(Telephony.Mms.CONTENT_URI, projection, selection, args, order)
            ?: throw ProviderUnavailableException("mms provider returned no cursor")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val boxIdx = it.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            while (rows.size < limit && it.moveToNext()) {
                rows += Row(it.getLong(idIdx), it.getLong(dateIdx) * 1000, it.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_INBOX)
            }
        }
        if (rows.isEmpty()) return PageResult.EMPTY

        // Provider reads (outside any DB transaction): sender per message, parts for the whole page.
        val ids = rows.map { it.id }
        val addresses = rows.map { env.normalize(senderAddress(env.resolver, it.id) ?: "unknown") }
        val partsById = (if (env.batchParts) batchParts(env.resolver, ids) else null) ?: HashMap()
        val parts = rows.map { partsById[it.id] ?: messageParts(env.resolver, it.id) }

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
                val existing = dao.findByAddressTimestamp(address, row.timestamp, row.isIncoming)
                if (existing != null) {
                    if (p.imagePartUri != null && existing.imagePartUri == null) {
                        dao.update(existing.copy(body = p.text, imagePartUri = p.imagePartUri))
                        updated++
                    }
                } else {
                    pendingIndex[key] = pending.size
                    pending += MessageEntity(
                        threadId = 0,
                        address = address,
                        body = p.text,
                        timestamp = row.timestamp,
                        isIncoming = row.isIncoming,
                        isRead = !row.isIncoming, // incoming MMS starts unread; our own sent MMS doesn't need the concept
                        imagePartUri = p.imagePartUri,
                    )
                }
            }
            (if (pending.isEmpty()) 0 else dao.insertAll(pending).count { id -> id != -1L }) + updated
        }
        return PageResult(rows.size, ids.min(), ids.max(), inserted)
    }

    private fun senderAddress(resolver: ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        resolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                // type 137 = PDU_HEADERS_FROM in the MMS spec
                val type = cursor.getInt(cursor.getColumnIndexOrThrow("type"))
                if (type == 137) {
                    return cursor.getString(cursor.getColumnIndexOrThrow("address"))
                }
            }
        }
        return null
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
