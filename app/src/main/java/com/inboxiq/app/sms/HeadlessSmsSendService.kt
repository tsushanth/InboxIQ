package com.inboxiq.app.sms

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Required for default-SMS-handler eligibility: handles RESPOND_VIA_MESSAGE
 * (e.g. "quick reply" from the phone/notification UI) without opening the app UI.
 *
 * Nothing here may throw out of the coroutine: an uncaught exception in this service would take the
 * whole app process down (and a failed send would also leave the service running).
 */
class HeadlessSmsSendService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val recipients = RespondViaMessage.recipients(intent?.data)
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (recipients.isNotEmpty() && !body.isNullOrEmpty()) {
            lifecycleScope.launch {
                try {
                    // One text per recipient: the intent can carry several numbers, and sending the
                    // whole list as one address would go nowhere.
                    for (address in recipients) {
                        try {
                            SmsSender.send(applicationContext, address, body)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "respond-via-message send failed: ${e.javaClass.simpleName}")
                        }
                    }
                } finally {
                    stopSelf(startId)
                }
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private companion object { const val TAG = "HeadlessSmsSend" }
}

/** Parses the recipient list of a RESPOND_VIA_MESSAGE intent (`smsto:+1555...` or `sms:a,b`). */
object RespondViaMessage {
    /** Distinct, non-blank numbers from the URI's scheme-specific part; query and fragment are ignored. */
    fun recipients(data: Uri?): List<String> {
        val raw = data?.schemeSpecificPart ?: return emptyList()
        return raw.substringBefore('?').split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
