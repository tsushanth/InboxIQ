package com.inboxiq.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.inboxiq.app.classify.QuickReplySuggester
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.inbox.InboxLogic
import com.inboxiq.app.inbox.InboxTab
import com.inboxiq.app.inbox.MuteDuration
import com.inboxiq.app.inbox.SearchLogic
import com.inboxiq.app.inbox.ThreadRow
import com.inboxiq.app.sms.ImportError
import com.inboxiq.app.sms.ImportStatus
import com.inboxiq.app.ui.theme.Density
import com.inboxiq.app.ui.theme.LocalDensityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Everything the inbox can ask its host to do. Defaults are no-ops so previews and render tests stay tiny. */
class InboxCallbacks(
    val onOpenThread: (String) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onRequestDefault: () -> Unit = {},
    val onPin: (Set<String>, Boolean) -> Unit = { _, _ -> },
    val onArchive: (Set<String>, Boolean) -> Unit = { _, _ -> },
    val onMute: (Set<String>, MuteDuration?) -> Unit = { _, _ -> },
    val onMarkRead: (Set<String>) -> Unit = {},
    val onMarkAllRead: () -> Unit = {},
    val onDelete: (Set<String>) -> Unit = {},
    val onBlock: (Set<String>) -> Unit = {},
    val onCorrectLabel: (String, MessageLabel) -> Unit = { _, _ -> },
    val onReportSpam: (String, Boolean) -> Unit = { _, _ -> },
    val onRetryImport: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InboxScreen(
    rows: List<ThreadRow>?,
    names: Map<String, String?>,
    isDefaultHandler: Boolean,
    callbacks: InboxCallbacks,
    modifier: Modifier = Modifier,
    search: (String) -> Flow<List<MessageEntity>> = { flowOf(emptyList()) },
    now: Long = System.currentTimeMillis(),
    initialTab: InboxTab = InboxTab.ALL,
    initialShowArchived: Boolean = false,
    activeAddress: String? = null,
    twoPane: Boolean = false,
    importStatus: ImportStatus = ImportStatus(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var showArchived by rememberSaveable { mutableStateOf(initialShowArchived) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    var menuOpen by remember { mutableStateOf(false) }
    var muteTargets by remember { mutableStateOf<Set<String>?>(null) }
    var labelTarget by remember { mutableStateOf<String?>(null) }
    var spamTarget by remember { mutableStateOf<String?>(null) }

    val all = rows.orEmpty()
    val counts = remember(all) { InboxLogic.unreadCounts(all) }
    val shown = remember(all, tab, showArchived) {
        if (showArchived) InboxLogic.archived(all) else InboxLogic.visible(all, tab)
    }
    val unreadTotal = remember(all) { InboxLogic.totalUnreadMessages(all) }
    val nameOf: (String) -> String? = { names[it] }

    // Drop selections that no longer exist (deleted / archived elsewhere).
    LaunchedEffect(shown) { selected.retainAll(shown.map { it.address }.toSet()) }

    val inSelection = selected.isNotEmpty()
    val selectedRows = all.filter { it.address in selected }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- header ----
            when {
                inSelection -> SelectionBar(
                    selectedRows = selectedRows,
                    now = now,
                    inArchive = showArchived,
                    onClear = { selected.clear() },
                    onPin = { pin -> callbacks.onPin(selected.toSet(), pin); selected.clear() },
                    onArchive = { callbacks.onArchive(selected.toSet(), !showArchived); selected.clear() },
                    onMute = { muteTargets = selected.toSet() },
                    onMarkRead = { callbacks.onMarkRead(selected.toSet()); selected.clear() },
                    onDelete = { callbacks.onDelete(selected.toSet()); selected.clear() },
                    onBlock = { callbacks.onBlock(selected.toSet()); selected.clear() },
                    onChangeLabel = { labelTarget = selected.single() },
                    onReportSpam = { spamTarget = selected.single() },
                )
                searching -> SearchBar(query, { query = it }, onClose = { searching = false; query = "" })
                else -> InboxHeader(
                    title = if (showArchived) "Archived" else "Inbox",
                    subtitle = when {
                        showArchived -> "Hidden from your inbox. New texts bring a thread back."
                        unreadTotal > 0 -> "$unreadTotal unread, sorted on this phone"
                        else -> "All caught up, sorted on this phone"
                    },
                    onBack = if (showArchived) ({ showArchived = false }) else null,
                    onSearch = { searching = true },
                    menu = {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Mark all as read") },
                                leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                                enabled = unreadTotal > 0,
                                onClick = { menuOpen = false; callbacks.onMarkAllRead() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (showArchived) "Back to inbox" else "Archived") },
                                leadingIcon = { Icon(if (showArchived) Icons.Filled.Inbox else Icons.Filled.Archive, null) },
                                onClick = { menuOpen = false; showArchived = !showArchived },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { menuOpen = false; callbacks.onOpenSettings() },
                            )
                        }
                    },
                )
            }

            if (!isDefaultHandler && !searching) {
                DefaultAppBanner(callbacks.onRequestDefault)
            }

            if (searching) {
                SearchResults(query, all, nameOf, search, callbacks.onOpenThread)
            } else {
                if (!showArchived) LabelTray(tab, counts, onSelect = { tab = it; selected.clear() })
                // The sheet the tray tabs sit on.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (importStatus.showStrip) ImportStrip(importStatus, callbacks.onRetryImport)
                        when {
                            rows == null -> LoadingState()
                            shown.isEmpty() && importStatus.running && tab == InboxTab.ALL && !showArchived -> StateMessage(
                                icon = Icons.Filled.Inbox,
                                title = "Copying your texts",
                                body = "Your newest conversations show up here as soon as they are copied. You can keep using the app.",
                                modifier = Modifier.fillMaxSize(),
                            )
                            shown.isEmpty() -> EmptyInbox(tab, showArchived, isDefaultHandler, callbacks.onRequestDefault)
                            else -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 96.dp),
                            ) {
                                items(shown, key = { it.address }) { row ->
                                    SwipeableThreadRow(
                                        row = row,
                                        name = names[row.address],
                                        now = now,
                                        showLabel = tab == InboxTab.ALL || tab == InboxTab.JUNK || showArchived,
                                        isSelected = row.address in selected,
                                        isActive = twoPane && row.address == activeAddress,
                                        selectionMode = inSelection,
                                        inArchive = showArchived,
                                        onClick = {
                                            if (inSelection) toggle(selected, row.address) else callbacks.onOpenThread(row.address)
                                        },
                                        onLongClick = { toggle(selected, row.address) },
                                        callbacks = callbacks,
                                        onMuteRequest = { muteTargets = setOf(row.address) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!searching && !inSelection && !showArchived) {
            ExtendedFloatingActionButton(
                onClick = callbacks.onNewConversation,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New message") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
            )
        }
    }

    muteTargets?.let { targets ->
        val anyMuted = all.filter { it.address in targets }.any { it.muted(now) }
        MuteDialog(
            anyMuted = anyMuted,
            onPick = { callbacks.onMute(targets, it); muteTargets = null; selected.clear() },
            onDismiss = { muteTargets = null },
        )
    }
    labelTarget?.let { address ->
        LabelPickerDialog(
            current = all.firstOrNull { it.address == address }?.label,
            onPick = { callbacks.onCorrectLabel(address, it); labelTarget = null; selected.clear() },
            onDismiss = { labelTarget = null },
        )
    }
    spamTarget?.let { address ->
        ReportSpamDialog(
            name = names[address] ?: address,
            onConfirm = { block -> callbacks.onReportSpam(address, block); spamTarget = null; selected.clear() },
            onDismiss = { spamTarget = null },
        )
    }
}

private fun toggle(list: MutableList<String>, address: String) {
    if (!list.remove(address)) list.add(address)
}

// ---------------------------------------------------------------- header

@Composable
private fun InboxHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    onSearch: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to inbox") }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search messages and contacts") }
        Box { menu() }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("Search people, numbers and message text") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
    }
}

