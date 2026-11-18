package com.inboxiq.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inboxiq.app.R
import com.inboxiq.app.reactions.EmojiCatalog
import com.inboxiq.app.reactions.ReactionEmoji

/** Test tags, so the UI tests can find these without depending on wording. */
object ReactionTags {
    const val STRIP = "reaction_strip"
    const val MORE = "reaction_more"
    const val CHIP = "reaction_chip"
    const val PICKER = "emoji_picker"
    const val SEND_SHEET = "reaction_send_sheet"
}

/**
 * The quick reactions at the top of the long-press menu: the six common emoji, then "+" for the full picker.
 * The emoji the user already chose is highlighted, and tapping it again removes it.
 */
@Composable
fun ReactionStrip(
    selected: String?,
    nameOf: (String) -> String,
    onPick: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(horizontal = 4.dp).testTag(ReactionTags.STRIP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReactionEmoji.QUICK.forEach { emoji ->
            val isSelected = selected != null && ReactionEmoji.same(selected, emoji)
            val label = if (isSelected) stringResource(R.string.reaction_remove_named, nameOf(emoji)) else stringResource(R.string.reaction_react_with, nameOf(emoji))
            Box(
                Modifier.size(width = 40.dp, height = 48.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Button, onClickLabel = label) { onPick(emoji) }
                    .semantics { contentDescription = label; this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, fontSize = 22.sp) }
        }
        Box(
            Modifier.size(width = 40.dp, height = 48.dp).clip(CircleShape).testTag(ReactionTags.MORE)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reaction_more), onClick = onMore),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.reaction_more), modifier = Modifier.size(22.dp)) }
    }
}

/** The small pill under a bubble showing the user's reaction. Tapping it removes the reaction. */
@Composable
fun ReactionChip(emoji: String, name: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.reaction_chip_description, name)
    val removeLabel = stringResource(R.string.reaction_remove_named, name)
    Box(
        modifier.minimumInteractiveComponentSize().testTag(ReactionTags.CHIP)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = removeLabel, onClick = onRemove)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(emoji, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp))
        }
    }
}

/**
 * Screen-reader and switch-access route to what the long-press menu offers: one action per quick reaction, plus
 * removal. A long press alone is not reachable with those tools.
 */
@Composable
fun rememberReactionActions(
    enabled: Boolean,
    current: String?,
    nameOf: (String) -> String,
    onReact: (String) -> Unit,
): List<CustomAccessibilityAction> {
    val context = LocalContext.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onReact)
    return remember(enabled, current) {
        if (!enabled) emptyList()
        else {
            val quick = ReactionEmoji.QUICK
                .filter { current == null || !ReactionEmoji.same(current, it) }
                .map { e -> CustomAccessibilityAction(context.getString(R.string.reaction_react_with, nameOf(e))) { latest.value(e); true } }
            val remove = current?.let { c -> listOf(CustomAccessibilityAction(context.getString(R.string.reaction_remove_action)) { latest.value(c); true }) }.orEmpty()
            quick + remove
        }
    }
}

fun Modifier.withCustomActions(actions: List<CustomAccessibilityAction>): Modifier =
    if (actions.isEmpty()) this else this.semantics { customActions = actions }

/** The full emoji picker: search, category tabs and a grid. Opened from the "+" in the strip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(
    categories: List<EmojiCatalog.Category>,
    recent: List<String>,
    nameOf: (String) -> String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val recentLabel = stringResource(R.string.reaction_picker_recent)
    // The Recent tab only exists once something has been picked.
    val tabs = remember(categories, recent, recentLabel) {
        val recentEntries = recent.map { EmojiCatalog.Entry(it, nameOf(it), "") }
        (if (recentEntries.isEmpty()) emptyList() else listOf(EmojiCatalog.Category("recent", "🕘", recentLabel, recentEntries))) + categories
    }
    var selectedTab by remember { mutableStateOf(0) }
    val searching = query.isNotBlank()
    val shown = if (searching) EmojiCatalog.search(categories, query) else tabs.getOrNull(selectedTab)?.entries.orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().testTag(ReactionTags.PICKER)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.reaction_picker_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searching) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.reaction_picker_clear)) }
                },
            )
            if (!searching) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tabs.forEachIndexed { i, c ->
                        val sel = i == selectedTab
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable(role = Role.Tab) { selectedTab = i }
                                .semantics { contentDescription = c.title; this.selected = sel },
                            contentAlignment = Alignment.Center,
                        ) { Text(c.icon, fontSize = 22.sp) }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.reaction_picker_empty),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    items(shown, key = { it.emoji }) { e ->
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reaction_react_with, e.name)) { onPick(e.emoji) }
                                .semantics { contentDescription = e.name },
                            contentAlignment = Alignment.Center,
                        ) { Text(e.emoji, fontSize = 26.sp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Asks whether a reaction goes out as a text. [explainer] is the one-time first-use version, whose choice also
 * becomes the standing setting; otherwise it is the per-reaction question of "Ask each time".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionSendSheet(
    emoji: String,
    explainer: Boolean,
    onSend: () -> Unit,
    onKeepPrivate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding().testTag(ReactionTags.SEND_SHEET)) {
            Text(
                if (explainer) stringResource(R.string.reaction_sheet_title) else stringResource(R.string.reaction_sheet_ask_title, emoji),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (explainer) stringResource(R.string.reaction_sheet_body) else stringResource(R.string.reaction_sheet_ask_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (explainer) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reaction_sheet_private_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSend, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.reaction_sheet_send)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onKeepPrivate, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.reaction_sheet_private)) }
        }
    }
}
