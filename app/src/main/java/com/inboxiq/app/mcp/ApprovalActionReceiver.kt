package com.inboxiq.app.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** Handles the Approve/Deny taps on an ApprovalNotifier notification, resolving the matching ApprovalGate entry. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val approved = intent.action == ACTION_APPROVE
        ApprovalGate.resolve(requestId, approved)
        NotificationManagerCompat.from(context).cancel(requestId.hashCode())
    }

    companion object {
        const val ACTION_APPROVE = "com.inboxiq.app.mcp.APPROVE"
        const val ACTION_DENY = "com.inboxiq.app.mcp.DENY"
        const val EXTRA_REQUEST_ID = "request_id"
    }
}
