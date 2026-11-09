package com.inboxiq.app.feature

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The relay variant has INTERNET, so the first-run screen must never claim otherwise. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PrivacyProofMatchesManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun relayHidesTheNoInternetProof() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains("android.permission.INTERNET"))
        assertFalse(context.resources.getBoolean(R.bool.privacy_no_internet))
    }
}
