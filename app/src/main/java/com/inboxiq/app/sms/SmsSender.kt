package com.inboxiq.app.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus

/** Outgoing-message path: persists the message, then hands off to SmsManager. */
object SmsSender {

    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_PROVIDER_URI = "provider_uri"

    /** A stored outgoing row that has not been handed to the radio yet. */
    internal data class Prepared(val id: Long, val address: String, val body: String, val timestamp: Long)

    /** Returns the id of the stored outgoing row (<= 0 when the insert was ignored). */
    suspend fun send(context: Context, rawAddress: String, body: String): Long =
        transmit(context, prepare(context, rawAddress, body))

    /**
     * Step 1: persist the outgoing row as PENDING. Split from [transmit] so a scheduled send can claim its queue row
     * and record the outgoing message in one database transaction: if the process dies in between, the message is
     * either still queued or visible in the thread, never silently gone.
     */
    internal suspend fun prepare(context: Context, rawAddress: String, body: String): Prepared {
        val dao = AppDatabase.get(context).messageDao()
        // Same normalisation the history import applies, so the row we store and the provider row it
        // later re-reads share one (address, timestamp, body) key. Idempotent on already-normal numbers.
        val address = PhoneNumberNormalizer.normalize(context, rawAddress)
        val timestamp = System.currentTimeMillis()
        val id = dao.insert(
            MessageEntity(
                threadId = 0,
                address = address,
                body = body,
                timestamp = timestamp,
                isIncoming = false,
                label = MessageLabel.PERSONAL, // outgoing messages skip the triage pipeline
                sendStatus = SendStatus.PENDING,
            ),
        )
        return Prepared(id, address, body, timestamp)
    }

    /** Step 2: hand a [prepare]d row to the radio. */
    internal suspend fun transmit(context: Context, p: Prepared): Long {
        val (id, address, body, timestamp) = p
        val dao = AppDatabase.get(context).messageDao()
        val sync = OutgoingSmsSync.create(context)
        // A default SMS app owns the provider's outbox -> sent transition. Failure is queued, never fatal.
        val providerUri = if (id > 0) sync.begin(id, address, body, timestamp) else null

        val sentIntent = Intent(context, SmsSentReceiver::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, id)
            providerUri?.let { putExtra(EXTRA_PROVIDER_URI, it) }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            sentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            // The SIM the conversation is on (dual-SIM); null keeps the phone's default SIM.
            val smsManager = SendSubscription.manager(context, SendSubscription.forAddress(context, address))
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(pendingIntent) } }
                smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(address, null, body, pendingIntent, null)
            }
        } catch (e: Exception) {
            // Handing off to the radio failed outright: no result broadcast will ever come, so settle the
            // row (ours and the provider's outbox copy) as failed instead of leaving it pending forever.
            if (id > 0) {
                dao.updateSendStatus(id, SendStatus.FAILED)
                sync.finish(id, providerUri, ok = false, errorCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE)
            }
            throw e
        }
        return id
    }
}
