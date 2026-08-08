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

        // Checks in with inboxiq-config once a day for any messages awaiting a
        // self-healing MMS fix (see AutoHealWorker) — cheap config poll, not
        // aggressive, since a fix landing "a few hours late" is fine.
        val autoHeal = PeriodicWorkRequestBuilder<AutoHealWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_heal_mms",
            ExistingPeriodicWorkPolicy.KEEP,
            autoHeal,
        )
    }
}
