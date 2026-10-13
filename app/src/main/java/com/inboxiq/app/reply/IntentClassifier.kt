package com.inboxiq.app.reply

/**
 * Reads one incoming text and says what it wants. Pure Kotlin, regex only, no allocation-heavy work:
 * every pattern is compiled once, and a text is split into sentences so a greeting in front of a
 * question ("Hey! Are you free tonight?") does not hide the question.
 */
object IntentClassifier {

    data class Result(
        val intent: ReplyIntent,
        val sub: ReplySub = ReplySub.NONE,
        /** For EITHER_OR: the two things offered, when they could be read safely. */
        val options: Pair<String, String>? = null,
    )

    private val I = RegexOption.IGNORE_CASE
    private fun re(p: String) = Regex(p, I)

    // ---------------------------------------------------------------- patterns
    private val reaction = re("^(liked|loved|laughed at|emphasized|disliked|questioned|reacted \\w+ to)\\s+[\"“”].*")
    private val sentenceSplit = Regex("(?<=[.!?])\\s+|\\n+")
    private val urlRe = re("https?://|\\bwww\\.|\\b[a-z0-9-]+\\.(com|org|net|io|co|app|me|ly|link|gl|page)(/|\\b)")
    private val mapsRe = re("maps\\.app\\.goo\\.gl|google\\.[a-z.]+/maps|maps\\.apple\\.com|goo\\.gl/maps|waze\\.com|what3words|\\bmy location\\b|dropped (a )?pin|shared (my )?location|pinned (my )?location|live location")

    private val etaAsk = re("\\bwhere (are|r) (you|u)\\b|\\bwhere you at\\b|\\bwhere'?s? (you|u)\\b|\\bhow far (are|r|away)\\b|\\byour eta\\b|\\bwhat'?s your eta\\b|\\bwhen (are|r) you (getting|coming|gonna|going to|arriving|gonna be|going to be)\\b|\\bhow long (until|till|before) you\\b|\\bwhat time (are|will) you (get|be|arrive|come)\\b|\\b(are|r) you (close|nearby|almost here|almost there|on your way|on the way|here yet|there yet)\\b")
    private val checkIn = re("\\bhow (are|r) (you|u)(?! (getting|going|planning|paying|doing that|gonna|able))\\b|\\bhow('s| is) it going\\b|\\bhow have you been\\b|\\bhow('s| is) your (day|morning|evening|weekend|week)\\b|\\bhow was your (day|weekend|trip|flight|night|week)\\b|\\bwhat('s| is) up\\b|\\bwhats up\\b|\\bwhat'?s good\\b|\\bwhat are you up to\\b|\\bhow('s| is) everything\\b|\\bhow you doing\\b|\\bhow are things\\b|\\bhow'?s life\\b|\\bhow do you do\\b|^sup\\b")
    private val clarify = re("^(what|huh|hm+|eh|sorry|come again|wdym|what do you mean)[?\\s!]*$|^\\?+$|\\bwhat do you mean\\b|\\bi don'?t (get|understand) (it|this|what)\\b")

