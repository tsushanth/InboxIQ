#!/usr/bin/env python3
"""Scores every corpus text with the bundled bert-tiny ONNX spam model and writes
spam_probs.tsv (sha1(utf8 text) <TAB> P(spam)).

The tokenizer below is a line-for-line port of app/.../classify/WordPieceTokenizer.kt
(checked by WordPieceTokenizerGoldenTest). Run with the venv python that has
`onnxruntime` and `numpy`:  score_spam_model.py <model.onnx> <vocab.txt> <classification_dir> [--golden out.tsv]
"""
import hashlib
import math
import os
import re
import sys

import numpy as np
import onnxruntime as ort

PUNCT = re.compile(r"[!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~]")
WS = re.compile(r"[ \t\n\x0b\f\r]+")
MAXLEN = 64


def load_vocab(path):
    vocab = {}
    with open(path, encoding="utf-8") as f:
        for line in f.read().split("\n"):
            line = line.rstrip("\r")
            if line:
                vocab[line] = len(vocab)
    return vocab


def basic(text):
    cleaned = PUNCT.sub(lambda m: " %s " % m.group(0), text.lower())
    return [w for w in WS.split(cleaned) if w.strip() != ""]


def wordpiece(vocab, words):
    out = []
    for word in words:
        if len(word) > 200:
            out.append("[UNK]")
            continue
        start, found, subs = 0, True, []
        while start < len(word):
            end, cur = len(word), None
            while start < end:
                sub = word[start:end]
                if start > 0:
                    sub = "##" + sub
                if sub in vocab:
                    cur = sub
                    break
                end -= 1
            if cur is None:
                found = False
                break
            subs.append(cur)
            start = end
        out.extend(subs if found else ["[UNK]"])
    return out


def encode(vocab, text):
    toks = ["[CLS]"] + wordpiece(vocab, basic(text)) + ["[SEP]"]
    if len(toks) > MAXLEN:
        toks = toks[: MAXLEN - 1] + ["[SEP]"]
    ids = [vocab.get(t, vocab.get("[UNK]", 100)) for t in toks]
    mask = [1] * len(ids)
    while len(ids) < MAXLEN:
        ids.append(vocab.get("[PAD]", 0))
        mask.append(0)
    return ids, mask


def main():
    model, vocab_path, cdir = sys.argv[1:4]
    vocab = load_vocab(vocab_path)
    texts = set()
    for sp in ("train", "dev", "test"):
        with open(os.path.join(cdir, sp + ".tsv"), encoding="utf-8") as f:
            next(f)
            for line in f:
                parts = line.rstrip("\n").split("\t")
                texts.update(parts[5].split(" ||| "))
    with open(os.path.join(cdir, "uci", "SMSSpamCollection"), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line:
                texts.add(line.split("\t", 1)[1])
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])
    out = []
    for t in sorted(texts):
        ids, mask = encode(vocab, t)
        feed = {"input_ids": np.array([ids], dtype=np.int64), "attention_mask": np.array([mask], dtype=np.int64),
                "token_type_ids": np.zeros((1, MAXLEN), dtype=np.int64)}
        logits = sess.run(None, feed)[0][0]
        m = max(logits)
        e = [math.exp(x - m) for x in logits]
        p = e[1] / sum(e)
        out.append((hashlib.sha1(t.encode("utf-8")).hexdigest(), p))
    with open(os.path.join(cdir, "spam_probs.tsv"), "w", encoding="utf-8") as f:
        for h, p in sorted(out):
            f.write("%s\t%.6f\n" % (h, p))
    if "--golden" in sys.argv:
        g = sys.argv[sys.argv.index("--golden") + 1]
        samples = ["Free entry in 2 a wkly comp to win FA Cup final tkts", "ok see you at 7!", "Your code is 482913", "héllo wörld 😂 test",
                   "WINNER!! Claim $1000 now: bit.ly/3xK9fPq", "a" * 250, " ".join(["word"] * 100), "tabs\tand\nnewlines  and   spaces", "don't can't it's"]
        with open(g, "w", encoding="utf-8") as f:
            for s in samples:
                ids, mask = encode(vocab, s)
                f.write("%s\t%s\n" % (s.replace("\t", "\\t").replace("\n", "\\n"), ",".join(map(str, ids))))
    print("scored", len(out), "texts")


if __name__ == "__main__":
    main()
