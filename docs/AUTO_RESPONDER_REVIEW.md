# Auto-reply when away: release-readiness review (0.5.1)

Scope: `autoreply/*`, the SMS_DELIVER hook, `SmsSender`, the settings and log screens, and their tests, read as a Play SMS-policy
reviewer and a carrier would. Worst outcome considered: many unwanted texts. Method: read all code and tests, wrote the tests below
first (the interface-level ones failed to compile against 0.5.0; the behavioural ones were written against the old logic and then
the fixes applied, I did not keep a separate red run of each), then fixed. Nothing here was run on a device or a carrier.

Severity: High = could send unwanted or many texts, Medium = unexpected cost or wrong recipient/SIM, Low = bookkeeping or edge case.

## Found and fixed

| # | Sev | Finding | Fix | Test |
|---|---|---|---|---|
| 1 | Medium | The cap and the per-sender memory were keyed on the raw address string. The same person as `+14155550101`, `14155550101` or `4155550101` was a different sender: a second reply inside 24 h was possible, and a known person written the other way looked like a stranger. | `AutoReplyStore.canonical()` (digits, NANP "1" dropped) for the limit keys; history and block lookups try every spelling. | `the same person written two ways...` |
| 2 | Medium | "20 a day" reset at local midnight, so 40 texts could go out within minutes around 00:00 (and a clock or time-zone change moved the boundary). | Rolling 24 h window over stored send times (`sentInLast24h`). | `the daily cap is a rolling 24 hours...` |
| 3 | High (cap bypass) | Workers for different senders run in parallel. Limits were read, checked and reserved without a lock, and the counter was read-modify-write, so a burst from many senders could overshoot the cap. | One `Mutex` around read-config, evaluate and reserve; the send itself is outside the lock. | `many senders at once cannot get past the daily cap` (a race test is probabilistic: it passes now, I cannot prove it failed reliably before) |
| 4 | Medium | The log write sat inside the same `try` as the send. If bookkeeping threw after the text had gone out, the slot was released and a later message could send again. | Only the send is in the `try`; bookkeeping failure is swallowed and the slot stays used. | `a failure while writing the log...` |
| 5 | Low | No per-message idempotency. A redelivered SMS_DELIVER reuses the stored row id and was protected only by the per-sender slot. | The incoming message id is recorded at reservation (`alreadyHandled`); released if the send fails. | `a redelivered broadcast...` (slot deliberately wiped) |
| 6 | Medium | Replies went out on the default SIM, not the SIM that received the text (dual-SIM: reply from the other number, possibly a different plan). | Subscription id carried through the work request into `SmsSender` (`getSmsManagerForSubscriptionId`). Default behaviour unchanged when -1. | `the reply goes out on the SIM that received the text` |
| 7 | Medium | Message validation allowed `{ } [ ] ~ | ^ \` and backtick. GSM extension characters count double and any non-GSM character forces 70-character UCS-2 segments, so a "160 character, one text" message could be several billable texts. | Whitelist of GSM basic ASCII characters. | `characters that make a text longer...` |
| 8 | Low | "Clear this list" also deleted the ids behind the "Sent automatically" marker and the "not something the user wrote" exclusion. | Ids kept separately (cap 2000); clearing the list only clears the list. | `clearing the list keeps...` |
| 9 | Low | Two phones running InboxIQ with a custom message could answer each other's automatic text once each. | A text equal to our own configured message is treated as automatic. | `a text that is just our own message...` |
| 10 | Low | Settings entry did not say plainly that automatic texts can cost money or how to switch it off. | Copy changed on the settings entry and the auto-reply screen. | render tests |

## Checked, already correct (tests added or existing)

- Kill switch: build flag, user switch, `until` expiry, and "off after the broadcast but before the worker ran" all stop the send
  (`changed settings apply to work already queued`, existing one-tap-off and release-flag tests).
- Lowering limits applies at once; raising past 24 h / 20 is clamped on read and on save (`lowering the daily cap...`).
- Staleness (5 min, also for timestamps in the future), import-running and backfill-incomplete guard, not-default-app guard, blocked
  numbers, group addresses, MMS and image parts, short codes, toll-free, email gateways, junk thread label overriding message label.
- Time zone and DST: schedule is evaluated on local wall-clock time at processing, including the repeated hour in November and the
  missing hour in March (`schedule follows the wall clock across a daylight saving change`).
- Failed send: `SmsSender` marks the row FAILED and throws, the slot is released, nothing is retried automatically.
- Rate-limit store survives restarts: it is a SharedPreferences file (`inboxiq_autoreply`), committed synchronously before sending;
  `allowBackup="false"` so it is not restored onto another device with the switch on.
- Provider writes: the automatic text goes through the normal `SmsSender` path, which writes the provider outbox row once and keys our
  row on (address, timestamp, body) with a unique index, so re-import is an IGNORE no-op. This is the existing, separately tested
  path; I did not add an auto-specific provider test.

## Known and not fixed

| Sev | Item | Why / mitigation |
|---|---|---|
| Medium | **Policy and cost risk is inherent**, see recommendation below. | Owner decision. |
| Low | Role lost, then regained months later: the user's switch is still on and it resumes. | Shown clearly in settings; could auto-switch-off when the role changes. Not done; small follow-up if wanted. |
| Low | Process killed after the reservation but before the send: that one reply is lost (fail-safe). Killed after the send but before the bookkeeping: that text has no "Sent automatically" marker and counts as a user-written text for "have I texted them" (only matters for a saved-contact-only person). | Window is milliseconds; fixing needs a pending-state record. |
| Low | The radio can still fail asynchronously after `sendTextMessage` returns (no signal). The row becomes FAILED but the slot stays used. | Fail-safe: never retries, so no spam. |
| Low | Sender spoofing: a spoofed originating number that the user has texted triggers one text to the real owner of that number. | Bounded by 1 per person per 24 h and the daily cap; same exposure as any auto-responder. |
| Low | Premium-rate numbers (for example +1 900/976 or foreign premium ranges) are not specifically excluded. | Reachable only if the user has previously texted that number. |
| Low | `SmsSender.send` can return an id <= 0 if its insert is ignored (identical row in the same millisecond); the text still goes out but is unmarked. | Practically unreachable. |
| Info | Carrier timestamps skewed by more than 5 minutes from the phone clock make the reply skip (fail-safe, no text). | Intended. |
| Info | International recipients are allowed and cost more per text. | Disclosed in the confirmation and settings copy; the daily cap bounds it. |

## Recommendation (honest)

Engineering readiness: with the fixes above I would call the logic safe against runaway sending (three independent limits:
per-message id, 24 h per person, rolling 20 per 24 h, all serialized, all lowerable only, plus a build-flag escape hatch). I would
not claim more than that: no real device, SMS role or carrier was involved.

Whether to ship it is a policy and business call that testing cannot settle:

- Google Play's SMS and Call Log policy permits SMS use by a default SMS handler, and the feature is disclosed, opt-in, narrow and
  rate limited. But sending texts the user did not individually type from a user's number is the pattern reviewers and carriers
  scrutinise, and a rejection or a restriction of the SMS permission group would take the whole app's core functionality with it,
  not just this feature. The upside of the feature is modest (a convenience many users will not use).
- Carrier cost: each text is billed like any other and international ones cost more.
- Mitigation if you ship it: keep the Play Console text in `play-console-permissions-declaration.md` and the privacy and listing
  text honest and identical to the behaviour; ship `-PautoResponder=false` as the instant fallback build if the review pushes back.
  A staged rollout (for example 20%) with the flag as the fallback is the cautious route.

My recommendation: it is acceptable to ship, but it is the riskiest line in the release for review, and the safest sequencing is
either a staged rollout or shipping 0.5.1 with the flag off and enabling it in a later release once the review of the 0.5.x listing
has passed without questions. If the app's SMS permission approval is the thing you cannot afford to lose, do not ship it yet.
