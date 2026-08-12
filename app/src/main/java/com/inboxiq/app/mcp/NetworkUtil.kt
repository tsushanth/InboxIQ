package com.inboxiq.app.mcp

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The MCP server binds to this address only — never 0.0.0.0 — so it's reachable strictly on
 * the local network, never the public internet, matching the spec's own security guidance
 * and the "local-network-only for v1" scope decision.
 */
object NetworkUtil {
    fun localWifiAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
