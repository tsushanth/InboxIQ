package com.inboxiq.app.sms

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.HistoryImportWorker

/**
 * Starts the history import whenever InboxIQ is the default SMS handler, by whatever route that
 * came about. Unique work with KEEP means a second request while one runs does nothing, and a run
 * with nothing left to import finishes after one cheap query per source.
 */
object ImportTrigger {
    const val WORK_NAME = "history_import"

    /** Enqueues the import if InboxIQ currently holds the SMS role. Returns whether it enqueued. */
    fun ensure(context: Context): Boolean {
        if (!DefaultSmsRole.isDefault(context)) return false
        enqueue(context)
        return true
    }

    /** Retry after an error; the role was already verified when the error was raised. */
    fun retry(context: Context) {
        ImportStatusHolder.publish(ImportStatusHolder.status.value.copy(error = null, running = true))
        enqueue(context)
    }

    private fun enqueue(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HistoryImportWorker>().build(),
        )
    }
}

/**
 * Turns "is InboxIQ the default app?" samples into import triggers. Android's role-holder listener
 * (RoleManager.OnRoleHoldersChangedListener) is system-only API for third-party apps, so we sample
 * instead: on every foreground transition ([check] with force) and periodically while visible, which
 * also catches a change made in split-screen or from the system dialog over us.
 */
class RoleWatcher(
    private val isDefault: () -> Boolean,
    private val onDefault: () -> Unit,
) {
    private var last: Boolean? = null

    /**
     * Calls [onDefault] when InboxIQ has just become default, on the first sample if it already is,
     * and on any forced check while it is (cheap: a no-op run when nothing is left to import).
     * Returns the sampled role state.
     */
    fun check(force: Boolean): Boolean {
        val now = isDefault()
        val prev = last
        last = now
        if (now && (force || prev != true)) onDefault()
        return now
    }
}
