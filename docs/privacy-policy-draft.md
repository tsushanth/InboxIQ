# InboxIQ Privacy Policy — draft

**Status: LIVE.** Hosted as a dedicated section within the shared Kreative
Koala LLC privacy policy at:
**https://kreativekoala.llc/privacy#** (section 10, "App-Specific
Disclosures: InboxIQ") — source in `~/Documents/GitHub/kreative-koala-legal/privacy.html`,
pushed 2026-08-02 (commit `637ce29`). Use that URL for the Play Console
"Privacy Policy" field, not this file — this file remains the working
draft/reference copy.

_Last updated: 2026-08-02_

## What InboxIQ does

InboxIQ is an SMS/MMS messaging app that also automatically categorizes
incoming messages (personal, work, promotional, one-time passcode, spam,
suspected scam) to help you triage your inbox.

## What data we access, and why

| Data | Why | Where it goes |
|---|---|---|
| SMS/MMS message content | Core app function: sending, receiving, displaying, and categorizing your texts | Stored only on your device. Never transmitted anywhere. |
| Phone numbers you message | Core app function: knowing who a conversation is with | Stored only on your device. |
| Contact names | Displaying a name instead of a raw number in your conversation list | Read from your device's contacts; never stored elsewhere or transmitted. |

## Message classification is 100% on-device

InboxIQ uses a machine-learning model that runs entirely on your phone to
categorize messages. Your message content is never sent to InboxIQ's
servers, or any third party's, for this or any other purpose. There is no
cloud AI service involved in classification.

## What we don't do

- We don't sell your data. We don't have an ads SDK in the app.
- We don't read your messages for advertising, profiling, or any purpose
  other than the categorization feature described above and displaying
  your own messages back to you.
- We don't share message content, contacts, or phone numbers with any
  third party.

## Data storage and deletion

All data described above lives in a local database on your device.
Uninstalling the app deletes it. [If/when a backup or sync feature is
added, this section must be rewritten before shipping it — flag any such
change as requiring a privacy-policy update first.]

## Permissions this app requests, and why

See `play-console-permissions-declaration.md` in this repo for the
permission-by-permission justification submitted to Google Play; the
same explanations apply here for a general audience:

- **SMS/MMS permissions** (read, receive, send): required because InboxIQ
  is a full default SMS/MMS app.
- **Contacts**: required to show contact names instead of raw phone
  numbers.
- **Notifications**: required to alert you to new messages.

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
