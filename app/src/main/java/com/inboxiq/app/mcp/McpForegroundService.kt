package com.inboxiq.app.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Hosts the MCP server for as long as it's running — a foreground service so Android doesn't
 * kill the socket in the background, with a persistent, honest notification since this process
 * can read and (with per-call approval) send the user's texts while it's alive.
 */
class McpForegroundService : Service() {

    private var server: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        val address = NetworkUtil.localWifiAddress()
        if (address == null) {
            stopSelf() // no LAN to bind to — nothing safe to do
            return
        }
        server = McpServer(applicationContext, address, McpServer.PORT).apply { start(SOCKET_TIMEOUT_MS, false) }
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Agent connection active", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("InboxIQ agent connection active")
            .setContentText("A paired agent on your local network can read messages and, with your approval, send them.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mcp_server_running"
        private const val NOTIFICATION_ID = 9001
        private const val SOCKET_TIMEOUT_MS = 30_000

        @Volatile var isRunning: Boolean = false
            private set
    }
}
