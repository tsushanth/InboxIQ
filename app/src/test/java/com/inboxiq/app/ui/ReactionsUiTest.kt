package com.inboxiq.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.ReactionEntity
import com.inboxiq.app.data.ReactionKey
import com.inboxiq.app.reactions.ReactionDelivery
import com.inboxiq.app.reactions.ReactionEmoji
import com.inboxiq.app.reactions.ReactionMode
import com.inboxiq.app.reactions.ReactionSettings
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
import android.graphics.Bitmap
import java.io.File

/** Long-press strip, chips, picker, send sheet and settings, with fake people only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ReactionsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val outDir = File("build/renders").apply { mkdirs() }

    private val person = "+14155550100"
    private val dinner = Fixtures.msg(person, "Dinner at 7?", 60 * 60_000L)
    private val mine = Fixtures.msg(person, "Yes, see you there", 50 * 60_000L, incoming = false)
    private val reactionText = Fixtures.msg(person, "Loved “Dinner at 7?”", 40 * 60_000L)

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bmp.width > 0)
    }

    private class Calls {
        val reacts = mutableListOf<Triple<Long, String, ReactionDelivery>>()
        val removes = mutableListOf<Pair<Long, Boolean>>()
        val modes = mutableListOf<ReactionMode>()
        fun callbacks() = ThreadCallbacks(
            onReact = { m, e, d -> reacts += Triple(m.id, e, d) },
            onRemoveReaction = { m, s -> removes += m.id to s },
            onReactionMode = { modes += it },
        )
    }

    private fun show(
        messages: List<MessageEntity>, calls: Calls, address: String = person,
        reactions: Map<Long, ReactionEntity> = emptyMap(), settings: ReactionSettings = ReactionSettings(),
        canSend: Boolean = true, theme: ThemeSettings = ThemeSettings(mode = ThemeMode.LIGHT), recent: List<String> = emptyList(),
    ) {
        rule.setContent {
            InboxTheme(theme) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    ThreadScreen(
                        address, "Avery Park", messages, calls.callbacks(), voiceEnabled = false, now = Fixtures.NOW,
                        reactions = reactions, reactionSettings = settings, canSendReactions = canSend, recentEmoji = recent,
                    )
                }
            }
        }
    }

    private fun longPress(text: String) {
        rule.onNodeWithText(text, substring = true).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    private val quickNames = listOf("thumbs up", "red heart", "face with tears of joy", "face with open mouth", "crying face", "pouting face")

    // ------------------------------------------------------------ strip

    @Test fun long_press_shows_the_six_quick_reactions_and_plus_before_every_existing_action() {
        show(listOf(dinner, mine), Calls())
        longPress("Dinner at 7?")
        rule.onNodeWithTag(ReactionTags.STRIP).assertIsDisplayed()
        quickNames.forEach { rule.onNodeWithContentDescription("React with $it").assertIsDisplayed() }
        rule.onNodeWithContentDescription("More reactions").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("React with", substring = true).assertCountEquals(6)
        // every action that was there before is still there
        for (a in listOf("Copy text", "Share", "Forward", "Details", "Delete message")) rule.onNodeWithText(a).assertIsDisplayed()
        // and the strip comes first
        val stripTop = rule.onNodeWithTag(ReactionTags.STRIP).getUnclippedBoundsInRoot().top
        val copyTop = rule.onNodeWithText("Copy text").getUnclippedBoundsInRoot().top
        assertTrue("strip $stripTop above Copy $copyTop", stripTop < copyTop)
        // all seven slots fit inside the menu and the screen (the menu must not clip or squeeze the strip)
        val strip = rule.onNodeWithTag(ReactionTags.STRIP).getUnclippedBoundsInRoot()
        assertTrue("strip width ${strip.width}", strip.width >= 280.dp)
        assertTrue("strip right edge ${strip.right}", strip.right <= 411.dp)
        rule.onNodeWithContentDescription("More reactions").assertIsDisplayed()
        shot("reactions_strip_light")
    }

    @Test fun the_strip_also_shows_on_my_own_messages_and_in_dark() {
        show(listOf(dinner, mine), Calls(), theme = ThemeSettings(mode = ThemeMode.DARK))
        longPress("Yes, see you there")
        rule.onNodeWithTag(ReactionTags.STRIP).assertIsDisplayed()
        shot("reactions_strip_dark")
    }

    @Test fun the_strip_is_hidden_on_a_message_that_is_itself_a_reaction_text() {
        show(listOf(reactionText), Calls())
        longPress("Loved")
        rule.onAllNodesWithTag(ReactionTags.STRIP).assertCountEquals(0)
        rule.onNodeWithText("Copy text").assertIsDisplayed() // the rest of the menu is unchanged
        rule.onNodeWithText("Delete message").assertIsDisplayed()
        // and it has no reaction actions for TalkBack either
        val withActions = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
        assertTrue(withActions.none { n -> n.config[SemanticsActions.CustomActions].any { it.label.startsWith("React with") } })
    }

    @Test fun the_current_reaction_is_highlighted_and_labelled_for_removal() {
        val r = ReactionKey.forMessage(dinner, ReactionEmoji.THUMBS_UP, 1)
        show(listOf(dinner), Calls(), reactions = mapOf(dinner.id to r))
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("Remove thumbs up reaction").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("React with", substring = true).assertCountEquals(5)
    }

    // ------------------------------------------------------------ what a tap does

    @Test fun first_reaction_shows_the_one_time_explainer_with_the_exact_wording_and_its_choice_becomes_the_setting() {
        val calls = Calls()
        show(listOf(dinner), calls)
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("React with red heart").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReactionTags.SEND_SHEET).assertIsDisplayed()
        rule.onNodeWithText("Reactions are sent as an ordinary text message that quotes the original. It uses one SMS and cannot be recalled. People with iPhone see it as text; Google Messages shows a reaction.").assertIsDisplayed()
        assertTrue("nothing is applied before the user chooses", calls.reacts.isEmpty())
        shot("reactions_explainer")
        rule.onNodeWithText("Send as text").performClick()
        rule.waitForIdle()
        assertEquals(listOf(ReactionMode.SEND_AS_TEXT), calls.modes)
        assertEquals(listOf(Triple(dinner.id, ReactionEmoji.HEART, ReactionDelivery.TEXT)), calls.reacts)
    }

    @Test fun keep_private_in_the_explainer_saves_it_on_the_phone_only() {
        val calls = Calls()
        show(listOf(dinner), calls)
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("React with thumbs up").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Keep private").performClick()
        rule.waitForIdle()
        assertEquals(listOf(ReactionMode.KEEP_PRIVATE), calls.modes)
        assertEquals(listOf(Triple(dinner.id, ReactionEmoji.THUMBS_UP, ReactionDelivery.PRIVATE)), calls.reacts)
    }

    private fun storedModeIsUsedWithoutAsking(mode: ReactionMode, delivery: ReactionDelivery) {
        val calls = Calls()
        show(listOf(dinner), calls, settings = ReactionSettings(mode, explained = true))
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("React with face with tears of joy").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(ReactionTags.SEND_SHEET).assertCountEquals(0)
        assertEquals(listOf(Triple(dinner.id, ReactionEmoji.LAUGH, delivery)), calls.reacts)
    }

    @Test fun once_explained_send_as_text_is_used_without_asking() = storedModeIsUsedWithoutAsking(ReactionMode.SEND_AS_TEXT, ReactionDelivery.TEXT)

    @Test fun once_explained_keep_private_is_used_without_asking() = storedModeIsUsedWithoutAsking(ReactionMode.KEEP_PRIVATE, ReactionDelivery.PRIVATE)

    @Test fun ask_each_time_asks_every_time_without_changing_the_setting() {
        val calls = Calls()
        show(listOf(dinner), calls, settings = ReactionSettings(ReactionMode.ASK, explained = true))
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("React with crying face").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Send 😢 as a text?").assertIsDisplayed()
        rule.onNodeWithText("Keep private").performClick()
        rule.waitForIdle()
        assertTrue(calls.modes.isEmpty())
        assertEquals(listOf(Triple(dinner.id, ReactionEmoji.SAD, ReactionDelivery.PRIVATE)), calls.reacts)
    }

    @Test fun where_sending_is_not_allowed_a_tap_applies_privately_with_no_sheet() {
        val calls = Calls()
        val code = Fixtures.msg("12345", "Your store order shipped", 60_000L)
        show(listOf(code), calls, address = "12345", settings = ReactionSettings(ReactionMode.SEND_AS_TEXT, explained = true))
        longPress("Your store order shipped")
        rule.onNodeWithContentDescription("React with thumbs up").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag(ReactionTags.SEND_SHEET).assertCountEquals(0)
        assertEquals(listOf(Triple(code.id, ReactionEmoji.THUMBS_UP, ReactionDelivery.PRIVATE)), calls.reacts)
    }

    @Test fun tapping_the_selected_emoji_again_removes_it() {
        val calls = Calls()
        val r = ReactionKey.forMessage(dinner, ReactionEmoji.THUMBS_UP, 1)
        show(listOf(dinner), calls, reactions = mapOf(dinner.id to r))
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("Remove thumbs up reaction").performClick()
        rule.waitForIdle()
        assertEquals(listOf(dinner.id to false), calls.removes)
        assertTrue(calls.reacts.isEmpty())
    }

    // ------------------------------------------------------------ chips

    @Test fun chip_sits_under_the_reacted_bubble_only_and_describes_itself_for_TalkBack() {
        val r = ReactionKey.forMessage(dinner, ReactionEmoji.HEART, 1)
        val r2 = ReactionKey.forMessage(mine, ReactionEmoji.LAUGH, 1)
        show(listOf(dinner, mine, Fixtures.msg(person, "No reaction here", 10 * 60_000L)), Calls(), reactions = mapOf(dinner.id to r, mine.id to r2))
        rule.onAllNodesWithTag(ReactionTags.CHIP).assertCountEquals(2)
        rule.onNodeWithContentDescription("You reacted red heart to this message").assertIsDisplayed()
        rule.onNodeWithContentDescription("You reacted face with tears of joy to this message").assertIsDisplayed()
        // under its bubble: the chip's top is below the bubble text
        val bubbleBottom = rule.onNodeWithText("Dinner at 7?").getUnclippedBoundsInRoot().bottom
        val chipTop = rule.onNodeWithContentDescription("You reacted red heart to this message").getUnclippedBoundsInRoot().top
        assertTrue("chip $chipTop under bubble $bubbleBottom", chipTop >= bubbleBottom - 2.dp)
        // 48dp touch target
        val touch = rule.onNodeWithContentDescription("You reacted red heart to this message").fetchSemanticsNode().touchBoundsInRoot
        val density = rule.density.density
        assertTrue("touch height ${touch.height / density}dp", touch.height / density >= 47.9f)
        shot("reactions_chips")
    }

    @Test fun tapping_the_chip_removes_the_reaction_without_a_confirmation() {
        val calls = Calls()
        val r = ReactionKey.forMessage(dinner, ReactionEmoji.HEART, 1)
        show(listOf(dinner), calls, reactions = mapOf(dinner.id to r))
        rule.onNodeWithContentDescription("You reacted red heart to this message").performClick()
        rule.waitForIdle()
        assertEquals(listOf(dinner.id to false), calls.removes)
    }

    @Test fun a_reaction_text_never_grows_a_chip() {
        val r = ReactionKey.forMessage(reactionText, ReactionEmoji.HEART, 1)
        show(listOf(reactionText), Calls(), reactions = mapOf(reactionText.id to r))
        rule.onAllNodesWithTag(ReactionTags.CHIP).assertCountEquals(0)
    }

    // ------------------------------------------------------------ TalkBack custom actions

    @Test fun the_bubble_offers_the_six_reactions_as_accessibility_actions() {
        val calls = Calls()
        show(listOf(dinner), calls, settings = ReactionSettings(ReactionMode.KEEP_PRIVATE, explained = true))
        val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes().first { n -> n.config[SemanticsActions.CustomActions].any { it.label.startsWith("React with") } }
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(quickNames.map { "React with $it" }, labels)
        node.config[SemanticsActions.CustomActions].first { it.label == "React with red heart" }.action()
        rule.waitForIdle()
        assertEquals(listOf(Triple(dinner.id, ReactionEmoji.HEART, ReactionDelivery.PRIVATE)), calls.reacts)
    }

    @Test fun with_a_reaction_the_actions_are_the_other_five_plus_remove() {
        val calls = Calls()
        val r = ReactionKey.forMessage(dinner, ReactionEmoji.HEART, 1)
        show(listOf(dinner), calls, reactions = mapOf(dinner.id to r))
        val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes().first { n -> n.config[SemanticsActions.CustomActions].any { it.label == "Remove reaction" } }
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(5 + 1, labels.size)
        assertTrue("React with red heart" !in labels)
        node.config[SemanticsActions.CustomActions].first { it.label == "Remove reaction" }.action()
        rule.waitForIdle()
        assertEquals(listOf(dinner.id to false), calls.removes)
    }

    // ------------------------------------------------------------ picker

    @Test fun plus_opens_the_picker_with_search_and_categories_and_picking_applies_the_emoji() {
        val calls = Calls()
        show(listOf(dinner), calls, settings = ReactionSettings(ReactionMode.KEEP_PRIVATE, explained = true))
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("More reactions").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ReactionTags.PICKER).assertIsDisplayed()
        rule.onNodeWithText("Search emoji").assertIsDisplayed()
        for (c in listOf("Smileys", "People and gestures", "Hearts and symbols", "Animals and nature", "Food and drink", "Activities", "Objects and places")) {
            rule.onNodeWithContentDescription(c).assertExists()
        }
        rule.onAllNodesWithContentDescription("Recent").assertCountEquals(0) // nothing picked yet
        shot("reactions_picker")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ReactionTags.PICKER))).performTextInput("fire")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("fire").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple(dinner.id, "🔥", ReactionDelivery.PRIVATE)), calls.reacts)
        rule.onAllNodesWithTag(ReactionTags.PICKER).assertCountEquals(0)
    }

    @Test fun the_picker_says_so_when_nothing_matches_and_shows_a_recent_tab_when_there_is_one() {
        show(listOf(dinner), Calls(), recent = listOf("🔥"))
        longPress("Dinner at 7?")
        rule.onNodeWithContentDescription("More reactions").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Recent").assertExists()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ReactionTags.PICKER))).performTextInput("zzzzqqq")
        rule.waitForIdle()
        rule.onNodeWithText("No emoji found").assertIsDisplayed()
    }

    // ------------------------------------------------------------ settings

    @Test fun settings_lists_the_three_modes_and_reports_a_change() {
        val modes = mutableListOf<ReactionMode>()
        val removal = mutableListOf<Boolean>()
        rule.setContent {
            InboxTheme(ThemeSettings(mode = ThemeMode.LIGHT)) {
                SettingsScreen(
                    ClassifierTier.DEFAULT, ThemeSettings(mode = ThemeMode.LIGHT), 0, 0, null,
                    SettingsCallbacks(onReactionMode = { modes += it }, onSendRemovalText = { removal += it }),
                    showCloudSections = false, reactions = ReactionSettings(ReactionMode.KEEP_PRIVATE, explained = true),
                )
            }
        }
        for (t in listOf("Reactions", "Send as text", "Keep private", "Ask each time")) rule.onNodeWithText(t).performScrollTo()
        rule.onNodeWithText("Send as text").performClick()
        rule.onNodeWithText("Ask each time").performClick()
        assertEquals(listOf(ReactionMode.SEND_AS_TEXT, ReactionMode.ASK), modes)
        rule.onNodeWithText("Send a text when I remove a reaction").performScrollTo()
        rule.onNodeWithText("Send a text when I remove a reaction").performClick()
        assertEquals(listOf(true), removal)
    }
}
