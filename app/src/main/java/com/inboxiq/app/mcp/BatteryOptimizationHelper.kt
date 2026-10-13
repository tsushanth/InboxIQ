package com.inboxiq.app.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Doze/App-Standby exemption for the relay connection. RelayForegroundService holds a
 * WAKE_LOCK while its WebSocket is open, but that alone doesn't stop Doze from tearing the
 * process down in the background — confirmed on a real device that InboxIQ is absent from
 * `adb shell dumpsys deviceidle whitelist` by default. Without this exemption the relay
 * silently stops working whenever the app is backgrounded, with no error surfaced anywhere.
 *
 * Deliberately not auto-requested on app launch: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS throws
 * up a system dialog, and firing that unprompted on every cold start is the kind of thing that
 * gets an app flagged in Play review and annoys users who haven't even turned the relay on
 * yet. Instead this is surfaced as a banner in the relay settings section (CloudConnectSection)
 * so the request is tied to the user's own decision to enable the relay.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Launches the direct "exempt this app" system dialog. Some OEM ROMs (custom battery
     * managers on top of AOSP) don't handle the direct-request intent, so this falls back to
     * the general battery-optimization list where the user can find InboxIQ and exempt it
     * manually.
     */
    fun requestExemption(context: Context) {
        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        try {
            context.startActivity(directIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct battery-exemption request not handled, falling back to settings list", e)
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.w(TAG, "Battery-optimization settings list also unavailable", e2)
            }
        }
    }
}
