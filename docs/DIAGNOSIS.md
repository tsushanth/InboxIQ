# InboxIQ diagnosis (2026-10-13)

Scope: app at branch `diagnose-fix` (from `wip-0.4.2-relay-boot`), relay backend `inboxiq-mcp-backend` (read-only),
legacy `inboxiq-config` (read-only), Fly state via read-only flyctl, live privacy page, store-listing copy.
The owner has not said what failed, and the Play API exposes no policy status, so the ranking below is
inference from code and listing evidence. Confidence is stated per finding. Nothing here proves a Play
notice exists; the first thing the owner should do is open Play Console > Policy status / Inbox.

## Ranked findings

### 1. Listing, privacy policy and permissions declaration say "no data leaves the device"; versions 0.4.0+ send message text to a cloud service. (Critical, certain the mismatch exists)
Evidence:
- `store-listing/en-US/short_description.txt`: "100% private, no cloud AI". `full_description.txt`: "Your messages are never sent to a server", "There is no cloud AI service involved in reading or categorizing your messages".
- Live https://kreativekoala.llc/privacy section 10: "No message content is ever sent to a cloud AI service or any external server for classification or any other purpose." It never mentions Claude or the relay.
- `docs/play-console-permissions-declaration.md`: "no message content is ever transmitted to any server, ours or third-party"; "Nothing is synced to any backend."
- Code: `mcp/RelayForegroundService.kt` holds a WebSocket to `wss://mcp.kreativekoala.llc/phone-ws`; `mcp/McpTools.kt` answers `list_threads`, `search_messages`, `read_thread` with message bodies, sender numbers, contact names and timestamps (up to 50 threads / 100 messages per call, any thread, unfiltered).
- Production is 0.4.1 (versionCode 7), which contains this feature.
Policy exposure: Misleading Claims / Deceptive Behavior, User Data (undisclosed collection and sharing), Data safety form inaccuracy, and the SMS and Call Log permissions policy (SMS data may be used only for core functionality and not transferred to third parties except as the user directs; AI-agent access is a high-risk use). This is the most probable reason a review, suspension, or "app does not match listing" notice exists. Not verified: whether Google has actually flagged it.

### 2. The relay shipped with no informed consent. (High, certain)
Before this branch the only gate was a Switch plus a phone-number field (`CloudConnectSection.kt`); copy said "It can read messages and propose texts" with no mention of the cloud service, Anthropic, what fields are shared, or that bank/OTP texts are included. Enabled state persisted forever. FIXED in this branch (see "Fixed").

### 3. Relay exposes one-time passcodes to the AI agent. (High, certain)
`read_thread`/`search_messages` returned raw bodies including 2FA codes and a prompt-injected agent (incoming SMS text is attacker-controlled input to the agent) could read them back. FIXED: codes in OTP-classified messages are masked (`RelayRedaction`). Heuristic only; not a guarantee (see limits below).

