package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.worker.ClassifyMessagesWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives SMS_DELIVER, the intent the platform sends only to the current
 * default SMS app. Must persist the message synchronously-ish and return quickly.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val rawAddress = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis
        val address = PhoneNumberNormalizer.normalize(context, rawAddress)

        // Becoming the default by any route delivers SMS_DELIVER to us; make sure history is imported.
        ImportTrigger.ensure(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.get(context)
                if (db.blockedNumberDao().isBlocked(address)) return@launch

                val id = db.messageDao().insert(
                    MessageEntity(
                        threadId = 0, // resolved lazily by the classifier/backfill pass
                        address = address,
                        body = body,
                        timestamp = timestamp,
                        isIncoming = true,
                        isRead = false,
                    ),
                )

                val labelForNotification = if (id > 0) {
                    IncomingLabeler.resolve(
                        db = db,
                        address = address,
                        body = body,
                        timestamp = timestamp,
                        messageId = id,
                        model = IncomingLabeler.defaultModel(context),
                        enqueueClassifier = {
                            WorkManager.getInstance(context).enqueue(
                                OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build(),
                            )
                        },
                    )
                } else {
                    com.inboxiq.app.data.MessageLabel.UNLABELED
                }

                val displayName = ContactResolver.displayNameFor(context, address)
                val muted = db.threadStateDao().isMuted(address, System.currentTimeMillis())
                MessageNotifier.notifyIncoming(context, address, displayName, body, labelForNotification, timestamp, muted)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
