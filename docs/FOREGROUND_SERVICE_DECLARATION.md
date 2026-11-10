# Foreground service declaration: none needed for InboxIQ's own code

As of app 0.5.0 InboxIQ declares no foreground service and requests no foreground-service permission of its own.
The optional "Connect to Claude" relay is in-process and tied to the app being visible: it connects when
InboxIQ comes on screen and disconnects a few seconds after it leaves the screen (switching apps, closing it,
or turning the screen off). There is no service, wake lock, boot start or ongoing notification.

## What the merged manifest still contains (verified from the built debug manifest, 2026-10-13)
| Entry | Added by | Notes |
|---|---|---|
| `FOREGROUND_SERVICE` permission | `com.google.android.play:asset-delivery:2.3.0` (Gemma on-demand pack) | Normal permission, no Play form of its own. |
| `FOREGROUND_SERVICE_DATA_SYNC` permission | `com.google.android.play:asset-delivery:2.3.0` | Needs a Play Console foreground-service declaration if it ships. |
| `ExtractionForegroundService` (`foregroundServiceType="dataSync"`, `enabled=false`) | `com.google.android.play:asset-delivery:2.3.0` | Started only by the Play library while extracting an asset pack, never by InboxIQ code. |
| `SystemForegroundService` (no declared type) | `androidx.work:work-runtime:2.9.1` | Used only if app code calls `setForeground` or expedited work; InboxIQ does neither (grep of app code, 2026-10-13). |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | `androidx.work:work-runtime:2.9.1` | WorkManager internals (reschedules periodic work after reboot). Not a service start. |

The earlier `specialUse` service, `FOREGROUND_SERVICE_SPECIAL_USE` and our own `WAKE_LOCK`/`FOREGROUND_SERVICE` entries were removed.

## Why the library entries are kept, not removed (re-checked 2026-11-09)
Removing `FOREGROUND_SERVICE_DATA_SYNC` and `ExtractionForegroundService` with `tools:node="remove"` looks like the way to avoid
a declaration, but it is not safe without a device test, and the checks below say it is more likely to break something than not:

1. `AssetPackManagerFactory.getInstance()` (called from `GemmaModelStore`: Settings screen and classifier selection) enables both
   `AssetPackExtractionService` and `ExtractionForegroundService` with `PackageManager.setComponentEnabledSetting`
   (library class `assetpacks.s`, via `internal.af.a`, bytecode of asset-delivery 2.3.0). If the component is missing from
   the manifest, that call is expected to throw `IllegalArgumentException`, which would crash the first Gemma-store call.
2. When Play hands the pack over for extraction (`assetpacks.b.d`, action 1), the library binds `ExtractionForegroundService`
   and calls `startForeground` on it. Keeping the service but dropping the permission would make that call throw a
   `SecurityException` on Android 14+.
3. So the service and the permission are kept or removed together, and removal needs the Gemma download test in the
   pre-release checklist. `ForegroundServiceManifestTest` pins the current state (exactly these two library permissions, no
   InboxIQ service with a foreground type, service and permission together, no foreground-service API in app source), so any
   change is deliberate.

Only the optional Gemma on-device tier (a Settings opt-in) touches this path. Nothing in the default install does.

## What the owner must do
Pick one:
1. Declare it (no code change, recommended until a device test says otherwise). In Play Console > App content > Foreground
   service permissions, add `dataSync` and answer: "Used only by the Google Play Asset Delivery library while Play extracts the
   optional on-device model pack the user chose to download in Settings. The app's own code starts no foreground service."
   Demo video: record Settings > on-device model download. The 0.5.0 review passed without this form appearing, so Play may
   never ask; if it does, this is the answer.
2. Remove it, only after a spare-phone test of the Gemma download: add to `AndroidManifest.xml`
   `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" tools:node="remove"/>` and
   `<service android:name="com.google.android.play.core.assetpacks.ExtractionForegroundService" tools:node="remove"/>`, update
   `ForegroundServiceManifestTest`, then run steps 9 to 11 of `docs/PRE_RELEASE_CHECKLIST.md` (open Settings, start the download
   on Wi-Fi, background the app, confirm Settings shows the model as ready and nothing crashes). If the app crashes on
   opening Settings, revert.
No declaration or demo video is needed for the relay.
