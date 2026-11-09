package com.inboxiq.app.firstrun

import android.content.Context
import android.content.SharedPreferences

/** The steps between "installed" and "trusts it as the default SMS app". */
enum class FunnelStep(val label: String) {
    ONBOARDING_SHOWN("Intro screen shown"),
    ROLE_REQUESTED("Tapped Choose as default"),
    ROLE_DECLINED("Android dialog closed without switching"),
    ROLE_GRANTED("Became the default SMS app"),
    IMPORT_STARTED("History copy started"),
    IMPORT_FINISHED("History copy finished"),
    FIRST_LOOK_SHOWN("First-look card shown"),
    FIRST_LOOK_DISMISSED("First-look card dismissed"),
    NOTIFICATIONS_CARD_SHOWN("Notifications-off card shown"),
    NOTIFICATIONS_ON("Notifications turned on"),
    SWITCH_BACK_OPENED("Opened Android default apps settings"),
}

data class FunnelEntry(val step: FunnelStep, val count: Int, val firstAt: Long, val lastAt: Long)

/**
 * Local counters for the first-run funnel, for whoever is testing a build on their own phone. Step name,
 * how many times and when; never message content, numbers, names or anything about contacts. There is no
 * network code here and the core app has no INTERNET permission, so nothing can leave the phone.
 *
 * Recording is OFF in release builds ([enabled] defaults to BuildConfig.DEBUG): the release app keeps no
 * such log at all, which keeps its "no analytics" statement literally true. The viewer lives in debug
 * builds only (Settings, Setup log).
 */
class FunnelLog(
    private val prefs: SharedPreferences,
    private val enabled: Boolean = com.inboxiq.app.BuildConfig.DEBUG,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    val isEnabled: Boolean get() = enabled

    fun record(step: FunnelStep) {
        if (!enabled) return
        val now = clock()
        val k = step.name
        prefs.edit()
            .putInt("$k.n", prefs.getInt("$k.n", 0) + 1)
            .apply { if (!prefs.contains("$k.first")) putLong("$k.first", now) }
            .putLong("$k.last", now)
            .apply()
    }

    /** Every step in funnel order, including the ones never reached (count 0). */
    fun snapshot(): List<FunnelEntry> = FunnelStep.entries.map { s ->
        FunnelEntry(s, prefs.getInt("${s.name}.n", 0), prefs.getLong("${s.name}.first", 0), prefs.getLong("${s.name}.last", 0))
    }

    fun clear() { prefs.edit().clear().apply() }

    companion object { const val FILE = "inboxiq_funnel" }
}
