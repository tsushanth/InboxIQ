package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {
    @Insert
    suspend fun insert(message: ScheduledMessageEntity): Long

    @Query("SELECT * FROM scheduled_messages WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages WHERE address = :address ORDER BY sendAt ASC")
    fun observeForThread(address: String): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages ORDER BY sendAt ASC")
    fun observeAll(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages ORDER BY sendAt ASC")
    suspend fun all(): List<ScheduledMessageEntity>

    /** Returns 1 if this call removed the row (i.e. it won the race against the sender), else 0. */
    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: Long): Int
}
