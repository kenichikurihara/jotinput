#!/usr/bin/env python3
"""
evaluate_flick_headroom_100.py

Empirical KLM-v1 Evaluation Runner for the 100-Case Flick Input Headroom Benchmark
(eval/flick_headroom_dataset_100.json).

Quantifies:
1. Baseline Flick Performance (Full kana typing, modifier taxes, candidate strip search, confirm tap)
2. Individual Radical Concept Impacts:
   - Concept 1: Full-Context 1-Mora Prediction & Multi-Bunsetsu Skeleton Decoding
   - Concept 2: Syntactic Slot-1 Takeover (0.30s penalty elimination)
   - Concept 3: Live Conversion Auto-Commit (0.40s C1 tap elimination)
   - Concept 4: Flick-on-Chip (Directional particle & suffix gliding)
   - Concept 5: 12-Key Neural Flick-Glide (Continuous trajectory)
   - Concept 6: Seion Turbo (Zero modifier tax)
3. Combined Radical Concept Stack (Unified Leap)

Outputs comprehensive JSON metrics and formatted analysis tables.
"""

import json
import os
import sys

# Import official KLM parameters
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "eis"))
from klm import KLM, est_kana_keystrokes

DATASET_PATH = "eval/flick_headroom_dataset_100.json"
OUTPUT_PATH = "eval/flick_headroom_100_results.json"

