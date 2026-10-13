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
    private const val KEY_CONSENT_VERSION = "consent_version"
    private const val KEY_NEEDS_RECONNECT = "needs_reconnect"
    private const val KEY_REPLACED = "replaced_elsewhere"

    /**
     * Bump when the data the relay exposes (or who receives it) changes, so everyone has to
     * re-read and re-accept the disclosure shown by RelayConsentDialog.
     * 2: the relay works only while InboxIQ is open on screen (no foreground service); closing the
     * app ends access. Everyone who accepted version 1 (0.4.x) must accept this text.
     * 3: linking now proves control of the number: the relay places an automated voice call to it
     * that reads a one-time code, and turning the relay off asks the relay to revoke this phone's
     * access.
     */
    const val CONSENT_VERSION = 3

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun relayToken(context: Context): String {
        val existing = prefs(context).getString(KEY_RELAY_TOKEN, null)
        if (existing != null) return existing
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs(context).edit().putString(KEY_RELAY_TOKEN, token).apply()
        return token
    }

    /** Like [relayToken] but never creates one. */
    fun existingRelayToken(context: Context): String? = prefs(context).getString(KEY_RELAY_TOKEN, null)

    fun phoneNumber(context: Context): String? = prefs(context).getString(KEY_PHONE_NUMBER, null)

    fun setPhoneNumber(context: Context, phoneNumber: String) {
        prefs(context).edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
    }

    /** True only if the user turned the relay on AND accepted the current disclosure. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false) && hasConsent(context)

    /** The server rejected our token (401/403); stay disconnected until the user re-verifies. */
    fun needsReconnect(context: Context): Boolean = prefs(context).getBoolean(KEY_NEEDS_RECONNECT, false)

    fun markReconnectNeeded(context: Context) {
        prefs(context).edit().putBoolean(KEY_NEEDS_RECONNECT, true).apply()
    }

    fun clearReconnectNeeded(context: Context) {
        prefs(context).edit().putBoolean(KEY_NEEDS_RECONNECT, false).putBoolean(KEY_REPLACED, false).apply()
    }

    /** The relay closed our socket with 4401 "replaced"/"unlinked": another install took this number over. */
    fun replacedElsewhere(context: Context): Boolean = prefs(context).getBoolean(KEY_REPLACED, false)

    fun markReplaced(context: Context) {
        prefs(context).edit().putBoolean(KEY_NEEDS_RECONNECT, true).putBoolean(KEY_REPLACED, true).apply()
    }

    /** Every condition for opening the relay socket (visibility is checked by the connection manager). */
    fun canConnect(context: Context): Boolean =
        isEnabled(context) && !phoneNumber(context).isNullOrBlank() && !needsReconnect(context)

    fun hasConsent(context: Context): Boolean =
        prefs(context).getInt(KEY_CONSENT_VERSION, 0) >= CONSENT_VERSION

    fun recordConsent(context: Context) {
        prefs(context).edit().putInt(KEY_CONSENT_VERSION, CONSENT_VERSION).apply()
    }

    /**
     * Turning the connection off also discards the relay credential, so the backend can no
     * longer route any Claude session to this phone until the user explicitly re-enables
     * (which mints a new token and re-links). Consent is kept so re-enabling doesn't re-prompt
     * within the same disclosure version; call [clearConsent] to withdraw it as well.
     */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).putBoolean(KEY_NEEDS_RECONNECT, false).putBoolean(KEY_REPLACED, false).remove(KEY_RELAY_TOKEN).apply()
    }

    fun clearConsent(context: Context) {
        prefs(context).edit().remove(KEY_CONSENT_VERSION).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
