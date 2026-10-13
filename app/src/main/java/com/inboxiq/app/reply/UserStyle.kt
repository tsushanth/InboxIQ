package com.inboxiq.app.reply

/** The job a short reply does. Used to find the user's OWN way of saying it. */
enum class Stance { AFFIRM, DECLINE, DEFER, ACK, THANKS, WELCOME, NOWORRIES, CONGRATS, SYMPATHY, ETA, LOVE, GREET, BYE, ASKBACK, OTHER }

enum class Tone { FORMAL, NEUTRAL, CASUAL }

/**
 * How this user writes, derived on the fly from texts they already sent. Nothing here is stored:
 * it is rebuilt for each suggestion request from the bodies handed in, and discarded.
 *
 * [thread] is what they sent to this contact (weighted heavily); [overall] is everything recent.
 */
class UserStyle(thread: List<String>, overall: List<String>, incomingHint: List<String> = emptyList()) {

    private val threadMsgs = thread.map { it.trim() }.filter { it.isNotEmpty() }
    private val overallMsgs = overall.map { it.trim() }.filter { it.isNotEmpty() }

    /** Prefer this contact's habits once there is a little evidence; otherwise fall back to overall. */
    private val sample: List<String> = if (threadMsgs.size >= 3) threadMsgs else (threadMsgs + overallMsgs).distinct().takeLast(150)

