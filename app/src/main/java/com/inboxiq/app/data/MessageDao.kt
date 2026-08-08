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

    /** One row per address: its most recent message + unread count, for the thread-list screen. */
    @Query(
        """
        SELECT m.*, (
            SELECT COUNT(*) FROM messages u
            WHERE u.address = m.address AND u.isIncoming = 1 AND u.isRead = 0
        ) AS unreadCount
        FROM messages m
        INNER JOIN (SELECT address, MAX(timestamp) AS maxTs FROM messages GROUP BY address) latest
        ON m.address = latest.address AND m.timestamp = latest.maxTs
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

    @Query("UPDATE messages SET label = :label, labelConfidence = :confidence WHERE id = :id")
    suspend fun updateLabel(id: Long, label: MessageLabel, confidence: Float)

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
