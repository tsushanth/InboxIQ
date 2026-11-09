# Play listing draft (core flavor, 0.5.2 and later)

Status: draft for the owner to paste into Play Console. Nothing here has been submitted. Every claim is tied to
code or a build output in the table at the end. Counts below were measured with a script, not estimated.

Current live listing (read from the public Play page on 2026-11-09): title "InboxIQ", short description
"On-device SMS spam & scam detection. Sorted inbox. 100% private, no cloud AI.", the pre-0.5 description,
3 phone screenshots. This draft replaces all three text fields and the screenshots.

## Title (28/30)

```
InboxIQ: SMS Inbox Organizer
```

Why: "SMS inbox organizer" is the phrase people search for, and it describes the job. It names no other app. No
"best", "free" or "#1" (Play metadata policy discourages them).

## Short description (76/80)

```
SMS inbox sorted on your phone: codes, promos, junk. No internet permission.
```

## Full description (3209/4000)

```
Your SMS inbox is mostly machines: login codes, bank alerts, delivery updates, promotions, and the odd scam. InboxIQ is a text-messaging app that sorts all of it for you on your phone, so the message you need is not buried under the ones you do not.

WHAT YOU GET

- Tabs for Personal, Work, Promos, Codes and Junk (spam and suspected scams). Bank, delivery and appointment alerts stay in your main inbox so you do not miss them.
- Codes tab: verification codes sit in one place with a Copy code button, also on the notification. A code stops being offered once it has expired.
- "Why was this filed here?" on any conversation, and one tap to change the label. Your correction applies to future texts from that number and never leaves the phone.
- Search people, numbers and message text.
- Quieter notifications: each kind of message has its own notification channel, so promos can be silent while codes and people stay loud. Mute a conversation for an hour, eight hours, a week or until you turn it back on.
- Needs-a-reply flag and quick-reply suggestions for people who are waiting on you.
- Link check: a warning before you open a link that looks like a lookalike, a shortened link or a bare IP address. It is a quick offline check, so no warning does not mean a link is safe.
- Schedule a text to send later, swipe to archive or delete, pin, block numbers, voice-to-text for English.
- Save a backup of your conversations to a file you choose, and restore from it.
- Light, dark and true-black themes, wallpaper colours, and a two-pane layout on tablets and other wide screens.

PRIVATE BY DESIGN

InboxIQ does not request the Android INTERNET permission, so it has no way to send your messages, contacts or phone numbers off your phone. There are no accounts, no ads and no analytics. Sorting runs on your phone with a small model that comes with the app. You can optionally add a larger on-device model; Google Play delivers it, and it contains nothing from your phone. Uninstalling InboxIQ deletes its own data.

SWITCHING IS REVERSIBLE

Android only delivers new texts to one messaging app, so InboxIQ has to be your default SMS app to work. When you choose it, InboxIQ copies your existing texts into a private database on your phone so it can sort and search them; it shows progress and you can keep using the app meanwhile. New texts are also saved to Android's own message store, so another SMS app can still see them. You can switch back to any other messaging app in Android's settings at any time.

GOOD TO KNOW

- InboxIQ handles SMS and MMS only. It does not support RCS chats, and group picture messages are not supported yet.
- Sorting is tuned for English-language texts and is not perfect; use "Change label" when it is wrong.
- Backups contain text, labels, pins, archive and mute settings and blocked numbers, not photos.

PERMISSIONS

SMS and MMS: required to be your default SMS app (send, receive, store and show your texts). Contacts: show names instead of numbers. Notifications: alerts for new texts. Microphone: only while you hold the voice button, transcribed on your phone.

Source code: https://github.com/tsushanth/InboxIQ
Privacy policy: https://kreativekoala.llc/privacy
```

## Claim to evidence table

| Claim in the listing | Evidence (path in this repo, or build output) |
|---|---|
| Tabs Personal, Work, Promos, Codes, Junk | `app/src/main/java/com/inboxiq/app/inbox/InboxLogic.kt` (`InboxTab`) |
| Bank, delivery and appointment alerts stay in the main inbox | `docs/CLASSIFICATION.md` taxonomy table: transactional notices are filed as Personal |
| Copy code button, also on the notification; expired codes not offered | `inbox/OtpExtractor.kt`, `inbox/NotificationPolicy.kt` |
| "Why was this filed here?" and Change label, applies to future texts, stays on the phone | `inbox/LabelExplainer.kt`; dialog text in `ui/InboxScreen.kt` ("Future texts from this number go to the same place. Your choice stays on this phone and the model is not retrained.") |
| Search people, numbers, text | `ui/InboxScreen.kt` search placeholder |
| Per-kind notification channels; mute 1 h, 8 h, 1 week, until turned back on | `inbox/NotificationPolicy.kt` (`MessageChannel`), `inbox/MuteDuration.kt` |
| Needs-a-reply flag, quick replies | `classify/QuickReplySuggester.kt`, `reply/` |
| Link check, offline, "no warning does not mean safe" | `inbox/LinkSafety.kt` (class comment says the same) |
| Schedule send; swipe archive/delete; pin; block | `inbox/ScheduleOptions.kt`; `ui/InboxScreen.kt` (`SwipeableThreadRow`); `ui/SecondaryScreens.kt` blocked numbers |
| Voice-to-text for English | `voice/VoiceTranscriber.kt` (ggml-tiny.en bundled) |
| Backup and restore to a file; photos not included | `ui/SettingsScreen.kt` backup text |
| Light, dark, true black, wallpaper colours; two-pane on wide screens | `ui/theme/ThemeSettings.kt`; `ui/MainActivity.kt` (`maxWidth >= 840.dp`) |
| No INTERNET permission | Merged manifest of `coreRelease` built from 6fdc805 on 2026-11-09: 0 occurrences of `permission.INTERNET`; the signed 0.5.1 core bundle's manifest also has none. Run `scripts/inspect-variant.py core --build` to reproduce |
| No accounts, ads or analytics | No such dependency in `app/build.gradle.kts`; RatingKit uses only Play's in-app review library |
| Optional larger model delivered by Google Play, contains nothing from the phone | `assetPacks += ":gemma_model_pack"`, `classify/GemmaModelStore.kt` |
| Copies existing texts into a private database; progress shown; usable meanwhile | `sms/HistoryImport.kt`; progress strip text in `ui/InboxScreen.kt` |
| New texts also saved to Android's own message store | `sms/ProviderWrites.kt`; 0.5.0 changelog |
| Switch back any time | `ui/SecondaryScreens.kt` onboarding text |
| No RCS; no group picture messages | `sms/MmsSender.kt` sends to one address; no RCS code anywhere |
| Tuned for English, imperfect | `docs/CLASSIFICATION.md` limits section |
| Permissions list | `app/src/main/AndroidManifest.xml` |

