#!/usr/bin/env python3
"""
Comprehensive Evaluation & Ablation Study of 5 Algorithmic Predictive Ideas.

Evaluates:
- Baseline SLM OFF
- Baseline SLM ON
- Idea 1 alone: Proposal Recall Expansion (Speculative Token & Enriched Proposal)
- Idea 2 alone: Information-Gain Gated Stratum Promotion & Length Normalization
- Idea 3 alone: Hierarchical Contextual Bigram Cache (Dirichlet-Multinomial Prior)
- Idea 4 alone: Lookahead Continuation Bonus (Prefix-Marginalized Lookahead)
- Idea 5 alone: Subword-Boundary Alignment & Space Canonicalization
- Combinations:
  - Combo A: Idea 2 + 3
  - Combo B: Idea 3 + 5
  - Combo C: Idea 2 + 3 + 5
  - Combo D: Idea 1 + 2 + 3 + 5
  - Combo E: Full Ensemble (Idea 1 + 2 + 3 + 4 + 5)
"""

import json
import os
import subprocess
import time
import math
from typing import Dict, List, Tuple, Any

GOLDEN_PATH = "tools/eval/reference_bigram_prediction_golden_set.json"
BASE_DICT_PATH = "tools/mozc_dict/base_dictionary.tsv"
EN_VOCAB_PATH = "tools/models/futo_swipe/vocab.txt"
SLM_CLI_PATH = "./tools/slm_scorer_cli"
SLM_MODEL_PATH = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
OUTPUT_PATH = "tools/eval/prediction_ideas_ablation_results.json"


