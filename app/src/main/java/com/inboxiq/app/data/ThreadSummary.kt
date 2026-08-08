package com.inboxiq.app.data

import androidx.room.Embedded

/** One row per conversation for the thread-list screen: latest message + unread count. */
data class ThreadSummary(
    @Embedded val latestMessage: MessageEntity,
    val unreadCount: Int,
)
