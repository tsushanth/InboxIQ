package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDraftDao {
    @Insert
    suspend fun insert(draft: AgentDraftEntity)

    @Query("SELECT * FROM agent_drafts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AgentDraftEntity>>

    @Query("SELECT * FROM agent_drafts WHERE status IN ('PENDING', 'SENDING') ORDER BY createdAt DESC")
    fun observePending(): Flow<List<AgentDraftEntity>>

    @Query("SELECT * FROM agent_drafts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AgentDraftEntity?

    @Query("UPDATE agent_drafts SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: AgentDraftStatus)

    /** Atomically PENDING -> SENDING. Returns 1 only for the single caller that wins the claim. */
    @Query("UPDATE agent_drafts SET status = 'SENDING' WHERE id = :id AND status = 'PENDING'")
    suspend fun claimForSending(id: String): Int

    @Query("DELETE FROM agent_drafts WHERE id = :id")
    suspend fun delete(id: String)
}
