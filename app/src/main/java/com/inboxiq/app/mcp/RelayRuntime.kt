package com.inboxiq.app.mcp

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Wires [RelayConnectionManager] to the app's visible lifecycle. No service, no wake lock. */
object RelayRuntime {
    @Volatile private var manager: RelayConnectionManager? = null

    private val notInstalled = kotlinx.coroutines.flow.MutableStateFlow(RelayStatus.OFF)

    /** OFF if [install] has not run (previews, tests). */
    fun status(): kotlinx.coroutines.flow.StateFlow<RelayStatus> = manager?.status ?: notInstalled
    fun refresh() { manager?.refresh() }

    fun install(context: Context) {
        if (manager != null) return
        val app = context.applicationContext
        val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
        val m = RelayConnectionManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            factory = { token, events -> OkHttpRelaySocket(http, token, events) },
            gate = ConfigGate(app),
            handler = { RelayJsonRpc.handle(app, it) },
        )
        manager = m
        // ProcessLifecycleOwner ON_STOP fires ~700 ms after the last activity stops and NOT on
        // rotation, so config changes keep the socket; screen-off stops the activity.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = m.onForeground()
            override fun onStop(owner: LifecycleOwner) = m.onBackground()
        })
    }
}

internal class ConfigGate(private val context: Context) : RelayGate {
    override fun isEnabled() = RelayConfig.isEnabled(context)
    override fun needsReconnect() = RelayConfig.needsReconnect(context)
    override fun canConnect() = RelayConfig.canConnect(context)
    override fun token() = RelayConfig.relayToken(context)
    override fun markReconnectNeeded() = RelayConfig.markReconnectNeeded(context)
}

private class OkHttpRelaySocket(client: OkHttpClient, token: String, events: RelaySocketEvents) : RelaySocket {
    private val ws: WebSocket = client.newWebSocket(
        Request.Builder().url("wss://mcp.kreativekoala.llc/phone-ws").addHeader("Authorization", "Bearer $token").build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = events.onMessage(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = events.onFailure(response?.code)
        },
    )
    override fun send(text: String) = ws.send(text)
    override fun cancel() = ws.cancel()
    override fun close() { ws.close(1000, "app left the screen") }
}
