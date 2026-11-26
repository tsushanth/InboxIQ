# InboxIQ: who it is for, and the one job it does

Written 2026-11-09 against `main` at 6fdc805 (version 0.5.2, versionCode 11, `core` flavor). Competitor facts were
read from public Play Store pages and news coverage on the same day; sources are listed at the end. Where a
fact comes from a third-party tracker or a weak source, it says so.

## 1. What the app does today (checked against the code)

InboxIQ is a default-SMS-app replacement for Android (SMS and MMS only). It:

- Copies the phone's existing texts into a private on-device database once it is the default SMS app, shows
  progress ("Copying your texts: x of y"), and writes new texts back to Android's own message store so another
  SMS app can still see them (`sms/HistoryImport`, `sms/ProviderWrites`).
- Sorts each sender into Personal, Work, Promo, Code (OTP/2FA), Spam or Scam with rules plus a small on-device
  model (`classify/TriageEngine`, bert-tiny ONNX; an optional larger Gemma model is delivered by Google Play).
  The inbox shows tabs: All, Personal, Work, Promos, Codes, Junk (spam and scam together) (`inbox/InboxLogic.kt`).
- Shows a "Why?" explanation for each label and lets the user change it (`inbox/LabelExplainer`).
- Extracts one-time codes, shows a Copy code chip and a Copy code notification button, and stops offering a code
  after it expires (default 10 minutes unless the text says otherwise) (`inbox/OtpExtractor`, `NotificationPolicy`).
- One notification channel per kind of message, mute for 1 h / 8 h / 1 week / until turned back on.
- Search across people, numbers and text; swipe to archive or delete with Undo; pin; block numbers; multi-select.
- Needs-a-reply flag and quick-reply chips (`reply/`), an offline link check (`inbox/LinkSafety`), scheduled send
  for text messages, English voice-to-text (whisper.cpp tiny.en bundled), backup and restore to a file,
  themes (system, light, dark, true black, wallpaper colours), two-pane layout at 840 dp and wider.
- Has an opt-in "Auto-reply when away" feature (`autoreply/`), compiled in only when the build flag
  `AUTO_RESPONDER_ENABLED` is on (release default: off).
- `core` manifest (merged manifest of `main`, built 2026-11-09): SMS/MMS permissions, READ_CONTACTS,
  POST_NOTIFICATIONS, RECORD_AUDIO, plus library-added FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC,
  WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED. **No INTERNET permission.**

### Unfinished or missing (what the product cannot do yet)

| Gap | Effect on users |
|---|---|
| No RCS (third-party default apps are SMS/MMS only; confirmed only by secondary sources) | Chats that would be RCS in Google Messages fall back to SMS/MMS |
| MMS send takes one recipient (`MmsSender.send(address, ...)`); conversations are keyed by one address | Group picture messages are not supported |
| No delivery receipts (`inbox/DeliveryInfo`) | "Sent" only means handed to the carrier |
| No "Transactions" label: bank, delivery and appointment texts are filed as Personal on purpose (`docs/CLASSIFICATION.md`) | The people who most want bank alerts grouped separately cannot get that |
| Classifier rules and the spam model are English and US/UK-tuned; no India or other-region sender patterns or languages | Quality outside English-language US/UK texts is unmeasured |
| No import preview before choosing the default app; import starts only after the role is granted | The step users fear most has no "see it first" |
| No reminders, bill or travel cards | Not a like-for-like replacement for organizer apps that had them |
| Memory: about 373 MB RSS after importing ~9.7k messages on a Pixel 9 Pro | Low-RAM phones are untested |
| Core MMS send/receive and the optional model download have not been exercised on a device on the current core build in this review | Treat as unverified |
| Repo is public but has no LICENSE file, and `settings.gradle.kts` points at a module outside the repo | The source is readable, but it is not "open source" in the licence sense and does not build for outsiders |

## 2. The field (all counts read from Play pages on 2026-11-09; Play shows install buckets, not exact numbers)

