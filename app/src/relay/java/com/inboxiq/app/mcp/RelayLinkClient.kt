package com.inboxiq.app.mcp

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface LinkResult {
    object Linked : LinkResult
    /** 401/403: the server no longer accepts this token (expired, revoked, or ownership not proven). */
    object Unauthorized : LinkResult
    data class Failed(val message: String) : LinkResult
}

/**
 * Seam for the relay backend's phone-linking protocol. Today: unauthenticated POST /phone/link.
 * The backend is adding proof-of-control for linking plus token expiry/revocation; that follow-up
 * should only need a new implementation (or a changed request in [HttpRelayLinkClient]) and, if a
 * verification step needs UI, a new [LinkResult] case handled in CloudConnectSection. Nothing else
 * in the app talks to the link endpoint.
 */
interface RelayLinkClient {
    suspend fun link(phoneNumber: String, relayToken: String): LinkResult
}

class HttpRelayLinkClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : RelayLinkClient {
    override suspend fun link(phoneNumber: String, relayToken: String): LinkResult = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("phone_number", phoneNumber)
            addProperty("relay_token", relayToken)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/phone/link").post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> LinkResult.Linked
                    response.code == 401 || response.code == 403 -> LinkResult.Unauthorized
                    else -> LinkResult.Failed("link failed: ${response.code}")
                }
            }
        } catch (e: java.io.IOException) {
            LinkResult.Failed("network error")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://mcp.kreativekoala.llc"
    }
}
