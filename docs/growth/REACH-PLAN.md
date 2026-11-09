# Reach plan: no-spend experiments, measurement, and review points

Written 2026-11-09. Companion to `STRATEGY.md` (who and why) and `PLAY-LISTING-DRAFT.md` (the text and art).
No paid promotion appears anywhere in this plan. Install numbers are my guesses with ranges, not forecasts; the
only hard baseline is 6 device installs in the app's whole life (Aug 2 and Sep 4), with 1 active device.

## 0. Ground rules

- Every community post is written as a person showing a project: says it is the developer's, says what it does and
  what it does not (no RCS, no group picture messages), is positive about the apps people already use, and offers no
  incentive for installs or reviews.
- Read the sidebar rules of each community on the day of posting. Rules change. If a rule is unclear, ask the mods first.
- One post per community, no cross-posting the same text on the same day, no replies from several accounts.
- Gate: experiments marked **[India gate]** wait until the product has a Transactions label and India sender patterns
  (`STRATEGY.md` section 3). Everything else can start as soon as the new listing is live.
- Tag each link so Play can attribute it: `https://play.google.com/store/apps/details?id=com.inboxiq.app&referrer=utm_source%3D<channel>%26utm_medium%3D<type>%26utm_campaign%3Dsms-organizer`.
  Not verified: that Play Console shows the UTM breakdown at this tiny volume. Check once after the first tagged install.

## 1. What we can measure (and what we cannot)

Core has no INTERNET permission, so there is no in-app telemetry, by design. That is a selling point and it limits us.

| Signal | Where | Notes |
|---|---|---|
| Daily device installs, uninstalls, current device installs, by country | Play Console install statistics (the same numbers pulled on 2026-11-09) | Primary measure |
| Store listing visitors, installers, conversion, by country and traffic source | Play Console store listing acquisition (CSV export `store_performance`) | I did not verify export access |
| UTM source breakdown | Play Console acquisition, from the `referrer` parameter | Check availability at low volume |
| Rating and written reviews | Play Console, and the in-app review prompt (asks after the second sent reply, 2+ days after first launch) | Reviews are the only sentiment signal |
| Crashes and ANRs | Play vitals (earlier checks: no data, too few users) | Becomes useful above roughly 50 users |
| Is it the default SMS app? | **Not measurable** | Proxy: devices that keep the app installed past day 14. An app that is not the default does almost nothing, so retention is a reasonable but imperfect proxy |
| GitHub traffic | Repo Insights (views, clones, referrers) | Free, gives referrer counts for guide and Hacker News posts |

Optional, only if the owner wants it: a voluntary "Tell us how it went" mail-to link in Settings (opens the user's own
email app, sends nothing by itself). It keeps the no-INTERNET promise and gives the only direct answer to "did you
make it your default and keep it?".

## 2. Ranked experiments

Ranked by expected installs per hour, adjusted for risk. Hours are total owner or session hours. Install ranges are
for 60 days after the action and are guesses.

### E1. Ship the new listing and 8 screenshots (6 h), start immediately

- What: paste the title, short and full description from `PLAY-LISTING-DRAFT.md`; replace the 3 old screenshots;
  fix the screenshot ratio; one contact email that is monitored.
- Expected: +3 to +15 installs from Play search and browse over 60 days (guess; baseline is about 2 a month, and
  the page converts more of what it does get). Needs organic traffic; the first search impressions are the unknown.
- Measure: store listing acquisition, weekly: visitors, conversion, installs by country.
- Kill: if after 30 days there are under 100 listing visitors, search is not delivering, move effort to E2 and E3
  (which create the traffic). Revisit the listing only if visitors are above 100 and conversion is under 10%.
- Constraint: listing edits are not a release; the owner publishes them in Console.

### E2. "Moving on from your SMS organizer" guide plus an AlternativeTo listing (12 h)

- What: one plain, kind guide (about 900 words) hosted where the owner already publishes: "Your texts are stored by
  Android, not by the app: how to try another SMS app and switch back", comparing Google Messages, Fossify
  Messages, Textra, Pulse and InboxIQ honestly (what each is good at, and where InboxIQ is weaker). Target
  searches: "SMS Organizer alternative", "SMS app that sorts OTP and promotions", "Samsung Messages replacement".
  Then suggest InboxIQ on AlternativeTo (the FAQ says anyone can "Suggest new application", the app is checked and
  approved, and a licence field is requested) and add it as an alternative on the SMS Organizer and Google Messages pages.
- Evidence of demand: the SMS Organizer shutdown notice (2026-05-19) and threads asking for replacements
  (r/androidapps 2026-05-28, 2026-01-05, 2026-01-03; r/IndiaTech 2026-05-19). Samsung Messages ended July 2026.
- Expected: +10 to +80 installs over 60 days if the guide ranks (many results for those queries are thin
  aggregator pages, so ranking is plausible). Slow to start: 2 to 6 weeks to index.
- Measure: UTM source `guide` and `alternativeto`; GitHub referrers; search impressions if the host reports them.
- Kill: under 5 attributed installs and no ranking in the first 60 days, stop adding guides; keep the two pages up.
- Risk: the guide must not claim equivalence with organizer-style reminders (bills, travel); it does not exist here.
- Licence note: AlternativeTo asks for a licence. Without a LICENSE file the honest field value is "unspecified";
  do not choose "open source" until a licence is added (an owner decision).

### E3. A `[Promo]` post on r/droidappshowcase (2 h)

- Why this venue: r/androidapps announced on 2026-05-27 (read via mirror sites, not the official page) that all
  self-promotion must go to the sister sub r/droidappshowcase, with zero tolerance in r/androidapps. The showcase
  sub's own feed (read 2026-11-09) is full of developer posts titled `[Name] [Promo]`. I did not read its sidebar
  rules; read them before posting.
