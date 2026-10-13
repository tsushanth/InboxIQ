package com.inboxiq.app.sms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ScheduledStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: ScheduledStore
    private var now = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ScheduledStore(db) { now }
    }

    @After fun tearDown() = db.close()

    @Test fun `undo before send means nothing is ever claimed`() = runTest {
        val id = store.create("+15550100", "oops", 6_000, isUndoWindow = true)
        assertTrue(store.cancel(id))
        assertNull(store.claim(id))
        assertTrue(store.pending().isEmpty())
    }

    @Test fun `two senders racing send exactly once`() = runTest {
        val id = store.create("+15550100", "hello", 2_000, isUndoWindow = false)
        val first = store.claim(id)
        val second = store.claim(id)
        assertNotNull(first); assertEquals("hello", first!!.body)
        assertNull(second)
    }

    @Test fun `cancel after send reports false so the UI does not claim it was stopped`() = runTest {
        val id = store.create("+15550100", "hello", 2_000, isUndoWindow = true)
        store.claim(id)
        assertFalse(store.cancel(id))
    }

    @Test fun `overdue lists only rows whose time has passed`() = runTest {
        store.create("+1", "late", 500, false)
        store.create("+1", "later", 9_000, false)
        assertEquals(listOf("late"), store.overdue(store.pending()).map { it.body })
        now = 10_000
        assertEquals(2, store.overdue(store.pending()).size)
    }

    @Test fun `scheduled rows survive in order of send time`() = runTest {
        store.create("+1", "b", 9_000, false)
        store.create("+1", "a", 3_000, false)
        assertEquals(listOf("a", "b"), store.pending().map { it.body })
    }
}