@Composable
private fun DefaultAppBanner(onRequest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "InboxIQ is not your default SMS app yet",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Until it is, Android keeps delivering texts to another app, so nothing new arrives here and nothing sent from here goes out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onRequest) { Text("Choose InboxIQ as default") }
        }
    }
}

// ---------------------------------------------------------------- the label tray

/**
 * The one bold move: the category row is a tray of index tabs. The selected tab rises and shares
 * the sheet's colour so it reads as the open folder; each tab carries its label's icon, its name,
 * and a count of conversations still unread.
 */
@Composable
fun LabelTray(selected: InboxTab, counts: Map<InboxTab, Int>, onSelect: (InboxTab) -> Unit) {
    val scroll = rememberScrollState()
    val offsets = remember { androidx.compose.runtime.mutableStateMapOf<InboxTab, Int>() }
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 32.dp.roundToPx() }
    // Keep the open tab in view, including when a deep link or restored state selects one that is off-screen.
    LaunchedEffect(selected, offsets[selected]) {
        offsets[selected]?.let { scroll.animateScrollTo((it - margin).coerceAtLeast(0)) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InboxTab.entries.forEach { t ->
            val style = tabStyle(t)
            val isSel = t == selected
            val unread = counts[t] ?: 0
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .trackX { offsets[t] = it }
                    .semantics(mergeDescendants = true) {
                        role = Role.Tab
                        this.selected = isSel
                        contentDescription = buildString {
                            append(t.title)
                            if (unread > 0) append(", $unread unread conversation${if (unread == 1) "" else "s"}")
                        }
                    }
                    .clickable(onClick = { onSelect(t) }, role = Role.Tab),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                Surface(
                    color = if (isSel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                    shape = shape,
                    modifier = Modifier.height(if (isSel) 48.dp else 42.dp),
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(3.dp).background(if (isSel) style.content else Color.Transparent))
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp).weight(1f, fill = true),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                style.icon, contentDescription = null,
                                tint = if (isSel) style.content else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                t.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (unread > 0) {
                                Spacer(Modifier.width(6.dp))
                                Surface(color = style.container, shape = CircleShape) {
                                    Text(
                                        unread.toString(),
                                        color = style.content,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- rows

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableThreadRow(
    row: ThreadRow,
    name: String?,
    now: Long,
    showLabel: Boolean,
    isSelected: Boolean,
    isActive: Boolean,
    selectionMode: Boolean,
    inArchive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    callbacks: InboxCallbacks,
    onMuteRequest: () -> Unit,
) {
    val address = row.address
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { callbacks.onArchive(setOf(address), !inArchive); true }
                SwipeToDismissBoxValue.EndToStart -> { callbacks.onDelete(setOf(address)); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    val pinLabel = if (row.pinned) "Unpin" else "Pin"
    val muted = row.muted(now)
    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            val toEnd = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val bg = if (toEnd) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
            val fg = if (toEnd) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
            Row(
                modifier = Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
            ) {
                val (icon, text) = when {
                    toEnd && inArchive -> Icons.Filled.Unarchive to "Unarchive"
                    toEnd -> Icons.Filled.Archive to "Archive"
                    else -> Icons.Filled.Delete to "Delete"
                }
                Icon(icon, contentDescription = null, tint = fg)
                Spacer(Modifier.width(8.dp))
                Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        ThreadRowContent(
            row = row, name = name, now = now, showLabel = showLabel, isSelected = isSelected, isActive = isActive,
            muted = muted,
            modifier = Modifier
                .semantics {
                    // Swipes are invisible to a screen reader, so every swipe action is also listed here.
                    customActions = listOf(
                        CustomAccessibilityAction(if (inArchive) "Unarchive" else "Archive") { callbacks.onArchive(setOf(address), !inArchive); true },
                        CustomAccessibilityAction("Delete conversation") { callbacks.onDelete(setOf(address)); true },
                        CustomAccessibilityAction(pinLabel) { callbacks.onPin(setOf(address), !row.pinned); true },
                        CustomAccessibilityAction(if (muted) "Unmute" else "Mute") { onMuteRequest(); true },
                        CustomAccessibilityAction("Select") { onLongClick(); true },
                    )
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        )
    }
    HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun ThreadRowContent(
    row: ThreadRow,
    name: String?,
    now: Long,
    showLabel: Boolean,
    isSelected: Boolean,
    isActive: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val latest = row.summary.latestMessage
    val unread = row.unread > 0
    val needsReply = QuickReplySuggester.needsResponse(latest.isIncoming, latest.label)
    val vPad = if (LocalDensityMode.current == Density.COMPACT) 8.dp else 14.dp
    val bg = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isActive -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }
    val title = name ?: latest.address
    val preview = row.draft?.let { "Draft: $it" } ?: when {
        latest.body.isBlank() && latest.imagePartUri != null -> "Photo"
        !latest.isIncoming -> "You: ${latest.body}"
        else -> latest.body
    }
    val description = buildString {
        append(title)
        append(". ")
        append(LabelPalette.displayName(latest.label))
        if (unread) append(". ${row.unread} unread")
        if (row.pinned) append(". Pinned")
        if (muted) append(". Muted")
        if (needsReply) append(". Needs a reply")
        append(". ")
        append(preview)
        append(". ")
        append(formatTimestamp(latest.timestamp, now))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = vPad),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            ContactAvatar(name, latest.address, latest.label)
            if (isSelected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.DoneAll, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
                if (muted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.NotificationsOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTimestamp(latest.timestamp, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    row.draft != null -> MaterialTheme.colorScheme.primary
                    unread -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
            )
            if (showLabel || needsReply || unread) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showLabel) LabelChip(latest.label)
                    if (needsReply) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Reply, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Needs a reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (unread) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Text(
                                row.unread.toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- selection

@Composable
private fun SelectionBar(
    selectedRows: List<ThreadRow>,
    now: Long,
    inArchive: Boolean,
    onClear: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onMute: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    onChangeLabel: () -> Unit,
    onReportSpam: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val allPinned = selectedRows.isNotEmpty() && selectedRows.all { it.pinned }
    val allMuted = selectedRows.isNotEmpty() && selectedRows.all { it.muted(now) }
    val single = selectedRows.size == 1
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
            Text(
                "${selectedRows.size} selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onPin(!allPinned) }) {
                Icon(Icons.Filled.PushPin, contentDescription = if (allPinned) "Unpin" else "Pin")
            }
            IconButton(onClick = onArchive) {
                Icon(if (inArchive) Icons.Filled.Unarchive else Icons.Filled.Archive, contentDescription = if (inArchive) "Unarchive" else "Archive")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text(if (allMuted) "Unmute" else "Mute notifications") },
                        leadingIcon = { Icon(Icons.Filled.NotificationsOff, null) },
                        onClick = { more = false; onMute() },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark as read") },
                        leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                        onClick = { more = false; onMarkRead() },
                    )
                    if (single) {
                        DropdownMenuItem(
                            text = { Text("Change label") },
                            leadingIcon = { Icon(Icons.Filled.Label, null) },
                            onClick = { more = false; onChangeLabel() },
                        )
                        DropdownMenuItem(
                            text = { Text("Report as spam") },
                            leadingIcon = { Icon(Icons.Filled.Report, null) },
                            onClick = { more = false; onReportSpam() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Block number") },
                        leadingIcon = { Icon(Icons.Filled.Block, null) },
                        onClick = { more = false; onBlock() },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun MuteDialog(anyMuted: Boolean, onPick: (MuteDuration?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute notifications") },
        text = {
            Column {
                Text(
                    "Messages still arrive and are sorted. You just will not be notified.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                MuteDuration.entries.forEach { d ->
                    TextButton(onClick = { onPick(d) }, modifier = Modifier.fillMaxWidth()) {
                        Text(d.label, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (anyMuted) {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Unmute now", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun LabelPickerDialog(current: MessageLabel?, onPick: (MessageLabel) -> Unit, onDismiss: () -> Unit) {
    val choices = listOf(
        MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.PROMO, MessageLabel.OTP_2FA, MessageLabel.SPAM, MessageLabel.SCAM,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File this conversation under") },
        text = {
            Column {
                choices.forEach { l ->
                    val style = styleFor(l)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(l) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LabelChip(l)
                        Spacer(Modifier.weight(1f))
                        if (l == current) Text("Current", style = MaterialTheme.typography.labelSmall, color = style.content)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Future texts from this number go to the same place. Your choice stays on this phone and the model is not retrained.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ReportSpamDialog(name: String, onConfirm: (alsoBlock: Boolean) -> Unit, onDismiss: () -> Unit) {
    var block by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report $name as spam?") },
        text = {
            Column {
                Text(
                    "The conversation moves to Junk, where you can restore it. Nothing is sent anywhere; reports stay on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { block = !block },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = block, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Also block this number")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(block) }) { Text("Report spam") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- states

/** Progress (or the reason it stopped) for copying the phone's existing texts in. */
@Composable
private fun ImportStrip(status: ImportStatus, onRetry: () -> Unit) {
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val error = status.error
            if (error != null) {
                Text(
                    when (error) {
                        ImportError.PERMISSION_DENIED -> "Android did not let InboxIQ read your texts."
                        ImportError.LOW_STORAGE -> "There is not enough free storage to copy your texts. Free some space, then try again."
                        ImportError.PROVIDER_UNAVAILABLE -> "Android's message storage did not answer."
                        ImportError.UNKNOWN -> "Copying your texts stopped unexpectedly."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                TextButton(onClick = onRetry) { Text("Try again") }
            } else {
                Text(
                    "Copying your texts: ${nf.format(status.processed)} of ${nf.format(maxOf(status.total, status.processed))}. You can keep using the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (status.total > 0) (status.processed.toFloat() / status.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text("Opening your messages", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyInbox(tab: InboxTab, archived: Boolean, isDefault: Boolean, onRequestDefault: () -> Unit) {
    val (title, body) = when {
        archived -> "Nothing archived" to "Swipe a conversation right, or select it and tap Archive, to tidy it away without deleting it."
        tab == InboxTab.ALL && !isDefault ->
            "No messages yet" to "Once InboxIQ is your default SMS app, your existing texts are copied in and sorted on this phone."
        tab == InboxTab.ALL -> "No conversations" to "New texts land here, already sorted. Tap New message to start one."
        tab == InboxTab.PERSONAL -> "No personal messages" to "Texts from people you know show up here."
        tab == InboxTab.WORK -> "No work messages" to "Texts about meetings, shifts and deadlines show up here."
        tab == InboxTab.PROMO -> "No promos" to "Sales and marketing texts are kept here, out of your main inbox."
        tab == InboxTab.CODES -> "No codes" to "Verification codes show up here with a Copy button, and expire after a few minutes."
        else -> "Nothing in Junk" to "Texts the model files as spam or scam go here. If it gets one wrong, open the conversation and change its label."
    }
    StateMessage(
        icon = if (archived) Icons.Filled.Archive else Icons.Filled.SearchOff,
        title = title,
        body = body,
        modifier = Modifier.fillMaxSize(),
        actionLabel = if (tab == InboxTab.ALL && !isDefault && !archived) "Choose InboxIQ as default" else null,
        onAction = if (tab == InboxTab.ALL && !isDefault && !archived) onRequestDefault else null,
    )
}

// ---------------------------------------------------------------- search

@Composable
private fun SearchResults(
    query: String,
    rows: List<ThreadRow>,
    nameOf: (String) -> String?,
    search: (String) -> Flow<List<MessageEntity>>,
    onOpenThread: (String) -> Unit,
) {
    val messagesFlow = remember(query) { search(query) }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val people = remember(query, rows) { SearchLogic.conversations(query, rows, nameOf) }
    val accent = MaterialTheme.colorScheme.primaryContainer

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        when {
            query.isBlank() -> StateMessage(
                Icons.Filled.Search, "Search this phone",
                "Find a person, a number, or any word in your texts. Searching never leaves the device.",
                modifier = Modifier.fillMaxSize(),
            )
            people.isEmpty() && messages.isEmpty() -> StateMessage(
                Icons.Filled.SearchOff, "No matches for \"${query.trim()}\"",
                "Check the spelling, or try part of a phone number. Archived conversations are included.",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (people.isNotEmpty()) {
                    item { SectionTitle("People and numbers") }
                    items(people, key = { "p" + it.address }) { hit ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenThread(hit.address) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val label = rows.firstOrNull { it.address == hit.address }?.label ?: MessageLabel.UNLABELED
                            ContactAvatar(nameOf(hit.address), hit.address, label, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                if (hit.title != hit.address) Text(hit.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (messages.isNotEmpty()) {
                    item { SectionTitle("Messages") }
                    items(messages, key = { "m" + it.id }) { m ->
                        val snippet = remember(m.id, query) { SearchLogic.snippet(m.body, query) }
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpenThread(m.address) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Row {
                                Text(nameOf(m.address) ?: m.address, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(formatTimestamp(m.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                buildAnnotatedString {
                                    append(snippet.text.substring(0, snippet.matchStart))
                                    withStyle(SpanStyle(background = accent, fontWeight = FontWeight.Bold)) {
                                        append(snippet.text.substring(snippet.matchStart, snippet.matchEnd))
                                    }
                                    append(snippet.text.substring(snippet.matchEnd))
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
    )
}

private fun Modifier.trackX(onX: (Int) -> Unit): Modifier =
    this.then(Modifier.onGloballyPositioned { onX(it.positionInParent().x.toInt()) })
