import json
import math
import os
import time

VOCAB_PATH = "tools/models/futo_swipe/vocab.txt"
GOLDEN_PATH = "eval/english_completion/completion_eval_100.json"

# QWERTY Key Coordinates
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
    if c1 == c2: return 0.0
    if c1 in key_coords and c2 in key_coords:
        x1, y1 = key_coords[c1]
        x2, y2 = key_coords[c2]
        return math.sqrt((x1 - x2)**2 + (y1 - y2)**2)
    return 3.0

def edit_and_spatial_cost(typed, cand):
    m, n = len(typed), len(cand)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1): dp[i][0] = i * 1.5
    for j in range(n + 1): dp[0][j] = j * 1.5
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            kd = key_dist(typed[i-1], cand[j-1])
            sub_cost = 0.0 if kd == 0.0 else (0.8 + 0.3 * min(kd, 2.0))
            dp[i][j] = min(
                dp[i-1][j] + 1.2,
                dp[i][j-1] + 1.2,
                dp[i-1][j-1] + sub_cost
            )
            if i > 1 and j > 1 and typed[i-1] == cand[j-2] and typed[i-2] == cand[j-1]:
                dp[i][j] = min(dp[i][j], dp[i-2][j-2] + 0.6)
    return dp[m][n]

def get_deletes(word, max_edit_distance=2):
    deletes = set()
    queue = [word]
    for _ in range(max_edit_distance):
        temp = []
        for w in queue:
            if len(w) > 1:
                for i in range(len(w)):
                    del_word = w[:i] + w[i+1:]
                    if del_word not in deletes:
                        deletes.add(del_word)
                        temp.append(del_word)
        queue = temp
    return deletes

class BaselineTypoEngine:
    def __init__(self, vocab):
        self.word_to_count = {}
        self.deletes_dict = {}
        for rank, word in enumerate(vocab):
            w_lower = word.lower()
            count = int(10000000.0 / (rank + 1))
            if w_lower not in self.word_to_count:
                self.word_to_count[w_lower] = (word, count)
                for d in get_deletes(w_lower, max_edit_distance=2):
                    if d not in self.deletes_dict:
                        self.deletes_dict[d] = []
                    self.deletes_dict[d].append(w_lower)

    def suggest(self, typed, max_candidates=5):
        typed = typed.lower()
        cands = set()
        if typed in self.word_to_count:
            cands.add(typed)
        for d in get_deletes(typed, max_edit_distance=2):
            if d in self.word_to_count:
                cands.add(d)
            if d in self.deletes_dict:
                for w in self.deletes_dict[d]:
                    cands.add(w)

        scored = []
        for w in cands:
            orig_word, count = self.word_to_count[w]
            cost = edit_and_spatial_cost(typed, w)
            max_cost = 3.6 if len(w) >= 7 else 3.0
            if cost <= max_cost:
                log_freq = math.log(count + 1)
                score = log_freq - 2.8 * cost
                scored.append((orig_word, score))

        scored.sort(key=lambda x: x[1], reverse=True)
        return [x[0] for x in scored[:max_candidates]]

class ProposedHybridEngine:
    def __init__(self, vocab, gamma_len=0.45, bonus_cov=2.0):
        self.word_to_count = {}
        self.deletes_dict = {}
        self.prefix_dict = {}
        self.gamma_len = gamma_len
        self.bonus_cov = bonus_cov

        for rank, word in enumerate(vocab):
            w_lower = word.lower()
            count = int(10000000.0 / (rank + 1))
            if w_lower not in self.word_to_count:
                self.word_to_count[w_lower] = (word, count)

                for d in get_deletes(w_lower, max_edit_distance=2):
                    if d not in self.deletes_dict:
                        self.deletes_dict[d] = []
                    self.deletes_dict[d].append(w_lower)

                L = len(w_lower)
                for p_len in range(2, min(L, 11)):
                    p = w_lower[:p_len]
                    if p not in self.prefix_dict:
                        self.prefix_dict[p] = []
                    if len(self.prefix_dict[p]) < 12:
                        self.prefix_dict[p].append(w_lower)

    def suggest(self, typed, max_candidates=5):
        typed = typed.lower()
        m = len(typed)
        cands = set()

        prefix_matches = self.prefix_dict.get(typed, [])
        for w in prefix_matches:
            cands.add(w)

        if typed in self.word_to_count:
            cands.add(typed)

        for d in get_deletes(typed, max_edit_distance=2):
            if d in self.word_to_count:
                cands.add(d)
            if d in self.deletes_dict:
                for w in self.deletes_dict[d]:
                    cands.add(w)

        if m >= 4:
            for d in get_deletes(typed, max_edit_distance=1):
                if d in self.prefix_dict:
                    for w in self.prefix_dict[d][:4]:
                        cands.add(w)

        scored = []
        for w in cands:
            orig_word, count = self.word_to_count[w]
            n = len(w)
            log_freq = math.log(count + 1)

            if w.startswith(typed) and n > m:
                len_diff = n - m
                cov_ratio = m / float(n)
                score = log_freq - self.gamma_len * len_diff + self.bonus_cov * cov_ratio
                scored.append((orig_word, score))
            elif w == typed:
                score = log_freq + 1.0
                scored.append((orig_word, score))
            else:
                cost = edit_and_spatial_cost(typed, w)
                max_cost = 3.6 if n >= 7 else 3.0
                if cost <= max_cost:
                    score = log_freq - 2.8 * cost
                    scored.append((orig_word, score))

        scored.sort(key=lambda x: x[1], reverse=True)
        return [x[0] for x in scored[:max_candidates]]

