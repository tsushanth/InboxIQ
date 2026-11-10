# Low-RAM check and plan

Status: JVM-side measurement done, **nothing here was measured on a phone**. The earlier on-device figure was about 373 MB
RSS after importing 9.7k messages on a Pixel 9 Pro (a 12 GB phone); no low-RAM phone has been tested. This note says what the
JVM can and cannot tell us, what changed, and the exact device test that is still needed.

## What was measured (synthetic, desktop JVM, Robolectric, in-memory Room)

`LowRamMeasureTest` builds 51,903 synthetic messages in 3,500 conversations: 12 automated senders with 2,000 messages each
(bank-style alerts, codes, offers) and 3,488 short conversations (people and shops). All text is made up. Numbers vary a
little run to run (ranges shown); only ratios and "does it stay flat" mean anything. The ONNX runtime has no native library
under Robolectric, so the spam model is not loaded and these runs are rules only.

| path | what it does | result |
|---|---|---|
| Inbox list (`observeThreadList`) | one row per conversation with its whole newest message, rebuilt on every `messages` write | 3,500 rows, first emission 32 to 69 ms, about 0.8 to 0.9 MB held (about 260 bytes per row) |
| Open the biggest conversation (`observeThread`) | every message of the thread in one list | 2,000 messages, 7 to 11 ms, 0.5 to 0.7 MB held |
| First-install classification pass | 50 conversations per page, one transaction per page, up to 5 recent texts each | 5.9 to 6.5 s; heap grew 117 to 176 MB at peak **on a 2 GB JVM heap, which is uncollected garbage**: 30.8 KB allocated per conversation times 3,500 is about 108 MB; live data stays about one page (about 1.5 MB) |
| One-time Transactions re-sort | the same pass over every conversation that is not hand-filed | 2.7 to 3.0 s, same garbage profile |
| Backup export | streams 500 messages at a time | 12.6 MB of output, heap growth about 25 MB and flat regardless of size |
| Rules benchmark (10,000 threads, `ClassificationBenchmarkTest`) | classification only | 383 microseconds and 30.8 KB per thread; before this change 293 microseconds and 24.5 KB. The Transactions rule set is about 30 percent more CPU and allocation per thread (known contacts skip it) |

Sizes that do not need a run: spam model asset 5.99 MB (int8) loaded only when no rule settles a message (51 percent of
the benchmark mix); the session was measured earlier at about +25 MB resident on a desktop, released at the end of a pass or on
a low-memory callback. Whisper: 77.7 MB asset, copied to app storage on first voice use (so about 155 MB of storage in total)
and loaded into memory only when a voice memo is transcribed; the native library itself is mapped as soon as a conversation
screen opens (`VoiceTranscriber.getInstance` runs in `remember`).

## What this says about the 373 MB

None of the paths above that scale with message count holds more than a few MB of live Java data at 50k messages. So the
hundreds of megabytes are **not** coming from the import, the inbox list or the classifier's live data, as far as a JVM can
tell. The likely owners are things a JVM run cannot see: ART heap growth and GC policy on the phone, Compose and
image caches, SQLite cursor windows and page cache (each cursor window is up to 2 MB), the native libraries, and the
unit-of-memory accounting of RSS itself (shared file-backed pages count). Treating this as "fixed" or "not a problem" would be a
guess; the device test below is how to find out.

## Code changes made for memory

None that change live memory. One that reduces work: a known contact (three or more real replies) never runs the transaction
rule set, which is the largest rule set. The Transactions change itself adds about 30 percent CPU and allocation per
classified thread, which is the price of the label.

## Hotspots that scale with message count (not changed here, ranked)

1. **Opening a long conversation loads all of it** (`MessageDao.observeThread`, `messagesForThread` for delete-undo). A
   bank sender with 20,000 alerts becomes a 20,000-entity list on every table write while the screen is open (about 350 bytes
   per message at the measured size, about 7 MB, plus Compose item state). Recommendation: load the newest 200 and page
   upward (`LIMIT`/`OFFSET` by timestamp or Paging 3), keep search jumping to a message by loading around its id.
   This is a UI behaviour change (scroll position, "jump to message"), so it was not done in this PR.
2. **The list query recomputes on every write.** `observeThreadList` is re-run for every `messages` write (label updates,
   read marks, each import page). It groups by address with a per-row unread subquery. Recommendation: an index on
   `messages(address, timestamp)` (from reading the schema, not measured: the existing unique index also carries `body`, so the
   group-by walks an index as big as the table) and a denormalised unread counter or a debounce on the flow. Needs a Room migration, so it was left out.
3. **Allocation per classified thread** is 30 KB (regex matcher objects over up to 5 recent texts). A cheap pre-filter
   (skip the transaction rules unless the text has a digit or one of a few trigger words) would remove most of it for
   chat; low risk, worth doing after the label has real-world feedback.
4. **Model load per incoming text from a new sender** (`IncomingLabeler.defaultModel`) reads 6 MB and creates a session each
   time, up to 1.5 s, then closes. Transient, but it is a spike inside a broadcast on a low-RAM phone; keep one lazy session
   for the receiver's lifetime or skip the model on `ActivityManager.isLowRamDevice()`.
5. The model asset is read fully into a byte array (6 MB heap) before the session copies it; creating the session from a file
   path would avoid the second copy.

## The on-device test the device agent should run (no code needed)

Use a low-RAM profile if one exists (Android Go or a 2 to 3 GB phone; or an emulator with 2 GB RAM and `hw.ramSize=2048`).
1. `adb shell dumpsys meminfo com.inboxiq.app` (note "TOTAL PSS", "Java Heap", "Native Heap", "Graphics") at: cold start with an
   empty database; after the history import of the largest mailbox available; after opening the inbox; after opening the
   longest conversation; after the classification pass (watch `adb logcat -s ClassifyMessagesWorker`); after a
   voice-memo transcription.
2. `adb shell am send-trim-memory com.inboxiq.app RUNNING_CRITICAL` mid-pass: the worker must keep running and the spam model must
   unload (PSS drops by about the model size).
3. `adb shell dumpsys activity processes | grep -i inboxiq` and `adb logcat | grep -i "lowmemorykiller\|am_kill"`: no kill during import.
4. Record the time of the import and of the first sort; note ANRs and janky frames (`adb shell dumpsys gfxinfo com.inboxiq.app`).
5. Confirm the Transactions resources load on the device: install the build, deliver (or import) a synthetic text such as
   `Rs 640.00 debited from A/c XX0042. UPI Ref No 600000000001. Avl Bal Rs 9,100.00.` from an alphanumeric sender such as
   `VM-TESTBK-T`, and check it lands in the Transactions tab with "Why?" naming the rule. (The rules and the India header table
   are Java resources read through the class loader; that works under the JVM test and the APK contains both files, but it has
   not been run on a device.)
Pass line to agree before testing: no lowmemorykiller kill during a 10k-message import, and PSS after the import below the
number measured on the 12 GB phone.
