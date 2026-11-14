"""
futo_swipe_pipeline.py

Cleanroom, independent Python implementation of FUTO Swipe inference with ONNX Runtime.
Contains:
1. Trace Resampling (time linear interp to 60Hz -> length 64)
2. QWERTY Geometry & Layout tensors
3. Encoder (honorable_sturgeon) + Decoder (magic_macaw) forward passes
4. Lexicon Trie & Length-normalized CTC Prefix Search
5. ContextLM (hungry_jellyfish) Causal DFSMN state computation & log-prob scoring via wyhash-style hashing.
"""

import json
import math
import numpy as np
import onnxruntime as ort
import os
import re

# Constants
FUTO_SWIPE_TIME_STEPS = 32
FUTO_SWIPE_CLASS_COUNT = 27  # 26 letters + blank (index 26)
FUTO_SWIPE_MAX_KEYS = 64
FUTO_CONTEXT_LENGTH = 16
FUTO_CONTEXT_DIM = 16
FUTO_CONTEXT_VOCAB_SIZE = 32768
FUTO_CONTEXT_HASHED_TOKEN_ID = 32768

MASK_64 = (1 << 64) - 1
CONSTANTS = [
    0xa0761d6478bd642f,
    0xe7037ed1a0b428db,
    0x8ebc6af09c88c6e3,
    0x589965cc75374cc3,
]
BUCKET_MULTIPLIERS = [
    0x9e3779b97f4a7c15,
    0x517cc1b727220a95,
]

def _mix(a: int, b: int) -> int:
    a &= MASK_64
    b &= MASK_64
    prod = a * b
    return (prod & MASK_64) ^ (prod >> 64)

