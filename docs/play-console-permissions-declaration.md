# Play Console Permissions Declaration — draft

Draft answers for Play Console's "Sensitive App Permissions" declaration
form, covering SMS/Call Log/default-app permissions. Google requires a
Permissions Declaration Form submission for any app requesting
READ_SMS/RECEIVE_SMS/SEND_SMS/RECEIVE_MMS — approval is gated on the app
qualifying as the default SMS, Phone, or Assistant handler, and on
"core functionality" justification. This is a draft to review/refine before
actual submission, not yet submitted.

## Which permissions are we declaring

- `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`
- `READ_CONTACTS` (for display-name resolution in the inbox UI)

## Core functionality justification (per permission)

**READ_SMS / RECEIVE_SMS / SEND_SMS / RECEIVE_MMS / RECEIVE_WAP_PUSH**

> InboxIQ is a full replacement default SMS/MMS handler app. These
> permissions are required to read, receive, and send text messages —
> the app's sole and entire purpose. InboxIQ additionally provides
> on-device (not server-side) automatic categorization of incoming
> messages into a fixed set of labels (personal / work / promotional /
> one-time-passcode / spam / suspected scam) to help users triage their
> inbox. All classification happens locally on the device using a bundled
> machine-learning model; no message content is ever transmitted to any
> server, ours or third-party.

**READ_CONTACTS**

> Used solely to display a sender's saved contact name instead of a raw
> phone number in the conversation list and thread view — standard
> messaging-app behavior. Contact data is read locally and never
> transmitted off-device.

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
  in a local on-device database (Room/SQLite). Nothing is synced to any
  backend.
- The classification model runs **on-device** via ONNX Runtime Mobile.
  No network call is made to classify a message.
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
- [ ] Screenshots/video demonstrating the app functioning as a full SMS
      client (Google reviews for "is this really a messaging app or just
      permission-grabbing")
- [ ] Confirm target API level meets current Play requirements at
      submission time (policy tightens roughly yearly)
- [ ] Re-verify this draft's permission list matches `AndroidManifest.xml`
      exactly before submitting — this doc can drift from the manifest as
      the app evolves
