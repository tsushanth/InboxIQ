package com.inboxiq.app.sms

import android.content.Context
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.worker.ClassifyMessagesWorker

/**
 * One-time import of the device's existing SMS history into our own DB,
 * run right after the default-SMS-handler role is granted (the content
 * provider is only readable once we hold that role, or READ_SMS anyway).
 */
object SmsBackfill {

    suspend fun run(context: Context) {
        val dao = AppDatabase.get(context).messageDao()
        val resolver = context.contentResolver

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )

        resolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, Telephony.Sms.DATE + " DESC")?.use { cursor ->
            val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (cursor.moveToNext()) {
                val type = cursor.getInt(typeIdx)
                val rawAddress = cursor.getString(addressIdx) ?: "unknown"
                dao.insert(
                    MessageEntity(
                        threadId = cursor.getLong(threadIdx),
                        address = PhoneNumberNormalizer.normalize(context, rawAddress),
                        body = cursor.getString(bodyIdx) ?: "",
                        timestamp = cursor.getLong(dateIdx),
                        isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                    ),
                )
            }
        }

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build(),
        )
    }
}
