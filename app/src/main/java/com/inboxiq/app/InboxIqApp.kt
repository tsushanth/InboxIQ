package com.inboxiq.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.AutoHealWorker
import java.util.concurrent.TimeUnit

class InboxIqApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Once a day, retries any MMS sends still stuck after both local
        // strategies failed (see AutoHealWorker) — purely local, recovers
        // transient failures like a brief loss of signal.
        val autoHeal = PeriodicWorkRequestBuilder<AutoHealWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_heal_mms",
            ExistingPeriodicWorkPolicy.KEEP,
            autoHeal,
        )
    }
}
