package com.inboxiq.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.classify.ClassifierTierPreference
import com.inboxiq.app.classify.QuickReplySuggester
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.data.ThreadSummary
import com.inboxiq.app.sms.DefaultSmsRole
import com.inboxiq.app.sms.MmsSender
import com.inboxiq.app.sms.SmsBackfill
import com.inboxiq.app.sms.SmsSender
import com.inboxiq.app.voice.VoiceTranscriber
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface Screen {
    data object ThreadList : Screen
    data class ThreadDetail(val address: String) : Screen
    data object Settings : Screen
    data object NewConversation : Screen
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ADDRESS = "com.inboxiq.app.EXTRA_THREAD_ADDRESS"
    }

    private val requestRole = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { runBackfillIfDefault() }

    private var onContactPicked: ((String?) -> Unit)? = null
    private val pickContact = registerForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { uri -> onContactPicked?.invoke(uri?.let { resolvePickedContactNumber(this, it) }) }

    private var onImagePicked: ((Uri?) -> Unit)? = null
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> onImagePicked?.invoke(uri) }

    private val screenState = mutableStateOf<Screen>(Screen.ThreadList)

    private fun addressFromIntent(intent: android.content.Intent?): String? {
        val fromExtra = intent?.getStringExtra(EXTRA_THREAD_ADDRESS)
        if (fromExtra != null) return fromExtra
        return intent?.data?.takeIf { it.scheme == "inboxiq" }?.lastPathSegment
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        addressFromIntent(intent)?.let { address -> screenState.value = Screen.ThreadDetail(address) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.get(applicationContext)
        val dao = db.messageDao()
        val blockedDao = db.blockedNumberDao()

        addressFromIntent(intent)?.let { address -> screenState.value = Screen.ThreadDetail(address) }

        // Self-heal the relay connection: a foreground service getting killed (Doze, low
        // memory, a reboot) doesn't clear the user's "enabled" preference, and nothing else
        // restarts it — confirmed live that this leaves Settings showing "on" while nothing
        // is actually connected, silently, for as long as the user doesn't happen to
        // re-toggle it. Runs from an Activity (not Application.onCreate) since starting a
        // foreground service from a background-triggered process start hits Android's
        // background-start restrictions; opening the app is always a safe, foregrounded context.
        if (com.inboxiq.app.mcp.RelayConfig.isEnabled(applicationContext) && !com.inboxiq.app.mcp.RelayForegroundService.isRunning) {
            startForegroundService(android.content.Intent(this, com.inboxiq.app.mcp.RelayForegroundService::class.java))
        }

        setContent {
            val dark = isSystemInDarkTheme()
            val colorScheme = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && dark ->
                    dynamicDarkColorScheme(this)
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
                    dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    var isDefault by remember { mutableStateOf(DefaultSmsRole.isDefault(this)) }
                    var screen by screenState
                    // Sorted newest-first at the query level (MessageDao.observeThreadList ORDER BY timestamp DESC).
                    val threads by dao.observeThreadList().collectAsState(initial = emptyList())
                    val blockedAddresses by blockedDao.observeBlockedAddresses().collectAsState(initial = emptyList())

                    BackHandler(enabled = screen != Screen.ThreadList) { screen = Screen.ThreadList }

                    when (val current = screen) {
                        is Screen.ThreadList -> ThreadListScreen(
                            isDefaultHandler = isDefault,
                            threads = threads,
                            contactName = { address -> ContactResolver.displayNameFor(this, address) },
                            onRequestDefault = { requestRole.launch(DefaultSmsRole.requestIntent(this)) },
                            onOpenThread = { address -> screen = Screen.ThreadDetail(address) },
                            onOpenSettings = { screen = Screen.Settings },
                            onNewConversation = { screen = Screen.NewConversation },
                            onDeleteThread = { address ->
                                lifecycleScope.launch {
                                    com.inboxiq.app.sms.MmsImageStore.deleteAll(applicationContext, dao.imageUrisForThread(address))
                                    dao.deleteThread(address)
                                }
                            },
                            onBlockThread = { address ->
                                lifecycleScope.launch { blockedDao.block(com.inboxiq.app.data.BlockedNumberEntity(address, System.currentTimeMillis())) }
                            },
                            searchMessages = { query -> dao.searchMessages(query) },
                        )

                        is Screen.ThreadDetail -> {
                            // Sorted oldest-first at the query level (MessageDao.observeThread ORDER BY timestamp ASC).
                            val messages by dao.observeThread(current.address).collectAsState(initial = emptyList())
                            val displayName = remember(current.address) {
                                ContactResolver.displayNameFor(this, current.address)
                            }
                            val appContext = applicationContext
                            LaunchedEffect(current.address) {
                                AppDatabase.get(appContext).messageDao().markThreadRead(current.address)
                            }
                            ThreadDetailScreen(
                                address = current.address,
                                displayName = displayName,
                                messages = messages,
                                isBlocked = blockedAddresses.contains(current.address),
                                onBack = { screen = Screen.ThreadList },
                                onSend = { body, imageUri ->
                                    lifecycleScope.launch {
                                        if (imageUri != null) {
                                            MmsSender.send(applicationContext, current.address, body, imageUri)
                                        } else {
                                            SmsSender.send(applicationContext, current.address, body)
                                        }
                                    }
                                },
                                onPickImage = { callback ->
                                    onImagePicked = callback
                                    pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onDeleteConversation = {
                                    lifecycleScope.launch {
                                        com.inboxiq.app.sms.MmsImageStore.deleteAll(applicationContext, dao.imageUrisForThread(current.address))
                                        dao.deleteThread(current.address)
                                    }
                                    screen = Screen.ThreadList
                                },
                                onDeleteMessage = { id ->
                                    lifecycleScope.launch {
                                        dao.getById(id)?.let { com.inboxiq.app.sms.MmsImageStore.delete(applicationContext, it.imagePartUri) }
                                        dao.deleteMessage(id)
                                    }
                                },
                                onRetryNow = { id ->
                                    lifecycleScope.launch {
                                        // Dispatching (a true return) only means handed off, not delivered —
                                        // MmsStatusReceiver owns the real result and the "Fixed!" notification.
                                        val msg = dao.getById(id) ?: return@launch
                                        MmsSender.retry(applicationContext, msg)
                                    }
                                },
                                onToggleBlock = {
                                    lifecycleScope.launch {
                                        if (blockedAddresses.contains(current.address)) {
                                            blockedDao.unblock(current.address)
                                        } else {
                                            blockedDao.block(com.inboxiq.app.data.BlockedNumberEntity(current.address, System.currentTimeMillis()))
                                        }
                                    }
                                },
                            )
                        }

                        is Screen.Settings -> SettingsScreen(
                            selectedTier = remember { ClassifierTierPreference.get(this) },
                            onSelectTier = { tier -> ClassifierTierPreference.set(this, tier) },
                            onBack = { screen = Screen.ThreadList },
                        )

                        is Screen.NewConversation -> NewConversationScreen(
                            onBack = { screen = Screen.ThreadList },
                            onPickContact = { callback ->
                                onContactPicked = callback
                                pickContact.launch(null)
                            },
                            onStart = { address -> screen = Screen.ThreadDetail(address) },
                        )
                    }
                }
            }
        }

        runBackfillIfDefault()
    }

    private fun runBackfillIfDefault() {
        if (!DefaultSmsRole.isDefault(this)) return
        lifecycleScope.launch { SmsBackfill.run(applicationContext) }
    }
}

