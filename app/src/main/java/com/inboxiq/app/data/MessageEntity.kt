package com.inboxiq.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["address", "timestamp", "body"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val label: MessageLabel = MessageLabel.UNLABELED,
    val labelConfidence: Float = 0f,
    val labeledAt: Long? = null,
    val sendStatus: SendStatus = SendStatus.NONE,
    val isRead: Boolean = true,
    /** content://mms/part/{id} of the first image attachment, if this MMS has one. */
    val imagePartUri: String? = null,
    /** True once a send has failed and is pending a local retry (see MmsSender.retry). */
    val awaitingAutoHeal: Boolean = false,
    /** Failed retries so far — capped at MmsSender.MAX_AUTO_HEAL_RETRIES before giving up permanently. */
    val autoHealRetryCount: Int = 0,
    /**
     * 0-1 confidence this message's text was AI-generated — only ever populated by the
     * MID/HIGH classifier tiers (see GemmaClassifier); null when unevaluated (DEFAULT tier,
     * or an outgoing message). Surfaced as a secondary badge, not a primary label, since
     * accuracy on SMS-length text is genuinely lower than on long-form text — see ClassifierTier.
     */
    val aiGeneratedConfidence: Float? = null,
)

enum class SendStatus { NONE, PENDING, SENT, FAILED }
