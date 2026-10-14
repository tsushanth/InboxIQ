# Play Console Data safety answer sheets (two variants)

The app is built in two variants (docs/ARCHITECTURE_RELAY_OPTIONAL.md). Use the sheet that matches the build you
upload. **The Play listing ships the `core` variant: use Answer set A.** Answer set B applies only to a `relay` build
(direct distribution, or a future separate listing).

# Answer set A: `core` variant (ship to Play)

The core APK has no INTERNET permission and no relay code (verify with `scripts/inspect-variant.py core --build`),
so it cannot transmit message content or anything else.

- Does your app collect or share any of the required user data types? **No**
- Encryption in transit, deletion request: not asked when the answer above is No.
- Data types: none collected, none shared. SMS/MMS, contacts, classification results and voice-memo audio are
  processed and stored on the device only (voice memos are transcribed on-device).
- Independent security review: No.
- **Auto-reply when away (0.5.1, optional, off by default) does not change any answer above.** It is an on-device
  rule that makes the app send a short text the user wrote and confirmed, as an ordinary SMS from the user's own
  number to a person who just texted them and whom they have messaged or saved. Nothing is transmitted to the
  developer or to any third party: the core build still has no INTERNET permission, no accounts, no analytics and no
  SDK that could receive data. The settings, the per-person timestamps and the list of automatic texts stay in a
  private on-device file. The recipient of the SMS is the other party in the user's own conversation, the same as
  for any text the user types; that is the app's core messaging function, not collection or sharing with a third
  party. So: collected = No, shared = No, for every data type, with the feature on or off.
- Notes for the reviewer: the optional larger on-device model is delivered by Google Play (Play Asset Delivery), not by
  the app's own network code. Play libraries add `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`,
  `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`; see `FOREGROUND_SERVICE_DECLARATION.md`.
- Policy URL: https://kreativekoala.llc/privacy (use the core text in `privacy-policy-draft.md`).

# Answer set B: `relay` variant (not for the Play listing)

The text below is the previous sheet, unchanged in substance.

## Top-level questions
- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes** (TLS: https and wss to mcp.kreativekoala.llc)
- Do you provide a way for users to request that their data be deleted? **Yes**: turning the connection off in the app discards the phone's relay key and asks the relay to unlink the phone and revoke Claude's access (best effort); relay-side phone number/token records are deleted on request to support@kreativekoala.llc (NOTE: the backend has no self-service delete endpoint yet; add one, or keep this answer as email request only).
- Independent security review: No.

## Data types (only when the user turns on "Connect to Claude"; data can leave the phone only while InboxIQ is open on screen)
| Data type | Collected | Shared | Purpose | Optional? | Notes |
|---|---|---|---|---|---|
| Messages > SMS or MMS | Yes (transmitted through relay, not stored) | Yes, with Anthropic (the AI service the user connects) | App functionality (user-directed AI assistant) | Yes, opt-in | Message text, one-time passcodes masked |
| Contacts > (contact names) | Yes (transmitted, not stored) | Yes, with Anthropic | App functionality | Yes | Names resolved from device contacts, sent in tool results |
| Personal info > Phone number | Yes (stored on relay) | No | App functionality, account management (find and verify the phone) | Yes | Entered by the user; also the number voice-called to read a 6-digit code when linking the phone and when adding the connector (the code is typed into the app and not stored on the phone); Twilio places that call as a processor |
| App info and performance | No | No | | | No analytics or crash SDK; crash log stays on device |
| Audio files / Voice | No | No | | | Mic used for on-device transcription, never leaves the phone |
| Device or other IDs | No | | | | |

Play's definitions: "collected" = transmitted off the device; passing through a service provider acting on
the developer's behalf (Fly.io, Twilio) is not "sharing", but transmitting to Anthropic at the user's direction
is conservatively declared as shared. Transient processing that is not stored is still "collected" if
transmitted. The "ephemeral processing" exemption applies only if data is processed in memory and not
retained, and it does not cover the third-party leg to Anthropic, so declare it.

## Data NOT leaving the device when the relay is off
SMS/MMS, contacts, classification results, voice memos, drafts: local Room database only.

## Related declarations
- SMS and Call Log permissions declaration: core functionality must be "default SMS handler". The relay is **not** core functionality; present it as user-initiated transfer to a service the user chooses. If the reviewer treats AI-agent access as a non-core transfer, the relay must go. See `play-console-permissions-declaration.md`.
- Foreground service declaration: none needed for our own code (the relay is foreground-only); see `FOREGROUND_SERVICE_DECLARATION.md` for the library-injected `dataSync` entry.
- Policy URL: https://kreativekoala.llc/privacy (section 10 must first be replaced with `privacy-policy-draft.md` text).
