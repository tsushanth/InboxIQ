package com.inboxiq.app.mcp

import android.content.Context
import com.google.gson.JsonObject

/**
 * The same JSON-RPC dispatch the old LAN-only McpServer used for POST /mcp, now driven by
 * relay messages instead of raw HTTP — the actual tool behavior in McpTools is unchanged.
 */
object RelayJsonRpc {
    suspend fun handle(context: Context, rpc: JsonObject): JsonObject {
        val id = rpc.get("id")
        val method = rpc.get("method")?.asString

        val result: JsonObject = when (method) {
            "initialize" -> JsonObject().apply {
                addProperty("protocolVersion", "2025-06-18")
                add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
                add("serverInfo", JsonObject().apply { addProperty("name", "inboxiq"); addProperty("version", "1.0") })
            }
            "tools/list" -> JsonObject().apply { add("tools", McpTools.definitions()) }
            "tools/call" -> {
                val params = rpc.getAsJsonObject("params")
                val toolName = params?.get("name")?.asString ?: ""
                val args = params?.getAsJsonObject("arguments") ?: JsonObject()
                McpTools.call(context, toolName, args)
            }
            else -> return JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", id)
                add("error", JsonObject().apply { addProperty("code", -32601); addProperty("message", "Method not found: $method") })
            }
        }

        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        }
    }
}
