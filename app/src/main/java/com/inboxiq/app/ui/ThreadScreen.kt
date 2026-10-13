package com.inboxiq.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inboxiq.app.classify.QuickReplySuggester
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ScheduledMessageEntity
import com.inboxiq.app.data.ThreadStateEntity
import com.inboxiq.app.inbox.DeliveryInfo
import com.inboxiq.app.inbox.LabelExplainer
import com.inboxiq.app.inbox.LinkAssessment
import com.inboxiq.app.inbox.LinkRisk
import com.inboxiq.app.inbox.LinkSafety
import com.inboxiq.app.inbox.MuteDuration
import com.inboxiq.app.inbox.OtpExtractor
import com.inboxiq.app.ui.theme.CodeStyle
import com.inboxiq.app.voice.VoiceTranscriber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

/** Everything the thread screen can ask its host to do. Defaults keep previews and render tests small. */
class ThreadCallbacks(
    val onBack: () -> Unit = {},
    val onSend: (body: String, imageUri: Uri?) -> Unit = { _, _ -> },
    val onPickImage: ((Uri?) -> Unit) -> Unit = {},
    val onDeleteConversation: () -> Unit = {},
    val onDeleteMessage: (Long) -> Unit = {},
    val onRetryNow: (Long) -> Unit = {},
    val onToggleBlock: () -> Unit = {},
    val onPin: (Boolean) -> Unit = {},
    val onArchive: (Boolean) -> Unit = {},
    val onMute: (MuteDuration?) -> Unit = {},
    val onCorrectLabel: (MessageLabel) -> Unit = {},
    val onReportSpam: (Boolean) -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onForward: (String) -> Unit = {},
    val onOpenMedia: () -> Unit = {},
    val onOpenImage: (Long) -> Unit = {},
    val onSchedule: (body: String, at: Long) -> Unit = { _, _ -> },
    val onUndoSend: (Long) -> Unit = {},
    val onCancelScheduled: (Long) -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    address: String,
    displayName: String?,
    messages: List<MessageEntity>,
    callbacks: ThreadCallbacks,
    modifier: Modifier = Modifier,
    state: ThreadStateEntity? = null,
    scheduled: List<ScheduledMessageEntity> = emptyList(),
    isBlocked: Boolean = false,
    labelConfidence: Float = 0f,
    showBack: Boolean = true,
    voiceEnabled: Boolean = true,
    initialDraft: String = "",
    now: Long = System.currentTimeMillis(),
) {
    var body by remember(address) { mutableStateOf(initialDraft) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var showWhy by remember { mutableStateOf(false) }
    var showMute by remember { mutableStateOf(false) }
    var showLabelPicker by remember { mutableStateOf(false) }
    var showSpam by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<MessageEntity?>(null) }
    var linkWarning by remember { mutableStateOf<LinkAssessment?>(null) }
    val context = LocalContext.current

    // A ticking clock, so a code's "Copy" button turns into "Expired" without the screen being reopened.
    var tick by remember { mutableLongStateOf(now) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); tick = System.currentTimeMillis() } }

    // Drafts are saved as you type (after a short pause) and when you leave, so a half-written text is never lost.
    val onDraft = rememberUpdatedState(callbacks.onDraftChange)
    LaunchedEffect(body) { delay(400); onDraft.value(body) }
    val latestBody = rememberUpdatedState(body)
    DisposableEffect(address) { onDispose { onDraft.value(latestBody.value) } }

    val threadLabel = messages.lastOrNull { it.isIncoming }?.label
    val lastMessage = messages.lastOrNull()
    val suggestions = remember(lastMessage?.id) {
        if (lastMessage != null && QuickReplySuggester.needsResponse(lastMessage.isIncoming, threadLabel ?: MessageLabel.UNLABELED)) {
            QuickReplySuggester.suggest(lastMessage.body)
        } else emptyList()
    }
    val muted = state?.isMuted(tick) == true

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // ---------------- header ----------------
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBack) {
                        IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to inbox") }
                    } else Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            displayName ?: address,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            threadLabel?.let {
                                // The label is a property of the sender, so it's shown once, here, and tapping it says why.
                                Box(Modifier.heightIn(min = 32.dp).clickable(onClickLabel = "Why is this ${LabelPalette.displayName(it)}?") { showWhy = true }, contentAlignment = Alignment.CenterStart) {
                                    LabelChip(it)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Why?",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showWhy = true }.padding(vertical = 8.dp),
                                )
                            }
                            if (displayName != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (muted) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    IconButton(onClick = callbacks.onOpenMedia) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Photos in this conversation") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val pinned = state?.pinnedAt != null
                            DropdownMenuItem(
                                text = { Text(if (pinned) "Unpin" else "Pin to top") },
                                leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                                onClick = { menuOpen = false; callbacks.onPin(!pinned) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state?.archived == true) "Move to inbox" else "Archive") },
                                leadingIcon = { Icon(Icons.Filled.Archive, null) },
                                onClick = { menuOpen = false; callbacks.onArchive(state?.archived != true) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (muted) "Unmute" else "Mute notifications") },
                                leadingIcon = { Icon(Icons.Filled.NotificationsOff, null) },
                                onClick = { menuOpen = false; if (muted) callbacks.onMute(null) else showMute = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Change label") },
                                leadingIcon = { Icon(Icons.Filled.Label, null) },
                                onClick = { menuOpen = false; showLabelPicker = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Report as spam") },
                                leadingIcon = { Icon(Icons.Filled.Report, null) },
                                onClick = { menuOpen = false; showSpam = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isBlocked) "Unblock number" else "Block number") },
                                leadingIcon = { Icon(Icons.Filled.Block, null) },
                                onClick = { menuOpen = false; callbacks.onToggleBlock() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete conversation") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menuOpen = false; callbacks.onDeleteConversation() },
                            )
                        }
                    }
                }
                if (isBlocked) {
                    Text(
                        "This number is blocked. New texts from it are not saved. Unblock it from the menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // ---------------- messages ----------------
        val listState = rememberLazyListState()
        val extra = scheduled.size
        LaunchedEffect(messages.size, extra) {
            val count = messages.size + extra
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
        if (messages.isEmpty() && scheduled.isEmpty()) {
            StateMessage(
                Icons.Filled.Info,
                "No messages yet",
                "Say hello below. Your texts go out as standard SMS or MMS through your carrier.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                    val prev = messages.getOrNull(index - 1)
                    if (prev == null || !sameDay(prev.timestamp, message.timestamp)) DayHeader(message.timestamp, tick)
                    MessageBubble(
                        message = message,
                        threadLabel = threadLabel ?: MessageLabel.UNLABELED,
                        now = tick,
                        onLinkClick = { url ->
                            val a = LinkSafety.analyze(url, threadLabel ?: MessageLabel.UNLABELED)
                            if (a.risk == LinkRisk.NONE) openUrl(context, a.url) else linkWarning = a
                        },
                        onOpenImage = { callbacks.onOpenImage(message.id) },
                        onDetails = { details = message },
                        onRetry = { callbacks.onRetryNow(message.id) },
                        onDelete = { callbacks.onDeleteMessage(message.id) },
                        onForward = { callbacks.onForward(message.body) },
                    )
                }
                itemsIndexed(scheduled, key = { _, s -> "s" + s.id }) { _, s ->
                    ScheduledBubble(s, onUndo = { callbacks.onUndoSend(s.id) }, onCancel = { callbacks.onCancelScheduled(s.id) })
                }
            }
        }

        // ---------------- quick replies ----------------
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    AssistChip(
                        onClick = { body = suggestion },
                        label = { Text(suggestion) },
                        shape = RoundedCornerShape(50),
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = null,
                    )
                }
            }
        }

        Composer(
            body = body,
            onBodyChange = { body = it },
            pendingImage = pendingImage,
            onPendingImage = { pendingImage = it },
            onPickImage = callbacks.onPickImage,
            voiceEnabled = voiceEnabled,
            onSend = {
                callbacks.onSend(body, pendingImage)
                body = ""; pendingImage = null
                callbacks.onDraftChange("")
            },
            onSchedule = { showSchedule = true },
        )
    }

    // ---------------- dialogs ----------------
    if (showWhy && threadLabel != null) {
        val e = remember(threadLabel, labelConfidence, state, messages.lastOrNull { it.isIncoming }?.body) {
            LabelExplainer.explain(threadLabel, labelConfidence, state?.labelCorrected == true, messages.lastOrNull { it.isIncoming }?.body, LabelPalette.displayName(threadLabel))
        }
        AlertDialog(
            onDismissRequest = { showWhy = false },
            title = { Text(e.headline) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { e.reasons.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } } },
            confirmButton = { TextButton(onClick = { showWhy = false; showLabelPicker = true }) { Text("Change label") } },
            dismissButton = { TextButton(onClick = { showWhy = false }) { Text("Close") } },
        )
    }
    if (showMute) MuteDialog(anyMuted = muted, onPick = { callbacks.onMute(it); showMute = false }, onDismiss = { showMute = false })
    if (showLabelPicker) LabelPickerDialog(threadLabel, onPick = { callbacks.onCorrectLabel(it); showLabelPicker = false }, onDismiss = { showLabelPicker = false })
    if (showSpam) ReportSpamDialog(displayName ?: address, onConfirm = { callbacks.onReportSpam(it); showSpam = false }, onDismiss = { showSpam = false })
    if (showSchedule) {
        ScheduleDialog(
            canSchedule = body.isNotBlank() && pendingImage == null,
            now = tick,
            onPick = { at ->
                callbacks.onSchedule(body, at)
                body = ""; callbacks.onDraftChange("")
                showSchedule = false
            },
            onDismiss = { showSchedule = false },
        )
    }
    details?.let { m -> MessageDetailsDialog(m, address, displayName, onDismiss = { details = null }) }
    linkWarning?.let { a -> LinkWarningDialog(a, onOpen = { openUrl(context, a.url); linkWarning = null }, onDismiss = { linkWarning = null }) }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayHeader(timestamp: Long, now: Long) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val label = when {
        sameDay(timestamp, now) -> "Today"
        sameDay(timestamp, cal.apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis) -> "Yesterday"
        else -> android.text.format.DateFormat.format(
            android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEMMMd"), Date(timestamp),
        ).toString()
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

/** Copies [text] and marks it sensitive so Android 13+ hides it from the clipboard preview. */
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
}