| App | Installs / rating | Updated | What it is, and what it does well | Where a small app can still help |
|---|---|---|---|---|
| Google Messages | 10B+ / 4.6 (51.6M reviews) | Nov 6 2026 | The Android default almost everywhere; the RCS client; spam protection; on-device Scam Detection (Gemini Nano) expanded to 20+ countries in Feb 2026; opt-in "Enhanced protection" with cloud analysis (Oct 2026) | Not on spam/scam detection, nor on RCS. Its Data safety lists location, personal info and device IDs as possibly shared |
| Samsung Messages | preinstalled | shut down July 2026 (SamMobile, Jul 7 2026) | Galaxy users on Android 12+ were moved to Google Messages | A one-time moment when many people re-chose their default app |
| Microsoft SMS Organizer | 1M+ / 4.8 | **Sep 12 2024** | Auto-organizes the inbox, bill and travel reminders, all on device, offline | In-app "sunsetting" notice on 2026-05-19 (Reddit posts that day, Android Authority and PiunikaWeb coverage); no official date seen. Users in Dec 2025 and Jan 2026 threads report missing notifications |
| Fossify Messages | 100K+ / 4.0 (4.27K reviews); a tracker estimates 254K+ | Oct 24 2026 | Open source, no data collected, SMS/MMS group messaging, backup, blocking | A privacy-first minimalist app with no sorting |
| Simple SMS Messenger | 5M+ / 4.1 | Oct 26 2026 | The long-standing minimalist app that Fossify forked from; Data safety lists device IDs | |
| Textra | 10M+ / 4.0 (440K) | Nov 7 2026 | Customization leader; Data safety lists location and app activity as shared | |
| Chomp SMS | 10M+ / 4.2 (338K) | Sep 23 2026 | Customization and scheduling | |
| Pulse SMS | 1M+ / 3.5 (82K) | Sep 17 2026 | Cross-device SMS, subscription model; Data safety lists messages as collected | |
| QKSMS | 1M+ / 3.8 | **Feb 23 2021** | Beloved open-source app, no longer updated | A visible gap for people who want a modern open app |
| Truecaller | 1B+ / 4.4 | Nov 6 2026 | Caller ID first, SMS filtering as an extra; a Reddit user in May 2026 named its paywall | |
| Signal | 100M+ | Nov 2 2026 | Removed SMS support from Android (announced Oct 2022) | Not an SMS option any more |
| InboxIQ (live listing) | 5+ / no rating | Oct 15 2026 | 3 screenshots, title "InboxIQ", the pre-0.5 description | |

What this says:

1. **Spam and scam detection is no longer a differentiator.** The default app ships it for free, with a model
   from the platform owner, in 20+ countries. Leading with "spam detection" invites a comparison InboxIQ loses.
2. **A sorted inbox is not a niche that Google fills everywhere.** Google Messages sorted texts into Personal,
   Transactions, OTPs and Offers in India from 2021; later, outside India, only Personal and Business tabs
   appeared (9to5Google, secondary reports). The tabs a user gets depend on country and version, and I could not
   confirm today's state in India.
3. **Two orphaned user groups appeared in 2026**: people leaving Microsoft SMS Organizer (shutdown notice May
   2026, last update 2024) and Galaxy users whose Samsung Messages was retired in July. Both had to pick an app.
4. **Data safety is the cleanest wedge on the Play page itself.** Most customization-focused competitors declare
   collected or shared data. Fossify and InboxIQ say none. InboxIQ is the only one of those two that sorts.
5. **Where InboxIQ cannot win:** RCS and iPhone-quality chats (Google Messages), group chats, customization
   depth (Textra), cross-device sync (Pulse), a proven open-source community (Fossify), caller ID (Truecaller).

## 3. The decision

**Segment:** Android users whose SMS inbox is flooded with automated texts (login codes, bank and delivery
alerts, promotions) and who are looking for a new SMS app because their old one is going away or never fit.
The first concrete group is the former Microsoft SMS Organizer user; the second is a Galaxy user who did not
want Google Messages. Lead market: English-language users in India, with the same listing working for any
English-speaking market.

**Job:** "Find the one text I need among the machine texts, without my messages leaving my phone."
In order of value: (1) the code is one tap away, (2) promos and junk are out of the way, (3) nothing is uploaded,
and the app proves it with no INTERNET permission.

**Cut from the pitch:**

- "Spam and scam detection" as the headline. Keep it as one line under Junk.
- AI language (smart replies, Gemma, "no cloud AI"). Say "on your phone" instead; the privacy claim is stronger
  than the model claim, and quick replies are retrieval-based.
- Auto-reply, voice memos, themes and scheduled send as headline features. They are list items.
- Any "complete replacement for your messaging app" wording: no RCS, no group MMS.
- Any name of another app in the store listing (Play metadata policy, and it is simply not kind).

**Why this and not the others I considered:**

| Option | Verdict | Reason |
|---|---|---|
| Spam and scam protection | Cut as lead | Free, platform-grade default in 20+ countries |
| Privacy-minded users | Secondary | Real, but Fossify owns the brand and is open source with a licence; InboxIQ's extra is sorting. Needs a licence decision to compete honestly |
| Scheduled send + auto-reply | Cut | Scheduled send is common; auto-reply is compiled out by default and Play and carrier risk is high |
| Android tablets | Cut | No evidence of demand; the two-pane layout is a bonus, not a reason to install |
| Heavy OTP/transaction inboxes (India, SE Asia) | **Lead, with a gate** | Matches the job and the SMS Organizer vacuum. Gate below. South-East Asia is unresearched: no recommendation yet |
| Organizer-style reminders (bills, travel) | Not now | Large build; the sorted inbox is the smaller first promise |

**The gate (product, not marketing).** Before pushing India-focused channels, the app needs: (a) a Transactions
label and tab so bank, UPI, delivery and bill alerts have their own place, (b) India sender-ID patterns (named
sender IDs already count as automated senders; there are no India-specific text patterns) and tests, (c) a check
on a low-RAM phone. Until (a) and (b) exist, the experiments in `REACH-PLAN.md` that target India stay off, and
the global English listing and guide posts run first. I did not build these; they belong to the app work.

## 4. The adoption funnel: Play listing to "this is my SMS app and I trust it"

