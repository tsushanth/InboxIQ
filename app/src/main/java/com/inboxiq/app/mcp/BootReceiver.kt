package com.inboxiq.app.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Restarts the relay connection after a device reboot. START_STICKY on RelayForegroundService
 * only protects against the OS killing the running process — a full reboot kills everything, and
 * without this the connection stayed dark until the user happened to reopen the app themselves
 * (confirmed live: this is exactly what happened after this phone's last restart).
 *
 * Does NOT start the service directly — confirmed live that calling startForegroundService()
 * straight out of this receiver crashes with ForegroundServiceStartNotAllowedException, since
 * the dataSync FGS type isn't on the small allowlist of types Android permits starting directly
 * from BOOT_COMPLETED. See RelayStartWorker for why routing through WorkManager fixes this.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!RelayConfig.isEnabled(context)) return
        // Confirmed live: the BOOT_COMPLETED temp-allowlist window that permits starting a
        // foreground service at all lasts exactly 20s (visible in ActivityManager's own log as
        // "reasonCode:BOOT_COMPLETED,duration:20000") — a 10s WorkManager delay still landed
        // inside it and hit the exact same ForegroundServiceStartNotAllowedException as calling
        // startForegroundService() directly. 45s comfortably clears that window.
        val request = OneTimeWorkRequestBuilder<RelayStartWorker>()
            .setInitialDelay(45, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
