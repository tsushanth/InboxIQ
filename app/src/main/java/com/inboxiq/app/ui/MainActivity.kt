package com.inboxiq.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.inboxiq.app.classify.ClassifierTierPreference
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.sms.DefaultSmsRole
import com.inboxiq.app.sms.MmsSender
import com.inboxiq.app.sms.ScheduledSend
import com.inboxiq.app.sms.SendPreferences
import com.inboxiq.app.sms.ImportStatusHolder
import com.inboxiq.app.sms.ImportTrigger
import com.inboxiq.app.sms.RoleWatcher
import com.inboxiq.app.autoreply.AutoReplyConfig
import com.inboxiq.app.autoreply.AutoReplyLogEntry
import com.inboxiq.app.autoreply.AutoReplyStore
import com.inboxiq.app.autoreply.AutoResponder
import com.inboxiq.app.ui.theme.InboxTheme
import com.inboxiq.app.ui.theme.ThemePreferences
import com.inboxiq.app.ui.theme.ThemeSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ADDRESS = "com.inboxiq.app.EXTRA_THREAD_ADDRESS"
        private const val PREF_ONBOARDED = "onboarding_done"
        private const val ROLE_POLL_MS = 3_000L
    }

    private val vm: InboxViewModel by viewModels()
    private var isDefault by mutableStateOf(false)
    private var theme by mutableStateOf(ThemeSettings())
    private var undoSeconds by mutableStateOf(0)

    private val requestRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshDefault()
        if (isDefault) {
            markOnboarded()
            if (vm.screen is Screen.Onboarding) vm.screen = Screen.Inbox
        }
        roleWatcher.check(force = true)
    }

    private var onContactPicked: ((String?) -> Unit)? = null
    private val pickContact = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        onContactPicked?.invoke(uri?.let { resolvePickedContactNumber(this, it) })
    }

    private var onImagePicked: ((Uri?) -> Unit)? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> onImagePicked?.invoke(uri) }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportTo) }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importFrom) }

    private fun addressFromIntent(intent: Intent?): String? {
        intent?.getStringExtra(EXTRA_THREAD_ADDRESS)?.let { return it }
        return intent?.data?.takeIf { it.scheme == "inboxiq" }?.lastPathSegment
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        addressFromIntent(intent)?.let { vm.screen = Screen.Thread(it) }
    }

    private var autoCfg by mutableStateOf(AutoReplyConfig())
    private var autoLog by mutableStateOf(emptyList<AutoReplyLogEntry>())
    private fun autoStore() = AutoReplyStore(this)
    private fun refreshAuto() { val s = autoStore(); autoCfg = s.config(); autoLog = s.log() }

    private fun refreshDefault() { isDefault = DefaultSmsRole.isDefault(this) || com.inboxiq.app.demo.DemoHooks.shownAsDefault(this) }

    /** Starts the history import whenever we are the default SMS app, however that came about (see ImportTrigger). */
    private val roleWatcher = RoleWatcher(
        isDefault = { DefaultSmsRole.isDefault(this) },
        onDefault = { ImportTrigger.ensure(applicationContext) },
    )
    private fun prefs() = getSharedPreferences("inboxiq_prefs", MODE_PRIVATE)
    private fun markOnboarded() = prefs().edit().putBoolean(PREF_ONBOARDED, true).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        theme = ThemePreferences.get(this)
        refreshAuto()
        undoSeconds = SendPreferences.undoSeconds(this)
        refreshDefault()
        if (savedInstanceState == null) {
            // Existing users already chose InboxIQ as default; only brand-new installs see the introduction.
            if (isDefault) markOnboarded()
            if (!prefs().getBoolean(PREF_ONBOARDED, false)) vm.screen = Screen.Onboarding(firstRun = true)
            addressFromIntent(intent)?.let { vm.screen = Screen.Thread(it) }
        }

        lifecycleScope.launch { ScheduledSend.reenqueueAll(applicationContext) }

        lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refreshDefault()
            // Back from Settings or a system dialog: the default may have changed while we were away.
            if (e == Lifecycle.Event.ON_START) roleWatcher.check(force = true)
        })
        // The role can also change under us while visible (split-screen, a dialog over the app).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(ROLE_POLL_MS)
                    refreshDefault()
                    roleWatcher.check(force = false)
                }
            }
        }

        setContent {
            InboxTheme(theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        val screen = vm.screen
        val rows by vm.rows.collectAsState()
        val names by vm.names.collectAsState()
        val blocked by vm.blocked.collectAsState()
        val importStatus by ImportStatusHolder.status.collectAsState()

        BackHandler(enabled = screen != Screen.Inbox) {
            vm.screen = when (screen) {
                is Screen.Viewer -> Screen.Media(screen.address)
                is Screen.Media -> Screen.Thread(screen.address)
                is Screen.Blocked -> Screen.Settings
                is Screen.AutoReply -> Screen.Settings
                is Screen.AutoReplyLog -> Screen.AutoReply
                is Screen.Onboarding -> if (screen.firstRun) Screen.Inbox else Screen.Settings
                else -> Screen.Inbox
            }
        }

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val twoPane = maxWidth >= 840.dp
                val inboxCallbacks = remember { inboxCallbacks() }

                when {
                    screen is Screen.Inbox || (screen is Screen.Thread && twoPane) -> {
                        val activeAddress = (screen as? Screen.Thread)?.address
                        if (twoPane) {
                            Row(Modifier.fillMaxSize()) {
                                InboxScreen(
                                    rows = rows, names = names, isDefaultHandler = isDefault, callbacks = inboxCallbacks,
                                    search = vm::search, activeAddress = activeAddress, twoPane = true, importStatus = importStatus,
                                    modifier = Modifier.width(380.dp).fillMaxHeight(),
                                )
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    if (activeAddress != null) ThreadHost(activeAddress, showBack = false)
                                    else EmptyDetailPane()
                                }
                            }
                        } else {
                            InboxScreen(rows, names, isDefault, inboxCallbacks, search = vm::search, importStatus = importStatus)
                        }
                    }
                    screen is Screen.Thread -> ThreadHost(screen.address, showBack = true)
                    screen is Screen.Settings -> SettingsScreen(
                        selectedTier = remember { ClassifierTierPreference.get(this@MainActivity) },
                        theme = theme,
                        undoSeconds = undoSeconds,
                        blockedCount = blocked.size,
                        backupStatus = vm.backupStatus,
                        autoReplySummary = if (AutoResponder.buildEnabled()) (if (autoCfg.enabled) "On" else "Off") else null,
                        callbacks = SettingsCallbacks(
                            onOpenAutoReply = { refreshAuto(); vm.screen = Screen.AutoReply },
                            onBack = { vm.screen = Screen.Inbox },
                            onSelectTier = { ClassifierTierPreference.set(this@MainActivity, it) },
                            onTheme = { theme = it; ThemePreferences.set(this@MainActivity, it) },
                            onUndoSeconds = { undoSeconds = it; SendPreferences.setUndoSeconds(this@MainActivity, it) },
                            onOpenBlocked = { vm.screen = Screen.Blocked },
                            onExport = { createBackup.launch("inboxiq-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json") },
                            onImport = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            onShowIntro = { vm.screen = Screen.Onboarding(firstRun = false) },
                        ),
                    )
                    screen is Screen.AutoReply && AutoResponder.buildEnabled() -> AutoReplyScreen(
                        config = autoCfg, isDefaultSms = isDefault, sentCount = autoLog.size,
                        callbacks = AutoReplyCallbacks(
                            onBack = { vm.screen = Screen.Settings },
                            onSave = { autoStore().save(it); refreshAuto() },
                            onEnable = { text -> autoStore().enable(text); refreshAuto() },
                            onDisable = { autoStore().disable(); refreshAuto() },
                            onOpenLog = { refreshAuto(); vm.screen = Screen.AutoReplyLog },
                        ),
                    )
                    screen is Screen.AutoReplyLog && AutoResponder.buildEnabled() -> AutoReplyLogScreen(
                        entries = autoLog, enabled = autoCfg.enabled, nameOf = { names[it] }, bodyOf = { vm.bodyOf(it) },
                        onTurnOff = { autoStore().disable(); refreshAuto() },
                        onClear = { autoStore().clearLog(); refreshAuto() },
                        onBack = { vm.screen = Screen.AutoReply },
                    )
                    screen is Screen.Blocked -> {
                        val entries by vm.blockedEntries.collectAsState()
                        BlockedNumbersScreen(entries, { names[it] }, onUnblock = { a -> vm.launch { vm.actions.setBlocked(a, false) } }, onBack = { vm.screen = Screen.Settings })
                    }
                    screen is Screen.NewConversation -> NewConversationScreen(
                        onBack = { vm.screen = Screen.Inbox },
                        onPickContact = { callback -> onContactPicked = callback; pickContact.launch(null) },
                        onStart = { address ->
                            val fwd = screen.forwardBody
                            vm.launch {
                                if (fwd != null) vm.actions.saveDraft(address, fwd)
                                vm.screen = Screen.Thread(address)
                            }
                        },
                        forwardingText = screen.forwardBody,
                    )
                    screen is Screen.Media -> {
                        val media by vm.threadMedia(screen.address).collectAsState(initial = emptyList())
                        MediaGalleryScreen(
                            title = names[screen.address] ?: screen.address, images = media,
                            onBack = { vm.screen = Screen.Thread(screen.address) },
                            onOpen = { i -> vm.screen = Screen.Viewer(screen.address, media[i].id) },
                        )
                    }
                    screen is Screen.Viewer -> {
                        val media by vm.threadMedia(screen.address).collectAsState(initial = emptyList())
                        val start = media.indexOfFirst { it.id == screen.startMessageId }.coerceAtLeast(0)
                        if (media.isNotEmpty()) key(screen.startMessageId) {
                            ImageViewerScreen(media, start, onClose = { vm.screen = Screen.Media(screen.address) })
                        }
                    }
                    screen is Screen.Onboarding -> OnboardingScreen(
                        isDefault = isDefault,
                        firstRun = screen.firstRun,
                        onChooseDefault = { requestRole.launch(DefaultSmsRole.requestIntent(this@MainActivity)) },
                        onSkip = {
                            markOnboarded()
                            vm.screen = if (screen.firstRun) Screen.Inbox else Screen.Settings
                        },
                    )
                }

                SnackbarHost(
                    vm.snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp),
                )
            }
        }
    }

    @Composable
    private fun EmptyDetailPane() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            StateMessage(Icons.Filled.Forum, "Pick a conversation", "Choose one on the left to read it here.")
        }
    }

    private fun inboxCallbacks() = InboxCallbacks(
        onOpenThread = { vm.screen = Screen.Thread(it) },
        onOpenSettings = { vm.screen = Screen.Settings },
        onNewConversation = { vm.screen = Screen.NewConversation() },
        onRequestDefault = { requestRole.launch(DefaultSmsRole.requestIntent(this)) },
        onRetryImport = { ImportTrigger.retry(applicationContext) },
        onPin = { addrs, pin -> vm.launch { addrs.forEach { vm.actions.setPinned(it, pin) } } },
        onArchive = { addrs, archived -> vm.archive(addrs, archived) },
        onMute = { addrs, choice -> vm.mute(addrs, choice) },
        onMarkRead = { addrs -> vm.launch { addrs.forEach { vm.actions.markRead(it) } } },
        onMarkAllRead = { vm.launch { vm.actions.markAllRead() } },
        onDelete = { addrs -> vm.deleteThreads(addrs) },
        onBlock = { addrs -> vm.launch { addrs.forEach { vm.actions.setBlocked(it, true) } } },
        onCorrectLabel = { a, l -> vm.launch { vm.actions.correctLabel(a, l) } },
        onReportSpam = { a, block -> vm.launch { vm.actions.reportSpam(a, block) } },
    )

    @Composable
    private fun ThreadHost(address: String, showBack: Boolean) = key(address) {
        val names by vm.names.collectAsState()
        val messages by vm.thread(address).collectAsState(initial = emptyList())
        val state by vm.threadState(address).collectAsState(initial = null)
        val scheduled by vm.scheduled(address).collectAsState(initial = emptyList())
        val blocked by vm.blocked.collectAsState()
        val draft by produceState<String?>(null, address) { value = vm.draftFor(address) }
        val displayName = names[address]
        val recentSent by produceState(emptyList<String>(), address) { value = runCatching { vm.recentSent() }.getOrDefault(emptyList()) }
        val autoIds = remember(messages.size, autoLog) { if (AutoResponder.buildEnabled()) autoStore().sentIds() else emptySet() }

        LaunchedEffect(address, messages.count { !it.isRead }) {
            vm.actions.markRead(address)
            com.inboxiq.app.sms.MessageNotifier.cancel(applicationContext, address)
        }

        val cb = remember(address) {
            ThreadCallbacks(
                onBack = { vm.screen = Screen.Inbox },
                onSend = { body, imageUri ->
                    if (imageUri != null) lifecycleScope.launch { MmsSender.send(applicationContext, address, body, imageUri) }
                    else vm.sendText(address, body)
                },
                onPickImage = { callback -> onImagePicked = callback; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onDeleteConversation = { vm.screen = Screen.Inbox; vm.deleteThreads(setOf(address)) },
                onDeleteMessage = { vm.deleteMessage(it) },
                onRetryNow = { id -> vm.launch { retryNow(id) } },
                onToggleBlock = { vm.launch { vm.actions.setBlocked(address, address !in vm.blocked.value) } },
                onPin = { pin -> vm.launch { vm.actions.setPinned(address, pin) } },
                onArchive = { archived -> vm.archive(setOf(address), archived); if (archived) vm.screen = Screen.Inbox },
                onMute = { vm.mute(setOf(address), it) },
                onCorrectLabel = { l -> vm.launch { vm.actions.correctLabel(address, l) } },
                onReportSpam = { block -> vm.launch { vm.actions.reportSpam(address, block) } },
                onDraftChange = { text -> vm.launch { vm.actions.saveDraft(address, text) } },
                onForward = { body -> vm.screen = Screen.NewConversation(forwardBody = body) },
                onOpenMedia = { vm.screen = Screen.Media(address) },
                onOpenImage = { id -> vm.screen = Screen.Viewer(address, id) },
                onSchedule = { body, at -> vm.schedule(address, body, at) },
                onUndoSend = { id -> vm.cancelScheduled(id) },
                onCancelScheduled = { id -> vm.cancelScheduled(id) },
            )
        }
        val loadedDraft = draft
        if (loadedDraft != null) {
            ThreadScreen(
                address = address,
                displayName = displayName,
                messages = messages,
                callbacks = cb,
                state = state,
                scheduled = scheduled,
                isBlocked = address in blocked,
                labelConfidence = messages.lastOrNull { it.isIncoming }?.labelConfidence ?: 0f,
                showBack = showBack,
                initialDraft = loadedDraft,
                recentSent = recentSent,
                isKnownContact = displayName != null,
                autoSentIds = autoIds,
            )
        }
    }

    private suspend fun retryNow(id: Long) {
        val dao = com.inboxiq.app.data.AppDatabase.get(applicationContext).messageDao()
        // Dispatching only means handed off, not delivered; MmsStatusReceiver owns the real result.
        val msg = dao.getById(id) ?: return
        MmsSender.retry(applicationContext, msg)
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
            if (phoneCursor.moveToFirst()) phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)) else null
        }
    }
    // Contacts entries are often saved without a country code, but the platform's SMS/MMS provider always
    // normalizes to E.164. An un-normalized address would silently start a second, disconnected thread
    // for the same person (confirmed live), so normalize here.
    return raw?.let { com.inboxiq.app.sms.PhoneNumberNormalizer.normalize(context, it) }
}
