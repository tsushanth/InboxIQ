# Importing, receiving and sending: how it works, what was wrong, what changed

Scope: how InboxIQ copies the phone's existing SMS/MMS into its own database, how new texts arrive,
and the reliability fixes made on the `import-perf` branch. No real message text was read or logged
while writing this; all tests use synthetic 555-01xx numbers.

## 1. The paths, end to end

### Data model
`messages` (Room, `MessageEntity`) has an auto-increment `id` and **no provider id**. Its natural key
is the unique index `(address, timestamp, body)`; every insert is `OnConflictStrategy.IGNORE`, which is
what makes any re-read of the provider harmless. Addresses are normalised to E.164 on the way in
(`PhoneNumberNormalizer`). Conversations are grouped by `address`, not by the provider's `thread_id`.
See the comments on `MIGRATION_11_12`..`13_14` in `AppDatabase`: earlier builds re-ingested un-normalised
addresses on every launch and split conversations; normalising before insert is what stopped that.

### Import of existing history (before this branch)
* Trigger: `MainActivity.onCreate` and the result callback of InboxIQ's own role request, only.
* `SmsBackfill.run`: one cursor over all of `content://sms`, ordered by date, one `dao.insert` per row
  (each its own Room transaction, each invalidating every observer).
* Then `MmsSync.syncRecent(ctx, 0)` over all of `content://mms`: two extra provider queries per message
  (sender, parts) and a per-row lookup/insert.
* Repeated in full on **every** launch.

### Receiving (unchanged in shape)
* `SmsDeliverReceiver` (`SMS_DELIVER`, only delivered to the default app): normalise, drop if blocked,
  insert into Room, label, notify.
* `MmsReceiver` (`WAP_PUSH_DELIVER`): parse the notification PDU, persist it to `content://mms` with
  `PduPersister`, schedule `MmsDownloadWorker` (3 s) and `SyncMmsWorker` (10 s). `MmsDownloadReceiver`
  re-runs `SyncMmsWorker` when the download finishes. `SyncMmsWorker` -> `MmsSync.syncRecent(last hour)`,
  which also heals a `[MMS message]` placeholder once the image part exists.

### Sending (unchanged)
`SmsSender` and `MmsSender` insert into Room first, then hand off to `SmsManager`/mmslib. Status comes
back through `SmsSentReceiver` / `MmsStatusReceiver`.

### Findings worth the owner's attention (not changed here)
1. **InboxIQ does not write received or sent SMS into the Android provider** (`content://sms`). A default
   SMS app is expected to. Consequence: texts that arrive or are sent while InboxIQ is default exist only
   in InboxIQ's database; if the user later switches to another SMS app, that app will not see them.
2. `MmsSync` takes the address of the `FROM` header (type 137) for every message, including sent MMS,
   where it is the user's own number or a placeholder token, not the recipient. Existing rows were
   created that way, so changing it now would create duplicates; it needs a deliberate migration.
3. `SmsDeliverReceiver` stores `threadId = 0`; the provider `thread_id` is only kept for imported SMS and
   nothing reads it.

## 2. The problems (evidence from the owner's phone: counts only)

| # | Problem | Cause |
|---|---------|-------|
| 1 | ~2 min import, inbox says "No conversations" until done, no progress | one transaction per row; one observer recompute per row; no UI state |
| 2 | Switching default app in Settings and returning to a running app imports nothing | import only ran from `onCreate` and from our own role-request callback |
| 3 | Every launch re-reads all of `content://sms` and `content://mms` | no high-water mark |
| 4 | ~416 MB RSS after import; earlier build OS-killed for low memory | see section 4 |
| 5 | Deleted conversations reappear after restart | re-read of the provider re-inserts rows we deleted; nothing remembers deletions |
| 6 | First text from a new sender notifies on the general channel | notification posted before any model ran |

## 3. What changed

### Import engine (`sms/HistoryImport`, `SmsBackfill`, `MmsSync`, `ImportState`)
* Pages keyed on the provider `_id`: 500 SMS or 100 MMS per page. Each page is read into a small list,
  the cursor is closed, then the rows go into Room in **one transaction** via `MessageDao.insertAll`
  (`IGNORE`, same unique index). MMS parts are read for a whole page with one `mid IN (...)` query, with
  a per-message fallback if the provider rejects it.
* First run per source: snapshot the highest `_id` and row count, then walk **downward** (newest first),
  so the inbox fills with recent conversations from the first page. After every page the resume point is
  saved (SharedPreferences file `inboxiq_import`, `commit()`), so a kill loses at most one page.
* Later runs: only `_id > highWater`, oldest first: one cheap query per source when nothing is new.
* Bounded repair pass: the newest 500 SMS / 100 MMS are re-offered at most every 6 h (also what heals an
  MMS placeholder). The first pass skips it.
* Upgrade path: if our DB already has messages when the state file does not exist yet (every existing
  install), the first pass reconciles quietly (no progress strip) and, being insert-ignore, changes nothing.
* Errors: `SecurityException` -> "Android did not let InboxIQ read your texts" with a retry; null cursor ->
  provider unavailable (retried up to 3 times by WorkManager, then shown); `SQLiteFullException`,
  `SQLiteDiskIOException`, or under 32 MB free -> low-storage message. Nothing logs row contents.
