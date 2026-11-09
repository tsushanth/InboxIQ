package com.inboxiq.app.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Delivery/result callback for messages this app sends via SmsManager. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(SmsSender.EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return

        val providerUri = intent.getStringExtra(SmsSender.EXTRA_PROVIDER_URI)
        val code = resultCode
        val ok = resultCode == Activity.RESULT_OK

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val finalOk = settle(AppDatabase.get(context).messageDao(), messageId, ok)
                // resultCode is Activity.RESULT_OK or one of SmsManager.RESULT_ERROR_*; kept as the provider's error_code.
                // A part that succeeded after another part failed still leaves the whole text failed.
                val errorCode = if (!ok) code else android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE
                OutgoingSmsSync.create(context).finish(messageId, providerUri, finalOk, errorCode)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /**
         * Records one result callback. A multipart text calls back once per part, and one failed part means the
         * recipient never got the whole text, so FAILED is sticky: a later OK does not undo it.
         * Returns whether the message is SENT after this callback.
         */
        internal suspend fun settle(dao: com.inboxiq.app.data.MessageDao, messageId: Long, ok: Boolean): Boolean {
            if (!ok) {
                dao.updateSendStatus(messageId, SendStatus.FAILED)
                return false
            }
            if (dao.markSentUnlessFailed(messageId) == 1) return true
            // No row to update: the user deleted the message while it was sending, and the radio result still stands.
            return dao.getById(messageId) == null
        }
    }
}
