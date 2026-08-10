# Gemma 3 270M model pack — setup

The MID classifier tier (`GemmaClassifier`) needs `gemma3-270m-it-q8.litertlm`
(~290MB) present at `gemma_model_pack/src/main/assets/` before building.
It's **not committed to git** — GitHub hard-blocks files over 100MB, and a
binary that size doesn't belong in version control history regardless.

## Getting the file

1. The model is a **gated** Hugging Face repo — you need an HF account that has
   clicked "Agree and access repository" at
   https://huggingface.co/litert-community/gemma-3-270m-it
   (this is a one-time, per-account manual step; there's no API for accepting
   a gated license).
2. Generate a read-scoped access token for that account
   (huggingface.co → Settings → Access Tokens).
3. Download the file:

   ```bash
   HF_TOKEN=hf_...your_token...
   mkdir -p gemma_model_pack/src/main/assets
   curl -L -H "Authorization: Bearer $HF_TOKEN" \
     "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm" \
     -o gemma_model_pack/src/main/assets/gemma3-270m-it-q8.litertlm
   ```

## How it ships to users

This file is **not** fetched by the app at runtime and does not require the
`INTERNET` permission — it's baked into the app's own release as an on-demand
Play Asset Delivery pack (`gemma_model_pack/`, delivery type `on-demand`).
Users only download it if they explicitly opt into the "Balanced (Gemma 3
270M)" classifier tier in Settings; Play's own infrastructure serves the
transfer, not this app's code. See `GemmaModelStore.kt`.

Because of this, updating the model requires re-uploading a new app release
to Play Console (`app-release.aab`, built with the file present) — there's no
live update path for it.

## Local testing without a Play Console release

Play Asset Delivery can't be exercised via a plain `./gradlew installDebug`
APK — asset packs are an App Bundle concept only. To test locally:

```bash
./gradlew bundleDebug
bundletool build-apks \
  --bundle=app/build/outputs/bundle/debug/app-debug.aab \
  --output=/tmp/inboxiq.apks \
  --local-testing \
  --ks=~/.android/debug.keystore --ks-pass=pass:android \
  --ks-key-alias=androiddebugkey --key-pass=pass:android
bundletool install-apks --apks=/tmp/inboxiq.apks
```

`--local-testing` makes Play Core serve the asset pack from a locally-pushed
copy instead of Play's real infrastructure — this is the only way to verify
the download/extract/verify flow and `GemmaModelStore` without an actual
Play Console release.
