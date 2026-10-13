# How InboxIQ sorts texts

Everything here runs on the phone. No message text, score or label leaves the device, and nothing in the
evaluation touched real user data: it uses the public UCI SMS Spam Collection, a synthetic corpus and three
hand-written challenge sets (see "Evaluation").

Labels (`data/MessageLabel.kt`): PERSONAL, WORK, PROMO, OTP_2FA, SPAM, SCAM (UNLABELED means "not sorted yet").

## Taxonomy decisions

There is no "transactional" label, so these are the rules the classifier and the test corpora follow:

| Message | Label | Why |
|---|---|---|
| Verification, login and one-time codes | OTP_2FA | the message exists to deliver a code |
| Bank and card alerts, fraud alerts ("Did you attempt..."), balance and payment notices | PERSONAL | wanted, must notify loudly; a quiet label would hide fraud alerts |
| Delivery and shipping updates, appointment and flight reminders, pharmacy and utility notices | PERSONAL | same reason |
| Marketing with a discount, offer or opt-out, store and restaurant broadcasts, political and charity fundraising, surveys | PROMO | legitimate bulk senders; quiet notifications |
| Unsolicited pitches: loans, warranties, casino, "we buy houses", solar, adult bait, SEO, easy-money | SPAM | unwanted but not trying to steal |
| Smishing and fraud: fake toll/parcel fees, locked account, tax and government threats, prizes, gift-card requests, crypto and fake jobs, romance and wrong-number lures, "is this you in this video" | SCAM | tries to get money, credentials or a download |
| Colleagues, clients, recruiters, on-call and incident alerts, shifts, PTO, meetings, deploys | WORK | |
| Family, friends, plans, short replies | PERSONAL | |

A TRANSACTIONAL label would be a better product answer than folding these into PERSONAL; it needs a new enum
value, UI for it and notification-channel work, which was out of scope.

## The pipeline before this change (integrate-0.5.0, 3bd45fb)

1. `ClassifyMessagesWorker` took each address with no cached thread label and read **one** message: the latest one,
   `ORDER BY timestamp DESC LIMIT 1` with no direction filter, so it could be the user's own outgoing text.
2. `HeuristicRules` (first match wins): a 4-8 digit number plus the word otp/code/verify/verification anywhere -> OTP_2FA
   ("my zip code is 78666" qualified); "irs" or one of five literal phrases -> SCAM; five promo phrases -> PROMO.
   There was **no WORK rule at all**, although the class comments talked about it.
3. Otherwise bert-tiny (UCI SMS Spam Collection fine-tune, 2005-era UK English): P(spam) > 0.6 -> SPAM, else PERSONAL.
4. The address got that label for good: thread labels were never recomputed, outgoing messages were never labelled
   (`applyThreadLabel` filters `isIncoming = 1`), the sender type and the user's replies were never looked at.
5. The model was read fully into a byte array (17.6 MB) and a session created per worker run; receivers and importers
   enqueue a worker each, so several could run and load it at once. Each address cost two separate DB writes.

### Weaknesses, measured on the frozen old logic (`LegacyPipeline`, same corpora as below)

* WORK recall 0 on every set. The owner's phone showed 0 WORK threads: consistent.
* SCAM recall 8.7% (synthetic test), 10% (challenge 3). The owner's phone showed 0 SCAM threads: consistent.
* 18.1% of synthetic PERSONAL+WORK threads filed as SPAM (28% of the owner's threads were SPAM; consistent with an
  over-eager model, though the true rate on real data is unknown).
* bert-tiny scores 16% of modern personal-style text above 0.6 and 70% of the smishing set; it was trained on 2005 SMS.
* `UNLABELED` counted in messages (5,488 of 9,712 on the phone) is mostly structural: every outgoing message stays
  UNLABELED by design, and the last observed run had not finished. **Inferred from reading the code, not checked on
  the owner's data.** "Threads without a label" is the metric that means something.
