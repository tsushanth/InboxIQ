package com.inboxiq.app.data

import androidx.room.Entity

@Entity(tableName = "blocked_numbers", primaryKeys = ["address"])
data class BlockedNumberEntity(
    val address: String,
    val blockedAt: Long,
)
