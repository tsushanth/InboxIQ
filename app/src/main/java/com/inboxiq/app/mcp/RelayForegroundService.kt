package com.inboxiq.app.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Holds a persistent WebSocket connection to inboxiq-mcp-backend so a Claude Connector
 * session (added on any device, via OAuth) can reach this phone. Replaces the old LAN-only
 * McpForegroundService — this is now the only path a paired agent uses.
 */
class RelayForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // Held for the service's whole lifetime — without these, Doze can throttle the radio
    // or let the CPU sleep while the WebSocket still reports itself as "connected", so a
    // relay call from Claude would silently hang until the OS wakes the process back up.
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:relayConnection")
    }
    private val wifiLock: WifiManager.WifiLock by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:relayConnection")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        connect()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        webSocket?.close(1000, "service stopped")
        webSocket = null
        scope.cancel()
        isRunning = false
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun connect() {
        val token = RelayConfig.relayToken(this)
        val request = Request.Builder()
            .url(RELAY_WS_URL)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectionState = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    val envelope = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return@launch
                    val correlationId = envelope.get("correlationId")?.asString ?: return@launch
                    val payload = envelope.getAsJsonObject("payload") ?: JsonObject()
                    val result = RelayJsonRpc.handle(this@RelayForegroundService, payload)
                    val response = JsonObject().apply {
                        addProperty("correlationId", correlationId)
                        add("payload", result)
                    }
                    webSocket.send(response.toString())
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionState = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionState = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        scope.launch {
            kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
            if (isRunning) connect()
        }
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Claude connection active", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("InboxIQ connected to Claude")
            .setContentText("Claude can read messages and, only as drafts you review, propose texts to send.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "relay_connection"
        private const val NOTIFICATION_ID = 9002
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val RELAY_WS_URL = "wss://mcp.kreativekoala.llc/phone-ws"

        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    }
}

enum class ConnectionState { DISCONNECTED, CONNECTED }
