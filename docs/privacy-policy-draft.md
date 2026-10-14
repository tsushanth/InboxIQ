# InboxIQ Privacy Policy — draft

**Status: LIVE copy is OUT OF DATE (says nothing leaves the device). Replace section 10 with the text below before the next release.** Original note: LIVE. Hosted as a dedicated section within the shared Kreative
Koala LLC privacy policy at:
**https://kreativekoala.llc/privacy#** (section 10, "App-Specific
Disclosures: InboxIQ") — source in `~/Documents/GitHub/kreative-koala-legal/privacy.html`,
pushed 2026-08-02 (commit `637ce29`). Use that URL for the Play Console
"Privacy Policy" field, not this file — this file remains the working
draft/reference copy.

_Last updated: 2026-10-13 (draft; the live page still carries the older text)_

## Which build this text covers

InboxIQ is built in two variants. **Core (Google Play)**: everything in sections "What InboxIQ does" through
"Permissions", read *without* the parts marked "relay build only". The core app has no internet permission, so it
cannot send your messages, contacts or phone numbers anywhere. **Relay build (direct download only)**: adds the
optional "Connect to Claude" feature described in the marked sections. Publish the core wording on the Play
listing's policy page; if a relay build is distributed, say so explicitly on the same page.

## Core-variant policy text (publish this for Play)

InboxIQ is an SMS/MMS messaging app that sorts incoming texts (personal, work, promotional, one-time passcode,
spam, suspected scam) using a model that runs on your phone.

- Your messages, phone numbers and contact names are read and stored only on your device, in a local database.
  InboxIQ cannot send them off the phone: it has no internet permission, no accounts, no ads and no analytics.
- Voice memos use the microphone only while you hold the button and are transcribed on the device.
- Optional "Auto-reply when away" (off until you turn it on): when you switch it on and confirm the exact message,
  InboxIQ can send that one short text, on your phone and from your number, to an individual who texts you and whom you
  have texted before or saved as a contact (never to businesses, short codes or groups), at most once per person per
  day and 20 a day. These are ordinary text messages to the person you are already talking to, and your mobile carrier
  may charge for them like any text you send. They are listed in the app so you can see and stop them. The feature
  works entirely on the device; nothing about it is sent to us or to anyone else.
- An optional larger on-device model can be downloaded; Google Play performs that download, and it contains no data
  from your phone. Google's handling of Play downloads is covered by Google's own policies.
- Uninstalling InboxIQ deletes its local data. We do not receive, sell, or share your data, and do not use it to
  train AI models.
- Permissions: SMS/MMS (send, receive, read: required for a default SMS app), Contacts (show names, and check whether a sender is saved for the optional auto-reply), Notifications,
  Microphone (voice memos). No internet permission.
- Contact: support@kreativekoala.llc

## Full reference text (core wording plus relay-build-only parts)

## What InboxIQ does

InboxIQ is an SMS/MMS messaging app that also automatically categorizes
incoming messages (personal, work, promotional, one-time passcode, spam,
suspected scam) to help you triage your inbox.

## What data we access, and why

| Data | Why | Where it goes |
|---|---|---|
| SMS/MMS message content | Sending, receiving, displaying, and categorizing your texts | Stored on your device. (Relay build only: not sent anywhere unless you turn on "Connect to Claude", below.) |
| Phone numbers you message | Knowing who a conversation is with | Stored on your device. Included in what Claude sees if you turn on the Claude connection. |
| Contact names | Showing a name instead of a number | Read from your contacts; included in what Claude sees if you turn on the Claude connection. |
| Your auto-reply settings and the list of automatic texts sent | Optional "Auto-reply when away": remembering your message, schedule and limits, and showing you what was sent | Stored on your device only. The automatic text itself goes by SMS to the person who messaged you, like any text you send. |
| Microphone audio | Voice memos | Transcribed on your device; never uploaded. |

## Message sorting is on-device

InboxIQ sorts messages with a model that runs on your phone. Message text is not sent to InboxIQ's servers
or any third party for sorting.

## Optional: Connect to Claude (off by default) - RELAY BUILD ONLY

This section and the Claude rows above apply only to the relay build. The Google Play (core) build has no such feature.

If you turn on "Connect to Claude" in Settings and accept the on-screen disclosure, InboxIQ opens a
connection from your phone, only while InboxIQ is open on your screen, to a relay service operated by Kreative Koala (mcp.kreativekoala.llc, hosted on
Fly.io in the United States). Through it, Claude (Anthropic), signed in to your own Claude account, can,
when you ask it to, list your conversations, search your messages, and read threads. What Claude receives:
message text, phone numbers, contact names, and timestamps. One-time passcodes are masked. Claude may also
create text drafts; nothing is sent unless you tap Send in InboxIQ.

- The relay passes this content through in memory and does not store or log it.
- The relay stores your phone number, the hashed credentials that link your phone to your Claude connector,
  and one-time verification records. When you turn the connection on, InboxIQ asks the relay to place an
  automated voice call to your number (through Twilio) that reads out a 6-digit code; you type it into InboxIQ to
  prove the number is yours. Adding the connector in Claude uses a second automated voice call the same way.
  Claude's access to the connector expires after a period of inactivity and can be revoked.
- Anthropic's handling of what Claude receives is governed by your Claude account's terms and privacy
  settings; Kreative Koala does not control it.
- Foreground only: Claude can reach your messages only while InboxIQ is open on your screen. When you close the
  app, switch to another app or turn the screen off, the connection ends within a few seconds. Nothing runs in
  the background and it does not reconnect until you open InboxIQ again.
- To stop: turn the switch off in Settings. That disconnects immediately, discards your phone's key, and asks the
  relay to unlink your phone and revoke Claude's access to it (if the relay cannot be reached, InboxIQ says so). To remove the connector from Claude, delete InboxIQ in Claude's Connectors settings.
  To delete the relay's record of your phone number, email support@kreativekoala.llc.

## What we don't do

- We don't sell your data and have no ads or analytics SDK in the app.
- We don't use your messages for advertising or profiling, or to train AI models.
- Apart from the optional Claude connection you control, we don't share message content, contacts, or phone
  numbers with third parties.

## Data storage and deletion

Messages, numbers, and contact names live in a local database on your device; uninstalling deletes them.
Relay-side records are described above.

## Permissions this app requests, and why

See `play-console-permissions-declaration.md` in this repo for the
permission-by-permission justification submitted to Google Play; the
same explanations apply here for a general audience:

- **SMS/MMS permissions** (read, receive, send): required because InboxIQ
  is a full default SMS/MMS app.
- **Contacts**: required to show contact names instead of raw phone
  numbers.
- **Notifications**: required to alert you to new messages and drafts.
- **Microphone**: voice memos, transcribed on device.
- **Internet** (relay build only): used by the optional Claude connection. The core build requests no internet permission; Google Play performs model-pack downloads itself.

## Changes to this policy

[Placeholder — define an actual process before publishing: e.g. "we'll
update the date above and, for material changes, notify users via an
in-app notice."]

## Contact

support@kreativekoala.llc (matches the contact used site-wide on kreativekoala.llc)

---

**Note for whoever finalizes this**: hosting URL and contact email are now
filled in above. The "Changes to this policy" section still has an open
placeholder — that's a real process decision (how you'll notify users of
material changes) worth making deliberately rather than defaulting to
boilerplate.
