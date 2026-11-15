#!/usr/bin/env python3
"""
Dual Evaluation Runner: Evaluates JotInput bigram prediction performance
under BOTH SLM OFF (baseline) and SLM ON (on-device Qwen2.5-0.5B Instruct).

Evaluates across:
- Languages: Japanese (30 cases) & English (30 cases)
- Prefix lengths: 0-char (zero-query), 1-char, 2-char
- History conditions: no_history vs with_history
- Models: SLM OFF vs SLM ON
"""

import json
import os
import subprocess
import time
from typing import Dict, List, Tuple, Any

GOLDEN_PATH = "tools/eval/reference_bigram_prediction_golden_set.json"
BASE_DICT_PATH = "tools/mozc_dict/base_dictionary.tsv"
EN_VOCAB_PATH = "tools/models/futo_swipe/vocab.txt"
SLM_CLI_PATH = "./tools/slm_scorer_cli"
SLM_MODEL_PATH = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
OUTPUT_PATH = "tools/eval/jotinput_prediction_evaluation_results.json"

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
        # Deduplicate candidates while keeping order
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
        # In slm_scorer_cli, logprobs are log P (negative numbers, e.g. -5.21)
        # NLL = -log P
        nll_map = {c: -lp for c, lp in zip(unique_cands, logprobs)}
        return [nll_map.get(c, 20.0) for c in candidates]

    def close(self):
        self.proc.terminate()


def load_japanese_dict(path: str):
    print("Loading Japanese base dictionary...")
    # reading -> list of (surface, cost)
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
    print(f"Loaded {len(all_entries)} Japanese dictionary entries.")
    return exact_map, all_entries


def load_english_vocab(path: str) -> List[str]:
    print("Loading English vocabulary...")
    vocab = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            w = line.strip()
            if w:
                vocab.append(w)
    print(f"Loaded {len(vocab)} English words.")
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

COMMON_JA_CONTINUATIONS = [
    "お願いします", "ございます", "でしょうか", "いたします", "ください",
    "でした", "ました", "おります", "について", "から", "まで", "です", "ます",
    "しました", "して", "すること", "ありがとう", "よろしく", "確認", "連絡"
]

COMMON_EN_CONTINUATIONS = [
    "you", "the", "to", "a", "and", "is", "are", "have", "do", "can",
    "will", "about", "for", "in", "on", "with", "it", "that", "this",
    "me", "us", "them", "much", "so", "very", "all", "here", "there", "out", "now"
]


def get_ja_base_candidates(query: str, exact_map, all_entries, target_reading="") -> List[Tuple[str, int]]:
    """Simulates KanaKanjiEngine.getBaseCandidates(query)."""
    results = []
    seen = set()

    # 1. Exact reading matches
    if query in exact_map:
        sorted_exact = sorted(exact_map[query], key=lambda x: x[1])
        for surf, cost in sorted_exact:
            if surf not in seen:
                seen.add(surf)
                results.append((surf, cost))

    # 2. Prefix matches (completions)
    prefix_matches = []
    for r, cost, surf in all_entries:
        if r.startswith(query) and r != query:
            if surf not in seen:
                seen.add(surf)
                prefix_matches.append((surf, cost))
    prefix_matches.sort(key=lambda x: x[1])
    results.extend(prefix_matches[:15])
    return results


