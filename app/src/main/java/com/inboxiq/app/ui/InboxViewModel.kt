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
            val text = if (snapshots.size == 1) "Conversation deleted" else "${snapshots.size} conversations deleted"
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) snapshots.forEach { actions.restore(it) }
            else snapshots.forEach { actions.commitDelete(it) }
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            val removed = actions.deleteMessage(id) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Message deleted", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) actions.restoreMessage(removed) else actions.commitMessageDelete(removed)
        }
    }

    fun archive(addresses: Set<String>, archived: Boolean) {
        viewModelScope.launch {
            addresses.forEach { actions.setArchived(it, archived) }
            snackbar.currentSnackbarData?.dismiss()
            val text = (if (addresses.size == 1) "Conversation" else "${addresses.size} conversations") + if (archived) " archived" else " moved to inbox"
            val result = snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) addresses.forEach { actions.setArchived(it, !archived) }
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
            backupStatus = "Saving backup"
            backupStatus = try {
                val n = withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { BackupManager(db).export(it) }
                }
                "Saved $n messages."
            } catch (e: Exception) {
                "Couldn't save the backup. Check there is free space and try a different location."
            }
        }
    }

    fun importFrom(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            backupStatus = "Restoring"
            backupStatus = try {
                val r = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)!!.use { BackupManager(db).import(it) }
                }
                "Restored ${r.added} messages" + if (r.alreadyPresent > 0) " (${r.alreadyPresent} were already here)." else "."
            } catch (e: BackupFormatException) {
                e.message
            } catch (e: Exception) {
                "Couldn't read that file. Choose an InboxIQ backup file."
            }
        }
    }
}
