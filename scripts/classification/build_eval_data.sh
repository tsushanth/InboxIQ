#!/usr/bin/env bash
# Regenerates the synthetic corpus and the cached spam-model probabilities.
# Needs a python venv with `onnxruntime` and `numpy` (PY=/path/to/venv/bin/python).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PY="${PY:-python3}"
OUT="$ROOT/app/src/test/resources/classification"
python3 "$ROOT/scripts/classification/gen_corpus.py" "$OUT"
"$PY" "$ROOT/scripts/classification/score_spam_model.py" \
  "$ROOT/app/src/main/assets/spam_classifier_v1.onnx" "$ROOT/app/src/main/assets/spam_classifier_vocab.txt" "$OUT" \
  --golden "$OUT/tokenizer_golden.tsv"
