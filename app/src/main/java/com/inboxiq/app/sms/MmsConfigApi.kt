package com.inboxiq.app.sms

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for inboxiq-config, the self-healing MMS backend. Every call here is
 * best-effort: a network failure just means we fall back to the default send
 * strategy, never blocks or fails the actual message send.
 */
object MmsConfigApi {
    private const val BASE_URL = "https://inboxiq-config.fly.dev"
    private const val TAG = "MmsConfigApi"

    /** Returns a known-good strategy id for this device+carrier, or null if none is configured. */
    suspend fun fetchStrategy(fingerprint: DeviceFingerprint): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "$BASE_URL/mms-strategy?device=${enc(fingerprint.deviceModel)}&carrier=${enc(fingerprint.carrier)}",
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("strategyId").takeIf { it.isNotBlank() && it != "null" }
        }.onFailure { Log.w(TAG, "fetchStrategy failed", it) }.getOrNull()
    }

    /** Reports the outcome of a send attempt. Fire-and-forget from the caller's perspective. */
    suspend fun reportOutcome(fingerprint: DeviceFingerprint, strategyId: String, success: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("$BASE_URL/mms-outcome")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000
                readTimeout = 5000
            }
            val payload = JSONObject().apply {
                put("device", fingerprint.deviceModel)
                put("carrier", fingerprint.carrier)
                put("strategyId", strategyId)
                put("success", success)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode
        }.onFailure { Log.w(TAG, "reportOutcome failed", it) }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
