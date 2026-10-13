package com.inboxiq.app.mcp

import com.google.gson.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectionManagerTest {

    private class FakeSocket(val events: RelaySocketEvents) : RelaySocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        var closed = false
        override fun send(text: String): Boolean { sent += text; return true }
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }

    private class FakeGate : RelayGate {
        var enabled = true
        var reconnect = false
        var linked = true
        override fun canConnect() = enabled && linked && !reconnect
        override fun isEnabled() = enabled
        override fun needsReconnect() = reconnect
        override fun token() = "tok"
        override fun markReconnectNeeded() { reconnect = true }
    }

    private class Rig(testScope: TestScope) {
        val sockets = mutableListOf<FakeSocket>()
        val gate = FakeGate()
        val manager = RelayConnectionManager(
            scope = testScope,
            factory = { _, ev -> FakeSocket(ev).also { sockets += it } },
            gate = gate,
            handler = { JsonObject().apply { addProperty("ok", true) } },
        )
        val live get() = sockets.count { !it.cancelled && !it.closed }
    }

    private fun TestScope.rig() = Rig(this)

    @Test
    fun `does not connect while app is not visible`() = runTest {
        val r = rig()
        r.manager.refresh()
        assertEquals(0, r.sockets.size)
        assertEquals(RelayStatus.PAUSED, r.manager.status.value)
    }

    @Test
    fun `connects on start, shows connecting then connected`() = runTest {
        val r = rig()
        r.manager.onForeground()
        assertEquals(RelayStatus.CONNECTING, r.manager.status.value)
        r.sockets[0].events.onOpen()
        assertEquals(RelayStatus.CONNECTED, r.manager.status.value)
    }

    @Test
    fun `no connection without consent, enabled flag or linked phone`() = runTest {
        val r = rig()
        r.gate.enabled = false
        r.manager.onForeground()
        assertEquals(0, r.sockets.size)
        assertEquals(RelayStatus.OFF, r.manager.status.value)
        r.gate.enabled = true; r.gate.linked = false
        r.manager.refresh()
        assertEquals(0, r.sockets.size)
    }

    @Test
    fun `stop disconnects only after the grace period, quick switch keeps socket`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onOpen()
        r.manager.onBackground()
        advanceTimeBy(RelayConnectionManager.GRACE_MS - 1); runCurrent()
        assertTrue(r.live == 1)
        r.manager.onForeground() // came back in time
        advanceTimeBy(RelayConnectionManager.GRACE_MS * 3); runCurrent()
        assertEquals(1, r.sockets.size)
        assertEquals(1, r.live)
        assertEquals(RelayStatus.CONNECTED, r.manager.status.value)
    }

    @Test
    fun `leaving the screen closes the socket and never reconnects until visible again`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onOpen()
        r.manager.onBackground()
        advanceTimeBy(RelayConnectionManager.GRACE_MS + 1); runCurrent()
        assertTrue(r.sockets[0].closed)
        assertEquals(RelayStatus.PAUSED, r.manager.status.value)
        r.sockets[0].events.onClosed() // late callback from the dead socket is ignored
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(1, r.sockets.size)
        r.manager.onForeground()
        assertEquals(2, r.sockets.size)
    }

    @Test
    fun `rotation does not drop the connection - no stop event means no teardown`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onOpen()
        // Activity recreation keeps the process lifecycle STARTED: no onBackground/onForeground pair.
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, r.live)
        assertEquals(RelayStatus.CONNECTED, r.manager.status.value)
    }

    @Test
    fun `repeated foreground and refresh calls never create a second socket`() = runTest {
        val r = rig()
        r.manager.onForeground(); r.manager.onForeground(); r.manager.refresh(); r.manager.refresh()
        r.sockets[0].events.onOpen()
        r.manager.refresh(); r.manager.onForeground()
        assertEquals(1, r.sockets.size)
    }

    @Test
    fun `reconnects with exponential backoff while visible`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onFailure(null)
        assertEquals(RelayStatus.OFFLINE, r.manager.status.value)
        advanceTimeBy(RelayConnectionManager.INITIAL_BACKOFF_MS - 1); runCurrent()
        assertEquals(1, r.sockets.size)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, r.sockets.size)
        r.sockets[1].events.onFailure(null)
        advanceTimeBy(RelayConnectionManager.INITIAL_BACKOFF_MS + 1); runCurrent()
        assertEquals(2, r.sockets.size) // second wait is double
        advanceTimeBy(RelayConnectionManager.INITIAL_BACKOFF_MS); runCurrent()
        assertEquals(3, r.sockets.size)
    }

    @Test
    fun `leaving the screen cancels a pending reconnect`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onFailure(null)
        r.manager.onBackground()
        advanceTimeBy(5 * 60_000); runCurrent()
        assertEquals(1, r.sockets.size)
    }

    @Test
    fun `401 or 403 stops, marks reconnect needed and does not loop`() = runTest {
        for (code in listOf(401, 403)) {
            val r = rig()
            r.manager.onForeground()
            r.sockets[0].events.onFailure(code)
            assertEquals(RelayStatus.RECONNECT_NEEDED, r.manager.status.value)
            assertTrue(r.gate.reconnect)
            advanceTimeBy(10 * 60_000); runCurrent()
            assertEquals(1, r.sockets.size)
            r.manager.onBackground(); r.manager.onForeground()
            assertEquals(1, r.sockets.size) // still blocked until re-verified
            r.gate.reconnect = false // user re-verified
            r.manager.refresh()
            assertEquals(2, r.sockets.size)
        }
    }

    @Test
    fun `turning the relay off closes the socket immediately`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onOpen()
        r.gate.enabled = false
        r.manager.refresh()
        assertTrue(r.sockets[0].closed)
        assertEquals(RelayStatus.OFF, r.manager.status.value)
    }

    @Test
    fun `answers requests with the correlation id while connected`() = runTest {
        val r = rig()
        r.manager.onForeground()
        r.sockets[0].events.onOpen()
        r.sockets[0].events.onMessage("""{"correlationId":"c1","payload":{"method":"x"}}""")
        runCurrent()
        assertEquals(1, r.sockets[0].sent.size)
        assertTrue(r.sockets[0].sent[0].contains("\"correlationId\":\"c1\""))
    }

    @Test
    fun `ignores events from a superseded socket`() = runTest {
        val r = rig()
        r.manager.onForeground()
        val old = r.sockets[0]
        old.events.onFailure(null)
        advanceTimeBy(RelayConnectionManager.INITIAL_BACKOFF_MS + 1); runCurrent()
        r.sockets[1].events.onOpen()
        old.events.onClosed()
        old.events.onFailure(500)
        assertEquals(RelayStatus.CONNECTED, r.manager.status.value)
        assertFalse(r.gate.reconnect)
    }
}
