import math
import numpy as np
import onnxruntime as ort
import os
import time

model_dir = "tools/models/futo_swipe"
clm_sess = ort.InferenceSession(os.path.join(model_dir, "context_lm.onnx"))
emb_sess = ort.InferenceSession(os.path.join(model_dir, "embeddings.onnx"))

# Load vocab
vocab = [line.strip() for line in open(os.path.join(model_dir, "vocab.txt"), encoding='utf-8')]
exact_ids = {w: i for i, w in enumerate(vocab)}
lower_ids = {w.lower(): i for i, w in enumerate(vocab) if w.lower() not in exact_ids}

# Load embeddings
embs = emb_sess.run(None, {})
exact_embeddings = embs[0] # [32768, 16]
exact_biases = embs[1]     # [32768]

# QWERTY Key Proximity Map
QWERTY_ROWS = [
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm"
]
key_coords = {}
for r, row in enumerate(QWERTY_ROWS):
    for c, char in enumerate(row):
        offset = 0.0 if r == 0 else (0.5 if r == 1 else 1.0)
        key_coords[char] = (c + offset, float(r))

def key_dist(c1, c2):
    c1, c2 = c1.lower(), c2.lower()
    if c1 == c2: return 0.0
    if c1 in key_coords and c2 in key_coords:
        x1, y1 = key_coords[c1]
        x2, y2 = key_coords[c2]
        return math.sqrt((x1 - x2)**2 + (y1 - y2)**2)
    return 3.0

def word_edit_and_spatial_cost(typed, cand):
    # Normalized spatial / edit cost
    m, n = len(typed), len(cand)
    # Simple Damerau-Levenshtein with spatial distance
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1): dp[i][0] = i * 1.5
    for j in range(n + 1): dp[0][j] = j * 1.5
    
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            cost = key_dist(typed[i-1], cand[j-1])
            if cost > 0: cost = 0.8 + 0.4 * min(cost, 2.0)
            dp[i][j] = min(
                dp[i-1][j] + 1.2,        # deletion
                dp[i][j-1] + 1.2,        # insertion
                dp[i-1][j-1] + cost      # match / substitution
            )
            if i > 1 and j > 1 and typed[i-1] == cand[j-2] and typed[i-2] == cand[j-1]:
                dp[i][j] = min(dp[i][j], dp[i-2][j-2] + 0.8) # transposition
    return dp[m][n]

def get_typo_candidates(typed, top_n=5):
    typed_lower = typed.lower()
    candidates = []
    # Filter vocab by length diff <= 2 and first letter proximity <= 1.5
    for word in vocab:
        if abs(len(word) - len(typed_lower)) > 2:
            continue
        if len(typed_lower) >= 3 and key_dist(typed_lower[0], word[0].lower()) > 1.6:
            continue
        cost = word_edit_and_spatial_cost(typed_lower, word.lower())
        if cost <= 2.8:
            candidates.append((word, cost))
    candidates.sort(key=lambda x: x[1])
    return candidates[:top_n]

test_words = ["teh", "quikc", "helo", "alredy", "wrok", "morngin"]
for w in test_words:
    t0 = time.time()
    cands = get_typo_candidates(w)
    dt = (time.time() - t0) * 1000
    print(f"Typed: {w} -> {[c[0] for c in cands]} ({dt:.1f}ms)")
