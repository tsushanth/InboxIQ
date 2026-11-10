package com.inboxiq.app.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.inboxiq.app.data.AppDatabase

/**
 * Dual-SIM: a reply should leave from the SIM the conversation is on, not from whichever SIM is the phone's default
 * for texts. The SIM is read from the provider (`sub_id` of the newest row of the conversation that has one; every text
 * we receive is stored with it, see [IncomingSmsStore]). Anything uncertain falls back to the platform default, which is
 * exactly what InboxIQ did before: a single-SIM phone, a SIM that is no longer active, or a conversation with no recorded SIM.
 */
object SendSubscription {

    /** Pure decision: the subscription to send on, or null to keep the platform default. */
    internal fun choose(threadSub: Int?, defaultSub: Int, isActive: (Int) -> Boolean): Int? {
        if (threadSub == null || threadSub < 0) return null
        if (threadSub == defaultSub) return null
        return if (isActive(threadSub)) threadSub else null
    }

    /** The subscription to use for a reply to [address], or null for the default. Never throws. */
    suspend fun forAddress(context: Context, address: String): Int? = try {
        val app = context.applicationContext
        val threadId = AppDatabase.get(app).messageDao().latestProviderThreadId(address)
        if (threadId == null) null
        else choose(ProviderSms(app.contentResolver).latestSubscriptionId(threadId), SmsManager.getDefaultSmsSubscriptionId(), isActive(app))
    } catch (e: Exception) {
        null
    }

    private fun isActive(context: Context): (Int) -> Boolean = { sub ->
        // isActiveSubscriptionId is API 29+; below that (or if the platform refuses) keep the default SIM.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) false
        else try {
            context.getSystemService(SubscriptionManager::class.java)?.isActiveSubscriptionId(sub) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The SmsManager for [subId] (null = default). `getSystemService(SmsManager::class.java)` only resolves on API 31+;
     * on 26..30 it returns null, so older phones use the static accessors instead.
     */
    @Suppress("DEPRECATION")
    fun manager(context: Context, subId: Int?): SmsManager {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = app.getSystemService(SmsManager::class.java)
            if (subId != null) base.createForSubscriptionId(subId) else base
        } else if (subId != null) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            SmsManager.getDefault()
        }
    }
}
