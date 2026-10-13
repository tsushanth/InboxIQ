# InboxIQ diagnosis (2026-10-13)

> **Update (branch integrate-0.5.0): the relay is now foreground-only.** Decision: the optional "Connect to Claude"
> relay works only while InboxIQ is open on screen. `RelayForegroundService`, its manifest entry, the
> `specialUse` permission, our own `FOREGROUND_SERVICE` and `WAKE_LOCK` entries and the battery-optimization
> prompt are gone. The connection is an in-process manager tied to the app's visible lifecycle (connects on start,
> disconnects about 5 s after the app leaves the screen, backoff while visible, Settings shows the status).
> Findings 5 and 6 and the "specialUse versus Play policy" section below describe the previous design and are kept
> as history. No foreground-service declaration is needed for our own code; see `FOREGROUND_SERVICE_DECLARATION.md`
> for what libraries still inject. Consent version is now 2, so 0.4.x users must accept the new text.

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

### 4. Backend `inboxiq-mcp-backend`: ownership model and token lifecycle are being hardened. (High, needs owner follow-up)
The review of the relay backend found that phone-to-token linking needs stronger proof of control, and that
tokens need expiry and revocation. Both are being hardened in the backend repo; the app keeps a seam for the new
linking scheme (`RelayLinkClient`) and shows "Reconnect needed" when the server rejects a token (401/403).
Implementation detail is intentionally not recorded in this public repo. Also pending on the backend side:
purging expired records, a self-service delete for the stored phone number, and a clear scope screen at connector approval.
The relay does not store message bodies. Deploy: single Fly machine, healthy at review time.

### 5. (Superseded by the foreground-only relay) Foreground service design was invalid on Android 14-16 / targetSdk 36. (High, certain from platform rules; not device-tested here)
- `foregroundServiceType="dataSync"` on target 35+ has a hard ~6h-per-24h cap; the platform then calls `Service.onTimeout()` and the app is crashed (`ForegroundServiceDidNotStopInTimeException`) if the service does not stop. The service had no `onTimeout`. So an enabled relay crashed the app after ~6h of connection. FIXED (specialUse, which has no cap, plus an `onTimeout` that stops cleanly).
- WIP `BootReceiver` -> `WorkManager` (45s delay) -> `RelayStartWorker` -> `startForegroundService`: the 45s trick does not make a legal start. Android 12+ forbids starting a foreground service from the background unless an exemption applies (WorkManager is not one), and Android 15 explicitly forbids dataSync from BOOT_COMPLETED. The WIP likely "worked" in the owner's test because that phone was battery-optimization-exempt (an exemption that permits background FGS starts); this is an inference, not verified. On typical user phones the worker would throw `ForegroundServiceStartNotAllowedException` (crash loop on every boot for anyone with the relay left on). FIXED by deleting the boot path; the user reopens the app to reconnect (`MainActivity` already restarts it from a foreground context).
- Earlier history: Play blocked an upload until `FOREGROUND_SERVICE_DATA_SYNC` was removed (commit 57e8c6c); 3f7593e added it back. Play requires every FGS type to be declared in Console with a justification and demo video. Without that declaration 0.4.x may be rejected again. Draft in `docs/FOREGROUND_SERVICE_DECLARATION.md`.
- Service bugs also fixed: a second `onStartCommand` (START_STICKY restart, repeated starts) opened another WebSocket without closing the first (duplicate answers, leaked sockets); constant 5s reconnect loop even when the server rejects the token (now exponential to 5 min).

### 6. (Resolved) `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (WIP) is not allowed for this app. (High, policy knowledge; I did not find a Play API to confirm per-app status)
Play restricts this permission to apps whose core function breaks without it. SMS delivery to the default SMS app is a manifest-registered broadcast and is unaffected by Doze; only the optional AI relay wants it. Using it risks rejection. REMOVED; the settings now opens the system battery list (no permission) instead.

### 7. Declarations missing or stale. (Medium, certain)
`RECORD_AUDIO` (voice memos, on-device whisper) appears in no declaration or listing text. The Data safety form was presumably filed as "no data shared"; it must change (see `docs/DATA_SAFETY.md`). Privacy policy section 10 must be rewritten (`docs/privacy-policy-draft.md`). `docs/mcp-server.md` describes the retired LAN server and a `scripts/inboxiq-pair.sh` pairing flow that no longer exists in the app; it is stale and now misleading.

### 8. Legacy `inboxiq-config` is gone from Fly; nothing in the app uses it. (Informational, certain)
`flyctl status -a inboxiq-config` -> "Could not find App"; `inboxiq-config.fly.dev` does not resolve; `flyctl apps list` shows only `inboxiq-mcp` among InboxIQ apps. Grep of the app, docs, fastlane and gradle files finds no reference. `CrashLogger` in `InboxIqApp.kt` is file-only, `AutoHealWorker` makes no network call. The repo `~/Documents/GitHub/inboxiq-config` still exists locally (server code for `/mms-strategy`, `/mms-outcome` telemetry keyed on device model + carrier). Old app builds (before the commits that removed it) may have called it; those calls now fail silently. Recommendation: nothing to restore; archive or delete the local repo; do not recreate it. If Play's Data safety form ever listed "device model / carrier" collection tied to that backend, remove it.

