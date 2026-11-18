# Emoji reactions

Long-press a message, pick an emoji, and it appears as a small chip under that message. This page describes
what the first slice of the feature does, the text a reaction travels as, and what it does not do yet.

## What you see

- **Long-press a message.** The menu starts with six quick reactions (thumbs up, heart, laughing, surprised,
  sad, angry) and a **+** button, followed by every action that was already there (Copy text, Share, Forward,
  Details, Retry now, Delete message).
- **+ opens a picker**: a search box, category tabs (Smileys, People and gestures, Hearts and symbols, Animals
  and nature, Food and drink, Activities, Objects and places, and Recent once you have used one) and a grid of
  about 370 common emoji. The list is bundled with the app (`res/values/emoji_catalog.xml`); it needs no
  network and no extra library, and sticks to emoji that every supported Android version can draw.
- **One reaction per message from you.** Choosing another emoji replaces it. Choosing the same emoji again, or
  tapping the chip, removes it. There is no confirmation.
- **A reaction text is not reactable.** A message that is itself a reaction text (see below) has no reaction
  strip and no chip.
- **Screen readers.** Each bubble offers "React with thumbs up", and so on for all six, as accessibility
  actions, plus "Remove reaction" when it has one. The chip reads "You reacted thumbs up to this message".

Reactions are stored on the phone, in InboxIQ's own database (table `reactions`, schema version 16). They are
keyed by the message's address, time, direction and a short digest of its text, not by a row id, so they
survive a restore from a backup file. Picture messages use an empty digest because their text is rewritten once
the pictures arrive. Deleting a message or conversation keeps its reactions until the undo bar closes, then
removes them. The backup file gets an optional `reactions` array (the format version stays 1; older files
restore without it, older builds skip it).

## Sending a reaction as a text

Plain SMS has no reaction object, so a reaction can only travel as an ordinary text that quotes the original.
**Settings > Reactions** chooses what happens:

| Setting | Effect |
|---|---|
| Send as text | The reaction is saved and also goes out as a text |
| Keep private | The reaction is saved on this phone only; the other person never sees it |
| Ask each time | A sheet asks for every reaction |

Until you have answered the first-use sheet the app asks; your answer there becomes the setting. The sheet says:
"Reactions are sent as an ordinary text message that quotes the original. It uses one SMS and cannot be
recalled. People with iPhone see it as text; Google Messages shows a reaction."

A reaction is **always kept private, with no send option offered**, for: group addresses, picture messages
(and ones still waiting for their pictures), short codes, alphanumeric senders, toll-free numbers, email
gateways, messages filed as promo, spam, scam, transaction or one-time code, texts that contain a one-time
code, texts that read like an automatic message (for example "Reply STOP"), messages with no text, and
whenever InboxIQ is not the default SMS app. These rules reuse the smart-reply gate (`ReplyGate`,
`SenderProfile`, `OtpExtractor`), so the two features agree about who is a person.

A text for a reaction goes out after a five second undo window. The text is queued as a stored row first, so if
the app is in the background at the deadline a background worker still sends it, and exactly one of the app's
timer and the worker sends it. Undo (on the bar or on the pending bubble) stops it and puts the previous
reaction back. Once sent it cannot be recalled.

### The text

Curly quotes, emoji and the invisible characters below force 16-bit (UCS-2) encoding, where one SMS segment holds
70 units. Every reaction text is sized so the whole text, invisible characters included, fits one segment. The
quoted original is at most 50 UTF-16 units including a trailing `…` when it was cut (less when a longer verb or
emoji leaves less room), is cut on a character boundary (never inside a surrogate pair, skin tone, ZWJ sequence,
flag or keycap), has newlines and runs of whitespace collapsed to one space, and has any of this format's own
invisible characters removed. A message with no text is described instead of quoted (`Liked a message`).

| Emoji | Add | Remove |
|---|---|---|
| 👍 | `Liked “…”` | `Removed a like from “…”` |
| ❤️ | `Loved “…”` | `Removed a heart from “…”` |
| 😂 | `Laughed at “…”` | `Removed a laugh from “…”` |
| 😮, ‼️ | `Emphasized “…”` | `Removed an exclamation from “…”` |
| 👎 | `Disliked “…”` | `Removed a dislike from “…”` |
| ❓ | `Questioned “…”` | `Removed a question mark from “…”` |
| any other emoji (😢, 😡, 🔥 …) | `U+200B 😢 U+200B to “U+200A …U+200A”` | `Removed U+200C 😢 U+200C from “U+200A …U+200A”` |

The first six are the words an iPhone writes when it reacts to a plain text message. The last row is the form
Google Messages writes when it falls back to SMS (read from its strings; zero-width space around the emoji,
hair space inside the quotes). Apple has no sad or angry reaction, so those use the Google form. 😮 is sent as
"Emphasized" because it is the closest quick emoji to Apple's "!!". The wording is always English, because the
recipient's language is unknown.

A removal text cannot unsend the earlier one; it only tells apps that read it. It is off by default
(**Send a text when I remove a reaction**), is only sent for a reaction that went out as a text, and follows the
same rules and undo window as adding. Its quote is shorter, because the wording is longer.

### Privacy

The text quotes the original message in the clear, to the same person, as a new message. That is why short
codes, one-time codes and automatic senders are excluded and why the quote is capped.

## What is not done

- **Incoming reactions.** Texts like `Loved “…”` from an iPhone or Google Messages still show as ordinary
  messages, and your own outgoing reaction text also appears as a message in the thread. Reading them into
  chips, hiding the raw text, and changing the inbox preview, notification and search is the next slice.
  `ReactionText.parse` already reads the iPhone wording, the Google Messages form and plain `emoji to “…”`
  (and the iOS 18 `Reacted 😂 to “…”`), and is what stops a reaction text from being reacted to.
- **RCS reactions.** Not possible for a third-party default SMS app; Android gives no access to RCS.
- **Group reactions.** Group picture messages are filed under the sender's own conversation, so reactions to
  them are private only.
- Skin-tone variants in the picker, double-tap to react, and showing other people's reactions by name.
- Other languages: the text is English only, and the new screen text is English only until native review.
