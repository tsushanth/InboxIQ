package com.inboxiq.app.demo

import android.content.Context

/**
 * Debug-only seam for store screenshots: fake contact names and a "looks like the default app" UI,
 * both driven by preferences that [DemoSeeder] writes. The release source set has an inert stub.
 * The UI-only default flag never touches the real SMS role, so nothing reads or writes the SMS provider.
 */
object DemoHooks {
    const val PREFS = "inboxiq_demo"
    const val KEY_ACTIVE = "active"
    const val NAME_PREFIX = "name:"

    fun contactName(context: Context, address: String): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getBoolean(KEY_ACTIVE, false)) p.getString(NAME_PREFIX + address, null) else null
    }

    fun shownAsDefault(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
}
