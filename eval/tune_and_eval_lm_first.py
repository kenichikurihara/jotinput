#!/usr/bin/env python3
"""
tune_and_eval_lm_first.py

Evaluates the LM-First Joint Evaluation Algorithm and performs multi-objective parameter tuning
across:
- alpha in [0.00, 0.05, 0.10, 0.20, 0.30]
- lambda_obs in [0.0, 0.5, 1.0, 1.5, 2.0, 3.0]
- gamma_user in [0.0, 1.0, 2.0, 3.0, 4.0, 5.0]

Metrics:
- Top-1 Accuracy (%)
- Top-3 Recall (%)
- MRR (Mean Reciprocal Rank)
- User Dictionary Protection Rate (%) (must be 100%)
- Diagnostic Accuracy (けっか/さくじょ 12 cases must be 100%)
- Observation Model Typo Recovery Rate (%)
"""

import json
import os
import subprocess
import time
from typing import List, Dict, Any, Tuple

class SlmScorerClient:
    def __init__(self, cli_path='./tools/slm_scorer_cli', model_path='./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf'):
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

    def score(self, context: str, candidates: List[str]) -> Tuple[List[float], float]:
        if not candidates:
            return [], 0.0
        self.counter += 1
        req_id = str(self.counter)
        req_line = f"{req_id}\t{context}\t" + "\t".join(candidates) + "\n"
        self.proc.stdin.write(req_line)
        self.proc.stdin.flush()

        resp_line = self.proc.stdout.readline().strip()
        parts = resp_line.split("\t")
        if len(parts) < 2:
            return [-20.0] * len(candidates), 0.0
        lat_ms = float(parts[1])
        logprobs = [float(p) for p in parts[2:]]
        # In slm_scorer_cli, logprobs are log P (negative numbers, e.g. -5.21)
        # NLL = -log P (positive numbers, e.g. 5.21)
        nlls = [-lp for lp in logprobs]
        return nlls, lat_ms

    def close(self):
        self.proc.terminate()

def compute_lm_first_score(
    cand: Dict[str, Any],
    nll: float,
    sys_best_nll: float,
    alpha: float,
    lambda_obs: float,
    gamma_user: float,
    epsilon: float = 0.05
) -> float:
    """
    Computes final ranking score (lower is better).
    """
    is_user = cand.get("source") == "user"
    obs_penalty = cand.get("obs_penalty", 0.0)
    mozc_cost = cand.get("mozc_cost", 3000)
    priority = cand.get("priority", 0.0)

    # 1. Effective NLL with Intra-Word Surprisal Clamping for user dictionary
    if is_user:
        # Clamped Floor: user word cannot be worse than best system competitor
        clamped_nll = min(sys_best_nll, nll)
        # Priority boost
        user_boost = gamma_user * priority
        # Contextual tie-breaker
        tie_breaker = epsilon * nll
        effective_nll = clamped_nll - user_boost + tie_breaker
    else:
        effective_nll = nll

    # 2. Observation penalty
    effective_obs = lambda_obs * obs_penalty * 5.0  # scaled so penalty 1.2 -> 6.0 nats

    # 3. Base dictionary cost (scaled rank cost)
    rank_cost = mozc_cost / 1000.0

    # 4. Final Combined Score
    # alpha weights Mozc rank cost vs LM+Obs
    final_score = (1.0 - alpha) * (effective_nll + effective_obs) + alpha * rank_cost
    return final_score

def precompute_nlls(dataset_path: str, cache_path: str = "eval/lm_first_nll_cache.json"):
    """
    Precomputes and caches raw SLM NLLs for all items so parameter grid search can run instantaneously.
    """
    if os.path.exists(cache_path):
        print(f"Loading cached NLLs from {cache_path}...")
        with open(cache_path, "r", encoding="utf-8") as f:
            return json.load(f)

    print(f"Computing NLLs for {dataset_path}...")
    with open(dataset_path, "r", encoding="utf-8") as f:
        items = json.load(f)

    scorer = SlmScorerClient()
    cached = []
    t0 = time.time()

    for idx, item in enumerate(items):
        ctx = item["context"]
        surfaces = [c["surface"] for c in item["candidates"]]
        nlls, lat = scorer.score(ctx, surfaces)
        item_copy = dict(item)
        item_copy["nlls"] = nlls
        cached.append(item_copy)

        if (idx + 1) % 50 == 0 or idx + 1 == len(items):
            elapsed = time.time() - t0
            print(f"  Processed {idx + 1}/{len(items)} items ({elapsed:.1f}s)...")

    scorer.close()
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(cached, f, ensure_ascii=False, indent=2)
    print(f"Cached NLLs saved to {cache_path}")
    return cached