### 9. Smaller runtime issues. (Low to medium)
- `SendDraftWorker` retried `SmsSender.send` on any exception and `SmsSender.send` inserts a message row before dispatch, so a retry could create duplicate rows or a duplicate real SMS. FIXED: drafts are claimed atomically (`AgentDraftDao.claimForSending`, status `SENDING`), never auto-retried; a failure returns the draft to PENDING for the user to resend; a draft stuck in SENDING (process died) stays visible and deletable. Tested (`AgentDraftClaimTest`).
- No runtime request for `POST_NOTIFICATIONS`/`READ_CONTACTS` anywhere in code (only `RECORD_AUDIO`). On Android 13+ the SMS role grants SMS, contacts and notifications groups to the role holder, so this works once InboxIQ is default; not verified on device (device not touched, rule). Before the app is default it shows no names or notifications, which is acceptable.
- Room: no `fallbackToDestructiveMigration()` (only on downgrade); migrations 7 to 14 present; DB v7 shipped in versionCode 1 so no missing earlier migration. Good.
- `allowBackup=false`, no minify, no `READ_MEDIA_*`, no ads/analytics SDK found. Keystore passwords are read from gitignored `local.properties`; git history contains only the `localProps.getProperty(...)` lookups, no literal passwords found in `app/build.gradle.kts` history. (Keystore path is hard-coded to the owner's home; release signing is out of scope.)
- Redaction limits: masking relies on `HeuristicRules` (digits plus words otp/code/verify/verification). Codes in messages without those words ("Your PIN is 4821") are not masked. Message text can contain other secrets (bank balances, addresses); the consent text says so.

## Merged-manifest check (from the built debug APK's merged manifest)
My manifest edits are not the whole story. Libraries still inject:
- `FOREGROUND_SERVICE_DATA_SYNC` plus a disabled `ExtractionForegroundService` (foregroundServiceType dataSync) from `com.google.android.play:asset-delivery:2.3.0` (the Gemma on-demand asset pack). This is probably what Play's earlier "drop FOREGROUND_SERVICE_DATA_SYNC" block was about, and it will be in the AAB regardless of the relay. Either declare dataSync in the Play FGS form too (justification: Play asset pack extraction, system-managed), or remove both with `tools:node="remove"` in the manifest and verify the Gemma download on a device. I did NOT remove them: it could break the MID tier download and I could not test on a device.
- `RECEIVE_BOOT_COMPLETED` from WorkManager 2.9.1 (reschedules periodic work; not a service start). Harmless, but it means the permissions declaration should not claim it is absent.

## specialUse versus Play policy
Play requires every foreground service type to be declared in Console, and `specialUse` needs a written justification and usually a demo video; reviewers accept it only when no standard type fits. A user-opted-in persistent socket to an AI connector plausibly fits no standard type, but combined with SMS data going to an AI service it will draw close scrutiny, and I judge acceptance uncertain (I cannot check Play's decision; this is judgment, not fact). The alternative that avoids the issue entirely is a foreground-only connection: hold the WebSocket from the visible app (lifecycle-scoped), no foreground service, no wake lock, no notification, no FGS declaration. Trade-off: Claude can reach the phone only while InboxIQ is open on screen, which makes the feature much less useful (the connector is for asking Claude from another device). It is also the lower-risk option for policy. IMPLEMENTED in 0.5.0 on the owner's decision. Ranking of options by Play risk: remove relay < foreground-only < specialUse FGS < dataSync FGS.

## Verification (2026-10-13, this branch, `./gradlew testDebugUnitTest assembleDebug`, GRADLE_USER_HOME=$HOME/.gradle because the clearvoice home lacked AGP 8.13.2 offline)
- Compiled: yes, `assembleDebug` succeeded (debug APK built, not installed anywhere).
- Unit tests: 31 run, 0 failures, 0 errors (20 pre-existing tests + RelayRedactionTest 3 + RelayConfigConsentTest 5 + AgentDraftClaimTest 3).
- Two problems found by actually building, both fixed: (1) a raw apostrophe in `strings.xml` broke resource compilation (my own bug); (2) the pre-existing Robolectric tests all failed on the base branch because Robolectric 4.14.1 cannot run SDK 36 (targetSdk 36 commit); fixed with `app/src/test/resources/robolectric.properties` (`sdk=35`). So at the WIP base commit the unit test suite did not pass.
- Not verified: UI rendering of the consent dialog, the service at runtime, the Android 15 behaviour, the Gemma download, anything on a real device (device not used).
- Secrets: gitleaks over the branch range (54e3fc7^..HEAD): no leaks. `local.properties` is git-ignored and untracked; only the `localProps.getProperty(...)` lookups appear in tracked files. A full-directory gitleaks scan flagged two generic-key hits only inside `app/build` cache files (untracked build output), which I deleted.

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
