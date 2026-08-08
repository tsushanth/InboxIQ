package com.inboxiq.app.data

/** Fixed v1 taxonomy. Keep in sync with the classifier's output head. */
enum class MessageLabel {
    PERSONAL,
    WORK,
    PROMO,
    OTP_2FA,
    SPAM,
    SCAM,
    UNLABELED,
}
