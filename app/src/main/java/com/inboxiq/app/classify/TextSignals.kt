package com.inboxiq.app.classify

import com.inboxiq.app.classify.LinkFeatures.Kind

/** One piece of evidence. [why] is phrased for the "Why was this labelled?" sheet, so it must be true when shown. */
data class Signal(val group: String, val weight: Float, val why: String)

data class OtpHit(val digits: Int, val strong: Boolean, val word: String)

/** Everything the rules can read from one message, computed once. Pure Kotlin, no Android types. */
class TextSignals private constructor(
    val text: String,
    val links: List<LinkFeatures.Link>,
    val otp: OtpHit?,
    val scam: List<Signal>,
    val promo: List<Signal>,
    val work: List<Signal>,
    val txn: List<Signal>,
    val spam: List<Signal>,
    val hasOptOut: Boolean,
) {
    companion object {
        private val I = RegexOption.IGNORE_CASE

        private class Rule(val group: String, val weight: Float, val why: String, val re: Regex, val perDistinctMatch: Boolean = false, val maxWeight: Float = weight)

        private fun r(group: String, weight: Double, why: String, pattern: String) = Rule(group, weight.toFloat(), why, Regex(pattern, I))

        /** Worth [weight] for each DISTINCT word it matches, up to [max]. */
        private fun many(group: String, weight: Double, max: Double, why: String, pattern: String) =
            Rule(group, weight.toFloat(), why, Regex(pattern, I), perDistinctMatch = true, maxWeight = max.toFloat())

        // -------------------------------------------------------------------- OTP
        private val digitGroup = Regex("(?<![\\w$£€.,#-])(\\d{4,8}|\\d{3}[- ]\\d{3})(?![\\w]|[.,]\\d)")
        private val prefixedCode = Regex("\\b[A-Z]{1,3}-(\\d{4,8})\\b")
        private val strongOtpWord = Regex(
            "\\b(otp|one[- ]?time|passcode|pass code|2fa|two[- ]factor|(verification|security|login|log-in|sign[- ]?in|authentication|auth|access|confirmation|reset|temporary|guard)\\s+(code|pin|password)|steam guard|verify)\\b",
            I,
        )
        private val weakOtpWord = Regex("\\b(code|pin|verification)\\b", I)
        private val otpContext = Regex(
            "\\b(is your|your .{0,25}code|enter|use|do not share|don'?t share|never share|expires?|valid (for|until)|will never ask|to (log|sign) in|to verify|to confirm)\\b",
            I,
        )
        private val notOtpCode = Regex("\\b(promo|coupon|discount|zip|area|country|postal|gift|referral|tracking|confirmation number|reservation|order|invoice|ticket|flight)\\s+(code|number|#)\\b", I)

        // -------------------------------------------------------------------- opt-out
        private val optOut = Regex(
            "\\b(reply|text|txt|send|msg)\\s+(stop|end|quit|cancel|unsubscribe)\\b|\\bstop\\s+(to|2)\\s+(opt|end|cancel|quit|unsubscribe|stop)|\\bunsubscribe\\b|\\bopt[- ]?out\\b|\\bmsg\\s*(&|and)\\s*data rates\\b|\\bmsg\\s*freq|\\bstop to end\\b|\\bstop to quit\\b",
            I,
        )

        // -------------------------------------------------------------------- scam rules (content)
        private val scamRules = listOf(
            r("pay", 1.5, "It demands payment of an unpaid toll, fine or balance", "\\b(unpaid|outstanding|overdue|past[- ]due)\\b.{0,40}\\b(toll|balance|invoice|ticket|fine|tax(es)?|violation|bill|parking)\\b"),
            r("pay", 1.2, "It mentions a toll, parking or traffic violation to pay", "\\b(toll|parking|traffic)\\b.{0,30}\\b(violation|ticket|invoice|balance|fine)\\b"),
            r("pay", 1.5, "It asks for a small delivery, handling or customs fee", "\\b(redelivery|re-delivery|handling|customs|delivery|shipping|release)\\s+fee\\b"),
            r("pay", 1.2, "It says a package is held because of an address problem", "\\b(incomplete|invalid|wrong|missing)\\s+address\\b|\\bon hold\\b.{0,30}\\b(address|package|parcel|delivery)\\b"),
            r("pay", 0.8, "It says a delivery failed and asks you to reschedule", "\\b(delivery attempt failed|could not be delivered|couldn'?t be delivered|unable to deliver)\\b"),
            r("pay", 0.8, "It threatens late fees or penalties", "\\b(late|additional)\\s+(fee|fees|penalt(y|ies))\\b|\\bavoid (a |any )?(fine|penalty|penalties)\\b"),
            r("acct", 1.3, "It says an account or card is locked, suspended or restricted", "\\b(account|card|profile|apple id|login|access|wallet|subscription)\\b.{0,60}\\b(locked|blocked|frozen|suspended|disabled|deactivated|terminated|closed|restricted|compromised|on hold|limited|expired)\\b"),
            r("acct", 1.0, "It asks you to verify your identity or account details", "\\bverify\\b.{0,30}\\b(identity|account|details|information|info)\\b|\\bconfirm your (identity|details|account|information)\\b"),
            r("acct", 0.6, "It talks about suspicious or unusual activity", "\\b(suspicious|unusual|unauthori[sz]ed)\\s+(activity|sign[- ]?in|login|charge|transaction)\\b"),
            r("acct", 0.9, "It asks you to update or restore login or billing details", "\\bsecure your account\\b|\\brestore (your )?access\\b|\\bkeep your password\\b|\\bupdate (your )?(billing|payment|account|card|details|information)\\b"),
            r("acct", 1.0, "It says your password is about to expire", "\\bpassword\\b.{0,30}\\b(expires?|expired|expiring)\\b"),
            r("gov", 1.2, "It claims to be the IRS or tax authority", "\\b(irs|internal revenue)\\b|\\b(tax refund|back taxes|unpaid tax(es)?|tax (debt|balance))\\b"),
            r("gov", 1.2, "It invokes Social Security", "\\bsocial security\\b|\\bssn\\b"),
            r("gov", 1.5, "It threatens arrest, a warrant or legal action", "\\b(arrest|warrant|legal action|lawsuit|prosecut\\w+|sheriff|court (date|order|summons))\\b"),
            r("gov", 1.0, "It claims to be the DMV or threatens a licence suspension", "\\b(dmv|department of motor vehicles|licen[cs]e suspension)\\b"),
            r("gov", 0.8, "It invokes Medicare or Medicaid", "\\bmedic(are|aid)\\b"),
            r("prize", 1.2, "It says you won something", "\\byou('ve| have)? (just )?won\\b|\\b(lucky )?winner\\b|\\byou are (the )?lucky\\b"),
            r("prize", 1.0, "It says you were selected for a reward", "\\b(selected|chosen) (to receive|for|as)\\b|\\byou have been (selected|chosen)\\b"),
            r("prize", 1.0, "It tells you to claim a prize", "\\bclaim (your|yours|it|now|the|this)\\b|\\bto claim\\b"),
            r("prize", 0.8, "It mentions a prize", "\\bprize\\b"),
            r("prize", 0.8, "It offers a gift card", "\\b(gift|shopping) ?cards?\\b|\\bgiving away\\b"),
            r("prize", 1.0, "It congratulates you on a prize", "\\bcongratulations\\b.{0,60}\\b(won|prize|reward|gift|iphone|selected|chosen)\\b"),
            r("invest", 0.8, "It pushes cryptocurrency or trading", "\\b(crypto(currency)?|usdt|bitcoin|btc|ethereum|forex|binary options|trading (bot|platform|signals?))\\b"),
            r("invest", 0.9, "It promises returns or passive income", "\\b(guaranteed|daily|weekly) (returns?|profits?|income)\\b|\\bpassive income\\b|\\bmentor\\b|\\binvestment (tip|opportunity)\\b|\\b(8|10|\\d+)x\\b.{0,20}\\b(month|week)\\b"),
            r("invest", 1.0, "It asks you to deposit money into a platform", "\\b(deposit|withdraw)\\b.{0,40}\\b(crypto|usdt|btc|platform|wallet)\\b|\\bdeposit \\$?\\d"),
            r("job", 1.5, "It promises big pay per day", "\\b(earn|make)\\b.{0,15}\\$?\\d[\\d,]*(-\\$?\\d[\\d,]*)?\\s*(/|per|a|each)?\\s*(day|daily|week|weekly|hour)\\b|\\$\\d[\\d,]*\\s*(per|a|/)\\s*day"),
            r("job", 1.0, "It offers an easy remote job", "\\b(no experience|work from home|remote (assistants?|jobs?|work)|part[- ]time (job|work)|online (customer )?(rater|assistant|job))\\b"),
            r("job", 0.5, "It mentions commissions", "\\bcommissions?\\b"),
            r("chat", 1.0, "It asks you to move to WhatsApp or Telegram", "\\b(whatsapp|telegram|wechat)\\b"),
            r("pay", 1.2, "It advertises an hourly, daily or weekly wage", "\\$\\d[\\d,]*(\\.\\d+)?\\s*(/|per|an?)\\s*(hour|hr|day|week)\\b"),
            r("job", 1.0, "It offers data entry, survey or rating work", "\\b(data entry|paid to take surveys|take surveys|rate products|like and review|online tasks?)\\b"),
            r("invest", 1.2, "It promises a daily, weekly or monthly percentage return", "\\b\\d+%?(-\\d+%?)?\\s*(daily|per day|weekly|a week|monthly|per month)\\b"),
            r("acct", 1.5, "It claims your computer or phone is infected or hacked", "\\b(virus|malware|infected|hacked)\\b.{0,40}\\b(computer|device|phone|account|pc)\\b"),
            r("call", 0.8, "It tells you to press a key to reach an agent", "\\bpress [19]\\b.{0,25}\\b(agent|speak|representative)\\b"),
            r("lure", 0.8, "It says it found your number by accident or through a friend", "\\bfound your number\\b|\\b(got|have) your number from\\b|\\byour friend gave me your number\\b|\\bdo you remember me\\b"),
            r("lure", 0.8, "It is a stranger looking for a friend or partner", "\\b(looking for|want|wanting) (a )?(good )?(friend|companion|relationship|partner)\\b|\\bbe friends\\b|\\b(widow|widower|deployed|oil rig)\\b"),
            r("lure", 0.8, "It asks for a quick favor", "\\bneed (some )?(help with )?a (quick |small )?favor\\b"),
            r("lure", 1.2, "It asks you to wire or send money to the sender", "\\b(wire|send|transfer|venmo|zelle)\\s+me\\s+\\$?\\d"),
            r("lure", 0.8, "It says the sender lost or broke their phone", "\\b(phone (fell|broke|cracked|died|is dead|got stolen)|lost my phone|broke my phone|texting from a friend)"),
            r("lure", 0.6, "It says it was a wrong number", "\\bwrong (number|person)\\b"),
            r("lure", 1.3, "It flatters you as a stranger", "\\byou (seem|sound|look) (very |so )?(nice|sweet|friendly|kind|lovely|beautiful|handsome)\\b|\\bcan we be friends\\b"),
            r("lure", 0.8, "It claims you met before", "\\bwe met\\b.{0,40}\\b(conference|last (week|month)|gym|party|event)\\b"),
            r("lure", 0.8, "It opens by asking if you are a certain person", "^\\s*(hi|hello|hey)[, ]+is this [a-z]+\\s*\\?"),
            r("lure", 2.2, "It says someone has a new number and needs money", "\\b(new|broke|lost|cracked)\\b.{0,40}\\b(phone|number)\\b.{0,160}\\b(send|need|transfer)\\b.{0,60}(money|\\$\\d|gift ?card|cash|venmo|zelle)"),
            r("lure", 0.7, "It says it cannot talk or be called", "\\b(don'?t call|can'?t talk|can'?t call)\\b"),
            r("lure", 1.5, "It asks you to buy or send gift cards", "\\b(buy|send|purchase|get|pick up|grab)\\b.{0,20}\\bgift ?cards?\\b|\\bgift ?cards?\\b.{0,30}\\b(send|buy|purchase)\\b"),
            r("lure", 2.0, "It asks if you are in a video or photo, a common malware lure", "\\bis (this|that) (you|u)\\b.{0,25}\\b(video|photo|pic|picture)\\b|\\b(you|u) in (this|that) (video|photo|pic)"),
            r("lure", 1.0, "It says there is a video or photo of you", "\\b(photo|video|pic|picture) of you\\b|\\bis (this|that) you\\s*\\?"),
            r("call", 1.2, "It says to call if the charge was not you", "\\b(if|was)\\b.{0,15}(this|it|that)?\\s*(was\\s*not|wasn'?t|is\\s*not|isn'?t|did\\s*not|didn'?t)\\b.{0,15}\\b(you|authori[sz]ed|your)\\b.{0,50}\\bcall\\b"),
            r("call", 0.8, "It tells you to call or contact a phone number", "\\b(call|contact|dial|phone)\\b.{0,30}\\b\\d{3}[-. ]?\\d{4}\\b"),
            r("call", 0.8, "It tells you to call straight away", "\\bcall\\b.{0,15}\\b(now|immediately|right away|within)\\b"),
            r("urgent", 0.7, "It uses pressure wording", "\\b(urgent(ly)?|immediately|right away|final (notice|warning|reminder)|last chance|act now|expires? (today|tonight|soon)|within \\d+ ?(hours?|hrs?|minutes?|mins?)|before (it'?s|it is) too late|you will be (arrested|fined|charged))\\b"),
            r("urgent", 0.7, "It threatens consequences if you do not act", "\\bor (your|the) [a-z ]{0,25}(will be |is )?(suspended|terminated|closed|cancell?ed|returned|forfeited|deactivated|disconnected)\\b|\\bto avoid (suspension|termination|closure|cancell?ation|arrest)\\b"),
        )

        private val businessPrefix = Regex("^\\s*\\[?[A-Z][A-Za-z0-9&.' -]{2,28}\\]?\\s*[:\\]]\\s")

        // -------------------------------------------------------------------- promo rules
        private val promoRules = listOf(
            r("offer", 1.0, "It advertises a discount or sale", "\\b\\d{1,2}\\s?%\\s*off\\b|\\$\\d+(\\.\\d\\d)? off\\b|\\b\\d{1,2}\\s?%\\s*discount\\b|\\b(sale|clearance|bogo|buy one,? get one|flash sale|black friday|cyber monday|doorbusters?)\\b|\\bsave (up to )?\\$?\\d+"),
            r("offer", 1.0, "It offers something free", "\\bfree (shipping|delivery|gift|trial|sample|drink|side|appetizer|dessert|toppings?|phone|samples|treat|pizza|coffee|item)\\b"),
            r("offer", 1.0, "It has a promo code or coupon", "\\b(coupon|promo(tion(al)?)? code|use code|discount|voucher)\\b|\\bcode [A-Z]{3,}\\d{1,3}\\b"),
            r("offer", 1.0, "It pushes an exclusive or limited-time offer", "\\bexclusive\\b|\\blimited[- ]time\\b|\\blimited time\\b|\\bends (tonight|today|soon|sunday|monday)\\b|\\b(today|tonight|this weekend|this week|this month) only\\b|\\bfinal hours\\b|\\blast chance\\b|\\boffer ends\\b|\\bno commitment\\b"),
            many("offer", 0.6, 1.8, "It tells you to shop, order or redeem, or talks about rewards", "\\b(shop now|shop|order now|order today|book now|redeem|new arrivals|grand opening|giveaway|enter to win|sweepstakes|free samples|rewards?|points|vip|members?|bonus|perks|cart|checkout|doorbusters?|collection|refer(-a-friend| a friend)|join today|special)\\b"),
            r("offer", 0.8, "It pitches a plan upgrade or bundle", "\\b(upgrade|bundle|get a quote|free trial)\\b"),
            r("civic", 1.0, "It is a fundraising or campaign message", "\\bpaid for by\\b|\\bchip in\\b|\\bdonat(e|ion|ions)\\b|\\bour campaign\\b|\\bcandidate\\b|\\b(city council|senate|congress|mayor|governor)\\b|\\bgift of \\$"),
            r("civic", 1.0, "It is about voting or an election", "\\b(polling place|early voting|vote|voting|ballot|election)\\b"),
            r("civic", 1.0, "It asks you to volunteer, or take a poll or survey", "\\b(volunteer|phone ?bank|rally|petition|poll|survey)\\b"),
        )

        // -------------------------------------------------------------------- transactional rules
        private val txnRules = listOf(
            r("txn", 1.0, "It reads like an order or delivery update", "\\b(your )?order\\b.{0,20}(#?\\d|has |was |is |shipped|delivered|ready|confirmed)|\\b(shipped|out for delivery|delivered to|tracking|your (driver|ride|courier)|arriving in|delivery (update|window)|arriv(e|es|ing) (today|tomorrow|between)|pickup counter|ready for pickup|parcel|package)\\b"),
            r("txn", 1.0, "It reads like an appointment, booking or travel notice", "\\b(appointment|reservation|reminder|reschedule|check-in|boarding|flight|gate|delayed|closure|prescription|refill|your visit|your table)\\b"),
            r("txn", 1.0, "It reads like an account or payment notice", "\\b(purchase of|payment|deposit|transaction|charged|balance|statement|bill|receipt|transfer|withdrawal|autopay|card ending|account ending|ending (in )?\\d{4}|ride receipt|sent you \\$)\\b"),
            r("txn", 1.0, "It asks a fraud-alert or confirmation question", "\\b(fraud alert|did you (attempt|make|authorize)|not you\\??|reply (yes|no|y|n|c|r|1|2)\\b)"),
        )

        // -------------------------------------------------------------------- work rules
        private val workRules = listOf(
            r("work", 1.6, "It uses on-call, incident or engineering vocabulary", "\\b(on[- ]?call|pager(duty)?|opsgenie|incident (bridge|call|commander|channel)?|sev ?-?[0-4]|postmortem|runbook|standup|stand-up|sprint (planning|review|demo|retro|goals|board|backlog)|(this|next|last) sprint|retro(spective)?|rollback|roll back|hotfix|deploy(ed|ment|ing|s)?|staging|pull request|code review|pr ?#?\\d+|jira|confluence|build (failed|passed|broke)|pipeline|ci/cd|merge(d)? (to|into)|on main|prod(uction)? (deploy|issue|incident|outage|error))\\b"),
            r("work", 1.6, "It is about time off, shifts or timesheets", "\\b(pto|ooo|out of office|timesheet|payroll|shift (swap|cover|change|reminder|schedule)|cover (my|a|the) shift|(your|my|the|closing|opening|morning|night|evening|weekend) shift|all[- ]hands|1:1|one[- ]on[- ]one|conf(erence)? room|kickoff|kick-off|sow|statement of work)\\b"),
            r("work", 1.6, "It uses office or operations vocabulary", "\\b(vpn|help ?desk|company portal|expense report|nda|board meeting|release branch|server.{0,15}(down|outage|crash\\w*)|migration|front desk|final round|contract role|staffing|headcount|org chart|q[1-4] (planning|review|goals)|offsite|planning offsite|dispatch|truck \\d+|work order)\\b"),
            r("work", 1.0, "It mentions a shift", "\\bshifts?\\b"),
            many("work", 0.7, 2.8, "It uses meeting, project and client vocabulary", "\\b(meeting|sync|zoom|teams|google meet|calendar|invite|agenda|deck|slides|roadmap|quarterly|kpi|eod|eow|stakeholders?|vendor|contract|deliverables?|proposal|budget|manager|onboarding|walkthrough|project|deadline|ticket|escalat\\w+|release|outage|latency|error rate|api|clients?|customers?|numbers|review|team|demo|qa|branch|account|legal|board|nda|vendor|ticket|report|channel|rebooting|server|expense|colleagues?|coworkers?|front desk)\\b"),
            r("work", 1.2, "It reads like a recruiter or interview message", "\\b(recruiter|recruiting|talent|hiring|job opening|open role|interview|offer letter)\\b"),
            many("work", 0.5, 1.0, "It mentions a role, profile or opening", "\\b(your profile|resume|career|opportunity|opening|position|role|engineer|developer|staff|senior)\\b"),
        )

        // -------------------------------------------------------------------- spam rules
        private val spamRules = listOf(
            r("spam", 1.5, "It pitches a loan or credit offer", "\\b(pre-?approved|pre-?qualified|no credit check|debt relief|(personal|business|payday|quick|fast|instant|student|home|cash) loans?|loan (offer|approval|approved|up to)|loans? (up to|of \\$)|get (a )?loan|apply (for )?(a )?loan|fast approval)\\b"),
            r("spam", 0.5, "It mentions a loan", "\\bloans?\\b"),
            r("spam", 1.5, "It is about a vehicle warranty", "\\b(extended warranty|(car|vehicle|auto)'?s?\\s+(extended\\s+)?warranty)\\b"),
            r("spam", 1.5, "It is about casino offers", "\\b(casino|free spins|jackpot|deposit bonus)\\b"),
            r("spam", 0.8, "It mentions betting, slots or poker", "\\b(betting|slots|poker|sportsbook)\\b"),
            r("spam", 1.5, "It is a cold home-buying, car-buying or solar pitch", "\\b(sell(ing)? your (home|house)|thinking (about|of) selling|real estate agent|cash offer|we pay cash|we buy houses|homeowners?|solar|mortgage|refinanc\\w+|roof(ing)? inspections?|(looking|want) to buy (cars?|houses?|homes?|properties))\\b"),
            many("spam", 0.6, 1.8, "It is a marketing or SEO pitch", "\\b(seo|search engine|website traffic|first page|free quote|guaranteed ranking|backlinks)\\b"),
            r("spam", 1.5, "It is adult or dating bait", "\\b(hot (singles|girls)|singles in your area|meet (local )?(singles|girls|women)|local dates|18\\+ only|see my photos|join free)\\b"),
            many("spam", 0.6, 1.2, "It uses flirty or adult wording", "\\b(sexy|handsome|lonely|dating|flirt\\w*|chat with me|want to chat)\\b"),
            r("spam", 1.5, "It sells pills, supplements or weight loss", "\\b(viagra|cialis|no prescription|lose \\d+ (pounds|lbs|kg)|diet pills|miracle (cure|pill))\\b"),
            r("spam", 0.6, "It mentions supplements or weight loss", "\\b(weight ?loss|supplements?)\\b"),
            r("spam", 0.8, "It mentions pills or meds", "\\b(pills|meds|cheap meds)\\b"),
            r("spam", 1.5, "It promises easy money", "\\b(make money|earn money|get rich|passive income|be your own boss|work from home|get paid to|make up to \\$?\\d+|from your couch|trading signals|accuracy guaranteed)\\b"),
            r("spam", 1.5, "It pumps a crypto token", "\\b(100x|moonshot|presale|airdrop|pump and dump)\\b|\\$[A-Z]{3,10}\\b|\\bpump(ing|ed)?\\b.{0,20}\\b(token|coin|crypto)"),
            r("spam", 1.5, "It uses classic premium-rate or prize-draw spam wording", "\\bfree (entry|cruise|vacation|trip|ringtone|msg|mobile|tones?)\\b|\\b(txt|text|send|reply)\\b.{0,30}\\bto\\s+\\d{4,6}\\b|\\b(call|ring)\\s+(on\\s+)?0\\d{9,10}\\b|\\b09\\d{8,9}\\b|\\b0[89]00[ ]?\\d{3}[ ]?\\d{4}\\b|\\bstd ?txt ?rate\\b|\\bt&c'?s?( apply)?\\b|\\bringtones?\\b|\\bto be won\\b|\\bwin\\b.{0,25}(£|cash|prize|vouchers?)|\\b\\d{2,3}p(/| per )(sms|msg|txt|min|week)\\b"),
            r("spam", 0.8, "It tells you to press a key or speak to an agent", "\\bpress [19]\\b|\\bspeak (to|with) an? (agent|representative)\\b"),
            r("spam", 0.5, "It uses a generic hard-sell phrase", "\\b(click here|apply now|limited spots|order today)\\b"),
            r("spam", 0.5, "It quotes a pound-sterling amount", "£\\d"),
        )

        private val solicitCta = Regex("\\b(call( us| now)?|visit|reply|click|apply|book|text us|contact us|details|sign up|join|order)\\b", I)
        private val solicitWords = Regex(
            "\\b(free|offers?|discounts?|deals?|prices?|cheap|guaranteed?|consultation|quote|estimate|evaluation|no obligation|limited|earn|income|followers|" +
                "investment|profits?|winning|approved|rates?|credit|debt|insurance|coverage|warranty|buyers?|cash|low|savings?|exclusive|private)\\b",
            I,
        )

        private val hitCap = 3.2f

        private fun scan(rules: List<Rule>, text: String, cap: Float = hitCap): List<Signal> {
            var total = 0f
            val out = ArrayList<Signal>(3)
            for (rule in rules) {
                val w = if (rule.perDistinctMatch) {
                    minOf(rule.maxWeight, rule.weight * rule.re.findAll(text).map { it.value.lowercase() }.toSet().size)
                } else if (rule.re.containsMatchIn(text)) rule.weight else 0f
                if (w > 0f) {
                    out += Signal(rule.group, w, rule.why)
                    total += w
                    if (total >= cap) break
                }
            }
            return out
        }

        private val alnumCode = Regex("\\b(?:code|password|passcode|pin)\\s*(?:is|:)\\s*([A-Za-z0-9]{4,8})\\b", I)

        private fun detectOtp(text: String): OtpHit? {
            val digits = digitGroup.find(text)?.value ?: prefixedCode.find(text)?.groupValues?.get(1)
                ?: alnumCode.find(text)?.groupValues?.get(1)?.takeIf { c -> c.any { it.isDigit() } && c.any { it.isLetter() } }
                ?: return null
            if (notOtpCode.containsMatchIn(text) && !strongOtpWord.containsMatchIn(text)) return null
            val strong = strongOtpWord.find(text)
            if (strong != null) return OtpHit(digits.count { it.isDigit() }, true, strong.value.lowercase())
            val weak = weakOtpWord.find(text) ?: return null
            if (!otpContext.containsMatchIn(text)) return null
            return OtpHit(digits.count { it.isDigit() }, false, weak.value.lowercase())
        }

        fun analyze(text: String, sender: SenderProfile): TextSignals {
            val links = LinkFeatures.extract(text)
            val spamSignals = ArrayList(scan(spamRules, text))
            val scam = ArrayList<Signal>()
            scam += scan(scamRules, text, cap = 6f).map { sg ->
                // "If this wasn't you, call 1-800-..." is the standard wording of a real bank alert from a short code or
                // named sender, so a callback request only counts at full weight from an ordinary phone number.
                if (sg.group == "call" && sender.isAutomated) sg.copy(weight = sg.weight * 0.3f) else sg
            }
            // Link evidence, strongest link wins per kind.
            val kinds = links.flatMap { it.kinds }.toSet()
            var linkTotal = 0f
            fun link(k: Kind, w: Float, why: String) {
                if (k in kinds && linkTotal < 3f) { scam += Signal("link", w, why); linkTotal += w }
            }
            link(Kind.IP_HOST, 2.0f, "It links to a bare IP address instead of a website name")
            link(Kind.PUNYCODE, 2.0f, "It links to a look-alike (punycode) address")
            link(Kind.BRAND_IMPERSONATION, 2.0f, "It links to a site that uses a well-known brand name but is not that brand's site")
            link(Kind.LOOKALIKE, 1.5f, "Its link looks like a fake login or support page (hyphenated words such as secure, verify, login)")
            link(Kind.ODD_TLD, 1.3f, "Its link ends in a cheap, often abused domain suffix")
            link(Kind.SHORTENER, 1.0f, "It hides its destination behind a shortened link")
            link(Kind.PLAIN_HTTP, 0.4f, "Its link is not encrypted (http)")
            if (sender.kind == SenderKind.EMAIL_GATEWAY) scam += Signal("sender", 0.8f, "It was sent from an email-to-text gateway, not a normal number")
            if (sender.kind == SenderKind.INTL_PHONE) scam += Signal("sender", 0.5f, "It came from a number in another country")
            if (!sender.isAutomated && sender.kind != SenderKind.UNKNOWN &&
                businessPrefix.containsMatchIn(text) && (links.isNotEmpty() || Regex("\\$\\d|\\b\\d{3}-\\d{4}\\b").containsMatchIn(text))
            ) {
                scam += Signal("sender", 1.0f, "It speaks as a company or agency, but came from an ordinary phone number")
            }
            // Generic sales-pitch structure: several commercial words AND a call to act (call, visit, reply, click, a link).
            // Catches pitches the keyword lists above do not know, but never fires without both halves.
            if (solicitCta.containsMatchIn(text) || links.isNotEmpty()) {
                val n = solicitWords.findAll(text).map { it.value.lowercase() }.toSet().size
                if (n >= 2) spamSignals += Signal("spam", minOf(1.5f, 0.5f * n), "It reads like a sales pitch: commercial wording plus a call to act")
            }
            val opt = optOut.containsMatchIn(text)

            return TextSignals(
                text = text,
                links = links,
                otp = detectOtp(text),
                scam = scam,
                promo = scan(promoRules, text),
                work = scan(workRules, text),
                txn = scan(txnRules, text),
                spam = spamSignals,
                hasOptOut = opt,
            )
        }
    }
}
