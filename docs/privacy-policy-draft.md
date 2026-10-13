# InboxIQ Privacy Policy — draft

**Status: LIVE copy is OUT OF DATE (says nothing leaves the device). Replace section 10 with the text below before the next release.** Original note: LIVE. Hosted as a dedicated section within the shared Kreative
Koala LLC privacy policy at:
**https://kreativekoala.llc/privacy#** (section 10, "App-Specific
Disclosures: InboxIQ") — source in `~/Documents/GitHub/kreative-koala-legal/privacy.html`,
pushed 2026-08-02 (commit `637ce29`). Use that URL for the Play Console
"Privacy Policy" field, not this file — this file remains the working
draft/reference copy.

_Last updated: 2026-10-13 (draft; the live page still carries the older text)_

## What InboxIQ does

InboxIQ is an SMS/MMS messaging app that also automatically categorizes
incoming messages (personal, work, promotional, one-time passcode, spam,
suspected scam) to help you triage your inbox.

## What data we access, and why

| Data | Why | Where it goes |
|---|---|---|
| SMS/MMS message content | Sending, receiving, displaying, and categorizing your texts | Stored on your device. Not sent anywhere unless you turn on "Connect to Claude" (below). |
| Phone numbers you message | Knowing who a conversation is with | Stored on your device. Included in what Claude sees if you turn on the Claude connection. |
| Contact names | Showing a name instead of a number | Read from your contacts; included in what Claude sees if you turn on the Claude connection. |
| Microphone audio | Voice memos | Transcribed on your device; never uploaded. |

## Message sorting is on-device

InboxIQ sorts messages with a model that runs on your phone. Message text is not sent to InboxIQ's servers
or any third party for sorting.

## Optional: Connect to Claude (off by default)

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
- **Internet**: only used by the optional Claude connection and Google Play downloads.

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
