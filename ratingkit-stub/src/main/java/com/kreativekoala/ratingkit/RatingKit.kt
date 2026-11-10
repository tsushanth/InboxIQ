package com.kreativekoala.ratingkit

import android.app.Activity
import android.content.Context

/** No-op stand-in with the API InboxIQ uses from the real RatingKit module. See ratingkit-stub/build.gradle.kts. */
object RatingKit {
    fun init(context: Context, appId: String) {}
    fun trackAppOpen(activity: Activity?) {}
    fun trackAction(activity: Activity?) {}
    fun reportError() {}
}
