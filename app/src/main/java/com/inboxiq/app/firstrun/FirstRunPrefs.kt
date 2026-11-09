package com.inboxiq.app.firstrun

import android.content.Context
import android.content.SharedPreferences

/**
 * Small flags for the first-run flow. Kept in the same preferences file as the existing onboarding flag so
 * installs that already finished onboarding keep behaving as before.
 */
class FirstRunPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    var onboardingDone: Boolean
        get() = prefs.getBoolean(ONBOARDED, false)
        set(v) { prefs.edit().putBoolean(ONBOARDED, v).apply() }

    /** The first-look card is waiting to be shown (a fresh install just became the default SMS app). */
    var firstLookPending: Boolean
        get() = prefs.getBoolean("first_look_pending", false)
        private set(v) { prefs.edit().putBoolean("first_look_pending", v).apply() }

    private var firstLookDone: Boolean
        get() = prefs.getBoolean("first_look_done", false)
        set(v) { prefs.edit().putBoolean("first_look_done", v).apply() }

    /**
     * Installs that were already set up before this flow existed (onboarded, or already the default when the
     * app opened) must not get a "welcome" card for an inbox they have been using.
     */
    fun noteLaunch(alreadyDefault: Boolean) {
        if (prefs.getBoolean("first_look_decided", false)) return
        // Decided once, on the first launch of a build that has this flow: later launches must not turn a
        // brand-new user who chose "look around" into an "existing" one.
        prefs.edit().putBoolean("first_look_decided", true).apply()
        if (onboardingDone || alreadyDefault) firstLookDone = true
    }

    /** The app has just become the default SMS app. Queues the first-look card once, for new installs only. */
    fun onRoleGranted() {
        if (!firstLookDone && !firstLookPending) firstLookPending = true
    }

    fun dismissFirstLook() {
        firstLookPending = false
        firstLookDone = true
    }

    /** The SMS app that was the default before we asked, so Settings can name it and offer a way back. */
    var previousDefaultPackage: String?
        get() = prefs.getString("previous_default", null)
        set(v) { prefs.edit().putString("previous_default", v).apply() }

    /** True once we have asked for the notification permission: a second ask is silently ignored by Android. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean("notifications_asked", false)
        set(v) { prefs.edit().putBoolean("notifications_asked", v).apply() }

    companion object {
        /** Same file and key as the original onboarding flag in MainActivity. */
        const val FILE = "inboxiq_prefs"
        const val ONBOARDED = "onboarding_done"
    }
}
