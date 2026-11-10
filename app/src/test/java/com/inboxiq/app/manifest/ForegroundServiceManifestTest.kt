package com.inboxiq.app.manifest

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the foreground-service footprint of the shipped manifest (docs/FOREGROUND_SERVICE_DECLARATION.md).
 *
 * InboxIQ's own code never starts a foreground service. The only foreground-service entries in the merged
 * manifest come from the Play Asset Delivery library (Gemma on-demand pack), which binds its own
 * `ExtractionForegroundService` while Play extracts the pack. That service and its permission must stay
 * together: the library enables the component at start-up, and `startForeground` on a dataSync service
 * without FOREGROUND_SERVICE_DATA_SYNC throws a SecurityException. Anyone adding a new foreground service
 * or permission has to update this test and the Play Console declaration on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ForegroundServiceManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun requested(): Set<String> =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

    private fun services() =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS).services.orEmpty().toList()

    @Test fun onlyTheTwoLibraryForegroundPermissionsAreRequested() {
        val fgs = requested().filter { it.startsWith("android.permission.FOREGROUND_SERVICE") }.toSet()
        assertEquals(
            "A new foreground-service permission needs a Play Console declaration: update docs/FOREGROUND_SERVICE_DECLARATION.md",
            setOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
            fgs,
        )
    }

    @Test fun noInboxIqServiceIsAForegroundService() {
        val own = services().filter { it.name.startsWith("com.inboxiq.app.") }
        assertTrue("expected the headless respond-via-message service", own.any { it.name.endsWith("HeadlessSmsSendService") })
        for (s in own) assertEquals("${s.name} must not declare a foreground service type", 0, s.foregroundServiceType)
    }

    @Test fun assetPackExtractionServiceAndItsPermissionStayTogether() {
        val extraction = services().firstOrNull { it.name == "com.google.android.play.core.assetpacks.ExtractionForegroundService" }
        val hasPermission = "android.permission.FOREGROUND_SERVICE_DATA_SYNC" in requested()
        assertEquals(
            "ExtractionForegroundService (dataSync) and FOREGROUND_SERVICE_DATA_SYNC must be removed or kept together, " +
                "and only after a device test of the Gemma pack download",
            extraction != null, hasPermission,
        )
    }

    @Test fun appSourceNeverStartsAForegroundService() {
        val banned = Regex("""startForeground\(|startForegroundService\(|setForeground\(|setForegroundAsync\(|ForegroundInfo|setExpedited\(|foregroundServiceType""")
        val roots = listOf(File("src"), File("app/src")).filter { it.isDirectory }
        assertTrue("run from the app module directory", roots.isNotEmpty())
        val hits = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.name == "AndroidManifest.xml") }
                .filter { "/cpp/" !in it.path && "/test" !in it.path }
                .filter { banned.containsMatchIn(it.readText()) }
                .map { it.path }
                .toList()
        }
        assertFalse("foreground service use found in app source: $hits", hits.isNotEmpty())
    }
}
