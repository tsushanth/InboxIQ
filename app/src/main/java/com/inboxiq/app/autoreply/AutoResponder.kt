package com.inboxiq.app.autoreply

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inboxiq.app.BuildConfig
import com.inboxiq.app.classify.BareReplies
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.sms.DefaultSmsRole
import com.inboxiq.app.sms.ImportState
import com.inboxiq.app.sms.ImportStatusHolder
import com.inboxiq.app.sms.SmsSender
import java.time.ZoneId

/** Sends the automatic text. The real one goes through [SmsSender], so it is written to the provider like any sent text. */
fun interface AutoReplySender {
    /** Returns the id of the stored outgoing message. */
    suspend fun send(context: Context, address: String, body: String): Long
}

/**
 * The optional "Auto-reply when away" feature. Off by default, hidden behind the AUTO_RESPONDER_ENABLED build
 * flag, driven by the existing SMS_DELIVER path plus one WorkManager unique job per sender. No foreground
 * service, no alarms. See docs/AUTO_RESPONDER.md.
 */
object AutoResponder {
    const val KEY_ADDRESS = "address"
    const val KEY_MESSAGE_ID = "message_id"

    /** Compiled in? Tests can pin this to simulate a release build with the flag off. */
    @Volatile var buildEnabledOverride: Boolean? = null
    fun buildEnabled(): Boolean = buildEnabledOverride ?: BuildConfig.AUTO_RESPONDER_ENABLED

    // Seams for tests; the defaults are the real thing.
    @Volatile var sender: AutoReplySender = AutoReplySender { c, a, b -> SmsSender.send(c, a, b) }
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile var zone: () -> ZoneId = { ZoneId.systemDefault() }
    @Volatile var isDefaultSms: (Context) -> Boolean = { DefaultSmsRole.isDefault(it) }
    @Volatile var savedContact: (Context, String) -> Boolean = { c, a -> ContactResolver.displayNameFor(c, a) != null }
    @Volatile var importRunning: (Context) -> Boolean = { c ->
        ImportStatusHolder.status.value.running || !ImportState(c).isBackfillComplete()
    }

    fun resetForTests() {
        buildEnabledOverride = null
        sender = AutoReplySender { c, a, b -> SmsSender.send(c, a, b) }
        clock = { System.currentTimeMillis() }
        zone = { ZoneId.systemDefault() }
        isDefaultSms = { DefaultSmsRole.isDefault(it) }
        savedContact = { c, a -> ContactResolver.displayNameFor(c, a) != null }
        importRunning = { c -> ImportStatusHolder.status.value.running || !ImportState(c).isBackfillComplete() }
    }

    /**
     * Called from SMS_DELIVER once the text is stored and labelled. Cheap: returns at once unless the feature is
     * compiled in AND switched on. The body is never put in the work request; the worker re-reads it from the database.
     */
    fun onIncoming(context: Context, address: String, messageId: Long) {
        if (!buildEnabled()) return
        val store = AutoReplyStore(context)
        if (!store.config().enabled) return
        val data = Data.Builder().putString(KEY_ADDRESS, address).putLong(KEY_MESSAGE_ID, messageId).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "auto_reply:$address",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AutoReplyWorker>().setInputData(data).build(),
        )
    }

    sealed interface Outcome {
        data class Sent(val messageId: Long) : Outcome
        data class Skipped(val reason: Skip) : Outcome
    }

    /** The whole decision and the send, for one incoming message. Used by the worker and by tests. */
    suspend fun process(context: Context, db: AppDatabase, store: AutoReplyStore, address: String, messageId: Long): Outcome {
        val now = clock()
        val zone = zone()
        val config = store.config()
        if (!buildEnabled()) return Outcome.Skipped(Skip.BUILD_DISABLED)
        if (config.enabled && config.untilMillis != 0L && now >= config.untilMillis) {
            store.disable() // an "until" time that has passed switches it off for good
            return Outcome.Skipped(Skip.EXPIRED)
        }
        val row = db.messageDao().getById(messageId)
        if (row == null || !row.isIncoming || row.address != address) return Outcome.Skipped(Skip.OFF)

        val autoIds = store.sentIds()
        val sent = db.replyHistoryDao().recentSentTo(address)
            .filter { it.id !in autoIds && it.sendStatus != com.inboxiq.app.data.SendStatus.FAILED && it.body.isNotBlank() && !BareReplies.isBare(it.body) }
        val threadLabel = db.threadLabelDao().get(address)?.label
        // A junk thread label wins even if this one message was read as personal.
        val label = if (threadLabel in setOf(MessageLabel.PROMO, MessageLabel.SPAM, MessageLabel.SCAM, MessageLabel.OTP_2FA)) threadLabel!! else row.label
        val facts = Facts(
            isDefaultSmsApp = isDefaultSms(context),
            importRunning = importRunning(context),
            isBlocked = db.blockedNumberDao().isBlocked(address),
            sentToBefore = sent.size,
            isSavedContact = savedContact(context, address),
            lastAutoReplyAt = store.lastSentAt(address),
            sentToday = store.sentOn(AutoReplyPolicy.dayKey(now, zone)),
        )
        val incoming = Incoming(address, row.body, row.timestamp, label, isMms = row.imagePartUri != null)
        AutoReplyPolicy.evaluate(true, config, incoming, facts, now, zone)?.let { return Outcome.Skipped(it) }

        val day = AutoReplyPolicy.dayKey(now, zone)
        val previous = store.lastSentAt(address)
        store.reserve(address, now, day)
        return try {
            val id = sender.send(context, address, config.message)
            store.record(AutoReplyLogEntry(now, address, id))
            Outcome.Sent(id)
        } catch (e: Exception) {
            store.release(address, previous, day)
            Outcome.Skipped(Skip.OFF)
        }
    }
}

class AutoReplyWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val address = inputData.getString(AutoResponder.KEY_ADDRESS) ?: return Result.success()
        val id = inputData.getLong(AutoResponder.KEY_MESSAGE_ID, 0)
        runCatching {
            AutoResponder.process(applicationContext, AppDatabase.get(applicationContext), AutoReplyStore(applicationContext), address, id)
        }.onFailure { android.util.Log.w("AutoReplyWorker", "auto reply failed: ${it.javaClass.simpleName}") }
        return Result.success()
    }
}