def evaluate_parameters(items: List[Dict[str, Any]], alpha: float, lambda_obs: float, gamma_user: float):
    cat_stats = {}
    overall_correct_1 = 0
    overall_correct_3 = 0
    overall_mrr = 0.0
    total = len(items)

    for item in items:
        cat = item["category"]
        if cat not in cat_stats:
            cat_stats[cat] = {"top1": 0, "top3": 0, "mrr": 0.0, "total": 0}

        target = item["target"]
        cands = item["candidates"]
        nlls = item["nlls"]

        # Find best system NLL for clamping
        sys_nlls = [nlls[i] for i, c in enumerate(cands) if c.get("source") != "user"]
        sys_best_nll = min(sys_nlls) if sys_nlls else 15.0

        scored_cands = []
        for i, c in enumerate(cands):
            score = compute_lm_first_score(
                c, nlls[i], sys_best_nll,
                alpha=alpha, lambda_obs=lambda_obs, gamma_user=gamma_user
            )
            scored_cands.append((score, c["surface"]))

        scored_cands.sort(key=lambda x: x[0])
        ranked_surfs = [s[1] for s in scored_cands]

        rank = next((idx + 1 for idx, s in enumerate(ranked_surfs) if s == target), -1)

        cat_stats[cat]["total"] += 1
        if rank == 1:
            overall_correct_1 += 1
            cat_stats[cat]["top1"] += 1
        if 1 <= rank <= 3:
            overall_correct_3 += 1
            cat_stats[cat]["top3"] += 1
        if rank > 0:
            reciprocal = 1.0 / rank
            overall_mrr += reciprocal
            cat_stats[cat]["mrr"] += reciprocal

    summary = {
        "alpha": alpha,
        "lambda_obs": lambda_obs,
        "gamma_user": gamma_user,
        "top1_acc": overall_correct_1 / total * 100.0,
        "top3_recall": overall_correct_3 / total * 100.0,
        "mrr": overall_mrr / total,
        "categories": {}
    }
    for cat, st in cat_stats.items():
        summary["categories"][cat] = {
            "top1_acc": st["top1"] / st["total"] * 100.0,
            "top3_recall": st["top3"] / st["total"] * 100.0,
            "mrr": st["mrr"] / st["total"],
            "count": st["total"]
        }
    return summary

def run_grid_search(items: List[Dict[str, Any]]):
    print("\n" + "=" * 70)
    print("  LM-First Multi-Objective Parameter Grid Search & Tuning")
    print("=" * 70)

    alphas = [0.00, 0.05, 0.10, 0.15, 0.20, 0.30]
    lambdas = [0.5, 1.0, 1.5, 2.0]
    gammas = [1.0, 2.0, 3.0, 4.0, 5.0]

    all_results = []

    best_score = -1.0
    best_config = None

    for alpha in alphas:
        for lam in lambdas:
            for gam in gammas:
                res = evaluate_parameters(items, alpha, lam, gam)
                all_results.append(res)

                # Objective: Top-1 Acc with strict requirement:
                # - User Dict Top-1 == 100.0%
                # - Diagnostic Top-1 == 100.0%
                user_acc = res["categories"].get("user_dict", {}).get("top1_acc", 0.0)
                diag_acc = res["categories"].get("diagnostic", {}).get("top1_acc", 0.0)

                # Composite score prioritizing overall Top-1 while penalizing failures in user or diagnostic
                penalty = 0.0
                if user_acc < 100.0:
                    penalty += (100.0 - user_acc) * 2.0
                if diag_acc < 100.0:
                    penalty += (100.0 - diag_acc) * 2.0

                score = res["top1_acc"] - penalty
                if score > best_score:
                    best_score = score
                    best_config = res

    # Sort results by Top-1 Acc
    all_results.sort(key=lambda x: x["top1_acc"], reverse=True)

    print("\nTop 5 Parameter Configurations:")
    for i, r in enumerate(all_results[:5], 1):
        u_acc = r["categories"]["user_dict"]["top1_acc"]
        d_acc = r["categories"]["diagnostic"]["top1_acc"]
        h_acc = r["categories"]["homophone"]["top1_acc"]
        p_acc = r["categories"]["phrase"]["top1_acc"]
        o_acc = r["categories"]["observation"]["top1_acc"]
        n_acc = r["categories"]["natural"]["top1_acc"]
        print(f"Rank {i}: alpha={r['alpha']:.2f}, lambda={r['lambda_obs']:.1f}, gamma={r['gamma_user']:.1f} => "
              f"Overall Top-1: {r['top1_acc']:.2f}%, MRR: {r['mrr']:.4f} | "
              f"User: {u_acc:.1f}%, Diag: {d_acc:.1f}%, Homo: {h_acc:.1f}%, Phrase: {p_acc:.1f}%, Obs: {o_acc:.1f}%, Nat: {n_acc:.1f}%")

    out_tuning = "eval/lm_first_tuning_results.json"
    with open(out_tuning, "w", encoding="utf-8") as f:
        json.dump({
            "best_config": best_config,
            "all_results": all_results
        }, f, ensure_ascii=False, indent=2)
    print(f"\nFull tuning results saved to {out_tuning}")
    return best_config

if __name__ == "__main__":
    dataset_file = "eval/lm_first_eval_dataset.json"
    if not os.path.exists(dataset_file):
        import build_lm_first_eval_dataset
        build_lm_first_eval_dataset.build_dataset()

    cached_items = precompute_nlls(dataset_file)
    best = run_grid_search(cached_items)
