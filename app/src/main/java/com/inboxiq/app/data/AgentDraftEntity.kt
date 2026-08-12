package com.inboxiq.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AgentDraftStatus { PENDING, SENT, DISCARDED }

/**
 * A message a paired agent (see mcp/McpTools.sendMessage) asked to send, held for the user to
 * review and explicitly Send or Delete in-app. The agent never sends directly — an on-device
 * approval tap alone isn't enough to confirm the recipient and full text were actually read,
 * so the draft is queued here instead and only sent when the user acts on it from the app,
 * where the full body and resolved contact name are visible.
 */
@Entity(tableName = "agent_drafts")
data class AgentDraftEntity(
    @PrimaryKey val id: String,
    val address: String,
    val resolvedName: String?,
    val body: String,
    val createdAt: Long,
    val status: AgentDraftStatus,
)
