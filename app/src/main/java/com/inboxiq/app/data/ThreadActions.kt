package com.inboxiq.app.data

/**
 * Everything the user can do to a whole conversation, in one place so the list, the thread
 * screen and notification actions behave identically (and so it can be unit-tested without UI).
 *
 * Deleting is two-phase for undo: [delete] removes the rows but hands back a [DeletedThread]
 * snapshot, and the attachment files are only removed by [commitDelete] once the undo window
 * has passed. [restore] puts everything back with the original message ids.
 */
class ThreadActions(
    private val db: AppDatabase,
    private val deleteImageFiles: (List<String>) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Remembers deletions so the next history import does not bring them back (see ImportTombstones). */
    private val tombstones: com.inboxiq.app.sms.ImportTombstones? = null,
) {
    data class DeletedThread(
        val address: String,
        val messages: List<MessageEntity>,
        val state: ThreadStateEntity?,
    ) {
        val imageUris: List<String> get() = messages.mapNotNull { it.imagePartUri }
    }

    private val messages get() = db.messageDao()
    private val states get() = db.threadStateDao()

    suspend fun setPinned(address: String, pinned: Boolean) = states.setPinned(address, pinned, clock())
    suspend fun setArchived(address: String, archived: Boolean) = states.setArchived(address, archived)
    suspend fun setMutedUntil(address: String, until: Long?) = states.setMutedUntil(address, until)
    suspend fun markRead(address: String) = messages.markThreadRead(address)
    suspend fun markAllRead() = messages.markAllRead()

    /** Saves a draft without creating state rows for conversations that never had one. */
    suspend fun saveDraft(address: String, text: String) {
        if (text.isBlank() && states.get(address) == null) return
        states.setDraft(address, text)
    }

    /** Deletes one message but returns it, so it can be put back while the undo bar is showing. */
    suspend fun deleteMessage(id: Long): MessageEntity? {
        val m = messages.getById(id) ?: return null
        messages.deleteMessage(id)
        tombstones?.markMessageDeleted(m.address, m.timestamp, m.body)
        return m
    }

    suspend fun restoreMessage(m: MessageEntity) {
        messages.restoreAll(listOf(m))
        tombstones?.clearMessage(m.address, m.timestamp, m.body)
    }
    fun commitMessageDelete(m: MessageEntity) = deleteImageFiles(listOfNotNull(m.imagePartUri))

    suspend fun delete(address: String): DeletedThread {
        val snapshot = DeletedThread(address, messages.messagesForThread(address), states.get(address))
        messages.deleteThread(address)
        states.delete(address)
        snapshot.messages.maxOfOrNull { it.timestamp }?.let { tombstones?.markThreadDeleted(address, it) }
        return snapshot
    }

    suspend fun restore(deleted: DeletedThread) {
        messages.restoreAll(deleted.messages)
        deleted.state?.let { states.ensure(it) }
        tombstones?.clearThread(deleted.address)
    }

    /** Call when the undo window closes without an undo: only now are attachment files removed. */
    fun commitDelete(deleted: DeletedThread) = deleteImageFiles(deleted.imageUris)

    /**
     * Files a conversation under a label the user chose. Future messages from this number
     * inherit it, and automatic heuristics no longer override it (see SmsDeliverReceiver).
     * The on-device model itself is not retrained.
     */
    suspend fun correctLabel(address: String, label: MessageLabel) {
        db.threadLabelDao().upsert(ThreadLabelEntity(address, label, 1f, clock()))
        messages.applyThreadLabel(address, label, 1f)
        states.setLabelCorrected(address, true)
    }

    /** Spam report: refile as spam and, optionally, block so nothing further is saved from this number. */
    suspend fun reportSpam(address: String, alsoBlock: Boolean) {
        correctLabel(address, MessageLabel.SPAM)
        if (alsoBlock) db.blockedNumberDao().block(BlockedNumberEntity(address, clock()))
    }

    suspend fun setBlocked(address: String, blocked: Boolean) {
        if (blocked) db.blockedNumberDao().block(BlockedNumberEntity(address, clock()))
        else db.blockedNumberDao().unblock(address)
    }
}
