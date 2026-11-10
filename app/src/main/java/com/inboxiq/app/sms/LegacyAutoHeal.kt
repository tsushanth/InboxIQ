package com.inboxiq.app.sms

import android.content.Context
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageDao

/**
 * One-time clean-up after the false-failure fix. Earlier builds marked a picture message failed whenever the
 * provider row could not be read back as sent, even when the carrier had taken it, and queued automatic retries.
 * Those queued retries cannot be told apart from genuine failures, and running them would send the picture to the
 * recipient again. So on the first start after the update every queued retry is dropped. The messages stay marked
 * as not delivered with "Retry now" available, so the person decides.
 */
object LegacyAutoHeal {
    private const val PREFS = "legacy_auto_heal"
    private const val KEY_DONE = "cleared_v1"

    suspend fun clearOnce(context: Context, dao: MessageDao = AppDatabase.get(context).messageDao()): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return 0
        val cleared = dao.clearAllAutoHeal()
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        return cleared
    }
}