## Things deliberately left out, and why

- Auto-reply. It is compiled in only with `-PautoResponder=true` (release default is off). I could not confirm
  which way the live 0.5.2 bundle was built. If it is on, add this block under WHAT YOU GET and update the
  permissions declaration text, because the app then sends texts on the user's behalf: "Optional auto-reply when
  you are away: off until you turn it on and confirm the exact message. Sends at most one short text per person
  per day, only to people you have written to or saved contacts, never to codes, promos or spam."
- Names of other apps, "spam protection" as a headline, any "AI" wording, any accuracy figure.
- The Gemma model size: the code says both about 125 MB (a build comment) and about 290 MB (settings text).

## Other fields

- Category: Communication (unchanged).
- Contact email: use one address that is monitored and matches the privacy policy (support@kreativekoala.llc is
  what the policy states). The live listing shows a different address and two phone numbers; the owner should decide.
- Privacy policy URL: https://kreativekoala.llc/privacy puts the InboxIQ section at item 10 of a shared page.
  Link to an anchor if the page has one.
- Optional India custom store listing (en-IN) only after the gate in `STRATEGY.md` section 3 is met; the text above
  is correct for India as written, but its promise ("codes, promos, junk") is thinner than what that audience expects
  without a Transactions tab.

## Screenshots (8, fake data; files in `store-listing/screenshots-new/`)

Size check: the stored PNGs are 960 x 2142, a ratio of 2.23:1. Play's screenshot rule (as I recall it, not re-read today)
is that the long side may not exceed twice the short side, so these may be rejected as they are. Cropping the status
bar and gesture area to about 960 x 1900, or framing each in a device mock-up with the caption above it, fixes that.

Order matters: the first two decide the install. Text overlays are added in the Play assets, not baked into the
PNGs. All overlay text must be true on the build that ships.

| # | File | Overlay caption | Why it is here |
|---|---|---|---|
| 1 | 01-inbox.png | Your texts, sorted on your phone | The job, in one picture |
| 2 | 04-code-copy.png | Copy one-time codes in one tap | The daily win |
| 3 | 05-junk-tab.png | Promos and junk kept out of the way | Second win |
| 4 | 03-why-labelled.png | See why a text was filed where it was | Trust: it explains itself |
| 5 | 10-settings-privacy.png (retake; see note) | No internet permission. No accounts. No ads | The proof point |
| 6 | 06-link-warning.png | A warning before a risky link | Safety, secondary |
| 7 | 07-search.png | Find any message | Utility |
| 8 | 11-inbox-dark.png | Dark mode and true black | Delight, last |

Dropped: 02-thread-smart-replies (generic), 08-multi-select, 09-settings-appearance. Note on #5: the stored image
comes from a debug build and shows an Auto-reply row; retake it from the release build, and only if the claim in the caption is on
the screen.
Add a 9th frame for the switching fear: a plain slide "Switch back to any messaging app in Android settings,
any time." (drawn text, no app UI).

Feature graphic (1024x500): the label tray over a quiet background with the line "Texts, sorted on your phone." Same
fake data as the screenshots. No competitor logos.

## 30 second video storyboard (fake data only, no voice-over; captions on screen)

| Sec | Shot | Caption |
|---|---|---|
| 0-3 | A notification shade full of mixed texts, then it fades to InboxIQ's tabs | Most texts are machines |
| 3-8 | Inbox, tap Codes, tap Copy code on a bank login code | Codes in one tap |
| 8-12 | Tap Promos then Junk, rows slide out of the main list | Promos and junk, out of the way |
| 12-17 | Open a conversation, tap "Why?", read the reason, tap Change label | See why. Correct it in a tap |
| 17-22 | Settings, privacy screen | No internet permission. No accounts. No ads |
| 22-27 | Onboarding card "You can switch back to another app in Android's settings at any time" | Switch back any time |
| 27-30 | App icon and name | InboxIQ: SMS Inbox Organizer |

Production notes: capture with `DemoSeedActivity` (debug only) on a spare phone, never the owner's daily phone as the
default SMS app; the demo seeder sets labels directly, so the label shots show real UI but not live
classification. Do not show real phone numbers or contacts.
