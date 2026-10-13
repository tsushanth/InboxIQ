package com.inboxiq.app.classify

/**
 * Outgoing messages that are an opt-out or a button press ("STOP", "Y", "1"), not a conversation.
 * They do not count as the user having replied to a sender (see ThreadLabelDao.substantiveReplies,
 * which uses the same list in SQL).
 */
object BareReplies {
    const val SQL_LIST = "'STOP','STOPALL','UNSUBSCRIBE','CANCEL','END','QUIT','YES','Y','NO','N','HELP','C','R','1','2','3'"
    private val set = SQL_LIST.split(',').map { it.trim('\'') }.toSet()

    fun isBare(body: String): Boolean = body.trim().uppercase() in set
}