### 4. Backend `inboxiq-mcp-backend`: weak ownership model and no revocation. (High, certain from code; needs owner decision, backend not touched)
- `POST /phone/link` is unauthenticated and upserts `phone_number -> relay_token_hash` (`ON CONFLICT DO UPDATE`). Anyone who knows a victim's number can re-link it to their own token, hijack the `phoneConnections` slot, and (a) knock the real phone offline, (b) answer the victim's legitimate Claude session with fabricated text (prompt injection into the victim's agent). They cannot read real messages without also passing the voice OTP, but (b) is real. Fix: bind link to a first-seen token (reject if a different hash is already linked unless proven with the old token or a fresh voice OTP).
- No revocation: access tokens last 1h but refresh tokens never expire and are never deleted; `/token` refresh issues new access tokens forever. App "off" previously did not touch the backend. Mitigated in the app (token discarded on disable) but the backend should add `DELETE /phone/link` authenticated by relay token that also deletes that phone's access/refresh tokens, and a refresh-token TTL.
- Ownership proof is a voice call to the number, entered by whoever is at the Claude consent page; there is no consent screen on the server saying what scope the connector gets (single implicit scope).
- Retention: SQLite on a Fly volume stores phone numbers (plaintext), hashed tokens, OTP challenge rows (6-digit code hashed with unsalted SHA-256, brute-forceable offline but short-lived and the file is private), OAuth client registrations. Expired rows are never purged. Message bodies are not stored and no body logging exists (no request logger; Fly logs show only proxy errors). Twilio retains call logs for the OTP calls (Twilio-side retention not verified).
- `/mcp` rate limit is per-IP (120/min), `trust proxy` = 1. Fine for now.
- Deploy is single machine `sjc`, `min_machines_running=1`, healthy (`/healthz` returns ok; deployed version 5, last update 2026-09-27). Secrets present by name: TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_PHONE_NUMBER.

### 5. Foreground service design was invalid on Android 14-16 / targetSdk 36. (High, certain from platform rules; not device-tested here)
- `foregroundServiceType="dataSync"` on target 35+ has a hard ~6h-per-24h cap; the platform then calls `Service.onTimeout()` and the app is crashed (`ForegroundServiceDidNotStopInTimeException`) if the service does not stop. The service had no `onTimeout`. So an enabled relay crashed the app after ~6h of connection. FIXED (specialUse, which has no cap, plus an `onTimeout` that stops cleanly).
- WIP `BootReceiver` -> `WorkManager` (45s delay) -> `RelayStartWorker` -> `startForegroundService`: the 45s trick does not make a legal start. Android 12+ forbids starting a foreground service from the background unless an exemption applies (WorkManager is not one), and Android 15 explicitly forbids dataSync from BOOT_COMPLETED. The WIP likely "worked" in the owner's test because that phone was battery-optimization-exempt (an exemption that permits background FGS starts); this is an inference, not verified. On typical user phones the worker would throw `ForegroundServiceStartNotAllowedException` (crash loop on every boot for anyone with the relay left on). FIXED by deleting the boot path; the user reopens the app to reconnect (`MainActivity` already restarts it from a foreground context).
- Earlier history: Play blocked an upload until `FOREGROUND_SERVICE_DATA_SYNC` was removed (commit 57e8c6c); 3f7593e added it back. Play requires every FGS type to be declared in Console with a justification and demo video. Without that declaration 0.4.x may be rejected again. Draft in `docs/FOREGROUND_SERVICE_DECLARATION.md`.
- Service bugs also fixed: a second `onStartCommand` (START_STICKY restart, repeated starts) opened another WebSocket without closing the first (duplicate answers, leaked sockets); constant 5s reconnect loop even when the server rejects the token (now exponential to 5 min).

### 6. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (WIP) is not allowed for this app. (High, policy knowledge; I did not find a Play API to confirm per-app status)
Play restricts this permission to apps whose core function breaks without it. SMS delivery to the default SMS app is a manifest-registered broadcast and is unaffected by Doze; only the optional AI relay wants it. Using it risks rejection. REMOVED; the settings now opens the system battery list (no permission) instead.

### 7. Declarations missing or stale. (Medium, certain)
`RECORD_AUDIO` (voice memos, on-device whisper) appears in no declaration or listing text. The Data safety form was presumably filed as "no data shared"; it must change (see `docs/DATA_SAFETY.md`). Privacy policy section 10 must be rewritten (`docs/privacy-policy-draft.md`). `docs/mcp-server.md` describes the retired LAN server and a `scripts/inboxiq-pair.sh` pairing flow that no longer exists in the app; it is stale and now misleading.

### 8. Legacy `inboxiq-config` is gone from Fly; nothing in the app uses it. (Informational, certain)
`flyctl status -a inboxiq-config` -> "Could not find App"; `inboxiq-config.fly.dev` does not resolve; `flyctl apps list` shows only `inboxiq-mcp` among InboxIQ apps. Grep of the app, docs, fastlane and gradle files finds no reference. `CrashLogger` in `InboxIqApp.kt` is file-only, `AutoHealWorker` makes no network call. The repo `~/Documents/GitHub/inboxiq-config` still exists locally (server code for `/mms-strategy`, `/mms-outcome` telemetry keyed on device model + carrier). Old app builds (before the commits that removed it) may have called it; those calls now fail silently. Recommendation: nothing to restore; archive or delete the local repo; do not recreate it. If Play's Data safety form ever listed "device model / carrier" collection tied to that backend, remove it.

### 9. Smaller runtime issues. (Low to medium)
- `SendDraftWorker` retries `SmsSender.send` on any exception; `SmsSender.send` inserts a message row before sending, so a failure after insert could duplicate the row/send on retry. Not changed (needs device testing); low likelihood.
- No runtime request for `POST_NOTIFICATIONS`/`READ_CONTACTS` anywhere in code (only `RECORD_AUDIO`). On Android 13+ the SMS role grants SMS, contacts and notifications groups to the role holder, so this works once InboxIQ is default; not verified on device (device not touched, rule). Before the app is default it shows no names or notifications, which is acceptable.
- Room: no `fallbackToDestructiveMigration()` (only on downgrade); migrations 7 to 14 present; DB v7 shipped in versionCode 1 so no missing earlier migration. Good.
- `allowBackup=false`, no minify, no `READ_MEDIA_*`, no ads/analytics SDK found. Keystore passwords are read from gitignored `local.properties`; git history contains only the `localProps.getProperty(...)` lookups, no literal passwords found in `app/build.gradle.kts` history. (Keystore path is hard-coded to the owner's home; release signing is out of scope.)
- Redaction limits: masking relies on `HeuristicRules` (digits plus words otp/code/verify/verification). Codes in messages without those words ("Your PIN is 4821") are not masked. Message text can contain other secrets (bank balances, addresses); the consent text says so.

## What cannot be determined from here
- Whether Google actually rejected, suspended, or warned. Check Play Console Inbox and Policy status.
- Whether 0.4.0/0.4.1 crashed for relay users after 6h (consistent with code; no crash data available).
- Real-device behaviour (device deliberately not used).

## Fixed on this branch
- Consent gate (`RelayConfig`, `RelayConsentDialog`, `strings.xml`), token discard on disable, service guard that refuses to run without consent. Existing 0.4.x users who enabled the relay will be switched off until they re-accept.
- Boot receiver/worker removed; `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` removed; FGS type specialUse; `onTimeout`; duplicate-socket and reconnect fixes; visible Disconnect action.
- OTP masking for relay output.
- Corrected store text, Data safety sheet, privacy draft, permissions declaration, FGS declaration (docs only; nothing uploaded).

## Needs the owner
See the final report; short list: Play Console edits (listing, Data safety, FGS declaration, permissions declaration), decision to keep/gate/remove the relay, backend fixes in `inboxiq-mcp-backend`, privacy page rewrite, delete stale `inboxiq-config` repo.
