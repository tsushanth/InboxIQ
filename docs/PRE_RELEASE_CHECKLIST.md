# Pre-release device pass

The unit tests simulate Android; they cannot show real receive, MMS, SIM and notification behaviour. Run this on a spare
phone before each release. Never use a personal phone as the test phone's only SMS app without a way to switch back
(step 15 explains it). Every step lists what you do and what you should see. Write PASS or FAIL and a note next to each.

**Setup.** A spare Android phone with a working SIM and a second phone (or a friend) to send you texts and a picture.
Ideal: one phone on Android 14 or newer, one dual-SIM phone with both SIMs active, and one low-memory phone (3 GB RAM or
less, or an Android Go phone). Use a throwaway or lightly used message history. Install the exact build you intend to ship
(the signed core bundle from an internal-testing track, or `./gradlew assembleCoreDebug` for a quick first pass; sideloaded
builds cannot download the on-device model, see step 9). Debug builds have the auto-reply feature on and a "Setup log" in
Settings; release builds follow `-PautoResponder`. If another build of InboxIQ is installed with a different signature, uninstall it first.

## A. Install, switch, import

1. **First launch.** Open InboxIQ. *Expect:* an intro screen with a made-up inbox badged "EXAMPLE ONLY", a "What changes when
   you switch" part that says RCS chat features do not work while InboxIQ is the default, "What stays", "Switching back",
   and (core build) "Check the privacy claim yourself". No permission or role dialog appears yet.
2. **Default SMS switch.** Tap the button at the bottom. Android shows its own dialog. First choose Cancel. *Expect:* InboxIQ
   says nothing changed and you can retry. Tap again and confirm. *Expect:* InboxIQ is now the default (Android Settings,
   Apps, Default apps, SMS app shows InboxIQ); you are not asked for any other permission at this point.
3. **Import progress.** Right after confirming, watch the inbox. *Expect:* "0 of N" with a count that rises within a few
   seconds, never an empty "No conversations" screen while it runs; finishes without a crash; on a phone with thousands of
   texts it completes in about a minute or two. Note the time, the count and whether the phone gets hot or slow. Then
   *expect* a one-time "first look" card saying how many conversations were filed in each group; tabs (All, Personal, Work,
   Promos, Codes, Junk) fill in as sorting finishes.
4. **Notifications card.** If notifications are off for InboxIQ (Android 13 and newer), *expect:* a card you cannot dismiss that
   offers the permission once and then opens the app's notification settings. Turn notifications on. The card disappears.

## B. Receive and send

5. **Incoming SMS.** From the other phone send "hello from test". *Expect:* a heads-up notification within seconds, the text at the
   top of the inbox as unread, opening the thread shows it and clears the notification and the unread count. Then switch
   the default back to the old SMS app (step 15) once and check the text is there too, then switch to InboxIQ again.
6. **Verification code.** Send "Your verification code is 482913. It expires in 10 minutes." *Expect:* labelled as a code
   (Codes tab), a "Copy code" chip in the app, and a notification on the "Verification codes" channel with a "Copy code"
   button. Tap each: *expect* the clipboard holds exactly 482913 (paste it into any text box). A second text with no code must
   not offer a code.
7. **Notification actions.** Send a personal-style text. In the notification use Reply (type "ok", send) and then
   Mark as read on another. *Expect:* the reply is sent and appears in the thread as Sent; the other notification disappears
   and the thread shows as read.
8. **Notification channels.** Android Settings, Apps, InboxIQ, Notifications. *Expect* six channels: Messages, Personal, Work,
   Verification codes, Promos (low importance), Spam and scams (low importance), plus "Message delivery fixes". Turn off
   Promos, send a marketing-style text ("SALE 50% off today, reply STOP to opt out"): *expect* it is filed under Promos with
   no sound.
9. **On-device model download (Play internal-testing install only).** Settings, Sorting, choose "Balanced (Gemma 3 270M ...)".
   *Expect:* "Downloading model, N%" rising, then "Model ready". A sideloaded or debug build cannot fetch the Play asset pack
   and will show a failure or wait: that is expected there, not a bug. Do this on Wi-Fi.
10. **Download while backgrounded.** Start the download, press Home, wait for it to finish, reopen InboxIQ. *Expect:* Settings
    shows "Model ready", no crash, no crash dialog from Android. (This is the test that must pass before removing the
    foreground-service permission, see `docs/FOREGROUND_SERVICE_DECLARATION.md`.)
