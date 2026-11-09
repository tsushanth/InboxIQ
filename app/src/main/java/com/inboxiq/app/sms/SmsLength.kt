package com.inboxiq.app.sms

/**
 * How many SMS segments a text costs, the way the radio stack counts them (3GPP TS 23.038 / 23.040).
 *
 * A text is sent in the 7-bit GSM alphabet only if EVERY character is in it (or its extension table,
 * where each character takes two septets); a single character outside it, such as an emoji, a backtick
 * or a curly quote, switches the whole text to UCS-2 with a far smaller limit. Pure Kotlin so it can be
 * tested without a device; the unit tests compare it with the platform's own `SmsMessage.calculateLength`.
 */
object SmsLength {
    enum class Encoding { GSM_7BIT, UCS2 }

    /** [units] are septets (GSM) or UTF-16 code units (UCS-2). */
    data class Count(val encoding: Encoding, val units: Int, val segments: Int)

    const val GSM_SINGLE = 160
    const val GSM_MULTI = 153
    const val UCS2_SINGLE = 70
    const val UCS2_MULTI = 67

    private const val BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Characters reached through the escape code: two septets each. */
    private const val EXTENSION = "\u000C^{}\\[~]|€"

    private val basic: Set<Char> = BASIC.filter { it != '\u001B' }.toSet()
    private val extension: Set<Char> = EXTENSION.toSet()

    fun isGsm7(c: Char): Boolean = c in basic || c in extension

    fun of(text: String): Count {
        if (text.all(::isGsm7)) {
            val septets = text.length + text.count { it in extension }
            return Count(Encoding.GSM_7BIT, septets, segments(septets, GSM_SINGLE, GSM_MULTI))
        }
        val units = text.length
        return Count(Encoding.UCS2, units, segments(units, UCS2_SINGLE, UCS2_MULTI))
    }

    private fun segments(units: Int, single: Int, multi: Int): Int = when {
        units == 0 -> 0
        units <= single -> 1
        else -> (units + multi - 1) / multi
    }
}
