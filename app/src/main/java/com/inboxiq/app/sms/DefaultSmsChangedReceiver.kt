package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * The platform tells the old and new default SMS app when the default changes. This gets the
 * import going even if the user changed the default in Android Settings while InboxIQ's process
 * was not running or was in the background.
 */
class DefaultSmsChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED) return
        // The extra says whether we became the default; ensure() re-verifies against the role anyway.
        ImportTrigger.ensure(context)
    }
}
