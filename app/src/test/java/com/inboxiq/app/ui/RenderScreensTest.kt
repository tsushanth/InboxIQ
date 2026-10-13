package com.inboxiq.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadStateEntity
import com.inboxiq.app.inbox.InboxTab
import com.inboxiq.app.ui.theme.Density
import com.inboxiq.app.ui.theme.InboxTheme
import com.inboxiq.app.ui.theme.ThemeMode
import com.inboxiq.app.ui.theme.ThemeSettings
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens headlessly with fake data and writes PNGs to app/build/renders so the
 * design can be reviewed without a device (and without touching any real messages).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RenderScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/renders").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        // Draw the real window; compose-test's own capture waits on a hardware draw pass Robolectric never delivers.
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name rendered non-empty", bmp.width > 0 && bmp.height > 0)
    }

    private fun show(theme: ThemeSettings = ThemeSettings(mode = ThemeMode.LIGHT), content: @Composable () -> Unit) {
        rule.setContent { InboxTheme(theme) { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() } } }
    }

    private fun inbox(initialTab: InboxTab = InboxTab.ALL, rows: List<com.inboxiq.app.inbox.ThreadRow>? = Fixtures.rows(), isDefault: Boolean = true) = @Composable {
        InboxScreen(rows = rows, names = Fixtures.names, isDefaultHandler = isDefault, callbacks = InboxCallbacks(), now = Fixtures.NOW, initialTab = initialTab)
    }

    @Test fun inbox_light() {
        show(content = inbox())
        shot("inbox_light")
        rule.onNodeWithText("Inbox").assertIsDisplayed()
        rule.onNodeWithText("Avery Park", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("New message", useUnmergedTree = true).assertExists()
    }

    @Test fun inbox_dark() { show(ThemeSettings(mode = ThemeMode.DARK), inbox()); shot("inbox_dark") }
    @Test fun inbox_true_black() { show(ThemeSettings(mode = ThemeMode.TRUE_BLACK), inbox()); shot("inbox_true_black") }
    @Test fun inbox_compact_large_text() {
        show(ThemeSettings(mode = ThemeMode.LIGHT, density = Density.COMPACT, fontScale = 1.3f), inbox()); shot("inbox_compact_largest_text")
    }
    @Test fun inbox_codes_tab() {
        show(content = inbox(InboxTab.CODES)); rule.onNodeWithText("Example Bank", substring = true).assertIsDisplayed(); shot("inbox_codes_tab")
    }
    @Test fun inbox_junk_tab() { show(content = inbox(InboxTab.JUNK)); shot("inbox_junk_tab") }
    @Test fun inbox_empty_not_default() { show(content = inbox(rows = emptyList(), isDefault = false)); shot("inbox_empty_not_default") }
    @Test fun inbox_empty_work_tab() { show(content = inbox(InboxTab.WORK, rows = emptyList())); shot("inbox_empty_work_tab") }
    @Test fun inbox_loading() { show(content = inbox(rows = null)); shot("inbox_loading") }

    @Test fun thread_personal() {
        show {
            ThreadScreen("+15550101", "Avery Park", Fixtures.thread(), ThreadCallbacks(), voiceEnabled = false, now = Fixtures.NOW,
                state = ThreadStateEntity("+15550101", draft = null))
        }
        rule.onNodeWithText("Why?").assertIsDisplayed()
        shot("thread_personal")
    }

    @Test fun thread_code_with_copy_chip() {
        show { ThreadScreen("+15550104", null, Fixtures.codeThread(), ThreadCallbacks(), voiceEnabled = false, now = Fixtures.NOW) }
        // The newest code is live ("Copy code"), the older one has expired.
        rule.onAllNodesWithText("Copy code").assertCountEquals(1)
        rule.onAllNodesWithText("Expired").assertCountEquals(1)
        shot("thread_code")
    }

    @Test fun thread_scam() {
        show { ThreadScreen("+15550105", null, Fixtures.scamThread(), ThreadCallbacks(), voiceEnabled = false, now = Fixtures.NOW) }
        shot("thread_scam")
    }

    @Test fun thread_dark() {
        show(ThemeSettings(mode = ThemeMode.DARK)) { ThreadScreen("+15550101", "Avery Park", Fixtures.thread(), ThreadCallbacks(), voiceEnabled = false, now = Fixtures.NOW) }
        shot("thread_dark")
    }

    @Test fun settings() {
        show {
            SettingsScreen(ClassifierTier.DEFAULT, ThemeSettings(mode = ThemeMode.LIGHT), 0, 2, null, SettingsCallbacks(), showCloudSections = false)
        }
        shot("settings")
    }

    @Test fun onboarding() { show { OnboardingScreen(isDefault = false, onChooseDefault = {}, onSkip = {}) }; shot("onboarding") }

    @Test fun blocked_empty() { show { BlockedNumbersScreen(emptyList(), { null }, {}, {}) }; shot("blocked_empty") }

    @Test fun gallery_empty() { show { MediaGalleryScreen("Avery Park", emptyList(), {}, {}) }; shot("gallery_empty") }

    @Test @Config(qualifiers = "w1100dp-h700dp-xhdpi")
    fun two_pane_tablet_landscape() {
        show {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
                InboxScreen(
                    rows = Fixtures.rows(), names = Fixtures.names, isDefaultHandler = true, callbacks = InboxCallbacks(),
                    now = Fixtures.NOW, activeAddress = "+15550101", twoPane = true,
                    modifier = androidx.compose.ui.Modifier.width(380.dp).fillMaxHeight(),
                )
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                    ThreadScreen("+15550101", "Avery Park", Fixtures.thread(), ThreadCallbacks(), showBack = false, voiceEnabled = false, now = Fixtures.NOW)
                }
            }
        }
        shot("two_pane_tablet")
    }

    @Test fun inbox_archived_empty() { show { InboxScreen(Fixtures.rows(), Fixtures.names, true, InboxCallbacks(), now = Fixtures.NOW, initialShowArchived = true) }; shot("inbox_archived_empty") }

    @Test fun link_warning_content_is_reachable() {
        // The scam thread's link is flagged, so tapping it must warn rather than open.
        val a = com.inboxiq.app.inbox.LinkSafety.analyze("usps-redelivery.top", MessageLabel.SCAM)
        assertTrue(a.risk == com.inboxiq.app.inbox.LinkRisk.DANGER)
    }
}