def main():
    print("Loading vocabulary and completion benchmark dataset...")
    with open(VOCAB_PATH, "r", encoding="utf-8") as f:
        vocab = [line.strip() for line in f if line.strip()]

    with open(GOLDEN_PATH, "r", encoding="utf-8") as f:
        golden = json.load(f)

    print(f"Loaded {len(vocab)} vocab words and {len(golden)} benchmark test cases.")
    baseline = BaselineTypoEngine(vocab)
    proposed = ProposedHybridEngine(vocab)

    # Metrics
    # 1. Target Word Recall@1 and Recall@3
    # 2. Golden Reference Top-1 Agreement
    b_target_r1, b_target_r3 = 0, 0
    p_target_r1, p_target_r3 = 0, 0

    b_ref_r1, b_ref_r3 = 0, 0
    p_ref_r1, p_ref_r3 = 0, 0

    diff_cases = []

    for item in golden:
        target = item["target"].lower()
        prefix = item["prefix"].lower()
        ref_top1 = item.get("expected_top1", "").strip().lower()

        b_cands = [w.lower() for w in baseline.suggest(prefix, 5)]
        p_cands = [w.lower() for w in proposed.suggest(prefix, 5)]

        # Target recall
        if b_cands and b_cands[0] == target: b_target_r1 += 1
        if target in b_cands[:3]: b_target_r3 += 1

        if p_cands and p_cands[0] == target: p_target_r1 += 1
        if target in p_cands[:3]: p_target_r3 += 1

        # Reference agreement
        if ref_top1:
            if b_cands and b_cands[0] == ref_top1: b_ref_r1 += 1
            if ref_top1 in b_cands[:3]: b_ref_r3 += 1

            if p_cands and p_cands[0] == ref_top1: p_ref_r1 += 1
            if ref_top1 in p_cands[:3]: p_ref_r3 += 1

        if (target in p_cands) and (target not in b_cands):
            diff_cases.append((target, prefix, b_cands, p_cands))

    N = len(golden)
    print("\n=================================================================")
    print("      ENGLISH LONG-WORD COMPLETION (100 WORDS) BENCHMARK         ")
    print("=================================================================")
    print(f"Total Test Cases: {N}")
    print("\n[Target Completion Recall]")
    print(f"  Baseline Engine:  Recall@1: {b_target_r1}/{N} ({b_target_r1/N*100:.1f}%) | Recall@3: {b_target_r3}/{N} ({b_target_r3/N*100:.1f}%)")
    print(f"  Proposed Engine:  Recall@1: {p_target_r1}/{N} ({p_target_r1/N*100:.1f}%) | Recall@3: {p_target_r3}/{N} ({p_target_r3/N*100:.1f}%)")
    print(f"  --> Net Recall@3 Gain: +{(p_target_r3 - b_target_r3)/N*100:.1f}%")

    print("\n[Golden Reference Agreement]")
    print(f"  Baseline Engine:  Agreement@1: {b_ref_r1}/{N} ({b_ref_r1/N*100:.1f}%) | Top-3 Match: {b_ref_r3}/{N} ({b_ref_r3/N*100:.1f}%)")
    print(f"  Proposed Engine:  Agreement@1: {p_ref_r1}/{N} ({p_ref_r1/N*100:.1f}%) | Top-3 Match: {p_ref_r3}/{N} ({p_ref_r3/N*100:.1f}%)")
    print(f"  --> Net Reference Top-3 Agreement Gain: +{(p_ref_r3 - b_ref_r3)/N*100:.1f}%")

    print(f"\n[Highlighted Improvements (Rescued by Proposed Engine: {len(diff_cases)} cases)]")
    for t, p, b, pr in diff_cases[:8]:
        print(f"  '{p}' (Target: '{t}'):")
        print(f"     Baseline: {b[:4]}")
        print(f"     Proposed: {pr[:4]}")

    # Typo Non-Regression Check
    typo_tests = [
        ("teh", "the"),
        ("quikc", "quick"),
        ("alredy", "already"),
        ("wrok", "work"),
        ("definately", "definitely")
    ]
    print("\n[Typo Non-Regression Test]")
    all_typo_pass = True
    for typed, target in typo_tests:
        cands = [w.lower() for w in proposed.suggest(typed, 3)]
        ok = target in cands
        print(f"  '{typed}' -> {cands} [Pass: {ok}]")
        if not ok: all_typo_pass = False

    print(f"Typo Non-Regression: {'100% PASS' if all_typo_pass else 'FAILED'}")

if __name__ == "__main__":
    main()
