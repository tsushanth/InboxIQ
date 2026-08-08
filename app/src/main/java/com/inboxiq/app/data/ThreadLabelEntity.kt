package com.inboxiq.app.data

import androidx.room.Entity

/**
 * The classification unit is the sender/thread, not the individual message —
 * a given address is essentially always the same category (a bank's OTP
 * number doesn't alternate between OTP and personal), so we classify once
 * per address and reuse the result for every message from it. Re-classified
 * only if a later message from the same address doesn't match the cached
 * label's heuristic rules (see ClassifyThreadsWorker).
 */
@Entity(tableName = "thread_labels", primaryKeys = ["address"])
data class ThreadLabelEntity(
    val address: String,
    val label: MessageLabel,
    val confidence: Float,
    val classifiedAt: Long,
)