* Known-contact threads could be filed SPAM/SCAM/OTP by a single message; `SmsDeliverReceiver` re-filed a thread from
  any one text matching a keyword rule.

## The pipeline now

```
ClassifyMessagesWorker (one pass at a time per process, pages of 50 addresses, 1 transaction per page)
  per address: up to 5 recent INCOMING messages + sender type + user's real replies (bare STOP/YES/C/1 excluded)
  -> TriageEngine.classify(ThreadContext)            [pure Kotlin, no Android types]
       per message, in this order:
         1 OTP_2FA   digits + code wording (strong words, or "is your / enter / expires" context); not "zip/promo/gift code"
         2 SCAM      weighted signals, threshold depends on relationship (below)
         3 WORK / PROMO   work vocabulary; promo = opt-out wording + offer/fundraising cues (+ short code bonus)
         4 transactional notice -> PERSONAL (can never become spam or promo)
         4b short code / named sender broadcasting a link or opt-out that is not a code or account notice -> PROMO
         5 SPAM      strong cue, or weak cue + model, or model >= 0.90 alone; never for a known contact
         6 PERSONAL  default
       recency-weighted vote across the messages; a scam in the NEWEST message wins outright
  -> thread_labels + messages.label/labelConfidence (unchanged storage, no schema change)
```

### Signals

* **Sender** (`SenderProfile`): short code (3-6 digits), alphanumeric ID, toll-free, ordinary phone, other country,
  email gateway. Short codes and named senders never hold conversations.
* **Relationship** (`Relationship`): the user's substantive replies to a person-like number. 0 = NONE, 1-2 = WEAK
  ("who is this?" to a scammer must not rescue the thread), 3+ = STRONG.
  STRONG: never SPAM, never PROMO, never weak-OTP; SCAM only at score >= 3.0 with two independent signal groups;
  the spam model is not even run.
* **Links** (`LinkFeatures`): shorteners, bare IP hosts, punycode, brand names on the wrong domain, hyphenated
  lookalikes (`usps-redelivery-help...`), abused TLDs (.top .xyz .click .icu ...), plain http.
* **Scam signals** (weights in `TextSignals.kt`): unpaid toll/fine/fee, held-parcel and customs fees, locked/suspended
  account, "verify your identity", IRS/SSA/DMV/warrant, prize and gift cards, crypto/forex/daily-return pitches, easy
  remote jobs, WhatsApp/Telegram redirects, romance and wrong-number lures, new-number-needs-money, video lure,
  callback requests, pressure wording, odd sender. Threshold 2.0 (2.5 weak relationship, 3.0 strong), and at least one
  content signal (pressure wording, a shortened link and an odd sender alone are never a scam).
  A callback request ("if this wasn't you, call 1-800...") counts at 30% when the sender is a short code or named ID,
  because that is how real bank alerts read.
* **Spam**: strong cues (loan/credit offers, warranty, casino, home-buying, solar, adult bait, pills, easy money,
  token pumps, classic premium-rate wording) >= 1.5; weak cues need the model (>= 0.5); a generic "several
  commercial words plus a call to act" rule; the model alone needs >= 0.90.
