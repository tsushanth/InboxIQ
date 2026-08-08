package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.ThreadLabelEntity
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

        val address = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

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

                if (id > 0) {
                    val cached = db.threadLabelDao().get(address)
                    // A high-precision heuristic (OTP code, "you've won" scam phrasing, etc.)
                    // on THIS message overrides a stale/wrong cached thread label — e.g. a
                    // spoofed sender number reusing an address we'd previously seen as benign.
                    // Only this message is corrected, not the address's older history, since
                    // we can't tell from one message whether the whole thread actually changed.
                    val heuristic = HeuristicRules.match(body)
                    when {
                        heuristic != null && heuristic.label != cached?.label -> {
                            db.threadLabelDao().upsert(
                                ThreadLabelEntity(address, heuristic.label, heuristic.confidence, timestamp),
                            )
                            db.messageDao().updateLabel(id, heuristic.label, heuristic.confidence)
                        }
                        cached != null -> {
                            db.messageDao().updateLabel(id, cached.label, cached.confidence)
                        }
                        else -> {
                            WorkManager.getInstance(context).enqueue(
                                OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build(),
                            )
                        }
                    }
                }

                val displayName = ContactResolver.displayNameFor(context, address)
                MessageNotifier.notifyIncoming(context, address, displayName, body)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
