package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Query

/** Read-only view of what the user has sent, for learning their phrasing on the fly (see reply.UserStyle). Nothing is stored. */
@Dao
interface ReplyHistoryDao {
    @Query("SELECT body FROM messages WHERE isIncoming = 0 AND body != '' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentSentBodies(limit: Int): List<String>

    /** The user's recent outgoing rows to one number (for "have I ever texted this person"). */
    @Query("SELECT * FROM messages WHERE address = :address AND isIncoming = 0 ORDER BY timestamp DESC LIMIT 100")
    suspend fun recentSentTo(address: String): List<MessageEntity>
}
