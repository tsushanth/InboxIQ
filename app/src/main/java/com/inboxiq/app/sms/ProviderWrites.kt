package com.inboxiq.app.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.worker.ProviderWriteRetryWorker
import java.util.concurrent.TimeUnit

/**
 * Keeps Android's shared SMS provider in step with InboxIQ's own database. A default SMS app must
 * write what it receives and sends, otherwise those texts exist only in our private DB and vanish
 * if the user switches apps or our data is cleared. See docs/PROVIDER_WRITES.md.
 *
 * Duplicate safety rests on one rule: the (address, timestamp, body) our row is keyed on is derived
 * from the provider row as read back after the insert, using the same normaliser and the same
 * fields [SmsBackfill] reads. A later import therefore computes the identical key and its
 * IGNORE insert is a no-op.
 */
class IncomingSmsStore(
    private val db: AppDatabase,
    private val provider: ProviderSms,
    private val queue: ProviderWriteQueue,
    private val normalize: (String) -> String,
    private val scheduleRetry: () -> Unit,
) {
    /** What was persisted. [id] is our row (or the existing equal row when this was a duplicate delivery). */
    data class Stored(val id: Long, val address: String, val body: String, val timestamp: Long)

    /** Returns null when the sender is blocked (nothing is saved anywhere, as before) or the row could not be stored in our DB. */
    suspend fun store(rawAddress: String, body: String, pduTimestamp: Long, serviceCenter: String?, subscriptionId: Int, now: Long = System.currentTimeMillis()): Stored? {
        val ts = if (pduTimestamp > 0) pduTimestamp else now
        val fallbackAddress = normalize(rawAddress)
        if (db.blockedNumberDao().isBlocked(fallbackAddress)) return null

        // 1. Provider first: if the process dies after this line the import recovers the row from the provider.
        val row = try {
            provider.insertInbox(rawAddress, body, ts, pduTimestamp, read = false, serviceCenter = serviceCenter, subscriptionId = subscriptionId)
        } catch (e: Exception) {
            Log.w(TAG, "provider insert failed: ${e.javaClass.simpleName}")
            null
        }

        // 2. Our row, keyed on what the provider holds (or on our own values when it refused).
        val entity = MessageEntity(
            threadId = row?.threadId ?: 0,
            address = row?.let { normalize(it.address) } ?: fallbackAddress,
            body = row?.body ?: body,
            timestamp = row?.date ?: ts,
            isIncoming = true,
            isRead = false,
        )
        var id = db.messageDao().insert(entity)
        if (id == -1L) {
            // Either the import saw the provider row in the gap between steps 1 and 2 (it stores the row with the provider's read flag, 0 here, so this is normally a no-op),
            // or this is a genuine duplicate. Reuse the existing row; if we just created the provider row, it is the former.
            val existing = db.messageDao().findByKey(entity.address, entity.timestamp, entity.body) ?: return null
            if (row != null && existing.isRead && existing.isIncoming) db.messageDao().markUnread(existing.id)
            id = existing.id
        }
        if (row == null && queue.add(id)) scheduleRetry()
        return Stored(id, entity.address, entity.body, entity.timestamp)
    }

    companion object {
        private const val TAG = "IncomingSmsStore"

        fun create(context: Context): IncomingSmsStore {
            val app = context.applicationContext
            return IncomingSmsStore(
                db = AppDatabase.get(app),
                provider = ProviderSms(app.contentResolver),
                queue = ProviderWriteQueue(app),
                normalize = { PhoneNumberNormalizer.normalize(app, it) },
                scheduleRetry = { ProviderWriteScheduler.enqueue(app) },
            )
        }
    }
}

/** Outgoing side: called by [SmsSender] and [SmsSentReceiver]. All failures are swallowed into the retry queue. */
class OutgoingSmsSync(
    private val provider: ProviderSms,
    private val queue: ProviderWriteQueue,
    private val scheduleRetry: () -> Unit,
    private val isDefault: () -> Boolean,
) {
    /** Writes the message to the outbox before it is handed to SmsManager. Returns the provider row uri string, or null. */
    fun begin(messageId: Long, address: String, body: String, timestamp: Long): String? {
        return try {
            provider.insertOutgoing(address, body, timestamp, Telephony.Sms.MESSAGE_TYPE_OUTBOX).uri.toString()
        } catch (e: Exception) {
            fail(messageId, e)
            null
        }
    }

    /** Result from the radio: outbox -> sent or failed. [providerUri] is null when [begin] could not write the row. */
    fun finish(messageId: Long, providerUri: String?, ok: Boolean, errorCode: Int) {
        val type = if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        if (providerUri == null) {
            // Nothing to update: let the retry write the row in its final state (it reads the status from our DB).
            if (queue.add(messageId)) scheduleRetry()
            return
        }
        try {
            // A row someone else deleted is not resurrected: update() simply matches nothing.
            provider.setType(android.net.Uri.parse(providerUri), type, if (ok) null else errorCode)
        } catch (e: Exception) {
            fail(messageId, e)
        }
    }

    private fun fail(messageId: Long, e: Exception) {
        Log.w(TAG, "provider write failed: ${e.javaClass.simpleName}")
        // Not the default app (e.g. role lost mid-send): the platform records such sends itself, so do not queue.
        if (e is SecurityException && !isDefault()) return
        if (queue.add(messageId)) scheduleRetry()
    }

    companion object {
        private const val TAG = "OutgoingSmsSync"

        fun create(context: Context): OutgoingSmsSync {
            val app = context.applicationContext
            return OutgoingSmsSync(
                provider = ProviderSms(app.contentResolver),
                queue = ProviderWriteQueue(app),
                scheduleRetry = { ProviderWriteScheduler.enqueue(app) },
                isDefault = { DefaultSmsRole.isDefault(app) },
            )
        }
    }
}

