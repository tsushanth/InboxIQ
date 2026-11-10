package com.inboxiq.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.inboxiq.app.worker.ClassifyMessagesWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Memory and time of the paths that scale with message count, on a SYNTHETIC 50,000-message database (3,500 conversations:
 * 12 automated senders with 2,000 messages each, the rest short). JVM numbers under Robolectric: only ratios, counts and
 * "does it stay flat" are meaningful. Not a phone, not RSS, and the ONNX runtime has no native library here, so the spam model is
 * not loaded (rules only). The device-side plan is in docs/LOW_RAM.md. Prints LOWRAM lines that the doc quotes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LowRamMeasureTest {
    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, db)
        context.getSharedPreferences("inboxiq_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        db.close()
    }

    private fun usedKb(): Long { repeat(3) { System.gc() }; return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 } }
    private fun usedMb(): Long = usedKb() / 1024

    /** Rough size of the entities a list holds: header and fields of the entity plus the characters of its strings. */
    private fun estimateKb(list: List<MessageEntity>): Long = list.sumOf { 176L + 2L * it.body.length + 2L * it.address.length } / 1024

    /** Runs [block] while sampling the heap; returns peak growth over the heap at the start, in MB. */
    private fun <T> peakGrowthMb(block: () -> T): Pair<T, Long> {
        val base = usedMb()
        val peak = AtomicLong(base)
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                peak.accumulateAndGet(Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_048_576 }, ::maxOf)
                try { Thread.sleep(3) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }
        val r = try { block() } finally { sampler.interrupt(); sampler.join() }
        return r to (peak.get() - base)
    }

    private fun seed(): Int {
        var ts = 1_600_000_000_000L
        var total = 0
        val batch = ArrayList<MessageEntity>(1000)
        fun flush() { runBlocking { db.messageDao().insertAll(batch) }; batch.clear() }
        fun add(address: String, body: String, incoming: Boolean) {
            batch += MessageEntity(threadId = 0, address = address, body = body, timestamp = ts++, isIncoming = incoming, label = MessageLabel.UNLABELED)
            total++
            if (batch.size >= 1000) flush()
        }
        // 12 automated senders, 2,000 messages each: debit alerts, OTPs, offers (synthetic bank-style text)
        for (s in 0 until 12) repeat(2_000) { k ->
            val a = "VM-TESTB${s.toString().padStart(1, '0')}K-T"
            add(a, when (k % 5) {
                0 -> "Rs ${100 + k}.00 debited from A/c XX00$s on 0${1 + k % 9}-Nov-26. UPI Ref No ${600000000000L + k}. Avl Bal Rs ${9000 + k}.00"
                1 -> "${100000 + k} is the OTP for txn of INR ${200 + k}.00 at DEMO STORE. Valid for 3 mins. Do not share."
                2 -> "Rs ${50 + k}.00 credited to A/c XX00$s via UPI Ref ${700000000000L + k}."
                3 -> "Get ${5 + k % 40}% cashback on UPI payments this weekend only! T&C apply. Reply STOP to opt out."
                else -> "Your statement for A/c XX00$s is ready. Total Amt Due Rs ${1000 + k}.00, Due Date 20-Nov-26."
            }, true)
        }
        // 3,488 short conversations: people (two-way) and a few shops
        for (i in 0 until 3_488) {
            val a = if (i % 4 == 0) "TESTSHP$i" else "+9198%08d".format(i)
            repeat(7 + i % 3) { k ->
                if (i % 4 == 0) add(a, "Your order ${1000 + k} is out for delivery. AWB ${7000000000L + i * 10 + k}", true)
                else add(a, if (k % 2 == 0) "hey are we still meeting at 7? #$k" else "yes see you there $k", k % 2 == 0)
            }
        }
        if (batch.isNotEmpty()) flush()
        return total
    }

    @Test fun `memory and time of the paths that scale with message count at 50k messages`() = runBlocking {
        val t0 = System.nanoTime()
        val total = seed()
        println("LOWRAM seeded messages=$total addresses=${db.openHelper.readableDatabase.query("SELECT COUNT(DISTINCT address) FROM messages").use { it.moveToFirst(); it.getInt(0) }} ms=${(System.nanoTime() - t0) / 1_000_000}")
        assertTrue(total >= 50_000)

        // 1. The inbox list: one row (with the whole newest message) per conversation, rebuilt on every table write.
        val base1 = usedKb()
        val t1 = System.nanoTime()
        val list = db.messageDao().observeThreadList().first()
        val ms1 = (System.nanoTime() - t1) / 1_000_000
        val retained1 = usedKb() - base1
        println("LOWRAM thread_list rows=${list.size} firstEmissionMs=$ms1 retainedKB=$retained1 estimatedKB=${estimateKb(list.map { it.latestMessage })}")
        assertEquals(3_500, list.size)

        // 2. One conversation screen: every message of the thread is loaded into a list.
        val biggest = "VM-TESTB0K-T"
        val base2 = usedKb()
        val t2 = System.nanoTime()
        val thread = db.messageDao().observeThread(biggest).first()
        val ms2 = (System.nanoTime() - t2) / 1_000_000
        val retained2 = usedKb() - base2
        println("LOWRAM thread_screen messages=${thread.size} firstEmissionMs=$ms2 retainedKB=$retained2 estimatedKB=${estimateKb(thread)}")
        assertEquals(2_000, thread.size)
        check(list.isNotEmpty() && thread.isNotEmpty())

        // 3. The classification pass (first install: every conversation unsorted), rules only.
        val (_, growth3) = peakGrowthMb {
            val t3 = System.nanoTime()
            runBlocking { TestListenableWorkerBuilder<ClassifyMessagesWorker>(context).build().doWork() }
            println("LOWRAM classify_pass ms=${(System.nanoTime() - t3) / 1_000_000}")
        }
        val labels = db.openHelper.readableDatabase.query("SELECT label, COUNT(DISTINCT address) FROM messages WHERE isIncoming=1 GROUP BY label").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
        println("LOWRAM classify_pass peakHeapGrowthMB=$growth3 threadsByLabel=$labels")
        assertTrue("no thread left unsorted: $labels", (labels["UNLABELED"] ?: 0) == 0)

        // 4. The Transactions upgrade: the version bump re-sorts every non-hand-filed thread once.
        context.getSharedPreferences("inboxiq_prefs", Context.MODE_PRIVATE).edit().putInt("classifier_pipeline_version", 2).commit()
        db.openHelper.writableDatabase.execSQL("UPDATE thread_labels SET classifiedAt = 1")
        val (_, growth4) = peakGrowthMb {
            val t4 = System.nanoTime()
            runBlocking { TestListenableWorkerBuilder<ClassifyMessagesWorker>(context).build().doWork() }
            println("LOWRAM resort_pass ms=${(System.nanoTime() - t4) / 1_000_000}")
        }
        println("LOWRAM resort_pass peakHeapGrowthMB=$growth4")

        // 5. Backup export streams in pages of 500: memory should not grow with the number of messages.
        val sink = object : OutputStream() { var n = 0L; override fun write(b: Int) { n++ }; override fun write(b: ByteArray, off: Int, len: Int) { n += len } }
        val (written, growth5) = peakGrowthMb { runBlocking { BackupManager(db).export(sink) } }
        println("LOWRAM backup_export messages=$written bytes=${sink.n} peakHeapGrowthMB=$growth5")
        assertEquals(total, written)

        // Loose tripwires, so a change that makes any of these scale badly is noticed (real limits belong to the device test).
        assertTrue("thread list retained ${retained1}KB", retained1 < 64 * 1024)
        assertTrue("classification pass grew the heap by ${growth3}MB", growth3 < 256)
        assertTrue("backup export grew the heap by ${growth5}MB", growth5 < 128)
    }
}