* **Work**: strong vocabulary (on-call, sev, standup, sprint X, deploy, PR #, PTO, timesheet, shift swap, VPN...)
  1.6; ordinary meeting/client/project words 0.7 each (distinct words, 3 needed, 2 plus a recruiter cue);
  casual chatter ("lol", "dinner") lowers the score.

### Cost control

Rules settle OTP, scam, promo, work, transactional and known-contact threads without the model. In the 10,000-thread
benchmark mix the model is consulted for 51% of threads. `LazySpamScorer` loads the session on first use and
`trim()` drops it again.

## Evaluation

All of it is in plain JVM unit tests (`app/src/test/java/com/inboxiq/app/classify/`); no device and no network.
The bert-tiny output for every corpus text is precomputed by `scripts/classification/score_spam_model.py` (a Python
port of the Kotlin tokenizer, pinned to it by `WordPieceTokenizerGoldenTest`) and read from
`spam_probs.tsv` (shipped int8 model) and `spam_probs_fp32.tsv` (the original export, used for the baseline).
`scripts/classification/build_eval_data.sh` regenerates everything.

| Set | What | Size | Notes |
|---|---|---|---|
| UCI SMS Spam Collection | public, CC BY 4.0 (see `uci/SOURCE.md`) | 4,827 ham, 747 spam | quarter used for calibration, the rest untouched. **bert-tiny was fine-tuned on this corpus, so it is not independent for the model.** |
| synthetic train/dev/test | `gen_corpus.py`, templates + slot fills + noise | 1,450 / 644 / 584 threads | every template belongs to one split only (see below). 555-01xx numbers, example.* domains, fictional firms |
| challenge 1 | hand-written, free-form | 134 | rules were tuned after seeing it |
| challenge 2 | hand-written, free-form | 120 | first pass recorded, then one rule (sales-pitch structure) was added after seeing it |
| challenge 3 | hand-written, free-form, written last | 91 | **evaluated once, never tuned against: the number to believe** |

Synthetic splits: each template is in exactly one of train (2/4), dev (1/4), test (1/4); test has 48 distinct
templates, so its effective size is 48, not 584 (instances of a template differ only by slot fills).

### Honest limits of the synthetic and hand-written data

* I wrote the rules **and** the corpora. Synthetic dev/test are in-distribution for the rules (macro F1 0.997 / 0.998)
  and are a ceiling, not an estimate. They exist to catch regressions and to show the old logic's failures.
* After first looking at the synthetic **test** split I fixed two things: a real false positive (a bank alert
  "if this wasn't you, call 1-888..." from a short code was called SCAM; callbacks now count 30% for automated senders)
  and added enterprise-IT vocabulary (VPN, helpdesk). First look at test: macro F1 0.971 with 12 personal threads in SCAM.
* Challenge 3 is still written by me, with knowledge of the rules; real texts will be messier.
* Real data differs: national mix (UCI is UK/Singapore 2005), carriers, emoji, other languages (only English patterns),
  MMS, group texts, and above all **base rates**: the owner's mailbox is PERSONAL-heavy, these corpora are not.
  Precision on rare classes will be lower on real data than here; the per-class numbers above must not be read as
  production accuracy.
* WORK vs PERSONAL for short human text is not decidable from words alone ("the client moved the demo to 2pm" is
  work; "I'm meeting the team for drinks" is not). The only strong WORK evidence is vocabulary; the user's own
  corrections are the real source of truth.

## Results

Old pipeline = `LegacyPipeline` on the fp32 model, applied to the newest incoming message exactly as the old worker did.

### Per-label F1 (precision / recall in the tables below)

| Set | Label | before F1 | after F1 |
|---|---|---|---|
| synthetic test | PERSONAL / WORK / PROMO / OTP / SPAM / SCAM | .640 / 0 / .579 / .889 / .260 / .160 | .996 / 1.0 / 1.0 / 1.0 / 1.0 / .993 |
| synthetic test | macro | 0.421 | 0.998 |
| challenge 3 (clean) | PERSONAL / WORK / PROMO / OTP / SPAM / SCAM | .533 / 0 / .625 / .737 / .381 / .182 | .781 / .720 / 1.0 / 1.0 / .556 / .800 |
| challenge 3 (clean) | macro | 0.410 | 0.809 |
| challenge 2 (first pass, before the sales-pitch rule) | macro | 0.362 | 0.794 |
| challenge 2 (final) / challenge 1 (tuned) | macro | 0.362 / 0.355 | 0.847 / 0.960 |

### Junk (SPAM or SCAM) false positives on PERSONAL+WORK threads

| Set | before | after |
|---|---|---|
| synthetic test | 18.1% | 0.0% |
| synthetic dev | 15.8% | 0.0% |
| challenge 2 (first pass) | 7.8% (SPAM 5.9 + SCAM 2.0) | 0.0% |
| challenge 3 | 0.0% | 0.0% |
| UCI ham, all 4,827 | 0.35% (17) | 0.04% (2) |

### UCI SMS Spam Collection (public data), all 5,574

| | before | after |
|---|---|---|
| ham filed SPAM/SCAM | 0.35% (17 of 4,827) | 0.04% (2) |
| ham filed WORK / PROMO / OTP | 0 / 1 / 0 | 2 / 0 / 0 |
| spam caught as SPAM/SCAM/PROMO | 85.8% | 80.3% |

Spam recall on UCI dropped by 5.5 points: the model's alone-threshold went from 0.6 to 0.9 because on non-UCI
text it fired on 16% of personal messages. That trade (fewer false positives, slightly lower recall on 2005
premium-rate spam) is deliberate and visible here, not hidden.

### Confusion matrices (rows gold, columns predicted)

Synthetic test, before:

| gold \ predicted | PERSONAL | WORK | PROMO | OTP_2FA | SPAM | SCAM |
|---|---|---|---|---|---|---|
| PERSONAL | 166 | 0 | 12 | 0 | 54 | 0 |
| WORK | 66 | 0 | 0 | 0 | 0 | 0 |
| PROMO | 7 | 0 | 22 | 0 | 13 | 0 |
| OTP_2FA | 4 | 0 | 0 | 56 | 10 | 0 |
| SPAM | 6 | 0 | 0 | 0 | 30 | 0 |
| SCAM | 38 | 0 | 0 | 0 | 88 | 12 |

Synthetic test, after:

| gold \ predicted | PERSONAL | WORK | PROMO | OTP_2FA | SPAM | SCAM |
|---|---|---|---|---|---|---|
| PERSONAL | 232 | 0 | 0 | 0 | 0 | 0 |
| WORK | 0 | 66 | 0 | 0 | 0 | 0 |
| PROMO | 0 | 0 | 42 | 0 | 0 | 0 |
| OTP_2FA | 0 | 0 | 0 | 70 | 0 | 0 |
| SPAM | 0 | 0 | 0 | 0 | 36 | 0 |
| SCAM | 2 | 0 | 0 | 0 | 0 | 136 |

Challenge 3, before / after:

| gold \ pred | PERSONAL | WORK | PROMO | OTP | SPAM | SCAM |
|---|---|---|---|---|---|---|
| PERSONAL | 24 / 25 | 0 / 0 | 0 / 0 | 1 / 0 | 0 / 0 | 0 / 0 |
| WORK | 14 / 5 | 0 / 9 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| PROMO | 5 / 0 | 0 / 0 | 5 / 10 | 0 / 0 | 0 / 0 | 0 / 0 |
| OTP_2FA | 3 / 0 | 0 / 0 | 0 / 0 | 7 / 10 | 0 / 0 | 0 / 0 |
| SPAM | 7 / 5 | 0 / 1 | 1 / 0 | 0 / 0 | 4 / 5 | 0 / 1 |
| SCAM | 12 / 4 | 0 / 1 | 0 / 0 | 1 / 0 | 5 / 1 | 2 / 14 |

What still goes wrong (challenge 3 and 2): SPAM and WORK recall (about 40-65%): paraphrased pitches and short human
work texts that use no vocabulary the lists know; scams written as plain chat (a stranger "looking for a friend", an
inheritance email, "buy gift cards for my boss") with no link; a person relaying a code to a friend is called OTP.
Misses fall to PERSONAL, which is the safe direction.

## Speed and memory

Measured here (desktop JVM / desktop onnxruntime 1.2x; not a phone):

| | before | after |
|---|---|---|
| Model asset (APK) | 17.6 MB | 6.0 MB (embedding table stored as int8 with a per-row scale, `scripts/classification/quantize_embeddings.py`; largest probability change 0.013 over 7,425 texts; 1 text crosses 0.5 or 0.6 differently and none crosses 0.9) |
| Session RSS (python, 1 thread, no arena) | +58 MB | +25 MB |
| Model inference | 0.88 ms | 0.89 ms |
| Session load | 19-52 ms | 13-14 ms |
| Rules + blend, 10,000 threads (JVM) | n/a | 2.9 s, 293 us/thread, 24.5 KB/thread, 239 MB allocated in total (garbage), model consulted for 51% |
| Worker end to end, Robolectric, in-memory Room, 700 threads / 19,000 messages, rules only | n/a | 1.5 s |

* Memory during a pass: pages of 50 addresses, <= 5 short strings each; no whole-table loads. The spam model is
  created on first use, a trim-memory callback (`TRIM_MEMORY_RUNNING_LOW` or `onLowMemory`) drops it mid-pass, and it
  is released when the pass ends. Nothing stays resident while idle. Only one pass runs at a time per process, so
  concurrent workers do not each load a model.
* Expected cost for the owner's mailbox (690 addresses): about 690 x (0.3 + 0.5 x 0.9) ms, under a second on a
  desktop; a phone core is maybe 5-10x slower: seconds, not minutes. **Not measured on a phone.**
* The old build's 416 MB RSS and two low-memory kills are **not explained by the classifier alone** (a 17.6 MB model
  and one session are tens of MB). The classifier part is reduced by roughly 35 MB per worker run; the rest (import,
  Room cursors, Compose, the Whisper asset, the relay) is outside this change.

