#!/usr/bin/env python3
"""Shrinks spam_classifier_v1.onnx (the optimum export) by storing the 30522x128 word-embedding table as int8 with a
per-row float scale (Gather -> Cast -> Mul). 17.6 MB -> 6.0 MB; spam probabilities move by at most
0.013 (checked over 7,425 texts, see docs/CLASSIFICATION.md). Needs `pip install onnx numpy`.

usage: quantize_embeddings.py <in.onnx> <out.onnx>
"""
import onnx, numpy as np, sys, os
from onnx import numpy_helper, helper, TensorProto
src, dst = sys.argv[1:3]
m = onnx.load(src)
g = m.graph
W = None
for i in g.initializer:
    if i.name == 'bert.embeddings.word_embeddings.weight':
        W = i
arr = numpy_helper.to_array(W)
scale = np.maximum(np.abs(arr).max(axis=1, keepdims=True), 1e-8) / 127.0
q = np.clip(np.round(arr / scale), -127, 127).astype(np.int8)
g.initializer.remove(W)
g.initializer.append(numpy_helper.from_array(q, 'word_emb_q'))
g.initializer.append(numpy_helper.from_array(scale.astype(np.float32), 'word_emb_scale'))
# find the Gather using the weight
for idx, n in enumerate(g.node):
    if n.op_type == 'Gather' and n.input[0] == W.name:
        ids = n.input[1]; out = n.output[0]
        axis = [a.i for a in n.attribute if a.name == 'axis']
        n1 = helper.make_node('Gather', ['word_emb_q', ids], ['wq_g'], axis=0)
        n2 = helper.make_node('Gather', ['word_emb_scale', ids], ['ws_g'], axis=0)
        n3 = helper.make_node('Cast', ['wq_g'], ['wq_f'], to=TensorProto.FLOAT)
        n4 = helper.make_node('Mul', ['wq_f', 'ws_g'], [out])
        g.node.remove(n)
        for k, nn in enumerate([n1, n2, n3, n4]):
            g.node.insert(idx + k, nn)
        break
else:
    raise SystemExit('gather not found')
onnx.checker.check_model(m)
onnx.save(m, dst)
print(os.path.getsize(dst))
