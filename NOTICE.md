# Notice

InboxIQ is free software, licensed under the GNU General Public License, version 3 or (at your option) any later
version (`GPL-3.0-or-later`). The full text is in `LICENSE`.

Copyright (C) 2026 Kreative Koala Solutions LLC

## Additional permission under GPLv3 section 7

The copyright holder gives permission to link or combine InboxIQ with the Google Play libraries it uses
(`com.google.android.play:*` and `com.google.android.gms:*`, distributed under the Google Play Core SDK and Android SDK
terms) and to convey the result, even though those libraries are not licensed under the GPL. This permission covers only
those Google Play libraries. If you modify InboxIQ you may remove this paragraph from your version.

## Third-party components

These are separate works under their own licences; they are not relicensed by the above.

| Component | Where | Licence |
|---|---|---|
| whisper.cpp / ggml | `app/src/main/cpp/whisper.cpp` (MIT text in that folder) | MIT |
| Whisper `tiny.en` model weights (OpenAI) | `app/src/main/assets/models/ggml-tiny.en.bin` | MIT |
| ONNX Runtime Mobile | Gradle dependency | MIT |
| bert-tiny SMS spam classifier: ONNX export (embeddings stored as int8) of `mrm8488/bert-tiny-finetuned-sms-spam-detection`; the architecture matches Google's Apache-2.0 BERT-Tiny, trained on the UCI SMS Spam Collection (CC BY 4.0). Vocabulary: standard uncased BERT word pieces (Apache-2.0) | `app/src/main/assets/spam_classifier_v1_q8.onnx`, `spam_classifier_vocab.txt` | **Licence unconfirmed.** The fine-tune's model page states no licence and does not name its base model; see "Open points" and `docs/SPAM_MODEL_LICENCE.md` |
| Fossify `mmslib` (fork of klinker `android-smsmms`) | Gradle dependency | Apache-2.0 (stated in its README; the fork has no separate licence file) |
| LiteRT-LM, AndroidX (Room, WorkManager, Compose, Navigation, Lifecycle), Kotlin, kotlinx, Guava, Gson, Okio, OkHttp (connector build only) | Gradle dependencies | Apache-2.0. LiteRT-LM and ONNX Runtime also bundle native code under their own notices (LiteRT-LM's `THIRD_PARTY_NOTICE.txt` lists Apache-2.0 and BSD-style licences; no copyleft licence was found in it) |
| Instrument Sans, Instrument Serif | `app/src/main/res/font/`, texts in `third_party/fonts/` | SIL Open Font License 1.1 |
| Google Play Asset Delivery; and, in the Play build, the Play In-App Review and Play Services base libraries pulled in by RatingKit | Gradle dependencies | Google Play Core SDK terms / Android SDK licence (proprietary, not GPL; see the permission above) |
| RatingKit (in-app review prompts) | Separate module by the same copyright holder, not in this repository. A plain clone builds with a no-op stand-in (`ratingkit-stub`); the Play build links the real module | Proprietary, not distributed with this source. Builds made from this repository alone do not contain it |
| Gemma 3 270M | Not in this repository. Built into the Play on-demand asset pack locally (`docs/gemma-model-pack.md`) | Gemma Terms of Use (not GPL; never commit it) |

## Open points

- **Spam classifier licence unconfirmed.** The model page of the fine-tune has no licence field, no licence text and no
  `LICENSE` file, and it does not name the model it started from. Its architecture is Google's Apache-2.0 BERT-Tiny and the
  author's other BERT-Tiny cards say they start from it, but that is an inference. The training data (UCI SMS Spam
  Collection) is CC BY 4.0; attribution: Almeida, Hidalgo and Yamakami, "Contributions to the Study of SMS Spam Filtering: New Collection and Results",
  DocEng 2011, via the UCI Machine Learning Repository. Until the author confirms a licence or the model is replaced, treat
  the `.onnx` file as "licence unconfirmed", not as GPL and not as free to reuse. Options and the recommendation (keep with
  this notice, retrain on permissive data, optionally ask the author) are in `docs/SPAM_MODEL_LICENCE.md`.
- The in-app screens do not yet list these notices; the Apache-2.0 and MIT licences ask for the notice to travel with the
  distributed app. This file covers the source repository only.
- The Play build links RatingKit, whose source is not in this repository (see the table). The copyright holder is the same,
  so this is a statement of fact rather than a conflict, but anyone rebuilding the Play binary from this source gets the
  stand-in instead.
