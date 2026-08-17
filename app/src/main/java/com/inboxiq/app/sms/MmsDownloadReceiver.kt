package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
        // resultCode is SmsManager's own outcome for this download — Activity.RESULT_OK (-1)
        // on success, one of SmsManager.MMS_ERROR_* otherwise. Logged raw since MMS_ERROR_*
        // constants aren't all resolvable to a name from a BroadcastReceiver's resultCode alone.
        Log.i(TAG, "download completed, resultCode=$resultCode")
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncMmsWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    companion object {
        private const val TAG = "MmsDownloadReceiver"
    }
}
