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

    @Query("SELECT * FROM agent_drafts WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<AgentDraftEntity>>

    @Query("SELECT * FROM agent_drafts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AgentDraftEntity?

    @Query("UPDATE agent_drafts SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: AgentDraftStatus)

    @Query("DELETE FROM agent_drafts WHERE id = :id")
    suspend fun delete(id: String)
}
