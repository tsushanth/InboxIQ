package com.inboxiq.app.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Seeded property test for the one promise that keeps a default SMS app from showing people their own messages
 * twice or losing them: whatever order texts arrive through the receiver, appear in the system provider on their
 * own (sent while another app was default), and get imported, our database ends up holding exactly one row for
 * every distinct (address, time, text) the provider holds, and nothing the provider does not hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DuplicateSuppressionPropertyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var fake: FakeTelephony
    private lateinit var provider: ProviderSms

    private val normalize: (String) -> String = { raw ->
        val digits = raw.filter(Char::isDigit)
        when {
            raw.any(Char::isLetter) || digits.length < 7 -> raw
            raw.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            else -> "+$digits"
        }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        fake = FakeTelephony().also { it.register() }
        provider = ProviderSms(context.contentResolver)
    }

    @After fun tearDown() = db.close()

    private fun runOnce(seed: Int) = runBlocking {
        val rnd = Random(seed)
        val queue = ProviderWriteQueue(context.getSharedPreferences("prop_q_$seed", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val store = IncomingSmsStore(db, provider, queue, normalize, {})
        val importState = ImportState(context.getSharedPreferences("prop_i_$seed", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val tomb = ImportTombstones(context.getSharedPreferences("prop_t_$seed", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val env = ImportEnv(context, context.contentResolver, db, tomb, normalizer = normalize)
        // Few distinct values on purpose: collisions in address, second and text are the interesting case.
        val raws = listOf("5550100123", "+15550100123", "(555) 010-0123", "+15550100456", "AMAZON", "55123")
        val bodies = listOf("ok", "hi", "your code is 123456", "see you at 6")
        val received = HashSet<Triple<String, Long, String>>()

        repeat(rnd.nextInt(20, 60)) {
            val raw = raws[rnd.nextInt(raws.size)]
            val body = bodies[rnd.nextInt(bodies.size)]
            val ts = 1_800_000_000_000L + rnd.nextInt(0, 6) * 1000L
            when (rnd.nextInt(4)) {
                0, 1 -> {
                    store.store(raw, body, ts, null, -1)
                    received += Triple(normalize(raw), ts, body)
                }
                2 -> fake.sms += FakeSms((fake.sms.maxOfOrNull { it.id } ?: 0L) + 1, 1, raw, body, ts, type = 1, read = rnd.nextInt(2))
                else -> HistoryImport.run(env, importState, now = { 1L }, freeBytes = { Long.MAX_VALUE }, isDefault = { true })
            }
        }
        HistoryImport.run(env, importState, now = { 1L }, freeBytes = { Long.MAX_VALUE }, isDefault = { true })
        // A completely fresh import must find nothing left to add either.
        val again = HistoryImport.run(env, ImportState(context.getSharedPreferences("prop_i2_$seed", Context.MODE_PRIVATE).also { it.edit().clear().commit() }), now = { 1L }, freeBytes = { Long.MAX_VALUE }, isDefault = { true })
        assertEquals("seed $seed: a fresh import added rows", 0, (again as HistoryImport.Outcome.Done).inserted)

        val providerKeys = fake.sms.map { Triple(normalize(it.address ?: "unknown"), it.date, it.body ?: "") }.toSet()
        val mine = db.messageDao().pageAfter(0, 10_000).map { Triple(it.address, it.timestamp, it.body) }
        assertEquals("seed $seed: duplicate rows", mine.size, mine.toSet().size)
        assertEquals("seed $seed: database and provider disagree", providerKeys, mine.toSet())
        assertTrue("seed $seed: a received text is missing", mine.toSet().containsAll(received))
    }

    @Test fun `receive, provider-only rows and imports in any order never duplicate or drop a message`() {
        for (seed in 1..40) runOnce(seed)
    }
}
