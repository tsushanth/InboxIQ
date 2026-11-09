package com.inboxiq.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.assertCountEquals
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.firstrun.FunnelEntry
import com.inboxiq.app.firstrun.FunnelStep
import com.inboxiq.app.ui.theme.InboxTheme
import com.inboxiq.app.ui.theme.ThemeMode
import com.inboxiq.app.ui.theme.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** First-run screens in every state that matters, with fake data only. Renders go to app/build/renders (not committed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FirstRunScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val outDir = File("build/renders").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 0 && bmp.height > 0)
    }

    private fun show(theme: ThemeSettings = ThemeSettings(mode = ThemeMode.LIGHT), content: @Composable () -> Unit) {
        rule.setContent { InboxTheme(theme) { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() } } }
    }

    // ---------------- intro / pre-switch explanation ----------------

    @Test fun intro_shows_example_labelled_as_made_up_and_the_real_ask_only_at_the_bottom() {
        var asked = 0
        show { OnboardingScreen(isDefault = false, onChooseDefault = { asked++ }, onSkip = {}, showPrivacyProof = true) }
        rule.onNodeWithText("EXAMPLE ONLY", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Copy code").assertExists()
        rule.onNodeWithText("Avery Park").assertExists()
        assertEquals("nothing may launch the system dialog before the user taps the button", 0, asked)
        rule.onNodeWithText("Choose InboxIQ as default SMS app").assertIsDisplayed().performClick()
        assertEquals(1, asked)
        shot("firstrun_intro")
    }

    @Test fun intro_explains_changes_stays_and_the_way_back() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, showPrivacyProof = true) }
        for (t in listOf("What changes when you switch", "What stays", "Switching back", "RCS chat features", "nothing is deleted", "about three taps")) {
            rule.onNodeWithText(t, substring = true).performScrollTo().assertExists()
        }
    }

    @Test fun intro_names_the_app_it_replaces_when_known() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, previousAppName = "Messages", showPrivacyProof = true) }
        rule.onNodeWithText("take over from Messages", substring = true).performScrollTo().assertExists()
    }

    @Test fun intro_privacy_proof_has_the_verifiable_steps_on_core() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, showPrivacyProof = true) }
        rule.onNodeWithText("Check the privacy claim yourself").performScrollTo().assertExists()
        rule.onNodeWithText("airplane mode", substring = true).assertExists()
        rule.onNodeWithText("adb shell dumpsys package com.inboxiq.app | grep INTERNET", substring = true).assertExists()
        rule.onNodeWithText("github.com/tsushanth/InboxIQ", substring = true).assertExists()
    }

    @Test fun intro_hides_the_proof_when_the_variant_has_internet() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, showPrivacyProof = false) }
        rule.onAllNodesWithText("Check the privacy claim yourself").assertCountEquals(0)
    }

    @Test fun intro_declined_says_nothing_changed() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, declined = true, showPrivacyProof = true) }
        rule.onNodeWithText("nothing changed", substring = true).assertIsDisplayed()
        shot("firstrun_intro_declined")
    }

    @Test fun intro_when_already_default_offers_only_continue() {
        var skipped = 0
        show { OnboardingScreen(isDefault = true, onChooseDefault = {}, onSkip = { skipped++ }, showPrivacyProof = true) }
        rule.onAllNodesWithText("Choose InboxIQ as default SMS app").assertCountEquals(0)
        rule.onNodeWithText("Continue to inbox").performClick()
        assertEquals(1, skipped)
    }

    @Test fun intro_reread_from_settings_says_back() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, firstRun = false, showPrivacyProof = true) }
        rule.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test fun intro_buttons_stay_reachable_at_the_largest_text_size() {
        show(ThemeSettings(mode = ThemeMode.LIGHT, fontScale = 1.5f)) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale = 2f),
            ) { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, showPrivacyProof = true) }
        }
        rule.onNodeWithText("Choose InboxIQ as default SMS app").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Not now, look around").assertIsDisplayed()
        shot("firstrun_intro_largest_text")
    }

    @Test fun intro_dark() { show(ThemeSettings(mode = ThemeMode.DARK)) { OnboardingScreen(false, {}, {}, showPrivacyProof = true) }; shot("firstrun_intro_dark") }

    @Test @Config(qualifiers = "w1100dp-h700dp-xhdpi")
    fun intro_on_a_tablet_keeps_a_readable_line_length() {
        show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}, showPrivacyProof = true) }
        val w = rule.onNodeWithText("Texts, sorted on this phone").getUnclippedBoundsInRoot().width
        assertTrue("headline column must not span the tablet (was $w)", w <= 560.dp)
        shot("firstrun_intro_tablet")
    }

    // ---------------- first session ----------------

    @Test fun first_look_card_summarises_the_inbox_and_dismisses() {
        var dismissed = 0
        show {
            InboxScreen(
                Fixtures.rows(), Fixtures.names, true, InboxCallbacks(onDismissFirstLook = { dismissed++ }), now = Fixtures.NOW,
                firstRun = FirstRunUi(firstLookSummary = "2 personal, 1 work, 1 promo, 1 code and 2 junk"),
            )
        }
        rule.onNodeWithText("Your inbox, sorted").assertIsDisplayed()
        rule.onNodeWithText("2 personal, 1 work", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open the Codes tab", substring = true).assertExists()
        shot("firstrun_first_look")
        rule.onNodeWithText("Got it").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun first_look_card_is_absent_by_default() {
        show { InboxScreen(Fixtures.rows(), Fixtures.names, true, InboxCallbacks(), now = Fixtures.NOW) }
        rule.onAllNodesWithText("Your inbox, sorted").assertCountEquals(0)
        rule.onAllNodesWithText("Notifications are off").assertCountEquals(0)
    }

    @Test fun notifications_card_asks_then_points_at_settings() {
        var taps = 0
        show {
            InboxScreen(
                Fixtures.rows(), Fixtures.names, true, InboxCallbacks(onEnableNotifications = { taps++ }), now = Fixtures.NOW,
                firstRun = FirstRunUi(notificationsOff = true),
            )
        }
        rule.onNodeWithText("Notifications are off").assertIsDisplayed()
        rule.onNodeWithText("Turn on notifications").performClick()
        assertEquals(1, taps)
        shot("firstrun_notifications_off")
    }

    @Test fun notifications_card_button_changes_once_android_stops_asking() {
        show {
            InboxScreen(Fixtures.rows(), Fixtures.names, true, InboxCallbacks(), now = Fixtures.NOW, firstRun = FirstRunUi(notificationsOff = true, notificationsOpensSettings = true))
        }
        rule.onNodeWithText("Open notification settings").assertIsDisplayed()
    }

    @Test fun not_default_banner_links_to_what_changes() {
        var opened = 0
        show { InboxScreen(emptyList(), Fixtures.names, false, InboxCallbacks(onShowIntro = { opened++ }), now = Fixtures.NOW) }
        rule.onNodeWithText("What changes?").performClick()
        assertEquals(1, opened)
    }

    @Test fun copying_state_says_what_comes_next() {
        show {
            InboxScreen(
                emptyList(), Fixtures.names, true, InboxCallbacks(), now = Fixtures.NOW,
                importStatus = com.inboxiq.app.sms.ImportStatus(running = true, processed = 0, total = 9_700),
            )
        }
        rule.onNodeWithText("Copying your texts: 0 of 9,700", substring = true).assertIsDisplayed()
        rule.onNodeWithText("then InboxIQ sorts them", substring = true).assertExists()
        shot("firstrun_copying")
    }

    // ---------------- settings: the way back ----------------

    @Test fun settings_default_section_offers_the_way_back() {
        var opened = 0
        var previous = 0
        show {
            SettingsScreen(
                ClassifierTier.DEFAULT, ThemeSettings(mode = ThemeMode.LIGHT), 0, 0, null,
                SettingsCallbacks(onOpenDefaultSettings = { opened++ }, onOpenPreviousApp = { previous++ }),
                showCloudSections = false, isDefault = true, previousAppName = "Messages", canOpenPreviousApp = true,
            )
        }
        rule.onNodeWithText("InboxIQ is your default SMS app.").assertIsDisplayed()
        rule.onNodeWithText("To go back to Messages", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Make InboxIQ the default").assertCountEquals(0)
        rule.onNodeWithText("Open Android default apps settings").performClick()
        rule.onNodeWithText("Open Messages").performClick()
        assertEquals(1, opened); assertEquals(1, previous)
        shot("firstrun_settings_default_app")
    }

    @Test fun settings_when_not_default_offers_to_make_it_default_and_hides_unknown_previous_app() {
        var made = 0
        show {
            SettingsScreen(
                ClassifierTier.DEFAULT, ThemeSettings(mode = ThemeMode.LIGHT), 0, 0, null,
                SettingsCallbacks(onMakeDefault = { made++ }), showCloudSections = false, isDefault = false,
            )
        }
        rule.onNodeWithText("InboxIQ is not your default SMS app", substring = true).assertIsDisplayed()
        rule.onNodeWithText("To go back to your old app", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Make InboxIQ the default").performClick()
        assertEquals(1, made)
        rule.onAllNodesWithText("Open Messages").assertCountEquals(0)
    }

    @Test fun settings_setup_log_entry_only_when_asked_for() {
        show { SettingsScreen(ClassifierTier.DEFAULT, ThemeSettings(), 0, 0, null, SettingsCallbacks(), showCloudSections = false) }
        rule.onAllNodesWithText("Setup log", substring = true).assertCountEquals(0)
    }

    @Test fun setup_log_lists_steps_without_any_message_content() {
        val entries = listOf(
            FunnelEntry(FunnelStep.ONBOARDING_SHOWN, 1, 1_790_000_000_000L, 1_790_000_000_000L),
            FunnelEntry(FunnelStep.ROLE_REQUESTED, 2, 1_790_000_001_000L, 1_790_000_030_000L),
            FunnelEntry(FunnelStep.ROLE_GRANTED, 0, 0, 0),
        )
        show { SetupLogScreen(entries, {}, {}) }
        rule.onNodeWithText("Tapped Choose as default: 2x").assertIsDisplayed()
        rule.onNodeWithText("Became the default SMS app: not reached").assertIsDisplayed()
        shot("firstrun_setup_log")
    }
}
