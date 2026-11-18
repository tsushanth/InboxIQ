package com.inboxiq.app.sms

import android.app.Activity
import android.telephony.SmsManager

/**
 * What SmsManager's own download result means, and how long a download may keep being retried.
 *
 * A carrier's content-location link for an incoming MMS expires after about two to three days, so retrying
 * past [MAX_AGE_MS] is pointless, and a bounded number of attempts keeps a dead link from being hammered.
 * Pure logic (no Android state) so it is unit-testable on the plain JVM.
 */
object MmsDownloadPolicy {
    enum class Outcome { SUCCESS, TRANSIENT, PERMANENT }

    /** Attempts per provider row, counting the first. With [delayMillis] they span about 44 hours. */
    const val MAX_ATTEMPTS = 16

    /** Stop for good this long after the notification arrived (the carrier link is gone by then). */
    const val MAX_AGE_MS = 48L * 60 * 60 * 1000

    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 6L * 60 * 60 * 1000

    /** After RESULT_OK, wait this long before treating a row that is still undownloaded as needing another try. */
    const val SUCCESS_RECHECK_MS = 5L * 60 * 1000

    /** How long a triggered download counts as still running; a second trigger inside it is skipped. */
    const val IN_FLIGHT_MS = 3L * 60 * 1000

    /** Delay before attempt number [attemptsSoFar] + 1: 30 s, 1 min, 2 min, ... doubling, capped at 6 h. */
    fun delayMillis(attemptsSoFar: Int): Long {
        val shift = (attemptsSoFar - 1).coerceIn(0, 30)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    /** True when a row that has had [attempts] tries since [firstSeenAt] may be tried again at [now]. */
    fun mayRetry(attempts: Int, firstSeenAt: Long, now: Long): Boolean =
        attempts < MAX_ATTEMPTS && now - firstSeenAt < MAX_AGE_MS

    /**
     * Classifies the result code of the download broadcast. [httpStatus] is the carrier's HTTP status when the
     * platform reported one (0 when absent); 404 and 410 mean the link is gone, which no retry can fix.
     */
    fun classify(resultCode: Int, httpStatus: Int = 0): Outcome = when (resultCode) {
        Activity.RESULT_OK -> Outcome.SUCCESS
        // The carrier sent bytes that are not an MMS retrieve confirmation: fetching again returns the same bytes.
        CODE_PARSE_FAILED -> Outcome.PERMANENT
        SmsManager.MMS_ERROR_HTTP_FAILURE ->
            if (httpStatus == 404 || httpStatus == 410) Outcome.PERMANENT else Outcome.TRANSIENT
        // Nothing about the phone's setup will change on its own: the subscription is gone or inactive,
        // the carrier disabled MMS, or the platform says the MMS configuration is unusable.
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
        SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
        SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION,
        SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER -> Outcome.PERMANENT
        // Everything else can clear up: no data at that moment, a carrier or APN hiccup, throttling, mobile data
        // switched off for a while. An unknown code is treated the same way and bounded by the attempt cap.
        else -> Outcome.TRANSIENT
    }

    /** The SDK's name for a result code (RESULT_OK or MMS_ERROR_*), for logs. */
    fun codeName(resultCode: Int): String = when (resultCode) {
        Activity.RESULT_OK -> "RESULT_OK"
        SmsManager.MMS_ERROR_UNSPECIFIED -> "MMS_ERROR_UNSPECIFIED"
        SmsManager.MMS_ERROR_INVALID_APN -> "MMS_ERROR_INVALID_APN"
        SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "MMS_ERROR_UNABLE_CONNECT_MMS"
        SmsManager.MMS_ERROR_HTTP_FAILURE -> "MMS_ERROR_HTTP_FAILURE"
        SmsManager.MMS_ERROR_IO_ERROR -> "MMS_ERROR_IO_ERROR"
        SmsManager.MMS_ERROR_RETRY -> "MMS_ERROR_RETRY"
        SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "MMS_ERROR_CONFIGURATION_ERROR"
        SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "MMS_ERROR_NO_DATA_NETWORK"
        SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID -> "MMS_ERROR_INVALID_SUBSCRIPTION_ID"
        SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION -> "MMS_ERROR_INACTIVE_SUBSCRIPTION"
        SmsManager.MMS_ERROR_DATA_DISABLED -> "MMS_ERROR_DATA_DISABLED"
        SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER -> "MMS_ERROR_MMS_DISABLED_BY_CARRIER"
        CODE_TRIGGER_FAILED -> "TRIGGER_FAILED"
        CODE_PARSE_FAILED -> "PDU_PARSE_FAILED"
        CODE_PERSIST_FAILED -> "PDU_PERSIST_FAILED"
        CODE_EMPTY_DOWNLOAD -> "EMPTY_DOWNLOAD"
        else -> "UNKNOWN_$resultCode"
    }

    /** Result code recorded when the download could not even be handed to the platform (an exception). */
    const val CODE_TRIGGER_FAILED = -1000

    /** The platform reported RESULT_OK but the downloaded bytes are not a parseable m-retrieve-conf (permanent). */
    const val CODE_PARSE_FAILED = -1001

    /** The PDU parsed but writing it into the system MMS store failed (transient). */
    const val CODE_PERSIST_FAILED = -1002

    /** RESULT_OK but the download file is missing or empty (transient). */
    const val CODE_EMPTY_DOWNLOAD = -1003
}
