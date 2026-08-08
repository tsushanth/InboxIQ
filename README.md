# InboxIQ

On-device SMS/MMS triage: labels incoming messages (personal / work / promo /
OTP-2FA / spam / scam) asynchronously in the background. No message content
ever leaves the device — classification runs entirely on-device, which is
also the core Play Store trust/compliance story (see `docs/`).

## Status

- [x] Default-SMS-handler manifest wiring (receivers, headless-respond service, RoleManager request flow)
- [x] Room DB (message + per-thread label storage, unread state)
- [x] Outgoing send path (`SmsSender` + `SmsSentReceiver` + `HeadlessSmsSendService` for quick-reply)
- [x] Existing-message backfill via content provider (`SmsBackfill`), triggered once the role is granted
- [x] Real classifier: bert-tiny SMS spam model (mrm8488/bert-tiny-finetuned-sms-spam-detection)
      exported to ONNX, run via ONNX Runtime Mobile, blended with keyword heuristics for
      OTP/2FA, promo, and scam (see `classify/` and known limitations below)
- [x] MMS sync (`MmsSync`/`SyncMmsWorker`) — reads platform-downloaded MMS from `content://mms`,
      including a real image viewer for MMS attachments (not just a text placeholder)
- [x] Contact name resolution (`ContactResolver`) in the thread list/detail UI
- [x] Thread-grouped Compose UI (list of conversations → tap into a thread) with unread badges
- [x] Notifications for incoming SMS (`MessageNotifier`)
- [x] Per-message heuristic override — a high-confidence heuristic on a new message
      (OTP code, scam phrasing) corrects a stale/wrong cached thread label without
      touching that address's older history
- [x] Settings screen with classifier-tier picker (only the default tier is wired up;
      mid/high tiers show as disabled "coming soon" until those models exist)
- [x] App icon (adaptive icon, `ic_launcher_foreground.xml`)
- [x] Play Console Permissions Declaration draft (`docs/play-console-permissions-declaration.md`)
- [x] Privacy policy — **live** at https://kreativekoala.llc/privacy (section 10), draft copy in
      `docs/privacy-policy-draft.md`
- [x] Visual design pass: color-coded label chips (light + dark variants), contact avatars,
      chat bubbles, relative timestamps, unread bold/dot indicator (`LabelStyle.kt`)
- [x] Dark mode — dynamic Material You color scheme on Android 12+, static light/dark fallback below that
- [x] "Needs a reply" indicator + retrieval-based quick-reply chips (`QuickReplySuggester`) —
      Gmail's original Smart-Reply approach (fixed candidate set via simple heuristics), not
      generative AI drafting, since there's no generative model in the pipeline yet

## Architecture

```
sms/       BroadcastReceivers + RoleManager flow required for default-SMS-handler eligibility;
           SmsBackfill (history import), SmsSender (outgoing), MmsSync (MMS read-back),
           MessageNotifier, and ContactResolver-consuming logic live here too
data/      Room entities/DAO — MessageEntity (per-message, incl. isRead + imagePartUri),
           ThreadLabelEntity (per-sender classification cache), ThreadSummary (thread-list query result),
           ContactResolver (phone number -> saved contact name)
worker/    WorkManager jobs — ClassifyMessagesWorker (per-thread classification, self-re-enqueuing),
           SyncMmsWorker (reads content://mms after a WAP_PUSH_DELIVER, delayed 10s for platform download)
classify/  Pluggable MessageClassifier interface + ClassifierTier/ClassifierTierPreference (settings-backed):
             v1 default : bert-tiny spam/ham encoder (ONNX Runtime Mobile) + word-boundary keyword heuristics
             mid tier    : Gemma 3 270M via LiteRT-LM (not yet implemented — picker entry disabled)
             high tier   : Gemma 3 1B / Qwen2.5 1.5B (quantized), gated to 12GB+ devices,
                           run only under WorkManager charging/idle constraints (not yet implemented)
           QuickReplySuggester — retrieval-based reply candidates (see below)
ui/        Compose screens — ThreadListScreen (conversations, unread badges, needs-reply icon) ->
           ThreadDetailScreen (messages + MMS images + quick-reply chips + send box) /
           SettingsScreen (classifier tier picker). LabelStyle.kt maps each MessageLabel to a
           light/dark-aware chip color.
```

### Classification is per-thread, not per-message

