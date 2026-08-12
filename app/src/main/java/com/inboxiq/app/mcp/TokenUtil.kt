package com.inboxiq.app.mcp

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Bearer tokens are random and shown to the caller once, at pairing time — only their hash is ever persisted. */
object TokenUtil {
    private val random = SecureRandom()

    fun randomToken(byteLength: Int = 32): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
