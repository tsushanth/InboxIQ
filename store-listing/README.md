# Play listing screenshots (core flavor)

Captured on a Pixel (960x2142) from a debug build seeded with fake data only (names such as Alex Rivera,
numbers 555-01xx, invented senders). Status bar: demo mode, 09:41, full battery and signal. No text is
baked into the images. Regenerate with `DemoSeedActivity` (src/debug, never in release):
`am start -n com.inboxiq.app.debug/com.inboxiq.app.demo.DemoSeedActivity --ez demo_seed true`
(needs a temporary `applicationIdSuffix ".debug"`).

| File | Feature | Suggested caption |
|---|---|---|
| screenshots-new/01-inbox.png | Inbox with the label tray (All, Personal, Work, Promos, Codes, Junk), pin, draft, "Needs a reply" | Your texts, sorted on your phone |
| screenshots-new/02-thread-smart-replies.png | Conversation with quick-reply chips and a photo message | Reply in one tap |
| screenshots-new/03-why-labelled.png | "Why?" explainer for a label, with Change label | See why a text was filed where it was |
| screenshots-new/04-code-copy.png | Verification-code thread with the Copy code chip | Copy one-time codes instantly |
| screenshots-new/05-junk-tab.png | Junk tab: suspected scam and spam, labelled | Scams and spam kept apart |
| screenshots-new/06-link-warning.png | "This link looks unsafe" warning (checked offline) | Warned before you tap a bad link |
| screenshots-new/07-search.png | Search results with highlighted match | Find any message |
| screenshots-new/08-multi-select.png | Multi-select with pin, archive, delete actions | Act on many conversations at once |
| screenshots-new/09-settings-appearance.png | Themes (system, light, dark, true black), wallpaper colours, text size, density | Make it yours |
| screenshots-new/10-settings-privacy.png | Privacy note: no accounts, ads, analytics or internet access; backup stays local | Private by design |
| screenshots-new/11-inbox-dark.png | Dark theme inbox | Dark mode |

Notes for the coordinator:
- 10-settings-privacy.png comes from a debug build, so it shows an "Auto-reply" section that is off in
  release builds. Retake or crop before using it if the listing should not mention auto-reply.
- The label and its "Why?" text are real app UI, but the labels were set directly by the seeder, not
  produced live by the classifier.
- Message times follow the capture time (about 07:00 to 07:50) while the status bar clock says 09:41.
- Not captured: swipe-to-archive with the Undo bar (multi-select shown instead).
