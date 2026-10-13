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
        val status = if (resultCode == Activity.RESULT_OK) SendStatus.SENT else SendStatus.FAILED

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(context).messageDao().updateSendStatus(messageId, status)
                // resultCode is Activity.RESULT_OK or one of SmsManager.RESULT_ERROR_*; kept as the provider's error_code.
                OutgoingSmsSync.create(context).finish(messageId, providerUri, status == SendStatus.SENT, code)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