* Progress: `ImportStatusHolder.status` (StateFlow of processed/total) drives `ImportStrip` in the inbox.
* Runs as unique, regular WorkManager work (`HistoryImportWorker`, name `history_import`, KEEP): outlives the
  activity, no foreground service, no expedited quota use. `ClassifyMessagesWorker` is enqueued afterwards
  only if something was inserted (same public enqueue as before).

### Trigger matrix (who starts the import when InboxIQ is, or becomes, the default)

| Route | Starts import? | How |
|-------|----------------|-----|
| Cold start while already default | yes | `MainActivity` ON_START forced check |
| Role granted from our own prompt | yes | result callback forces a check; ON_START/ON_RESUME also fires |
| Changed in Android Settings, user returns to the running app | yes | ON_START forced check |
| Changed while the app is visible (split screen, dialog over it) | yes | 3 s role poll while RESUMED (`RoleWatcher`) |
| Changed while the process is dead or backgrounded | yes (expected) | manifest receiver for `android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED` (the platform sends it to the old and new default app) |
| First SMS or MMS after becoming default | yes | `SmsDeliverReceiver` / `MmsReceiver` call `ImportTrigger.ensure` |
| Already complete | no-op | one `_id > highWater` query per source |

`RoleManager.OnRoleHoldersChangedListener` is **not** usable: its registration methods are `@SystemApi`.
The sampling above replaces it. The broadcast route could not be verified on Android 17 without the device.

### Deleted conversations (decision: tombstones in InboxIQ, never touch the provider)
Why they came back: every launch re-read the entire provider. With a high-water mark that mostly stops
by itself, but a full pass still happens on first run after upgrade, after "clear data", and in the repair
pass, so deletions must be remembered. `ImportTombstones` (SharedPreferences `inboxiq_import_tombstones`)
records, for a deleted conversation, "address + newest timestamp it held": provider rows for that address
at or before that moment are skipped, newer texts import normally. A deleted single message is
remembered by a SHA-256 prefix of address+timestamp+body (no text stored). Undo clears the tombstone.
`ThreadActions.delete/restore/deleteMessage/restoreMessage` maintain them.
**No provider delete was added.** Whether "delete" should also remove the text from Android's own store
(so other apps stop seeing it) is the owner's call; it would have to be an explicit, confirmed action.
Limit: a user who deletes a conversation, then wipes app data, gets it back on the next import (by design:
the provider still has it).

### Notification ordering (`IncomingLabeler`, used by `SmsDeliverReceiver`)
Before the notification is posted: user-corrected thread -> heuristic (`HeuristicRules.match`, as before)
-> cached thread label -> for a **brand-new sender** only, a bounded (1.5 s) call to the model through the
public `ClassifierFactory.create` / `MessageClassifier.classify` interface, DEFAULT tier only (the Gemma
tiers cannot load inside a broadcast). On timeout/failure it falls back to the old behaviour (general
channel, background worker). The seam is `IncomingLabeler.resolve(model = ...)`: the classification agent
can swap or disable the model call there without touching the receiver.

## 4. Memory (item 4): what is the import's share?

Measured: nothing on a device (the shared phone was not used). Reasoning from the code, to be checked on
hardware by someone who owns a test device:
* A `content://sms` cursor streams through `CursorWindow`s (2 MB, shared memory, one live at a time), so
  the old cursor was not itself 400 MB.
* Old import: ~10k individual transactions, each invalidating `observeThreadList()`, whose grouped query
  with a correlated unread-count subquery rebuilt up to 692 `ThreadSummary` objects (each with a body
  string) per emission: heavy allocation churn that pushes the ART heap's high-water mark up; ART rarely
  gives that back, so it stays in RSS afterwards. JVM test with an observer: 1,085 emissions.
* Not the import's: ONNX Runtime + the classifier worker that starts right after the import
  (`ClassifyMessagesWorker`), whisper.cpp native code, Compose. These belong to the other agent / existing
  code. Their share cannot be separated without a heap/`dumpsys meminfo` capture on a device.
* Fix: bounded pages (500/100 rows), cursor closed before the DB write, one transaction per page, cached
  address normalisation. Emissions drop to ~71 for the same data.

## 5. Timings (JVM, Robolectric, native SQLite, synthetic provider; NOT device numbers)
Device times are dominated by provider IPC and are larger; use these as ratios only.
Data: 10,000 SMS + 5,000 MMS, 692 distinct contacts, an `observeThreadList()` collector attached.

| | Before (3bd45fb) | After |
|---|---|---|
| SMS import | 3.4 s | interleaved with MMS |
| MMS import | 2.0 s | interleaved with SMS |
| Total | 5.4 s | 1.0 s (1.0 to 1.4 s over runs) |
| Thread-list recomputes during import | 1,085 | 71 |
| Re-run with nothing new | full re-read (same cost again) | 2 provider queries |
| Peak heap delta during import | not measured | 54 MB, almost all the *fake* provider materialising its result set; not representative |

## 6. Tests
`HistoryImportTest` (18), `ImportPerfTest` (1), `RoleWatcherTest` (5), `IncomingLabelerTest` (6),
`ThreadActionsTombstoneTest` (2), plus the existing `MmsSyncUpsertTest`. `FakeTelephony` is a
MatrixCursor-backed stand-in for the SMS and MMS providers. Covered: 10k SMS + 5k MMS counts, no duplicates
on re-run, incremental import, kill mid-import then resume (newest rows landed first, no re-read of
committed pages), existing rows unchanged, error surfaces, tombstones, role-trigger logic, notification
labelling order.
