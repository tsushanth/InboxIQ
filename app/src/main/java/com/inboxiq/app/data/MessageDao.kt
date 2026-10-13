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

    /**
     * Bulk variant of [insert] for history import. Same IGNORE semantics against the unique
     * (address, timestamp, body) index, so re-importing a row is a no-op; the returned ids are
     * -1 for ignored rows. Call inside one transaction per page so observers invalidate once.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

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

    @Query("SELECT * FROM messages WHERE address = :address AND timestamp = :timestamp AND body = :body LIMIT 1")
    suspend fun findByKey(address: String, timestamp: Long, body: String): MessageEntity?

    @Query("UPDATE messages SET isRead = 0 WHERE id = :id")
    suspend fun markUnread(id: Long)

    /** Provider thread ids of this address's unread incoming rows (0 = never had one), so reads can be mirrored. */
    @Query("SELECT DISTINCT threadId FROM messages WHERE address = :address AND isIncoming = 1 AND isRead = 0 AND threadId != 0")
    suspend fun unreadThreadIds(address: String): List<Long>

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

    /** Outgoing rows for [address] whose timestamp is in [from]..[to] (a sent MMS' whole second). */
    @Query("SELECT * FROM messages WHERE address = :address AND isIncoming = 0 AND timestamp BETWEEN :from AND :to")
    suspend fun outgoingBetween(address: String, from: Long, to: Long): List<MessageEntity>

    /** Timestamps of incoming rows still unread here: lets the one-time read reconcile skip provider rows that cannot matter. */
    @Query("SELECT DISTINCT timestamp FROM messages WHERE isIncoming = 1 AND isRead = 0")
    suspend fun unreadIncomingTimestamps(): List<Long>

    /** Marks the one incoming row with this natural key read. Never touches a read row and never marks anything unread. */
    @Query("UPDATE messages SET isRead = 1 WHERE address = :address AND timestamp = :timestamp AND body = :body AND isIncoming = 1 AND isRead = 0")
    suspend fun markIncomingReadByKey(address: String, timestamp: Long, body: String): Int

    /** Same for an MMS, whose body can still be a placeholder: matched on conversation and provider time only. */
    @Query("UPDATE messages SET isRead = 1 WHERE address = :address AND timestamp = :timestamp AND isIncoming = 1 AND isRead = 0")
    suspend fun markIncomingReadByAddressTimestamp(address: String, timestamp: Long): Int

    @Query("SELECT DISTINCT timestamp FROM messages WHERE isIncoming = 0")
    suspend fun outgoingTimestamps(): List<Long>

    @Query("SELECT * FROM messages WHERE isIncoming = 0 AND imagePartUri IS NOT NULL ORDER BY address, timestamp, id")
    suspend fun outgoingWithAttachment(): List<MessageEntity>

    /** Re-files a row under another conversation address, keeping everything else about it. */
    @Query("UPDATE messages SET address = :address WHERE id = :id")
    suspend fun updateAddress(id: Long, address: String)

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

    /**
     * Literal (wildcard-safe) body search for the in-app search screen. Pass the query through
     * SearchLogic.escapeLike first. The older [searchMessages] is left as-is for its other callers.
     */
    @Query("SELECT * FROM messages WHERE body LIKE '%' || :escapedQuery || '%' ESCAPE '\\' ORDER BY timestamp DESC LIMIT 200")
    fun searchMessagesLiteral(escapedQuery: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    @Query("SELECT * FROM messages WHERE address = :address ORDER BY timestamp ASC")
    suspend fun messagesForThread(address: String): List<MessageEntity>

    /** Re-inserts rows with their original ids (used by undo-delete). Existing ids are left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restoreAll(messages: List<MessageEntity>)

    /** Image attachments in a thread, newest first, for the media gallery. */
    @Query("SELECT * FROM messages WHERE address = :address AND imagePartUri IS NOT NULL ORDER BY timestamp DESC")
    fun observeThreadMedia(address: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    /** Stable paging (by id) so a backup can stream a large history without holding it all in memory. */
    @Query("SELECT * FROM messages WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<MessageEntity>
}
