package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_devices ORDER BY pairedAt DESC")
    fun observeAll(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE tokenHash = :tokenHash LIMIT 1")
    suspend fun findByTokenHash(tokenHash: String): PairedDeviceEntity?

    @Query("UPDATE paired_devices SET lastActiveAt = :timestamp WHERE tokenHash = :tokenHash")
    suspend fun touch(tokenHash: String, timestamp: Long)

    @Query("DELETE FROM paired_devices WHERE id = :id")
    suspend fun revoke(id: String)
}