## Upgrade behaviour

`PIPELINE_VERSION = 2` in `ClassifyMessagesWorker`. On the first pass after the update, every `thread_labels` row
older than that moment is re-classified once, except threads the user filed by hand (`thread_state.labelCorrected`).
Labels from the old pipeline were weak; this is what turns the owner's 0 WORK / 0 SCAM into real values without a
re-import. A bump of the constant repeats it. No schema change.

## Explanation sheet

`LabelExplainer` re-runs the engine (without the model) on the same messages, sender and reply count. It cites rules
only when that run reproduces the stored label; if the stored label came from the model, it says so and shows the
score; if today's rules would disagree with an older stored label, it says the label may be out of date.

## Not done, and why

* A new or better on-device model: nothing could be trained and evaluated reproducibly here without real data; the
  synthetic corpus would only teach a model my own templates. Int8 embeddings is a size/memory change only.
* Business-hours and reply-cadence features: the corpora carry no timestamps, so they could not be tested honestly.
* Using corrected senders as rule inputs beyond "the correction is authoritative and survives re-sorting".
* Gemma tier: unchanged, except that rules now decide first and a model may not call a known contact spam or scam.
* Labelling the user's own outgoing messages.
* Non-English text.

## Recommendations

1. **Feedback loop.** Every "Change label" is a labelled example (`correctLabel`). Store, locally only, the corrected
   label with a few features (sender kind, signal groups that fired, never the text) and use them to adjust the
   per-group thresholds for this user. Report the correction count by (predicted -> chosen) in a local debug screen.
2. **Safe on-phone evaluation.** Add a debug-only "audit" screen that shows the label, the evidence list and two
   buttons (right / wrong) for a random sample of 50 threads, counts only, stored locally; export the counts, not the
   text. That gives real precision per label within a day.
3. Add a TRANSACTIONAL label (UI and notification channel) so alerts stop sharing PERSONAL.
4. Label outgoing messages with the thread label, or stop counting them as "unlabeled".
5. Gate the Gemma tier on the same relationship rule inside `GemmaClassifier` itself.

## Reproducing

```
scripts/classification/build_eval_data.sh      # needs python with onnxruntime+numpy; FP32=<old export> for the baseline table
./gradlew :app:testDebugUnitTest --tests 'com.inboxiq.app.classify.*'   # writes app/build/classification/eval.md
```
