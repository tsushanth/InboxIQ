package com.inboxiq.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An external agent (e.g. a personal AI agent running on the user's own computer, see
 * mcp/McpServer.kt) paired to read/search messages and — subject to per-call on-device
 * approval — send them via the local-network MCP server. Never reachable off the LAN.
 */
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** SHA-256 of the bearer token — the raw token is shown once at pairing time and never stored. */
    val tokenHash: String,
    val pairedAt: Long,
    val lastActiveAt: Long,
)