private fun resolvePickedContactNumber(context: android.content.Context, contactUri: Uri): String? {
    val raw = context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val contactId = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts._ID))
        val hasPhone = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER))
        if (hasPhone == 0) return@use null
        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        )?.use { phoneCursor ->
            if (phoneCursor.moveToFirst()) {
                phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER))
            } else {
                null
            }
        }
    }
    // Contacts entries are often saved without a country code. The platform's own SMS/MMS
    // provider always normalizes to E.164, so an un-normalized address here would silently
    // start a second, disconnected thread for the same person (confirmed live: this exact
    // gap split a real contact's history into a 19-message thread and a separate 2-message
    // one that only ever saw failed MMS sends).
    return raw?.let { com.inboxiq.app.sms.PhoneNumberNormalizer.normalize(context, it) }
}

private fun formatTimestamp(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "h:mm a" else "MMM d"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

@Composable
private fun LabelChip(label: com.inboxiq.app.data.MessageLabel) {
    val style = styleFor(label)
    Surface(color = style.container, shape = RoundedCornerShape(50), modifier = Modifier) {
        Text(
            text = style.displayName,
            color = style.content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private val urlPattern = android.util.Patterns.WEB_URL

/** Below this, the AI-generated-text signal is genuinely too noisy on SMS-length text to show — see MessageClassifier.kt. */
private const val AI_GENERATED_DISPLAY_THRESHOLD = 0.6f

// Detects RiddleVerse's existing /open-riddle share links (see sharing.routes.js)
// so an invite renders as a game card instead of a plain blue link.
private val riddleInvitePattern = Regex("""puzzleverseai\.com/open-riddle\?puzzleId=([\w-]+)""")

private fun extractRiddleInvite(body: String): Pair<String, String>? {
    val match = riddleInvitePattern.find(body) ?: return null
    val puzzleId = match.groupValues[1]
    return puzzleId to "https://${match.value}"
}

@Composable
private fun RiddleInviteCard(puzzleId: String, url: String, isMe: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp).width(220.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧩", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "RiddleVerse invite",
                    fontWeight = FontWeight.SemiBold,
                    color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You've been invited to solve a puzzle",
                style = MaterialTheme.typography.bodySmall,
                color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play now") }
        }
    }
}

@Composable
private fun LinkifiedText(text: String, color: androidx.compose.ui.graphics.Color) {
    val annotated = remember(text) {
        buildAnnotatedString {
            var lastEnd = 0
            val matcher = urlPattern.matcher(text)
            while (matcher.find()) {
                append(text.substring(lastEnd, matcher.start()))
                val url = matcher.group()
                val normalized = if (url.contains("://")) url else "https://$url"
                withLink(
                    LinkAnnotation.Url(
                        url = normalized,
                        styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline)),
                    ),
                ) {
                    append(url)
                }
                lastEnd = matcher.end()
            }
            append(text.substring(lastEnd))
        }
    }
    Text(annotated, color = color)
}

