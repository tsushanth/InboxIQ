package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
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

        val incoming = combine(messages)
        val rawAddress = incoming.rawAddress
        val body = incoming.body
        val pduTimestamp = incoming.pduTimestamp
        val serviceCenter = incoming.serviceCenter
        // Dual-SIM: the platform tags the delivering subscription on the intent.
        val subscriptionId = intent.getIntExtra("subscription", -1)

        // Becoming the default by any route delivers SMS_DELIVER to us; make sure history is imported.
        ImportTrigger.ensure(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Provider row first, then ours, keyed on what the provider holds (see IncomingSmsStore).
                val stored = IncomingSmsStore.create(context).store(rawAddress, body, pduTimestamp, serviceCenter, subscriptionId)
                    ?: return@launch
                val db = AppDatabase.get(context)
                val address = stored.address
                val id = stored.id
                val timestamp = stored.timestamp

                val labelForNotification = IncomingLabeler.resolve(
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

                val displayName = ContactResolver.displayNameFor(context, address)
                val muted = db.threadStateDao().isMuted(address, System.currentTimeMillis())
                MessageNotifier.notifyIncoming(context, address, displayName, body, labelForNotification, timestamp, muted)
            } catch (e: Exception) {
                // Never crash the receiver. If our DB failed after the provider write the text is safe in the
                // provider and the import recovers it; log the class only, never the message.
                android.util.Log.w("SmsDeliverReceiver", "incoming handling failed: ${e.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** One received text: all PDU parts of a multipart message joined into a single body and a single row. */
    data class Incoming(val rawAddress: String, val body: String, val pduTimestamp: Long, val serviceCenter: String?)

    companion object {
        internal fun combine(messages: Array<SmsMessage>): Incoming = Incoming(
            rawAddress = messages[0].originatingAddress ?: "unknown",
            body = messages.joinToString(separator = "") { it.messageBody ?: "" },
            pduTimestamp = messages[0].timestampMillis,
            serviceCenter = messages[0].serviceCenterAddress,
        )
    }
}
