package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The real MMS delivery result — mmslib's own MmsSentReceiver fires synchronously-too-early
 * from MmsSender's point of view (that call only confirms the send was handed off, not that
 * it reached the carrier). We redirect that completion broadcast here via
 * Transaction.setExplicitBroadcastForSentMms, then read the same platform MMS row
 * (content://mms, msg_box column) that any other SMS app — including the one a user might
 * switch back to — reads as the authoritative status, so both agree.
 */
class MmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localId = intent.getLongExtra(EXTRA_LOCAL_MESSAGE_ID, -1L)
        if (localId < 0) return
        val contentUriString = intent.getStringExtra(EXTRA_CONTENT_URI)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sent = contentUriString?.let { isActuallySent(context, Uri.parse(it)) } ?: false
                val dao = AppDatabase.get(context).messageDao()
                if (sent) {
                    dao.updateSendStatus(localId, SendStatus.SENT)
                } else {
                    dao.updateSendStatus(localId, SendStatus.FAILED)
                    dao.setAwaitingAutoHeal(localId, true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isActuallySent(context: Context, mmsUri: Uri): Boolean {
        return context.contentResolver.query(mmsUri, arrayOf(Telephony.Mms.MESSAGE_BOX), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)) == Telephony.Mms.MESSAGE_BOX_SENT
        } ?: false
    }

    companion object {
        const val EXTRA_LOCAL_MESSAGE_ID = "inboxiq_local_message_id"
        const val EXTRA_CONTENT_URI = "content_uri" // matches mmslib's MmsSentReceiver.EXTRA_CONTENT_URI
        const val ACTION_MMS_SENT = "com.klinker.android.messaging.MMS_SENT"
    }
}
