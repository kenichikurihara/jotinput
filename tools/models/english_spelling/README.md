---
license: apache-2.0
language: [en, fr, de, es, it, pt, ru, ar, ko, ja]
pipeline_tag: text2text-generation
tags: [onnx, spelling-correction, grammar, mobile, keyboard]
---

# gl-keyboard-correction

Spelling & sentence correction for mobile keyboards. 13.5M params trained
from scratch, shipped as int8 ONNX (27MB total), ~25ms per sentence on 2 CPU
threads. Also includes `lexicons.json`: frequency-ranked wordlists and
next-word tables for 10 locales.

Don't use it for Japanese. It makes Japanese text worse.

## Run the correction model

```
pip install onnxruntime sentencepiece numpy huggingface_hub
```

```python
import numpy as np, onnxruntime as ort, sentencepiece as spm
from huggingface_hub import hf_hub_download

repo = "Loke-60000/gl-keyboard-correction"
enc = ort.InferenceSession(hf_hub_download(repo, "gec-encoder-int8.onnx"))
dec = ort.InferenceSession(hf_hub_download(repo, "gec-decoder-int8.onnx"))
sp = spm.SentencePieceProcessor(model_file=hf_hub_download(repo, "spm.model"))

MAX_LEN = 96  # fixed shapes; pad=0, bos=2, eos=3

def correct(text, lang):  # lang: en fr de es it pt_br ru ar ko ja
    src = np.zeros((1, MAX_LEN), dtype=np.int64)
    ids = [sp.piece_to_id(f"<{lang}>")] + sp.encode(text)
    src[0, :len(ids)] = ids[:MAX_LEN]
    memory = enc.run(None, {"src": src})[0]
    tgt = np.zeros((1, MAX_LEN), dtype=np.int64)
    tgt[0, 0] = 2
    out = []
    for pos in range(min(len(ids) + 8, MAX_LEN - 1)):
        logits = dec.run(None, {"tgt": tgt, "pos": np.array([pos], dtype=np.int64),
                                "memory": memory, "src": src})[0]
        nxt = int(logits[0].argmax())
        if nxt == 3:
            break
        tgt[0, pos + 1] = nxt
        out.append(nxt)
    return sp.decode(out)

print(correct("i cant beleive its alredy friday", "en"))
print(correct("das waere schoen, vielen dank fuer alles", "de"))
```

## Use the wordlists

```python
import json
from huggingface_hub import hf_hub_download

lex = json.load(open(hf_hub_download("Loke-60000/gl-keyboard-correction", "lexicons.json")))
print(lex["en-US"][:10])                    # words ranked by frequency
print(lex["_nextWords"]["en-US"]["thank"])  # next-word prediction
```

## Deploying on phones

Numbers below are from a real Android integration, measured on device-class
ART (emulator) and a JVM benchmark. Phone CPUs scale roughly 3-5x slower
than the JVM figures.

Parse `lexicons.json` once per process, on a background thread, and build
your suggestion index there too. Parsing the 4.4MB JSON costs ~236 ms and
index building ~403 ms; doing both synchronously on every text-field focus
freezes the UI for ~640 ms per focus and 1.5-2 s on cold open. Keep one
parsed copy: re-parsing the same file for abbreviations or Japanese
conversions adds ~470 ms more.

Per-keystroke suggest over the 20,787-word en-US list (JVM):

| query          | latency |
|----------------|---------|
| w (1 char)     | 0.27 ms |
| he             | 0.25 ms |
| beleive        | 1.4 ms  |
| misunderstannd | 2.6 ms  |

For the ONNX model: run it only when a sentence is committed, never per
keystroke. Create the two `OrtSession`s once and reuse them for the process
lifetime; session creation is the expensive part, inference is ~25 ms.
Surface the result as a tappable suggestion, never a silent rewrite, and
show nothing when the output equals the input.

Training data: OPUS OpenSubtitles v2018 (Lison & Tiedemann, 2016). No
subtitle text is included in these files.
