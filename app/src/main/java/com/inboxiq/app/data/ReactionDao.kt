package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    /** Sets or replaces the reaction for a message (one reaction per message). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reaction: ReactionEntity)

    /** Backup restore: never overwrites a reaction already set on this phone. Returns the row id, -1 when ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(reaction: ReactionEntity): Long

    @Query("SELECT * FROM reactions WHERE address = :address AND timestamp = :timestamp AND isIncoming = :isIncoming AND bodyHash IN (:bodyHashes)")
    suspend fun find(address: String, timestamp: Long, isIncoming: Boolean, bodyHashes: List<String>): List<ReactionEntity>

    @Query("DELETE FROM reactions WHERE address = :address AND timestamp = :timestamp AND isIncoming = :isIncoming AND bodyHash IN (:bodyHashes)")
    suspend fun delete(address: String, timestamp: Long, isIncoming: Boolean, bodyHashes: List<String>): Int

    @Query("UPDATE reactions SET sentAsText = 1 WHERE address = :address AND timestamp = :timestamp AND isIncoming = :isIncoming AND bodyHash = :bodyHash")
    suspend fun markSentAsText(address: String, timestamp: Long, isIncoming: Boolean, bodyHash: String)

    /** All reactions of one conversation; the thread screen reads this once and maps it by message. */
    @Query("SELECT * FROM reactions WHERE address = :address")
    fun observeThread(address: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE address = :address")
    suspend fun forThread(address: String): List<ReactionEntity>

    @Query("DELETE FROM reactions WHERE address = :address")
    suspend fun deleteForThread(address: String): Int

    /** Used when a repair pass re-files a message under another address. */
    @Query("UPDATE OR IGNORE reactions SET address = :newAddress WHERE address = :oldAddress AND timestamp = :timestamp AND isIncoming = :isIncoming")
    suspend fun reassignAddress(oldAddress: String, newAddress: String, timestamp: Long, isIncoming: Boolean): Int

    /** Reactions whose message no longer exists (a delete whose undo window never closed, a repair that dropped a duplicate). */
    @Query(
        "DELETE FROM reactions WHERE NOT EXISTS (SELECT 1 FROM messages m WHERE m.address = reactions.address " +
            "AND m.timestamp = reactions.timestamp AND m.isIncoming = reactions.isIncoming)",
    )
    suspend fun deleteOrphans(): Int

    @Query("SELECT * FROM reactions ORDER BY address, timestamp")
    suspend fun all(): List<ReactionEntity>
}
