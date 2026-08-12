package com.inboxiq.app.mcp

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/**
 * Local-only config for the cloud relay connection: this device's own relay token (its
 * credential for the backend's WebSocket, never seen by Claude) and the phone number it's
 * linked under. Small and single-valued, so plain SharedPreferences beats a Room table.
 */
object RelayConfig {
    private const val PREFS = "relay_config"
    private const val KEY_RELAY_TOKEN = "relay_token"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun relayToken(context: Context): String {
        val existing = prefs(context).getString(KEY_RELAY_TOKEN, null)
        if (existing != null) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs(context).edit().putString(KEY_RELAY_TOKEN, token).apply()
        return token
    }

    fun phoneNumber(context: Context): String? = prefs(context).getString(KEY_PHONE_NUMBER, null)

    fun setPhoneNumber(context: Context, phoneNumber: String) {
        prefs(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