def futo_context_hash(word: str) -> int:
    b = word.encode('utf-8')
    n = len(b)

    def read_u(start: int, width: int) -> int:
        val = 0
        for i in range(width):
            val |= b[start + i] << (8 * i)
        return val

    state = _mix(CONSTANTS[0], CONSTANTS[1])
    left = 0
    right = 0

    if n <= 16:
        if n >= 4:
            step = (n // 8) * 4
            left = (read_u(0, 4) << 32) | read_u(step, 4)
            right = (read_u(n - 4, 4) << 32) | read_u(n - 4 - step, 4)
        elif n > 0:
            left = (b[0] << 16) | (b[n // 2] << 8) | b[-1]
    else:
        lanes = [state, state, state]
        offset = 0
        if n > 48:
            while offset + 48 <= n:
                for lane in range(3):
                    p = offset + lane * 16
                    lanes[lane] = _mix(
                        read_u(p, 8) ^ CONSTANTS[lane + 1],
                        read_u(p + 8, 8) ^ lanes[lane],
                    )
                offset += 48
        while offset + 16 <= n:
            lanes[0] = _mix(
                read_u(offset, 8) ^ CONSTANTS[1],
                read_u(offset + 8, 8) ^ lanes[0],
            )
            offset += 16
        state = (lanes[0] ^ lanes[1] ^ lanes[2]) if n > 48 else lanes[0]
        left = read_u(n - 16, 8)
        right = read_u(n - 8, 8)

    return _mix(
        CONSTANTS[1] ^ n,
        _mix(left ^ CONSTANTS[1], right ^ state),
    )

def futo_context_buckets(word: str):
    h = futo_context_hash(word)
    return [
        int(((h * mult) & MASK_64) >> 49)
        for mult in BUCKET_MULTIPLIERS
    ]

def resample_trace(points, max_pause_ms: float = 65.0, arc_blend: float = 0.02, smooth_alpha: float = 0.06):
    """
    points: list of dict {'x': float, 'y': float, 't': int (ms or us)}
    Returns: np.ndarray shape (2, 64), float32
    Implements Idea 5:
      1. Stationary pause time clamping (dt > max_pause_ms when dist < 0.03)
      2. 60Hz time interpolation + hybrid cumulative arc-length parameterization
      3. Endpoint-preserving 3-tap low-pass smoothing on interior points [1..62]
    """
    count = len(points)
    if count == 0:
        return np.zeros((2, 64), dtype=np.float32)
    if count == 1:
        res = np.zeros((2, 64), dtype=np.float32)
        res[0, :] = points[0]['x']
        res[1, :] = points[0]['y']
        return res

    t0 = points[0]['t']
    raw_times = np.array([(p['t'] - t0) for p in points], dtype=np.float64)
    if len(raw_times) > 1 and raw_times[-1] > 100000:
        raw_times /= 1000.0  # convert microseconds to ms

    xs = np.array([p['x'] for p in points], dtype=np.float64)
    ys = np.array([p['y'] for p in points], dtype=np.float64)

    # 1. Stationary pause clamping
    times = np.zeros_like(raw_times)
    for i in range(1, count):
        dt = max(0.0, raw_times[i] - raw_times[i - 1])
        dist = math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if dist < 0.03 and dt > max_pause_ms:
            dt = max_pause_ms
        times[i] = times[i - 1] + dt

    duration = times[-1]
    if duration > 1e-3:
        samples = max(2, int(round(duration / (1000.0 / 60.0))) + 1)
        grid = np.linspace(0.0, duration, samples)
        stage_x = np.interp(grid, times, xs)
        stage_y = np.interp(grid, times, ys)
    else:
        stage_x = xs
        stage_y = ys

    # 2. Hybrid time + cumulative arc-length parameterization to 64 points
    s_len = len(stage_x)
    if s_len > 2 and arc_blend > 0.0:
        dx = np.diff(stage_x)
        dy = np.diff(stage_y)
        ds = np.hypot(dx, dy)
        cum_s = np.concatenate([[0.0], np.cumsum(ds)])
        if cum_s[-1] > 1e-5:
            t_norm = np.linspace(0.0, 1.0, s_len)
            s_norm = cum_s / cum_s[-1]
            hybrid_param = (1.0 - arc_blend) * t_norm + arc_blend * s_norm
            target_grid = np.linspace(0.0, 1.0, 64)
            rx = np.interp(target_grid, hybrid_param, stage_x).astype(np.float32)
            ry = np.interp(target_grid, hybrid_param, stage_y).astype(np.float32)
        else:
            rx = np.full(64, stage_x[0], dtype=np.float32)
            ry = np.full(64, stage_y[0], dtype=np.float32)
    else:
        positions = np.arange(s_len, dtype=np.float64)
        idx_grid = np.linspace(0.0, s_len - 1, 64)
        rx = np.interp(idx_grid, positions, stage_x).astype(np.float32)
        ry = np.interp(idx_grid, positions, stage_y).astype(np.float32)

    # 3. Endpoint-preserving 3-tap low-pass smoothing on interior points [1..62]
    if smooth_alpha > 0.0:
        sx = rx.copy()
        sy = ry.copy()
        sx[1:-1] = (1.0 - smooth_alpha) * rx[1:-1] + 0.5 * smooth_alpha * (rx[:-2] + rx[2:])
        sy[1:-1] = (1.0 - smooth_alpha) * ry[1:-1] + 0.5 * smooth_alpha * (ry[:-2] + ry[2:])
        rx, ry = sx, sy

    return np.stack([rx, ry], axis=0)  # shape (2, 64)

def default_qwerty_layout():
    """
    Uniform QWERTY layout matching FUTO paper & validation.
    Returns:
      layout_keys: shape (64, 2) float32
      layout_mask: shape (64,) bool
    """
    rows = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']
    starts = [0.05, 0.10, 0.20]
    key_centers = {}
    for r_idx, (row, start) in enumerate(zip(rows, starts)):
        for c_idx, ch in enumerate(row):
            key_centers[ch] = (start + c_idx * 0.1, (r_idx + 0.5) / 3.0)

    layout_keys = np.zeros((FUTO_SWIPE_MAX_KEYS, 2), dtype=np.float32)
    for i in range(26):
        ch = chr(ord('a') + i)
        cx, cy = key_centers[ch]
        layout_keys[i, 0] = cx
        layout_keys[i, 1] = cy

    layout_mask = np.zeros((FUTO_SWIPE_MAX_KEYS,), dtype=bool)
    layout_mask[:26] = True
    return layout_keys, layout_mask

class LexiconNode:
    __slots__ = ('text', 'last', 'children', 'surfaces', 'has_plain_surface', 'frequency')
    def __init__(self, text: str, last: int):
        self.text = text
        self.last = last
        self.children = {}  # key: letter index 0..25 -> LexiconNode
        self.surfaces = []  # list of (surface_str, vocab_rank, zipf_freq, has_apostrophe)
        self.has_plain_surface = False
        self.frequency = 0.0

class PrefixMass:
    __slots__ = ('blank', 'letter', 'priority')
    def __init__(self):
        self.blank = -float('inf')
        self.letter = -float('inf')
        self.priority = -float('inf')

    @property
    def total(self):
        return log_add(self.blank, self.letter)

def log_add(a: float, b: float) -> float:
    if a == -float('inf'): return b
    if b == -float('inf'): return a
    hi = max(a, b)
    lo = min(a, b)
    return hi + math.log(1.0 + math.exp(lo - hi))

def add_lexicon_word(root: LexiconNode, surface: str, vocab_rank: int = 32768):
    key = re.sub(r"['\u2019]", "", surface.lower())
    if not key or not key.isalpha() or not key.isascii():
        return False
    node = root
    for ch in key:
        idx = ord(ch) - ord('a')
        if idx not in node.children:
            node.children[idx] = LexiconNode(node.text + ch, idx)
        node = node.children[idx]

    zipf_freq = max(0.0, float(255.0 * (1.0 - math.log(vocab_rank + 1.0) / math.log(32768.0))))
    has_apos = ("'" in surface) or ("\u2019" in surface)

    found = False
    for i, (s, r, f, ha) in enumerate(node.surfaces):
        if s == surface:
            node.surfaces[i] = (s, min(r, vocab_rank), max(f, zipf_freq), ha)
            found = True
            break
    if not found:
        node.surfaces.append((surface, vocab_rank, zipf_freq, has_apos))
    if not has_apos:
        node.has_plain_surface = True
    node.frequency = max(node.frequency, zipf_freq)
    return True

def ctc_prefix_search(logits: np.ndarray, root: LexiconNode, beam: int = 100, gamma: float = 0.1902, beta: float = 1.2727):
    """
    logits: shape (32, 27) float32
    """
    steps, classes = logits.shape
    blank = classes - 1
    states = {root: PrefixMass()}
    states[root].blank = 0.0

    divisors = [max(1.0, float(i)) ** gamma for i in range(steps + 1)]

    for t in range(steps):
        next_states = {}
        row = logits[t]

        for node, old in states.items():
            # Extend with blank
            same = next_states.setdefault(node, PrefixMass())
            same.blank = log_add(same.blank, old.total + row[blank])

            # Repeat final letter
            if node.last >= 0:
                same.letter = log_add(same.letter, old.letter + row[node.last])

            # Extend with children
            for edge_idx, edge_node in node.children.items():
                dest = next_states.setdefault(edge_node, PrefixMass())
                prev = old.blank if edge_idx == node.last else old.total
                dest.letter = log_add(dest.letter, prev + row[edge_idx])

        # Priority calculation and pruning
        ordered = list(next_states.items())
        for node, p in ordered:
            l = len(node.text)
            div = divisors[min(l, steps)]
            p.priority = p.total / div + beta * l

        ordered.sort(key=lambda item: item[1].priority, reverse=True)
        states = dict(ordered[:beam])

    return states

class FutoSwipeEngine:
    def __init__(self, model_dir: str = 'tools/models/futo_swipe'):
        self.model_dir = model_dir

        # ONNX sessions
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 4
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        self.enc_sess = ort.InferenceSession(os.path.join(model_dir, 'encoder.onnx'), opts)
        self.dec_sess = ort.InferenceSession(os.path.join(model_dir, 'decoder.onnx'), opts)
        self.clm_sess = ort.InferenceSession(os.path.join(model_dir, 'context_lm.onnx'), opts)
        self.emb_sess = ort.InferenceSession(os.path.join(model_dir, 'embeddings.onnx'), opts)

        # Load embeddings outputs
        emb_outs = self.emb_sess.run(None, {})
        self.exact_embeddings = emb_outs[0]     # (32768, 16)
        self.exact_biases = emb_outs[1]         # (32768,)
        self.hashed_embeddings = emb_outs[2]    # (32768, 16)
        self.hashed_biases = emb_outs[3]        # (32768,)

        # Load vocabulary
        vocab_file = os.path.join('tools/futo_swipe/vocab.txt')
        with open(vocab_file, 'r', encoding='utf-8') as f:
            self.vocabulary = [line.strip() for line in f if line.strip()]

        self.exact_ids = {}
        self.lowercase_ids = {}
        for idx, w in enumerate(self.vocabulary[:32767]):
            token_id = idx + 1  # 0 is padding
            self.exact_ids[w] = token_id
            self.lowercase_ids.setdefault(w.lower(), token_id)

        # Load scoring weights
        scoring_file = os.path.join('tools/futo_swipe/scoring.json')
        with open(scoring_file, 'r', encoding='utf-8') as f:
            scoring_data = json.load(f)

        self.dec_weights = scoring_data["encoder:honorable_sturgeon decoder:magic_macaw"]
        self.ctx_weights = scoring_data["encoder:honorable_sturgeon decoder:magic_macaw contextlm:hungry_jellyfish"]

        # Default layout
        self.layout_keys, self.layout_mask = default_qwerty_layout()
        self.key_centers = self.layout_keys[:26].copy()

        # Build Lexicon Trie (Idea 2: Zipf rank prior + possessive base stem derivation)
        self.root = LexiconNode('', -1)
        for rank, w in enumerate(self.vocabulary):
            add_lexicon_word(self.root, w, rank)

        for rank, w in enumerate(self.vocabulary):
            if len(w) >= 4 and (w.endswith("'s") or w.endswith("'S") or w.endswith("\u2019s")):
                stem = w[:-2]
                if stem.isalpha() and stem.isascii() and stem.lower() not in self.lowercase_ids:
                    add_lexicon_word(self.root, stem, rank)

        self.context_cache = {}

    def set_layout_keys(self, key_centers_xy):
        arr = np.asarray(key_centers_xy, dtype=np.float32).reshape(-1, 2)
        if len(arr) >= 26:
            self.key_centers = arr[:26].copy()
            self.layout_keys[:26] = self.key_centers
            self.layout_mask[:26] = True

    def lookup_unigram_bias(self, word: str) -> float:
        w_id = self.exact_ids.get(word) or self.lowercase_ids.get(word.lower())
        if w_id is not None:
            return float(self.exact_biases[w_id])
        b0, b1 = futo_context_buckets(word)
        return float(self.hashed_biases[b0] + self.hashed_biases[b1])

    def get_context_state(self, previous_words):
        if not previous_words:
            tokens = []
        else:
            tokens = previous_words[-FUTO_CONTEXT_LENGTH:]

        key = "\x00".join(tokens)
        if key in self.context_cache:
            return self.context_cache[key]

        token_ids = np.zeros((1, FUTO_CONTEXT_LENGTH), dtype=np.int64)
        hash_buckets = np.zeros((1, FUTO_CONTEXT_LENGTH, 2), dtype=np.int64)

        for i, word in enumerate(tokens):
            w_id = self.exact_ids.get(word) or self.lowercase_ids.get(word.lower())
            if w_id is not None:
                token_ids[0, i] = w_id
            else:
                token_ids[0, i] = FUTO_CONTEXT_HASHED_TOKEN_ID
                b0, b1 = futo_context_buckets(word)
                hash_buckets[0, i, 0] = b0
                hash_buckets[0, i, 1] = b1

        out = self.clm_sess.run(None, {
            'token_ids': token_ids,
            'hash_buckets': hash_buckets
        })
        states = out[0] # (1, 16, 16)
        offset = max(0, len(tokens) - 1)
        state_vec = states[0, offset].astype(np.float32)
        self.context_cache[key] = state_vec
        return state_vec

    def score_word_in_context(self, state_vec, word):
        w_id = self.exact_ids.get(word) or self.lowercase_ids.get(word.lower())
        if w_id is not None:
            score = float(self.exact_biases[w_id])
            score += float(np.dot(state_vec, self.exact_embeddings[w_id]))
            return score
        else:
            buckets = futo_context_buckets(word)
            score = 0.0
            for b in buckets:
                score += float(self.hashed_biases[b])
                score += float(np.dot(state_vec, self.hashed_embeddings[b]))
            return score

    def decode(self, trajectory, previous_words=None, beam=100, max_candidates=5):
        """
        trajectory: list of dict {'x': float, 'y': float, 't': int}
        previous_words: list of str or None. If None, decode without ContextLM.
        """
        # 1. Resample trajectory (Idea 5)
        resampled = resample_trace(trajectory)  # (2, 64)
        px0, py0 = float(resampled[0, 0]), float(resampled[1, 0])
        px1, py1 = float(resampled[0, 63]), float(resampled[1, 63])
        features = np.expand_dims(resampled, 0)  # (1, 2, 64)

        layout_k = np.expand_dims(self.layout_keys, 0) # (1, 64, 2)
        layout_m = np.expand_dims(self.layout_mask, 0) # (1, 64)

        # 2. Encoder forward
        enc_out = self.enc_sess.run(None, {
            'features': features,
            'layout_keys': layout_k,
            'layout_mask': layout_m
        })
        log_emissions = enc_out[0]  # (1, 32, 65)
        coefficients = enc_out[1]   # (1, 32, 64)
        intention = enc_out[2]      # (1, 32, 1)

        # 3. Assemble combined decoder input: (1, 32, 92)
        combined = np.zeros((1, FUTO_SWIPE_TIME_STEPS, 92), dtype=np.float32)
        # letter scores (26)
        combined[:, :, :26] = log_emissions[:, :, :26]
        # blank score (1)
        combined[:, :, 26] = log_emissions[:, :, 64]
        # coefficients (64)
        combined[:, :, 27:91] = coefficients[:, :, :64]
        # intention (1)
        combined[:, :, 91] = intention[:, :, 0]

        # 4. Decoder forward
        dec_out = self.dec_sess.run(None, {'features': combined})
        refined_logits = dec_out[0][0] # (32, 27)

        # 5. CTC prefix search
        weights = self.dec_weights if previous_words is None else self.ctx_weights
        states = ctc_prefix_search(
            refined_logits,
            self.root,
            beam=beam,
            gamma=weights['gamma_prune'],
            beta=weights['beta_prune']
        )

        # 6. Candidate scoring & Rescoring (Ideas 2, 3, 4)
        state_vec = None
        if previous_words is not None:
            state_vec = self.get_context_state(previous_words)

        gamma = weights['gamma']
        beta = weights['beta']
        alpha = weights['alpha'] if state_vec is not None else 0.0

        best_common_tot = -1e9
        for node, mass in states.items():
            if not node.surfaces:
                continue
            tot = mass.total
            if any(r < 6000 for (_, r, _, _) in node.surfaces):
                if tot > best_common_tot:
                    best_common_tot = tot

        candidates = []
        for node, mass in states.items():
            if not node.surfaces or not node.text:
                continue
            l = len(node.text)
            tot = mass.total

            # Idea 3: Start & End Key Radial Anchor Distance Penalty
            c0 = ord(node.text[0]) - ord('a')
            c1 = ord(node.text[-1]) - ord('a')
            d0_sq = (px0 - float(self.key_centers[c0, 0])) ** 2 + (py0 - float(self.key_centers[c0, 1])) ** 2
            d1_sq = (px1 - float(self.key_centers[c1, 0])) ** 2 + (py1 - float(self.key_centers[c1, 1])) ** 2

            base_score = tot / (l ** gamma) + beta * l - 2.0 * d0_sq - 3.5 * d1_sq

            for surface, vocab_rank, zipf_freq, has_apos in node.surfaces:
                total_s = base_score

                # Idea 4: Shared-node apostrophe disambiguation
                if has_apos and node.has_plain_surface:
                    total_s -= 2.5

                if state_vec is None:
                    # Idea 2: Unigram frequency prior + embedding unigram bias in spatial mode
                    uni_bias = self.lookup_unigram_bias(surface)
                    total_s += 0.004 * zipf_freq + 0.25 * uni_bias
                else:
                    # Idea 2: Mild Zipf rank prior in context mode
                    total_s += 0.0015 * zipf_freq
                    ctx_score = self.score_word_in_context(state_vec, surface)
                    # Idea 4: Confidence-gated ContextLM length bonus protection
                    tot_gap = best_common_tot - tot
                    if best_common_tot > -0.55 and tot_gap > 1.7 and ctx_score < 1.0:
                        total_s -= 0.80 * (tot_gap - 1.7)
                    total_s += alpha * ctx_score

                candidates.append((surface, total_s))

        candidates.sort(key=lambda x: x[1], reverse=True)

        # Deduplicate
        seen = set()
        deduped = []
        for word, score in candidates:
            lower = word.lower()
            if lower not in seen:
                seen.add(lower)
                deduped.append((word, score))
            if len(deduped) >= max_candidates:
                break

        return deduped
