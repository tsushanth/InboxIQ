package com.inboxiq.app.demo

import android.content.Context

/** Release stub: store-screenshot demo mode exists only in debug builds (see src/debug), so these never fire. */
object DemoHooks {
    @Suppress("UNUSED_PARAMETER")
    fun contactName(context: Context, address: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun shownAsDefault(context: Context): Boolean = false
}
