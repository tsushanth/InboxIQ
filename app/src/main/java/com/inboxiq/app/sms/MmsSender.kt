package com.inboxiq.app.sms

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction

/**
 * Outgoing MMS path (image attachments). Uses Fossify's maintained fork of
 * klinker's PDU encoder (see build.gradle.kts) since the public Android SDK
 * has no API to build an MMS PDU itself. Settings.setUseSystemSending(true)
 * lets the OS resolve the carrier's MMSC/APN for us — only available once
 * this app holds the default-SMS-handler role, which InboxIQ requires anyway.
 */
object MmsSender {

    suspend fun send(context: Context, address: String, body: String, imageUri: Uri) {
        val dao = AppDatabase.get(context).messageDao()
        val id = dao.insert(
            MessageEntity(
                threadId = 0,
                address = address,
                body = body,
                timestamp = System.currentTimeMillis(),
                isIncoming = false,
                label = MessageLabel.PERSONAL,
                sendStatus = SendStatus.PENDING,
                imagePartUri = imageUri.toString(),
            ),
        )

        try {
            val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: throw IllegalStateException("Could not decode picked image")

            val settings = Settings().apply { setUseSystemSending(true) }
            val transaction = Transaction(context, settings)
            val message = Message(body, address, bitmap)
            transaction.sendNewMessage(message)
            // Real delivery confirmation arrives async via MmsSentReceiver (see AndroidManifest);
            // this just confirms the PDU was handed off to the OS without throwing.
            dao.updateSendStatus(id, SendStatus.SENT)
        } catch (e: Exception) {
            dao.updateSendStatus(id, SendStatus.FAILED)
        }
    }
}
