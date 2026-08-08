package com.inboxiq.app.sms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction

/** Known send strategies — a closed set the config backend can switch between per device+carrier. */
private enum class MmsStrategy(val id: String) {
    SYSTEM_DEFAULT("system_default"),
    RESIZED_IMAGE("resized_image"),
    ;

    companion object {
        fun fromId(id: String?) = entries.find { it.id == id } ?: SYSTEM_DEFAULT
    }
}

/**
 * Outgoing MMS path (image attachments). Uses Fossify's maintained fork of
 * klinker's PDU encoder (see build.gradle.kts) since the public Android SDK
 * has no API to build an MMS PDU itself. Settings.setUseSystemSending(true)
 * lets the OS resolve the carrier's MMSC/APN for us — only available once
 * this app holds the default-SMS-handler role, which InboxIQ requires anyway.
 *
 * Self-healing: checks inboxiq-config for a known-good strategy for this
 * device+carrier before sending, falls back to RESIZED_IMAGE locally if the
 * primary attempt throws, and reports the outcome either way. See
 * MmsConfigApi/DeviceFingerprint — only device model + carrier + strategy id
 * + success are ever sent, never message content or address.
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

        val fingerprint = DeviceFingerprint.forDevice(context)
        val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (bitmap == null) {
            dao.updateSendStatus(id, SendStatus.FAILED)
            dao.setAwaitingAutoHeal(id, true)
            return
        }

        val remoteStrategy = MmsStrategy.fromId(MmsConfigApi.fetchStrategy(fingerprint))
        val (succeededStrategy, succeeded) = attempt(context, address, body, bitmap, remoteStrategy)
            .let { if (it) remoteStrategy to true else fallbackAttempt(context, address, body, bitmap, remoteStrategy) }

        MmsConfigApi.reportOutcome(fingerprint, succeededStrategy.id, succeeded)

        if (succeeded) {
            dao.updateSendStatus(id, SendStatus.SENT)
        } else {
            dao.updateSendStatus(id, SendStatus.FAILED)
            dao.setAwaitingAutoHeal(id, true)
        }
    }

    /**
     * Retries a previously-failed MMS that's awaiting a self-healing fix — used by
     * AutoHealWorker once inboxiq-config has a strategy for this device+carrier.
     * Returns true if the retry succeeded.
     */
    suspend fun retry(context: Context, message: MessageEntity): Boolean {
        val dao = AppDatabase.get(context).messageDao()
        val imageUri = message.imagePartUri?.let { Uri.parse(it) } ?: run {
            dao.setAwaitingAutoHeal(message.id, false)
            return false
        }
        val bitmap = context.contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: run {
            dao.setAwaitingAutoHeal(message.id, false)
            return false
        }

        val fingerprint = DeviceFingerprint.forDevice(context)
        val strategy = MmsStrategy.fromId(MmsConfigApi.fetchStrategy(fingerprint))
        val succeeded = attempt(context, message.address, message.body, bitmap, strategy)
        MmsConfigApi.reportOutcome(fingerprint, strategy.id, succeeded)

        if (succeeded) {
            dao.updateSendStatus(message.id, SendStatus.SENT)
            dao.setAwaitingAutoHeal(message.id, false)
        }
        return succeeded
    }

    /** Only tried if the primary (remote-config or default) strategy throws — one local fallback, not a chain. */
    private suspend fun fallbackAttempt(
        context: Context,
        address: String,
        body: String,
        bitmap: Bitmap,
        alreadyTried: MmsStrategy,
    ): Pair<MmsStrategy, Boolean> {
        val fallback = if (alreadyTried == MmsStrategy.SYSTEM_DEFAULT) MmsStrategy.RESIZED_IMAGE else MmsStrategy.SYSTEM_DEFAULT
        return fallback to attempt(context, address, body, bitmap, fallback)
    }

    private fun attempt(context: Context, address: String, body: String, bitmap: Bitmap, strategy: MmsStrategy): Boolean {
        return try {
            val settings = Settings().apply { setUseSystemSending(true) }
            val transaction = Transaction(context, settings)
            val payload = if (strategy == MmsStrategy.RESIZED_IMAGE) resize(bitmap) else bitmap
            val message = Message(body, address, payload)
            transaction.sendNewMessage(message)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Downsizes to fit common carrier MMS size caps (~300KB) — the most common real-world MMS send failure. */
    private fun resize(bitmap: Bitmap): Bitmap {
        val maxDimension = 1024
        val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }
}