- Post: what it is, the one job, no INTERNET permission, what it does not do, link, "feedback welcome". No codes,
  no giveaways (the app is free).
- Expected: +5 to +40 installs. Most of the showcase posts are other developers; treat the audience as testers
  more than users.
- Measure: UTM `droidappshowcase`; comments are the real yield (bug reports on real devices are valuable).
- Kill: if under 5 installs and no useful feedback, do not repost; one post only.

### E4. Open the repo to visitors (6 h)

- What: a product-facing `README` top section (screenshot, Play link, "why no internet permission", how switching
  back works), repo topics (`android`, `sms`, `privacy`, `jetpack-compose`), and a repo that builds from a clean
  clone (the `ratingkit` module path in `settings.gradle.kts` points outside the repo; the Gemma asset is
  git-ignored). Decide on a licence.
- Expected: +3 to +20 installs directly, but it multiplies the trust of every other channel (E2, E3, E5).
- Measure: GitHub Insights referrers and stars; UTM `github`.
- Kill: none; this is hygiene. If the licence decision is "no", remove "open source" wording everywhere.

### E5. Show HN (4 h)

- Rules read (news.ycombinator.com/showhn.html via search summary): it must be something people can try, made by
  the poster who stays to discuss it, no landing pages. A Play-only Android app is tryable by Android users only, so
  say so in the first comment.
- Title shape: "Show HN: An SMS app that sorts codes and promos on-device and has no INTERNET permission".
- Expected: most posts get no traction. Guess: 85% chance of under 10 installs, 15% chance of 50 to 250.
  Expected value about +15 installs.
- Measure: UTM `hn`; GitHub referrers.
- Kill: one attempt only, and only after E4 (a repo that builds and a licence make the thread much kinder).

### E6. r/IndiaTech, r/developersIndia and similar regional communities **[India gate]** (3 h)

- Rules: I found no explicit self-promotion rule on r/IndiaTech's wiki page (searched, not read directly), so ask
  the mods by modmail before posting. r/developersIndia restricts self-promotion; a showcase flair exists and
  open-source projects with a GitHub link are the kind of post that is allowed (secondary source). r/India and
  r/indiandevs require contribution ratios. Do not post anywhere before reading the current rules.
- Why gated: this audience wants bank, UPI and bill alerts separated, which today are filed as Personal.
- Expected after the gate: +20 to +120 installs. Before the gate: do not run it; a poor first impression in the
  one community most likely to adopt it is hard to undo.
- Kill: if the first thread's feedback is "labels are wrong for my bank", pause, fix the patterns, do not repost.

### E7. Short outreach to a few Android bloggers and channel owners (6 h)

- What: 8 to 10 individually written emails to people who have written about SMS Organizer, Fossify or Google
  Messages alternatives. One paragraph, one screenshot, one link, no asks beyond "if you ever do a roundup".
- Expected: 0 to 100 installs; most outcomes are zero. Guess: 10% chance that one covers it.
- Measure: UTM `press`.
- Kill: no replies after 3 weeks, stop. Do not follow up more than once.

### E8. Regional listings and South-East Asia

- English store listings for en-IN (custom store listing) and en-SG, en-PH, en-ZA can be added free after the gate.
- I did not research South-East Asian SMS usage, sender formats or competing apps. Do not target a country
  until a local sender-ID pattern set and an English-quality check exist for it. This is a research task (8 h),
  not an install experiment.

## 3. Sequence

| Week | Do | Hours |
|---|---|---|
| 0 | E1 listing live; E4 README and licence decision | 12 |
| 1 | E2 guide, AlternativeTo; E3 showcase post | 14 |
| 2 | E5 Show HN (if E4 done) | 4 |
| 3 to 4 | India gate work (product); E7 outreach | 6 + product |
| 5 to 8 | E6 regional posts after the gate; one improvement per week from user feedback | 3 + |

Total owner/session time through week 4: about 36 hours. If all expected values land, the 60-day total is roughly
40 to 250 installs. The wide range is the honest answer: one lucky mention changes everything, and nothing here is
guaranteed.

## 4. Review points: what the numbers mean

Counted from the day E1 and E2 are both live. "Installs" means new device installs from Play statistics; "current"
means current device installs.

**Day 30**

| Result | Meaning | Action |
|---|---|---|
| 75 or more new installs, and 25 or more current devices that are 14+ days old | Segment and listing resonate | Keep investing: run E5, E6 (if gated work is done), start the Transactions label |
| 25 to 74 installs | Some pull | Keep the best two channels by UTM, drop the rest, fix the funnel step with the biggest drop (listing visitors to installs, installs to day-14 retention) |
| Under 25 installs with E1, E2 and E3 all done | The wedge is not reaching people at this volume | No new channels. Hold features, fix bugs only |

**Day 60**

| Result | Meaning | Action |
|---|---|---|
| 250 or more cumulative installs, 100 or more current, rating 4.0+ with at least 5 reviews | Real product-market signal | Continue; plan the reminders and bill cards work; consider a second market |
| 100 to 249 cumulative, retention around 30% at day 14 | Interest, weak habit | Invest only in the thing users say they miss (read reviews and mail); one more quarter at low effort |
| Under 100 cumulative, or under 20% of installs still present at day 14 | The default-SMS barrier is stopping adoption, or the product is not worth the switch | Move to maintenance only (security and OS compatibility fixes) and stop acquisition work |

Two checks override the table: a rating under 3.5 with 5 or more reviews means fix before any promotion; and any
report that texts were lost or not delivered is a stop-everything bug, whatever the install numbers say.
