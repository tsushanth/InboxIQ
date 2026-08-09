package com.inboxiq.app.sms

import android.content.Context
import android.net.Uri

/** Deletes the app-private copies MmsSender.persistLocally() writes — prevents an unbounded storage leak on message/thread delete. */
object MmsImageStore {

    fun delete(context: Context, imagePartUri: String?) {
        val uri = imagePartUri?.let { Uri.parse(it) } ?: return
        if (uri.authority != "${context.packageName}.fileprovider") return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun deleteAll(context: Context, imagePartUris: List<String>) {
        imagePartUris.forEach { delete(context, it) }
    }
}