@Composable
private fun ContactAvatar(name: String?, address: String) {
    val initial = (name?.trim()?.firstOrNull() ?: address.trim().firstOrNull { it.isLetterOrDigit() } ?: '#')
        .uppercaseChar()
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial.toString(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadListScreen(
    isDefaultHandler: Boolean,
    threads: List<ThreadSummary>,
    contactName: (String) -> String?,
    onRequestDefault: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit = {},
    onDeleteThread: (String) -> Unit = {},
    onBlockThread: (String) -> Unit = {},
    searchMessages: (String) -> kotlinx.coroutines.flow.Flow<List<MessageEntity>> = { kotlinx.coroutines.flow.flowOf(emptyList()) },
) {
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFlow = remember(query) { if (query.isBlank()) null else searchMessages(query) }
    val searchResults by (searchFlow ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchActive) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Search messages") },
                        singleLine = true,
                    )
                    IconButton(onClick = { searchActive = false; query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close search")
                    }
                } else {
                    Text("InboxIQ", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            }
            if (!isDefaultHandler) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("InboxIQ needs to be your default SMS app to label messages.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onRequestDefault) { Text("Set as default SMS app") }
                    }
                }
            }

            if (searchActive && query.isNotBlank()) {
                LazyColumn {
                    items(searchResults, key = { it.id }) { message ->
                        val name = remember(message.address) { contactName(message.address) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenThread(message.address) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(name ?: message.address, fontWeight = FontWeight.Medium)
                            Text(
                                message.body,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                    if (searchResults.isEmpty()) {
                        item {
                            Text(
                                "No messages found",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn {
                    items(threads, key = { it.latestMessage.address }) { thread ->
                        val latest = thread.latestMessage
                        val name = remember(latest.address) { contactName(latest.address) }
                        val unread = thread.unreadCount > 0
                        val needsReply = QuickReplySuggester.needsResponse(latest.isIncoming, latest.label)
                        var menuOpen by remember { mutableStateOf(false) }

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onOpenThread(latest.address) },
                                        onLongClick = { menuOpen = true },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(name, latest.address)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = name ?: latest.address,
                                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = formatTimestamp(latest.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = latest.body,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        if (needsReply) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Reply,
                                                contentDescription = "Needs a reply",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp).padding(end = 4.dp),
                                            )
                                        }
                                        LabelChip(latest.label)
                                        if (unread) {
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                            )
                                        }
                                    }
                                }
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete conversation") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { menuOpen = false; onDeleteThread(latest.address) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Block number") },
                                    leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                                    onClick = { menuOpen = false; onBlockThread(latest.address) },
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onNewConversation,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "New message")
        }
    }
}

