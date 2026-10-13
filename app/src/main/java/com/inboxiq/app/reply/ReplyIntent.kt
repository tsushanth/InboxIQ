package com.inboxiq.app.reply

/**
 * What the latest incoming text is asking for or doing. Drives both the suggestion chips and the
 * "Needs a reply" indicator, so the two can never disagree about what a message means.
 */
enum class ReplyIntent(
    /** A real person is waiting on an answer: raises the "Needs a reply" indicator. */
    val needsReply: Boolean,
    /** Worth offering one-tap replies for (some courtesy replies are offered without being "needed"). */
    val offersReplies: Boolean,
    /** Position in the "which unanswered message matters most" ranking; lower wins. */
    internal val rank: Int,
) {
    ETA_ASK(true, true, 0),
    EITHER_OR(true, true, 1),
    SCHEDULING(true, true, 2),
    REQUEST(true, true, 3),
    YES_NO(true, true, 4),
    OPEN_QUESTION(true, true, 5),
    CHECK_IN(true, true, 6),
    CLARIFY(true, true, 7),
    BAD_NEWS(true, true, 8),
    REMINDER(true, true, 9),
    GOOD_NEWS(true, true, 10),
    CONGRATS_RECEIVED(false, true, 11),
    ETA_UPDATE(false, true, 12),
    APOLOGY(false, true, 13),
    THANKS(false, true, 14),
    AFFECTION(false, true, 15),
    SHARE(false, true, 16),
    INFO(false, true, 17),
    GREETING(true, true, 18),
    FAREWELL(false, true, 19),

    /** "ok", "lol", a thumbs-up: a conversation closer. */
    ACK(false, false, 20),

    /** A tapback such as Liked "..." or an emoji-only text. */
    REACTION(false, false, 21),

    /** A statement that asks nothing. */
    STATEMENT(false, false, 22),

    /** Nothing to read (blank, or an attachment placeholder with no image). */
    EMPTY(false, false, 23),
    ;

    /** True for intents where the other person is waiting on us (used to pick among several unanswered texts). */
    val isDemanding: Boolean get() = rank <= CLARIFY.rank
}

/** Finer detail inside an intent; selects the wording. */
enum class ReplySub {
    NONE,

    // yes/no
    AVAILABILITY, DID_YOU, ARE_YOU_OK, WANT_TO, OPINION,

    // open questions
    WHEN, WHERE, WHY, TEASER,

    // scheduling
    PROPOSAL, ASK_TIME,

    // requests
    CALL, LET_ME_KNOW, HELP, MONEY,

    // eta updates
    LATE, ARRIVED, OMW,

    // sharing
    LINK, PHOTO, LOCATION, FILE,

    // reminders
    CONFIRM,

    // farewell
    NIGHT, BYE,

    // greetings
    MORNING, AFTERNOON, EVENING,
}

/** Why no suggestions were produced. Used by tests, the doc table, and the "Needs a reply" logic. */
enum class Suppression {
    NOT_INCOMING,
    JUNK_LABEL,
    AUTOMATED_SENDER,
    GROUP_OR_SYSTEM,
    OTP_OR_CODE,
    JUNK_TEXT,
    AUTO_GENERATED,
    ASKS_FOR_CODE,
    UNKNOWN_SENDER_LINK,
    UNKNOWN_SENDER_PROBE,
    ATTACHMENT_ONLY,
    NO_REPLY_NEEDED,
}
