package com.inboxiq.app.mcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayLinkControllerTest {
    private class FakeClient : RelayLinkClient {
        var start: StartResult = StartResult.Sent("c1", 300)
        var link: LinkResult = LinkResult.Linked
        var confirm: LinkResult = LinkResult.Linked
        var unlink: UnlinkResult = UnlinkResult.Done
        var startCalls = 0; var linkCalls = 0; var confirmCalls = 0; var unlinkCalls = 0
        var lastUnlinkToken: String? = null
        override suspend fun link(phoneNumber: String, relayToken: String): LinkResult { linkCalls++; return link }
        override suspend fun startVerification(phoneNumber: String): StartResult { startCalls++; return start }
        override suspend fun confirm(phoneNumber: String, relayToken: String, challengeId: String, otp: String): LinkResult { confirmCalls++; return confirm }
        override suspend fun unlink(relayToken: String): UnlinkResult { unlinkCalls++; lastUnlinkToken = relayToken; return unlink }
    }

    private class FakeStore : RelayLinkStore {
        var enabled = false
        var token: String? = "tok"
        var phone: String? = null
        var reconnect = false
        override fun isEnabled() = enabled
        override fun existingToken() = token
        override fun token() = token ?: "new".also { token = it }
        override fun onLinked(phone: String) { this.phone = phone; enabled = true; reconnect = false }
        override fun disableLocally() { enabled = false; token = null; reconnect = false }
        override fun markReconnectNeeded() { reconnect = true }
    }

    private class Rig(scope: TestScope) {
        var clock = 1_000_000L
        val client = FakeClient()
        val store = FakeStore()
        val c = RelayLinkController(scope, client, store) { clock }
        val s get() = c.state.value
    }

    private fun TestScope.rig() = Rig(this)

    @Test fun `new flow success - start, code, confirm, linked`() = runTest {
        val r = rig()
        r.c.begin("+14155550123"); advanceUntilIdle()
        assertEquals(LinkPhase.AWAITING_CODE, r.s.phase)
        assertEquals(LinkNotice.CODE_SENT, r.s.notice)
        assertEquals(0, r.client.linkCalls)
        r.c.submitCode("+14155550123", "123456"); advanceUntilIdle()
        assertEquals(LinkPhase.IDLE, r.s.phase)
        assertTrue(r.s.enabled)
        assertEquals("+14155550123", r.store.phone)
    }

    @Test fun `blank phone asks for a number and calls nothing`() = runTest {
        val r = rig()
        r.c.begin("  "); advanceUntilIdle()
        assertEquals(LinkNotice.ENTER_PHONE, r.s.notice)
        assertEquals(0, r.client.startCalls)
    }

    @Test fun `wrong code keeps the challenge so the user can retype`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle()
        r.client.confirm = LinkResult.OtpInvalid
        r.c.submitCode("+1", "000000"); advanceUntilIdle()
        assertEquals(LinkPhase.AWAITING_CODE, r.s.phase)
        assertEquals(LinkNotice.WRONG_CODE, r.s.notice)
        assertFalse(r.s.enabled)
        assertEquals(1, r.client.confirmCalls)
    }

    @Test fun `malformed code is rejected locally without a network call`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle()
        r.c.submitCode("+1", "12345"); r.c.submitCode("+1", "12a456"); advanceUntilIdle()
        assertEquals(0, r.client.confirmCalls)
        assertEquals(LinkNotice.ENTER_SIX_DIGITS, r.s.notice)
    }

    @Test fun `expired code returns to idle and drops the challenge`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle()
        r.client.confirm = LinkResult.OtpExpired
        r.c.submitCode("+1", "111111"); advanceUntilIdle()
        assertEquals(LinkPhase.IDLE, r.s.phase)
        assertEquals(LinkNotice.CODE_EXPIRED, r.s.notice)
        assertNull(r.s.challengeId)
    }

    @Test fun `lockout shows retry-after, blocks new codes until it passes, never loops`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle()
        r.client.confirm = LinkResult.RateLimited(1800)
        r.c.submitCode("+1", "111111"); advanceUntilIdle()
        assertEquals(LinkNotice.LOCKED, r.s.notice)
        assertEquals(1800L, r.s.retryAfterSeconds)
        assertEquals(LinkPhase.IDLE, r.s.phase)
        // Tapping Verify again inside the window does nothing.
        val starts = r.client.startCalls
        r.c.begin("+1"); advanceUntilIdle()
        assertEquals(starts, r.client.startCalls)
        // After the window it is allowed.
        r.clock += 1800_000L
        r.c.begin("+1"); advanceUntilIdle()
        assertEquals(starts + 1, r.client.startCalls)
    }

    @Test fun `lockout without Retry-After assumes up to an hour`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle()
        r.client.confirm = LinkResult.RateLimited(null)
        r.c.submitCode("+1", "111111"); advanceUntilIdle()
        assertEquals(r.clock + RelayLinkController.DEFAULT_LOCKOUT_S * 1000, r.s.blockedUntilMs)
    }

    @Test fun `resend only after the cool-down`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle()
        r.c.resend("+1"); advanceUntilIdle()
        assertEquals(1, r.client.startCalls)
        r.clock += RelayLinkController.RESEND_COOLDOWN_MS
        r.client.start = StartResult.Sent("c2", 300)
        r.c.resend("+1"); advanceUntilIdle()
        assertEquals(2, r.client.startCalls)
        assertEquals("c2", r.s.challengeId)
    }

    @Test fun `start rate limit shows the wait and blocks`() = runTest {
        val r = rig()
        r.client.start = StartResult.RateLimited(600)
        r.c.begin("+1"); advanceUntilIdle()
        assertEquals(LinkNotice.SEND_RATE_LIMITED, r.s.notice)
        assertEquals(600L, r.s.retryAfterSeconds)
        r.c.begin("+1"); advanceUntilIdle()
        assertEquals(1, r.client.startCalls)
    }

    @Test fun `start failures map to notices and do not retry`() = runTest {
        val cases = listOf(
            StartResult.InvalidPhone to LinkNotice.INVALID_PHONE,
            StartResult.DestinationNotAllowed to LinkNotice.DESTINATION_NOT_ALLOWED,
            StartResult.DeliveryFailed to LinkNotice.DELIVERY_FAILED,
            StartResult.Failed("x") to LinkNotice.NETWORK,
        )
        for ((res, notice) in cases) {
            val r = rig(); r.client.start = res
            r.c.begin("+1"); advanceUntilIdle()
            assertEquals(notice, r.s.notice)
            assertEquals(1, r.client.startCalls)
            assertEquals(0, r.client.linkCalls)
        }
    }

    // ---- compatibility matrix ----

    @Test fun `old server - start missing falls back to the legacy link exactly once`() = runTest {
        val r = rig(); r.client.start = StartResult.Unsupported
        r.c.begin("+14155550123"); advanceUntilIdle()
        assertEquals(1, r.client.linkCalls)
        assertEquals(0, r.client.confirmCalls)
        assertTrue(r.s.enabled)
        assertEquals("+14155550123", r.store.phone)
    }

    @Test fun `legacy 409 shows the take-over state and offers the code flow, without looping`() = runTest {
        val r = rig(); r.client.start = StartResult.Unsupported; r.client.link = LinkResult.OtpRequired
        r.c.begin("+1"); advanceUntilIdle()
        assertEquals(LinkNotice.CONFLICT_NEEDS_CODE, r.s.notice)
        assertFalse(r.s.enabled)
        assertEquals(1, r.client.linkCalls)
        // The user taps "Verify with a code": the relay now supports /start.
        r.client.start = StartResult.Sent("c9", 300)
        r.c.begin("+1"); advanceUntilIdle()
        assertEquals(LinkPhase.AWAITING_CODE, r.s.phase)
        assertEquals(1, r.client.linkCalls)
    }

    @Test fun `new server compat off - the OTP flow is all that is used`() = runTest {
        val r = rig()
        r.c.begin("+1"); advanceUntilIdle(); r.c.submitCode("+1", "123456"); advanceUntilIdle()
        assertTrue(r.s.enabled); assertEquals(0, r.client.linkCalls)
    }

    @Test fun `legacy 401 or 403 marks reconnect needed`() = runTest {
        val r = rig(); r.client.start = StartResult.Unsupported; r.client.link = LinkResult.Unauthorized
        r.c.begin("+1"); advanceUntilIdle()
        assertTrue(r.store.reconnect)
        assertEquals(LinkNotice.REJECTED, r.s.notice)
    }

    // ---- unlink ----

    @Test fun `disconnect clears locally and revokes on the server`() = runTest {
        val r = rig(); r.store.enabled = true
        r.c.disconnect(); advanceUntilIdle()
        assertNull(r.store.token)
        assertFalse(r.s.enabled)
        assertEquals("tok", r.client.lastUnlinkToken)
        assertEquals(LinkNotice.NONE, r.s.notice)
    }

    @Test fun `disconnect with an unreachable server still clears locally and says so`() = runTest {
        val r = rig(); r.store.enabled = true; r.client.unlink = UnlinkResult.Unreachable
        r.c.disconnect(); advanceUntilIdle()
        assertNull(r.store.token)
        assertFalse(r.s.enabled)
        assertEquals(LinkNotice.DISCONNECTED_SERVER_UNREACHABLE, r.s.notice)
    }

    @Test fun `disconnect against an old server says it cannot revoke remotely`() = runTest {
        val r = rig(); r.client.unlink = UnlinkResult.Unsupported
        r.c.disconnect(); advanceUntilIdle()
        assertEquals(LinkNotice.DISCONNECTED_NO_REMOTE_REVOKE, r.s.notice)
    }

    @Test fun `disconnect with no token makes no server call`() = runTest {
        val r = rig(); r.store.token = null
        r.c.disconnect(); advanceUntilIdle()
        assertEquals(0, r.client.unlinkCalls)
    }
}