/**
 * Drains [ProviderWriteQueue]: for each queued row of ours, make sure the provider has it in the
 * right state. Idempotent (looks for an existing provider row first), bounded (batch size and
 * per-entry attempts) and resumable (each entry is removed only after it succeeded).
 */
class ProviderWriteRetry(
    private val db: AppDatabase,
    private val provider: ProviderSms,
    private val queue: ProviderWriteQueue,
    private val normalize: (String) -> String,
    private val isDefault: () -> Boolean,
) {
    enum class Outcome { DONE, NOT_DEFAULT, RETRY_LATER }

    suspend fun run(): Outcome {
        if (queue.isEmpty()) return Outcome.DONE
        // The provider rejects writes unless we are the default app; waiting costs nothing and burns no attempts.
        if (!isDefault()) return Outcome.NOT_DEFAULT
        var failed = false
        for (id in queue.ids().take(BATCH)) {
            val m = db.messageDao().getById(id)
            if (m == null || m.imagePartUri != null) { queue.remove(id); continue } // deleted by the user, or MMS (never ours to write here)
            try {
                ensure(m)
                queue.remove(id)
            } catch (e: SecurityException) {
                return Outcome.NOT_DEFAULT
            } catch (e: Exception) {
                Log.w(TAG, "retry failed: ${e.javaClass.simpleName}")
                queue.recordFailure(id)
                failed = true
            }
        }
        return if (failed) Outcome.RETRY_LATER else Outcome.DONE
    }

    private fun ensure(m: MessageEntity) {
        val desiredType = when {
            m.isIncoming -> Telephony.Sms.MESSAGE_TYPE_INBOX
            m.sendStatus == SendStatus.SENT -> Telephony.Sms.MESSAGE_TYPE_SENT
            m.sendStatus == SendStatus.FAILED -> Telephony.Sms.MESSAGE_TYPE_FAILED
            else -> Telephony.Sms.MESSAGE_TYPE_OUTBOX
        }
        val existing = provider.findByDateAndBody(m.timestamp, m.body).firstOrNull { normalize(it.address) == m.address }
        if (existing != null) {
            if (!m.isIncoming && existing.type != desiredType) provider.setType(existing.uri, desiredType)
            return
        }
        if (m.isIncoming) {
            provider.insertInbox(m.address, m.body, m.timestamp, m.timestamp, read = m.isRead, serviceCenter = null, subscriptionId = -1)
        } else {
            provider.insertOutgoing(m.address, m.body, m.timestamp, desiredType)
        }
    }

    companion object {
        private const val TAG = "ProviderWriteRetry"
        const val BATCH = 200

        fun create(context: Context): ProviderWriteRetry {
            val app = context.applicationContext
            return ProviderWriteRetry(
                db = AppDatabase.get(app),
                provider = ProviderSms(app.contentResolver),
                queue = ProviderWriteQueue(app),
                normalize = { PhoneNumberNormalizer.normalize(app, it) },
                isDefault = { DefaultSmsRole.isDefault(app) },
            )
        }
    }
}

object ProviderWriteScheduler {
    const val WORK_NAME = "provider_write_retry"

    fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProviderWriteRetryWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    /** Cheap: one preferences read. Called on app start and after becoming default so a pending list is never forgotten. */
    fun enqueueIfPending(context: Context) {
        if (!ProviderWriteQueue(context).isEmpty()) enqueue(context)
    }
}

/** Mirrors the user's explicit read actions into the provider; best effort, never throws. */
class ProviderReadSync(private val provider: ProviderSms) : com.inboxiq.app.data.ReadSync {
    override fun markThreadsRead(threadIds: Collection<Long>) {
        if (threadIds.isEmpty()) return
        try { provider.markThreadsRead(threadIds) } catch (e: Exception) { Log.w(TAG, "read sync failed: ${e.javaClass.simpleName}") }
    }

    override fun markAllRead() {
        try { provider.markAllRead() } catch (e: Exception) { Log.w(TAG, "read sync failed: ${e.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "ProviderReadSync"
        fun create(context: Context) = ProviderReadSync(ProviderSms(context.applicationContext.contentResolver))
    }
}
