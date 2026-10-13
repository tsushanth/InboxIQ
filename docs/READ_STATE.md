# Read state, unread counts and the sent-MMS duplicate

Scope: how `MessageEntity.isRead` is set on every path, why a full import showed far too many unread
messages, and what the `unread-fix` branch changed. No message text was read or logged while writing this;
all tests use synthetic 555-01xx numbers.

## 1. What the owner measured

After a full import the inbox header said 2425 unread while Android's provider holds about 596 unread
incoming messages (251 SMS with `read = 0`, 345 MMS with `msg_box = 1` and `read = 0`).

## 2. How `isRead` is set, path by path (before this branch)

`isRead` defaults to `true` on the entity; only rows with `isIncoming = true` ever count as unread
(the thread-list query counts `isIncoming = 1 AND isRead = 0`).

| Path | Code | isRead | Note |
|------|------|--------|------|
| SMS_DELIVER (we are default) | `SmsDeliverReceiver` -> `IncomingSmsStore.store` | `false` (provider row written with `read = 0`) | correct |
| Send SMS | `SmsSender` | default `true` | correct |
| Send MMS | `MmsSender` | default `true` | correct |
| SMS history import | `SmsBackfill` | default `true`; provider `read` never read | wrong in the other direction: the 251 unread SMS arrive as read |
| MMS receive / import / sync | `MmsSync` (also used by `SyncMmsWorker`) | `!isIncoming`, so **every incoming MMS is unread** and `read` never read | the main cause of the inflated count |
| Mark thread read (UI open, list action, notification "Mark as read") | `ThreadActions.markRead` -> `markThreadRead`; `NotificationActionReceiver` calls the same | sets 1 for the address, mirrors `read = 1` to provider SMS rows by thread id | MMS rows were not mirrored (they had `threadId = 0`) |
| Mark all read | `ThreadActions.markAllRead` | sets 1 everywhere; mirrors SMS and MMS `read = 0 -> 1` | correct |
| Backup restore | `Backup.readMessage` | the saved flag, default `true`; no provider writes | correct, keeps the user's state |
| Duplicate SMS delivery | `IncomingSmsStore.store` (insert ignored) | existing row set back to unread when it was read | pre-existing; a genuine redelivery of an already read text re-flags it |

### Root causes

1. `MmsSync` set `isRead = !isIncoming` and never looked at the provider's `read` column, so every incoming
   MMS (read or not) was imported as unread. That is the likely source of the 2425 (not measured per row
   on the device: only the counts above were read).
2. `SmsBackfill` never mapped `read` either; it relied on the entity default (`true`), so the 251 genuinely
   unread SMS were lost as read, and the badge disagreed with Android in both directions.
3. Imported MMS rows had `threadId = 0`, so "mark read" could not mirror them to the provider.
4. Sent MMS: `MmsSender` stamped the local row with `currentTimeMillis()`; `content://mms` stores `date` in
   whole seconds and `MmsSync` matched on the exact timestamp. Also `MmsSync` took the FROM header (type 137)
   as the conversation address even for sent MMS, which is this phone / the placeholder
   `insert-address-token`, not the recipient (type 151). Net effect: a sent MMS existed twice, the real row in
   the recipient's conversation and an imported copy in a bogus conversation.

## 3. What changed

* `SmsBackfill`, `MmsSync`: import carries the provider's `read` flag; sent items are read by definition.
  Batch-safe (one transaction per page, as before) and idempotent (inserts still ignore on the unique
  key; existing rows are never altered by the import).
* `MmsSync` stores the provider `thread_id` for new MMS rows and `ProviderSms.markThreadsRead` also updates
  `content://mms` rows of that thread, so mark-read mirrors for MMS too (for rows imported from now on).
* `MmsSender` stamps whole seconds (`sentTimestamp`) and, if two identical sends land in the same second,
  moves the later one to the next free second (`insertAligned`) so the unique index cannot swallow it.
