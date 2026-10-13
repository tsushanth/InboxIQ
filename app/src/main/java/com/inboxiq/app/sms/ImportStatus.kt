package com.inboxiq.app.sms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ImportError { PERMISSION_DENIED, PROVIDER_UNAVAILABLE, LOW_STORAGE, UNKNOWN }

/** What the inbox shows while history is being copied in. */
data class ImportStatus(
    val running: Boolean = false,
    val processed: Long = 0,
    val total: Long = 0,
    val error: ImportError? = null,
) {
    /** Only the first full copy shows UI; later incremental runs are silent. */
    val showStrip: Boolean get() = running || error != null
}

/** Process-wide holder; the worker writes, the UI collects. */
object ImportStatusHolder {
    private val _status = MutableStateFlow(ImportStatus())
    val status: StateFlow<ImportStatus> = _status.asStateFlow()

    fun publish(s: ImportStatus) { _status.value = s }
    fun reset() { _status.value = ImportStatus() }
}
