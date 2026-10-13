package com.inboxiq.app.mcp

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Starts RelayForegroundService on WorkManager's clock instead of directly from BootReceiver.
 *
 * Confirmed live: calling startForegroundService() straight out of onReceive(BOOT_COMPLETED)
 * crashes with ForegroundServiceStartNotAllowedException — "FGS type dataSync not allowed to
 * start from BOOT_COMPLETED!". Only a specific allowlist of foreground service types (location,
 * camera, microphone, etc.) is exempted to start directly from that broadcast; dataSync isn't
 * one of them. Routing the actual start through a WorkManager job — the standard, documented
 * workaround for this exact restriction — starts it from a context the OS does allow.
 */
class RelayStartWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!RelayConfig.isEnabled(applicationContext)) return Result.success()
        applicationContext.startForegroundService(Intent(applicationContext, RelayForegroundService::class.java))
        return Result.success()
    }
}
