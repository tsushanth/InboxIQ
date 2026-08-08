package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.SyncMmsWorker
import java.util.concurrent.TimeUnit

/**
 * Required receiver for default-SMS-handler eligibility (WAP_PUSH_DELIVER).
 * The platform handles the actual PDU download/decode once we hold the
 * default-handler role; our job is just to trigger a sync of content://mms
 * shortly after, since the download isn't guaranteed complete by the time
 * this broadcast is delivered.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncMmsWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build(),
        )
    }
}
