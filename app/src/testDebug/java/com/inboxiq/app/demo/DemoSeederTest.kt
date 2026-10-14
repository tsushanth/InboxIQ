package com.inboxiq.app.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.inbox.OtpExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records every call so the test can prove the seeder never touches the SMS, MMS or contacts providers. */
class RecordingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? { calls++; return null }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? { calls++; return null }
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int { calls++; return 0 }
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int { calls++; return 0 }
    companion object { var calls = 0 }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DemoSeederTest {
    private lateinit var db: AppDatabase
    private val now = 1_800_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        RecordingProvider.calls = 0
        listOf("sms", "mms", "mms-sms", "com.android.contacts").forEach { Robolectric.buildContentProvider(RecordingProvider::class.java).create(it) }
    }

    @After fun tearDown() = db.close()

    @Test fun `seeds ten threads and the expected message count`() = runTest {
        val r = DemoSeeder.seed(db, now)
        assertEquals(10, r.threads)
        assertEquals(db.messageDao().count(), r.messages)
        assertEquals(DemoSeeder.contacts.size, db.messageDao().observeThreadList().first().size)
    }

    @Test fun `every incoming message carries its thread label`() = runTest {
        DemoSeeder.seed(db, now)
        DemoSeeder.contacts.forEach { c ->
            val incoming = db.messageDao().messagesForThread(c.address).filter { it.isIncoming }
            assertTrue(c.address, incoming.isNotEmpty() && incoming.all { it.label == c.label })
            assertEquals(c.label, db.threadLabelDao().get(c.address)?.label)
        }
        val labels = DemoSeeder.contacts.map { it.label }.toSet()
        assertTrue(labels.containsAll(listOf(MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.PROMO, MessageLabel.OTP_2FA, MessageLabel.SPAM, MessageLabel.SCAM)))
    }

    @Test fun `codes and the scam text are recognised by the real rules`() = runTest {
        DemoSeeder.seed(db, now)
        val code = db.messageDao().messagesForThread(DemoSeeder.CODE_ADDRESS).single()
        assertEquals("482913", OtpExtractor.extract(code.body, code.timestamp)?.code)
        assertEquals(MessageLabel.OTP_2FA, HeuristicRules.match(code.body)?.label)
    }

    @Test fun `pin draft unread mix and one MMS placeholder are present`() = runTest {
        DemoSeeder.seed(db, now, photoUri = "file:///demo.png")
        assertNotNull(db.threadStateDao().get(DemoSeeder.PIN_ADDRESS)?.pinnedAt)
        assertEquals(DemoSeeder.DRAFT_TEXT, db.threadStateDao().get(DemoSeeder.DRAFT_ADDRESS)?.draft)
        val all = db.messageDao().observeAll().first()
        assertTrue(all.any { !it.isRead } && all.any { it.isRead })
        assertEquals(1, all.count { it.imagePartUri != null })
        assertEquals(DemoSeeder.MMS_ADDRESS, all.single { it.imagePartUri != null }.address)
    }

    @Test fun `seeded rows are fake numbers and never carry a provider thread id`() = runTest {
        DemoSeeder.seed(db, now)
        val all = db.messageDao().observeAll().first()
        assertTrue(all.all { it.threadId == 0L })
        assertTrue(DemoSeeder.contacts.map { it.address }.filter { it.startsWith("+") }.all { it.startsWith("+1555555010") || it.startsWith("+1555555017") || it.startsWith("+1555555018") })
    }

    @Test fun `seeding twice replaces rather than duplicates`() = runTest {
        DemoSeeder.seed(db, now)
        val first = db.messageDao().count()
        DemoSeeder.seed(db, now + 1_000)
        assertEquals(first, db.messageDao().count())
    }

    @Test fun `seeding never touches the sms mms or contacts providers`() = runTest {
        DemoSeeder.seed(db, now)
        assertEquals(0, RecordingProvider.calls)
    }
}
