package com.inboxiq.app.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Scratch files for MMS PDUs in the app cache, exposed through the app's FileProvider.
 *
 * SmsManager.downloadMultimediaMessage writes the raw downloaded PDU to the content uri it is given and does
 * nothing else: a default SMS app has to read it back, parse it and store it itself. The platform's MMS service
 * (the phone process) does the writing, so it needs a write grant on the uri.
 */
object MmsDownloadFiles {
    private const val DIR = "mms_download"
    private const val TAG = "MmsDownloadFiles"

    /** Packages that run the platform MMS service (it moved between them across Android versions). */
    private val MMS_SERVICE_PACKAGES = listOf("com.android.phone", "com.android.mms.service")

    /** One file for one download attempt. [name] travels in the result broadcast; [uri] goes to SmsManager. */
    data class Target(val name: String, val file: File, val uri: Uri)

    fun interface TargetFactory {
        fun create(context: Context, id: Long, attempt: Int): Target
    }

    val REAL = TargetFactory { context, id, attempt ->
        // The nano time keeps two attempts at the same row from sharing a file.
        val name = "dl_${id}_${attempt}_${System.nanoTime()}.pdu"
        val file = fileFor(context, name)
        runCatching { file.createNewFile() }
        Target(name, file, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    }

    /** The file called [name] in the scratch directory; [name] must be a plain file name. */
    fun fileFor(context: Context, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && !name.contains("..")) { "bad file name" }
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, name)
    }

    /** Lets the platform MMS service write (and read) [uri]. */
    fun grantToMmsService(context: Context, uri: Uri) {
        for (pkg in MMS_SERVICE_PACKAGES) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) {
                Log.w(TAG, "grant to $pkg failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Removes scratch files older than [maxAgeMs] (a crash between download and parse leaves one behind). */
    fun cleanStale(context: Context, now: Long = System.currentTimeMillis(), maxAgeMs: Long = 60L * 60 * 1000) {
        runCatching {
            File(context.cacheDir, DIR).listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
        }
    }
}
