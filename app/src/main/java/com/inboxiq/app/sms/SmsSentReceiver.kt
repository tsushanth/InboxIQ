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

        val status = if (resultCode == Activity.RESULT_OK) SendStatus.SENT else SendStatus.FAILED

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(context).messageDao().updateSendStatus(messageId, status)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