    private val schedulingQ = re("\\b(are|r) you (free|available|around|busy|up for)\\b|\\bdo you (have|got) (time|a minute|a sec|a moment|a few minutes)\\b|\\bdoes (that|this|\\d|tomorrow|tonight|today|\\w+day|noon|morning|evening|afternoon|next \\w+|the \\w+) work\\b|\\bwould (that|this|\\d|\\w+day|tomorrow|tonight|noon) work\\b|\\bwhat about (\\d|tomorrow|tonight|today|\\w+day|next|this|the weekend|noon|later)|\\bhow about (\\d|tomorrow|tonight|today|\\w+day|next|this|the weekend|noon|later|a |the )|\\bwhen (are|r) you (free|available)\\b|\\bwhat time (works|is good|suits|should|can|do you)|\\bwhen (works|is good|can you|can we|should we|do you want)\\b|\\bwhich day\\b|\\bcan we (meet|talk|chat|catch up|do|reschedule|move|push|grab|get together|hang)\\b|\\bcan you (meet|make it|come over|swing by|stop by)\\b|\\bwant to (meet|grab|get|do|hang|catch)\\b|\\bwanna (meet|grab|get|do|hang|catch)\\b|\\bup for (lunch|dinner|coffee|drinks|a walk|a call|brunch|breakfast)\\b|\\bfree (today|tonight|tomorrow|this|on|later|after|at)\\b")
    private val schedulingStmt = re("\\blet'?s (meet|do|grab|get|go|catch|hang|have|try|plan|say|aim|make it)\\b|\\bmeet (me|us|you)\\b.{0,15}\\b(at|in|by|on|near)\\b|\\b(i|we) (can|could) (do|meet|make) (it )?(at|on|around|after|before)?\\s*\\d|\\bhow about\\b|\\bwhat about\\b|\\bmake it (at|on)?\\s*\\d")
    private val askTime = re("\\bwhen (are|r) you (free|available)\\b|\\bwhat time (works|is good|suits|should|can|do you)|\\bwhen (works|is good|can you|can we|should we|do you want)\\b|\\bwhich day\\b|\\bwhat day\\b")
    private val concreteTime = re("\\b\\d{1,2}(:\\d\\d)?\\s?(am|pm|a\\.m\\.|p\\.m\\.)\\b|\\bat \\d|\\b(noon|midnight|tomorrow|tonight|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|weekend)\\b")

    private val requestRe = re("\\b(can|could|would|will) you (please )?(pick|grab|get|bring|send|text|help|give|take|check|let me know|tell|forward|share|watch|feed|water|drop|buy|order|book|pay|remind|look|make|do|make sure|stop|cover|lend|loan|borrow|hold|find|fix|handle|take care|review|sign|approve|confirm|update|email|print|bring)\\b|\\bplease (send|text|bring|pick|let me know|help|check|remind|grab|get|call|reply|confirm|review|forward|share|tell|send me)\\b|\\bi need (you to|your help|a favor|a favour)\\b|\\bneed (some )?help\\b|\\bcan i (ask|get|borrow) \\b|\\bdo me a (favor|favour)\\b|\\bmind (helping|sending|picking|grabbing|checking|bringing)\\b|\\bany chance you (can|could)\\b|\\bhelp me\\b|\\bwould you mind\\b|\\bI was wondering if you could\\b|\\bcould you possibly\\b")
    private val callMe = re("\\bcall me\\b|\\bgive me a (call|ring|buzz)\\b|\\bring me\\b|\\b(can|could|will|would) you call\\b|\\bcall (back|when|asap)\\b|\\bphone me\\b|\\bcan we (talk|speak)\\b|\\bcan you talk\\b")
    private val letMeKnow = re("\\blet me know\\b|\\bkeep me (posted|updated)\\b|\\bhit me up\\b|\\bget back to me\\b|\\btext me when\\b")
    private val helpRe = re("\\bneed (some )?help\\b|\\bi need (you to|your help|a favor|a favour)\\b|\\bdo me a (favor|favour)\\b|\\bhelp me\\b|\\bcan i ask (you )?(a )?(favor|favour|something)\\b")
    private val moneyRe = re("\\b(venmo|zelle|cash ?app|paypal|wire|transfer|loan|lend|send)\\b.{0,25}(\\$|money|cash|dollars|bucks|\\d{2,})|\\b(\\$\\s?\\d|\\d+\\s?(dollars|bucks|usd))\\b.{0,25}\\b(venmo|zelle|cash ?app|paypal|send|lend|loan|owe|pay)\\b|\\bgift ?cards?\\b|\\bcan i borrow (some )?(money|cash|\\$)\\b|\\bpay me (back)?\\b|\\bbank (details|account|info)\\b")