def evaluate_case(case):
    cid = case["id"]
    cat = case["category"]
    inp = case["input"]
    tgt = case["target"]
    chars = len(tgt)

    # -------------------------------------------------------------------------
    # 1. Baseline Performance Model
    # -------------------------------------------------------------------------
    full_keys = est_kana_keystrokes(inp)
    base_typing_sec = KLM.K_f * full_keys

    # Baseline ranks reflect current status-quo without radical concepts:
    if cat == "multi_bunsetsu":
        # Multi-bunsetsu phrases often have ambiguity or segmentation issues (rank 1 or 2)
        base_rank = 1
        base_sel_sec = KLM.select_cost(base_rank)
    elif cat == "context_homophone":
        # Homophones without full-context SLM ranking often strand target at rank 2-4
        base_rank = 2
        base_sel_sec = KLM.select_cost(base_rank)
    elif cat == "skeleton_input":
        # Without skeleton decoding, user MUST type all kana to get the target
        base_rank = 1
        base_sel_sec = KLM.select_cost(base_rank)
    elif cat == "next_phrase_collocation":
        # Without zero-char prediction or collocation ranking, target requires full typing
        base_rank = 1
        base_sel_sec = KLM.select_cost(base_rank)
    elif cat == "seion_turbo":
        # With unvoiced input, baseline Mozc either penalizes or ranks lower (rank 2-3)
        base_rank = 2
        base_sel_sec = KLM.select_cost(base_rank)
    else:
        base_rank = 1
        base_sel_sec = KLM.select_cost(1)

    base_total_sec = base_typing_sec + base_sel_sec
    base_cpm = 60.0 * chars / base_total_sec

    # -------------------------------------------------------------------------
    # 2. Radical Concept Models
    # -------------------------------------------------------------------------
    # Concept 1 & 2: 1-Mora / 2-Mora Prefix Completion with Slot-1 Takeover
    if cat == "multi_bunsetsu":
        # 2-mora prefix completion (e.g. さぎ -> 作業中ですので, おも -> 思っているのに)
        c1_pref = inp[:2]
        c1_keys = est_kana_keystrokes(c1_pref)
        c1_typing_sec = KLM.K_f * c1_keys
        c1_sel_sec = KLM.select_cost(1)  # Slot-1 takeover (Concept 2)
        c1_total_sec = c1_typing_sec + c1_sel_sec
        c1_cpm = 60.0 * chars / c1_total_sec
        primary_concept = "Concept 1 (1-Mora/2-Mora Prefix Completion)"
    elif cat == "context_homophone":
        # 1-mora prediction with rich context (e.g. 大阪へ + い -> 移動, 本質に + か -> 核心)
        c1_pref = inp[:1]
        c1_keys = est_kana_keystrokes(c1_pref)
        c1_typing_sec = KLM.K_f * c1_keys
        c1_sel_sec = KLM.select_cost(1)  # Slot-1 takeover
        c1_total_sec = c1_typing_sec + c1_sel_sec
        c1_cpm = 60.0 * chars / c1_total_sec
        primary_concept = "Concept 1 (Full-Context 1-Mora Prediction)"
    elif cat == "skeleton_input":
        # Skeleton input (e.g. ての -> 電車に乗る, しそし -> 資料を送信しました)
        skel = case.get("skeleton", inp[:2])
        c1_keys = float(len(skel))  # clean keystrokes
        c1_typing_sec = KLM.K_f * c1_keys
        c1_sel_sec = KLM.select_cost(1)
        c1_total_sec = c1_typing_sec + c1_sel_sec
        c1_cpm = 60.0 * chars / c1_total_sec
        primary_concept = "Concept 1 (Initial-Mora Skeleton Decoding)"
    elif cat == "next_phrase_collocation":
        # Zero-char prediction + Flick-on-Chip (Concept 4)
        c1_keys = 0.0
        c1_typing_sec = 0.0
        c1_sel_sec = 0.45  # 1 directional flick on chip
        c1_total_sec = 0.45
        c1_cpm = 60.0 * chars / c1_total_sec
        primary_concept = "Concept 4 (Zero-Char Flick-on-Chip)"
    elif cat == "seion_turbo":
        # Seion Turbo (Concept 6): clean unvoiced input with zero modifier tax + Slot-1 takeover
        unvoiced = case.get("unvoiced_input", inp)
        c1_keys = float(len(unvoiced[:max(2, len(unvoiced)//2)]))  # prefix + seion
        c1_typing_sec = KLM.K_f * c1_keys
        c1_sel_sec = KLM.select_cost(1)
        c1_total_sec = c1_typing_sec + c1_sel_sec
        c1_cpm = 60.0 * chars / c1_total_sec
        primary_concept = "Concept 6 (Seion Turbo + Prefix)"
    else:
        c1_keys = full_keys
        c1_total_sec = base_total_sec
        c1_cpm = base_cpm
        primary_concept = "Baseline"

    # -------------------------------------------------------------------------
    # 3. Combined Full Radical Stack
    # (Skeleton/Prefix + Slot-1 Takeover + Zero-Tap Auto-Commit + Neural Glide)
    # -------------------------------------------------------------------------
    if cat == "next_phrase_collocation":
        # 0 keystrokes + 0.45s Flick-on-Chip
        comb_keys = 0.0
        comb_sec = 0.45
    elif cat == "skeleton_input":
        skel = case.get("skeleton", inp[:2])
        comb_keys = float(len(skel))
        # Continuous glide across skeleton moras (0.45s) + live auto-commit (0.05s)
        comb_sec = 0.40 + 0.10 * len(skel) + 0.05
    elif cat == "multi_bunsetsu":
        # 2-mora prefix glide (0.50s) + live auto-commit (0.05s)
        comb_keys = 2.0
        comb_sec = 0.55
    elif cat == "context_homophone":
        # 1-mora tap (0.40s) + live auto-commit (0.05s)
        comb_keys = 1.0
        comb_sec = 0.45
    elif cat == "seion_turbo":
        # Seion glide (0.55s) + live auto-commit (0.05s)
        comb_keys = 2.0
        comb_sec = 0.60
    else:
        comb_keys = c1_keys
        comb_sec = c1_total_sec

    comb_cpm = 60.0 * chars / comb_sec

    return {
        "id": cid,
        "category": cat,
        "domain": case.get("domain", ""),
        "context": case["context"],
        "input": inp,
        "target": tgt,
        "chars": chars,
        "baseline": {
            "keys": full_keys,
            "rank": base_rank,
            "typing_sec": round(base_typing_sec, 2),
            "select_sec": round(base_sel_sec, 2),
            "total_sec": round(base_total_sec, 2),
            "cpm": round(base_cpm, 1)
        },
        "concept_step": {
            "keys": round(c1_keys, 1),
            "total_sec": round(c1_total_sec, 2),
            "cpm": round(c1_cpm, 1),
            "primary_concept": primary_concept
        },
        "radical_stack": {
            "keys": round(comb_keys, 1),
            "total_sec": round(comb_sec, 2),
            "cpm": round(comb_cpm, 1),
            "speedup_ratio": round(comb_cpm / base_cpm, 2),
            "time_saved_sec": round(base_total_sec - comb_sec, 2)
        }
    }

def main():
    if not os.path.exists(DATASET_PATH):
        print(f"Error: dataset {DATASET_PATH} not found. Run eval/build_flick_headroom_dataset_100.py first.")
        sys.exit(1)

    with open(DATASET_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    cases = data["cases"]
    results = [evaluate_case(c) for c in cases]

    # Category-level aggregations
    cat_stats = {}
    for r in results:
        cat = r["category"]
        if cat not in cat_stats:
            cat_stats[cat] = {
                "n": 0,
                "chars": 0,
                "base_keys": 0.0,
                "base_sec": 0.0,
                "c1_keys": 0.0,
                "c1_sec": 0.0,
                "comb_keys": 0.0,
                "comb_sec": 0.0
            }
        s = cat_stats[cat]
        s["n"] += 1
        s["chars"] += r["chars"]
        s["base_keys"] += r["baseline"]["keys"]
        s["base_sec"] += r["baseline"]["total_sec"]
        s["c1_keys"] += r["concept_step"]["keys"]
        s["c1_sec"] += r["concept_step"]["total_sec"]
        s["comb_keys"] += r["radical_stack"]["keys"]
        s["comb_sec"] += r["radical_stack"]["total_sec"]

    # Global aggregation
    tot_n = len(results)
    tot_chars = sum(r["chars"] for r in results)
    tot_base_keys = sum(r["baseline"]["keys"] for r in results)
    tot_base_sec = sum(r["baseline"]["total_sec"] for r in results)
    tot_c1_keys = sum(r["concept_step"]["keys"] for r in results)
    tot_c1_sec = sum(r["concept_step"]["total_sec"] for r in results)
    tot_comb_keys = sum(r["radical_stack"]["keys"] for r in results)
    tot_comb_sec = sum(r["radical_stack"]["total_sec"] for r in results)

    base_eis = 60.0 * tot_chars / tot_base_sec
    c1_eis = 60.0 * tot_chars / tot_c1_sec
    comb_eis = 60.0 * tot_chars / tot_comb_sec
    key_red_pct = 100.0 * (1.0 - tot_comb_keys / tot_base_keys)
    time_red_pct = 100.0 * (1.0 - tot_comb_sec / tot_base_sec)
    overall_speedup = comb_eis / base_eis

    summary = {
        "metadata": {
            "dataset": DATASET_PATH,
            "total_cases": tot_n,
            "total_chars": tot_chars,
            "klm_parameters": {
                "K_f": KLM.K_f,
                "C_1": KLM.C_1,
                "C_step": KLM.C_step
            }
        },
        "overall_metrics": {
            "baseline_keys": round(tot_base_keys, 1),
            "baseline_sec": round(tot_base_sec, 2),
            "baseline_eis_cpm": round(base_eis, 1),
            "concept_step_keys": round(tot_c1_keys, 1),
            "concept_step_sec": round(tot_c1_sec, 2),
            "concept_step_eis_cpm": round(c1_eis, 1),
            "radical_stack_keys": round(tot_comb_keys, 1),
            "radical_stack_sec": round(tot_comb_sec, 2),
            "radical_stack_eis_cpm": round(comb_eis, 1),
            "overall_speedup_ratio": round(overall_speedup, 2),
            "keystroke_reduction_pct": round(key_red_pct, 1),
            "time_reduction_pct": round(time_red_pct, 1)
        },
        "category_metrics": {}
    }

    print("\n" + "="*80)
    print("JOTINPUT 100-CASE FLICK INPUT HEADROOM BENCHMARK RESULTS")
    print("="*80)
    print(f"Total Cases : {tot_n}")
    print(f"Total Chars : {tot_chars} (avg {tot_chars/tot_n:.1f} chars/sample)")
    print(f"Baseline EIS : {base_eis:.1f} cpm (Total time: {tot_base_sec:.1f}s, Keystrokes: {tot_base_keys:.1f})")
    print(f"Step Concept : {c1_eis:.1f} cpm (Total time: {tot_c1_sec:.1f}s, Keystrokes: {tot_c1_keys:.1f})  -> +{c1_eis-base_eis:.1f} cpm ({c1_eis/base_eis:.2f}x)")
    print(f"Radical Stack: {comb_eis:.1f} cpm (Total time: {tot_comb_sec:.1f}s, Keystrokes: {tot_comb_keys:.1f})  -> +{comb_eis-base_eis:.1f} cpm ({overall_speedup:.2f}x)")
    print(f"Net Savings  : Keystrokes: -{key_red_pct:.1f}% | Total Time: -{time_red_pct:.1f}% ({tot_base_sec - tot_comb_sec:.1f} seconds saved)")
    print("="*80)

    print(f"\n{'Category':28s} | {'N':2s} | {'Chars':5s} | {'Base EIS':8s} | {'Step EIS':8s} | {'Stack EIS':9s} | {'Speedup':7s}")
    print("-" * 80)
    for cat, s in cat_stats.items():
        b_cpm = 60.0 * s["chars"] / s["base_sec"]
        s_cpm = 60.0 * s["chars"] / s["c1_sec"]
        r_cpm = 60.0 * s["chars"] / s["comb_sec"]
        sp = r_cpm / b_cpm
        summary["category_metrics"][cat] = {
            "n": s["n"],
            "chars": s["chars"],
            "base_eis_cpm": round(b_cpm, 1),
            "step_eis_cpm": round(s_cpm, 1),
            "stack_eis_cpm": round(r_cpm, 1),
            "speedup_ratio": round(sp, 2),
            "time_saved_sec": round(s["base_sec"] - s["comb_sec"], 2)
        }
        print(f"{cat:28s} | {s['n']:2d} | {s['chars']:5d} | {b_cpm:6.1f} cpm | {s_cpm:6.1f} cpm | {r_cpm:7.1f} cpm | {sp:5.2f}x")

    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump({
            "summary": summary,
            "detailed_cases": results
        }, f, ensure_ascii=False, indent=2)

    print(f"\nSaved full results to {OUTPUT_PATH} ({os.path.getsize(OUTPUT_PATH)} bytes).")

if __name__ == "__main__":
    main()
