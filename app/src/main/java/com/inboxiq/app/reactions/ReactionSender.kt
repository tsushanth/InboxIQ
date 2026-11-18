package com.inboxiq.app.reactions

import android.content.Context
import com.inboxiq.app.sms.ScheduledSend
import kotlinx.coroutines.delay

/**
 * Sends a reaction text after a short undo window.
 *
 * The text is queued first (a stored row that survives the app being closed, with a background worker as the
 * fallback), then [await] waits for the deadline and hands it to the normal send path. Cancelling and sending
 * race on the same stored row, so exactly one of them wins: an undo before the deadline means nothing is ever
 * sent, and if the app is in the background at the deadline the worker sends it instead.
 *
 * The three operations are injected so the timing can be tested with a fake clock; [production] wires them to
 * [ScheduledSend], which ends in the unchanged SmsSender path.
 */
class ReactionSender(
    private val clock: () -> Long,
    private val schedule: suspend (address: String, body: String, sendAt: Long) -> Long,
    private val dispatch: suspend (id: Long) -> Boolean,
    private val cancel: suspend (id: Long) -> Boolean,
    private val undoMillis: Long = UNDO_MILLIS,
) {
    /** A queued reaction text. [deadline] is the clock time at which it goes out unless undone. */
    data class Pending(val id: Long, val deadline: Long)

    suspend fun queue(address: String, body: String): Pending {
        val deadline = clock() + undoMillis
        return Pending(schedule(address, body, deadline), deadline)
    }

    /** Waits for the deadline, then sends. True when this call sent it, false when it was undone or already sent by the worker. */
    suspend fun await(pending: Pending): Boolean {
        val wait = pending.deadline - clock()
        if (wait > 0) delay(wait)
        return dispatch(pending.id)
    }

    /** True when the text was stopped before it went out. */
    suspend fun undo(pending: Pending): Boolean = cancel(pending.id)

    companion object {
        /** The undo window for a reaction text. */
        const val UNDO_MILLIS = 5_000L

        fun production(context: Context): ReactionSender {
            val app = context.applicationContext
            return ReactionSender(
                clock = System::currentTimeMillis,
                schedule = { address, body, sendAt -> ScheduledSend.schedule(app, address, body, sendAt, isUndoWindow = true) },
                dispatch = { id -> ScheduledSend.dispatch(app, id) },
                cancel = { id -> ScheduledSend.cancel(app, id) },
            )
        }
    }
}
