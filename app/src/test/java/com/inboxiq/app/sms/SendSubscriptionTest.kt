package com.inboxiq.app.sms

import android.content.ContentResolver
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SendSubscriptionTest {
    private lateinit var db: AppDatabase
    private val fake = FakeTelephony()
    private lateinit var resolver: ContentResolver

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        fake.register()
        resolver = context.contentResolver
    }
    @After fun tearDown() { db.close() }

    // ---- the decision ----

    @Test fun `a reply uses the conversation's SIM when it differs from the default and is active`() {
        assertEquals(2, SendSubscription.choose(threadSub = 2, defaultSub = 1, isActive = { true }))
    }

    @Test fun `everything uncertain falls back to the default SIM`() {
        assertNull("no recorded SIM", SendSubscription.choose(null, 1) { true })
        assertNull("invalid recorded SIM", SendSubscription.choose(-1, 1) { true })
        assertNull("same as the default", SendSubscription.choose(1, 1) { true })
        assertNull("SIM no longer active", SendSubscription.choose(2, 1) { false })
    }

    // ---- reading the SIM of a conversation ----

    @Test fun `the newest row that records a SIM wins, and sent rows without one are skipped`() {
        fake.sms += FakeSms(1, thread = 77, address = "+15550100", body = "old", date = 1_000, subId = 1)
        fake.sms += FakeSms(2, thread = 77, address = "+15550100", body = "newer", date = 2_000, subId = 2)
        fake.sms += FakeSms(3, thread = 77, address = "+15550100", body = "my reply", date = 3_000, type = 2, subId = -1)
        fake.sms += FakeSms(4, thread = 78, address = "+15550199", body = "other thread", date = 4_000, subId = 1)
        assertEquals(2, ProviderSms(resolver).latestSubscriptionId(77))
        assertEquals(1, ProviderSms(resolver).latestSubscriptionId(78))
    }

    @Test fun `a conversation with no recorded SIM, or an unknown one, gives null`() {
        fake.sms += FakeSms(1, thread = 77, address = "+15550100", body = "x", date = 1_000, subId = -1)
        assertNull(ProviderSms(resolver).latestSubscriptionId(77))
        assertNull(ProviderSms(resolver).latestSubscriptionId(999))
    }

    @Test fun `the provider conversation id comes from our newest row that has one`() = runTest {
        val dao = db.messageDao()
        dao.insert(MessageEntity(threadId = 0, address = "+15550100", body = "mine", timestamp = 3, isIncoming = false))
        dao.insert(MessageEntity(threadId = 41, address = "+15550100", body = "a", timestamp = 1, isIncoming = true))
        dao.insert(MessageEntity(threadId = 42, address = "+15550100", body = "b", timestamp = 2, isIncoming = true))
        assertEquals(42L, dao.latestProviderThreadId("+15550100"))
        assertNull(dao.latestProviderThreadId("+15550000"))
    }
}
