package com.inboxiq.app.mcp

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RelayLinkClientTest {
    private fun client(code: Int? , seen: MutableList<okhttp3.Request> = mutableListOf()): HttpRelayLinkClient {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen += chain.request()
            if (code == null) throw IOException("down")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("m").body("{}".toResponseBody(null)).build()
        }).build()
        return HttpRelayLinkClient(http, "https://example.invalid")
    }

    @Test fun `200 is linked`() = runTest { assertEquals(LinkResult.Linked, client(200).link("+1555", "t")) }
    @Test fun `401 and 403 are unauthorized`() = runTest {
        assertEquals(LinkResult.Unauthorized, client(401).link("+1555", "t"))
        assertEquals(LinkResult.Unauthorized, client(403).link("+1555", "t"))
    }
    @Test fun `500 and network errors are failures not unauthorized`() = runTest {
        assertTrue(client(500).link("+1555", "t") is LinkResult.Failed)
        assertTrue(client(null).link("+1555", "t") is LinkResult.Failed)
    }
    @Test fun `posts to phone link`() = runTest {
        val seen = mutableListOf<okhttp3.Request>()
        client(200, seen).link("+1555", "t")
        assertEquals("https://example.invalid/phone/link", seen.single().url.toString())
        assertEquals("POST", seen.single().method)
    }
}