    private val opinion = re("\\bwhat do you think\\b|\\bthoughts\\??$|\\bwhat'?s your (opinion|take)\\b|\\bhow does (that|this) (look|sound)\\b|\\bwdyt\\b|\\bdo you like\\b|\\bwhich (one|do you like|is better)\\b")
    private val yesNoOpinionTag = re("\\b(sound|sounds) (good|ok|okay|fine|right)\\s*\\??$|\\bmake sense\\s*\\??$|\\bis that (ok|okay|alright|fine|good)\\b|\\bokay\\s*\\?+$|\\bok\\s*\\?+$|\\b(right|yeah|yes|no|deal)\\s*\\?+$")
    private val wantTo = re("\\b(do you|would you|you) (want|like|wanna) to\\b|\\bwanna\\b|\\bwould you like\\b|\\b(are|r) you (coming|in|joining|going|down|game|gonna come)\\b|\\bwant to (come|join|go|try|see)\\b|\\byou (coming|in|down|going)\\b|\\binterested\\b")
    private val didYou = re("^(did|have|has|had|were|was) (you|u)\\b|\\bdid you (get|see|eat|finish|call|receive|hear|read|check|talk|sleep|make|do|take|bring|forget)\\b|\\bhave you (seen|heard|eaten|got|received|checked|read|talked|been|tried)\\b|\\bany (news|update|luck)\\b")
    private val areYouOk = re("\\b(are|r) (you|u) (ok|okay|alright|all right|good|safe|fine|home|there|up|awake|still)\\b|\\byou (ok|okay|alright)\\b|\\bare you (feeling|doing) (ok|okay|better|alright)\\b|\\bfeeling better\\b|\\bstill (up|awake|there|at)\\b|\\b(u|you) (up|home|there)\\b")
    private val availability = re("\\b(are|r) you (free|available|around|busy)\\b|\\bu free\\b|\\byou free\\b|\\bfree (today|tonight|tomorrow|this|later|after)\\b|\\bhave time\\b")

    private val whWord = re("^(what|when|where|who|whom|whose|why|how|which)\\b")
    private val whenQ = re("\\bwhen\\b|\\bwhat time\\b|\\bwhat day\\b|\\bhow long\\b")
    private val whereQ = re("\\bwhere\\b|\\bwhich (place|restaurant|spot|cafe|address)\\b")
    private val whyQ = re("^why\\b|\\bwhy (did|didn'?t|are|aren'?t|is|isn'?t|do|don'?t|would|wouldn'?t|haven'?t|can'?t)\\b|\\bhow come\\b")
    private val teaser = re("^guess what\\b|^you won'?t believe\\b|^i have (some )?(news|something to tell)\\b|^i have a (surprise|secret)\\b|^we need to talk\\b|^can i tell you something\\b")
    private val auxStart = re("^(do|does|did|are|is|was|were|have|has|had|can|could|will|would|should|shall|may|might|am|wanna|want to|you|u|ya|y'all|we|they|he|she|it|any|anyone|anybody|everything)\\b")
    private val subjectShort = re("^(you|u|we|they|he|she|it|y'all) (coming|free|there|home|up|ok|okay|good|around|down|in|here|ready|awake|busy|alive|done|on|off|sure|serious|joking|mad|angry|upset|available|still)\\b")

