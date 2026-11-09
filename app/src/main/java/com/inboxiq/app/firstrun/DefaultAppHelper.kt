package com.inboxiq.app.firstrun

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony

/** Looking after the way back: which app we replaced, and how to reach Android's default-app screen. */
object DefaultAppHelper {

    /** Android's "Default apps" page (SMS app is one tap in). Falls back to plain Settings where a phone lacks it. */
    fun defaultAppsIntent(context: Context): Intent {
        val primary = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (primary.resolveActivity(context.packageManager) != null) primary
        else Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Remembers the current default SMS app if it is somebody else. Call just before asking for the role. */
    fun rememberPrevious(context: Context, prefs: FirstRunPrefs) {
        val current = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        if (current != null && current != context.packageName) prefs.previousDefaultPackage = current
    }

    /** Display name of the remembered app, or null when unknown or not visible to us. */
    fun previousLabel(context: Context, prefs: FirstRunPrefs): String? {
        val pkg = prefs.previousDefaultPackage ?: return null
        if (pkg == context.packageName) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Launch intent for the remembered app, or null if it is gone or cannot be seen. */
    fun previousLaunchIntent(context: Context, prefs: FirstRunPrefs): Intent? {
        val pkg = prefs.previousDefaultPackage ?: return null
        if (pkg == context.packageName) return null
        return context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
