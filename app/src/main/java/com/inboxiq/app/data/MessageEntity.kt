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
)

enum class SendStatus { NONE, PENDING, SENT, FAILED }
