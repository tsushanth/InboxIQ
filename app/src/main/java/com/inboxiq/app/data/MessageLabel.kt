package com.inboxiq.app.data

/** Fixed v1 taxonomy. Keep in sync with the classifier's output head. */
enum class MessageLabel {
    PERSONAL,
    WORK,
    PROMO,
    OTP_2FA,
    /** Bank, payment, bill, order, delivery, appointment and ticket notices from automated senders (docs/TRANSACTIONS.md). */
    TRANSACTION,
    SPAM,
    SCAM,
    UNLABELED,
}
