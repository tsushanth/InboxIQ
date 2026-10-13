package com.inboxiq.app.mcp

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Wire-level tests of [HttpRelayLinkClient] against a scripted fake HTTP layer (no network). */
class RelayLinkProtocolTest {
    private class Reply(val code: Int, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    private class Fake(val routes: Map<String, Reply>, val down: Boolean = false) {
        val seen = mutableListOf<Pair<Request, String>>()
        fun client(): HttpRelayLinkClient {
            val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buf = Buffer(); req.body?.writeTo(buf)
                seen += req to buf.readUtf8()
                if (down) throw IOException("down")
                val r = routes["${req.method} ${req.url.encodedPath}"] ?: Reply(404, "<html>Cannot ${req.method}</html>")
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(r.code).message("m")
                    .apply { r.headers.forEach { (k, v) -> header(k, v) } }
                    .body(r.body.toResponseBody(null)).build()
            }).build()
            return HttpRelayLinkClient(http, "https://example.invalid")
        }
    }

    private fun fake(vararg r: Pair<String, Reply>, down: Boolean = false) = Fake(mapOf(*r), down)

    // ---- new flow ----

    @Test fun `start verification posts the phone number and returns the challenge`() = runTest {
        val f = fake("POST /phone/link/start" to Reply(200, """{"challenge_id":"c1","expires_in":300}"""))
        val r = f.client().startVerification("+14155550123")
        assertEquals(StartResult.Sent("c1", 300), r)
        assertEquals("""{"phone_number":"+14155550123"}""", f.seen.single().second)
    }

    @Test fun `confirm sends phone, token, challenge and code and succeeds`() = runTest {
        val f = fake("POST /phone/link" to Reply(200, """{"ok":true,"link_proof":"otp","replaced":false}"""))
        assertEquals(LinkResult.Linked, f.client().confirm("+14155550123", "tok", "c1", "123456"))
        val body = f.seen.single().second
        listOf("phone_number", "relay_token", "challenge_id", "otp_code").forEach { assertTrue(body, body.contains("\"$it\"")) }
        assertFalse("OTP must never be in the URL", f.seen.single().first.url.toString().contains("123456"))
    }

    @Test fun `wrong code`() = runTest {
        val f = fake("POST /phone/link" to Reply(401, """{"error":"otp_invalid"}"""))
        assertEquals(LinkResult.OtpInvalid, f.client().confirm("+1", "t", "c", "000000"))
    }

    @Test fun `expired code`() = runTest {
        val f = fake("POST /phone/link" to Reply(401, """{"error":"otp_expired"}"""))
        assertEquals(LinkResult.OtpExpired, f.client().confirm("+1", "t", "c", "000000"))
    }

    @Test fun `lockout is a rate limit and carries Retry-After`() = runTest {
        val f = fake("POST /phone/link" to Reply(429, """{"error":"rate_limited"}""", mapOf("Retry-After" to "1800")))
        assertEquals(LinkResult.RateLimited(1800), f.client().confirm("+1", "t", "c", "000000"))
        val g = fake("POST /phone/link" to Reply(429, """{"error":"rate_limited"}"""))
        assertEquals(LinkResult.RateLimited(null), g.client().confirm("+1", "t", "c", "000000"))
    }

    @Test fun `start errors map to plain results`() = runTest {
        assertEquals(StartResult.RateLimited(60), fake("POST /phone/link/start" to Reply(429, "{}", mapOf("Retry-After" to "60"))).client().startVerification("+1"))
        assertEquals(StartResult.InvalidPhone, fake("POST /phone/link/start" to Reply(400, """{"error":"invalid_phone_number"}""")).client().startVerification("+1"))
        assertEquals(StartResult.DestinationNotAllowed, fake("POST /phone/link/start" to Reply(400, """{"error":"destination_not_allowed"}""")).client().startVerification("+1"))
        assertEquals(StartResult.DeliveryFailed, fake("POST /phone/link/start" to Reply(502, """{"error":"delivery_failed"}""")).client().startVerification("+1"))
        assertEquals(StartResult.DeliveryFailed, fake("POST /phone/link/start" to Reply(503, """{"error":"otp_delivery_unavailable"}""")).client().startVerification("+1"))
        assertTrue(fake(down = true).client().startVerification("+1") is StartResult.Failed)
        assertTrue(fake("POST /phone/link/start" to Reply(200, "{}")).client().startVerification("+1") is StartResult.Failed)
    }

    // ---- compatibility ----

    @Test fun `old server - missing start endpoint is Unsupported (404 and 405)`() = runTest {
        assertEquals(StartResult.Unsupported, fake().client().startVerification("+1"))
        assertEquals(StartResult.Unsupported, fake("POST /phone/link/start" to Reply(405)).client().startVerification("+1"))
    }

    @Test fun `legacy link body is unchanged and old server answers 200`() = runTest {
        val f = fake("POST /phone/link" to Reply(200, """{"ok":true}"""))
        assertEquals(LinkResult.Linked, f.client().link("+1555", "t"))
        assertEquals("""{"phone_number":"+1555","relay_token":"t"}""", f.seen.single().second)
    }

    @Test fun `new server compat on - 409 link_conflict means a code is required`() = runTest {
        val f = fake("POST /phone/link" to Reply(409, """{"error":"link_conflict","otp_required":true}"""))
        assertEquals(LinkResult.OtpRequired, f.client().link("+1", "t"))
    }

    @Test fun `new server compat off - 403 otp_required means a code is required, other 403 is unauthorized`() = runTest {
        assertEquals(LinkResult.OtpRequired, fake("POST /phone/link" to Reply(403, """{"error":"otp_required"}""")).client().link("+1", "t"))
        assertEquals(LinkResult.Unauthorized, fake("POST /phone/link" to Reply(403, "{}")).client().link("+1", "t"))
    }

    // ---- unlink ----

    @Test fun `unlink sends DELETE with the bearer token`() = runTest {
        val f = fake("DELETE /phone/link" to Reply(200, """{"ok":true}"""))
        assertEquals(UnlinkResult.Done, f.client().unlink("secret-token"))
        val req = f.seen.single().first
        assertEquals("DELETE", req.method)
        assertEquals("Bearer secret-token", req.header("Authorization"))
    }

    @Test fun `unlink - already unknown token (401) counts as done`() = runTest {
        assertEquals(UnlinkResult.Done, fake("DELETE /phone/link" to Reply(401, """{"error":"unauthorized"}""")).client().unlink("t"))
    }

    @Test fun `unlink - old server and unreachable server are reported distinctly`() = runTest {
        assertEquals(UnlinkResult.Unsupported, fake().client().unlink("t"))
        assertEquals(UnlinkResult.Unreachable, fake("DELETE /phone/link" to Reply(500)).client().unlink("t"))
        assertEquals(UnlinkResult.Unreachable, fake(down = true).client().unlink("t"))
    }
}
