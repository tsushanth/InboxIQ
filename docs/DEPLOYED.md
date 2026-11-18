# What is deployed (InboxIQ)

Newest first. Parity rule: whatever runs in production is on `main` (tree compared for the deployed paths). Releases before 0.6.1 are recorded only as git tags `play/<version>-<code>`.

## 2026-11-18 Android 0.6.1 (15), core flavor, on Google Play production at 20%

- Play production track read back after commit: 0.6.1 (15) inProgress at userFraction 0.2, 0.6.0 (14) completed. Not raised above 20%.
- Built from `main` `a5549a0` (merge of #29 on top of #28, #27, #26, #25, #24, #23); tag `play/0.6.1-15`. Flavor: `core` (`:app:bundleCoreRelease`, no INTERNET permission); the `relay` flavor is not shipped to Play. The built tree equals `origin/main` (later commits, if any, touch docs only).
- AAB sha256 `cb5786bbdfa575ed5d282555e868c8d6b4f8b39a56ab96fdec39c62f0d20d564`, signed with the upload key. The on-demand Gemma model pack is a gitignored asset needed in the build worktree (`gemma_model_pack/src/main/assets/*.litertlm`).
- Checks run: `:app:testCoreDebugUnitTest`, `:app:lintCoreDebug`, `:app:bundleCoreRelease` exit 0 (lint and tests on `98135ff`, identical app code; version bump only after). Device check on `98135ff` (Pixel 9 Pro, in-place update over the sideloaded 0.6.0, SMS role untouched, 10 locales, Inbox list and Settings only, no message content opened): PASS, 0 crash/ANR, no raw tokens, Arabic mirrors. Thread screens and the MMS retry UI were not exercised on device.
- Contents: 280 UI strings moved into resources in 27 locales (machine translations needing native review), model names removed from user-visible text, MMS download retry with backoff plus Retry for a missing picture (#25) and storing the downloaded MMS in the app (#27). Changelog: "More of the app is now translated into your language."
- Rollback: halt the rollout in Play Console (Production > Halt rollout), or re-upload 0.6.0 (14) as the current release with the same API pattern.