* `MmsSync` matches a sent MMS to a local row by conversation and the provider's whole second, requiring
  the same text or an attachment on both sides (a plain SMS in the same second never matches).
* `MmsSync` files a sent MMS under its first recipient (type 151). When a sent MMS has no recipient row it
  falls back to the FROM header, which is what earlier builds stored. Received MMS are unchanged (FROM).
  Group MMS: the first recipient is used; group threads are not modelled (conversations are by address).
* `ReadReconcile` (one-time) and `SentMmsRepair` (one-time) repair existing installs; see below.
* One query path for unread: header total, tab counts, per-thread dots and bold rows all derive from
  `MessageDao.observeThreadList()` (`unreadCount` = incoming rows with `isRead = 0`), via
  `InboxLogic.totalUnreadMessages` / `unreadCounts`. There is no second counter to drift.
  `ReadStateTest` asserts that after importing a fake provider with N unread rows, the thread-list sum, the
  header total and a direct row count are all exactly N.

## 4. ReadReconcile: the rule

Runs once per install at the end of an import run (flag `readReconcileV` in the `inboxiq_import`
SharedPreferences file; no Room change). For each provider row that is incoming and has `read = 1`, the
matching row of ours (SMS: address + time + body; MMS: address + provider time) is set to read if it is
unread. Nothing else.

Why that is safe, given InboxIQ may be the default app and then the provider is not purely authoritative:

* The provider is trusted in one direction only. `read = 1` there means somebody read the text; `read = 0`
  there never changes our row, and nothing is ever marked unread. A row the user read inside InboxIQ stays
  read, whatever the provider says.
* Texts we received while default and have not read: we write them to the provider with `read = 0`, and
  the provider flips to 1 only when they are read (our mirror, or another app). They stay unread.
* A row whose provider counterpart cannot be found is left exactly as it is.
* Only the `isRead` column of incoming unread rows is written; no inserts or deletes; a failed pass leaves
  the flag unset, and the pass is idempotent so it is simply re-run next time.

Limit: reconciling happens once; later changes made in another app are not pulled in.

## 5. SentMmsRepair: the de-dup rule

Runs once, before the first import page of the upgraded build. Step 1, per provider sent MMS (only those
whose time equals the time of a row of ours):

* If an old-style copy exists (bogus FROM address, the provider's exact time, same text, imported image or
  none) and the recipient's conversation already holds the same message (exact key, or a sent MMS with the
  same text or attachment in the same second), the copy is an exact duplicate: the copy is deleted and the
  row the user had stays, with its own label and state (it only borrows a label when it had none).
* If there is no such row, the copy is re-filed under the recipient in place (same id, label, read state and
  image; only the address changes, after checking the unique key is free).
* A received message with the same key is never merged. A copy whose provider row is gone is left alone.

Step 2 (`dedupe`, DB only): in one conversation, an imported sent MMS (image is a `content://mms/part/` uri)
is removed only when exactly one local twin (own image file) has the same text in the same second. Two local
rows, or two imported rows, are never merged with each other.

Safety argument: every deletion has a surviving row that agrees on conversation, direction, text,
attachment presence and second; everything else is a column update on the same row. Covered by
`ReadStateTest` over a database seeded the way the old build left it. Not verified against a real provider.

## 6. Open points (owner decisions)

* Uncertain real-provider facts: that a sent MMS' FROM is `insert-address-token` or this phone's number, that
  recipients are `type = 151` rows in `content://mms/<id>/addr`, and that the platform's `date` for a sent MMS
  falls in the same second as InboxIQ's stamp. If it is off by a second, the sent MMS can still be imported
  once more (now under the right conversation); a +/-1 s tolerance is a possible follow-up.
* Group MMS and the "sent to me by another app" shapes are not modelled.
* Old MMS rows keep `threadId = 0`, so marking an old MMS read does not mirror to the provider; backfilling
  thread ids would be a deliberate one-time update.
* The "duplicate SMS delivery re-flags a read message unread" behaviour in `IncomingSmsStore` predates this branch
  and is untouched.