// ------------------------------------------------------------------------ bubbles

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    threadLabel: MessageLabel,
    now: Long,
    onLinkClick: (String) -> Unit,
    onOpenImage: () -> Unit,
    onDetails: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
) {
    val context = LocalContext.current
    val isMe = !message.isIncoming
    var menu by remember { mutableStateOf(false) }
    val otp = remember(message.id, message.body) { if (message.isIncoming) OtpExtractor.extract(message.body, message.timestamp) else null }
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (isMe) 18.dp else 4.dp, bottomEnd = if (isMe) 4.dp else 18.dp,
    )
    val failed = DeliveryInfo.isFailure(message)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 520.dp)) {
            Box {
                val invite = remember(message.body) { extractRiddleInvite(message.body) }
                if (invite != null) {
                    Box(Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })) {
                        RiddleInviteCard(url = invite.second, isMe = isMe)
                    }
                } else {
                    Surface(
                        color = bubbleColor, shape = shape,
                        modifier = Modifier.combinedClickable(onClickLabel = null, onClick = {}, onLongClickLabel = "Message actions", onLongClick = { menu = true }),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            if (message.body.isNotBlank()) LinkifiedText(message.body, textColor, onLinkClick)
                            message.imagePartUri?.let { uri ->
                                if (message.body.isNotBlank()) Spacer(Modifier.height(6.dp))
                                MmsImage(uri, Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open photo", onClick = onOpenImage))
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (message.body.isNotBlank()) {
                        DropdownMenuItem(text = { Text("Copy text") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menu = false; copyToClipboard(context, "Message", message.body); Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show() })
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; shareText(context, message.body) })
                        DropdownMenuItem(text = { Text("Forward") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, null) },
                            onClick = { menu = false; onForward() })
                    }
                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                        onClick = { menu = false; onDetails() })
                    if (message.awaitingAutoHeal || message.sendStatus == com.inboxiq.app.data.SendStatus.FAILED) {
                        DropdownMenuItem(text = { Text("Retry now") }, leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { menu = false; onRetry() })
                    }
                    DropdownMenuItem(text = { Text("Delete message") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; onDelete() })
                }
            }

            otp?.let { CodeChip(it.code, expired = it.isExpired(now), onCopy = {
                copyToClipboard(context, "Verification code", it.code, sensitive = true)
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }) }

            // Only shown above a confidence floor: this signal is weaker on SMS-length text.
            message.aiGeneratedConfidence?.takeIf { !isMe && it >= AI_GENERATED_DISPLAY_THRESHOLD }?.let { c ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        "Possibly AI-written (${(c * 100).toInt()}% sure)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            val status = DeliveryInfo.short(message)
            Text(
                text = formatMessageTime(message.timestamp) + (status?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
            if (failed) {
                TextButton(onClick = onRetry) { Text("Retry now") }
            }
        }
    }
}

