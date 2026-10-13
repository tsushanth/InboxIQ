package com.inboxiq.app.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.robolectric.shadows.ShadowContentResolver

data class FakeSms(val id: Long, val thread: Long, val address: String?, val body: String?, val date: Long, val type: Int = 1)
data class FakeMmsPart(val id: Long, val contentType: String, val text: String? = null)
data class FakeMms(val id: Long, val dateSeconds: Long, val box: Int, val from: String?, val parts: List<FakeMmsPart> = emptyList())

/**
 * In-memory stand-in for the Telephony providers (Robolectric has none). Understands only the
 * selections the importers issue: `_id < ?`, `_id > ?`, `_id <= ?`, `date >= ?` joined by AND, and
 * `mid IN (..)` for parts. Rows are synthetic (555-01xx numbers), no real data.
 */
class FakeTelephony {
    val sms = mutableListOf<FakeSms>()
    val mms = mutableListOf<FakeMms>()

    /** Called for every query; throw from here to simulate the process dying mid-import. */
    var onQuery: (Uri) -> Unit = {}
    var securityDenied = false
    var nullCursor = false
    var queryCount = 0
        private set
    var batchedPartQueries = true

    fun register() {
        ShadowContentResolver.registerProviderInternal("sms", Provider())
        ShadowContentResolver.registerProviderInternal("mms", Provider())
    }

    private fun filter(sel: String?, args: Array<out String>?, id: (Int) -> Long, date: (Int) -> Long, n: Int): List<Int> {
        var idx = (0 until n).toList()
        if (sel.isNullOrBlank()) return idx
        var a = 0
        for (clause in sel.split(" AND ")) {
            val c = clause.trim()
            val v = args?.getOrNull(a)?.toLongOrNull()
            when {
                c == "_id < ?" -> { idx = idx.filter { id(it) < v!! }; a++ }
                c == "_id > ?" -> { idx = idx.filter { id(it) > v!! }; a++ }
                c == "_id <= ?" -> { idx = idx.filter { id(it) <= v!! }; a++ }
                c == "date >= ?" -> { idx = idx.filter { date(it) >= v!! }; a++ }
                else -> error("fake provider cannot parse selection clause: $c")
            }
        }
        return idx
    }

    private inner class Provider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queryCount++
            onQuery(uri)
            if (securityDenied) throw SecurityException("denied")
            if (nullCursor) return null
            val seg = uri.pathSegments
            val auth = uri.authority
            return when {
                auth == "sms" && seg.isEmpty() -> smsRows(projection, selection, selectionArgs, sortOrder)
                auth == "mms" && seg.isEmpty() -> mmsRows(projection, selection, selectionArgs, sortOrder)
                auth == "mms" && seg.size == 2 && seg[1] == "addr" -> {
                    val c = MatrixCursor(arrayOf("address", "type"))
                    mms.firstOrNull { it.id == seg[0].toLong() }?.from?.let { c.addRow(arrayOf<Any?>(it, 137)) }
                    c
                }
                auth == "mms" && seg.size == 2 && seg[1] == "part" -> parts(listOf(seg[0].toLong()))
                auth == "mms" && seg == listOf("part") -> {
                    if (!batchedPartQueries) return null
                    val m = Regex("mid IN \\(([0-9,]+)\\)").find(selection ?: "") ?: error("unexpected part selection")
                    parts(m.groupValues[1].split(",").map { it.toLong() })
                }
                else -> error("unexpected uri $uri")
            }
        }

        private fun parts(mids: List<Long>): Cursor {
            val c = MatrixCursor(arrayOf("_id", "mid", "ct", "text"))
            for (m in mms.filter { it.id in mids }) for (p in m.parts) c.addRow(arrayOf<Any?>(p.id, m.id, p.contentType, p.text))
            return c
        }

        private fun smsRows(projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf("_id")
            val idx = filter(sel, args, { sms[it].id }, { sms[it].date }, sms.size)
                .let { if (sort?.contains("_id ASC") == true) it.sortedBy { i -> sms[i].id } else it.sortedByDescending { i -> if (sort?.startsWith("date") == true) sms[i].date else sms[i].id } }
            val c = MatrixCursor(cols, idx.size)
            for (i in idx) {
                val r = sms[i]
                c.addRow(cols.map { col ->
                    when (col) {
                        "_id" -> r.id; "thread_id" -> r.thread; "address" -> r.address
                        "body" -> r.body; "date" -> r.date; "type" -> r.type; else -> null
                    }
                })
            }
            return c
        }

        private fun mmsRows(projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf("_id")
            val idx = filter(sel, args, { mms[it].id }, { mms[it].dateSeconds }, mms.size)
                .let { if (sort?.contains("_id ASC") == true) it.sortedBy { i -> mms[i].id } else it.sortedByDescending { i -> if (sort?.startsWith("date") == true) mms[i].dateSeconds else mms[i].id } }
            val c = MatrixCursor(cols, idx.size)
            for (i in idx) {
                val r = mms[i]
                c.addRow(cols.map { col ->
                    when (col) { "_id" -> r.id; "date" -> r.dateSeconds; "msg_box" -> r.box; else -> null }
                })
            }
            return c
        }

        override fun insert(uri: Uri, values: ContentValues?) = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    companion object {
        /** 555-01xx style synthetic numbers; 692 distinct contacts like the owner's phone profile. */
        fun number(i: Int) = "+1555%07d".format(100 + (i % 692))

        fun seed(smsCount: Int, mmsCount: Int, base: Long = 1_700_000_000_000L): FakeTelephony = FakeTelephony().also { f ->
            for (i in 1..smsCount) f.sms += FakeSms(i.toLong(), (i % 692).toLong(), number(i), "sms body $i", base + i * 1000L, if (i % 2 == 0) 1 else 2)
            for (i in 1..mmsCount) f.mms += FakeMms(
                i.toLong(), (base / 1000) + i * 7L, if (i % 3 == 0) 2 else 1, number(i + 5),
                if (i % 2 == 0) listOf(FakeMmsPart(i * 10L, "image/jpeg")) else listOf(FakeMmsPart(i * 10L, "text/plain", "mms text $i")),
            )
        }
    }
}
