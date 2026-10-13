package com.inboxiq.app.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the relay settings row shows. Wording lives in strings.xml (relay_status_*). */
enum class RelayStatus { OFF, CONNECTING, CONNECTED, OFFLINE, PAUSED, RECONNECT_NEEDED }

/** One open socket. Implementations must make [cancel] and [close] safe to call repeatedly. */
interface RelaySocket {
    fun send(text: String): Boolean
    fun cancel()
    fun close()
}

interface RelaySocketEvents {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed()
    /** [httpCode] is the HTTP status of a rejected upgrade, if any. */
    fun onFailure(httpCode: Int?)
}

fun interface RelaySocketFactory {
    fun open(token: String, events: RelaySocketEvents): RelaySocket
}

/** The conditions that must ALL hold before a socket may exist. */
interface RelayGate {
    /** Relay turned on AND current consent accepted AND a linked phone AND no pending re-verification. */
    fun canConnect(): Boolean
    fun isEnabled(): Boolean
    fun needsReconnect(): Boolean
    fun token(): String
    /** The server rejected the token (401/403): stop and require re-verification. */
    fun markReconnectNeeded()
}

/**
 * In-process relay connection bound to the app being visible. There is no service, wake lock or
 * notification: [onForeground] is called when the app comes on screen (ProcessLifecycleOwner
 * ON_START), [onBackground] when it leaves (ON_STOP, which also fires on screen-off). Rotation
 * does not reach either, so it never drops the socket. Leaving the screen disconnects after
 * [GRACE_MS] so a quick app switch does not flap; nothing reconnects until the app is visible again.
 *
 * All state changes are serialized by [lock]; [scope] is only used for delays and handler work.
 */
class RelayConnectionManager(
    private val scope: CoroutineScope,
    private val factory: RelaySocketFactory,
    private val gate: RelayGate,
    private val handler: suspend (JsonObject) -> JsonObject,
) {
    private val lock = Any()
    private val _status = MutableStateFlow(RelayStatus.OFF)
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private var foreground = false
    private var socket: RelaySocket? = null
    private var generation = 0
    private var deadGeneration = -1
    private var backoffMs = INITIAL_BACKOFF_MS
    private var graceJob: Job? = null
    private var retryJob: Job? = null

    fun onForeground() {
        synchronized(lock) {
            foreground = true
            graceJob?.cancel(); graceJob = null
            evaluate()
        }
    }

    fun onBackground() {
        synchronized(lock) {
            foreground = false
            retryJob?.cancel(); retryJob = null
            if (socket == null) {
                _status.value = idleStatus()
                return
            }
            graceJob?.cancel()
            graceJob = scope.launch {
                delay(GRACE_MS)
                synchronized(lock) {
                    if (!foreground) {
                        teardown()
                        _status.value = idleStatus()
                    }
                }
            }
        }
    }

    /** Call after the user changes anything the gate reads (toggle, link, re-verify). */
    fun refresh() {
        synchronized(lock) { evaluate() }
    }

    private fun idleStatus(): RelayStatus = when {
        gate.needsReconnect() && gate.isEnabled() -> RelayStatus.RECONNECT_NEEDED
        !gate.canConnect() -> RelayStatus.OFF
        else -> RelayStatus.PAUSED
    }

    private fun evaluate() {
        if (!foreground) {
            // Settings changed while hidden (or never shown): never open a socket.
            if (socket != null && !gate.canConnect()) { teardown() }
            _status.value = idleStatus()
            return
        }
        if (!gate.canConnect()) {
            teardown()
            _status.value = idleStatus()
            return
        }
        if (socket == null && retryJob?.isActive != true) {
            backoffMs = INITIAL_BACKOFF_MS
            connect()
        }
    }

    private fun connect() {
        // Replace, never stack: only one socket may exist, and events from a superseded one are ignored.
        socket?.cancel(); socket = null
        val gen = ++generation
        _status.value = RelayStatus.CONNECTING
        val listener = object : RelaySocketEvents {
            override fun onOpen() {
                synchronized(lock) {
                    if (gen != generation) return
                    backoffMs = INITIAL_BACKOFF_MS
                    _status.value = RelayStatus.CONNECTED
                }
            }

            override fun onMessage(text: String) {
                val current = synchronized(lock) { if (gen == generation) socket else null } ?: return
                scope.launch {
                    val envelope = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return@launch
                    val correlationId = envelope.get("correlationId")?.asString ?: return@launch
                    val payload = envelope.getAsJsonObject("payload") ?: JsonObject()
                    val result = handler(payload)
                    // Never answer after the app left the screen or the socket was replaced.
                    val stillCurrent = synchronized(lock) { gen == generation && socket === current }
                    if (!stillCurrent) return@launch
                    current.send(JsonObject().apply {
                        addProperty("correlationId", correlationId)
                        add("payload", result)
                    }.toString())
                }
            }

            override fun onClosed() {
                synchronized(lock) {
                    if (gen != generation) return
                    deadGeneration = gen
                    socket = null
                    scheduleRetry()
                }
            }

            override fun onFailure(httpCode: Int?) {
                synchronized(lock) {
                    if (gen != generation) return
                    deadGeneration = gen
                    socket = null
                    if (httpCode == 401 || httpCode == 403) {
                        gate.markReconnectNeeded()
                        _status.value = RelayStatus.RECONNECT_NEEDED
                    } else {
                        scheduleRetry()
                    }
                }
            }
        }
        // The factory may call back synchronously (the lock is reentrant); don't keep a socket
        // that already died inside open().
        val opened = factory.open(gate.token(), listener)
        if (deadGeneration != gen && gen == generation) socket = opened
    }

    private fun scheduleRetry() {
        if (!foreground || !gate.canConnect()) {
            _status.value = idleStatus()
            return
        }
        _status.value = RelayStatus.OFFLINE
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                retryJob = null
                if (foreground && gate.canConnect() && socket == null) connect()
            }
        }
    }

    private fun teardown() {
        generation++ // invalidate any in-flight listener
        retryJob?.cancel(); retryJob = null
        graceJob?.cancel(); graceJob = null
        socket?.close()
        socket = null
    }

    companion object {
        const val GRACE_MS = 5_000L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