/** Below this, the AI-written-text signal is too noisy on SMS-length text to show. */
private const val AI_GENERATED_DISPLAY_THRESHOLD = 0.6f

@Composable
fun CodeChip(code: String, expired: Boolean, onCopy: () -> Unit) {
    val spaced = if (code.length == 6) code.chunked(3).joinToString(" ") else code
    Surface(
        color = if (expired) MaterialTheme.colorScheme.surfaceVariant else styleFor(MessageLabel.OTP_2FA).container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp).clickable(enabled = !expired, onClickLabel = "Copy code", onClick = onCopy)
            .semantics { contentDescription = if (expired) "Code $code has expired" else "Copy code ${code.toList().joinToString(" ")}" },
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (expired) Icons.Filled.Schedule else Icons.Filled.ContentCopy, null,
                tint = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else styleFor(MessageLabel.OTP_2FA).content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                spaced,
                style = CodeStyle,
                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else styleFor(MessageLabel.OTP_2FA).content,
                textDecoration = if (expired) TextDecoration.LineThrough else null,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (expired) "Expired" else "Copy code",
                style = MaterialTheme.typography.labelLarge,
                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else styleFor(MessageLabel.OTP_2FA).content,
            )
        }
    }
}

@Composable
private fun ScheduledBubble(s: ScheduledMessageEntity, onUndo: () -> Unit, onCancel: () -> Unit) {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), s.id) {
        while (s.isUndoWindow) { delay(1_000); value = System.currentTimeMillis() }
    }
    val remaining = ((s.sendAt - now) / 1000).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(s.body, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (s.isUndoWindow) "Sending in ${remaining}s" else "Scheduled for " + formatFullTimestamp(s.sendAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = if (s.isUndoWindow) onUndo else onCancel) { Text(if (s.isUndoWindow) "Undo" else "Cancel") }
            }
        }
    }
}

