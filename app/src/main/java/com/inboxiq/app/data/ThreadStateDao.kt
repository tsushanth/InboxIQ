package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensure(state: ThreadStateEntity): Long

    @Query("SELECT * FROM thread_state")
    fun observeAll(): Flow<List<ThreadStateEntity>>

    @Query("SELECT * FROM thread_state WHERE address = :address LIMIT 1")
    suspend fun get(address: String): ThreadStateEntity?

    @Query("SELECT * FROM thread_state WHERE address = :address LIMIT 1")
    fun observe(address: String): Flow<ThreadStateEntity?>

    @Query("UPDATE thread_state SET pinnedAt = :pinnedAt WHERE address = :address")
    suspend fun updatePinned(address: String, pinnedAt: Long?)

    @Query("UPDATE thread_state SET archived = :archived WHERE address = :address")
    suspend fun updateArchived(address: String, archived: Boolean)

    @Query("UPDATE thread_state SET mutedUntil = :mutedUntil WHERE address = :address")
    suspend fun updateMutedUntil(address: String, mutedUntil: Long?)

    @Query("UPDATE thread_state SET draft = :draft WHERE address = :address")
    suspend fun updateDraft(address: String, draft: String?)

    @Query("UPDATE thread_state SET labelCorrected = :corrected WHERE address = :address")
    suspend fun updateLabelCorrected(address: String, corrected: Boolean)

    @Query("DELETE FROM thread_state WHERE address = :address")
    suspend fun delete(address: String)

    // Each setter first makes sure a row exists, so callers never have to care.
    suspend fun setPinned(address: String, pinned: Boolean, now: Long) {
        ensure(ThreadStateEntity(address)); updatePinned(address, if (pinned) now else null)
    }

    suspend fun setArchived(address: String, archived: Boolean) {
        ensure(ThreadStateEntity(address)); updateArchived(address, archived)
    }

    suspend fun setMutedUntil(address: String, until: Long?) {
        ensure(ThreadStateEntity(address)); updateMutedUntil(address, until)
    }

    suspend fun setDraft(address: String, draft: String?) {
        ensure(ThreadStateEntity(address)); updateDraft(address, draft?.takeIf { it.isNotBlank() })
    }

    suspend fun setLabelCorrected(address: String, corrected: Boolean) {
        ensure(ThreadStateEntity(address)); updateLabelCorrected(address, corrected)
    }

    suspend fun isMuted(address: String, now: Long): Boolean = get(address)?.isMuted(now) == true
}
