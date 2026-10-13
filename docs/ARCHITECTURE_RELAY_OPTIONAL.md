# Making the Claude relay optional: two variants from one codebase

Owner direction: the core product (SMS/MMS, on-device sorting, quick replies) must not depend on the
"Connect to Claude" relay in any way, and we must be able to ship an app with no relay code, no relay
permission and no network capability.

## Variants

| | `core` | `relay` |
|---|---|---|
| Gradle tasks | `assembleCoreDebug`, `bundleCoreRelease`, `testCoreDebugUnitTest` | `assembleRelayDebug`, `bundleRelayRelease`, `testRelayDebugUnitTest` |
| applicationId / signing | `com.inboxiq.app` | `com.inboxiq.app` (same, so a user's data carries over; the two cannot be installed side by side) |
| INTERNET permission | **absent** | present (relay source set manifest) |
| Relay code, OkHttp, relay strings | none | yes |
| Ship to | Google Play | opt-in / direct distribution only (see "Which variant to ship") |

## Mechanism: product-flavor source sets, not a `:relay` module

A Gradle library module would be the textbook answer, and it was rejected because the relay is not a leaf:
it reads `MessageEntity`, `AppDatabase`, `ContactResolver`, `HeuristicRules`, `SmsSender`, `MainActivity` and `R`
from `:app`, and `SendDraftWorker` sits in the same dependency knot. A `:relay` module that `:app` depends on would
have to depend back on `:app` (a cycle), so making it work means first extracting `:core-data`, `:sms` and
`:classify` modules. That is a large, risky refactor of code two other in-flight branches are editing
(classification, import speed) and it buys nothing the flavor split does not: with flavors, source sets that are not
selected are never compiled, so the core APK contains no relay class, string, library or manifest entry.

Layout:

- `app/src/main/.../feature/RelayFeature.kt`: the only seam. Three members: `onAppStart(app)`, a composable
  `SettingsSection()`, and `introExceptionText()` (null in core).
- `app/src/core/.../feature/RelayFeatures.kt`: no-op `RelayFeatures.instance`.
- `app/src/relay/.../feature/RelayFeatures.kt`: wires `RelayRuntime.install`, `CloudConnectSection`, `AgentDraftsSection`.
  Same object name and package in both source sets, so the main code just calls `RelayFeatures.instance`. No
  reflection, no `ServiceLoader`, no `BuildConfig` flag to forget to check.
- `app/src/relay/` also holds the whole `mcp` package, `SendDraftWorker`, the relay `strings.xml` (consent text,
  status text, and the relay-specific variants of `settings_privacy_note` and `intro_relay_exception`) and
  `AndroidManifest.xml` with `INTERNET`.
- `app/src/core/res/values/strings.xml` has the core `settings_privacy_note` (no mention of Claude).
- Relay unit tests moved to `app/src/testRelay/`. Core-only guard tests are in `app/src/testCore/`.
- `okhttp` is a `relayImplementation` dependency only.

Core UI: the Settings privacy paragraph and the first-run intro never mention Claude; the "Connect to Claude" and
"Drafts from Claude" sections do not render (nothing replaces them).

## Database compatibility

No schema change. `AgentDraftEntity`, `AgentDraftDao` and the `agent_drafts` table stay in `src/main/.../data` and in
`AppDatabase`, so both variants open the same database version with the same identity hash. The core variant simply
never reads or writes the table. Room migrations and `app/schemas/*.json` are untouched. Moving a user from `relay`
to `core` (or back) keeps all messages; leftover `agent_drafts` rows are ignored in core. `AgentDraftClaimTest` and
`MigrationTest` run in both variants.

## Verifying the core variant

`scripts/inspect-variant.py core --build` prints the merged-manifest permissions, activities, services, receivers and
providers, the InboxIQ classes that reference network APIs, and the network-API classes present in the APK, then exits
non-zero if core has INTERNET or any InboxIQ class that touches a network API. Run it for `relay` to compare.
`CoreHasNoRelayTest` asserts at unit-test time that the relay classes and strings do not exist and that INTERNET is
not requested.

Honest caveat on "network-capable classes": the core APK still contains `com.squareup.okhttp` 2.x (about 160 classes)
because `org.fossify:mmslib` (MMS PDU encoding) depends on it, plus Android framework references such as
`ConnectivityManager` from WorkManager and Play libraries. Without the INTERNET permission the OS refuses to let this app
open sockets, so that code cannot reach the network; no InboxIQ-authored class references any network API. MMS
send uses the system path (`setUseSystemSending(true)`) and MMS download uses `SmsManager.downloadMultimediaMessage`,
both performed by the system, so neither needs our INTERNET permission. The Gemma model pack is delivered by Google Play
(Play Asset Delivery), also without our INTERNET permission. Neither has been exercised on a device for this task.

## Which variant to ship

Play: `core`. Its Data safety answers are "no data collected, none shared", which is true because the APK cannot
transmit anything. Opt-in distribution (website APK, or a possible separate Play listing with its own policy review):
`relay`. If relay distribution is dropped, delete `src/relay`, `src/testRelay`, the `relay` flavor and the
`relayImplementation` line; nothing in core changes.
