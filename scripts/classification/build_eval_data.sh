#!/usr/bin/env bash
# Regenerates the synthetic corpus and the cached spam-model probabilities.
# Needs a python venv with `onnxruntime` and `numpy` (PY=/path/to/venv/bin/python).
#   spam_probs.tsv        scores of the shipped model (app/src/main/assets/spam_classifier_v1_q8.onnx)
#   spam_probs_fp32.tsv   scores of the original fp32 export from commit 3bd45fb (baseline); pass FP32=<path to that .onnx>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PY="${PY:-python3}"
OUT="$ROOT/app/src/test/resources/classification"
python3 "$ROOT/scripts/classification/gen_corpus.py" "$OUT"
"$PY" "$ROOT/scripts/classification/score_spam_model.py" \
  "$ROOT/app/src/main/assets/spam_classifier_v1_q8.onnx" "$ROOT/app/src/main/assets/spam_classifier_vocab.txt" "$OUT" \
  --out spam_probs.tsv --golden "$OUT/tokenizer_golden.tsv"
if [ -n "${FP32:-}" ]; then
  "$PY" "$ROOT/scripts/classification/score_spam_model.py" "$FP32" "$ROOT/app/src/main/assets/spam_classifier_vocab.txt" "$OUT" --out spam_probs_fp32.tsv
fi
