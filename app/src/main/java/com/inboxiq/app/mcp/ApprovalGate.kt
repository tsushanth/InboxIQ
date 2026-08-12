package com.inboxiq.app.mcp

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocks an HTTP-handler thread until the user taps Approve/Deny on a notification (see
 * ApprovalActionReceiver), or a timeout elapses. Shared by both the pairing-confirmation
 * flow and the send_message tool — anything that needs a human to physically approve it
 * on the phone before an MCP request completes goes through here.
 */
object ApprovalGate {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun register(requestId: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pending[requestId] = deferred
        return deferred
    }

    fun resolve(requestId: String, approved: Boolean) {
        pending.remove(requestId)?.complete(approved)
    }

    fun cancel(requestId: String) {
        pending.remove(requestId)
    }
}