// ------------------------------------------------------------------------ links

@Composable
private fun LinkifiedText(text: String, color: Color, onLinkClick: (String) -> Unit) {
    val annotated = remember(text, color) {
        buildAnnotatedString {
            var lastEnd = 0
            val matcher = android.util.Patterns.WEB_URL.matcher(text)
            while (matcher.find()) {
                append(text.substring(lastEnd, matcher.start()))
                val url = matcher.group()
                withLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)),
                        linkInteractionListener = { onLinkClick(url) },
                    ),
                ) { append(url) }
                lastEnd = matcher.end()
            }
            append(text.substring(lastEnd))
        }
    }
    Text(annotated, color = color, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun LinkWarningDialog(a: LinkAssessment, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, null, tint = if (a.risk == LinkRisk.DANGER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        title = { Text(if (a.risk == LinkRisk.DANGER) "This link looks unsafe" else "Check this link first") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It would open:", style = MaterialTheme.typography.bodyMedium)
                Text(a.host, style = MaterialTheme.typography.titleMedium)
                a.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "InboxIQ checks links on your phone only. No warning would not mean a link is safe.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Don't open") } },
        dismissButton = { TextButton(onClick = onOpen) { Text("Open anyway") } },
    )
}

// ------------------------------------------------------------------------ details / share

