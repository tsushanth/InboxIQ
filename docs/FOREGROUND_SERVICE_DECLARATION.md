# Foreground service declaration (draft, nothing submitted)

Service: `com.inboxiq.app.mcp.RelayForegroundService`
Type: `specialUse` (permission `FOREGROUND_SERVICE_SPECIAL_USE`); subtype string in the manifest property.

Why not dataSync: on Android 15+ (target 35+) dataSync is capped at roughly 6 hours per 24h and the OS stops/crashes the app at the cap. Why not a boot start: forbidden/blocked for this type; the service is never started in the background.

## Console answers
- What does the service do: holds one outbound WebSocket to the relay (mcp.kreativekoala.llc) while the user has enabled the optional "Connect to Claude" feature, so that the user's own Claude session can read messages and create drafts.
- User-initiated and visible: yes. Started only from the Settings switch after the consent dialog; persistent notification "InboxIQ is sharing messages with Claude" with a Disconnect action; stops on Disconnect/switch off.
- Why it cannot run another way: a live inbound connection from Claude needs a persistent socket; WorkManager/JobScheduler cannot receive pushed requests. (FCM would be the alternative; not implemented.)
- Impact if deferred/stopped: the connection drops; the user reconnects by reopening the app. No SMS functionality depends on it.
- Video: record (1) Settings > Connect to Claude > consent dialog > enable, (2) the notification with Disconnect, (3) Claude issuing a read, (4) Disconnect. Use a test phone with test messages only.
- Honest risk: Play may reject specialUse as "not special". Fallback options: remove the relay, or replace the persistent socket with FCM-triggered short work (needs backend work and a Firebase dependency).
