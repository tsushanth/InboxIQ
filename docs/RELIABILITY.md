# Reliability and message safety

What InboxIQ does so that a text is never lost, never shown twice and never sent twice, and what was
tightened in the reliability review. Written for people using and reviewing the app; see the other
documents in this folder for the details of each path.

## How a text is kept safe

| Moment | What happens | Why it is safe |
|---|---|---|
| A text arrives | It is written to Android's shared SMS store first, then to InboxIQ's own database, using the values the store reports back | If the app is stopped between the two steps, the import recovers the text from the shared store |
| The same text is seen twice (arrival, then import) | Both compute the same key (number, time, text) and the database ignores the second | No duplicate rows, tested with randomised orderings |
| You delete a conversation or message | A small record of the deletion is kept | A later import does not bring it back; a newer text from the same person still arrives normally |
| History import | Reads in pages, saves its place after every page, only reads new rows on later runs | A kill, a full disk or a provider error never loses or repeats work |
| You send a text | The outgoing row is stored as "sending" before it is handed to the radio, then updated from the radio's result | A crash cannot leave a sent text unrecorded |
| You schedule a text, or use undo-send | The queued row is claimed and the outgoing message is recorded in one database step | The text is sent at most once, and a crash cannot make it vanish silently |
| A long text is sent in several parts | The result for every part is combined | One failed part keeps the whole text marked as not delivered |
| Auto-reply (optional, off by default) | Only to people you have texted or saved, never to short codes, businesses, groups, code or spam senders; once per person per day; at most 20 a day; never answers an automatic text | Limits are enforced by the app and cannot be raised in settings |
| You stop being the default SMS app | Scheduled sends and picture-message retries stop and are shown as not delivered | The app does not use the SMS permissions when it is not the default app |

## Changes in this review

- Quick-reply service (respond-via-message): sends one text per recipient and can no longer take the app down if a send fails.
- Multi-part texts: a failed part is no longer hidden by a later successful part.
- Receive: a message part the system could not decode is skipped instead of failing the whole delivery.
- Message length: segment counting follows the real 7-bit SMS alphabet (a backtick, curly quote or emoji changes the encoding and the limit); the auto-reply text must be a single 7-bit segment.
- Scheduled and undo-send: claim and record in one step.
- Picture and group messages now post a notification when they arrive, like a text does.
- Picture messages download over the SIM they arrived on (dual-SIM phones).
- Logs no longer contain phone numbers or download links.
- No retry of an old picture message or scheduled text after the default-app role is lost.
- Optional assistant connection (sideload build only): search is literal and matches only the text the assistant is allowed to see; grouped codes such as "123 456" are hidden like other codes.

## Tests

The review added focused tests for each change plus two property-style tests:

- Segment counting is compared with the platform's own counter on seeded random text (emoji, extension characters, accents, empty text), and every message the auto-reply validator accepts is checked to be a single 7-bit segment.
- Duplicate suppression: random mixes of received texts, rows written to the shared store by another app, and imports in random order always end with exactly one row per distinct (number, time, text) and nothing missing.

Run them with `./gradlew :app:testCoreDebugUnitTest :app:testRelayDebugUnitTest`.

## Known limits

- Two identical texts RECEIVED from one number inside the same second are kept as one in InboxIQ (the system store keeps both). The cause is the (number, time, text) key, and the time of a received text has one-second resolution. Fixing it needs a provider row id column and a migration, plus changes to every importer and to backup/restore, so it is left for a change that can be tested on a device. Two identical texts SENT in the same millisecond are now both kept.
- A group picture message is filed under the sender's one-to-one conversation, and replies go to that sender only.
- Deleting in InboxIQ does not delete from Android's shared SMS store, so another SMS app can still show the text.
- Replies go out on the phone's default SMS SIM, not necessarily the SIM the conversation arrived on.
- Real-device behaviour (live receive, MMS on dual-SIM, carrier handling of automatic replies) is covered by the unit tests only where the platform can be simulated; it needs a pass on a spare phone before each release.

## Database downgrade or pre-migration file: set aside, never silently wiped

Room has explicit migrations from schema 7 to the current one. A file Room cannot read (written by a NEWER build, e.g. an
older build sideloaded over a newer one) or one older than the first migration (versions 1 to 6, which used to crash on open)
is rebuilt from scratch so the app still starts. Before that happens `DatabaseGuard` renames it to
`databases/inboxiq.db.v<oldVersion>.<millis>.bak` (with its `-wal` and `-shm`); the two newest are kept.

What a rebuild loses: pins, archive, mute, drafts, scheduled texts, blocked numbers and the corrected labels. Message text is
re-read from the Android SMS provider by the history import. To get the old file back: close the app, rename the `.bak` files
to `inboxiq.db` (and `-wal`/`-shm`) while running a build that is not older than the file. Not covered: a future schema change
still needs its own `Migration`; this is the safety net, not a substitute.
