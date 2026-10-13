package com.inboxiq.app.sms

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Copies the phone's SMS/MMS history into our DB: resumable, incremental, idempotent.
 *
 *  1. First run per source: snapshot the highest provider `_id` and the row count, then walk
 *     downward from there in pages (newest first, so the inbox fills with recent chats at once).
 *     The resume point is saved after every page, so a kill loses at most one page of work.
 *  2. Every later run: only rows with `_id > highWater`, oldest first. Nothing is re-read.
 *  3. A bounded "repair" pass (newest [REPAIR_ROWS] rows, at most every [REPAIR_EVERY_MS]) re-offers
 *     recent rows to the insert-ignore path in case something was missed.
 *
 * Duplicate safety does not depend on the bookkeeping: every insert is IGNOREd against the unique
 * (address, timestamp, body) index, and rows the user deleted in InboxIQ are skipped via
 * [ImportTombstones]. Existing rows are never modified except the existing MMS placeholder heal.
 */
object HistoryImport {

    const val SMS_PAGE = 500
    const val MMS_PAGE = 100
    const val REPAIR_ROWS = 500
    const val REPAIR_EVERY_MS = 6 * 60 * 60 * 1000L
    const val MIN_FREE_BYTES = 32L * 1024 * 1024

    sealed interface Outcome {
        data class Done(val inserted: Int) : Outcome
        data object NotDefault : Outcome
        data class Failed(val error: ImportError, val retryable: Boolean) : Outcome
    }

    private class Source(
        val key: String,
        val maxId: (ImportEnv) -> Long?,
        val count: (ImportEnv) -> Int,
        val below: suspend (ImportEnv, Long, Int) -> PageResult,
        val above: suspend (ImportEnv, Long, Int) -> PageResult,
        val pageSize: Int,
    )

    private val sources = listOf(
        Source(ImportState.SMS, SmsBackfill::maxId, SmsBackfill::count, SmsBackfill::pageBelow, SmsBackfill::pageAbove, SMS_PAGE),
        Source(ImportState.MMS, MmsSync::maxId, MmsSync::count, MmsSync::pageBelow, MmsSync::pageAbove, MMS_PAGE),
    )

    /** What the UI should show for the saved state before any run in this process. */
    fun statusFromState(state: ImportState): ImportStatus {
        if (state.isBackfillComplete() || state.quietFirstPass) return ImportStatus()
        val all = ImportState.SOURCES.map { state.load(it) }
        if (all.none { it.started }) return ImportStatus()
        return ImportStatus(running = false, processed = all.sumOf { it.processed }, total = all.sumOf { it.total })
    }

    suspend fun run(
        env: ImportEnv,
        state: ImportState,
        now: () -> Long = System::currentTimeMillis,
        freeBytes: () -> Long = { env.context.filesDir.usableSpace },
        isDefault: () -> Boolean,
    ): Outcome {
        if (!isDefault()) return Outcome.NotDefault
        if (freeBytes() < MIN_FREE_BYTES) return fail(ImportError.LOW_STORAGE, retryable = false)

        var inserted = 0
        try {
            val quiet = prepare(env, state, now())
            inserted += backfill(env, state, quiet)
            inserted += incremental(env, state)
            inserted += repairIfDue(env, state, now())
            ImportStatusHolder.publish(ImportStatus())
            return Outcome.Done(inserted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            return fail(ImportError.PERMISSION_DENIED, retryable = false)
        } catch (e: ProviderUnavailableException) {
            return fail(ImportError.PROVIDER_UNAVAILABLE, retryable = true)
        } catch (e: SQLiteFullException) {
            return fail(ImportError.LOW_STORAGE, retryable = false)
        } catch (e: SQLiteDiskIOException) {
            return fail(ImportError.LOW_STORAGE, retryable = false)
        } catch (e: Exception) {
            // Never log row contents; the class name is enough to diagnose.
            android.util.Log.w(TAG, "history import failed: ${e.javaClass.simpleName}")
            return fail(ImportError.UNKNOWN, retryable = true)
        }
    }

    private fun fail(error: ImportError, retryable: Boolean): Outcome.Failed {
        val cur = ImportStatusHolder.status.value
        ImportStatusHolder.publish(cur.copy(running = false, error = error))
        return Outcome.Failed(error, retryable)
    }

    /** Snapshots each source the first time it is seen. Returns true when the first pass should be silent. */
    private suspend fun prepare(env: ImportEnv, state: ImportState, now: Long): Boolean {
        if (sources.none { !state.load(it.key).started }) return state.quietFirstPass
        if (sources.all { !state.load(it.key).started }) {
            // Existing users already have their history from the old always-re-read import: reconcile quietly.
            state.quietFirstPass = env.db.messageDao().count() > 0
            // The first pass reads everything, so a repair pass right after it would be redundant.
            state.lastRepairAt = now
        }
        for (s in sources) {
            val cur = state.load(s.key)
            if (cur.started) continue
            val max = s.maxId(env)
            val total = s.count(env)
            state.save(
                s.key,
                if (max == null) SourceState(started = true, backfillDone = true)
                else SourceState(started = true, highWater = max, low = max + 1, total = total.toLong()),
            )
        }
        return state.quietFirstPass
    }

    private suspend fun backfill(env: ImportEnv, state: ImportState, quiet: Boolean): Int {
        var inserted = 0
        while (sources.any { !state.load(it.key).backfillDone }) {
            for (s in sources) {
                currentCoroutineContext().ensureActive()
                val cur = state.load(s.key)
                if (cur.backfillDone) continue
                val page = s.below(env, cur.low, s.pageSize)
                inserted += page.inserted
                val processed = cur.processed + page.read
                // `minId >= low` would mean the provider ignored our bound; stop rather than loop forever.
                val finished = page.read < s.pageSize || page.minId >= cur.low
                state.save(
                    s.key,
                    cur.copy(
                        low = if (page.read == 0) cur.low else page.minId,
                        backfillDone = finished,
                        processed = processed,
                        total = maxOf(cur.total, processed),
                    ),
                )
                if (!quiet) publishProgress(state)
            }
        }
        return inserted
    }

    private fun publishProgress(state: ImportState) {
        val all = ImportState.SOURCES.map { state.load(it) }
        ImportStatusHolder.publish(ImportStatus(running = true, processed = all.sumOf { it.processed }, total = all.sumOf { it.total }))
    }

    private suspend fun incremental(env: ImportEnv, state: ImportState): Int {
        var inserted = 0
        for (s in sources) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val cur = state.load(s.key)
                val page = s.above(env, cur.highWater, s.pageSize)
                if (page.read == 0) break
                inserted += page.inserted
                if (page.maxId <= cur.highWater) break // provider ignored the bound
                state.save(s.key, cur.copy(highWater = page.maxId))
                if (page.read < s.pageSize) break
            }
        }
        return inserted
    }

    private suspend fun repairIfDue(env: ImportEnv, state: ImportState, now: Long): Int {
        if (now - state.lastRepairAt < REPAIR_EVERY_MS) return 0
        var inserted = 0
        for (s in sources) {
            currentCoroutineContext().ensureActive()
            val hw = state.load(s.key).highWater
            if (hw > 0) inserted += s.below(env, hw + 1, if (s.key == ImportState.SMS) REPAIR_ROWS else REPAIR_ROWS / 5).inserted
        }
        state.lastRepairAt = now
        return inserted
    }

    private const val TAG = "HistoryImport"
}
