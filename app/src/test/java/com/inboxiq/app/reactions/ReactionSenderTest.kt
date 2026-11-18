package com.inboxiq.app.reactions

import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo window, with a fake clock. The stored row is replaced by a map with the same rule as the real
 * ScheduledStore (see ScheduledStoreTest): cancelling and sending both try to remove the same row, and only the one
 * that removes it acts. That keeps virtual time under the test's control (Room answers on its own threads).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactionSenderTest {
    private class Row(val address: String, val body: String, val sendAt: Long)
    private val rows = HashMap<Long, Row>()
    private var nextId = 1L
    private val sent = mutableListOf<Pair<String, String>>()

    private fun TestScope.sender() = ReactionSender(
        clock = { testScheduler.currentTime },
        schedule = { a, b, at -> val id = nextId++; rows[id] = Row(a, b, at); id },
        dispatch = { id -> rows.remove(id)?.let { sent += it.address to it.body; true } ?: false },
        cancel = { id -> rows.remove(id) != null },
    )

    @Test fun `the undo window is five seconds`() = runTest {
        val s = sender()
        advanceTimeBy(1_234)
        val p = s.queue("+14155550100", "Liked “hi”")
        assertEquals(1_234 + 5_000L, p.deadline)
        assertEquals(1_234 + 5_000L, rows[p.id]!!.sendAt)
    }

    @Test fun `without an undo the text goes out exactly at the deadline`() = runTest {
        val s = sender()
        val p = s.queue("+14155550100", "Liked “hi”")
        var result: Boolean? = null
        launch { result = s.await(p) }
        advanceTimeBy(4_999); runCurrent()
        assertTrue("nothing goes out before the deadline", sent.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("+14155550100" to "Liked “hi”"), sent)
        assertEquals(true, result)
    }

    @Test fun `an undo inside the window means nothing is ever sent`() = runTest {
        val s = sender()
        val p = s.queue("+14155550100", "Liked “hi”")
        var result: Boolean? = null
        launch { result = s.await(p) }
        advanceTimeBy(3_000); runCurrent()
        assertTrue(s.undo(p))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(false, result)
        assertNull(rows[p.id])
    }

    @Test fun `an undo after the text went out cannot stop it and says so`() = runTest {
        val s = sender()
        val p = s.queue("+14155550100", "Liked “hi”")
        launch { s.await(p) }
        advanceTimeBy(5_000); runCurrent()
        assertFalse(s.undo(p))
        assertEquals(1, sent.size)
    }

    @Test fun `if the app is backgrounded and its timer dies, the worker still sends at the deadline, once`() = runTest {
        val s = sender()
        val p = s.queue("+14155550100", "Loved “hi”")
        val timer: Job = launch { s.await(p) }
        advanceTimeBy(1_000); runCurrent()
        timer.cancel() // process or scope gone
        advanceTimeBy(4_000)
        // the background worker's path: claim the stored row and send it
        val row = rows.remove(p.id)!!
        sent += row.address to row.body
        assertEquals(1, sent.size)
        assertNull("a second claim (timer and worker both firing) gets nothing", rows.remove(p.id))
    }

    @Test fun `when the worker wins the race the in-app timer does not send a second copy`() = runTest {
        val s = sender()
        val p = s.queue("+14155550100", "Loved “hi”")
        var result: Boolean? = null
        launch { result = s.await(p) }
        advanceTimeBy(4_000); runCurrent()
        val row = rows.remove(p.id)!!
        sent += row.address to row.body
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, sent.size)
        assertEquals(false, result)
    }

    @Test fun `a deadline already in the past sends straight away`() = runTest {
        val s = sender()
        val p = s.queue("+14155550100", "Liked “hi”")
        advanceTimeBy(20_000)
        var result: Boolean? = null
        launch { result = s.await(p) }
        runCurrent()
        assertEquals(true, result)
        assertEquals(1, sent.size)
    }
}
