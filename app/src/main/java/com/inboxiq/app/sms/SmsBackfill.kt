package com.inboxiq.app.sms

import android.content.Context
import android.provider.Telephony
import androidx.room.withTransaction
import com.inboxiq.app.data.MessageEntity

/**
 * Reads content://sms in bounded pages keyed on the provider's `_id` (see [ImportState]); the
 * orchestration (progress, resume, incremental runs) lives in [HistoryImport]. Each page is read
 * into a small list, the cursor is closed, and the rows go into Room in one transaction with a
 * single batch insert, so memory stays flat and Room invalidates observers once per page.
 */
object SmsBackfill {

    /** Compatibility entry point: runs the whole (resumable, incremental) history import. */
    suspend fun run(context: Context) {
        HistoryImport.run(ImportEnv.from(context), ImportState(context)) { true }
    }

    private val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
    )

    /** Highest `_id` in the provider, or null when it holds no SMS. */
    fun maxId(env: ImportEnv): Long? {
        val c = env.resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, "${Telephony.Sms._ID} DESC")
            ?: throw ProviderUnavailableException("sms provider returned no cursor")
        c.use { return if (it.moveToFirst()) it.getLong(0) else null }
    }

    fun count(env: ImportEnv): Int {
        val c = env.resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
            ?: throw ProviderUnavailableException("sms provider returned no cursor")
        c.use { return it.count }
    }

    /** Up to [limit] rows with `_id < [below]`, newest first. */
    suspend fun pageBelow(env: ImportEnv, below: Long, limit: Int): PageResult =
        page(env, "${Telephony.Sms._ID} < ?", below, "${Telephony.Sms._ID} DESC", limit)

    /** Up to [limit] rows with `_id > [above]`, oldest first (incremental). */
    suspend fun pageAbove(env: ImportEnv, above: Long, limit: Int): PageResult =
        page(env, "${Telephony.Sms._ID} > ?", above, "${Telephony.Sms._ID} ASC", limit)

    private suspend fun page(env: ImportEnv, selection: String, bound: Long, order: String, limit: Int): PageResult {
        val entities = ArrayList<MessageEntity>(limit)
        var read = 0
        var minId = Long.MAX_VALUE
        var maxId = Long.MIN_VALUE

        val cursor = env.resolver.query(Telephony.Sms.CONTENT_URI, projection, selection, arrayOf(bound.toString()), order)
            ?: throw ProviderUnavailableException("sms provider returned no cursor")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (read < limit && it.moveToNext()) {
                read++
                val id = it.getLong(idIdx)
                if (id < minId) minId = id
                if (id > maxId) maxId = id
                val address = env.normalize(it.getString(addressIdx) ?: "unknown")
                val body = it.getString(bodyIdx) ?: ""
                val timestamp = it.getLong(dateIdx)
                if (env.tombstones.isSuppressed(address, timestamp, body)) continue
                entities += MessageEntity(
                    threadId = it.getLong(threadIdx),
                    address = address,
                    body = body,
                    timestamp = timestamp,
                    isIncoming = it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                )
            }
        }
        if (read == 0) return PageResult.EMPTY

        val inserted = if (entities.isEmpty()) 0 else env.db.withTransaction {
            env.db.messageDao().insertAll(entities).count { id -> id != -1L }
        }
        return PageResult(read, minId, maxId, inserted)
    }
}