The classification unit is the **sender address**, not each individual
message — a given number is essentially always the same category (a bank's
OTP line doesn't alternate between OTP and personal chat). So:

- Inference runs once per new address, using its most recent message as the
  sample. The result is cached in `thread_labels` and bulk-applied via SQL
  (`MessageDao.applyThreadLabel`) to every message from that address —
  no repeated model calls for repeat senders.
- A new message from an *already-classified* address gets its label applied
  instantly in `SmsDeliverReceiver` straight from the cache — no WorkManager
  job, no inference — *unless* a high-precision heuristic on that specific
  new message disagrees with the cached label (e.g. a spoofed number that
  previously looked benign suddenly sends obvious scam phrasing), in which
  case only that one message is corrected and the cache is updated for
  future messages; older history for that address is left alone since one
  message isn't strong enough evidence to relabel everything.
- A new message from a *new* address enqueues `ClassifyMessagesWorker`,
  which classifies just that address (and drains any other
  not-yet-classified addresses, capped at 500/run, self-re-enqueuing if more
  remain — this is what makes a large history backfill affordable: a
  ~4,800-message real-device backfill produced only ~540 inference calls,
  one per distinct contact).
- Outgoing (sent) messages are never run through the classifier.

### MMS: read-back, not PDU parsing

Hand-parsing WSP/PDU bytes requires internal AOSP classes
(`com.google.android.mms.pdu_alt.*`) that aren't part of the public SDK.
Once InboxIQ holds the default-SMS-handler role, the platform itself
auto-downloads incoming MMS and inserts the decoded result into
`content://mms` — the same approach real default SMS apps take. `MmsReceiver`
just acknowledges the WAP push and schedules `SyncMmsWorker` (10s delay for
the platform download to land), which reads the sender + parts back from the
MMS provider. The first image part per message is decoded and rendered
inline in the thread view (`MmsImage` in `MainActivity.kt`) via
`content://mms/part/{id}`; only one image per message is kept for v1.

### Known model limitation (and a real bug that was fixed)

The bundled bert-tiny model is trained on the 2005-era UK SMS Spam
Collection dataset — strong on classic spam phrasing ("txt FA to 87121"),
weaker on modern scam/phishing language. Separately, v1 shipped a real
heuristic bug: the SCAM keyword `"irs"` was matched with plain
substring `contains()`, so it matched inside "**Fi**rs**t** Tech Federal
Credit Union" and mislabeled a legitimate bank alert as SCAM. Fixed by
switching all `HeuristicRules` keyword matching to `\b`-word-boundary
regex (`containsAnyWord`) instead of substring `contains` — verified
against the real message on-device, now correctly labels as PROMO
(arguably still imperfect — it's a legit transactional alert, not
promotional — but no longer alarmingly wrong). A fine-tuned model on
modern, more representative data remains the deeper fix, tracked as a
follow-up.

### Quick replies are retrieval-based, not generative

`QuickReplySuggester` shows up to 3 canned reply candidates (e.g. "Yes" /
"No" / "Let me get back to you" for a question) above the compose box when
the other person sent the last message in a PERSONAL/WORK/UNLABELED
thread — the same "needs a reply" signal drives the reply-arrow icon on
the thread list. This is Gmail's *original* Smart Reply approach (a fixed
candidate set picked by simple keyword heuristics), not AI-drafted text —
there's no generative model in the classifier pipeline yet (see tiers
above). Tapping a chip fills the compose box; it doesn't auto-send.
Verified end-to-end on a real sent SMS (tapped "Thanks!" → `SmsManager`
delivery confirmed, `sendStatus=SENT` in the DB).

## Build

```bash
./gradlew assembleDebug
```

Open in Android Studio for the emulator/device run loop (SMS testing requires
a real device or an emulator with SMS injection via `adb emu sms send`).

## Model export

The bundled `app/src/main/assets/spam_classifier_v1.onnx` +
`spam_classifier_vocab.txt` were produced by exporting
`mrm8488/bert-tiny-finetuned-sms-spam-detection` via `optimum.exporters.onnx`
(task: `text-classification`). Output verified to match native PyTorch
inference exactly before bundling. Re-run the same export for future
fine-tunes; the Kotlin `WordPieceTokenizer` expects a standard BERT
`vocab.txt`.

## Compliance docs

- `docs/play-console-permissions-declaration.md` — draft answers for
  Play's Sensitive App Permissions declaration form. Has open items
  (screenshots, final manifest cross-check) before actual submission.
- `docs/privacy-policy-draft.md` — draft privacy policy, now hosted live
  (see Status above); the "Changes to this policy" notification process
  is still an open placeholder — a real decision, not a technical task.

## Next steps

1. Fine-tune the spam/ham model (or train a full 6-way classifier) on more
   modern spam/scam phrasing — the known-limitation gap above.
2. Wire an actual mid-tier model (Gemma 3 270M via LiteRT-LM) behind the
   Settings screen's already-scaffolded (but disabled) picker entry —
   this would also upgrade quick replies from retrieval-based to genuinely
   generated/drafted text.
3. Support MMS messages with more than one image part (currently only the
   first is kept).
4. Decide and implement the privacy-policy change-notification process
   (the one remaining open placeholder in the draft).
