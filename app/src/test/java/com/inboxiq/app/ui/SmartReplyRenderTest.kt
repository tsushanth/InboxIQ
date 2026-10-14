package com.inboxiq.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.inboxiq.app.autoreply.AutoReplyConfig
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.reply.ReplyIntent
import com.inboxiq.app.reply.ReplyResult
import com.inboxiq.app.reply.ReplySuggester
import com.inboxiq.app.ui.theme.InboxTheme
import com.inboxiq.app.ui.theme.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SmartReplyRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun thread_shows_chips_from_the_suggester_and_the_automatic_marker() {
        val msgs = Fixtures.thread()
        val autoId = msgs[1].id // the user's own earlier text, pretending it was automatic
        rule.setContent {
            InboxTheme(ThemeSettings()) {
                ThreadScreen(
                    address = "+15550101", displayName = "Avery Park", messages = msgs, callbacks = ThreadCallbacks(), now = Fixtures.NOW, voiceEnabled = false,
                    suggester = ReplySuggester { ReplyResult(ReplyIntent.OPEN_QUESTION, suggestions = listOf("Chip one", "Chip two")) },
                    autoSentIds = setOf(autoId),
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Chip one")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Chip two").assertIsDisplayed()
        assertEquals(1, rule.onAllNodes(hasText("Sent automatically", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun auto_reply_settings_screen_says_off_and_default_app_requirement() {
        rule.setContent {
            InboxTheme(ThemeSettings()) { AutoReplyScreen(config = AutoReplyConfig(), isDefaultSms = false, sentCount = 0, callbacks = AutoReplyCallbacks()) }
        }
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("InboxIQ is not your default SMS app", substring = true).assertExists()
        rule.onNodeWithText("Your carrier may charge", substring = true).assertExists()
        rule.onNodeWithText("may cost money", substring = true).assertExists()
        rule.onNodeWithText("To turn it off", substring = true).assertExists()
    }

    @Test fun settings_entry_is_plain_about_cost_recipients_and_turning_it_off() {
        rule.setContent {
            InboxTheme(ThemeSettings()) {
                SettingsScreen(ClassifierTier.DEFAULT, ThemeSettings(), 0, 0, null, SettingsCallbacks(), showCloudSections = false, autoReplySummary = "Off")
            }
        }
        rule.onNodeWithText("Automatic texts may cost money", substring = true).assertExists()
        rule.onNodeWithText("only to people you have texted or saved", substring = true).assertExists()
        rule.onNodeWithText("Turn it off here any time", substring = true).assertExists()
    }
}
