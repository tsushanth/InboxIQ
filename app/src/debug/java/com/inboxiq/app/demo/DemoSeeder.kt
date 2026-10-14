package com.inboxiq.app.demo

import androidx.room.withTransaction
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.data.ThreadLabelEntity
import com.inboxiq.app.data.ThreadStateEntity

/**
 * Fills InboxIQ's own Room database with obviously fake conversations for the Play store screenshots.
 * Debug builds only (src/debug): it takes just the database, so it cannot reach the SMS provider,
 * contacts, or the network. Every message gets threadId 0, which keeps the provider read-sync a no-op.
 * Numbers are 555-01xx; senders are invented brands.
 */
object DemoSeeder {

    data class Contact(val address: String, val name: String?, val label: MessageLabel)

    val contacts = listOf(
        Contact("+15555550101", "Alex Rivera", MessageLabel.PERSONAL),
        Contact("+15555550102", "Jordan Lee", MessageLabel.PERSONAL),
        Contact("+15555550103", "Sam Patel", MessageLabel.WORK),
        Contact("+15555550104", "Priya Nair", MessageLabel.WORK),
        Contact("Northline Bank", null, MessageLabel.OTP_2FA),
        Contact("Verifyd", null, MessageLabel.OTP_2FA),
        Contact("Greenleaf Market", null, MessageLabel.PROMO),
        Contact("Skyline Cafe", null, MessageLabel.PROMO),
        Contact("+15555550188", null, MessageLabel.SCAM),
        Contact("+15555550177", null, MessageLabel.SPAM),
    )

    const val PIN_ADDRESS = "+15555550101"
    const val DRAFT_ADDRESS = "+15555550102"
    const val DRAFT_TEXT = "Sure, I'll grab two cartons"
    const val MMS_ADDRESS = "+15555550101"
    const val SCAM_ADDRESS = "+15555550188"
    const val CODE_ADDRESS = "Northline Bank"

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    data class Result(val messages: Int, val threads: Int)

    /** Replaces everything in the app's own database with the demo set. [photoUri] is a local image for the one MMS placeholder. */
    suspend fun seed(db: AppDatabase, now: Long, photoUri: String? = null): Result {
        val label = contacts.associate { it.address to it.label }
        val rows = messages(now, photoUri).map { (a, m) ->
            m.copy(address = a, label = if (m.isIncoming) label.getValue(a) else MessageLabel.UNLABELED,
                labelConfidence = if (m.isIncoming) 0.93f else 0f)
        }
        db.withTransaction {
            db.clearAllTables()
            db.messageDao().insertAll(rows)
            contacts.forEach { db.threadLabelDao().upsert(ThreadLabelEntity(it.address, it.label, 0.93f, now)) }
            db.threadStateDao().ensure(ThreadStateEntity(PIN_ADDRESS, pinnedAt = now - 2 * HOUR))
            db.threadStateDao().ensure(ThreadStateEntity(DRAFT_ADDRESS, draft = DRAFT_TEXT))
        }
        return Result(rows.size, rows.map { it.address }.distinct().size)
    }

    private fun m(body: String, ago: Long, now: Long, incoming: Boolean = true, unread: Boolean = false, photo: String? = null) =
        MessageEntity(
            threadId = 0, address = "", body = body, timestamp = now - ago, isIncoming = incoming,
            isRead = !unread, sendStatus = if (incoming) SendStatus.NONE else SendStatus.SENT, imagePartUri = photo,
        )

    private fun messages(now: Long, photoUri: String?): List<Pair<String, MessageEntity>> = buildList {
        fun add(a: String, vararg ms: MessageEntity) = ms.forEach { add(a to it) }
        add("+15555550101",
            m("Hey! How was the trip?", 3 * DAY, now),
            m("So good, I'll send photos tonight", 3 * DAY - 5 * MIN, now, incoming = false),
            m("", 2 * DAY, now, photo = photoUri),
            m("That view is unreal", 2 * DAY - 2 * MIN, now),
            m("Are we still on for dinner Friday? I booked a table for 7.", 25 * MIN, now, unread = true))
        add("+15555550102",
            m("Running late, start without me", 5 * HOUR, now),
            m("No worries, saving you a seat", 5 * HOUR - MIN, now, incoming = false),
            m("Can you grab milk on the way home?", 90 * MIN, now, unread = true))
        add("+15555550103",
            m("Morning! Did the client reply about the proposal?", DAY + 3 * HOUR, now),
            m("Not yet, I'll chase them after lunch", DAY + 2 * HOUR, now, incoming = false),
            m("Standup moved to 10:30. Can you share the Q3 deck before then?", 40 * MIN, now, unread = true))
        add("+15555550104",
            m("Invoice approved, payment goes out Thursday.", 2 * DAY + HOUR, now))
        add("Northline Bank",
            m("Your Northline Bank verification code is 482913. It expires in 10 minutes. Never share this code.", 8 * MIN, now, unread = true))
        add("Verifyd",
            m("Verifyd: 739104 is your login code. Don't share it with anyone.", 3 * DAY, now))
        add("Greenleaf Market",
            m("Greenleaf Market: weekend deal, 20% off all produce. Show this text in store. Reply STOP to opt out.", 6 * HOUR, now))
        add("Skyline Cafe",
            m("Skyline Cafe: free pastry with any coffee today before noon. Reply STOP to unsubscribe.", DAY + 5 * HOUR, now))
        add("+15555550188",
            m("Your package is on hold. Pay a \$1.99 redelivery fee within 24 hours: http://parcel-post-redeliver.top/pay", 3 * HOUR, now, unread = true))
        add("+15555550177",
            m("Congratulations! You have won a \$500 gift card. Claim now: http://claim-prize.win/ab12", 4 * DAY, now))
    }
}
