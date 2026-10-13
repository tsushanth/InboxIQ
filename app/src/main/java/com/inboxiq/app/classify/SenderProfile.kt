package com.inboxiq.app.classify

/** What kind of sender an address is. Cheap, offline, and the single most informative feature for SMS triage. */
enum class SenderKind {
    /** 3 to 6 digit short code (banks, retailers, 2FA providers, carriers). Always automated. */
    SHORT_CODE,

    /** Alphanumeric sender ID such as "ACMEBANK". Always automated. */
    ALPHA_ID,

    /** US/Canada toll-free number (800, 833, 844, 855, 866, 877, 888). Almost always a business. */
    TOLL_FREE,

    /** Ordinary US/Canada 10-digit number: a person, but also 10DLC business marketing and scammers. */
    PHONE,

    /** Any other country code. Unsolicited texts from abroad are a common scam vector. */
    INTL_PHONE,

    /** Email-to-SMS gateway address (contains "@"). Rarely a personal contact. */
    EMAIL_GATEWAY,

    UNKNOWN,
}

data class SenderProfile(val kind: SenderKind) {
    /** Short codes, alphanumeric IDs and toll-free numbers do not hold conversations. */
    val isAutomated: Boolean get() = kind == SenderKind.SHORT_CODE || kind == SenderKind.ALPHA_ID || kind == SenderKind.TOLL_FREE

    /** A sender that could plausibly be a friend, colleague or family member. */
    val isPersonLike: Boolean get() = kind == SenderKind.PHONE || kind == SenderKind.INTL_PHONE

    val description: String
        get() = when (kind) {
            SenderKind.SHORT_CODE -> "a short code, the kind businesses use for automated texts"
            SenderKind.ALPHA_ID -> "a named sender ID, the kind businesses use for automated texts"
            SenderKind.TOLL_FREE -> "a toll-free number, usually a business"
            SenderKind.PHONE -> "an ordinary phone number"
            SenderKind.INTL_PHONE -> "a phone number from another country"
            SenderKind.EMAIL_GATEWAY -> "an email-to-text gateway address, which people rarely text from"
            SenderKind.UNKNOWN -> "an unrecognised sender"
        }

    companion object {
        val UNKNOWN = SenderProfile(SenderKind.UNKNOWN)

        private val tollFreePrefixes = setOf("800", "833", "844", "855", "866", "877", "888")

        fun of(address: String?): SenderProfile {
            val a = address?.trim().orEmpty()
            if (a.isEmpty()) return UNKNOWN
            if (a.contains('@')) return SenderProfile(SenderKind.EMAIL_GATEWAY)
            if (a.any { it.isLetter() }) return SenderProfile(SenderKind.ALPHA_ID)
            val hasPlus = a.startsWith("+")
            val digits = a.filter { it.isDigit() }
            if (digits.isEmpty()) return UNKNOWN
            if (!hasPlus && digits.length in 3..6) return SenderProfile(SenderKind.SHORT_CODE)
            val national = when {
                hasPlus && digits.startsWith("1") && digits.length == 11 -> digits.substring(1)
                !hasPlus && digits.length == 11 && digits.startsWith("1") -> digits.substring(1)
                !hasPlus && digits.length == 10 -> digits
                else -> null
            }
            if (national != null) {
                return SenderProfile(if (national.substring(0, 3) in tollFreePrefixes) SenderKind.TOLL_FREE else SenderKind.PHONE)
            }
            if (hasPlus || digits.length > 10) return SenderProfile(SenderKind.INTL_PHONE)
            return UNKNOWN
        }
    }
}

/** How much the user has actually talked to this sender. Replies to bare STOP/YES do not count (see ThreadLabelDao). */
enum class Relationship {
    NONE,

    /** The user answered once or twice: could be a real contact, could be "who is this?" to a scammer. */
    WEAK,

    /** A real back-and-forth. Treated as a known contact: never filed as spam, scam only on overwhelming evidence. */
    STRONG,
    ;

    companion object {
        fun of(sender: SenderProfile, replies: Int): Relationship = when {
            !sender.isPersonLike -> NONE
            replies >= 3 -> STRONG
            replies >= 1 -> WEAK
            else -> NONE
        }
    }
}
