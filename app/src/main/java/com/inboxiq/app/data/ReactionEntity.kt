package com.inboxiq.app.data

import androidx.room.Entity
import java.security.MessageDigest

/**
 * The user's own emoji reaction to one message.
 *
 * Reactions are keyed by the message's natural key, not its local row id: ids change when a
 * backup is restored and the Android provider id is not stored at all. There is deliberately no
 * foreign key to `messages`: delete-with-undo re-inserts the very same rows, and a cascade would
 * drop the reaction inside the undo window. [ThreadActions] removes reactions when a delete is
 * committed instead.
 */
@Entity(
    tableName = "reactions",
    primaryKeys = ["address", "timestamp", "isIncoming", "bodyHash"],
)
data class ReactionEntity(
    val address: String,
    /** Timestamp of the message that was reacted to. */
    val timestamp: Long,
    /** Direction of the message that was reacted to. */
    val isIncoming: Boolean,
    /** [ReactionKey.bodyHash] of the target; empty for a picture message (its body changes in place). */
    val bodyHash: String,
    val emoji: String,
    val createdAt: Long,
    /** True once this reaction went out as a text message (see sms/ReactionSender). */
    val sentAsText: Boolean = false,
)

/** How a [MessageEntity] maps onto the key columns of [ReactionEntity]. One place, so storage, lookup and cleanup agree. */
object ReactionKey {
    /** Body the MMS importer writes while a picture message has not finished downloading. */
    const val MMS_PLACEHOLDER = "[MMS message]"

    fun isMms(m: MessageEntity): Boolean = m.imagePartUri != null || m.body == MMS_PLACEHOLDER

    /** Empty for MMS, because an MMS body is rewritten in place once its parts arrive; a short digest of the text otherwise. */
    fun bodyHash(m: MessageEntity): String = if (isMms(m)) "" else hashOf(m.body)

    fun hashOf(body: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        return md.take(8).joinToString("") { "%02x".format(it) }
    }

    fun forMessage(m: MessageEntity, emoji: String, createdAt: Long, sentAsText: Boolean = false) =
        ReactionEntity(m.address, m.timestamp, m.isIncoming, bodyHash(m), emoji, createdAt, sentAsText)

    /**
     * A reaction belongs to a message when address, time and direction agree and either the text digest agrees
     * or the reaction was filed while the message was still an MMS placeholder (empty digest).
     */
    fun matches(r: ReactionEntity, m: MessageEntity): Boolean =
        r.address == m.address && r.timestamp == m.timestamp && r.isIncoming == m.isIncoming &&
            (r.bodyHash.isEmpty() || r.bodyHash == hashOf(m.body))

    /** The reaction for each message of a thread, by message id. One pass over the thread, no per-bubble lookups. */
    fun byMessageId(messages: List<MessageEntity>, reactions: List<ReactionEntity>): Map<Long, ReactionEntity> {
        if (reactions.isEmpty() || messages.isEmpty()) return emptyMap()
        val byTime = reactions.groupBy { it.timestamp }
        val out = HashMap<Long, ReactionEntity>(reactions.size * 2)
        for (m in messages) {
            val candidates = byTime[m.timestamp] ?: continue
            val hit = candidates.firstOrNull { matches(it, m) } ?: continue
            out[m.id] = hit
        }
        return out
    }
}
