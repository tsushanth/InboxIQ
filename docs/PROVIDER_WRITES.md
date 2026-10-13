# Writing to the Android SMS provider

## Why

A default SMS app is expected to keep Android's shared SMS provider (`content://sms`,
`android.provider.Telephony`) up to date: every received text goes to `Sms.Inbox` when the app
handles `SMS_DELIVER`, every sent text goes to the outbox and then to sent or failed, and MMS lives
in `content://mms`. Before this change InboxIQ only wrote to its own Room database. Anything
received while InboxIQ was the default existed only there, so switching back to another SMS app,
clearing InboxIQ's data or uninstalling it lost those texts (including verification codes), other
apps never saw them, and the history import (which reads the provider) could not recover them.

## Behaviour before this change (as read from the code)

| Path | What it did |
| --- | --- |
| `SmsDeliverReceiver` | Joined the PDU parts, stored `(normalize(originatingAddress), pdu timestamp, body)` in Room with `threadId = 0`. No provider write. |
| `SmsSender` (all sends: UI, notification reply, `HeadlessSmsSendService`, scheduled send, relay `SendDraftWorker`) | Inserted a PENDING row stamped `System.currentTimeMillis()` into Room, then `SmsManager`. No provider write. The address was stored as given (raw for respond-via-message). |
| `SmsSentReceiver` | Updated our `sendStatus` only. |
| `HistoryImport` / `SmsBackfill` | Reads `content://sms` by `_id` and inserts `(normalize(address), date, body)` with `INSERT IGNORE` against the unique index `(address, timestamp, body)`; incoming = `type == INBOX`. Honors tombstones. |
| Mark read | Room only. |
| MMS | `MmsReceiver` persists the WAP-push notification into `content://mms/inbox` via `PduPersister`; `MmsDownloadWorker` asks `SmsManager.downloadMultimediaMessage` to fill that row; `MmsSender` uses the klinker/Fossify `Transaction` with system sending, which persists to the MMS provider; `MmsSync` / `HistoryImport` read it back. So MMS is already provider based (see "MMS" below). |

## Design

Principle: the (address, timestamp, body) that our row is stored under is derived from the
provider row after the insert (read back), using the same normaliser and the same fields
`SmsBackfill` reads. A later import therefore computes an identical key and its `INSERT IGNORE`
does nothing. Existing rows are never altered by the import (it only ever inserts).

### Receive (`IncomingSmsStore`, called from `SmsDeliverReceiver`)

1. Blocked sender: dropped exactly as before (nothing saved anywhere; a provider row would be
   re-imported into our DB, which does not filter blocked numbers for SMS).
2. Insert into `Telephony.Sms.Inbox` with: raw originating address (as the network sent it), joined
   multipart body (one row), `date` = PDU timestamp, `date_sent` = PDU timestamp, `read = 0`,
   `seen = 0`, `service_center`, `sub_id` when the intent carries one. If the PDU timestamp is not
   positive, `now` is used for `date` (and `date_sent` is omitted).
3. Read the row back (`_id`, `thread_id`, `address`, `body`, `date`).
4. Insert our row from the read-back values: `address = normalize(provider address)`,
   `timestamp = provider date`, `body = provider body`, `threadId = provider thread_id`, unread.
5. Provider first, ours second: if the process dies between them, the import recovers the text
   from the provider; the reverse order would leave a text that only we have.
6. If our insert is ignored (the import saw the provider row in the gap and stored it as read, or
   a genuine duplicate) the existing row is reused for labeling and, when we had just created the
   provider row, flipped back to unread.

### Send (`SmsSender`, `OutgoingSmsSync`, `SmsSentReceiver`)

1. The address is normalised before storing (previously only the UI paths did this), so our key
   equals what the import computes from the provider row.
2. Our PENDING row, then the provider outbox row (`type = 4`, same `date` as our timestamp, read), then
   `SmsManager`. The provider row URI travels in the sent `PendingIntent` extras.
