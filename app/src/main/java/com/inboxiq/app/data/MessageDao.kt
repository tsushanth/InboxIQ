package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE label = :label ORDER BY timestamp DESC")
    fun observeByLabel(label: MessageLabel): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE address = :address ORDER BY timestamp ASC")
    fun observeThread(address: String): Flow<List<MessageEntity>>

    /**
     * One row per address: its most recent message + unread count, for the thread-list screen.
     *
     * The final GROUP BY m.address is load-bearing, not decorative — confirmed live that a
     * single multi-part MMS (e.g. an image part and a text part landing as two separate
     * provider rows, same conversation turn) can produce two messages with the *exact* same
     * timestamp. The join alone matches both of them to the same address+maxTs pair, handing
     * the LazyColumn two rows with an identical key and crashing
     * ("Key ... was already used"). Collapsing to one row per address after the join is what
     * actually guarantees the "one row per address" the screen depends on.
     */
    @Query(
        """
        SELECT m.*, (
            SELECT COUNT(*) FROM messages u
            WHERE u.address = m.address AND u.isIncoming = 1 AND u.isRead = 0
        ) AS unreadCount
        FROM messages m
        INNER JOIN (SELECT address, MAX(timestamp) AS maxTs FROM messages GROUP BY address) latest
        ON m.address = latest.address AND m.timestamp = latest.maxTs
        GROUP BY m.address
        ORDER BY m.timestamp DESC
        """,
    )
    fun observeThreadList(): Flow<List<ThreadSummary>>

    @Query("UPDATE messages SET isRead = 1 WHERE address = :address AND isRead = 0")
    suspend fun markThreadRead(address: String)

    @Query("UPDATE messages SET sendStatus = :status WHERE id = :id")
    suspend fun updateSendStatus(id: Long, status: SendStatus)

    @Query("UPDATE messages SET awaitingAutoHeal = :awaiting WHERE id = :id")
    suspend fun setAwaitingAutoHeal(id: Long, awaiting: Boolean)

    @Query("SELECT * FROM messages WHERE awaitingAutoHeal = 1")
    suspend fun messagesAwaitingAutoHeal(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    /**
     * Finds a message already synced for this exact address+timestamp, regardless of its body —
     * used by MmsSync to detect a placeholder row it inserted before an image part finished
     * downloading, so a later re-sync can UPDATE it instead of silently no-op'ing (the unique
     * index on address+timestamp+body means an insert with different body/image content is a
     * distinct row, not a conflict, so plain insert-ignore can never self-heal a placeholder).
     */
    @Query("SELECT * FROM messages WHERE address = :address AND timestamp = :timestamp AND isIncoming = :isIncoming LIMIT 1")
    suspend fun findByAddressTimestamp(address: String, timestamp: Long, isIncoming: Boolean): MessageEntity?

    @Query("UPDATE messages SET autoHealRetryCount = autoHealRetryCount + 1 WHERE id = :id")
    suspend fun incrementAutoHealRetryCount(id: Long)

    /** Backing image files to delete before removing these rows — see MmsImageStore. */
    @Query("SELECT imagePartUri FROM messages WHERE address = :address AND imagePartUri IS NOT NULL")
    suspend fun imageUrisForThread(address: String): List<String>

    @Query("UPDATE messages SET label = :label, labelConfidence = :confidence WHERE id = :id")
    suspend fun updateLabel(id: Long, label: MessageLabel, confidence: Float)

    @Query("UPDATE messages SET aiGeneratedConfidence = :confidence WHERE id = :id")
    suspend fun updateAiGeneratedConfidence(id: Long, confidence: Float)

    /** Applies a thread's cached classification to every message from that address — pure SQL, no inference. */
    @Query("UPDATE messages SET label = :label, labelConfidence = :confidence WHERE address = :address AND isIncoming = 1")
    suspend fun applyThreadLabel(address: String, label: MessageLabel, confidence: Float)

    @Query("DELETE FROM messages WHERE address = :address")
    suspend fun deleteThread(address: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /** Case-insensitive body search across every thread, newest first — feeds the search results list. */
    @Query("SELECT * FROM messages WHERE body LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 200")
    fun searchMessages(query: String): Flow<List<MessageEntity>>
}
