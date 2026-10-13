package com.inboxiq.app

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.AutoHealWorker
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class InboxIqApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Once a day, retries any MMS sends still stuck after both local
        // strategies failed (see AutoHealWorker) — purely local, recovers
        // transient failures like a brief loss of signal.
        val autoHeal = PeriodicWorkRequestBuilder<AutoHealWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_heal_mms",
            ExistingPeriodicWorkPolicy.KEEP,
            autoHeal,
        )

        CrashLogger.install(this)
        com.inboxiq.app.feature.RelayFeatures.instance.onAppStart(this)
    }
}

/**
 * Captures otherwise-invisible crashes (e.g. a background worker dying silently
 * with no logcat history left by the time anyone looks) to a small local file so
 * they can be inspected after the fact via `adb pull`.
 *
 * File-only: there is no existing backend endpoint for crash/status reporting (checked
 * inboxiq-mcp-backend's server.js) and the core app has no network capability at all, so this
 * deliberately does not POST anywhere.
 */
object CrashLogger {
    private const val LOG_FILE = "crash_log.txt"
    private const val OLD_LOG_FILE = "crash_log.txt.old"
    private const val MAX_SIZE_BYTES = 500 * 1024L

    fun install(app: Application) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(app, thread, throwable)
            } catch (t: Throwable) {
                // Never let logging itself take down the crash handler.
                Log.e("CrashLogger", "failed to write crash log", t)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(app: Application, thread: Thread, throwable: Throwable) {
        val logFile = File(app.filesDir, LOG_FILE)
        if (logFile.exists() && logFile.length() > MAX_SIZE_BYTES) {
            val oldFile = File(app.filesDir, OLD_LOG_FILE)
            logFile.copyTo(oldFile, overwrite = true)
            logFile.delete()
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        val entry = buildString {
            appendLine("---- $timestamp ----")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("app: ${app.packageName}")
            append(stackTrace)
            appendLine()
        }

        logFile.appendText(entry)
    }
}
