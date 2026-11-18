package com.inboxiq.app.data

/**
 * Sets, replaces and clears the user's reaction to a message. One reaction per message: choosing a
 * different emoji replaces it, choosing the same emoji again removes it.
 *
 * This only records the choice on the phone. Whether a reaction also goes out as a text message is
 * decided in sms/ReactionSender, after the choice is saved here.
 */
class ReactionRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Change {
        /** [previous] is the emoji this replaced, null when the message had no reaction. */
        data class Set(val emoji: String, val previous: String?) : Change()
        data class Cleared(val previous: String, val wasSentAsText: Boolean) : Change()
    }

    private val dao get() = db.reactionDao()

    private fun hashes(m: MessageEntity) = listOf(ReactionKey.bodyHash(m), "").distinct()

    suspend fun current(m: MessageEntity): ReactionEntity? =
        dao.find(m.address, m.timestamp, m.isIncoming, hashes(m)).firstOrNull()

    /** Same emoji as the current reaction removes it; any other emoji sets or replaces it. */
    suspend fun toggle(m: MessageEntity, emoji: String): Change {
        val existing = current(m)
        return if (existing != null && existing.emoji == emoji) clear(m)!! else set(m, emoji)
    }

    suspend fun set(m: MessageEntity, emoji: String): Change.Set {
        val previous = current(m)
        // Drop any older row filed under the empty (placeholder) digest so a message never carries two.
        dao.delete(m.address, m.timestamp, m.isIncoming, hashes(m))
        dao.upsert(ReactionKey.forMessage(m, emoji, clock()))
        return Change.Set(emoji, previous?.emoji)
    }

    suspend fun clear(m: MessageEntity): Change.Cleared? {
        val existing = current(m) ?: return null
        dao.delete(m.address, m.timestamp, m.isIncoming, hashes(m))
        return Change.Cleared(existing.emoji, existing.sentAsText)
    }

    /** Puts a removed reaction back exactly as it was (the snackbar Undo). */
    suspend fun restore(m: MessageEntity, reaction: ReactionEntity) {
        dao.delete(m.address, m.timestamp, m.isIncoming, hashes(m))
        dao.upsert(reaction)
    }

    suspend fun markSentAsText(m: MessageEntity) {
        current(m)?.let { dao.markSentAsText(it.address, it.timestamp, it.isIncoming, it.bodyHash) }
    }

    /** Removes reactions whose message is gone. Safe to run at startup, when no undo window can be open. */
    suspend fun sweepOrphans(): Int = dao.deleteOrphans()
}
