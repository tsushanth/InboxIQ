package com.inboxiq.app.firstrun

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.ui.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class FirstRunLogicTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var n = 0
    private fun prefs() = FirstRunPrefs(context.getSharedPreferences("firstrun_test_${n++}", Context.MODE_PRIVATE))

    // ---- first-look queueing ----

    @Test fun `fresh install that becomes default queues the first look`() {
        val p = prefs()
        p.noteLaunch(alreadyDefault = false)
        assertFalse(p.firstLookPending)
        p.onRoleGranted()
        assertTrue(p.firstLookPending)
    }

    @Test fun `install that was already default at first launch never gets the card`() {
        val p = prefs()
        p.noteLaunch(alreadyDefault = true)
        p.onRoleGranted()
        assertFalse(p.firstLookPending)
    }

    @Test fun `install that finished onboarding before this flow existed never gets the card`() {
        val p = prefs()
        p.onboardingDone = true
        p.noteLaunch(alreadyDefault = false)
        p.onRoleGranted()
        assertFalse(p.firstLookPending)
    }

    @Test fun `new user who looked around first still gets the card when they switch later`() {
        val p = prefs()
        p.noteLaunch(alreadyDefault = false)
        p.onboardingDone = true          // tapped "Not now, look around"
        p.noteLaunch(alreadyDefault = false) // next cold start must not reclassify them as existing
        p.onRoleGranted()
        assertTrue(p.firstLookPending)
    }

    @Test fun `dismissing the card is permanent`() {
        val p = prefs()
        p.noteLaunch(false); p.onRoleGranted(); p.dismissFirstLook()
        assertFalse(p.firstLookPending)
        p.onRoleGranted() // switching away and back later must not bring it back
        assertFalse(p.firstLookPending)
    }

    @Test fun `onboarding flag shares the key the original screen used`() {
        val raw = context.getSharedPreferences(FirstRunPrefs.FILE, Context.MODE_PRIVATE)
        raw.edit().putBoolean("onboarding_done", true).commit()
        assertTrue(FirstRunPrefs(context).onboardingDone)
    }

    // ---- first look content ----

    @Test fun `summary counts conversations by label and drops empty groups from the sentence`() {
        val s = FirstLook.summarize(Fixtures.rows())
        // Fixture: 3 personal-labelled (incl. outgoing), 1 work, 1 code, 1 promo, 1 scam, 1 spam
        assertEquals(mapOf("personal" to 2, "work" to 1, "promos" to 1, "codes" to 1, "junk" to 2), s.parts.toMap())
        assertEquals(7, s.sorted)
        assertEquals("2 personal, 1 work, 1 promo, 1 code and 2 junk", s.sentence())
    }

    @Test fun `sentence handles one and two groups and nothing`() {
        assertEquals("", FirstLookSummary(0, listOf("personal" to 0)).sentence())
        assertEquals("3 codes", FirstLookSummary(3, listOf("personal" to 0, "codes" to 3)).sentence())
        assertEquals("1 code", FirstLookSummary(1, listOf("codes" to 1)).sentence())
        assertEquals("1 work and 3 codes", FirstLookSummary(4, listOf("work" to 1, "codes" to 3)).sentence())
    }

    @Test fun `unlabeled conversations are not counted as sorted`() {
        val rows = Fixtures.rows().filter { it.label == MessageLabel.PERSONAL }
        assertEquals(rows.size, FirstLook.summarize(rows).sorted)
        assertEquals(0, FirstLook.summarize(emptyList()).sorted)
    }

    @Test fun `first look shows only when pending, default, not copying and something is sorted`() {
        val some = FirstLookSummary(3, listOf("personal" to 3))
        assertTrue(FirstLook.shouldShow(true, true, false, some))
        assertFalse(FirstLook.shouldShow(false, true, false, some))
        assertFalse(FirstLook.shouldShow(true, false, false, some))
        assertFalse(FirstLook.shouldShow(true, true, true, some))
        assertFalse(FirstLook.shouldShow(true, true, false, FirstLookSummary(0, emptyList())))
    }

    // ---- notifications ----

    @Test fun `notification warning only while we hold the role and notifications are off`() {
        assertTrue(NotificationNudge.shouldShow(isDefault = true, notificationsEnabled = false))
        assertFalse(NotificationNudge.shouldShow(isDefault = true, notificationsEnabled = true))
        assertFalse(NotificationNudge.shouldShow(isDefault = false, notificationsEnabled = false))
    }

    @Test fun `permission is asked once on 33 plus then the button opens settings`() {
        assertEquals(NotificationNudge.Action.ASK, NotificationNudge.action(sdk = 34, asked = false))
        assertEquals(NotificationNudge.Action.OPEN_SETTINGS, NotificationNudge.action(sdk = 34, asked = true))
        assertEquals(NotificationNudge.Action.OPEN_SETTINGS, NotificationNudge.action(sdk = 32, asked = false))
    }

    // ---- the way back ----

    @Test fun `default apps intent falls back to plain settings when the page is missing`() {
        val i = DefaultAppHelper.defaultAppsIntent(context)
        assertEquals(android.provider.Settings.ACTION_SETTINGS, i.action) // Robolectric registers no activity for the page
    }

    @Test fun `previous app is remembered only when it is somebody else`() {
        val p = prefs()
        assertNull(DefaultAppHelper.previousLabel(context, p))
        p.previousDefaultPackage = context.packageName
        assertNull(DefaultAppHelper.previousLabel(context, p))
        assertNull(DefaultAppHelper.previousLaunchIntent(context, p))
        p.previousDefaultPackage = "com.example.gone" // uninstalled or invisible: no label, no launch intent, no crash
        assertNull(DefaultAppHelper.previousLabel(context, p))
        assertNull(DefaultAppHelper.previousLaunchIntent(context, p))
    }

    // ---- funnel log ----

    @Test fun `funnel log counts steps and keeps first and last time`() {
        var t = 1_000L
        val log = FunnelLog(context.getSharedPreferences("funnel_test_a", Context.MODE_PRIVATE), enabled = true, clock = { t })
        log.record(FunnelStep.ROLE_REQUESTED); t = 2_000; log.record(FunnelStep.ROLE_REQUESTED); t = 3_000; log.record(FunnelStep.ROLE_GRANTED)
        val by = log.snapshot().associateBy { it.step }
        assertEquals(2, by.getValue(FunnelStep.ROLE_REQUESTED).count)
        assertEquals(1_000L, by.getValue(FunnelStep.ROLE_REQUESTED).firstAt)
        assertEquals(2_000L, by.getValue(FunnelStep.ROLE_REQUESTED).lastAt)
        assertEquals(1, by.getValue(FunnelStep.ROLE_GRANTED).count)
        assertEquals(0, by.getValue(FunnelStep.IMPORT_FINISHED).count)
        assertEquals(FunnelStep.entries.size, log.snapshot().size)
        log.clear()
        assertTrue(log.snapshot().all { it.count == 0 })
    }

    @Test fun `funnel log records nothing when disabled`() {
        val prefs = context.getSharedPreferences("funnel_test_b", Context.MODE_PRIVATE)
        val log = FunnelLog(prefs, enabled = false)
        FunnelStep.entries.forEach(log::record)
        assertTrue(log.snapshot().all { it.count == 0 })
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `funnel keys hold only step names counts and times`() {
        val prefs = context.getSharedPreferences("funnel_test_c", Context.MODE_PRIVATE)
        FunnelLog(prefs, enabled = true).also { it.record(FunnelStep.ONBOARDING_SHOWN) }
        val allowed = Regex("[A-Z_]+\\.(n|first|last)")
        assertTrue(prefs.all.keys.all { allowed.matches(it) })
    }
}
