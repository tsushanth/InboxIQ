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

## What the owner must do
The library-injected `FOREGROUND_SERVICE_DATA_SYNC` is not caused by the relay and was left in place, because
removing it could break the Gemma on-demand model download and that was not testable without a device. Choose one:
1. In Play Console > App content > Foreground service permissions, declare `dataSync` as "Play asset pack
   extraction, system-managed, not started by app code" (the 0.4.x history shows Play flagged this permission once).
2. Or add `tools:node="remove"` for `FOREGROUND_SERVICE_DATA_SYNC` and `ExtractionForegroundService`, then test the
   Gemma download on a device before shipping. Only do this after that test.
No declaration or demo video is needed for the relay.
