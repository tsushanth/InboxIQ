package com.inboxiq.app.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.InputStream
import java.io.OutputStream

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ImportResult(val added: Int, val alreadyPresent: Int, val blocked: Int)

/**
 * Local backup of conversations to a file the user picks (system file picker, so no storage
 * permission and nothing leaves the phone). Text, labels, pins/archive/mute and blocked
 * numbers are included. Photos are not: they live in app-private storage and would make the
 * file huge; the export screen says so.
 */
class BackupManager(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    companion object {
        const val FORMAT = "inboxiq-backup"
        const val VERSION = 1
        private const val PAGE = 500
    }

    suspend fun export(out: OutputStream): Int {
        var written = 0
        JsonWriter(out.bufferedWriter(Charsets.UTF_8)).use { w ->
            w.setIndent("")
            w.beginObject()
            w.name("format").value(FORMAT)
            w.name("version").value(VERSION.toLong())
            w.name("exportedAt").value(clock())
            w.name("messages").beginArray()
            var after = 0L
            while (true) {
                val page = db.messageDao().pageAfter(after, PAGE)
                if (page.isEmpty()) break
                page.forEach { writeMessage(w, it); written++ }
                after = page.last().id
            }
            w.endArray()
            w.name("threadState").beginArray()
            db.threadStateDao().all().forEach { writeState(w, it) }
            w.endArray()
            w.name("blocked").beginArray()
            db.blockedNumberDao().all().forEach { w.beginObject().name("address").value(it.address).name("blockedAt").value(it.blockedAt).endObject() }
            w.endArray()
            w.endObject()
        }
        return written
    }

    suspend fun import(input: InputStream): ImportResult {
        var added = 0; var dup = 0; var blockedCount = 0
        JsonReader(input.bufferedReader(Charsets.UTF_8)).use { r ->
            try {
                if (r.peek() != JsonToken.BEGIN_OBJECT) throw BackupFormatException("This is not an InboxIQ backup file.")
                r.beginObject()
                var seenFormat = false
                val batch = ArrayList<MessageEntity>(PAGE)
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    for (m in batch) { if (db.messageDao().insert(m) > 0) added++ else dup++ }
                    batch.clear()
                }
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "format" -> { if (r.nextString() != FORMAT) throw BackupFormatException("This is not an InboxIQ backup file."); seenFormat = true }
                        "version" -> { val v = r.nextInt(); if (v > VERSION) throw BackupFormatException("This backup was made by a newer version of InboxIQ. Update the app and try again.") }
                        "messages" -> {
                            if (!seenFormat) throw BackupFormatException("This is not an InboxIQ backup file.")
                            r.beginArray()
                            while (r.hasNext()) { batch += readMessage(r); if (batch.size >= PAGE) flush() }
                            r.endArray(); flush()
                        }
                        "threadState" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                val s = readState(r)
                                // Never overwrite what the user has set on this phone; only fill in conversations with no state yet.
                                db.threadStateDao().ensure(s)
                            }
                            r.endArray()
                        }
                        "blocked" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                var a: String? = null; var t = clock()
                                r.beginObject()
                                while (r.hasNext()) when (r.nextName()) { "address" -> a = r.nextString(); "blockedAt" -> t = r.nextLong(); else -> r.skipValue() }
                                r.endObject()
                                a?.let { db.blockedNumberDao().block(BlockedNumberEntity(it, t)); blockedCount++ }
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (!seenFormat) throw BackupFormatException("This is not an InboxIQ backup file.")
            } catch (e: BackupFormatException) {
                throw e
            } catch (e: Exception) {
                throw BackupFormatException("The file is damaged or incomplete, so nothing more was imported. Messages read before the problem were kept.", e)
            }
        }
        return ImportResult(added, dup, blockedCount)
    }

    private fun writeMessage(w: JsonWriter, m: MessageEntity) {
        w.beginObject()
        w.name("address").value(m.address)
        w.name("body").value(m.body)
        w.name("timestamp").value(m.timestamp)
        w.name("isIncoming").value(m.isIncoming)
        w.name("label").value(m.label.name)
        w.name("labelConfidence").value(m.labelConfidence.toDouble())
        w.name("isRead").value(m.isRead)
        w.name("sendStatus").value(m.sendStatus.name)
        w.name("hasPhoto").value(m.imagePartUri != null)
        w.endObject()
    }

    private fun readMessage(r: JsonReader): MessageEntity {
        var address = ""; var body = ""; var ts = 0L; var incoming = true
        var label = MessageLabel.UNLABELED; var conf = 0f; var read = true; var status = SendStatus.NONE
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "address" -> address = r.nextString()
                "body" -> body = r.nextString()
                "timestamp" -> ts = r.nextLong()
                "isIncoming" -> incoming = r.nextBoolean()
                "label" -> { val v = r.nextString(); label = MessageLabel.entries.find { it.name == v } ?: MessageLabel.UNLABELED }
                "labelConfidence" -> conf = r.nextDouble().toFloat()
                "isRead" -> read = r.nextBoolean()
                "sendStatus" -> { val v = r.nextString(); status = SendStatus.entries.find { it.name == v } ?: SendStatus.NONE }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (address.isBlank() || ts <= 0) throw BackupFormatException("A message in the file has no sender or time.")
        // A message that was still sending when the backup was made must not look like it is still in flight.
        val safeStatus = if (status == SendStatus.PENDING) SendStatus.NONE else status
        return MessageEntity(threadId = 0, address = address, body = body, timestamp = ts, isIncoming = incoming, label = label, labelConfidence = conf, sendStatus = safeStatus, isRead = read)
    }

    private fun writeState(w: JsonWriter, s: ThreadStateEntity) {
        w.beginObject()
        w.name("address").value(s.address)
        s.pinnedAt?.let { w.name("pinnedAt").value(it) }
        w.name("archived").value(s.archived)
        s.mutedUntil?.let { w.name("mutedUntil").value(it) }
        w.name("labelCorrected").value(s.labelCorrected)
        w.endObject()
    }

    private fun readState(r: JsonReader): ThreadStateEntity {
        var address = ""; var pinned: Long? = null; var archived = false; var muted: Long? = null; var corrected = false
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "address" -> address = r.nextString()
            "pinnedAt" -> pinned = r.nextLong()
            "archived" -> archived = r.nextBoolean()
            "mutedUntil" -> muted = r.nextLong()
            "labelCorrected" -> corrected = r.nextBoolean()
            else -> r.skipValue()
        }
        r.endObject()
        return ThreadStateEntity(address, pinned, archived, muted, null, corrected)
    }
}