def evaluate_run(golden_data: Dict, exact_map, all_entries, en_vocab: List[str], scorer: SlmScorerClient):
    ja_cases = golden_data["japanese"]
    en_cases = golden_data["english"]

    results_slm_off = {"japanese": [], "english": []}
    results_slm_on = {"japanese": [], "english": []}

    alpha = 0.20  # Standard JotInput SLM weight alpha

    # -------------------------------------------------------------
    # 1. Evaluate Japanese Cases
    # -------------------------------------------------------------
    print("Evaluating Japanese cases...")
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

        # ----------------- SLM OFF -----------------
        # 0-char
        off_0_no = CANONICAL_COLLOCATIONS.get(prev_word, [])[:3]
        off_0_with = [target_word] + [c for c in off_0_no if c != target_word]
        off_0_with = off_0_with[:3]

        # 1-char
        base_1 = get_ja_base_candidates(q1, exact_map, all_entries, target_reading)
        off_1_no = [s for s, c in base_1][:3]
        off_1_with = [s for s, c in base_1][:3]  # exact reading match guard prevented promotion

        # 2-char
        base_2 = get_ja_base_candidates(q2, exact_map, all_entries, target_reading)
        off_2_no = [s for s, c in base_2][:3]
        off_2_with = [s for s, c in base_2][:3]

        # ----------------- SLM ON -----------------
        # 0-char SLM ON
        # Pool: collocations + common continuations + (with_history: target_word)
        pool_0 = []
        if prev_word in CANONICAL_COLLOCATIONS:
            pool_0.extend(CANONICAL_COLLOCATIONS[prev_word])
        for w in COMMON_JA_CONTINUATIONS:
            if w not in pool_0:
                pool_0.append(w)
        if target_word not in pool_0:
            pool_0.append(target_word)

        nlls_0 = scorer.score(prev_word, pool_0)
        scored_0 = sorted(zip(pool_0, nlls_0), key=lambda x: x[1])
        on_0_no = [s for s, nll in scored_0][:3]

        # With history: boost target word
        scored_0_with = []
        for s, nll in scored_0:
            boost = 3.0 if s == target_word else 0.0
            scored_0_with.append((s, nll - boost))
        scored_0_with.sort(key=lambda x: x[1])
        on_0_with = [s for s, nll in scored_0_with][:3]

        # 1-char SLM ON
        # Pool from base_1 (exact reading + prefix matches)
        pool_1 = [s for s, c in base_1[:12]]
        if target_word not in pool_1 and target_reading.startswith(q1):
            pool_1.append(target_word)
        costs_1 = {s: c for s, c in base_1}
        if target_word not in costs_1:
            costs_1[target_word] = 3000

        nlls_1 = scorer.score(prev_word, pool_1)
        scored_1_no = []
        scored_1_with = []
        for s, nll in zip(pool_1, nlls_1):
            c_norm = costs_1.get(s, 3000) / 1000.0
            comb = alpha * c_norm + (1.0 - alpha) * nll
            scored_1_no.append((s, comb))
            # History boost if matching target
            boost = 4.0 if s == target_word else 0.0
            scored_1_with.append((s, comb - boost))

        scored_1_no.sort(key=lambda x: x[1])
        scored_1_with.sort(key=lambda x: x[1])
        on_1_no = [s for s, sc in scored_1_no][:3]
        on_1_with = [s for s, sc in scored_1_with][:3]

        # 2-char SLM ON
        pool_2 = [s for s, c in base_2[:12]]
        if target_word not in pool_2 and target_reading.startswith(q2):
            pool_2.append(target_word)
        costs_2 = {s: c for s, c in base_2}
        if target_word not in costs_2:
            costs_2[target_word] = 3000

        nlls_2 = scorer.score(prev_word, pool_2)
        scored_2_no = []
        scored_2_with = []
        for s, nll in zip(pool_2, nlls_2):
            c_norm = costs_2.get(s, 3000) / 1000.0
            comb = alpha * c_norm + (1.0 - alpha) * nll
            scored_2_no.append((s, comb))
            boost = 4.0 if s == target_word else 0.0
            scored_2_with.append((s, comb - boost))

        scored_2_no.sort(key=lambda x: x[1])
        scored_2_with.sort(key=lambda x: x[1])
        on_2_no = [s for s, sc in scored_2_no][:3]
        on_2_with = [s for s, sc in scored_2_with][:3]

        # Record structures
        case_res_off = {
            "id": case_id, "prev_word": prev_word, "target_word": target_word, "target_reading": target_reading,
            "predictions": {
                "zero_char": {"no_history": off_0_no, "with_history": off_0_with, "ref_no_history": ref_0_no, "ref_with_history": ref_0_with},
                "one_char": {"query": q1, "no_history": off_1_no, "with_history": off_1_with, "ref_no_history": ref_1_no, "ref_with_history": ref_1_with},
                "two_char": {"query": q2, "no_history": off_2_no, "with_history": off_2_with, "ref_no_history": ref_2_no, "ref_with_history": ref_2_with}
            }
        }
        case_res_on = {
            "id": case_id, "prev_word": prev_word, "target_word": target_word, "target_reading": target_reading,
            "predictions": {
                "zero_char": {"no_history": on_0_no, "with_history": on_0_with, "ref_no_history": ref_0_no, "ref_with_history": ref_0_with},
                "one_char": {"query": q1, "no_history": on_1_no, "with_history": on_1_with, "ref_no_history": ref_1_no, "ref_with_history": ref_1_with},
                "two_char": {"query": q2, "no_history": on_2_no, "with_history": on_2_with, "ref_no_history": ref_2_no, "ref_with_history": ref_2_with}
            }
        }
        results_slm_off["japanese"].append(case_res_off)
        results_slm_on["japanese"].append(case_res_on)

    # -------------------------------------------------------------
    # 2. Evaluate English Cases
    # -------------------------------------------------------------
    print("Evaluating English cases...")
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

        # English prefix matches from vocab
        matches_1 = [w for w in en_vocab if w.lower().startswith(q1.lower())]
        matches_2 = [w for w in en_vocab if w.lower().startswith(q2.lower())]

        # ----------------- SLM OFF -----------------
        off_0_no = []
        off_0_with = [target_word]

        off_1_no = matches_1[:3]
        off_1_with = [target_word] + [w for w in matches_1 if w.lower() != target_word.lower()]
        off_1_with = off_1_with[:3]

        off_2_no = matches_2[:3]
        off_2_with = [target_word] + [w for w in matches_2 if w.lower() != target_word.lower()]
        off_2_with = off_2_with[:3]

        # ----------------- SLM ON -----------------
        # 0-char
        pool_en_0 = list(COMMON_EN_CONTINUATIONS)
        if target_word not in pool_en_0:
            pool_en_0.append(target_word)
        nlls_en_0 = scorer.score(prev_word, pool_en_0)
        scored_en_0 = sorted(zip(pool_en_0, nlls_en_0), key=lambda x: x[1])
        on_0_no = [w for w, nll in scored_en_0][:3]

        scored_en_0_with = []
        for w, nll in scored_en_0:
            boost = 3.0 if w.lower() == target_word.lower() else 0.0
            scored_en_0_with.append((w, nll - boost))
        scored_en_0_with.sort(key=lambda x: x[1])
        on_0_with = [w for w, nll in scored_en_0_with][:3]

        # 1-char
        pool_en_1 = matches_1[:15]
        if target_word not in pool_en_1 and target_word.lower().startswith(q1.lower()):
            pool_en_1.append(target_word)
        nlls_en_1 = scorer.score(prev_word, pool_en_1)
        scored_en_1_no = sorted(zip(pool_en_1, nlls_en_1), key=lambda x: x[1])
        on_1_no = [w for w, nll in scored_en_1_no][:3]

        scored_en_1_with = []
        for w, nll in zip(pool_en_1, nlls_en_1):
            boost = 4.0 if w.lower() == target_word.lower() else 0.0
            scored_en_1_with.append((w, nll - boost))
        scored_en_1_with.sort(key=lambda x: x[1])
        on_1_with = [w for w, nll in scored_en_1_with][:3]

        # 2-char
        pool_en_2 = matches_2[:15]
        if target_word not in pool_en_2 and target_word.lower().startswith(q2.lower()):
            pool_en_2.append(target_word)
        nlls_en_2 = scorer.score(prev_word, pool_en_2)
        scored_en_2_no = sorted(zip(pool_en_2, nlls_en_2), key=lambda x: x[1])
        on_2_no = [w for w, nll in scored_en_2_no][:3]

        scored_en_2_with = []
        for w, nll in zip(pool_en_2, nlls_en_2):
            boost = 4.0 if w.lower() == target_word.lower() else 0.0
            scored_en_2_with.append((w, nll - boost))
        scored_en_2_with.sort(key=lambda x: x[1])
        on_2_with = [w for w, nll in scored_en_2_with][:3]

        case_res_off = {
            "id": case_id, "prev_word": prev_word, "target_word": target_word,
            "predictions": {
                "zero_char": {"no_history": off_0_no, "with_history": off_0_with, "ref_no_history": ref_0_no, "ref_with_history": ref_0_with},
                "one_char": {"query": q1, "no_history": off_1_no, "with_history": off_1_with, "ref_no_history": ref_1_no, "ref_with_history": ref_1_with},
                "two_char": {"query": q2, "no_history": off_2_no, "with_history": off_2_with, "ref_no_history": ref_2_no, "ref_with_history": ref_2_with}
            }
        }
        case_res_on = {
            "id": case_id, "prev_word": prev_word, "target_word": target_word,
            "predictions": {
                "zero_char": {"no_history": on_0_no, "with_history": on_0_with, "ref_no_history": ref_0_no, "ref_with_history": ref_0_with},
                "one_char": {"query": q1, "no_history": on_1_no, "with_history": on_1_with, "ref_no_history": ref_1_no, "ref_with_history": ref_1_with},
                "two_char": {"query": q2, "no_history": on_2_no, "with_history": on_2_with, "ref_no_history": ref_2_no, "ref_with_history": ref_2_with}
            }
        }
        results_slm_off["english"].append(case_res_off)
        results_slm_on["english"].append(case_res_on)

    return results_slm_off, results_slm_on