@Composable
private fun MessageDetailsDialog(m: MessageEntity, address: String, name: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow(if (m.isIncoming) "From" else "To", name?.let { "$it ($address)" } ?: address)
                DetailRow(if (m.isIncoming) "Received" else "Sent from this phone", formatFullTimestamp(m.timestamp))
                DetailRow("Type", if (m.imagePartUri != null) "Picture message (MMS)" else "Text message (SMS)")
                DetailRow("Length", "${m.body.length} characters")
                if (m.isIncoming) {
                    DetailRow("Filed under", LabelPalette.displayName(m.label))
                } else {
                    DetailRow("Status", DeliveryInfo.detail(m))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ------------------------------------------------------------------------ schedule

@Composable
private fun ScheduleDialog(canSchedule: Boolean, now: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val options = remember(now) { com.inboxiq.app.inbox.ScheduleOptions.quick(now) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send later") },
        text = {
            Column {
                if (!canSchedule) {
                    Text("Write a text message first. Picture messages cannot be scheduled.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "InboxIQ sends it for you, so the app does not need to be open. Android decides the exact moment, so it may go a few minutes after the time you pick.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    options.forEach { (label, at) ->
                        TextButton(onClick = { onPick(at) }, modifier = Modifier.fillMaxWidth()) {
                            Text("$label  ·  ${formatFullTimestamp(at)}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    val ctx = LocalContext.current
                    TextButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = now }
                            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                                android.app.TimePickerDialog(ctx, { _, h, min ->
                                    val at = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                                    if (at > System.currentTimeMillis()) onPick(at)
                                    else Toast.makeText(ctx, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                                }, 9, 0, android.text.format.DateFormat.is24HourFormat(ctx)).show()
                            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pick a date and time", modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------ composer

@Composable
private fun Composer(
    body: String,
    onBodyChange: (String) -> Unit,
    pendingImage: Uri?,
    onPendingImage: (Uri?) -> Unit,
    onPickImage: ((Uri?) -> Unit) -> Unit,
    voiceEnabled: Boolean,
    onSend: () -> Unit,
    onSchedule: () -> Unit,
) {
    val canSend = body.isNotBlank() || pendingImage != null
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            pendingImage?.let { uri ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        MmsImage(uri.toString(), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                        IconButton(
                            onClick = { onPendingImage(null) },
                            modifier = Modifier.size(28.dp).align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                        ) { Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { onPickImage { uri -> if (uri != null) onPendingImage(uri) } }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach a photo")
                }
                if (voiceEnabled) VoiceButton { text -> onBodyChange(if (body.isBlank()) text else "$body $text") }
                TextField(
                    value = body,
                    onValueChange = onBodyChange,
                    placeholder = { Text("Text message", maxLines = 1) },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    maxLines = 5,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).heightIn(min = 48.dp),
                )
                if (body.isNotBlank() && pendingImage == null) {
                    IconButton(onClick = onSchedule, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Schedule, contentDescription = "Send later")
                    }
                }
                FilledIconButton(
                    onClick = { if (canSend) onSend() },
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Hold to record, release to transcribe. Entirely on-device (see VoiceTranscriber). */
@Composable
private fun VoiceButton(onText: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriber = remember { VoiceTranscriber.getInstance(context) }
    var isRecording by remember { mutableStateOf(false) }
    var isTranscribing by remember { mutableStateOf(false) }
    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "Allow the microphone in Settings to record voice memos", Toast.LENGTH_SHORT).show()
    }
    if (isTranscribing) {
        Box(Modifier.size(48.dp).semantics { contentDescription = "Transcribing your voice memo" }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    } else {
        // Deliberately a plain Box with its own gesture detector: an IconButton's built-in click handling
        // races with press-and-hold detection (confirmed on a real device).
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = if (isRecording) "Recording. Release to transcribe." else "Hold to record a voice memo" }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (!transcriber.hasRecordPermission()) {
                            requestMic.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                        }
                        if (!transcriber.startRecording()) {
                            Toast.makeText(context, "Couldn't start recording", Toast.LENGTH_SHORT).show(); return@detectTapGestures
                        }
                        isRecording = true
                        val released = tryAwaitRelease()
                        isRecording = false
                        if (released) {
                            isTranscribing = true
                            scope.launch {
                                when (val r = transcriber.stopAndTranscribe()) {
                                    is VoiceTranscriber.Result.Success -> onText(r.text)
                                    is VoiceTranscriber.Result.Failure -> Toast.makeText(context, r.reason, Toast.LENGTH_SHORT).show()
                                }
                                isTranscribing = false
                            }
                        } else transcriber.cancelRecording()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(40.dp).background(if (isRecording) MaterialTheme.colorScheme.errorContainer else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------------ riddle invite (existing feature)

private val riddleInvitePattern = Regex("""puzzleverseai\.com/open-riddle\?puzzleId=([\w-]+)""")

internal fun extractRiddleInvite(body: String): Pair<String, String>? {
    val match = riddleInvitePattern.find(body) ?: return null
    return match.groupValues[1] to "https://${match.value}"
}

@Composable
private fun RiddleInviteCard(url: String, isMe: Boolean) {
    val context = LocalContext.current
    val container = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(color = container, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(14.dp).width(220.dp)) {
            Text("RiddleVerse invite", style = MaterialTheme.typography.titleSmall, color = content)
            Spacer(Modifier.height(6.dp))
            Text("You've been invited to solve a puzzle", style = MaterialTheme.typography.bodySmall, color = content)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { openUrl(context, url) }, modifier = Modifier.fillMaxWidth()) { Text("Play now") }
        }
    }
}
