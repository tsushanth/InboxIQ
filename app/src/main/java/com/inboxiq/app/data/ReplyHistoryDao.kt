package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Query

/** Read-only view of what the user has sent, for learning their phrasing on the fly (see reply.UserStyle). Nothing is stored. */
@Dao
interface ReplyHistoryDao {
    @Query("SELECT body FROM messages WHERE isIncoming = 0 AND body != '' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentSentBodies(limit: Int): List<String>
}
