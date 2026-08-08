package com.inboxiq.app.sms

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

/**
 * The only two fields ever sent to inboxiq-config — never message content,
 * address, or any other identifier. Carrier is the SIM's MCC+MNC (e.g.
 * "310260" for T-Mobile US), not a human-readable carrier name.
 */
data class DeviceFingerprint(val deviceModel: String, val carrier: String) {
    companion object {
        fun forDevice(context: Context): DeviceFingerprint {
            val model = "${Build.MANUFACTURER}_${Build.MODEL}".replace(" ", "_")
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrier = tm?.simOperator?.takeIf { it.isNotBlank() } ?: "unknown"
            return DeviceFingerprint(model, carrier)
        }
    }
}
