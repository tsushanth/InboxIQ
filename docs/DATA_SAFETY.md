# Play Console Data safety answer sheet (draft, version with optional Claude connection)

Do not submit until the relay decision (keep / gate / remove) is made. If the relay is removed from the
app, the "Shared" and "Collected" answers below revert to "no data collected / shared", and the older
listing text is true again.

## Top-level questions
- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes** (TLS: https and wss to mcp.kreativekoala.llc)
- Do you provide a way for users to request that their data be deleted? **Yes**: turning the connection off in the app discards the phone's relay key; relay-side phone number/token records are deleted on request to support@kreativekoala.llc (NOTE: the backend has no self-service delete endpoint yet; add one, or keep this answer as email request only).
- Independent security review: No.

## Data types (only when the user turns on "Connect to Claude")
| Data type | Collected | Shared | Purpose | Optional? | Notes |
|---|---|---|---|---|---|
| Messages > SMS or MMS | Yes (transmitted through relay, not stored) | Yes, with Anthropic (the AI service the user connects) | App functionality (user-directed AI assistant) | Yes, opt-in | Message text, one-time passcodes masked |
| Contacts > (contact names) | Yes (transmitted, not stored) | Yes, with Anthropic | App functionality | Yes | Names resolved from device contacts, sent in tool results |
| Personal info > Phone number | Yes (stored on relay) | No | App functionality, account management (find and verify the phone) | Yes | Entered by the user; also the number voice-called for OTP; Twilio places that call as a processor |
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
- Foreground service declaration: `FOREGROUND_SERVICE_DECLARATION.md`.
- Policy URL: https://kreativekoala.llc/privacy (section 10 must first be replaced with `privacy-policy-draft.md` text).