    val emojiRate: Double = rate(sample) { EMOJI.containsMatchIn(it) }
    val usesEmoji: Boolean = sample.isNotEmpty() && emojiRate >= (if (threadMsgs.size >= 3) 0.2 else 0.25)
    val favouriteEmoji: String? = if (usesEmoji) {
        sample.flatMap { m -> EMOJI.findAll(m).map { it.value }.toList() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    } else null

    private val wordy = sample.filter { m -> m.count { it.isLetter() } >= 3 }
    val startsLowercase: Boolean = wordy.size >= 3 && rate(wordy) { m -> m.firstOrNull { it.isLetter() }?.isLowerCase() == true } >= 0.7
    val lowercaseI: Boolean = startsLowercase && wordy.any { LOWER_I.containsMatchIn(it) }

    private val longer = sample.filter { it.split(' ').size >= 3 }
    val dropsFinalPeriod: Boolean = longer.size >= 3 && rate(longer) { it.endsWith(".") } <= 0.15
    val usesFinalPeriod: Boolean = longer.size >= 3 && rate(longer) { it.endsWith(".") } >= 0.6
    val avoidsExclamation: Boolean = sample.size >= 5 && rate(sample) { it.contains('!') } <= 0.04
    val loveExclamation: Boolean = sample.size >= 4 && rate(sample) { it.contains('!') } >= 0.3

    val tone: Tone = run {
        var formal = 0.0
        var casual = 0.0
        for (m in sample) {
            if (FORMAL.containsMatchIn(m)) formal += 1.0
            if (CASUAL.containsMatchIn(m)) casual += 1.0
        }
        val n = sample.size.toDouble()
        // A little hint from how the other side writes, but only when the user has said very little.
        val hint = incomingHint.take(6)
        var f2 = 0.0
        for (m in hint) if (FORMAL_INCOMING.containsMatchIn(m)) f2 += 1.0
        when {
            n >= 2 && formal / n >= 0.25 && casual / n < 0.2 -> Tone.FORMAL
            n >= 2 && (casual / n >= 0.3 || (startsLowercase && casual > 0)) -> Tone.CASUAL
            n < 2 && hint.size >= 2 && f2 / hint.size >= 0.5 -> Tone.FORMAL
            n >= 2 && startsLowercase -> Tone.CASUAL
            else -> Tone.NEUTRAL
        }
    }

    // ------------------------------------------------------------ the user's own phrasings
    private class Phrase(var shown: String, var thread: Int = 0, var overall: Int = 0)

    private val phrases = HashMap<Stance, HashMap<String, Phrase>>()

    init {
        for (m in overallMsgs) learn(m, inThread = false)
        for (m in threadMsgs) learn(m, inThread = true)
    }

    private fun learn(raw: String, inThread: Boolean) {
        if (!usable(raw)) return
        val key = raw.lowercase().trim().trimEnd('.', '!', ' ', ',')
        val stance = StanceMatcher.of(key) ?: return
        val map = phrases.getOrPut(stance) { HashMap() }
        val p = map.getOrPut(key) { Phrase(raw) }
        if (inThread) p.thread++ else p.overall++
        p.shown = raw
    }

    /**
     * The user's most-used way of doing [stance], if they have one: used at least once with this contact,
     * or at least three times overall. Returned exactly as they type it.
     */
    fun phrase(stance: Stance): String? {
        val best = phrases[stance]?.values
            ?.map { it to (it.thread * 3 + it.overall) }
            ?.filter { it.first.thread >= 1 || it.first.overall >= 3 }
            ?.maxByOrNull { it.second } ?: return null
        return best.first.shown
    }

    /** True when [stance] was learned from this user's own texts, not the built-in bank. */
    fun knows(stance: Stance): Boolean = phrase(stance) != null

    companion object {
        val EMOJI = Regex("[\\u2600-\\u27BF\\u2B00-\\u2BFF\\x{1F300}-\\x{1FAFF}\\x{1F000}-\\x{1F2FF}]\\uFE0F?")
        private val LOWER_I = Regex("(^|\\s)i('|\\s|$)")
        private val FORMAL = Regex("^(hello|dear|good (morning|afternoon|evening))\\b|\\b(thank you|regards|sincerely|kindly|i will|i would|please let me know|best wishes|i am)\\b", RegexOption.IGNORE_CASE)
        private val CASUAL = Regex("\\b(lol|lmao|haha+|yeah|yep|yup|nope|gonna|wanna|gotta|omg|brb|thx|ya|u|ur|k|kk|lemme|dunno|idk|np|ttyl|bruh|bro|dude|sup|tho)\\b|^(hey|yo)\\b", RegexOption.IGNORE_CASE)
        private val FORMAL_INCOMING = Regex("\\b(dear|regards|sincerely|kindly|sir|ma'am|good (morning|afternoon)|thank you for|please find|we would like|at your earliest)\\b", RegexOption.IGNORE_CASE)

        private fun rate(list: List<String>, f: (String) -> Boolean): Double =
            if (list.isEmpty()) 0.0 else list.count(f).toDouble() / list.size

        /**
         * Short, generic, safe to show back to the user later: no digits, links, addresses or names
         * (an interior capital letter other than "I" means a name or a place), and no question.
         */
        internal fun usable(raw: String): Boolean {
            val t = raw.trim()
            if (t.length !in 2..32 || t.split(' ').size > 6) return false
            if (t.any { it.isDigit() } || t.contains('@') || t.contains("http", true) || t.contains("www.", true) || t.contains('?')) return false
            val words = t.split(' ').drop(1)
            if (words.any { w -> w.firstOrNull()?.isUpperCase() == true && w != "I" && !w.startsWith("I'") }) return false
            return true
        }
    }
}

/** Which stance a short reply expresses; used to learn the user's phrasing and to check relevance in tests. */
object StanceMatcher {
    private val I = RegexOption.IGNORE_CASE
    private val ordered: List<Pair<Stance, Regex>> = listOf(
        Stance.THANKS to Regex("^(thanks|thank you|thank u|thx|thnx|ty|tysm|thanks so much|thank you so much|thanks a lot|appreciate it|much appreciated|many thanks|cheers|thanks for (letting me know|the (reminder|heads up|update|info)|sharing|sending (this|that|it)|checking in)|thanks,? (i appreciate it|got it))\\b.*$", I),
        Stance.WELCOME to Regex("^(you'?re welcome|youre welcome|you are welcome|welcome|np|no prob(lem)?|anytime|any time|my pleasure|happy to help|don'?t mention it|glad to help|glad i could help)\\b.*$", I),
        Stance.NOWORRIES to Regex("^(no worries|all good|it'?s (ok|okay|fine|all good|alright)|don'?t worry( about it)?|no need to (apologi[sz]e|be sorry)|no problem at all|that'?s (ok|okay|fine|alright)|not a problem|it happens|there'?s no need to apologi[sz]e)\\b.*$", I),
        Stance.CONGRATS to Regex("^(congrats|congratulations|congrat|that'?s (amazing|awesome|great|wonderful|fantastic|incredible)( news)?|so happy for you|well done|amazing|awesome|yay|woohoo|i'?m (so )?(happy|delighted|thrilled) for you|great news).*$", I),
        Stance.SYMPATHY to Regex("^(so sorry|i'?m (so )?sorry|i am (so )?sorry|i am here|i'?m here if|sorry to hear|that'?s (rough|tough|hard|awful|terrible)|that sucks|here for you|i'?m here|thinking of you|hang in there|ugh|oh no|my condolences|sending (love|hugs))\\b.*$", I),
        Stance.LOVE to Regex("^(love you|love u|love you too|miss you|miss you too|miss u|luv you|me too)\\b.*$", I),
        Stance.ETA to Regex("^((i am|i'?m|i will be|i'?ll be) )?(on my way|omw|be right there|be there (soon|shortly)|coming|leaving now|almost there|running (a bit |a few minutes )?late|i'?ll be there|heading (out|over|there))\\b.*$", I),
        Stance.DEFER to Regex("^(let me (check|see|think|find out|look|get back)|lemme (check|see|think)|i'?ll (let you know|check|see|think about it)|will let you know|get back to you|give me a (minute|sec|second|moment)|one (sec|second|moment|minute)|hold on|hang on|checking|not sure yet|i'?ll get back to you|can i get back to you)\\b.*$", I),
        Stance.DECLINE to Regex("^(no|nope|nah|not (today|now|really|this time|tonight|yet)|can'?t|cannot|i can'?t( make it)?|sorry,? (i )?can'?t|sorry,? no|unfortunately|rain ?check|maybe next time|i'?ll pass|pass|i don'?t think so|no thanks|no thank you|i'?m afraid not)\\b.*$", I),
        Stance.AFFIRM to Regex("^(yes|yeah|yep|yup|ya|yea|sure|sounds (good|great|fun|perfect)|works for me|that works|i'?m (in|down|game)|down|in|for sure|definitely|absolutely|of course|will do|sure thing|can do|i can|i will|please|yes please|why not|let'?s do it|deal|bet|ok(ay)?,? (sure|sounds good))\\b.*$", I),
        Stance.ACK to Regex("^(ok(ay)?|k+|kk|got it|noted|cool|alright|aight|understood|gotcha|roger|copy|fine|good to know|makes sense|i see|oh ok|oh okay|okie)\\b.*$", I),
        Stance.GREET to Regex("^(hi|hey|hello|hiya|yo|howdy|good (morning|afternoon|evening)|morning|hey there|hi there|heya)\\b.*$", I),
        Stance.BYE to Regex("^(good ?night|night|gn|bye|goodbye|ttyl|talk (to you )?(later|soon)|see you( later| soon| tomorrow)?|cya|take care|later|sleep well)\\b.*$", I),
    )

    fun of(text: String): Stance? {
        val t = text.trim().trimEnd('.', '!', ' ', ',').trim()
        if (t.isEmpty()) return null
        for ((s, re) in ordered) if (re.matches(t)) return s
        return null
    }
}
