package com.inboxiq.app.mcp

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Read-only battery-optimization status plus a link to the system's battery-optimization list.
 *
 * Deliberately does NOT use ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / the matching
 * permission: Google Play restricts that permission to apps whose core function breaks without
 * it, and an optional AI relay does not qualify (incoming SMS reach the default SMS app through
 * a manifest receiver regardless of Doze). Users who want the connection to stay up in the
 * background can exempt InboxIQ themselves from the system list this opens.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system battery-optimization list (needs no special permission). */
    fun requestExemption(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Battery-optimization settings list unavailable", e)
        }
    }
}
