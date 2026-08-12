package com.inboxiq.app.mcp

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.inboxiq.app.data.AgentDraftEntity
import com.inboxiq.app.data.AgentDraftStatus
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.data.MessageEntity
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The actual behavior behind each MCP tool. Read tools (search/list/read) are freely callable
 * once a device is paired. send_message is different — it never sends directly. A bearer token
 * alone doesn't prove the user actually read this exact address and body, so it's queued as a
 * draft (see AgentDraftEntity) for the user to review and explicitly Send or Delete in-app,
 * where the full text and resolved contact name are visible — not from a notification action.
 */
object McpTools {

    private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun definitions(): JsonArray = JsonArray().apply {
        add(toolDef(
            "list_threads",
            "List recent message conversations with contact name, last message preview, and unread status.",
            JsonObject(),
        ))
        add(toolDef(
            "search_messages",
            "Search message text across all conversations.",
            paramsSchema("query" to "The text to search for"),
        ))
        add(toolDef(
            "read_thread",
            "Read the full message history with one contact/number.",
            paramsSchema("address" to "Phone number or contact address, as returned by list_threads/search_messages"),
        ))
        add(toolDef(
            "send_message",
            "Queue a text message as a draft for the user to review. It is NOT sent yet — the user must explicitly send it from the InboxIQ app themselves. Use this whenever the user asks you to text/message someone.",
            paramsSchema("address" to "Recipient phone number", "body" to "Message text to send"),
        ))
    }

    private fun toolDef(name: String, description: String, schema: JsonObject) = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", schema)
    }

    private fun paramsSchema(vararg fields: Pair<String, String>): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        val props = JsonObject()
        fields.forEach { (name, desc) ->
            props.add(name, JsonObject().apply { addProperty("type", "string"); addProperty("description", desc) })
        }
        add("properties", props)
        add("required", com.google.gson.JsonArray().apply { fields.forEach { add(it.first) } })
    }

    suspend fun call(context: Context, name: String, args: JsonObject): JsonObject = when (name) {
        "list_threads" -> listThreads(context)
        "search_messages" -> searchMessages(context, args.get("query")?.asString.orEmpty())
        "read_thread" -> readThread(context, args.get("address")?.asString.orEmpty())
        "send_message" -> sendMessage(context, args.get("address")?.asString.orEmpty(), args.get("body")?.asString.orEmpty())
        else -> textResult("Unknown tool: $name", isError = true)
    }

    private suspend fun listThreads(context: Context): JsonObject {
        val dao = AppDatabase.get(context).messageDao()
        val threads = dao.observeThreadList().first()
        val lines = threads.take(50).map { thread ->
            val latest = thread.latestMessage
            val name = ContactResolver.displayNameFor(context, latest.address) ?: latest.address
            val unread = if (thread.unreadCount > 0) " (${thread.unreadCount} unread)" else ""
            "$name [${latest.address}]$unread — ${dateFormat.format(Date(latest.timestamp))}: ${latest.body.take(80)}"
        }
        return textResult(if (lines.isEmpty()) "No conversations." else lines.joinToString("\n"))
    }

    private suspend fun searchMessages(context: Context, query: String): JsonObject {
        if (query.isBlank()) return textResult("query is required", isError = true)
        val dao = AppDatabase.get(context).messageDao()
        val results = dao.searchMessages(query).first()
        val lines = results.take(50).map { formatMessage(context, it) }
        return textResult(if (lines.isEmpty()) "No matches for \"$query\"." else lines.joinToString("\n"))
    }

    private suspend fun readThread(context: Context, address: String): JsonObject {
        if (address.isBlank()) return textResult("address is required", isError = true)
        val dao = AppDatabase.get(context).messageDao()
        val messages = dao.observeThread(address).first()
        val lines = messages.takeLast(100).map { formatMessage(context, it, includeAddress = false) }
        return textResult(if (lines.isEmpty()) "No messages with $address." else lines.joinToString("\n"))
    }

    private fun formatMessage(context: Context, message: MessageEntity, includeAddress: Boolean = true): String {
        val who = if (message.isIncoming) {
            ContactResolver.displayNameFor(context, message.address) ?: message.address
        } else {
            "me"
        }
        val addrSuffix = if (includeAddress) " [${message.address}]" else ""
        return "${dateFormat.format(Date(message.timestamp))} $who$addrSuffix: ${message.body}"
    }

    private suspend fun sendMessage(context: Context, address: String, body: String): JsonObject {
        if (address.isBlank() || body.isBlank()) return textResult("address and body are required", isError = true)

        val resolvedName = ContactResolver.displayNameFor(context, address)
        val draft = AgentDraftEntity(
            id = UUID.randomUUID().toString(),
            address = address,
            resolvedName = resolvedName,
            body = body,
            createdAt = System.currentTimeMillis(),
            status = AgentDraftStatus.PENDING,
        )
        AppDatabase.get(context).agentDraftDao().insert(draft)
        DraftNotifier.notifyNewDraft(context, draft)

        val recipientDescription = resolvedName?.let { "$it ($address)" } ?: address
        return textResult(
            "Drafted, not sent. Queued a message to $recipientDescription for the user to review — " +
                "they need to open InboxIQ and tap Send themselves before it goes anywhere.",
        )
    }

    private fun textResult(text: String, isError: Boolean = false) = JsonObject().apply {
        val content = JsonArray()
        content.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) })
        add("content", content)
        if (isError) addProperty("isError", true)
    }
}
