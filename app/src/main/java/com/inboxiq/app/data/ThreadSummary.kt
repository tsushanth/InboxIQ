package com.inboxiq.app.data

import androidx.room.Embedded

/**
 * One row per conversation for the thread-list screen: latest message + unread count + the conversation's own label.
 * The newest message may be one the user sent (history import stores those UNLABELED), so the row's label comes from the
 * conversation's label when there is one ([threadLabel]), not from that message.
 */
data class ThreadSummary(
    @Embedded val latestMessage: MessageEntity,
    val unreadCount: Int,
    val threadLabel: MessageLabel? = null,
)