@Composable
fun NewConversationScreen(
    onBack: () -> Unit,
    onPickContact: ((String?) -> Unit) -> Unit,
    onStart: (String) -> Unit,
) {
    var number by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("New message", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Phone number") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = { onPickContact { picked -> if (picked != null) number = picked } }) {
                    Icon(Icons.Filled.Contacts, contentDescription = "Pick contact")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (number.isNotBlank()) onStart(number.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = number.isNotBlank(),
            ) { Text("Start conversation") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadDetailScreen(
    address: String,
    displayName: String?,
    messages: List<MessageEntity>,
    isBlocked: Boolean = false,
    onBack: () -> Unit,
    onSend: (body: String, imageUri: Uri?) -> Unit,
    onPickImage: ((Uri?) -> Unit) -> Unit = {},
    onDeleteConversation: () -> Unit = {},
    onDeleteMessage: (Long) -> Unit = {},
    onRetryNow: (Long) -> Unit = {},
    onToggleBlock: () -> Unit = {},
) {
    var body by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    // The label is a property of the whole thread (see README: classification
    // is per-sender, not per-message), so it's shown once in the header —
    // repeating it on every bubble was redundant noise.
    val threadLabel = messages.lastOrNull { it.isIncoming }?.label
    val lastMessage = messages.lastOrNull()
    val suggestions = remember(lastMessage?.id) {
        if (lastMessage != null &&
            QuickReplySuggester.needsResponse(lastMessage.isIncoming, threadLabel ?: com.inboxiq.app.data.MessageLabel.UNLABELED)
        ) {
            QuickReplySuggester.suggest(lastMessage.body)
        } else {
            emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                displayName ?: address,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            threadLabel?.let { LabelChip(it) }
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete conversation") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDeleteConversation() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isBlocked) "Unblock number" else "Block number") },
                        leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null) },
                        onClick = { menuOpen = false; onToggleBlock() },
                    )
                }
            }
        }
        HorizontalDivider()
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(messages, key = { it.id }) { message ->
                val isMe = !message.isIncoming
                var bubbleMenuOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                ) {
                    Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { bubbleMenuOpen = true },
                            ),
                        ) {
                        DropdownMenu(expanded = bubbleMenuOpen, onDismissRequest = { bubbleMenuOpen = false }) {
                            if (message.awaitingAutoHeal) {
                                DropdownMenuItem(
                                    text = { Text("Retry now") },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                    onClick = { bubbleMenuOpen = false; onRetryNow(message.id) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete message") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { bubbleMenuOpen = false; onDeleteMessage(message.id) },
                            )
                        }
                        val invite = remember(message.body) { extractRiddleInvite(message.body) }
                        if (invite != null) {
                            val (puzzleId, url) = invite
                            RiddleInviteCard(puzzleId = puzzleId, url = url, isMe = isMe)
                        } else {
                            Surface(
                                color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    if (message.body.isNotBlank()) {
                                        LinkifiedText(
                                            text = message.body,
                                            color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    message.imagePartUri?.let { uri -> MmsImage(uri) }
                                }
                            }
                        }
                        }
                        // Only shown above a confidence floor — this signal is genuinely weaker on
                        // SMS-length text than on long-form text, so a low score is noise, not a finding.
                        message.aiGeneratedConfidence?.takeIf { !isMe && it >= AI_GENERATED_DISPLAY_THRESHOLD }?.let { confidence ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                Text(
                                    text = "Possibly AI-generated · ${(confidence * 100).toInt()}% confidence",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        val gaveUp = message.sendStatus == SendStatus.FAILED && !message.awaitingAutoHeal && message.autoHealRetryCount > 0
                        val statusText = when {
                            !isMe -> ""
                            message.awaitingAutoHeal -> "  ·  Couldn't send — will retry automatically"
                            gaveUp -> "  ·  Couldn't be delivered"
                            else -> "  ·  ${message.sendStatus}"
                        }
                        Text(
                            text = formatTimestamp(message.timestamp) + statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.awaitingAutoHeal || gaveUp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { suggestion ->
                    AssistChip(
                        onClick = { body = suggestion },
                        label = { Text(suggestion) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        pendingImage?.let { uri ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    MmsImage(uri.toString(), modifier = Modifier.size(64.dp).padding(top = 4.dp))
                    IconButton(
                        onClick = { pendingImage = null },
                        modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove image", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val transcriber = remember { VoiceTranscriber.getInstance(context) }
        var isRecording by remember { mutableStateOf(false) }
        var isTranscribing by remember { mutableStateOf(false) }

        val requestMicPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                Toast.makeText(context, "Microphone permission is needed for voice memos", Toast.LENGTH_SHORT).show()
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPickImage { uri -> if (uri != null) pendingImage = uri } }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach image")
            }
            if (isTranscribing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        "Transcribing…",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Deliberately NOT an IconButton — IconButton installs its own internal
                // clickable gesture detector, which races/conflicts with a custom
                // detectTapGestures(onPress+tryAwaitRelease) on the same node and made
                // press-and-hold fire unreliably (confirmed live: taps produced no
                // response at all). A plain Box with only our own gesture detector fixes it.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (isRecording) MaterialTheme.colorScheme.errorContainer else androidx.compose.ui.graphics.Color.Transparent,
                            CircleShape,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (!transcriber.hasRecordPermission()) {
                                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        return@detectTapGestures
                                    }
                                    val started = transcriber.startRecording()
                                    if (!started) {
                                        Toast.makeText(context, "Couldn't start recording", Toast.LENGTH_SHORT).show()
                                        return@detectTapGestures
                                    }
                                    isRecording = true
                                    val released = tryAwaitRelease()
                                    isRecording = false
                                    if (released) {
                                        isTranscribing = true
                                        coroutineScope.launch {
                                            when (val result = transcriber.stopAndTranscribe()) {
                                                is VoiceTranscriber.Result.Success -> {
                                                    body = if (body.isBlank()) result.text else "$body ${result.text}"
                                                }
                                                is VoiceTranscriber.Result.Failure -> {
                                                    Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            isTranscribing = false
                                        }
                                    } else {
                                        transcriber.cancelRecording()
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = if (isRecording) "Recording — release to transcribe" else "Hold to record voice memo",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
                    )
                }
                Text(
                    if (isRecording) "Recording…" else "Hold",
                    fontSize = 9.sp,
                    color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Button(onClick = {
                if (body.isNotBlank() || pendingImage != null) {
                    onSend(body, pendingImage)
                    body = ""
                    pendingImage = null
                }
            }) { Text("Send") }
        }
    }
}

@Composable
private fun MmsImage(partUri: String, modifier: Modifier = Modifier.size(200.dp).padding(top = 4.dp)) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(partUri) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(partUri))?.use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "MMS attachment",
            modifier = modifier,
        )
    } else {
        Text("[MMS image — couldn't load]")
    }
}

@Composable
fun SettingsScreen(
    selectedTier: ClassifierTier,
    onSelectTier: (ClassifierTier) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf(selectedTier) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var modelState by remember {
        mutableStateOf<com.inboxiq.app.classify.ModelDownloadState>(
            if (com.inboxiq.app.classify.GemmaModelStore.isReady(context)) {
                com.inboxiq.app.classify.ModelDownloadState.Ready
            } else {
                com.inboxiq.app.classify.ModelDownloadState.NotDownloaded
            },
        )
    }
    LaunchedEffect(Unit) {
        com.inboxiq.app.classify.GemmaModelStore.observeDownloadState(context).collect { modelState = it }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Classifier tier", style = MaterialTheme.typography.titleSmall)
            ClassifierTier.entries.forEach { tier ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = tier.available) {
                            current = tier
                            onSelectTier(tier)
                            if (tier == ClassifierTier.MID && modelState !is com.inboxiq.app.classify.ModelDownloadState.Ready) {
                                com.inboxiq.app.classify.GemmaModelStore.requestDownload(context)
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == tier, onClick = null, enabled = tier.available)
                    Text(tier.label, modifier = Modifier.padding(start = 8.dp))
                }
                if (tier == ClassifierTier.MID && current == ClassifierTier.MID) {
                    val hint = when (val s = modelState) {
                        is com.inboxiq.app.classify.ModelDownloadState.Downloading -> {
                            val pct = if (s.totalBytes > 0) (100 * s.bytesDownloaded / s.totalBytes) else 0
                            "Downloading model… $pct%"
                        }
                        is com.inboxiq.app.classify.ModelDownloadState.WaitingForWifi -> "Waiting for Wi-Fi to download the model"
                        is com.inboxiq.app.classify.ModelDownloadState.RequiresConfirmation -> "Tap to confirm the model download"
                        is com.inboxiq.app.classify.ModelDownloadState.Failed -> "Download failed — tap the tier again to retry"
                        is com.inboxiq.app.classify.ModelDownloadState.Ready -> "Model ready — using Gemma 3 270M for this tier"
                        is com.inboxiq.app.classify.ModelDownloadState.NotDownloaded -> "Starting download…"
                    }
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 40.dp, bottom = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            com.inboxiq.app.mcp.CloudConnectSection()
            com.inboxiq.app.mcp.AgentDraftsSection()
        }
    }
}

@Preview
@Composable
fun ThreadListScreenPreview() {
    ThreadListScreen(
        isDefaultHandler = false,
        threads = emptyList(),
        contactName = { null },
        onRequestDefault = {},
        onOpenThread = {},
        onOpenSettings = {},
    )
}
