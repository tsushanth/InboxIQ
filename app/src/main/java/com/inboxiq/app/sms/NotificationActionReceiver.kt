package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.ui.copyToClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the buttons on a message notification: Reply, Mark as read, Copy code. Not exported. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(MessageNotifier.EXTRA_ADDRESS) ?: return
        when (intent.action) {
            MessageNotifier.ACTION_COPY_CODE -> {
                val code = intent.getStringExtra(MessageNotifier.EXTRA_CODE) ?: return
                copyToClipboard(context, "Verification code", code, sensitive = true)
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                MessageNotifier.cancel(context, address)
            }
            MessageNotifier.ACTION_MARK_READ -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AppDatabase.get(context).messageDao().markThreadRead(address)
                        MessageNotifier.cancel(context, address)
                    } finally { pending.finish() }
                }
            }
            MessageNotifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifier.KEY_REPLY)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runCatching { SmsSender.send(context, address, text) }
                            .onSuccess { AppDatabase.get(context).messageDao().markThreadRead(address); MessageNotifier.cancel(context, address) }
                            .onFailure { Toast.makeText(context, "Couldn't send the reply. Open InboxIQ to try again.", Toast.LENGTH_LONG).show() }
                    } finally { pending.finish() }
                }
            }
        }
    }
}
