package com.inboxiq.app.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.launch

/**
 * Required for default-SMS-handler eligibility: handles RESPOND_VIA_MESSAGE
 * (e.g. "quick reply" from the phone/notification UI) without opening the app UI.
 */
class HeadlessSmsSendService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val data = intent?.data
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val address = data?.schemeSpecificPart

        if (address != null && !body.isNullOrEmpty()) {
            lifecycleScope.launch {
                SmsSender.send(applicationContext, address, body)
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
