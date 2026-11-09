package com.inboxiq.app.feature

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first-run screen's "check it yourself" steps claim the app has no INTERNET permission. They are shown
 * only when this flag is true, so the flag must never be true for a build whose manifest has INTERNET.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PrivacyProofMatchesManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun coreShowsTheProofAndCoreHasNoInternet() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains("android.permission.INTERNET"))
        assertTrue(context.resources.getBoolean(R.bool.privacy_no_internet))
    }

    @Test fun proofStepsNameTheRealPackage() {
        assertTrue(context.getString(R.string.proof_step_4).contains(context.packageName))
    }
}
