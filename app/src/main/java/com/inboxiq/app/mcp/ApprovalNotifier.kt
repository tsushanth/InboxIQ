package com.inboxiq.app.mcp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Fires the Approve/Deny notification for anything gated by ApprovalGate — pairing requests and send_message calls. */
object ApprovalNotifier {
    private const val CHANNEL_ID = "mcp_approvals"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connected-agent approvals",
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun requestApproval(context: Context, requestId: String, title: String, body: String) {
        ensureChannel(context)

        val approveIntent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_APPROVE
            putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, requestId)
        }
        val denyIntent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_DENY
            putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, requestId)
        }
        val approvePending = PendingIntent.getBroadcast(
            context, requestId.hashCode(), approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val denyPending = PendingIntent.getBroadcast(
            context, requestId.hashCode() + 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL) // heads-up, time-sensitive
            .setAutoCancel(true)
            .addAction(0, "Approve", approvePending)
            .addAction(0, "Deny", denyPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(requestId.hashCode(), notification)
    }

    fun cancel(context: Context, requestId: String) {
        NotificationManagerCompat.from(context).cancel(requestId.hashCode())
    }
}
