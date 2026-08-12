package com.inboxiq.app.mcp

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Talks to inboxiq-mcp-backend's plain REST endpoints (not the relay itself). */
object RelayApi {
    private const val BASE_URL = "https://mcp.kreativekoala.llc"
    private val client = OkHttpClient()
    private val JSON = "application/json".toMediaType()

    /** Links this device's relay token to a phone number so the backend can route relay calls to it. */
    suspend fun linkPhone(phoneNumber: String, relayToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("phone_number", phoneNumber)
            addProperty("relay_token", relayToken)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder().url("$BASE_URL/phone/link").post(body).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("link failed: ${response.code} ${response.body?.string()}")
            }
        }
    }
}
