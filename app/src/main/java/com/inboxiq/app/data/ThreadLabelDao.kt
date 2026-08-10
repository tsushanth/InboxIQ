package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThreadLabelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(threadLabel: ThreadLabelEntity)

    @Query("SELECT * FROM thread_labels WHERE address = :address LIMIT 1")
    suspend fun get(address: String): ThreadLabelEntity?

    /** Distinct incoming addresses that have messages but no cached thread label yet. */
    @Query(
        """
        SELECT DISTINCT m.address FROM messages m
        LEFT JOIN thread_labels t ON m.address = t.address
        WHERE m.isIncoming = 1 AND t.address IS NULL
        LIMIT :limit
        """,
    )
    suspend fun addressesNeedingClassification(limit: Int): List<String>

    /** Most recent message body for an address — used as the representative sample for classification. */
    @Query("SELECT body FROM messages WHERE address = :address ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestBody(address: String): String?

    /** Id of that same latest message — lets MID/HIGH tiers store aiGeneratedConfidence against the exact sample classified, not the whole thread. */
    @Query("SELECT id FROM messages WHERE address = :address ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestMessageId(address: String): Long?

    @Query("DELETE FROM thread_labels WHERE address = :address")
    suspend fun delete(address: String)
}
