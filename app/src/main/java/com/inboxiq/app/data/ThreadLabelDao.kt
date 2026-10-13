package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inboxiq.app.classify.BareReplies

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(threadLabels: List<ThreadLabelEntity>)

    /** The newest few incoming messages of a conversation, newest first: what the classifier reads. */
    @Query("SELECT body FROM messages WHERE address = :address AND isIncoming = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentIncomingBodies(address: String, limit: Int): List<String>

    /** Id of the newest incoming message, for per-message scores that only apply to the sample actually read. */
    @Query("SELECT id FROM messages WHERE address = :address AND isIncoming = 1 ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestIncomingMessageId(address: String): Long?

    /**
     * How many times the user really answered this address. A bare STOP, YES, C or 1 sent to a short code
     * is an opt-out or a button press, not a conversation, so those do not count.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE address = :address AND isIncoming = 0
        AND UPPER(TRIM(body)) NOT IN (${BareReplies.SQL_LIST})
        """,
    )
    suspend fun substantiveReplies(address: String): Int

    /**
     * Addresses whose label predates the current classifier version and which the user has not corrected.
     * Re-sorting them is how an upgrade fixes the labels an older build got wrong; hand-filed threads are
     * never touched.
     */
    @Query(
        """
        SELECT t.address FROM thread_labels t
        LEFT JOIN thread_state s ON s.address = t.address
        WHERE t.classifiedAt < :cutoff AND COALESCE(s.labelCorrected, 0) = 0
        LIMIT :limit
        """,
    )
    suspend fun staleAddresses(cutoff: Long, limit: Int): List<String>

    @Query("UPDATE thread_labels SET classifiedAt = :now WHERE address = :address")
    suspend fun touch(address: String, now: Long)

    @Query("DELETE FROM thread_labels WHERE address = :address")
    suspend fun delete(address: String)
}
