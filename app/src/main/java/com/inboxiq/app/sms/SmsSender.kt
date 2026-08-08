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

    suspend fun send(context: Context, address: String, body: String) {
        val dao = AppDatabase.get(context).messageDao()
        val id = dao.insert(
            MessageEntity(
                threadId = 0,
                address = address,
                body = body,
                timestamp = System.currentTimeMillis(),
                isIncoming = false,
                label = MessageLabel.PERSONAL, // outgoing messages skip the triage pipeline
                sendStatus = SendStatus.PENDING,
            ),
        )

        val sentIntent = Intent(context, SmsSentReceiver::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            sentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val smsManager = context.getSystemService(SmsManager::class.java)
        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(pendingIntent) } }
            smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
        } else {
            smsManager.sendTextMessage(address, null, body, pendingIntent, null)
        }
    }
}