class SlmScorerClient:
    def __init__(self, cli_path=SLM_CLI_PATH, model_path=SLM_MODEL_PATH):
        self.proc = subprocess.Popen(
            [cli_path, model_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        line = self.proc.stderr.readline()
        if "Model initialized successfully" not in line:
            raise RuntimeError(f"SLM Scorer failed to initialize: {line}")
        print(f"[SlmScorerClient] Initialized: {line.strip()}")
        self.counter = 0

    def score(self, context: str, candidates: List[str]) -> List[float]:
        if not candidates:
            return []
        self.counter += 1
        req_id = str(self.counter)
        eff_ctx = context if context.strip() else "<s>"
        unique_cands = []
        for c in candidates:
            if c not in unique_cands:
                unique_cands.append(c)
        req_line = f"{req_id}\t{eff_ctx}\t" + "\t".join(unique_cands) + "\n"
        self.proc.stdin.write(req_line)
        self.proc.stdin.flush()

        resp_line = self.proc.stdout.readline().strip()
        parts = resp_line.split("\t")
        if len(parts) < 2:
            return [20.0] * len(candidates)
        logprobs = [float(p) for p in parts[2:]]
        nll_map = {c: -lp for c, lp in zip(unique_cands, logprobs)}
        return [nll_map.get(c, 20.0) for c in candidates]

    def close(self):
        self.proc.terminate()


def load_japanese_dict(path: str):
    exact_map = {}
    all_entries = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) >= 5:
                reading, cost, surface = parts[0], int(parts[3]), parts[4]
                if reading not in exact_map:
                    exact_map[reading] = []
                exact_map[reading].append((surface, cost))
                all_entries.append((reading, cost, surface))
    return exact_map, all_entries


def load_english_vocab(path: str) -> List[str]:
    vocab = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            w = line.strip()
            if w:
                vocab.append(w)
    return vocab


CANONICAL_COLLOCATIONS = {
    "よろしく": ["お願いします", "お願いいたします", "どうぞ"],
    "お願い": ["します", "いたします", "申し上げます"],
    "ありがとう": ["ございます", "ございました"],
    "お疲れ様": ["です", "でした"],
    "お疲れ": ["様です", "様でした"],
    "ご": ["連絡", "確認", "案内", "検討"],
    "確認": ["しました", "いたします", "お願いします", "中"],
    "作業": ["中", "完了", "内容", "を進めます"],
    "了解": ["しました", "いたしました", "です"],
    "承知": ["いたしました", "しました"],
    "いつも": ["お世話になっております", "ありがとうございます"],
    "お世話": ["になっております", "になります"],
    "お待たせ": ["いたしました", "しました"],
    "ご検討": ["のほどよろしくお願いいたします", "ください"],
    "申し訳": ["ありません", "ございません", "ない"],
    "大丈夫": ["です", "ですか", "でしょうか"],
    "問題": ["ありません", "ない", "ですか"],
    "連絡": ["します", "いたします", "お待ちしております"],
    "今日": ["は", "の", "中に", "も"],
    "明日": ["は", "の", "までに", "も"],
}

COMMON_JA_CONTINUATIONS_STD = [
    "お願いします", "ございます", "でしょうか", "いたします", "ください",
    "でした", "ました", "おります", "について", "から", "まで", "です", "ます",
    "しました", "して", "すること", "ありがとう", "よろしく", "確認", "連絡"
]

COMMON_JA_CONTINUATIONS_EXPANDED = COMMON_JA_CONTINUATIONS_STD + [
    "いたしますので", "お願い申し上げます", "させていただきたく", "ございますでしょうか",
    "頂けますでしょうか", "存じます", "お願いいたします", "お世話になっております",
    "ありがとうございます", "申し訳ございません", "お待たせいたしました",
    "よろしくお願いいたします", "ご案内", "ご相談", "ご報告", "承知いたしました"
]

COMMON_EN_CONTINUATIONS_STD = [
    "you", "the", "to", "a", "and", "is", "are", "have", "do", "can",
    "will", "about", "for", "in", "on", "with", "it", "that", "this",
    "me", "us", "them", "much", "so", "very", "all", "here", "there", "out", "now"
]

COMMON_EN_CONTINUATIONS_EXPANDED = COMMON_EN_CONTINUATIONS_STD + [
    "know", "see", "get", "make", "think", "take", "come", "time", "day", "way",
    "back", "up", "down", "again", "please", "help", "work", "great", "good",
    "well", "today", "tomorrow", "tonight", "soon", "later", "before", "after",
    "your", "my", "our", "their", "his", "her", "its", "what", "how", "when"
]


def get_ja_candidates(query: str, exact_map, all_entries, max_pool: int = 15) -> List[Tuple[str, int]]:
    results = []
    seen = set()
    if query in exact_map:
        for surf, cost in sorted(exact_map[query], key=lambda x: x[1]):
            if surf not in seen:
                seen.add(surf)
                results.append((surf, cost))
    prefix_matches = []
    for r, cost, surf in all_entries:
        if r.startswith(query) and r != query:
            if surf not in seen:
                seen.add(surf)
                prefix_matches.append((surf, cost))
    prefix_matches.sort(key=lambda x: x[1])
    results.extend(prefix_matches[:max_pool])
    return results


def evaluate_configuration(
    cfg_name: str,
    golden_data: Dict,
    exact_map,
    all_entries,
    en_vocab: List[str],
    scorer: SlmScorerClient,
    idea1_proposal: bool = False,
    idea2_length_norm: bool = False,
    idea3_bigram_cache: bool = False,
    idea4_lookahead: bool = False,
    idea5_space_canonical: bool = False,
    slm_enabled: bool = True
) -> Dict:
    ja_cases = golden_data["japanese"]
    en_cases = golden_data["english"]

    evaluated_ja = []
    evaluated_en = []

    alpha = 0.20  # Standard JotInput SLM weight alpha

    # 1. Evaluate Japanese
    for item in ja_cases:
        case_id = item["id"]
        prev_word = item["prev_word"]
        target_word = item["target_word"]
        target_reading = item["target_reading"]
        q1 = item["one_char"]["query"]
        q2 = item["two_char"]["query"]

        ref_0_no = item["zero_char"]["no_history"]
        ref_0_with = item["zero_char"]["with_history"]
        ref_1_no = item["one_char"]["no_history"]
        ref_1_with = item["one_char"]["with_history"]
        ref_2_no = item["two_char"]["no_history"]
        ref_2_with = item["two_char"]["with_history"]

        # Pool size determined by Idea 1
        ja_pool_size = 30 if idea1_proposal else 12
        base_1 = get_ja_candidates(q1, exact_map, all_entries, max_pool=ja_pool_size)
        base_2 = get_ja_candidates(q2, exact_map, all_entries, max_pool=ja_pool_size)

        if not slm_enabled:
            # Baseline SLM OFF
            p0_no = CANONICAL_COLLOCATIONS.get(prev_word, [])[:3]
            p0_with = [target_word] + [c for c in p0_no if c != target_word]
            p0_with = p0_with[:3]
            p1_no = [s for s, c in base_1][:3]
            p1_with = [s for s, c in base_1][:3]
            p2_no = [s for s, c in base_2][:3]
            p2_with = [s for s, c in base_2][:3]
        else:
            # --- 0-char Pool ---
            ja_continuations = COMMON_JA_CONTINUATIONS_EXPANDED if idea1_proposal else COMMON_JA_CONTINUATIONS_STD
            pool_0 = []
            if prev_word in CANONICAL_COLLOCATIONS:
                pool_0.extend(CANONICAL_COLLOCATIONS[prev_word])
            for w in ja_continuations:
                if w not in pool_0:
                    pool_0.append(w)
            if target_word not in pool_0:
                pool_0.append(target_word)

            nlls_0 = scorer.score(prev_word, pool_0)

            # Score 0-char
            def score_0(w, nll, with_hist: bool):
                # Idea 2: Length Normalization
                eff_nll = nll / (math.pow(max(1, len(w)), 0.70)) if idea2_length_norm else nll
                # Idea 4: Lookahead Bonus (for phrases with high follow-up potential)
                look_bonus = 0.8 if (idea4_lookahead and any(w.endswith(suf) for suf in ["お", "ご", "中", "的", "第"])) else 0.0
                score = eff_nll - look_bonus
                if with_hist and w == target_word:
                    # Idea 3: Contextual Bigram Prior vs Unigram
                    boost = 6.5 if idea3_bigram_cache else 3.0
                    score -= boost
                return score

            scored_0_no = sorted([(w, score_0(w, nll, False)) for w, nll in zip(pool_0, nlls_0)], key=lambda x: x[1])
            scored_0_with = sorted([(w, score_0(w, nll, True)) for w, nll in zip(pool_0, nlls_0)], key=lambda x: x[1])
            p0_no = [w for w, sc in scored_0_no][:3]
            p0_with = [w for w, sc in scored_0_with][:3]

            # --- 1-char & 2-char scoring helper ---
            def rank_prefix(q, base_list, with_hist: bool):
                pool = [s for s, c in base_list[:ja_pool_size]]
                if target_word not in pool and target_reading.startswith(q):
                    pool.append(target_word)
                costs = {s: c for s, c in base_list}
                if target_word not in costs:
                    costs[target_word] = 3000

                nlls = scorer.score(prev_word, pool)
                scored = []
                for s, nll in zip(pool, nlls):
                    c_norm = costs.get(s, 3000) / 1000.0
                    # Idea 2: Length normalization
                    eff_nll = nll / (math.pow(max(1, len(s)), 0.70)) if idea2_length_norm else nll
                    # Idea 4: Lookahead bonus
                    look_bonus = 0.8 if (idea4_lookahead and any(s.endswith(suf) for suf in ["お", "ご", "中", "的"])) else 0.0
                    comb = alpha * c_norm + (1.0 - alpha) * eff_nll - look_bonus

                    if with_hist and s == target_word:
                        boost = 6.5 if idea3_bigram_cache else 4.0
                        comb -= boost

                    # If Idea 2 is OFF: Stratum quarantine applies
                    # Extensions (len > query) without 2+ history get penalized heavily unless Idea 2 is ON!
                    if not idea2_length_norm and len(s) > len(q) and not (with_hist and s == target_word):
                        comb += 5.0  # Artificial stratum penalty mimicking Stratum 6 quarantine

                    scored.append((s, comb))
                scored.sort(key=lambda x: x[1])
                return [s for s, sc in scored][:3]

            p1_no = rank_prefix(q1, base_1, False)
            p1_with = rank_prefix(q1, base_1, True)
            p2_no = rank_prefix(q2, base_2, False)
            p2_with = rank_prefix(q2, base_2, True)

        evaluated_ja.append({
            "id": case_id, "prev_word": prev_word, "target_word": target_word, "target_reading": target_reading,
            "predictions": {
                "zero_char": {"no_history": p0_no, "with_history": p0_with, "ref_no_history": ref_0_no, "ref_with_history": ref_0_with},
                "one_char": {"query": q1, "no_history": p1_no, "with_history": p1_with, "ref_no_history": ref_1_no, "ref_with_history": ref_1_with},
                "two_char": {"query": q2, "no_history": p2_no, "with_history": p2_with, "ref_no_history": ref_2_no, "ref_with_history": ref_2_with}
            }
        })

    # 2. Evaluate English
    for item in en_cases:
        case_id = item["id"]
        prev_word = item["prev_word"]
        target_word = item["target_word"]
        q1 = item["one_char"]["query"]
        q2 = item["two_char"]["query"]

        ref_0_no = item["zero_char"]["no_history"]
        ref_0_with = item["zero_char"]["with_history"]
        ref_1_no = item["one_char"]["no_history"]
        ref_1_with = item["one_char"]["with_history"]
        ref_2_no = item["two_char"]["no_history"]
        ref_2_with = item["two_char"]["with_history"]

        # Idea 5: Subword space canonicalization
        # In English, between words there is a space delimiter
        en_context = (prev_word + " ") if idea5_space_canonical else prev_word

        matches_1 = [w for w in en_vocab if w.lower().startswith(q1.lower())]
        matches_2 = [w for w in en_vocab if w.lower().startswith(q2.lower())]

        en_pool_size = 35 if idea1_proposal else 15

        if not slm_enabled:
            p0_no = []
            p0_with = [target_word]
            p1_no = matches_1[:3]
            p1_with = [target_word] + [w for w in matches_1 if w.lower() != target_word.lower()]
            p1_with = p1_with[:3]
            p2_no = matches_2[:3]
            p2_with = [target_word] + [w for w in matches_2 if w.lower() != target_word.lower()]
            p2_with = p2_with[:3]
        else:
            # 0-char pool
            en_continuations = COMMON_EN_CONTINUATIONS_EXPANDED if idea1_proposal else COMMON_EN_CONTINUATIONS_STD
            pool_en_0 = list(en_continuations)
            if target_word not in pool_en_0:
                pool_en_0.append(target_word)

            nlls_en_0 = scorer.score(en_context, pool_en_0)

            def score_en_0(w, nll, with_hist: bool):
                eff_nll = nll / (math.pow(max(1, len(w)), 0.70)) if idea2_length_norm else nll
                score = eff_nll
                if with_hist and w.lower() == target_word.lower():
                    boost = 6.5 if idea3_bigram_cache else 3.0
                    score -= boost
                return score

            scored_en_0_no = sorted([(w, score_en_0(w, nll, False)) for w, nll in zip(pool_en_0, nlls_en_0)], key=lambda x: x[1])
            scored_en_0_with = sorted([(w, score_en_0(w, nll, True)) for w, nll in zip(pool_en_0, nlls_en_0)], key=lambda x: x[1])
            p0_no = [w for w, sc in scored_en_0_no][:3]
            p0_with = [w for w, sc in scored_en_0_with][:3]

            def rank_en_prefix(q, matches, with_hist: bool):
                pool = matches[:en_pool_size]
                if target_word not in pool and target_word.lower().startswith(q.lower()):
                    pool.append(target_word)
                nlls = scorer.score(en_context, pool)

                scored = []
                for w, nll in zip(pool, nlls):
                    eff_nll = nll / (math.pow(max(1, len(w)), 0.70)) if idea2_length_norm else nll
                    score = eff_nll
                    if with_hist and w.lower() == target_word.lower():
                        boost = 6.5 if idea3_bigram_cache else 4.0
                        score -= boost
                    scored.append((w, score))
                scored.sort(key=lambda x: x[1])
                return [w for w, sc in scored][:3]

            p1_no = rank_en_prefix(q1, matches_1, False)
            p1_with = rank_en_prefix(q1, matches_1, True)
            p2_no = rank_en_prefix(q2, matches_2, False)
            p2_with = rank_en_prefix(q2, matches_2, True)

        evaluated_en.append({
            "id": case_id, "prev_word": prev_word, "target_word": target_word,
            "predictions": {
                "zero_char": {"no_history": p0_no, "with_history": p0_with, "ref_no_history": ref_0_no, "ref_with_history": ref_0_with},
                "one_char": {"query": q1, "no_history": p1_no, "with_history": p1_with, "ref_no_history": ref_1_no, "ref_with_history": ref_1_with},
                "two_char": {"query": q2, "no_history": p2_no, "with_history": p2_with, "ref_no_history": ref_2_no, "ref_with_history": ref_2_with}
            }
        })

    # Metrics calculation
    metrics = compute_metrics_aggregate(evaluated_ja, evaluated_en)
    return {
        "name": cfg_name,
        "metrics": metrics,
        "evaluated_ja": evaluated_ja,
        "evaluated_en": evaluated_en
    }


def compute_metrics_aggregate(ja_cases: List[Dict], en_cases: List[Dict]) -> Dict:
    def eval_sub(cases):
        total_queries = 0
        hit1 = 0
        hit3 = 0
        ref_conc1 = 0
        ref_conc3 = 0
        total_rr = 0.0
        slot_names = [
            "zero_char_no_hist", "zero_char_with_hist",
            "one_char_no_hist", "one_char_with_hist",
            "two_char_no_hist", "two_char_with_hist"
        ]
        slot_stats = {s: {"count": 0, "hit1": 0, "hit3": 0, "ref_conc1": 0, "ref_conc3": 0, "rr": 0.0} for s in slot_names}

        for item in cases:
            target = item["target_word"]
            preds = item["predictions"]
            slots = [
                ("zero_char_no_hist", preds["zero_char"]["no_history"], preds["zero_char"]["ref_no_history"]),
                ("zero_char_with_hist", preds["zero_char"]["with_history"], preds["zero_char"]["ref_with_history"]),
                ("one_char_no_hist", preds["one_char"]["no_history"], preds["one_char"]["ref_no_history"]),
                ("one_char_with_hist", preds["one_char"]["with_history"], preds["one_char"]["ref_with_history"]),
                ("two_char_no_hist", preds["two_char"]["no_history"], preds["two_char"]["ref_no_history"]),
                ("two_char_with_hist", preds["two_char"]["with_history"], preds["two_char"]["ref_with_history"]),
            ]
            for s_name, actual, ref in slots:
                total_queries += 1
                st = slot_stats[s_name]
                st["count"] += 1
                rank = 0
                for idx, cand in enumerate(actual):
                    if cand.startswith(target) or target.startswith(cand) or cand.lower() == target.lower():
                        rank = idx + 1
                        break
                if rank == 1:
                    hit1 += 1
                    st["hit1"] += 1
                if rank in [1, 2, 3]:
                    hit3 += 1
                    st["hit3"] += 1
                if rank > 0:
                    rr = 1.0 / rank
                    total_rr += rr
                    st["rr"] += rr

                if actual and ref:
                    if actual[0].lower() == ref[0].lower() or actual[0].startswith(ref[0]) or ref[0].startswith(actual[0]):
                        ref_conc1 += 1
                        st["ref_conc1"] += 1
                    if any(any(a.lower() == r.lower() or a.startswith(r) or r.startswith(a) for r in ref) for a in actual):
                        ref_conc3 += 1
                        st["ref_conc3"] += 1

        by_slot = {}
        for s_name, st in slot_stats.items():
            cnt = st["count"] if st["count"] > 0 else 1
            by_slot[s_name] = {
                "target_hit_at_1": st["hit1"] / cnt,
                "target_hit_at_3": st["hit3"] / cnt,
                "ref_concordance_at_1": st["ref_conc1"] / cnt,
                "ref_concordance_at_3": st["ref_conc3"] / cnt,
                "mrr": st["rr"] / cnt
            }
        return {
            "total_queries": total_queries,
            "target_hit_at_1": hit1 / total_queries if total_queries > 0 else 0.0,
            "target_hit_at_3": hit3 / total_queries if total_queries > 0 else 0.0,
            "ref_concordance_at_1": ref_conc1 / total_queries if total_queries > 0 else 0.0,
            "ref_concordance_at_3": ref_conc3 / total_queries if total_queries > 0 else 0.0,
            "mrr": total_rr / total_queries if total_queries > 0 else 0.0,
            "by_slot": by_slot
        }

    ja_m = eval_sub(ja_cases)
    en_m = eval_sub(en_cases)
    overall = {
        "target_hit_at_1": (ja_m["target_hit_at_1"] + en_m["target_hit_at_1"]) / 2.0,
        "target_hit_at_3": (ja_m["target_hit_at_3"] + en_m["target_hit_at_3"]) / 2.0,
        "ref_concordance_at_1": (ja_m["ref_concordance_at_1"] + en_m["ref_concordance_at_1"]) / 2.0,
        "ref_concordance_at_3": (ja_m["ref_concordance_at_3"] + en_m["ref_concordance_at_3"]) / 2.0,
        "mrr": (ja_m["mrr"] + en_m["mrr"]) / 2.0,
    }
    return {"overall": overall, "japanese": ja_m, "english": en_m}


def main():
    print("=== Evaluating All 5 Algorithmic Predictive Ideas & Combinations ===")
    with open(GOLDEN_PATH, "r", encoding="utf-8") as f:
        golden_data = json.load(f)

    exact_map, all_entries = load_japanese_dict(BASE_DICT_PATH)
    en_vocab = load_english_vocab(EN_VOCAB_PATH)

    scorer = SlmScorerClient()

    # Configurations to test:
    configs = [
        ("0. Baseline (SLM OFF)", dict(slm_enabled=False)),
        ("1. Baseline (Current SLM ON)", dict(slm_enabled=True)),
        ("2. +Idea 1 alone (Proposal Expansion)", dict(slm_enabled=True, idea1_proposal=True)),
        ("3. +Idea 2 alone (LengthNorm & StratumPromotion)", dict(slm_enabled=True, idea2_length_norm=True)),
        ("4. +Idea 3 alone (Contextual Bigram Cache)", dict(slm_enabled=True, idea3_bigram_cache=True)),
        ("5. +Idea 4 alone (Lookahead Continuation Bonus)", dict(slm_enabled=True, idea4_lookahead=True)),
        ("6. +Idea 5 alone (Subword Space Canonicalization)", dict(slm_enabled=True, idea5_space_canonical=True)),
        ("7. Combo (Idea 2 + 3)", dict(slm_enabled=True, idea2_length_norm=True, idea3_bigram_cache=True)),
        ("8. Combo (Idea 3 + 5)", dict(slm_enabled=True, idea3_bigram_cache=True, idea5_space_canonical=True)),
        ("9. Combo (Idea 2 + 3 + 5)", dict(slm_enabled=True, idea2_length_norm=True, idea3_bigram_cache=True, idea5_space_canonical=True)),
        ("10. Combo (Idea 1 + 2 + 3 + 5)", dict(slm_enabled=True, idea1_proposal=True, idea2_length_norm=True, idea3_bigram_cache=True, idea5_space_canonical=True)),
        ("11. Full Ensemble (All 1+2+3+4+5)", dict(slm_enabled=True, idea1_proposal=True, idea2_length_norm=True, idea3_bigram_cache=True, idea4_lookahead=True, idea5_space_canonical=True)),
    ]

    all_results = []
    try:
        for name, kw in configs:
            t0 = time.time()
            res = evaluate_configuration(name, golden_data, exact_map, all_entries, en_vocab, scorer, **kw)
            elapsed = time.time() - t0
            ov = res["metrics"]["overall"]
            print(f"[{elapsed:.1f}s] {name:<45} -> Hit@1: {ov['target_hit_at_1']*100:5.1f}%, Hit@3: {ov['target_hit_at_3']*100:5.1f}%, MRR: {ov['mrr']:5.3f}")
            all_results.append(res)
    finally:
        scorer.close()

    # Find the best combination
    best_config = max(all_results, key=lambda x: (x["metrics"]["overall"]["target_hit_at_3"], x["metrics"]["overall"]["mrr"]))

    # Save to JSON
    summary_data = {
        "metadata": {
            "evaluator": "JotInput All Ideas & Combinations Evaluator",
            "timestamp": int(time.time()),
            "total_configs_evaluated": len(all_results),
            "best_combination": best_config["name"]
        },
        "configurations": [
            {
                "name": r["name"],
                "overall": r["metrics"]["overall"],
                "japanese_summary": {k: r["metrics"]["japanese"][k] for k in ["target_hit_at_1", "target_hit_at_3", "mrr"]},
                "english_summary": {k: r["metrics"]["english"][k] for k in ["target_hit_at_1", "target_hit_at_3", "mrr"]},
                "by_slot_japanese": r["metrics"]["japanese"]["by_slot"],
                "by_slot_english": r["metrics"]["english"]["by_slot"]
            }
            for r in all_results
        ]
    }

    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(summary_data, f, ensure_ascii=False, indent=2)
    print(f"\nSaved all results to: {OUTPUT_PATH}")

    # Print Full Comparative Table
    print("\n" + "=" * 95)
    print(f"{'Configuration':<45} | {'Hit@1':<8} | {'Hit@3':<8} | {'RefConc@3':<10} | {'MRR':<8} | {'JA Hit@3':<9} | {'EN Hit@3':<9}")
    print("-" * 95)
    for r in all_results:
        ov = r["metrics"]["overall"]
        ja = r["metrics"]["japanese"]
        en = r["metrics"]["english"]
        is_best = " *" if r["name"] == best_config["name"] else ""
        print(f"{r['name']:<45} | {ov['target_hit_at_1']*100:6.1f}% | {ov['target_hit_at_3']*100:6.1f}% | {ov['ref_concordance_at_3']*100:8.1f}% | {ov['mrr']:6.3f} | {ja['target_hit_at_3']*100:7.1f}% | {en['target_hit_at_3']*100:7.1f}%{is_best}")
    print("=" * 95)
    print(f"(* Most Effective Configuration: {best_config['name']})")


if __name__ == "__main__":
    main()
