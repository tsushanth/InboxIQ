# Classification evaluation corpora

* `uci/SMSSpamCollection` (+ `README.uci.txt`): the UCI "SMS Spam Collection v.1"
  (Almeida and Gomez Hidalgo, 2011), 5,574 English SMS tagged ham/spam.
  Downloaded once on 2026-10-13 from
  https://archive.ics.uci.edu/static/public/228/sms+spam+collection.zip
  (SHA-256 of the extracted file: `7d039a24a6083ed9ef0f806ebad56bbb976e3aeb8de05669173bfdc4996c239d`).
  Licence: CC BY 4.0 as listed on the UCI repository page; the corpus's own readme
  carries an as-is disclaimer and asks users to cite the paper (reproduced in `README.uci.txt`).
  Test-only data, not shipped in the APK.
* `train.tsv`, `dev.tsv`, `test.tsv`: SYNTHETIC. Written by `scripts/classification/gen_corpus.py`
  (deterministic, seed in the script). All numbers are 555-01xx, domains are example.* or made up,
  businesses are fictional. No real message was read or used.
* `spam_probs.tsv`: output of the bundled bert-tiny ONNX model for every text above, keyed by
  SHA-1 of the UTF-8 text. Produced by `scripts/classification/score_spam_model.py` so the JVM
  tests need no native ONNX runtime. Regenerate with `scripts/classification/build_eval_data.sh`.
