package com.inboxiq.app.classify

/**
 * Offline URL features for triage. Nothing is fetched or resolved; this only reads how a link is
 * written. (inbox/LinkSafety does the same kind of inspection for the in-thread warning chip; this
 * copy exists so the classifier has no dependency on UI code and can run in plain JVM tests.)
 */
object LinkFeatures {
    enum class Kind { SHORTENER, IP_HOST, PUNYCODE, BRAND_IMPERSONATION, LOOKALIKE, ODD_TLD, PLAIN_HTTP }

    data class Link(val raw: String, val host: String, val kinds: Set<Kind>)

    private val tlds = "com|net|org|io|co|me|ly|gl|to|gg|app|dev|info|biz|us|uk|gov|edu|top|xyz|click|icu|vip|cc|live|shop|buzz|help|" +
        "cfd|sbs|cyou|rest|monster|work|support|link|online|site|store|club|tk|ml|ga|cf|gq|ru|cn|in|ws|su|pw|bond|quest|zip"

    private val shortenerAlternation = "bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|is\\.gd|ow\\.ly|cutt\\.ly|rb\\.gy|shorturl\\.at|tiny\\.cc|" +
        "rebrand\\.ly|buff\\.ly|t\\.ly|bit\\.do|v\\.gd|s\\.id|lnkd\\.in|qr\\.ae|tr\\.im"

    private val urlRegex = Regex(
        "(?:https?://|www\\.)[^\\s<>\"']+|\\b(?:$shortenerAlternation)\\b(?:/[^\\s<>\"']*)?|" +
            "\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:$tlds)\\b(?::\\d+)?(?:/[^\\s<>\"']*)?",
        RegexOption.IGNORE_CASE,
    )
    private val ipRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc",
        "rebrand.ly", "buff.ly", "t.ly", "bit.do", "v.gd", "s.id", "lnkd.in", "shorturl.com", "qr.ae", "tr.im",
    )
    private val oddTlds = setOf(
        "top", "xyz", "click", "icu", "vip", "cc", "live", "shop", "buzz", "help", "cfd", "sbs", "cyou", "rest",
        "monster", "work", "support", "tk", "ml", "ga", "cf", "gq", "ws", "su", "pw", "bond", "quest", "zip", "info", "link",
    )
    private val lureWords = setOf(
        "verify", "secure", "security", "login", "signin", "account", "update", "support", "billing", "pay", "payment",
        "refund", "claim", "track", "tracking", "redeliver", "redelivery", "delivery", "toll", "alert", "auth", "unlock", "help", "service",
    )
    private val twoPartSuffixes = setOf("co.uk", "org.uk", "com.au", "co.in", "co.jp", "com.br", "co.nz")

    /** brand token -> registrable domains that really belong to it */
    private val brands = mapOf(
        "usps" to setOf("usps.com"), "fedex" to setOf("fedex.com"), "ups" to setOf("ups.com"), "dhl" to setOf("dhl.com"),
        "amazon" to setOf("amazon.com", "amazon.ca", "amazon.co.uk", "amzn.to"),
        "paypal" to setOf("paypal.com"), "apple" to setOf("apple.com", "icloud.com"), "icloud" to setOf("icloud.com"),
        "netflix" to setOf("netflix.com"), "chase" to setOf("chase.com"), "wellsfargo" to setOf("wellsfargo.com"),
        "bankofamerica" to setOf("bankofamerica.com"), "citi" to setOf("citi.com"), "irs" to setOf("irs.gov"),
        "ezpass" to setOf("e-zpassny.com", "ezpass.com"), "coinbase" to setOf("coinbase.com"), "venmo" to setOf("venmo.com"),
        "zelle" to setOf("zellepay.com"), "walmart" to setOf("walmart.com"), "microsoft" to setOf("microsoft.com"),
        "google" to setOf("google.com"), "facebook" to setOf("facebook.com"), "instagram" to setOf("instagram.com"),
        "whatsapp" to setOf("whatsapp.com", "wa.me"),
    )

    fun extract(text: String): List<Link> {
        val out = ArrayList<Link>(2)
        for (m in urlRegex.findAll(text)) {
            val raw = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
            if (raw.length < 4) continue
            out += analyze(raw)
        }
        return out
    }

    fun analyze(raw: String): Link {
        val plainHttp = raw.startsWith("http://", ignoreCase = true)
        val host = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').substringBefore(':').lowercase().trimEnd('.')
        val kinds = LinkedHashSet<Kind>()
        val labels = host.split('.')
        val registrable = registrable(labels)
        val tld = labels.last()
        when {
            ipRegex.matches(host) -> kinds += Kind.IP_HOST
            else -> {
                if (registrable in shorteners) kinds += Kind.SHORTENER
                if (labels.any { it.startsWith("xn--") }) kinds += Kind.PUNYCODE
                if (tld in oddTlds) kinds += Kind.ODD_TLD
                val tokens = labels.dropLast(1).flatMap { it.split('-') }
                val brandHit = brands.entries.firstOrNull { (k, real) -> k in tokens && registrable !in real }
                if (brandHit != null) kinds += Kind.BRAND_IMPERSONATION
                val hyphenated = labels.dropLast(1).filter { '-' in it }
                if (hyphenated.any { l -> l.split('-').any { it in lureWords } } || labels.count { '-' in it } >= 3) kinds += Kind.LOOKALIKE
            }
        }
        if (plainHttp && Kind.SHORTENER !in kinds) kinds += Kind.PLAIN_HTTP
        return Link(raw, host, kinds)
    }

    private fun registrable(labels: List<String>): String {
        if (labels.size <= 2) return labels.joinToString(".")
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in twoPartSuffixes) labels.takeLast(3).joinToString(".") else last2
    }
}
