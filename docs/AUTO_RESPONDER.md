# Auto-reply when away (optional)

**Ships in 0.5.1 (10).** It is compiled behind the build flag `AUTO_RESPONDER_ENABLED`: **on for every `core` build (debug and the
Play release)**, off only for the `relay` flavor's release. `-PautoResponder=false` is the escape hatch that compiles it out of any
build (no settings entry, no hook in the receiver, no worker: the code is inert); `-PautoResponder=true` forces it on. The flag
only makes the feature available: **the user's switch is off on first run** and turning it on needs the confirmation dialog.
It shipped compiled out in 0.5.0 (9). Review findings and fixes: `AUTO_RESPONDER_REVIEW.md`.

## What it does

When the user turns it on, InboxIQ sends one short text (default "I am busy right now and will reply later.") to
people who message while the user is away. It is **off by default**; turning it on takes a confirmation dialog showing the
exact message, who may receive it, the limits, and a note that carriers may charge for texts.

Settings (Settings > Auto-reply, only when compiled in): on/off, message (templates: busy / driving / in a meeting; one
SMS segment, plain characters, no links), optional schedule (from/until time, weekdays; a window that crosses midnight belongs
to the day it starts on; evaluated in the phone's current time zone), optional end time (1 h, 4 h, 6 PM), limits (per sender
every 24/12/6/1 hours, 20/10/5 a day), and "Auto-replies sent" with a one-tap **Turn off**. The settings screen says plainly when
InboxIQ is not the default SMS app, because then nothing can run.

## Safeguards (all unit tested; see `autoreply/AutoReplyPolicy.kt`)

- Only individual phone numbers. Never short codes, alphanumeric IDs, toll-free numbers, email gateways, groups, MMS.
- Only if the user has previously sent that number a real message (not a bare STOP/YES, not one of our own automatic texts) or the
  number is a saved contact (`ContactResolver`).
- Only when the message and thread label are PERSONAL or WORK. Never PROMO / SPAM / SCAM / OTP_2FA / unlabelled.
- Never a blocked number. Never a text that looks automatic ("auto-reply", "do not reply", "STOP", "unsubscribe", out of office) or
  that asks for a code, so two auto-responders cannot ping-pong. Never within 60 s of our own auto reply to that number.
- At most one per sender per 24 h (can be lowered, never raised), at most 20 in any rolling 24 hours in total (the kill switch;
  also lowerable only). One person written as +14155550101, 14155550101 or 4155550101 is one sender.
- The message may only use plain GSM characters (letters, digits, common punctuation; not { } [ ] ~ | ^ ` backslash or emoji) so it
  can never become a multi-part, multi-billed text. A text that is just our own message coming back is not answered.
- Never for texts older than 5 minutes (imported or delayed history), never while the history import is running or has not finished
  its first pass, never when InboxIQ is not the default SMS app.
- The slot is reserved before sending, under one lock so parallel workers cannot overshoot the cap; a failed send releases it; a
  failure in the bookkeeping after a successful send does not. Each incoming message id is handled once (redelivery safe).
- The reply leaves on the SIM that received the text (dual-SIM phones).
- Editing the message switches it off until re-confirmed; `enable()` only accepts the exact confirmation text for the current settings.
- An end time that has passed switches it off permanently.
- Sends go through the existing `SmsSender`, so they are written to the Android provider like any sent text and show in the thread,
  marked "Sent automatically" (ids kept in a small prefs log, capped at 200; no Room schema change).
- No message body is put in the WorkManager request (the worker re-reads it from the database), and nothing from other people is stored.

Implementation: `SmsDeliverReceiver` calls `AutoResponder.onIncoming` after the text is stored and labelled; that returns at once
unless the flag and the user's switch are on, then enqueues a WorkManager unique job per sender. No foreground service, no
exact alarms, no new permissions.

## Policy and cost considerations (honest)

- **Google Play SMS policy (see AUTO_RESPONDER_REVIEW.md for the recommendation).** InboxIQ already uses the default-SMS-app role, which Play allows for SMS apps. An app that sends
  texts the user did not individually write must be a real default handler and the behaviour must be disclosed. The listing,
  data-safety form and in-app text need to say that it can send texts automatically, only after explicit opt-in. Automatic
  replies to bulk or unknown senders are the kind of thing reviewers and carriers dislike, which is why recipients are narrowed
  so far. I cannot predict how a reviewer will read it; this is the main reason to consider shipping smart replies first and the
  responder later.
- **Carrier cost and rules.** Each automatic text is an ordinary outgoing SMS. On plans without unlimited texting it costs money,
  international recipients cost more, and the daily cap bounds it at 20. Carriers can throttle or flag an unusual outgoing pattern;
  one text per person per day is far below typical thresholds, but this is untested with real carriers.
- **Etiquette / safety.** An auto-reply tells the sender the user is away; the default texts say nothing about location. The
  "driving" template does not claim the user is driving safely or give any detail.
- **Reliability.** WorkManager may delay a job (Doze, battery saver); the 5 minute age limit means a delayed reply is dropped rather
  than sent hours late.

## Not verified

Real device, real SMS role, real carriers (the owner's phone was off limits). Verified with Robolectric and a fake sender.