The default-SMS switch is the scary step. Every step below has one job: remove one fear.

| # | Step | Friction | Trust risk | What reassures (exact copy) |
|---|---|---|---|---|
| 1 | Finds the listing (search, guide, community link) | Unknown app, "5+" installs, no reviews | "Why would I hand my texts to this?" | Title: `InboxIQ: SMS Inbox Organizer`. First screenshot caption: "Your texts, sorted on your phone". Short description: "SMS inbox sorted on your phone: codes, promos, junk. No internet permission." Data safety already says "No data collected" |
| 2 | Reads the description | Long text, jargon | Feels like every other SMS app | The first two sentences state the job; a "SWITCHING IS REVERSIBLE" block answers the fear before they ask (see the listing draft) |
| 3 | Installs, opens | Needs to be default before anything works | Fear of losing texts or RCS | Existing first screen: "Texts, sorted on this phone". Keep "Not now, look around" |
| 4 | Reads "Why it needs to be your default SMS app" | One more screen | "You can read all my texts" | Existing text works: "Android only delivers new texts to one app. To receive, send and sort them, InboxIQ has to be that app. You can switch back to another app in Android's settings at any time." Add one line: "Your existing texts stay where they are in Android; InboxIQ copies them to sort and search them." |
| 5 | System role dialog | A system dialog the app cannot style | "What does this change?" | Line above the button: "Next, Android will ask you to make InboxIQ your default SMS app. Tap Set as default." (Proposed; not in the app) |
| 6 | Import runs | Wait; a large inbox takes about 45 to 50 seconds on a Pixel 9 Pro (9.7k messages); slower phones unmeasured | "Is it stuck?" | Existing: "Copying your texts: x of y. You can keep using the app." |
| 7 | First sorted inbox | Mislabelled threads | "It filed my bank text under Promos" | Existing "Why?" and "Change label". Proposed first-open tip: "Wrong tab? Open the conversation and tap Change label. It stays on this phone." |
| 8 | First real code arrives | Notification must work | Missed code means uninstall | Copy code on the notification (exists). Not verified on this review's devices beyond the earlier real-phone receive test |
| 9 | Week one: "can I leave?" | Fear of lock-in | "Are my texts trapped?" | Settings has "Read what InboxIQ does and does not access". Proposed addition: a "How to switch back" help entry: "Open Android Settings, Apps, Default apps, SMS app, and pick any other app. Your texts remain in Android's message store." |
| 10 | Asks for a review | Interrupts | Pushes for a rating | RatingKit asks after the second sent reply and 2+ days; keep |

Proof points in order of strength: (1) no INTERNET permission, verifiable in the manifest and listed by
permission-viewer tools; (2) Play Data safety "No data collected"; (3) "Source code: GitHub" link; (4) the label
"Why?" explanation. I rank the source link last until a licence exists and the repo builds without the owner's
machine.

**Import preview.** The strongest reassurance would be showing a sorted preview before the system dialog. It is
not built, and reading texts before the role is granted may conflict with Play's SMS policy, so check the policy
before building anything. Until then, the screen copy above is the substitute.

## 5. What to ship, in order

1. New listing text and 8 screenshots (`PLAY-LISTING-DRAFT.md`). Owner action in Play Console.
2. The three funnel copy additions in steps 4, 5, 7, 9.
3. The India gate (Transactions label, India patterns, low-RAM check).
4. A licence decision and a repo that builds from a clean clone, then the privacy community channels open up.

## Sources (all read 2026-11-09 unless noted)

- Play Store pages (public HTML) for com.google.android.apps.messaging, com.microsoft.android.smsorganizer,
  org.fossify.messages, com.simplemobiletools.smsmessenger, com.textra, com.p1.chompsms, xyz.klinker.messenger,
  com.moez.QKSMS, org.thoughtcrime.securesms, com.truecaller, com.inboxiq.app.
- AppGoblin, Fossify Messages: https://appgoblin.info/apps/org.fossify.messages (tracker estimate, not Google data)
- PiunikaWeb, 2026-05-19: https://piunikaweb.com/2026/05/19/microsoft-sms-organizer-shutdown-soon/
- Android Authority (headline only; page blocked automated fetch): https://www.androidauthority.com/microsoft-sms-organizer-shutting-down-3669132/
- Reddit r/IndiaTech and r/androidapps threads, 2026-05-19, 2026-05-28, 2026-01-05, 2026-01-03, 2025-12-14.
- Samsung Messages shutdown: https://www.sammobile.com/news/samsung-messages-app-getting-discontinued/ and
  https://www.androidcentral.com/phones/samsung-galaxy/samsung-messages-app-has-a-shutdown-date
- Google Scam Detection expansion (Feb 2026) and Enhanced protection (Oct 2026): TechRepublic, 9to5Google
  (https://9to5google.com/2026/10/14/google-messages-enhanced-protection-mode/), Android Authority.
- Google Messages India categories (2021): https://www.techradar.com/news/google-messages-will-auto-delete-otps-and-categorise-like-gmail-only-in-india
- Signal removing SMS: https://signal.org/blog/sms-removal-android/
- Truecaller Message IDs (May 2025): Business Today.
