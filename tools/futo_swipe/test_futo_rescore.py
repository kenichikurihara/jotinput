import numpy as np
import onnxruntime as ort
import os

model_dir = "tools/models/futo_swipe"
clm_sess = ort.InferenceSession(os.path.join(model_dir, "context_lm.onnx"))
emb_sess = ort.InferenceSession(os.path.join(model_dir, "embeddings.onnx"))

print("CLM inputs:", [(i.name, i.shape, i.type) for i in clm_sess.get_inputs()])
print("CLM outputs:", [(o.name, o.shape, o.type) for o in clm_sess.get_outputs()])
print("EMB inputs:", [(i.name, i.shape, i.type) for i in emb_sess.get_inputs()])
print("EMB outputs:", [(o.name, o.shape, o.type) for o in emb_sess.get_outputs()])
