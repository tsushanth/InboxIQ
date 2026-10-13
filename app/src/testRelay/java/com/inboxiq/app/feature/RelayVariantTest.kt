package com.inboxiq.app.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The `relay` variant keeps the relay, its strings and the INTERNET permission it needs. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class RelayVariantTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun relayStringsAndPermissionPresent() {
        assertTrue(context.resources.getIdentifier("relay_consent_title", "string", context.packageName) != 0)
        val requested = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(requested.contains("android.permission.INTERNET"))
    }
}
