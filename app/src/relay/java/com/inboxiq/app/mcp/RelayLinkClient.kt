package com.inboxiq.app.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface LinkResult {
    object Linked : LinkResult
    /** 401/403 without a more specific reason: the server no longer accepts this token. */
    object Unauthorized : LinkResult
    /**
     * The number is bound to another install (409 link_conflict) or the server requires a code
     * (403 otp_required). The caller runs the code flow; it must never retry this automatically.
     */
    object OtpRequired : LinkResult
    object OtpInvalid : LinkResult
    object OtpExpired : LinkResult
    /** 429. [retryAfterSeconds] comes from a Retry-After header when the server sends one. */
    data class RateLimited(val retryAfterSeconds: Long?) : LinkResult
    data class Failed(val message: String) : LinkResult
}

sealed interface StartResult {
    /** The server placed a voice call. [expiresInSeconds] is how long the code stays valid. */
    data class Sent(val challengeId: String, val expiresInSeconds: Long) : StartResult
    /** 404/405: an older relay without code verification; use the legacy [RelayLinkClient.link]. */
    object Unsupported : StartResult
    data class RateLimited(val retryAfterSeconds: Long?) : StartResult
    object InvalidPhone : StartResult
    object DestinationNotAllowed : StartResult
    /** 502/503: the relay could not place the call. */
    object DeliveryFailed : StartResult
    data class Failed(val message: String) : StartResult
}

sealed interface UnlinkResult {
    /** Server confirmed (200), or no longer knows this token (401): either way access is gone. */
    object Done : UnlinkResult
    /** 404/405: an older relay with no revoke endpoint. */
    object Unsupported : UnlinkResult
    object Unreachable : UnlinkResult
}

/**
 * Seam for the relay backend's phone-linking protocol (docs/PROTOCOL.md of the relay):
 *  - [startVerification]  POST /phone/link/start  (voice call with a 6-digit code)
 *  - [confirm]            POST /phone/link with challenge_id + otp_code
 *  - [link]               POST /phone/link, legacy shape; only used when the relay lacks /start
 *  - [unlink]             DELETE /phone/link with the relay token (also revokes Claude grants)
 * Nothing else in the app talks to these endpoints. The OTP is never logged or stored.
 */
interface RelayLinkClient {
    suspend fun link(phoneNumber: String, relayToken: String): LinkResult
    suspend fun startVerification(phoneNumber: String): StartResult
    suspend fun confirm(phoneNumber: String, relayToken: String, challengeId: String, otp: String): LinkResult
    suspend fun unlink(relayToken: String): UnlinkResult
}

class HttpRelayLinkClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : RelayLinkClient {
    private val json = "application/json".toMediaType()

    private fun errorCode(response: Response): String? = try {
        // Bounded read; the body is a tiny {"error": "..."} and never contains the OTP.
        val text = response.peekBody(4096).string()
        JsonParser.parseString(text).asJsonObject.get("error")?.asString
    } catch (e: Exception) { null }

    private fun retryAfter(response: Response): Long? =
        response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it in 1..86_400 }

    override suspend fun link(phoneNumber: String, relayToken: String): LinkResult =
        post(phoneNumber, relayToken, null, null)

    override suspend fun confirm(phoneNumber: String, relayToken: String, challengeId: String, otp: String): LinkResult =
        post(phoneNumber, relayToken, challengeId, otp)

    private suspend fun post(phone: String, token: String, challengeId: String?, otp: String?): LinkResult = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("phone_number", phone)
            addProperty("relay_token", token)
            if (challengeId != null && otp != null) {
                addProperty("challenge_id", challengeId)
                addProperty("otp_code", otp)
            }
        }.toString().toRequestBody(json)
        val request = Request.Builder().url("$baseUrl/phone/link").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (response.isSuccessful) return@use LinkResult.Linked
                val error = errorCode(response)
                when {
                    code == 409 || error == "otp_required" || error == "link_conflict" -> LinkResult.OtpRequired
                    error == "otp_invalid" -> LinkResult.OtpInvalid
                    error == "otp_expired" -> LinkResult.OtpExpired
                    code == 429 -> LinkResult.RateLimited(retryAfter(response))
                    code == 401 || code == 403 -> LinkResult.Unauthorized
                    else -> LinkResult.Failed("link failed: $code")
                }
            }
        } catch (e: java.io.IOException) {
            LinkResult.Failed("network error")
        }
    }

    override suspend fun startVerification(phoneNumber: String): StartResult = withContext(Dispatchers.IO) {
        val body = JsonObject().apply { addProperty("phone_number", phoneNumber) }.toString().toRequestBody(json)
        val request = Request.Builder().url("$baseUrl/phone/link/start").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (response.isSuccessful) {
                    val obj = try { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject } catch (e: Exception) { null }
                    val id = obj?.get("challenge_id")?.asString
                    if (id.isNullOrBlank()) return@use StartResult.Failed("bad start response")
                    return@use StartResult.Sent(id, obj.get("expires_in")?.asLong ?: 300L)
                }
                val error = errorCode(response)
                when {
                    // A relay without the endpoint answers 404 (unknown route) or 405.
                    (code == 404 && error == null) || code == 405 -> StartResult.Unsupported
                    code == 429 -> StartResult.RateLimited(retryAfter(response))
                    error == "destination_not_allowed" -> StartResult.DestinationNotAllowed
                    code == 400 -> StartResult.InvalidPhone
                    code == 502 || code == 503 -> StartResult.DeliveryFailed
                    else -> StartResult.Failed("start failed: $code")
                }
            }
        } catch (e: java.io.IOException) {
            StartResult.Failed("network error")
        }
    }

    override suspend fun unlink(relayToken: String): UnlinkResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/phone/link").delete()
            .header("Authorization", "Bearer $relayToken").build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 401 -> UnlinkResult.Done
                    response.code == 404 || response.code == 405 -> UnlinkResult.Unsupported
                    else -> UnlinkResult.Unreachable
                }
            }
        } catch (e: java.io.IOException) {
            UnlinkResult.Unreachable
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://mcp.kreativekoala.llc"
    }
}
