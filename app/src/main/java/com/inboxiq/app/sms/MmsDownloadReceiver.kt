package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.SyncMmsWorker
import java.util.concurrent.TimeUnit

/**
 * Fires once SmsManager.downloadMultimediaMessage (see MmsDownloadWorker) actually completes —
 * success or failure. Either way, re-run the existing sync so InboxIQ picks up whatever is
 * now in content://mms (the image if the download succeeded; still nothing if it didn't, which
 * at least keeps behavior identical to before rather than silently swallowing a failure).
 */
class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncMmsWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS)
                .build(),
        )
    }
}
