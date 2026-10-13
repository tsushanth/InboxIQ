package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageLabel
import java.net.URI

enum class LinkRisk { NONE, CAUTION, DANGER }

data class LinkAssessment(val url: String, val host: String, val risk: LinkRisk, val reasons: List<String>)

/**
 * Offline, heuristic link check. It cannot know whether a site is malicious (no lookups happen,
 * by design); it only flags well-known tricks, so "no warning" never means "safe".
 */
object LinkSafety {
    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "cutt.ly", "rb.gy", "shorturl.at", "tiny.cc", "rebrand.ly", "buff.ly",
    )
    private val riskyTlds = setOf("top", "xyz", "icu", "click", "gq", "tk", "cf", "ml", "ga", "cyou", "rest", "monster", "zip", "mov", "support", "work")
    private val twoPartSuffixes = setOf("co.uk", "org.uk", "com.au", "co.in", "co.jp", "com.br", "co.nz")

    /** brand keyword -> the registrable domains that really belong to it */
    private val brands = mapOf(
        "paypal" to setOf("paypal.com"),
        "usps" to setOf("usps.com"),
        "fedex" to setOf("fedex.com"),
        "dhl" to setOf("dhl.com"),
        "ups" to setOf("ups.com"),
        "amazon" to setOf("amazon.com", "amazon.co.uk", "amzn.to", "amazon.ca"),
        "apple" to setOf("apple.com", "icloud.com"),
        "netflix" to setOf("netflix.com"),
        "chase" to setOf("chase.com"),
        "wellsfargo" to setOf("wellsfargo.com"),
        "bankofamerica" to setOf("bankofamerica.com"),
        "irs" to setOf("irs.gov"),
        "venmo" to setOf("venmo.com"),
        "coinbase" to setOf("coinbase.com"),
    )

    fun analyze(rawUrl: String, label: MessageLabel): LinkAssessment {
        val withScheme = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
        val uri = runCatching { URI(withScheme) }.getOrNull()
        val host = (uri?.host ?: withScheme.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':'))
            .lowercase().trimEnd('.')
        val reasons = mutableListOf<String>()
        var danger = false

        if (uri?.rawUserInfo != null || withScheme.substringAfter("://").substringBefore('/').contains('@')) {
            reasons += "The address contains an @, a trick that hides the real site"; danger = true
        }
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            reasons += "It points to a bare IP address instead of a website name"; danger = true
        }
        if (host.split('.').any { it.startsWith("xn--") }) {
            reasons += "The name uses look-alike characters (punycode)"; danger = true
        }
        val registrable = registrable(host)
        brands.forEach { (brand, real) ->
            if (host.contains(brand) && registrable !in real) {
                reasons += "It mentions ${brand.replaceFirstChar { it.uppercase() }} but is not their site"; danger = true
            }
        }
        if (registrable in shorteners || host in shorteners) reasons += "It is a shortened link, so the destination is hidden"
        if (host.substringAfterLast('.') in riskyTlds) reasons += "Its ending (.${host.substringAfterLast('.')}) is common in throwaway scam sites"
        if (host.split('.').size >= 5 || host.count { it == '-' } >= 3) reasons += "The name is unusually long and chained together"
        if (withScheme.startsWith("http://")) reasons += "It does not use a secure connection"
        if (label == MessageLabel.SCAM) { reasons += "InboxIQ filed this message as a scam"; danger = true }
        else if (label == MessageLabel.SPAM) reasons += "InboxIQ filed this message as spam"

        val risk = when {
            danger -> LinkRisk.DANGER
            reasons.isNotEmpty() -> LinkRisk.CAUTION
            else -> LinkRisk.NONE
        }
        return LinkAssessment(withScheme, host, risk, reasons)
    }

    fun registrable(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (lastTwo in twoPartSuffixes) parts.takeLast(3).joinToString(".") else lastTwo
    }
}
