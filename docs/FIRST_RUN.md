# First run: from install to "I trust this as my default SMS app"

This describes the flow as it is now and why. InboxIQ only works as the default SMS app, and asking
someone to swap the app that carries their bank codes and their family's texts is the biggest hurdle
the app has. The flow is built around one idea: say what will happen, in the user's terms, before the
system dialog appears, and give them a way back that they can find without us.

## The flow

1. **Intro screen** (`ui/FirstRunScreens.kt`, `OnboardingScreen`). Shown on a fresh install, and again
   from Settings, Privacy ("Read what InboxIQ does and does not access").
   - A made-up inbox, badged "EXAMPLE ONLY: made-up messages, not yours", drawn with the real label chips
     and an inert Copy code chip. It shows the value (sorted tabs, one-tap codes, flagged scams) before
     anything is asked. It is never built from the user's own data.
   - **What changes when you switch.** One default app at a time; InboxIQ replaces the current one
     (named when we can see it); notifications move. Plain statement that InboxIQ is SMS and MMS only:
     RCS chat features in Google Messages (typing indicators, read receipts, Wi-Fi chats) do not work while
     InboxIQ is the default, and chats that live only inside Google Messages may not appear. This is the
     most likely reason for someone to regret the switch, so it is said up front rather than discovered.
   - **What stays.** Existing texts are copied, originals stay in Android's message storage, nothing is
     deleted; texts sent and received while InboxIQ is default are written to Android's message storage
     too (`docs/PROVIDER_WRITES.md`), so the app you go back to has them.
   - **Switching back.** Android Settings, Apps, Default apps, SMS app, pick the old app. "About three
     taps". We do not claim two.
   - **Check the privacy claim yourself** (core build only). The core build has no INTERNET permission, so
     the screen lists ways to check without trusting us: airplane mode, Android's data usage list, the public
     source, and an `adb` command. It also states the one optional exception (the larger sorting model is
     downloaded by Google Play, not by InboxIQ). The relay build has INTERNET and never shows this block
     (`R.bool.privacy_no_internet`, defined per flavor and tested against the real manifest).
   - The system role dialog is launched only from the button at the bottom, under the line "Android will
     show its own confirmation. Nothing changes until you confirm there." If the dialog closes without a
     switch, the screen says nothing changed (it used to say nothing).
   - Layout: the button area is pinned and the content scrolls, so large font sizes cannot push the
     button off screen; content is capped at 560 dp so a tablet does not get 1000 dp text lines.
2. **Android's role dialog.** `DefaultSmsRole.requestIntent`. Before launching, we note which app is the
   current default (`DefaultAppHelper.rememberPrevious`) so Settings can name it later.
3. **Copy of existing texts** (`sms/HistoryImport`, unchanged engine: resumable, newest first, checkpointed
   per page). New: the inbox shows "0 of N" the moment the job starts instead of an empty "No conversations"
   state until the first page lands, and the empty state says sorting follows the copy.
4. **First look card** (`FirstLookCard`). Once the copy has finished and at least one conversation is
   labelled, a one-time card says how many conversations were filed in each group and gives three things to
   try: the Codes tab and Copy code, Junk and how to correct a label, swipe actions. It is shown only to
   installs that became default while this flow existed (`FirstRunPrefs.noteLaunch` decides once, on first
   launch), is dismissed with one tap and never returns. Counts are live, so they grow as sorting finishes.
5. **Notifications-off card** (`NotificationsOffCard`). A default SMS app with notifications off silently
   loses the user's texts. While InboxIQ is default and notifications are off, a card that cannot be
   dismissed offers the permission dialog once (Android 13+) and then opens the app's notification settings,
   because Android ignores repeated requests. Nothing is asked at install or on the intro screen: the
   notification permission is not requested anywhere else in the app. The microphone permission is still
   requested only when the user holds the voice memo button.
6. **Not-default banner** now also has "What changes?", which opens the intro screen.
7. **Settings, Default SMS app** (first section). Current status, the way back, a button that opens
   Android's default apps page (falls back to Settings where a phone lacks it), "Open <previous app>" when
   we know and can see it, and "Make InboxIQ the default" when it is not.

## Decisions and what was left out

- **No early sorting during the copy.** Sorting the newest conversations after the first page would make the
  tabs fill a few seconds sooner, but it classifies on a partial sample, overlaps the model with the heaviest
  memory phase of the copy, and leaves later-copied older messages of an already-sorted address unlabelled
  (labels are applied per address when it is first classified). Not worth the risk without a device pass.
- **No fake data in the user's inbox, ever.** The example lives only on the intro screen and is badged.
- **Localisation.** The app ships English only and most existing UI strings are literals in Kotlin. New
  first-run strings are in `res/values/strings.xml`, so they are ready to translate, but nothing was
  machine-translated. Whoever adds locales needs a native-speaker review of these strings, in particular
  the RCS and switching-back wording, which varies by phone maker. The summary sentence ("2 personal and
  1 code") is built in code and needs the same treatment.
- **Accessibility.** Section titles are headings; the example inbox rows are merged into single TalkBack
  nodes and the badge is a heading read first; all new buttons are at least 48 dp; layouts are tested at
  2x font scale.
- **Tablet.** Capped content width on the intro screen; the inbox's existing two-pane layout is unchanged.

## Funnel counters (debug builds only)

`firstrun/FunnelLog`: for each step (intro shown, role requested, dialog closed without switching, became
default, copy started, copy finished, first-look shown/dismissed, notifications card shown/turned on,
opened Android default apps settings) it keeps a count and first/last time in a private preferences file.
No message text, number or name; no network code (the core build has no INTERNET permission). Recording is
**off in release builds**, so the release app keeps no such log and "no analytics" stays literally true; the
viewer is Settings, Setup log, in debug builds. It exists so whoever runs a debug build for a device check
can see which step a tester stopped at. It cannot tell the owner anything about real users.

## What a device check should confirm before release

Do this on a spare phone, not the owner's phone with Google Messages as default.

1. Fresh install: intro screen shows, example is clearly labelled, buttons visible at the largest font
   size, TalkBack reads the example as one item per row.
2. Tap Choose as default: Android's dialog appears only now; cancel it, confirm the intro says nothing
   changed; try again and accept.
3. After the switch the inbox shows "0 of N" at once (not "No conversations"), then conversations, then the
   first-look card; the label counts match the tabs.
4. On Android 13 or later, check whether the notifications card appears (it appears only if Android did not
   pre-grant the notification permission to the SMS role holder; we could not confirm that from code). Tap
   it; a second denial must lead to the notification settings page; send a test text to the phone and confirm a
   notification with Copy code for a code message.
5. Settings, Default SMS app: names the previous app (needs the `<queries>` entry to work on that OEM), the
   button opens a page where SMS app can be changed, switching back works, and a text sent while InboxIQ was
   default is visible in the other app.
6. Data usage list shows no data for InboxIQ after a session (the intro claims it should; this has not been
   checked on a device).
7. Memory and speed of the copy on a large history are unchanged (the engine was not touched; only a status
   publish was added).
