# Licence of the bundled spam model

Checked 2026-11-09 against the public model page and files. Nobody has been contacted.

## What is bundled

`app/src/main/assets/spam_classifier_v1_q8.onnx` and `spam_classifier_vocab.txt`: an ONNX export of
`mrm8488/bert-tiny-finetuned-sms-spam-detection` (Hugging Face), with the word-embedding table stored as int8
(see "Model export" in the README). The vocabulary is the standard uncased BERT word-piece list.

## What upstream says

| Item | Finding |
|---|---|
| Model repo `mrm8488/bert-tiny-finetuned-sms-spam-detection` | No licence: the repo metadata has no `license` field, the card (`README.md`) has no licence text, and there is no `LICENSE` file. The card is four lines: title, "Validation accuracy: 0.98", dataset `sms_spam`, one widget example. Last modified 2023-03-16. |
| Base model | Not named on the card or in `config.json` (`_name_or_path` is a private Colab path). The architecture (2 layers, hidden size 128, 2 heads, 30,522 word pieces) is that of Google's `bert_uncased_L-2_H-128_A-2` ("BERT-Tiny", Apache-2.0); the same author's other BERT-Tiny model cards say they start from Google's BERT-Tiny. So Apache-2.0 for the base is very likely, but it is an inference, not a statement by the author. (`prajjwal1/bert-tiny`, MIT, has the same shape and is the other candidate; both are permissive.) |
| Training data | The card names the Hugging Face dataset `sms_spam`, whose card says licence "unknown". The original UCI SMS Spam Collection page states **CC BY 4.0** (attribution required). Its messages come from the Grumbletext site (spam), the NUS SMS Corpus (most of the ham, collected from volunteers who knew it would be published; a 2011 post by its authors calls it public domain), a PhD thesis, and an earlier corpus. Provenance below the CC BY statement was not independently verified. |
| Sibling models by the same author | `bert-tiny-finetuned-enron-spam-detection` declares `apache-2.0`; this one does not. Not evidence of intent for this model. |

## What that means

Without a licence, the fine-tuned weights are "all rights reserved" by default: nothing grants permission to copy, modify
or redistribute them, and InboxIQ redistributes a converted and quantised copy inside a GPL app. The practical risk is
small (a 6 MB utility model that is widely downloaded and re-exported by others, nothing to suggest the author objects),
but the legal position is not clean, and the NOTICE must not claim more than is known. It now says exactly this.

The model is also weak for this app independent of its licence: trained on 2005-era UK SMS, it scores 16% of modern
personal-style texts above 0.6 (`docs/CLASSIFICATION.md`), and the rules layer does most of the work.

## Options

**(a) Ask the upstream author for a licence.** Open a discussion on the model page (or write to the author) asking him to
add a licence, ideally Apache-2.0 or MIT, and to name the base model. Cost: a few minutes. Odds of a useful answer: low to
moderate (the account is a prolific publisher; many requests like this go unanswered). It does not block anything and a "yes"
fully closes the question. Not done: this needs the owner's decision to contact someone.

**(b) Replace it with a model we train.** Fine-tune Google BERT-Tiny (Apache-2.0) ourselves on permissively licensed data:
the UCI collection (CC BY 4.0, attribution in NOTICE) plus modern examples we write or label ourselves (the synthetic corpora under `app/src/test/resources/classification/`, written by `scripts/classification/gen_corpus.py`,
are also ours; keep the evaluation sets out of training). The export recipe in the README and `scripts/classification/quantize_embeddings.py` are reusable and
the app's tokenizer and vocabulary stay the same, so the swap is one asset file. Cost: about a day of work plus a small
amount of GPU or CPU time; the evaluation harness already exists. Benefit: a model we own outright, and a chance to fix the
modern-text weakness that is the known deeper fix in the README. Risk: it must beat the current model on
`docs/CLASSIFICATION.md`'s corpora or it is not worth shipping; the NUS-derived ham in the UCI data carries the residual
provenance uncertainty above, which is a much smaller question than an unlicensed model.

**(c) Keep it, with attribution and an honest NOTICE.** Cost: nothing. Leaves the question open as described above.

## Recommendation

Do (c) now and (b) next; do (a) only if the owner wants to, in parallel, because it costs nothing.

1. Ship with the current model and the honest NOTICE (done in this change). The risk of leaving it for one more release is
   low and the cost of being wrong is a takedown request, answered by removing or replacing one asset.
2. Treat (b) as the actual fix, and schedule it with the classifier-quality work already listed in the README, not as a
   legal chore. It removes the only unlicensed asset in the repository.
3. Optionally do (a) first: if the author answers with a licence, (b) is no longer needed for legal reasons, only for quality.

Decision for the owner: whether to contact the upstream author (a), and whether (b) is wanted this cycle.
