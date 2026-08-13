package com.inboxiq.app.sms

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Best-effort E.164 normalization for any address entering the app — from a contact pick,
 * from platform backfill (content://sms, content://mms), or from an incoming-message
 * receiver. Confirmed live that this matters: some of the platform's own historical SMS rows
 * store addresses without a country code, and without normalizing on the way in, those
 * silently created a second, disconnected thread for a contact whose other messages were
 * already stored in E.164 — repeatedly, since backfill re-reads platform history on every
 * launch. Short codes and non-phone senders (e.g. "AMAZON") pass through unchanged.
 */
object PhoneNumberNormalizer {
    fun normalize(context: Context, raw: String): String {
        val countryIso = (context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
            ?.networkCountryIso
            ?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return PhoneNumberUtils.formatNumberToE164(raw, countryIso.uppercase(Locale.US)) ?: raw
    }
}
