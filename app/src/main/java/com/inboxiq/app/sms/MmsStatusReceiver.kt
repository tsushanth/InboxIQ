package com.inboxiq.app.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * The real MMS send result. mmslib's own MmsSentReceiver is bypassed (MmsSender redirects the completion
 * broadcast here), so this receiver does what it did: it reads the platform's result code, flips the
 * provider row to "sent" so other SMS apps agree, and deletes the PDU temp file.
 *
 * The result code is the only authority on whether the carrier took the message. It used to be ignored in
 * favour of reading the provider row's msg_box back, but nothing in the app ever moved that row out of the
 * outbox (that was MmsSentReceiver's job), and on Android 17 the row can be missing altogether. A message the
 * carrier had accepted was therefore marked FAILED, shown as "Couldn't send, retrying", and re-sent by
 * AutoHealWorker, which means the recipient would get duplicates.
 */
class MmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localId = intent.getLongExtra(EXTRA_LOCAL_MESSAGE_ID, -1L)
        if (localId < 0) return
        val sent = resultCode == Activity.RESULT_OK
        val contentUriString = intent.getStringExtra(EXTRA_CONTENT_URI)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (sent) markProviderSent(context, contentUriString)
                filePath?.let { runCatching { File(it).delete() } }
                val dao = AppDatabase.get(context).messageDao()
                val message = dao.getById(localId)
                settle(dao, localId, sent)
                // Only a genuine recovery, not every first-time send, deserves the "Fixed!" notification.
                if (sent && message?.sendStatus == SendStatus.FAILED) {
                    val displayName = com.inboxiq.app.data.ContactResolver.displayNameFor(context, message.address)
                    MessageNotifier.notifyAutoHealed(context, message.address, displayName)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Moves the provider row out of the outbox, as mmslib's MmsSentReceiver would. Best effort: the row may not exist. */
    private fun markProviderSent(context: Context, mmsUri: String?) {
        if (mmsUri.isNullOrEmpty()) return
        runCatching {
            val values = ContentValues(1).apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT) }
            context.contentResolver.update(Uri.parse(mmsUri), values, null, null)
        }
    }

    companion object {
        const val EXTRA_LOCAL_MESSAGE_ID = "inboxiq_local_message_id"
        const val EXTRA_CONTENT_URI = "content_uri" // matches mmslib's MmsSentReceiver.EXTRA_CONTENT_URI
        const val EXTRA_FILE_PATH = "file_path" // matches mmslib's MmsSentReceiver.EXTRA_FILE_PATH
        const val ACTION_MMS_SENT = "com.klinker.android.messaging.MMS_SENT"

        /** Records the platform's verdict. OK clears any pending auto-retry; anything else queues one. */
        internal suspend fun settle(dao: com.inboxiq.app.data.MessageDao, localId: Long, sent: Boolean) {
            if (sent) {
                dao.updateSendStatus(localId, SendStatus.SENT)
                dao.setAwaitingAutoHeal(localId, false)
            } else {
                dao.updateSendStatus(localId, SendStatus.FAILED)
                dao.setAwaitingAutoHeal(localId, true)
            }
        }
    }
}
