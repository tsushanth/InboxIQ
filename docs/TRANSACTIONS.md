# Transactions label: design

Status: implemented in this change. Everything here runs on the phone; no text, score or label leaves the device.

## Why

Bank, payment, delivery and booking texts were filed as Personal on purpose (see `docs/CLASSIFICATION.md`, "Taxonomy
decisions"), because the only alternative was a quiet label that could hide a fraud alert. That left no place of their own:
in markets where almost every purchase sends an SMS, Personal fills up with automated alerts and the
conversations with people get buried. A separate label with a normal-volume notification channel fixes that without hiding
anything.

## What is a Transaction

An automated message about a specific financial event, order or booking the user is a party to:

* bank and card debit/credit alerts, UPI / IMPS / NEFT / RTGS / card payments, refunds and reversals, mandates
* balance and statement alerts
* bill, EMI, loan and premium due / paid, recharges, FASTag
* order, shipment, delivery and pickup updates (including AWB / tracking notices)
* appointment, ticket, booking and reservation confirmations and reminders (train / flight / bus / hotel / ride / clinic)
* fraud-alert questions from a bank about a payment ("Did you attempt ...? Reply YES")

What it is not:

| Message | Label | Why |
|---|---|---|
| One-time codes, including a bank's OTP and a bank text that carries a code | Codes | the text exists to deliver a code; the OTP rule runs first |
| A bank's or shop's offers, pre-approved loans, cashback campaigns | Promos / Junk | promo and spam rules run before the transaction rule and win when they score at least as high |
| Smishing that imitates the above (fake KYC, fake toll, held parcel with a fee) | Junk | the scam rule runs first |
| A friend saying "I paid the bill", "did the parcel come?" | Personal | needs an automated sender, or a number the user has never talked to plus very strong structure |

## Rules in the pipeline

Order per message (unchanged apart from step 4): OTP, scam, (work / promo), **transaction**, spam, personal.

* An **automated sender** (short code, named sender ID, toll-free, India DLT header) needs transaction evidence of at
  least 1.5. A single broad word (for example "reminder") is 1.0, so it still stays Personal exactly as before.
* A **person-like number the user has never really replied to** needs 2.5 (structured text such as a flight-delay or
  bill notice) and not one scam hint: US businesses do text from ordinary 10-digit numbers, but a "debited ... if not you
  call / click ..." text from a phone number is what a callback scam looks like, and that stays Personal or Junk. A number
  the user has a real back-and-forth with is never Transactions (and its transaction rules are not even run).
* Spam wording (score 1.5 or more) blocks it unless the transaction evidence is 3.0 or more and the spam score is under 2.0
  (a 0800 free-phone number reads as premium-rate spam to an old rule, which broke UK bank alerts); promo wording beats it when the promo score is higher; weak work vocabulary
  (meeting, account, ticket) does not beat it for an automated sender, strong work vocabulary (on-call, deploy, PTO) does.
* Evidence is data, not code: `app/src/main/resources/classify/transaction_rules.tsv` (text patterns, English and
  common Hinglish) and `india_senders.tsv` (TRAI header structure, suffix meaning, brand headers with a cited source).
  See `docs/INDIA_SENDER_IDS.md`.
* OTP texts stay Codes. One fix was needed for that: a bank alert ends with "Never share OTP or PIN with anyone", which
  the old OTP rule read as a code message. Warning sentences ("do not share ...") are now ignored when looking for code words.

## Tab, order, name

`All, Personal, Work, Transactions, Promos, Codes, Junk`. "Transactions" sits after Work: it is the third group the user
wants to read, ahead of the quiet ones. Icon: receipt. "All" still shows everything except Junk, so nothing is hidden.

## Notification channel

New channel `msg_transactions` ("Transactions"), importance **default** (sound, no heads-up pop-up), not high.
Reasons: a UPI-heavy user can get dozens a day, and a pop-up for each is spam; but it must not be silent like Promos,
because a fraud alert or "payment failed" is time-sensitive and must still sound. Personal stays high. The channel is
created with the others on first use; users who tuned the Personal channel keep that setting for Personal only (a new
channel starts from the default, which is a known cost of any new channel). No inline Reply button on these
notifications (replying to an automated sender does nothing).

## Existing user choices

* A conversation the user filed by hand (`thread_state.labelCorrected`) is never re-sorted, before and after this change.
  Someone who kept their bank under Personal keeps it there.
* One bug found on the way and fixed: a backup restored `labelCorrected` but not the label itself, so the worker then
  re-sorted a hand-filed thread from scratch. The worker now keeps the restored label for corrected threads.
* Smart replies, auto-reply and the "needs a reply" mark treat Transactions like other automated labels: no offers.

## Existing threads (one-time, safe, idempotent)

`ClassifyMessagesWorker.PIPELINE_VERSION` goes 2 to 3. On the next pass every thread whose label is older than the
upgrade and that is not hand-filed is re-sorted once, in pages of 50 in a single transaction per page (the mechanism the
version 2 upgrade used). A progress cutoff is stored, the pass resumes if it is killed, and the version is only written
when nothing stale is left, so running it again changes nothing. Cost, measured on a synthetic 50,000-message / 3,500-thread
database on a desktop JVM (rules only, in-memory Room): 2.7 s for the one-time re-sort, 5.9 s for a first-install sort.
A phone is slower and this was not measured on one; see `docs/LOW_RAM.md`.

## "Why?" text

Rule-based: the existing explainer re-runs the engine, so it states the rule that fired ("It reports money debited ...
so it is filed under Transactions") plus the sender note ("a named sender ID registered in India, transactional").
If the stored label is Personal and today's rules say Transactions, the sheet says that these texts used to be filed
under Personal and will move on the next sort.

## Room, backup

* **Room**: labels are stored as text (`TypeConverter` by enum name). Adding a value changes no table, column or index,
  so the exported schema is identical and no version bump is needed; a bump with an empty migration would only add a
  downgrade wipe (`fallbackToDestructiveMigrationOnDowngrade`). Tests: a v15 database with every existing label opens and
  reads unchanged, `TRANSACTION` round-trips through the DAO, and an unknown label name (future or downgraded data) reads
  as "not sorted yet" instead of crashing the converter (new, tested).
* **Backup**: format version stays 1 and the label is the enum name. An older backup (no Transactions) restores and the
  threads are sorted fresh on the next pass. A newer backup read by an older app shows the label as "not sorted yet"
  (that reader already ignored unknown names). Tested both directions.

## Strings

English only. The app has no translated string files, so every other locale already falls back to English; nothing was
machine-translated. New UI strings are in `res/values/strings.xml`; the tab, channel and label names follow the existing
convention of living next to their enum.

## Evaluation (all synthetic: NOT a measure of real-world accuracy)

Two sets, both written from public formats with masked account numbers and made-up references (no real messages):

* `transactions.tsv`, 121 rows, written by the rule author in the same session as the rules. In-distribution: a regression
  tripwire and an optimistic ceiling. First look at the first draft of the rules: TRANSACTION 78 of 79 found; after
  fixing what that showed: precision 1.000, recall 1.000.
* `transactions_blind.tsv`, 140 rows, written by a separate author who was given the label definitions and never
  saw the rules or the code. **First look, before any rule was changed in response**: TRANSACTION precision 0.958, recall
  0.767 (46 of 60; 13 went to Personal, one to Spam). I then changed rules to fix what that showed (India balance wording,
  Hinglish bills and recharges, UK/US currency words, "your driver is arriving", reservations, direct debit, the
  0800 spam-rule clash, plus three scam rules for fake "click to cancel this debit", ".apk" links and "pay to rearrange
  delivery"). After that: TRANSACTION 60 of 60, precision 1.000. **That set is no longer independent**, so the first-look
  numbers are the ones to read.

Final per-label precision / recall (rules only, no spam model):

| label | my set (n=121) | blind set after tuning (n=140) | blind set, first look |
|---|---|---|---|
| TRANSACTION | 1.00 / 1.00 | 1.00 / 1.00 | 0.96 / 0.77 |
| PERSONAL | 1.00 / 0.93 | 0.80 / 1.00 | 0.51 / 1.00 |
| WORK | 0.75 / 1.00 | 1.00 / 0.75 | 1.00 / 0.75 |
| PROMO | 1.00 / 0.88 | 1.00 / 0.60 | 0.90 / 0.60 |
| OTP_2FA | 1.00 / 1.00 | 1.00 / 1.00 | 1.00 / 0.93 |
| SPAM | 0.50 / 0.50 | 0.58 / 1.00 | 0.54 / 1.00 |
| SCAM | 0.83 / 1.00 | 1.00 / 0.87 | 1.00 / 0.67 |

Per-class numbers on 2 to 20 rows are noise (one row moves a rate by 5 to 50 points). The remaining non-Transaction misses
are old behaviour, not caused by this change: a bank's "pre-approved loan" and "T&C apply" promos score as spam, a recruiter
or shift-cover text without work vocabulary is Personal, and "account" plus "branch" in a Hinglish chat reads as work.

The older corpora (`docs/CLASSIFICATION.md`) have gold labels that predate the label. Their tests fold Transactions back
into Personal, so every existing floor still holds and nothing else moved; re-scored with transactional notices as gold
Transactions (automated-sender notices in the `txn_*` templates and the challenge sets), recall is 100% on all five sets
(72, 72, 6, 5 and 5 notices). Those corpora were visible while the rules were tuned (an in-sample result, and the first
run was lower: 63% on synthetic dev and 76% on synthetic test before the US ride-receipt, flight, pickup-counter and
"will arrive Tuesday" wording was added).

## Not done

* The bert-tiny model is unchanged; Transactions are rules only. The Gemma tiers' prompt does not list the new label
  (rules decide first; the model is only asked when no rule fired).
* Regional-language (Hindi script and others) texts: only Latin-script Hinglish is covered.
* No per-brand sender claims beyond the verified list; see `docs/INDIA_SENDER_IDS.md`.
