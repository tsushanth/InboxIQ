package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.MmsDownloadWorker
import com.inboxiq.app.worker.SyncMmsWorker
import java.util.concurrent.TimeUnit

/**
 * Required receiver for default-SMS-handler eligibility (WAP_PUSH_DELIVER).
 *
 * Confirmed live that the OS does not reliably auto-download MMS content just because this
 * app holds the default-handler role — a real incoming photo left its notification-indication
 * row sitting undownloaded in content://mms indefinitely, with SyncMmsWorker correctly finding
 * nothing to import since there genuinely was nothing there yet. MmsDownloadWorker explicitly
 * triggers the download via SmsManager.downloadMultimediaMessage; SyncMmsWorker still runs
 * afterward as a backstop for any carrier/network where auto-download does work on its own.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<MmsDownloadWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build(),
        )
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncMmsWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build(),
        )
    }
}
