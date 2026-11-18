package com.inboxiq.app.inbox

import androidx.annotation.StringRes
import com.inboxiq.app.R
import com.inboxiq.app.data.MessageLabel
import java.net.URI

enum class LinkRisk { NONE, CAUTION, DANGER }

/** One reason a link was flagged: a string resource, plus the single value it mentions (a brand or an ending) when it has one. */
data class LinkReason(@StringRes val res: Int, val arg: String? = null)

data class LinkAssessment(val url: String, val host: String, val risk: LinkRisk, val reasons: List<LinkReason>)

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
        val reasons = mutableListOf<LinkReason>()
        var danger = false

        if (uri?.rawUserInfo != null || withScheme.substringAfter("://").substringBefore('/').contains('@')) {
            reasons += LinkReason(R.string.link_reason_at_sign); danger = true
        }
        if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            reasons += LinkReason(R.string.link_reason_ip_address); danger = true
        }
        if (host.split('.').any { it.startsWith("xn--") }) {
            reasons += LinkReason(R.string.link_reason_punycode); danger = true
        }
        val registrable = registrable(host)
        brands.forEach { (brand, real) ->
            if (host.contains(brand) && registrable !in real) {
                reasons += LinkReason(R.string.link_reason_brand, brand.replaceFirstChar { it.uppercase() }); danger = true
            }
        }
        if (registrable in shorteners || host in shorteners) reasons += LinkReason(R.string.link_reason_shortened)
        if (host.substringAfterLast('.') in riskyTlds) reasons += LinkReason(R.string.link_reason_risky_ending, host.substringAfterLast('.'))
        if (host.split('.').size >= 5 || host.count { it == '-' } >= 3) reasons += LinkReason(R.string.link_reason_long_name)
        if (withScheme.startsWith("http://")) reasons += LinkReason(R.string.link_reason_insecure)
        if (label == MessageLabel.SCAM) { reasons += LinkReason(R.string.link_reason_filed_scam); danger = true }
        else if (label == MessageLabel.SPAM) reasons += LinkReason(R.string.link_reason_filed_spam)

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
