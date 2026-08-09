package com.inboxiq.app.sms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import java.io.File

/** Known send strategies — tried locally, in order, on every send. Nothing external decides between them. */
private enum class MmsStrategy {
    SYSTEM_DEFAULT,
    RESIZED_IMAGE,
}

/**
 * Outgoing MMS path (image attachments). Uses Fossify's maintained fork of
 * klinker's PDU encoder (see build.gradle.kts) since the public Android SDK
 * has no API to build an MMS PDU itself. Settings.setUseSystemSending(true)
 * lets the OS resolve the carrier's MMSC/APN for us — only available once
 * this app holds the default-SMS-handler role, which InboxIQ requires anyway.
 *
 * Fully on-device: if the primary attempt fails, a resized-image variant is
 * tried immediately as a local fallback — no network call, no data ever
 * leaves the phone. A failed send can be retried later (manually, or via
 * AutoHealWorker) purely to recover from a transient carrier hiccup.
 */
object MmsSender {

    suspend fun send(context: Context, address: String, body: String, imageUri: Uri) {
        val dao = AppDatabase.get(context).messageDao()

        // The system Photo Picker's read grant on imageUri is short-lived and won't
        // survive a later retry (confirmed live: retry() crashed with a
        // SecurityException reading a picker uri from an earlier send). Copy the
        // bytes into our own storage immediately and use that stable uri everywhere.
        val localUri = persistLocally(context, imageUri) ?: run {
            dao.insert(
                MessageEntity(
                    threadId = 0,
                    address = address,
                    body = body,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = false,
                    label = MessageLabel.PERSONAL,
                    sendStatus = SendStatus.FAILED,
                    awaitingAutoHeal = true,
                ),
            )
            return
        }

        val id = dao.insert(
            MessageEntity(
                threadId = 0,
                address = address,
                body = body,
                timestamp = System.currentTimeMillis(),
                isIncoming = false,
                label = MessageLabel.PERSONAL,
                sendStatus = SendStatus.PENDING,
                imagePartUri = localUri.toString(),
            ),
        )

        val bitmap = context.contentResolver.openInputStream(localUri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (bitmap == null) {
            dao.updateSendStatus(id, SendStatus.FAILED)
            dao.setAwaitingAutoHeal(id, true)
            return
        }

        val succeeded = attempt(context, address, body, bitmap, MmsStrategy.SYSTEM_DEFAULT) ||
            attempt(context, address, body, bitmap, MmsStrategy.RESIZED_IMAGE)

        if (succeeded) {
            dao.updateSendStatus(id, SendStatus.SENT)
        } else {
            dao.updateSendStatus(id, SendStatus.FAILED)
            dao.setAwaitingAutoHeal(id, true)
        }
    }

    /** After this many failed retries (~5 days at the daily worker cadence), stop retrying and tell the user it's permanent. */
    const val MAX_AUTO_HEAL_RETRIES = 5

    /**
     * Retries a previously-failed MMS — used by AutoHealWorker and the manual
     * "Retry now" action. Purely local: recovers a transient failure (no
     * signal, carrier hiccup) by trying both known strategies again.
     * Returns true if the retry succeeded.
     */
    suspend fun retry(context: Context, message: MessageEntity): Boolean {
        val dao = AppDatabase.get(context).messageDao()
        val imageUri = message.imagePartUri?.let { Uri.parse(it) } ?: run {
            dao.setAwaitingAutoHeal(message.id, false)
            return false
        }
        val bitmap = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: run {
            // Unreadable (e.g. a stale picker uri from before local persistence was added) —
            // this image can never be recovered, so stop retrying it instead of crashing.
            dao.setAwaitingAutoHeal(message.id, false)
            return false
        }

        val succeeded = attempt(context, message.address, message.body, bitmap, MmsStrategy.SYSTEM_DEFAULT) ||
            attempt(context, message.address, message.body, bitmap, MmsStrategy.RESIZED_IMAGE)

        if (succeeded) {
            dao.updateSendStatus(message.id, SendStatus.SENT)
            dao.setAwaitingAutoHeal(message.id, false)
        } else {
            dao.incrementAutoHealRetryCount(message.id)
            if (message.autoHealRetryCount + 1 >= MAX_AUTO_HEAL_RETRIES) {
                // Both known strategies still fail after repeated tries — nothing left
                // to switch to, so stop implying a retry might still fix it.
                dao.setAwaitingAutoHeal(message.id, false)
            }
        }
        return succeeded
    }

    /** Copies a picked image into app-private storage and returns a stable FileProvider uri for it. */
    private fun persistLocally(context: Context, sourceUri: Uri): Uri? {
        return try {
            val dir = File(context.filesDir, "mms_images").apply { mkdirs() }
            val destFile = File(dir, "${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        } catch (e: Exception) {
            null
        }
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
