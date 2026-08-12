package com.inboxiq.app.mcp

/**
 * A pairing session exists only in memory, only while the "Pair new agent" screen is open,
 * and only for PAIRING_TTL_MS — never a persistent/static QR code (that pattern is exactly
 * what's been abused in real device-linking attacks on other messaging apps). Single-use:
 * consumed the moment a /pair request redeems it.
 */
object PairingManager {
    private const val PAIRING_TTL_MS = 2 * 60 * 1000L

    private data class Session(val token: String, val expiresAt: Long)

    @Volatile private var active: Session? = null

    fun begin(): String {
        val token = TokenUtil.randomToken()
        active = Session(token, System.currentTimeMillis() + PAIRING_TTL_MS)
        return token
    }

    fun cancel() {
        active = null
    }

    /** Consumes the pending session if [token] matches and it hasn't expired — null otherwise. */
    fun redeem(token: String): Boolean {
        val session = active ?: return false
        if (System.currentTimeMillis() > session.expiresAt) {
            active = null
            return false
        }
        if (session.token != token) return false
        active = null // single-use
        return true
    }
}
