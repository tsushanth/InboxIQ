package com.inboxiq.app.sms

import android.content.Context

/** Undo-send window in seconds (0 = send immediately). Local preference only. */
object SendPreferences {
    private const val PREFS = "inboxiq_prefs"
    val UNDO_CHOICES = listOf(0, 5, 10)

    fun undoSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("undo_send_seconds", 0).takeIf { it in UNDO_CHOICES } ?: 0

    fun setUndoSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("undo_send_seconds", seconds).apply()
    }
}
