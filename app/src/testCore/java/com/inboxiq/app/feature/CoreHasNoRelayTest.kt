package com.inboxiq.app.feature

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.inboxiq.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The `core` variant must contain no relay code, strings or network permission. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class CoreHasNoRelayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun relayClassesAreAbsent() {
        for (name in listOf(
            "com.inboxiq.app.mcp.RelayRuntime",
            "com.inboxiq.app.mcp.CloudConnectSection",
            "com.inboxiq.app.mcp.RelayLinkClient",
            "com.inboxiq.app.worker.SendDraftWorker",
            "okhttp3.OkHttpClient",
        )) {
            val found = try { Class.forName(name); true } catch (_: ClassNotFoundException) { false }
            assertFalse("$name must not exist in core", found)
        }
    }

    @Test fun relayStringsAreAbsent() {
        for (res in listOf("relay_consent_title", "relay_section_blurb", "intro_relay_exception")) {
            assertEquals("string/$res must not exist in core", 0, context.resources.getIdentifier(res, "string", context.packageName))
        }
        assertFalse(context.getString(R.string.settings_privacy_note).contains("Claude"))
    }

    @Test fun manifestHasNoInternetPermission() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(requested.isNotEmpty())
        assertFalse(requested.contains("android.permission.INTERNET"))
    }

    @Test fun featureIsNoOp() {
        assertEquals(Unit, RelayFeatures.instance.onAppStart(context.applicationContext as android.app.Application))
    }
}