    private val badNews = re("\\b(passed away|passing of|has died|just died|he died|she died|my (dad|mom|mother|father|grandma|grandpa|grandmother|grandfather|brother|sister|uncle|aunt|friend|dog|cat) died|funeral|lost my (job|dad|mom|mother|father|grandma|grandpa|dog|cat|wallet|grandmother|grandfather|brother|sister)|got laid off|laid off|got fired|i was fired|diagnos(ed|is)|cancer|in the hospital|at the hospital|in the er\\b|emergency room|surgery|car accident|had an accident|got into an accident|was in an accident|crashed my|broke up|breaking up|getting divorced|divorce|didn'?t get (it|the job|in|the offer)|got rejected|was rejected|i failed|failed my|bad news|sad news|terrible news|awful news|worst day|rough day|really sick|so sick|feeling sick|got covid|tested positive|miscarriage|house (burned|flooded)|got robbed|was robbed|totaled my car|we lost him|we lost her|lost our)\\b")
    private val notBadNews = re("\\bphone died\\b|\\bbattery died\\b|\\bsick of\\b|\\bdied laughing\\b|\\bdying laughing\\b|\\bwent well\\b|\\bwas successful\\b|\\bgot rejected from my\\b")
    private val goodNews = re("\\bgot (the|a) (job|offer|promotion|raise|house|apartment|place|internship|scholarship)\\b|\\bgot (engaged|married|promoted|accepted|in(to)?)\\b|\\bpassed (my|the) (exam|test|interview|bar|driving test|boards|class|course)\\b|\\b(we|i)('re| are|'m| am) (engaged|pregnant|expecting|getting married|moving|having a (baby|boy|girl))\\b|\\bgood news\\b|\\bgreat news\\b|\\bexciting news\\b|\\bit'?s a (boy|girl)\\b|\\bbaby (was|is) born\\b|\\bwe did it\\b|\\bi did it\\b|\\b(he|she) said yes\\b|\\bi'?m (getting )?(a )?(new job|promoted)\\b|\\bpromoted\\b|\\bgraduat(ed|ing|ion)\\b|\\bwe (won|made it)\\b|\\bi (won|made it)\\b|\\bour baby\\b|\\bsold the house\\b|\\bclosed on (the|our)\\b|\\bsurgery went well\\b|\\bget(ting)? the keys\\b|\\bi got in\\b")
    private val congratsRe = re("\\bcongrat(s|ulations|z)?\\b|\\bwell done\\b|\\bproud of you\\b|\\bgood job\\b|\\bnice work\\b|\\bgreat job\\b")
    private val sympathyRe = re("\\bsorry to hear\\b|\\bthinking of you\\b|\\bhere for you\\b|\\bhope you feel better\\b|\\bhope you'?re (ok|okay|feeling better|doing better)\\b|\\bget well\\b|\\bfeel better soon\\b|\\bour condolences\\b|\\bmy condolences\\b")
    private val thanksRe = re("\\b(thanks|thank you|thank u|thx|thnx|tysm|ty|appreciate (it|you|that|this)|cheers|much appreciated|you'?re the best|you rock|you saved me|lifesaver|life saver)\\b")
    private val apologyRe = re("\\b(sorry|my bad|apologi[sz]e|apologies|my fault|forgive me|oops|whoops)\\b")
    private val etaStmt = re("\\b(on my way|omw|on the way|heading (there|over|out|your way)|leaving (now|soon|shortly|in)|just left|be there in|will be there|i'?ll be there|running (a bit |a little |\\d+ ?min(ute)?s? |\\d+ ?mins? )?(late|behind)|stuck in traffic|traffic is|almost there|almost here|i'?m (here|outside|downstairs|parking|parked|at the (door|entrance|lobby|front))|just (arrived|got here|pulled up)|here now|be (there|over|by) (soon|shortly)|pulling up|coming now|\\d+ ?(min|mins|minutes) away|be (a bit|a little|a few minutes) late|going to be late|gonna be late)\\b")
    private val lateRe = re("\\blate\\b|\\bbehind\\b|\\btraffic\\b|\\bdelay")
    private val arrivedRe = re("\\bi'?m (here|outside|downstairs|parking|parked)|\\barrived\\b|\\bpulling up\\b|\\bgot here\\b|\\bat the (door|entrance|lobby|front)\\b|\\bpulled up\\b|\\bhere now\\b")
    private val affection = re("\\blove (you|u)\\b|\\bmiss (you|u)\\b|\\bthinking (of|about) (you|u)\\b|\\bxoxo\\b|\\bmuch love\\b|\\bluv (you|u)\\b")
    private val farewell = re("^(good ?night|night|gn|nighty night|sweet dreams|bye|goodbye|ttyl|talk (to you )?(later|soon|tomorrow)|see you (later|soon|tomorrow|then)|cya|take care|have a (good|great|nice) (night|one|day|weekend|evening)|catch you later|later|sleep well|nighty)\\b")
    private val nightRe = re("night|gn\\b|sweet dreams|sleep well")
    private val greeting = re("^(hi|hey|hello|hiya|yo|howdy|good (morning|afternoon|evening)|morning|gm|hey there|hi there|hello there|heya|hola)\\b[\\s,!.;:-]*([a-z']+\\s*){0,3}[!.?]*$")
    private val shareRe = re("\\b(check (this|it|that) out|look at this|check out|saw this|sent you|forwarded|photo|pic|picture|video|screenshot|attached|here'?s (the|a|my|an)|found this|take a look|sharing)\\b")
    private val reminderRe = re("\\b(reminder|don'?t forget|remember (to|that|we)|just a reminder|friendly reminder|you have an? (appointment|meeting|reservation|booking)|your (appointment|reservation|visit|booking|table|session|class)|confirm (your|the|that)|reschedule|appointment (is|on|with|at|for))\\b")
    private val confirmAsk = re("\\breply (c|yes|y|1|confirm)\\b|\\bplease confirm\\b|\\bcan you confirm\\b|\\bconfirm (your|the) (attendance|appointment|reservation)\\b|\\blet us know if you (need|want) to (reschedule|cancel|change)\\b|\\bto confirm\\b|\\bconfirm\\b.*\\?")
    private val infoRe = re("\\b(moved|postponed|cancel(l)?ed|delayed|pushed|rescheduled|changed to|now at|running behind|is ready|are ready|ready for pickup|just so you know|fyi|heads up|letting you know|wanted to let you know|let you know|ordered|booked|submitted|finished|done with|got home|home safe|landed|is open|is closed|starts at|ends at|won'?t be able|can'?t make it|not coming)\\b")
    private val ackRe = re("^(ok(ay)?|k+|kk|sure|yes|yeah|yep|yup|ya|no|nope|nah|cool|great|nice|awesome|perfect|sounds (good|great|fine)|got it|noted|alright|aight|fine|will do|haha+|hehe+|lol+|lmao|rofl|same|true|right|exactly|agreed|np|no worries|you too|same to you|ty|thx|thanks|dope|sweet|bet|word|ikr|omg|wow|oh|ah|aw+|hm+|yay|yes please|of course|definitely|for sure|absolutely|good|gotcha|understood|makes sense|that works|works for me|fair enough|i see|oh ok|oh okay|okie|okey|roger|copy|cheers)\\b[\\s\\p{P}\\p{So}\\p{Sk}]*([a-z']+\\s*){0,2}[\\s\\p{P}\\p{So}\\p{Sk}]*$")

