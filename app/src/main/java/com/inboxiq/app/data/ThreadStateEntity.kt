package com.inboxiq.app.data

import androidx.room.Entity

/**
 * Per-conversation state the user controls: pin, archive, mute, unsent draft, and whether they
 * hand-corrected the on-device label. Kept in its own table (keyed by address, like
 * thread_labels) so adding it never touches the messages table.
 */
@Entity(tableName = "thread_state", primaryKeys = ["address"])
data class ThreadStateEntity(
    val address: String,
    /** Epoch millis the thread was pinned (pins sort by this, newest pin last); null when not pinned. */
    val pinnedAt: Long? = null,
    val archived: Boolean = false,
    /** Notifications are silenced until this time; Long.MAX_VALUE means until unmuted. */
    val mutedUntil: Long? = null,
    val draft: String? = null,
    /** True once the user corrected the label; automatic re-classification must not override it. */
    val labelCorrected: Boolean = false,
) {
    fun isMuted(now: Long): Boolean = mutedUntil != null && mutedUntil > now
}
