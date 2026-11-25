# What is deployed (InboxIQ)

Newest first. Parity rule: whatever runs in production is on `main` (tree compared for the deployed paths). Releases before 0.6.1 are recorded only as git tags `play/<version>-<code>`.

## 2026-11-25 Android 0.6.2 (16), core flavor, on Google Play production at 20%

- Play production track read back after commit: 0.6.2 (16) inProgress at userFraction 0.2, 0.6.1 (15) completed. Beta, alpha and internal moved to 16 completed (they held 15). Uploaded with the Play edit API.
- Built from `main` `58b6140` (#34 on top of #33); tag `play/0.6.2-16`. Flavor `core` (`:app:bundleCoreRelease`), clean detached worktree plus the gitignored `gemma_model_pack/src/main/assets/gemma3-270m-it-q8.litertlm` copied from the main checkout (sha256 `757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63`). The `ratingkit` module is the real one from its local checkout.
- AAB sha256 `88663e1c8bf53a150eff62760f10aff9e1b18e65ff640a5d3b7323c5edfc92ac`, signed with the upload key, manifest 0.6.2 (16), targetSdk 36. No `android.permission.INTERNET` in the merged manifest of either module (base, gemma_model_pack); no permission added compared with the Play-generated APK of 15.
- Release note (en-US): "Typing a number on the New message screen no longer starts a second conversation with someone you already text."
- Contents: #32. Conversations already duplicated are not merged.
- #34: signing passwords fall back to the shared `ANDROID_UPLOAD_*` Gradle properties when `local.properties` has none, so a clean worktree can build.
- Checks: `:app:testCoreDebugUnitTest` 542 tests, 0 failures; `:app:lintCoreDebug` 0 errors; `:app:bundleCoreRelease`.
- NOT verified: any device run of this build (the fix itself was checked on a device on 2026-11-20 with a release build of #32).
- Rollback: halt the rollout; 0.6.1 (15) stays completed.

## 2026-11-19 Android 0.6.1 (15), core flavor, raised to 100% on Google Play production

- Raised from 20% to 100% on the owner's instruction (Play Developer API edit: 0.6.1 (15) set to completed with no userFraction, validated, committed). Play production track read back afterwards: 0.6.1 (15) completed; 0.6.0 (14) is no longer on the track. The release itself is unchanged from the entry below (same AAB, `main` `a5549a0`, tag `play/0.6.1-15`).
- Device check done before the promotion (Pixel 9 Pro, a release APK of the `core` flavor built with `:app:assembleCoreRelease` from a clean worktree at tag `play/0.6.1-15`, signed with the upload key, installed in place over 0.6.0 with data kept; InboxIQ was the default SMS app on a live SIM). Receiving: two picture messages arrived and each was downloaded on the first attempt with outcome SUCCESS, with no parse or persist failure and no retry in the following minutes; both are stored and appear in the conversation. Sending: two picture messages were handed to the platform with no error logged; delivery was confirmed by one of the two recipients on 2026-11-20 (the person at the other end said the picture arrived); the other was not confirmed. 0.6.1 does not log the carrier's result for a sent MMS, so the phone itself cannot show delivery.
- Known: one phone number could appear as two conversations (the same number saved with and without the country code), caused by a number typed on the New message screen not being normalized. Fixed on `main` in #32 (`142ad6af`, 2026-11-20) and checked on the Pixel with a release build; this fix is on `main` but NOT in the 0.6.1 (15) build that is in production, so production still has the bug until the next release. Conversations that are already duplicated are not merged by the fix. Three older pending MMS rows are listed on every download pass but not retried, which is consistent with the 48-hour retry limit; their ages were not checked.
- Not done: a crash or ANR rate review at promotion time (the Play vitals query returned an error).

## 2026-11-18 Android 0.6.1 (15), core flavor, on Google Play production at 20%

- Play production track read back after commit: 0.6.1 (15) inProgress at userFraction 0.2, 0.6.0 (14) completed. Not raised above 20%.
- Built from `main` `a5549a0` (merge of #29 on top of #28, #27, #26, #25, #24, #23); tag `play/0.6.1-15`. Flavor: `core` (`:app:bundleCoreRelease`, no INTERNET permission); the `relay` flavor is not shipped to Play. The built tree equals `origin/main` (later commits, if any, touch docs only).
- AAB sha256 `cb5786bbdfa575ed5d282555e868c8d6b4f8b39a56ab96fdec39c62f0d20d564`, signed with the upload key. The on-demand Gemma model pack is a gitignored asset needed in the build worktree (`gemma_model_pack/src/main/assets/*.litertlm`).
- Checks run: `:app:testCoreDebugUnitTest`, `:app:lintCoreDebug`, `:app:bundleCoreRelease` exit 0 (lint and tests on `98135ff`, identical app code; version bump only after). Device check on `98135ff` (Pixel 9 Pro, in-place update over the sideloaded 0.6.0, SMS role untouched, 10 locales, Inbox list and Settings only, no message content opened): PASS, 0 crash/ANR, no raw tokens, Arabic mirrors. Thread screens and the MMS retry UI were not exercised on device.
- Contents: 280 UI strings moved into resources in 27 locales (machine translations needing native review), model names removed from user-visible text, MMS download retry with backoff plus Retry for a missing picture (#25) and storing the downloaded MMS in the app (#27). Changelog: "More of the app is now translated into your language."
- Rollback: halt the rollout in Play Console (Production > Halt rollout), or re-upload 0.6.0 (14) as the current release with the same API pattern.
