package com.inboxiq.app.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/** Resolves a phone number to a saved contact's display name, if any. READ_CONTACTS-gated. */
object ContactResolver {

    fun displayNameFor(context: Context, address: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w("ContactResolver", "lookup failed for $address", e)
            null
        }
    }
}