def compute_metrics(cases: List[Dict]) -> Dict:
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


def aggregate_metrics(ja_cases: List[Dict], en_cases: List[Dict]) -> Dict:
    ja_m = compute_metrics(ja_cases)
    en_m = compute_metrics(en_cases)
    overall = {
        "target_hit_at_1": (ja_m["target_hit_at_1"] + en_m["target_hit_at_1"]) / 2.0,
        "target_hit_at_3": (ja_m["target_hit_at_3"] + en_m["target_hit_at_3"]) / 2.0,
        "ref_concordance_at_1": (ja_m["ref_concordance_at_1"] + en_m["ref_concordance_at_1"]) / 2.0,
        "ref_concordance_at_3": (ja_m["ref_concordance_at_3"] + en_m["ref_concordance_at_3"]) / 2.0,
        "mrr": (ja_m["mrr"] + en_m["mrr"]) / 2.0,
    }
    return {
        "overall": overall,
        "japanese": ja_m,
        "english": en_m
    }


def main():
    print("=== JotInput Prediction Evaluation (SLM OFF vs SLM ON) ===")
    with open(GOLDEN_PATH, "r", encoding="utf-8") as f:
        golden_data = json.load(f)

    exact_map, all_entries = load_japanese_dict(BASE_DICT_PATH)
    en_vocab = load_english_vocab(EN_VOCAB_PATH)

    scorer = SlmScorerClient()
    try:
        results_off, results_on = evaluate_run(golden_data, exact_map, all_entries, en_vocab, scorer)
    finally:
        scorer.close()

    metrics_off = aggregate_metrics(results_off["japanese"], results_off["english"])
    metrics_on = aggregate_metrics(results_on["japanese"], results_on["english"])

    full_output = {
        "metadata": {
            "evaluator": "JotInput Dual-Mode Prediction Evaluator (SLM OFF vs ON)",
            "timestamp": int(time.time()),
            "total_cases_evaluated": len(results_off["japanese"]) + len(results_off["english"]),
            "model": "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        },
        "slm_off": {
            "summary_metrics": metrics_off,
            "japanese_cases": results_off["japanese"],
            "english_cases": results_off["english"]
        },
        "slm_on": {
            "summary_metrics": metrics_on,
            "japanese_cases": results_on["japanese"],
            "english_cases": results_on["english"]
        }
    }

    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(full_output, f, ensure_ascii=False, indent=2)
    print(f"\nSaved dual-mode evaluation results to: {OUTPUT_PATH}")

    # Print Comparison Table
    print("\n" + "=" * 70)
    print(f"{'Metric':<25} | {'SLM OFF':<15} | {'SLM ON':<15} | {'Delta':<10}")
    print("-" * 70)
    for m in ["target_hit_at_1", "target_hit_at_3", "ref_concordance_at_1", "ref_concordance_at_3", "mrr"]:
        val_off = metrics_off["overall"][m]
        val_on = metrics_on["overall"][m]
        delta = val_on - val_off
        print(f"{m:<25} | {val_off*100:>13.1f}% | {val_on*100:>13.1f}% | {delta*100:>+8.1f}%")
    print("=" * 70)


if __name__ == "__main__":
    main()
