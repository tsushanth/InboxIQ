package com.inboxiq.app.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Hand-built MMS wire bytes for tests (the library's composer cannot write an m-retrieve-conf). */
object MmsTestPdus {
    val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, 0xFF.toByte(), 0xD9.toByte())

    /**
     * An m-retrieve-conf (MMS 1.2) from [from], dated [dateSeconds], with one image/jpeg part named pic.jpg and, when
     * [text] is not null, one text/plain part.
     */
    fun retrieveConf(from: String = "+15551230000/TYPE=PLMN", dateSeconds: Long = 1_700_000_000L, image: ByteArray? = JPEG, text: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x8C.toByte(), 0x84.toByte()))                 // X-Mms-Message-Type: m-retrieve-conf
        out.write(0x98); out.write("T1".toByteArray()); out.write(0)           // X-Mms-Transaction-Id
        out.write(byteArrayOf(0x8D.toByte(), 0x92.toByte()))                 // X-Mms-MMS-Version: 1.2
        out.write(0x85); out.write(4)                                          // Date: long-integer, 4 octets
        out.write(byteArrayOf((dateSeconds shr 24).toByte(), (dateSeconds shr 16).toByte(), (dateSeconds shr 8).toByte(), dateSeconds.toByte()))
        val addr = from.toByteArray()
        out.write(0x89); out.write(addr.size + 2); out.write(0x80); out.write(addr); out.write(0) // From: address-present
        out.write(0x84); out.write(0xA3)                                       // Content-Type: application/vnd.wap.multipart.mixed

        val parts = ArrayList<ByteArray>()
        if (text != null) parts += part(byteArrayOf(0x83.toByte()), text.toByteArray())      // text/plain
        if (image != null) {
            val h = ByteArrayOutputStream()
            h.write(0x9E)                                                         // image/jpeg
            h.write(0x8E); h.write("pic.jpg".toByteArray()); h.write(0)           // Content-Location
            parts += part(h.toByteArray(), image)
        }
        out.write(parts.size)                                                     // number of entries (uintvar, < 128)
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun part(headers: ByteArray, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(uintvar(headers.size)); o.write(uintvar(data.size)); o.write(headers); o.write(data)
        return o.toByteArray()
    }

    private fun uintvar(v: Int): ByteArray {
        if (v < 128) return byteArrayOf(v.toByte())
        val groups = ArrayList<Int>()
        var x = v
        while (x > 0) { groups.add(0, x and 0x7F); x = x shr 7 }
        return ByteArray(groups.size) { i -> (groups[i] or if (i < groups.size - 1) 0x80 else 0).toByte() }
    }
}

/**
 * A stand-in for the system MMS and MMS-SMS providers: records inserts, updates and deletes, hands out ids, serves
 * a notification row, answers the thread-id lookup, and captures what is written to part files.
 */
class FakeMmsStore(private val appContext: Context) {
    data class Insert(val uri: Uri, val values: ContentValues, val id: Long)

    val inserts = ArrayList<Insert>()
    val updates = ArrayList<Pair<Uri, ContentValues>>()
    val deletes = ArrayList<Uri>()
    /** Bytes written to each part uri (Robolectric serves streams from a registry, not from the provider's openFile). */
    val partData = HashMap<String, ByteArrayOutputStream>()
    var failInserts = false
    /** The notification-indication row's values, served at content://mms/<id>. */
    val notificationRows = HashMap<Long, ContentValues>()
    private var nextId = 100L

    private val provider = object : ContentProvider() {
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (failInserts) throw IllegalStateException("provider down")
            val id = nextId++
            inserts += Insert(uri, ContentValues(values!!), id)
            val last = uri.lastPathSegment
            return when {
                last == "part" -> Uri.parse("content://mms/part/$id").also { partUri ->
                    val sink = ByteArrayOutputStream()
                    partData[partUri.toString()] = sink
                    org.robolectric.Shadows.shadowOf(appContext.contentResolver).registerOutputStream(partUri, sink)
                }
                last == "addr" -> Uri.parse("content://mms/addr/$id")
                else -> Uri.parse("content://mms/$id")
            }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updates += uri to ContentValues(values!!); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes += uri; return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val cols = projection ?: arrayOf("_id")
            val c = MatrixCursor(cols)
            if (uri.authority == "mms-sms") { c.addRow(cols.map { 77L }); return c } // thread id lookup
            val id = uri.lastPathSegment?.toLongOrNull()
            val row = id?.let { notificationRows[it] }
            if (row != null) c.addRow(cols.map { row.get(it) })
            return c
        }
        override fun getType(uri: Uri): String? = null
    }

    fun register() {
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("mms", provider)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("mms-sms", provider)
    }

    /** The inserts into part tables. */
    fun partInserts() = inserts.filter { it.uri.lastPathSegment == "part" }
    fun addrInserts() = inserts.filter { it.uri.lastPathSegment == "addr" }
    fun messageInserts() = inserts.filter { it.uri.toString() == "content://mms/inbox" }
}
