# Play Console Permissions Declaration — draft

Draft answers for Play Console's "Sensitive App Permissions" declaration
form, covering SMS/Call Log/default-app permissions. Google requires a
Permissions Declaration Form submission for any app requesting
READ_SMS/RECEIVE_SMS/SEND_SMS/RECEIVE_MMS — approval is gated on the app
qualifying as the default SMS, Phone, or Assistant handler, and on
"core functionality" justification. This is a draft to review/refine before
actual submission, not yet submitted.

> **CORRECTED 2026-10-13.** The earlier text of this file said no message content is ever transmitted
> to any server. That has been false since app 0.4.0, which includes the optional "Connect to Claude"
> relay. The statements below are the corrected, true versions for the `relay` build; the `core` build makes none of those transfers. Do not reuse the older wording.

## Variants

Two builds exist (docs/ARCHITECTURE_RELAY_OPTIONAL.md). **Submit this declaration for the `core` build**, which is the
one that goes to Play. Use the "core" justification text below and ignore every statement about the Claude
connection, which exists only in the `relay` build. For `core`: no `INTERNET` permission (verify with
`scripts/inspect-variant.py core`), no message content ever leaves the device, no relay.

Core justification, ready to paste for READ_SMS / RECEIVE_SMS / SEND_SMS / RECEIVE_MMS / RECEIVE_WAP_PUSH:

> InboxIQ is a full default SMS/MMS handler app. These permissions are required to read, receive and send text
> messages, the app's entire purpose. It also categorizes incoming messages on the device with a bundled
> model, and offers quick replies. The app has no internet permission: message content is never transmitted.

READ_CONTACTS (core): only to show a sender's saved name instead of a number, read locally, never transmitted.

## Which permissions are we declaring

- `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`
- `READ_CONTACTS` (for display-name resolution in the inbox UI)
- Not SMS-policy permissions but should be explained in listing/Data safety: `RECORD_AUDIO` (on-device voice memo transcription), `POST_NOTIFICATIONS`, `INTERNET` (relay build only; the core build does not request it). InboxIQ declares no foreground-service or wake-lock permission of its own; libraries add some (see FOREGROUND_SERVICE_DECLARATION.md)
- Removed from our own manifest by 0.5.0: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`. Libraries still add `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` (WorkManager) and `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (Play Asset Delivery); see FOREGROUND_SERVICE_DECLARATION.md

## Core functionality justification (per permission)

**READ_SMS / RECEIVE_SMS / SEND_SMS / RECEIVE_MMS / RECEIVE_WAP_PUSH**

> InboxIQ is a full replacement default SMS/MMS handler app. These
> permissions are required to read, receive, and send text messages —
> the app's sole and entire purpose. InboxIQ additionally provides
> on-device (not server-side) automatic categorization of incoming
> messages into a fixed set of labels (personal / work / promotional /
> one-time-passcode / spam / suspected scam) to help users triage their
> inbox. All classification happens locally on the device using a bundled
> machine-learning model; no message content is transmitted for classification.
> Separately, an optional feature, OFF by default and enabled only after an
> on-screen disclosure and consent, lets the user connect their own Claude
> account (claude.ai / Claude Desktop). When on, message text, sender numbers
> and contact names are sent, at the user's request and only while InboxIQ is
> open on screen (no background service; closing the app ends access), through a
> relay operated by the developer to Anthropic's Claude. Replies are only drafts; the user
> must tap Send in InboxIQ. One-time passcodes are masked. This feature is a
> user-directed transfer, not required for the app's core messaging function.

**READ_CONTACTS**

> Used solely to display a sender's saved contact name instead of a raw
> phone number in the conversation list and thread view — standard
> messaging-app behavior. Contact names are read locally; if the user
> enables the optional Claude connection, names of the contacts in conversations
> Claude reads are included in what is sent.

## Why default-handler status, not just permissions

InboxIQ implements the full default-SMS-app contract (SMS_DELIVER
receiver, WAP_PUSH_DELIVER receiver, RESPOND_VIA_MESSAGE headless service,
SENDTO/sms/smsto/mms/mmsto intent filters) required to be eligible for and
hold `android.app.role.SMS` — not merely requesting the permissions
without the role. Users explicitly opt in via the system's own
"set as default SMS app" dialog (`RoleManager.createRequestRoleIntent`),
which Android — not InboxIQ — presents and controls.

## Data handling summary (for the "how is this data used" question)

- Message content, sender addresses, and contact names are stored **only**
  in a local on-device database (Room/SQLite). Nothing is synced or backed up.
  Exception: the optional Claude connection (above) transmits them on demand;
  this happens only while the app is open on screen. The relay does not store message content but does store the user's phone number
  and hashed tokens.
- The classification model runs **on-device** via ONNX Runtime Mobile.
  No network call is made to classify a message. (The optional Claude relay is
  the only path where message content leaves the phone.)
- No analytics SDK reads message content. (If/when we add crash reporting
  or usage analytics, it must be scoped to explicitly exclude message
  bodies, addresses, and contact names — flag this constraint before
  adding any such SDK.)
- No ads SDK is present.

## Open items before actual submission

- [x] Privacy policy URL live: https://kreativekoala.llc/privacy
      (section 10, "App-Specific Disclosures: InboxIQ") — still needs to be
      pasted into the actual Play Console listing field when the app is
      submitted; see `privacy-policy-draft.md` in this folder
- [x] Relay decision: resolved by shipping the `core` variant to Play; the relay is only in the `relay` build. Owner still decides whether a relay build is distributed at all.
- [ ] Screenshots/video demonstrating the app functioning as a full SMS
      client (Google reviews for "is this really a messaging app or just
      permission-grabbing")
- [ ] Confirm target API level meets current Play requirements at
      submission time (policy tightens roughly yearly)
- [ ] Re-verify this draft's permission list matches `AndroidManifest.xml`
      exactly before submitting — this doc can drift from the manifest as
      the app evolves
