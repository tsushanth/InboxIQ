package com.inboxiq.app.sms

/**
 * What happened when we asked Android to make InboxIQ the default SMS app.
 *
 * The system prompt gives no reason when it is declined, and after the user dismisses it a few
 * times Android stops showing it and returns "cancelled" within a blink. Before this existed the
 * "Choose InboxIQ as default" button then looked dead: nothing on screen changed. We tell the
 * three cases apart so the user always gets a next step.
 */
enum class RoleRequestOutcome {
    /** InboxIQ now holds the role. */
    GRANTED,

    /** The prompt was shown and the user said no (or backed out). Trying again will show it again. */
    DECLINED,

    /** Android returned at once without a prompt. Only the Default apps screen can grant it now. */
    SYSTEM_SUPPRESSED;

    companion object {
        /** A human cannot read and decline a system dialog this fast. */
        const val SUPPRESSED_BELOW_MS = 600L

        fun classify(isDefaultNow: Boolean, elapsedMs: Long): RoleRequestOutcome = when {
            isDefaultNow -> GRANTED
            elapsedMs < SUPPRESSED_BELOW_MS -> SYSTEM_SUPPRESSED
            else -> DECLINED
        }
    }
}

/** Snackbar copy for an outcome that needs a message; null when nothing should be said. */
data class RoleRequestMessage(val text: String, val action: Action) {
    enum class Action(val label: String) { TRY_AGAIN("Try again"), OPEN_SETTINGS("Open settings") }

    companion object {
        fun forOutcome(o: RoleRequestOutcome): RoleRequestMessage? = when (o) {
            RoleRequestOutcome.GRANTED -> null
            RoleRequestOutcome.DECLINED -> RoleRequestMessage(
                "InboxIQ is not your default SMS app. Your current texting app keeps working as before.",
                Action.TRY_AGAIN,
            )
            RoleRequestOutcome.SYSTEM_SUPPRESSED -> RoleRequestMessage(
                "Android did not show the prompt. Choose InboxIQ under Default apps > SMS app.",
                Action.OPEN_SETTINGS,
            )
        }
    }
}
