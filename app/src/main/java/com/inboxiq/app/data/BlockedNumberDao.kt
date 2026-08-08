package com.inboxiq.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedNumberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(entry: BlockedNumberEntity)

    @Query("DELETE FROM blocked_numbers WHERE address = :address")
    suspend fun unblock(address: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE address = :address)")
    suspend fun isBlocked(address: String): Boolean

    @Query("SELECT address FROM blocked_numbers")
    fun observeBlockedAddresses(): Flow<List<String>>
}
