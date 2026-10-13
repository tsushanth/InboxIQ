package com.inboxiq.app.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM-level numbers for the owner's data shape (4.8k SMS + 5k MMS from 692 contacts, scaled to 10k/5k).
 * Real devices are slower (provider IPC), so only ratios and counts are meaningful, not absolute times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ImportPerfTest {

    @Test
    fun `an observing inbox is invalidated once per page, not once per row, and heap stays flat`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        FakeTelephony.seed(10_000, 5_000).register()
        val env = ImportEnv(
            context, context.contentResolver, db,
            ImportTombstones(context.getSharedPreferences("perf_t", Context.MODE_PRIVATE)),
            normalizer = { it },
        )
        val state = ImportState(context.getSharedPreferences("perf_s", Context.MODE_PRIVATE))

        val emissions = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch { db.messageDao().observeThreadList().collect { emissions.incrementAndGet() } }
        Thread.sleep(300)

        System.gc()
        val base = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val peak = AtomicLong(base)
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                peak.accumulateAndGet(used, ::maxOf)
                try { Thread.sleep(5) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { start() }

        val t0 = System.nanoTime()
        val outcome = HistoryImport.run(env, state, freeBytes = { Long.MAX_VALUE }, isDefault = { true })
        val ms = (System.nanoTime() - t0) / 1_000_000
        Thread.sleep(500)
        sampler.interrupt(); sampler.join()
        scope.cancel()

        println("IMPORT_PERF ms=$ms emissions=${emissions.get()} peakHeapDeltaMB=${(peak.get() - base) / 1_048_576}")
        assertEquals(15_000, (outcome as HistoryImport.Outcome.Done).inserted)
        // 20 SMS pages + 50 MMS pages = 70 transactions; the baseline fired ~1,000 recomputes.
        assertTrue("emissions=${emissions.get()}", emissions.get() < 200)
        db.close()
    }
}
