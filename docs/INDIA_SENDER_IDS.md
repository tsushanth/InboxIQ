# India sender IDs and the Transactions rules

Everything here is public-format knowledge. No real message, account number or customer data was used or collected.
Files: `app/src/main/resources/classify/india_senders.tsv` (header structure and brand headers) and
`app/src/main/resources/classify/transaction_rules.tsv` (text patterns). Code: `classify/IndiaSenders.kt`.

## What a sender ID looks like

An Indian business SMS shows a named sender, not a number: `XY-ABCDEF`, and since May 2025 `XY-ABCDEF-S`.

* `X` is the originating operator, `Y` the licence service area (circle), `ABCDEF` the registered header: currently six
  alphanumeric characters, up to 11 allowed. TRAI, "Headers assigned by Access Providers to Principal Entities", 16 Jun 2020
  (<https://cms.trai.gov.in/sites/default/files/2024-09/Detail_Header_Prefixes_16062020.pdf>, read 2026-11-09). The page
  lists the operator codes (A Airtel, B BSNL, J Jio, V Vodafone Idea, ...) and circle codes (D Delhi, M Mumbai, X Karnataka, ...);
  both tables are in `india_senders.tsv` and a prefix only parses when both letters are real codes.
* The suffix names the registered message class: `-P` promotional, `-S` service, `-T` transactional, `-G` government.
  TRAI, Telecom Commercial Communications Customer Preference (Second Amendment) Regulations 2025, 12 Feb 2025, item 2 of
  Schedule I, "by suffixing -P, -S, -T and -G for Promotional, Service, Transactional and Government Messages"
  (<https://trai.gov.in/sites/default/files/2025-02/Regulation_12022025.pdf>, read 2026-11-09, text extracted and checked).
  The suffix is added by the operator from the registered template, not by the sender. Press and vendor pages date the
  rollout from about 6 May 2025 (MediaNama, 20 May 2025; Storyboard18, 14 Jun 2025). Messages received before that have no
  suffix, so a missing suffix means nothing.
* Not every sender ID is a header: unregistered senders and some international routes arrive from a random number, and a
  header can be spoofed by a look-alike (one character changed). The app treats the suffix and a known brand as
  supporting evidence only (worth 0.4 to 0.8 of the 1.5 needed). A text with no transaction wording is never a
  transaction because of its sender, and a scam rule always wins.

## How the suffix is used

| suffix | evidence added |
|---|---|
| `-T` | transaction +0.8 |
| `-S`, `-G` | transaction +0.4 |
| `-P` | promo +1.0 (counts as promo text evidence, so a `-P` text with an opt-out line is a Promo) |
| none | nothing |

Operators sometimes put promotional content in a transactional header; that is why the text still decides.

## The brand table (short on purpose)

Only headers with a public source that names them are in the table. Each row of `india_senders.tsv` carries a source key and
the sources have URL, access date and what they support. Two rows state a caveat: the HDFC and SBI pages were read from
search-result excerpts because the bank page redirected or did not open, so confirm them before relying on them.

| header | brand | source |
|---|---|---|
| HDFCBK, HDFCBN | HDFC Bank | the bank's security page (hdfc.bank.in/security), "a header like HDFCBK / HDFCBN" |
| SBIINB, SBIPSG, SBICRD | State Bank of India / SBI Card | SBI YONO phishing-awareness page (sbi.bank.in), "SBIINB, SBIPSG or SBICRD" |
| CBSSBI | State Bank of India | consumer article, 3 Mar 2023 (mlfoundation.in), lists BZ-CBSSBI |
| AXISBK, AXISMR | Axis Bank | Axis fraud-awareness pages; the bank's RBI notice booklet shows AXISMR as promotional (-P) |
| BLUDRT | Blue Dart | Storyboard18, 14 Jun 2025 (example `VM-BLUDRT-S`) |
| ZOMATO | Zomato | same article (`KA-ZOMATO-S`) |
| MEESHO | Meesho | same article (`GJ-MEESHO-P`) |

Looked for and **not** verified, so not in the table: ICICIB, KOTAKB, PAYTM, IRCTC, Amazon, Flipkart, Delhivery, India Post,
Airtel, Jio, and the UPI-app headers. Only a heuristic is kept: a header that ends in `UPI` or `BANK` (and is longer than
that ending) is treated as "payments" or "bank" with 0.3 weight, and the sheet says only that it "ends like a ... header".
The authoritative lookup is the TRAI header portal (smsheader.trai.gov.in), which cannot be queried offline; a maintainer
adds a row after checking a header there or on the brand's own page, and writes the source in the `SRC` lines.

## Text patterns

`transaction_rules.tsv`: tab separated `group, weight, why, regex`; case-insensitive; the `why` is shown to the user, so it
must be a true statement. Weight 1.0 is a broad word (kept as before: Personal), 1.5 and up is specific; a notice needs 1.5.
Covered, in English and Latin-script Hinglish: debited / credited / withdrawn with an amount (`Rs`, `INR`, rupee sign, `$`,
`GBP`, `EUR`), "kat gaye", "debit hua", "jama hue", "aapke khate se"; masked account or card (`A/c XX0042`, `card ending`);
UPI, IMPS, NEFT, RTGS, "UPI Ref No", UTR, VPA (`name@okhdfcbank`); "Avl Bal", low balance; statement, total and minimum
amount due, due date; EMI, auto-debit, mandate, standing order, direct debit; refund, reversal; recharge and plan validity,
FASTag; order shipped / out for delivery / "will be delivered" / AWB / pickup / "your driver is arriving"; "Aapka order
deliver ho gaya", "parcel aaj deliver hoga"; appointment, booking, PNR, coach / berth, "chart prepared", flight and train
updates, boarding pass. Every test sample is synthetic (`app/src/test/resources/classification/transactions*.tsv`).

Known gaps: Hindi and other scripts, regional-language banking texts, messages that carry only a link ("Your update:
bit.ly/..."), and any brand wording not covered above. Those fall back to Personal or Promo, never to a wrong confident label.