3. `SmsSentReceiver`: our status as before, and the provider row moves to sent (`type = 2`) or failed
   (`type = 5`, `error_code = resultCode`). A row someone else deleted is not recreated (the update
   matches nothing).
4. If `SmsManager` throws on hand-off, the row is settled as failed in both stores (no result
   broadcast will ever arrive) and the exception is rethrown as before.
5. Delivery reports are not requested by the app today, so there is no delivered state to mirror.

### Failure handling and retry

* Any exception from the provider (not default -> `SecurityException`, storage full, provider down)
  is caught. The message is still stored in our DB and its `messages.id` goes into
  `ProviderWriteQueue` (SharedPreferences `inboxiq_provider_writes`, ids and attempt counts only,
  max 500 entries, an entry is dropped after 6 failed attempts, the message itself always stays).
* `ProviderWriteScheduler` enqueues unique work `provider_write_retry` (exponential backoff 30 s);
  `InboxIqApp.onCreate` re-enqueues when the queue is non-empty so nothing is forgotten after a reboot.
* `ProviderWriteRetry` is idempotent: for each id it rebuilds the write from our row, first looks
  for an existing provider row with the same `date` and body and the same normalised address, and
  only inserts when there is none (an existing outbox row is moved to its final type instead). It
  does nothing, and burns no attempts, while InboxIQ is not the default app.
* A send that loses the default role mid-flight is not queued: the platform records sends from
  non-default apps itself and queueing would later create a second row.
* The receiver catches everything (it never crashes). Provider work is two quick binder calls; the
  only time-budgeted piece, the model call in `IncomingLabeler`, is unchanged.

### Mark read

`ThreadActions.markRead(address)` / `markAllRead()` (in-app and notification "Mark as read" / reply)
update the provider with `read = 1, seen = 1` for the provider thread ids of that conversation's unread
incoming rows (`markAllRead`: all unread SMS and MMS). Best effort and silent: a provider failure
never blocks the in-app action. Limit: rows whose `threadId` is 0 (imported MMS, rows whose provider
write failed) are not mirrored at thread level.

### Deletes

Unchanged: tombstones in our DB and no provider deletes. This change adds no provider delete (the
test provider throws if one is attempted).

### Repair of rows received by older builds: NOT built

There is no reliable marker that separates "received via SMS_DELIVER / sent by us before this change"
from "imported from the provider": old receive rows have `threadId = 0` but so do imported MMS
rows, and the schema has no origin column. Guessing would risk writing imported MMS text into the
SMS provider as fake SMS. The queue added here is a reliable marker going forward (only the receive
and send paths add to it). Recovering the older rows would need an owner decision: a Room column with
a migration (set only for new rows, so it would not help old ones either), or a one-off manual
"export unsynced" for rows with `threadId = 0` and no MMS image after the owner reviews them.

### MMS

Received MMS: the notification indication is written to `content://mms/inbox` by us
(`PduPersister`), and the content is downloaded into that row by the platform. Sent MMS: persisted by the
library's system-sending path and moved to sent by the platform/`MmsStatusReceiver` result handling.
This was not changed; it could not be verified without a device. Finding: our local row for a sent
MMS is stamped in milliseconds while the provider row's `date` is whole seconds, so `MmsSync` /
`HistoryImport` may import the same sent MMS a second time (the placeholder-heal matches on exact
address and timestamp). Not fixed here (not clearly testable without the library's persist timing).

## Tests

`ProviderWritesTest` (Robolectric + the in-memory provider in `FakeTelephony`): provider row fields,
no duplicate after a full import and after repeated imports, key equality against a second DB filled
only by the import (synthetic normaliser and the real `PhoneNumberNormalizer`), real multipart PDUs
joined into one row, invalid PDU timestamp, import-wins race, blocked sender, failure -> queue +
retry scheduled, not-default, bounded retry, retry idempotency, deleted rows not resurrected, work
uniqueness, send outbox -> sent / failed, outbox failure recovery, role lost mid-send, `SmsSender`
address normalisation and hand-off failure, read mirroring.