    // ---------------------------------------------------------------- public
    fun classify(body: String): Result {
        val text = normalise(body)
        if (text.none { it.isLetterOrDigit() }) return Result(if (text.isBlank()) ReplyIntent.EMPTY else ReplyIntent.REACTION)
        if (reaction.containsMatchIn(text)) return Result(ReplyIntent.REACTION)

        val sentences = text.split(sentenceSplit).map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }
        if (sentences.isEmpty()) return Result(ReplyIntent.REACTION)

        // Whole-message shapes first.
        if (sentences.size == 1 && clarify.containsMatchIn(sentences[0]) && sentences[0].length <= 24) return Result(ReplyIntent.CLARIFY)

        val results = sentences.mapNotNull { sentence(it, text) }
        // The last thing asked is what is being asked now; with nothing demanding, the strongest remaining intent.
        val best = results.lastOrNull { it.intent.isDemanding } ?: results.minByOrNull { it.intent.rank }
        return best ?: Result(ReplyIntent.STATEMENT)
    }

    fun normalise(body: String): String =
        body.take(MAX_CHARS).replace('\u2019', '\'').replace('\u2018', '\'').replace('\u00A0', ' ').replace(Regex("[ \\t]+"), " ").trim()

    /** Longer texts are read only up to here: bounds the work, and the question is nearly always in the opening. */
    const val MAX_CHARS = 1200

    fun hasLink(text: String) = urlRe.containsMatchIn(text)
    fun hasLocation(text: String) = mapsRe.containsMatchIn(text)

    // ---------------------------------------------------------------- one sentence
    private fun sentence(s: String, whole: String): Result? {
        val q = s.endsWith("?") || s.endsWith("?!") || s.contains("?")
        val lower = s.lowercase()
        val hasUrl = urlRe.containsMatchIn(s)

        // ---- links and places: shared, not asked about (a question about a link is handled as a request below)
        if (hasUrl || mapsRe.containsMatchIn(s)) {
            if (requestRe.containsMatchIn(s) || (q && !mapsRe.containsMatchIn(s))) {
                return Result(ReplyIntent.REQUEST)
            }
            val sub = if (mapsRe.containsMatchIn(s)) ReplySub.LOCATION else ReplySub.LINK
            return Result(ReplyIntent.SHARE, sub)
        }

        // ---- money and gift cards: always a request, always handled carefully
        if (moneyRe.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, ReplySub.MONEY)

        // ---- bad news in this sentence (a question later in the message still wins through rank)
        if (badNews.containsMatchIn(s) && !notBadNews.containsMatchIn(s) && !q) return Result(ReplyIntent.BAD_NEWS)

        if (q || auxStart.containsMatchIn(s) && s.length < 60 && !s.endsWith(".") && !s.endsWith("!") && isQuestionShaped(lower)) {
            val r = question(s, lower, q)
            if (r != null) return r
        }

        // ---- imperatives and asks that are not phrased as questions
        if (callMe.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, ReplySub.CALL)
        if (letMeKnow.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, ReplySub.LET_ME_KNOW)
        if (requestRe.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, if (helpRe.containsMatchIn(s)) ReplySub.HELP else ReplySub.NONE)
        if (schedulingStmt.containsMatchIn(s) && concreteTime.containsMatchIn(s)) return Result(ReplyIntent.SCHEDULING, ReplySub.PROPOSAL)
        if (schedulingStmt.containsMatchIn(s) && lower.contains("let's")) return Result(ReplyIntent.SCHEDULING, ReplySub.PROPOSAL)
        if (teaser.containsMatchIn(s)) return Result(ReplyIntent.OPEN_QUESTION, ReplySub.TEASER)

        if (sympathyRe.containsMatchIn(s)) return Result(ReplyIntent.CONGRATS_RECEIVED) // they are being kind to us: say thanks
        if (congratsRe.containsMatchIn(s) && !goodNews.containsMatchIn(s)) return Result(ReplyIntent.CONGRATS_RECEIVED)
        if (reminderRe.containsMatchIn(s) && (concreteTime.containsMatchIn(s) || confirmAsk.containsMatchIn(s) || lower.contains("reminder") || lower.contains("don't forget"))) {
            return Result(ReplyIntent.REMINDER, if (confirmAsk.containsMatchIn(s)) ReplySub.CONFIRM else ReplySub.NONE)
        }
        if (confirmAsk.containsMatchIn(s)) return Result(ReplyIntent.REMINDER, ReplySub.CONFIRM)
        if (goodNews.containsMatchIn(s)) return Result(ReplyIntent.GOOD_NEWS)
        if (etaStmt.containsMatchIn(s)) {
            val sub = when {
                arrivedRe.containsMatchIn(s) -> ReplySub.ARRIVED
                lateRe.containsMatchIn(s) -> ReplySub.LATE
                else -> ReplySub.OMW
            }
            return Result(ReplyIntent.ETA_UPDATE, sub)
        }
        if (apologyRe.containsMatchIn(s) && !thanksRe.containsMatchIn(s)) return Result(ReplyIntent.APOLOGY)
        if (thanksRe.containsMatchIn(s)) return Result(ReplyIntent.THANKS)
        if (affection.containsMatchIn(s)) return Result(ReplyIntent.AFFECTION)
        if (shareRe.containsMatchIn(s) && !q) {
            val sub = when {
                lower.contains("photo") || lower.contains("pic") || lower.contains("selfie") -> ReplySub.PHOTO
                lower.contains("video") -> ReplySub.PHOTO
                lower.contains("file") || lower.contains("doc") || lower.contains("attached") || lower.contains("pdf") -> ReplySub.FILE
                else -> ReplySub.LINK
            }
            return Result(ReplyIntent.SHARE, sub)
        }
        if (infoRe.containsMatchIn(s)) return Result(ReplyIntent.INFO)
        if (greeting.containsMatchIn(s) && whole.length <= 40) {
            val sub = when {
                lower.contains("morning") || lower == "gm" -> ReplySub.MORNING
                lower.contains("afternoon") -> ReplySub.AFTERNOON
                lower.contains("evening") -> ReplySub.EVENING
                else -> ReplySub.NONE
            }
            return Result(ReplyIntent.GREETING, sub)
        }
        if (farewell.containsMatchIn(s) && whole.length <= 60) {
            return Result(ReplyIntent.FAREWELL, if (nightRe.containsMatchIn(s)) ReplySub.NIGHT else ReplySub.BYE)
        }
        if (ackRe.containsMatchIn(s) || s.split(' ').size <= 2 && s.length <= 12) return Result(ReplyIntent.ACK)
        return null
    }

    private fun isQuestionShaped(lower: String): Boolean =
        subjectShort.containsMatchIn(lower) || Regex("^(do|does|did|are|is|was|were|have|has|had|can|could|will|would|should|shall|am|wanna|want to)\\b", I).containsMatchIn(lower)

    // ---------------------------------------------------------------- questions
    private fun question(s: String, lower: String, hasMark: Boolean): Result? {
        if (clarify.containsMatchIn(s) && s.length <= 24) return Result(ReplyIntent.CLARIFY)
        if (etaAsk.containsMatchIn(s)) return Result(ReplyIntent.ETA_ASK)
        if (checkIn.containsMatchIn(s)) return Result(ReplyIntent.CHECK_IN)
        if (moneyRe.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, ReplySub.MONEY)

        eitherOr(s)?.let { return it }

        if (callMe.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, ReplySub.CALL)
        if (askTime.containsMatchIn(s)) return Result(ReplyIntent.SCHEDULING, ReplySub.ASK_TIME)
        if (requestRe.containsMatchIn(s)) return Result(ReplyIntent.REQUEST, if (helpRe.containsMatchIn(s)) ReplySub.HELP else ReplySub.NONE)
        // "Are you free tonight?" and "Want to grab lunch?" are yes/no questions about the person; "Does 7 work?" is a proposal.
        val proposal = Regex("\\bdoes (that|this|\\d|tomorrow|tonight|today|\\w+day|noon|morning|evening|afternoon|next \\w+|the \\w+) work\\b|\\bwould (that|this|\\d|\\w+day|tomorrow|tonight|noon) work\\b|\\b(what|how) about\\b|\\bcan we (meet|reschedule|move|push|do|talk|chat|catch up)\\b|\\bcan you (meet|make it)\\b", I)
        if (availability.containsMatchIn(s) && !proposal.containsMatchIn(s)) return Result(ReplyIntent.YES_NO, ReplySub.AVAILABILITY)
        if (wantTo.containsMatchIn(s) && !proposal.containsMatchIn(s)) return Result(ReplyIntent.YES_NO, ReplySub.WANT_TO)
        if (schedulingQ.containsMatchIn(s)) {
            return Result(ReplyIntent.SCHEDULING, ReplySub.PROPOSAL)
        }
        if (teaser.containsMatchIn(s)) return Result(ReplyIntent.OPEN_QUESTION, ReplySub.TEASER)
        if (opinion.containsMatchIn(s)) {
            return if (whWord.containsMatchIn(s) || lower.contains("thought")) Result(ReplyIntent.OPEN_QUESTION, ReplySub.OPINION)
            else Result(ReplyIntent.YES_NO, ReplySub.OPINION)
        }
        if (yesNoOpinionTag.containsMatchIn(s) && !whWord.containsMatchIn(s)) return Result(ReplyIntent.YES_NO, ReplySub.OPINION)

        val wh = whWord.containsMatchIn(s)
        if (!wh || lower.startsWith("how about") || lower.startsWith("what about")) {
            val sub = when {
                availability.containsMatchIn(s) -> ReplySub.AVAILABILITY
                areYouOk.containsMatchIn(s) -> ReplySub.ARE_YOU_OK
                didYou.containsMatchIn(s) -> ReplySub.DID_YOU
                wantTo.containsMatchIn(s) -> ReplySub.WANT_TO
                else -> ReplySub.NONE
            }
            val shaped = auxStart.containsMatchIn(s) || subjectShort.containsMatchIn(lower) || hasMark
            if (shaped) {
                // A bare noun phrase ("Pizza?") is a proposal; a long unshaped sentence ending in ? is an open question.
                if (!auxStart.containsMatchIn(s) && s.split(' ').size > 7) return Result(ReplyIntent.OPEN_QUESTION)
                return Result(ReplyIntent.YES_NO, sub)
            }
            return null
        }
        val sub = when {
            whyQ.containsMatchIn(s) -> ReplySub.WHY
            whereQ.containsMatchIn(s) -> ReplySub.WHERE
            whenQ.containsMatchIn(s) -> ReplySub.WHEN
            else -> ReplySub.NONE
        }
        if (sub == ReplySub.WHEN && askTime.containsMatchIn(s)) return Result(ReplyIntent.SCHEDULING, ReplySub.ASK_TIME)
        if (!hasMark) return null
        return Result(ReplyIntent.OPEN_QUESTION, sub)
    }

    private val stopWords = setOf(
        "the", "a", "an", "it", "me", "you", "we", "this", "that", "yes", "no", "not", "now", "later", "not", "i", "us", "them", "or", "and", "to", "of",
        "in", "on", "at", "is", "are", "do", "be", "so", "if", "my", "your", "our", "his", "her", "their", "something", "else", "anything", "what", "how",
    )
    private val eitherRe = Regex("([a-z0-9'-]+)\\s+or\\s+([a-z0-9'-]+)\\s*[?!.]*\\s*$", I)

    private fun eitherOr(s: String): Result? {
        val lower = s.lowercase()
        if (!Regex("\\bor\\b").containsMatchIn(lower)) return null
        if (!(s.contains('?') || auxStart.containsMatchIn(s) || whWord.containsMatchIn(s))) return null
        if (Regex("\\b(yes or no|or not|or what|or something|or anything|or whatever|or so|or later|or else)\\b", I).containsMatchIn(lower)) return null
        // Two things offered in one clause: "pizza or tacos?". Only trust single plain words at the end.
        val m = eitherRe.find(s)
        val a = m?.groupValues?.get(1)?.lowercase()
        val b = m?.groupValues?.get(2)?.lowercase()
        val ok = a != null && b != null && a !in stopWords && b !in stopWords && a.length >= 3 && b.length >= 3 && a.any(Char::isLetter) && b.any(Char::isLetter)
        // "or" inside a question with no clean pair ("call or text me?") is still an either/or.
        return Result(ReplyIntent.EITHER_OR, ReplySub.NONE, if (ok) a!! to b!! else null)
    }
}
