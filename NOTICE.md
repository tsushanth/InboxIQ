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
| bert-tiny SMS spam classifier (ONNX export of `mrm8488/bert-tiny-finetuned-sms-spam-detection`, itself fine-tuned from the Apache-2.0 BERT-tiny on the UCI SMS Spam Collection, CC BY 4.0) | `app/src/main/assets/spam_classifier_v1_q8.onnx` | The fine-tune's model card states no licence; see "Open points" |
| Fossify `mmslib` (fork of klinker `android-smsmms`) | Gradle dependency | Apache-2.0 |
| LiteRT-LM, AndroidX, Jetpack Compose, Kotlin, kotlinx, Guava, Gson, Okio | Gradle dependencies | Apache-2.0 |
| Instrument Sans, Instrument Serif | `app/src/main/res/font/`, texts in `third_party/fonts/` | SIL Open Font License 1.1 |
| Google Play Asset Delivery, Play In-App Review, Play Services base libraries | Gradle dependencies | Google Play Core SDK terms / Android SDK licence (proprietary, not GPL; see the permission above) |
| Gemma 3 270M | Not in this repository. Built into the Play on-demand asset pack locally (`docs/gemma-model-pack.md`) | Gemma Terms of Use (not GPL; never commit it) |

## Open points

- The spam classifier fine-tune has no stated licence upstream. Its base model and training data are permissively licensed
  (the dataset requires attribution, which this file provides: UCI SMS Spam Collection, CC BY 4.0), but until the
  author confirms a licence, treat the `.onnx` file as "licence unconfirmed" rather than GPL.
- The in-app screens do not yet list these notices; the Apache-2.0 and MIT licences ask for the notice to travel with the
  distributed app. This file covers the source repository only.
