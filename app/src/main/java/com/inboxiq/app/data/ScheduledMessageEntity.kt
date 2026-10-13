package com.inboxiq.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A text message waiting to be sent. Covers both "send later" and the few-second undo window:
 * an undo-send is just a scheduled message whose [sendAt] is a few seconds away and which the
 * user can still cancel. Rows are deleted once handed to the SMS stack or cancelled.
 */
@Entity(tableName = "scheduled_messages", indices = [Index("address"), Index("sendAt")])
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val sendAt: Long,
    val createdAt: Long,
    /** True for the short undo window, false for a user-chosen later time. */
    val isUndoWindow: Boolean = false,
)