11. **Sorting with the model.** With the model ready, receive two new texts from a new number. *Expect:* they are filed within a minute and the app stays responsive; switch back to "Default" and confirm that also works.
12. **Outgoing SMS.** Send a short text, then a 200-character text (it goes as parts), then the same short text twice quickly.
    *Expect:* each shows Sending then Sent; the long one arrives as one message on the other phone; both copies of the repeated
    text show in the thread and arrive. In Settings, Sending, set Undo send to 5 seconds, send a text and tap Undo: *expect* it is not sent.
13. **MMS.** From the other phone send a picture; then send a picture from InboxIQ. *Expect:* a notification when the picture
    arrives, the image shows in the thread and in the media view, the sent picture shows Sent and arrives on the other phone.
    Send the other phone a group MMS: *expect* it appears under the sender's one-to-one thread (group chats are not supported;
    this is a documented limit).
14. **Dual-SIM reply** (dual-SIM phone, both SIMs active). Set the phone's default text SIM to SIM 1. Have the other phone text
    the number of SIM 2. Reply in InboxIQ. *Expect:* the other phone sees the reply come from SIM 2's number. Repeat with a
    picture. Then set the default text SIM to SIM 2 and text SIM 1: the reply leaves from SIM 1. A thread that has never
    received a text uses the default SIM.

## C. Safety and settings

15. **Way back.** Settings, Default SMS app: tap the button that opens Android's default apps page and choose the old app.
    *Expect:* messages sent and received while InboxIQ was default are visible in that app; InboxIQ shows its "not default"
    banner and sends nothing. Switch back to InboxIQ and confirm no duplicates appear.
16. **Deleting.** Delete a conversation (swipe or menu) and Undo within the bar: *expect* it returns intact. Delete again and
    let the bar expire: *expect* it is gone from InboxIQ and does not come back after force-stopping and reopening the app.
    The texts remain in Android's own store (the other SMS app still shows them): this is the documented current behaviour.
17. **Backup and restore.** Settings, Backup, Save backup to a file; clear the app's storage (Android Settings, Apps,
    InboxIQ, Storage, Clear storage) or reinstall; become default and finish the import; Restore from file. *Expect:* "Restored N messages (M were already here).", pins, archive, mute and blocked numbers come back; photos are not in the file (the screen says so);
    restored texts are not written to Android's store (the screen says so). Restoring the same file twice adds nothing.
18. **Blocking and spam.** Block a number from its thread, text from it: *expect* no notification and nothing saved.
    Mark a thread as spam: it moves to Junk and its notifications go silent.
19. **Auto-reply** (only if compiled in: Settings has an "Auto-reply" row). It must be off on a fresh install. Turn it on,
    read the confirmation dialog, confirm, and text from a number the phone has messaged before: *expect* one short reply,
    no second reply within the limit, no reply to a number the phone never texted, and "Sent automatically" in the thread.
    Turn it off: no further replies.
20. **Privacy checks.** Airplane mode on: the app still sorts, sends nothing, shows no network errors. On a computer with
    adb: `adb shell dumpsys package com.inboxiq.app | grep -E "permission|INTERNET"` must not list INTERNET for the core
    build, and must list RECEIVE_SMS, SEND_SMS, READ_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH, READ_CONTACTS, POST_NOTIFICATIONS, RECORD_AUDIO and
    the two library permissions FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC (and nothing else sensitive).
21. **Microphone.** Hold the voice-memo button in a thread. *Expect:* the microphone prompt only now, a transcript appears,
    airplane mode does not matter.

## D. Low-memory phone

22. On the low-memory phone repeat steps 1 to 6 and 12. *Expect:* no crash or "InboxIQ isn't responding" during the import,
    the inbox is usable while it runs, scrolling is smooth afterwards, the import finishes (note how long) and a
    force-stop in the middle of the import resumes where it stopped on next launch rather than starting over. Check
    Android's "Battery" or developer "Running services" for InboxIQ: it should hold no persistent process after you leave the app.
23. Receive a text while the app is not running (swipe it from recents): *expect* it still arrives and notifies.
24. Leave the phone idle for an hour with InboxIQ default, then text it: *expect* a normal notification (battery optimisation must not delay it by more than a few seconds).

## Record

Phone model, Android version, RAM, build version and versionCode, import duration and message count, and any FAIL with a
screenshot or `adb logcat -d | grep -i inboxiq` excerpt (it contains no message text).
