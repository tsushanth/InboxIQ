package com.inboxiq.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inboxiq.app.R
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.BackupFormatException
import com.inboxiq.app.data.BackupManager
import com.inboxiq.app.inbox.MuteDuration
import com.inboxiq.app.sms.ScheduledSend
import com.inboxiq.app.sms.SendPreferences
import com.inboxiq.app.sms.SmsSender
import kotlinx.coroutines.delay
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.ThreadActions
import com.inboxiq.app.inbox.InboxLogic
import com.inboxiq.app.inbox.SearchLogic
import com.inboxiq.app.inbox.ThreadRow
import com.inboxiq.app.sms.MmsImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Inbox : Screen
    data class Thread(val address: String) : Screen
    data object Settings : Screen
    data object Blocked : Screen
    data object AutoReply : Screen
    data object AutoReplyLog : Screen
    data class NewConversation(val forwardBody: String? = null) : Screen
    data class Media(val address: String) : Screen
    data class Viewer(val address: String, val startMessageId: Long) : Screen
    data class Onboarding(val firstRun: Boolean) : Screen
    data object SetupLog : Screen
}

/** Data for the inbox: joined rows, resolved contact names, blocked numbers, search. */
class InboxViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val actions = ThreadActions(db, { MmsImageStore.deleteAll(app, it) }, tombstones = com.inboxiq.app.sms.ImportTombstones(app),
        readSync = com.inboxiq.app.sms.ProviderReadSync.create(app))

    /** null until the first database emission, so the screen can show a loading state instead of "empty". */
    val rows: StateFlow<List<ThreadRow>?> = combine(
        db.messageDao().observeThreadList(),
        db.threadStateDao().observeAll(),
    ) { threads, states -> InboxLogic.join(threads, states) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val blocked: StateFlow<List<String>> = db.blockedNumberDao().observeBlockedAddresses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _names = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** address -> saved contact name (null when none). Resolved off the main thread, once per address. */
    val names: StateFlow<Map<String, String?>> = _names

    init {
        viewModelScope.launch {
            rows.collect { list ->
                val missing = list.orEmpty().map { it.address }.filter { it !in _names.value }
                if (missing.isNotEmpty()) {
                    val resolved = withContext(Dispatchers.IO) {
                        missing.associateWith { ContactResolver.displayNameFor(getApplication(), it) }
                    }
                    _names.value = _names.value + resolved
                }
            }
        }
    }

    fun search(query: String): Flow<List<MessageEntity>> =
        if (query.isBlank()) flowOf(emptyList()) else db.messageDao().searchMessagesLiteral(SearchLogic.escapeLike(query.trim()))

    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun threadState(address: String) = db.threadStateDao().observe(address)
    fun threadMedia(address: String) = db.messageDao().observeThreadMedia(address)
    fun thread(address: String) = db.messageDao().observeThread(address)
    fun scheduled(address: String) = db.scheduledMessageDao().observeForThread(address)

    // ---------------- navigation + one-off UI state (survives rotation) ----------------

    var screen: Screen by mutableStateOf(Screen.Inbox)
    val snackbar = SnackbarHostState()
    var backupStatus: String? by mutableStateOf(null)

    /** Application context for text shown from here (snackbars, backup status); the screens hold no Activity reference. */
    private val app: Application get() = getApplication()
    private val undo: String get() = app.getString(R.string.common_undo)

    /** Recent texts the user sent, to learn their phrasing for reply chips. Read-only, derived on the fly, never stored. */
    suspend fun recentSent(): List<String> = db.replyHistoryDao().recentSentBodies(300)
    suspend fun bodyOf(id: Long): String? = db.messageDao().getById(id)?.body

    suspend fun draftFor(address: String): String = db.threadStateDao().get(address)?.draft.orEmpty()
    val blockedEntries = db.blockedNumberDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------- actions with undo ----------------

    fun deleteThreads(addresses: Set<String>) {
        viewModelScope.launch {
            val snapshots = addresses.map { actions.delete(it) }
            val text = app.resources.getQuantityString(R.plurals.snack_conversations_deleted, snapshots.size, snapshots.size)
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) snapshots.forEach { actions.restore(it) }
            else snapshots.forEach { actions.commitDelete(it) }
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            val removed = actions.deleteMessage(id) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(app.getString(R.string.snack_message_deleted), actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) actions.restoreMessage(removed) else actions.commitMessageDelete(removed)
        }
    }

    fun archive(addresses: Set<String>, archived: Boolean) {
        viewModelScope.launch {
            addresses.forEach { actions.setArchived(it, archived) }
            snackbar.currentSnackbarData?.dismiss()
            val text = app.resources.getQuantityString(if (archived) R.plurals.snack_conversations_archived else R.plurals.snack_conversations_unarchived, addresses.size, addresses.size)
            val result = snackbar.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) addresses.forEach { actions.setArchived(it, !archived) }
        }
    }

    /** Pin or unpin, and say so (a snackbar is announced by screen readers; the pin icon alone is not a confirmation). */
    fun pin(addresses: Set<String>, pinned: Boolean) {
        viewModelScope.launch {
            addresses.forEach { actions.setPinned(it, pinned) }
            snackbar.currentSnackbarData?.dismiss()
            val text = app.resources.getQuantityString(if (pinned) R.plurals.snack_conversations_pinned else R.plurals.snack_conversations_unpinned, addresses.size, addresses.size)
            val result = snackbar.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) addresses.forEach { actions.setPinned(it, !pinned) }
        }
    }

    fun mute(addresses: Set<String>, choice: MuteDuration?) {
        viewModelScope.launch {
            val until = choice?.until(System.currentTimeMillis())
            addresses.forEach { actions.setMutedUntil(it, until) }
        }
    }

    /** Sends a text now, or after the user's undo window. Pictures always go straight out. */
    fun sendText(address: String, body: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val secs = SendPreferences.undoSeconds(app)
            if (secs <= 0) { SmsSender.send(app, address, body); return@launch }
            val id = ScheduledSend.schedule(app, address, body, System.currentTimeMillis() + secs * 1_000L, isUndoWindow = true)
            delay(secs * 1_000L)
            ScheduledSend.dispatch(app, id)
        }
    }

    fun cancelScheduled(id: Long, restoreInto: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val row = db.scheduledMessageDao().get(id)
            if (ScheduledSend.cancel(getApplication(), id) && row != null) restoreInto?.invoke(row.body)
        }
    }

    fun schedule(address: String, body: String, at: Long) {
        viewModelScope.launch { ScheduledSend.schedule(getApplication(), address, body, at, isUndoWindow = false) }
    }

    // ---------------- backup ----------------

    fun exportTo(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            backupStatus = app.getString(R.string.backup_saving)
            backupStatus = try {
                val n = withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { BackupManager(db).export(it) }
                }
                app.resources.getQuantityString(R.plurals.backup_saved, n, n)
            } catch (e: Exception) {
                app.getString(R.string.backup_save_failed)
            }
        }
    }

    fun importFrom(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            backupStatus = app.getString(R.string.backup_restoring)
            backupStatus = try {
                val r = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)!!.use { BackupManager(db).import(it) }
                }
                if (r.alreadyPresent > 0) app.resources.getQuantityString(R.plurals.backup_restored_with_existing, r.added, r.added, r.alreadyPresent)
                else app.resources.getQuantityString(R.plurals.backup_restored, r.added, r.added)
            } catch (e: BackupFormatException) {
                app.getString(e.messageRes)
            } catch (e: Exception) {
                app.getString(R.string.backup_read_failed)
            }
            // The text under the Restore button can be off-screen: also say it where every screen shows it.
            backupStatus?.let {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            }
        }
    }
}
