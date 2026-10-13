package com.inboxiq.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.RemoteInput
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.inbox.MessageChannel
import com.inboxiq.app.inbox.NotificationPolicy
import com.inboxiq.app.ui.LabelPalette
import com.inboxiq.app.ui.MainActivity

object MessageNotifier {

    private const val CHANNEL_ID = "incoming_messages"
    private const val AUTO_HEAL_CHANNEL_ID = "auto_heal"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming messages",
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val autoHealChannel = NotificationChannel(
            AUTO_HEAL_CHANNEL_ID,
            "Message delivery fixes",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(autoHealChannel)
    }

    /** A previously-failed MMS just went through on a local retry (see AutoHealWorker). */
    fun notifyAutoHealed(context: Context, address: String, displayName: String?) {
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("inboxiq://thread/$address")
            putExtra(MainActivity.EXTRA_THREAD_ADDRESS, address)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            "heal_$address".hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AUTO_HEAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Fixed!")
            .setContentText("Your message to ${displayName ?: address} just sent.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify("heal_$address".hashCode(), notification)
    }

    const val ACTION_REPLY = "com.inboxiq.app.action.REPLY"
    const val ACTION_MARK_READ = "com.inboxiq.app.action.MARK_READ"
    const val ACTION_COPY_CODE = "com.inboxiq.app.action.COPY_CODE"
    const val EXTRA_ADDRESS = "com.inboxiq.app.extra.ADDRESS"
    const val EXTRA_CODE = "com.inboxiq.app.extra.CODE"
    const val KEY_REPLY = "reply_text"

    fun cancel(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(address.hashCode())
    }

    private fun ensureMessageChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        MessageChannel.entries.forEach { c ->
            // GENERAL keeps its original id so anyone who tuned "Incoming messages" keeps that choice.
            val ch = NotificationChannel(c.id, c.title, c.importance).apply { description = c.description }
            nm.createNotificationChannel(ch)
        }
    }

    private fun action(context: Context, action: String, address: String, extra: Pair<String, String>? = null, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_ADDRESS, address)
        extra?.let { intent.putExtra(it.first, it.second) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (action + address).hashCode(), intent, flags)
    }

    /**
     * Posts the notification for a received text. Channel, sound level and buttons follow the
     * conversation's label (see NotificationPolicy). A muted conversation posts nothing; the message is still saved.
     */
    fun notifyIncoming(
        context: Context,
        address: String,
        displayName: String?,
        body: String,
        label: MessageLabel = MessageLabel.UNLABELED,
        receivedAt: Long = System.currentTimeMillis(),
        muted: Boolean = false,
    ) {
        val plan = NotificationPolicy.plan(label, body, receivedAt, System.currentTimeMillis(), muted)
        if (!plan.notify) return
        ensureChannel(context)
        ensureMessageChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("inboxiq://thread/$address")
            putExtra(MainActivity.EXTRA_THREAD_ADDRESS, address)
        }
        val openPending = PendingIntent.getActivity(context, address.hashCode(), openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = displayName ?: address
        val sender = androidx.core.app.Person.Builder().setName(title).build()
        val style = NotificationCompat.MessagingStyle(androidx.core.app.Person.Builder().setName("You").build())
            .addMessage(body, receivedAt, sender)

        val builder = NotificationCompat.Builder(context, plan.channel.id)
            .setSmallIcon(com.inboxiq.app.R.drawable.ic_stat_inboxiq)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(style)
            .setSubText(LabelPalette.displayName(label).takeIf { label != MessageLabel.UNLABELED })
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setPriority(if (plan.channel.importance >= 4) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            // Message text stays off the lock screen until the phone is unlocked.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        plan.code?.let { code ->
            builder.addAction(NotificationCompat.Action.Builder(0, "Copy code", action(context, ACTION_COPY_CODE, address, EXTRA_CODE to code)).build())
        }
        if (plan.canReply) {
            val remote = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply", action(context, ACTION_REPLY, address, mutable = true))
                    .addRemoteInput(remote)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setAllowGeneratedReplies(false)
                    .build(),
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Mark as read", action(context, ACTION_MARK_READ, address))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .build(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(address.hashCode(), builder.build())
    }
}
