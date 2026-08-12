package com.inboxiq.app.mcp

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.PairedDeviceEntity
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Local-network-only MCP server. Binds to the phone's LAN IP specifically (never 0.0.0.0 —
 * see NetworkUtil), so it's unreachable from outside the local network by construction, not
 * just by convention. Two endpoints: POST /pair (bootstraps a paired device from a short-lived
 * QR-encoded token) and POST /mcp (the actual JSON-RPC tool-call surface, bearer-token gated).
 */
class McpServer(private val context: Context, hostname: String, port: Int) : NanoHTTPD(hostname, port) {

    override fun serve(session: IHTTPSession): Response {
        // Defense-in-depth per the MCP spec's own guidance against DNS rebinding — a same-origin
        // browser context has no legitimate reason to be calling this API at all.
        val origin = session.headers["origin"]
        if (origin != null) {
            return jsonError(Response.Status.FORBIDDEN, "Cross-origin requests are not allowed")
        }

        return when {
            session.method == Method.POST && session.uri == "/pair" -> handlePair(session)
            session.method == Method.POST && session.uri == "/mcp" -> handleMcp(session)
            else -> jsonError(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun handlePair(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Missing body")
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON")

        val pairingToken = json.get("pairingToken")?.asString
        val deviceName = json.get("deviceName")?.asString?.take(64) ?: "Unnamed agent"
        if (pairingToken.isNullOrBlank()) return jsonError(Response.Status.BAD_REQUEST, "pairingToken is required")
        if (!PairingManager.redeem(pairingToken)) {
            return jsonError(Response.Status.FORBIDDEN, "Pairing code is invalid or expired")
        }

        // Defense-in-depth beyond the QR itself: show the human what's actually being paired
        // before it's finalized, in case the pairing code was relayed/intercepted rather than
        // scanned directly by the intended agent.
        val requestId = UUID.randomUUID().toString()
        val deferred = ApprovalGate.register(requestId)
        ApprovalNotifier.requestApproval(context, requestId, "Pair \"$deviceName\"?", "This lets it read and, with your approval each time, send texts.")

        val approved = runBlocking {
            try {
                kotlinx.coroutines.withTimeout(120_000L) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                ApprovalGate.cancel(requestId)
                ApprovalNotifier.cancel(context, requestId)
                false
            }
        }
        if (!approved) return jsonError(Response.Status.FORBIDDEN, "Pairing was not approved on the phone")

        val bearerToken = TokenUtil.randomToken()
        val device = PairedDeviceEntity(
            id = UUID.randomUUID().toString(),
            displayName = deviceName,
            tokenHash = TokenUtil.sha256(bearerToken),
            pairedAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
        )
        runBlocking { AppDatabase.get(context).pairedDeviceDao().insert(device) }

        val result = JsonObject().apply { addProperty("bearerToken", bearerToken) }
        return jsonResponse(Response.Status.OK, result)
    }

    private fun handleMcp(session: IHTTPSession): Response {
        val authHeader = session.headers["authorization"]
        val token = authHeader?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) return jsonError(Response.Status.UNAUTHORIZED, "Missing bearer token")

        val dao = AppDatabase.get(context).pairedDeviceDao()
        val tokenHash = TokenUtil.sha256(token)
        val device = runBlocking { dao.findByTokenHash(tokenHash) }
            ?: return jsonError(Response.Status.UNAUTHORIZED, "Not paired or token was revoked")
        runBlocking { dao.touch(tokenHash, System.currentTimeMillis()) }

        val body = readBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "Missing body")
        val rpc = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON")

        val id = rpc.get("id")
        val method = rpc.get("method")?.asString

        val result: JsonObject = when (method) {
            "initialize" -> JsonObject().apply {
                addProperty("protocolVersion", "2025-06-18")
                add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                add(
                    "serverInfo",
                    JsonObject().apply { addProperty("name", "inboxiq"); addProperty("version", "1.0") },
                )
            }
            "tools/list" -> JsonObject().apply { add("tools", McpTools.definitions()) }
            "tools/call" -> {
                val params = rpc.getAsJsonObject("params")
                val toolName = params?.get("name")?.asString ?: ""
                val args = params?.getAsJsonObject("arguments") ?: JsonObject()
                runBlocking { McpTools.call(context, toolName, args) }
            }
            else -> return jsonRpcError(id, -32601, "Method not found: $method")
        }

        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        }
        return jsonResponse(Response.Status.OK, envelope, sessionId = device.id)
    }

    private fun readBody(session: IHTTPSession): String? {
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            files["postData"]
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonResponse(status: Response.IStatus, body: JsonObject, sessionId: String? = null): Response {
        val response = newFixedLengthResponse(status, "application/json", body.toString())
        if (sessionId != null) response.addHeader("Mcp-Session-Id", sessionId)
        return response
    }

    private fun jsonError(status: Response.IStatus, message: String): Response =
        jsonResponse(status, JsonObject().apply { addProperty("error", message) })

    private fun jsonRpcError(id: com.google.gson.JsonElement?, code: Int, message: String): Response {
        val envelope = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) })
        }
        return jsonResponse(Response.Status.OK, envelope)
    }

    companion object {
        const val PORT = 47821
    }
}
