#!/usr/bin/env python3
"""
run_futo_swipe_eval.py

Full Quantitative Benchmark & Latency Evaluation for FUTO Swipe.
Evaluates:
  1. Baseline (Spatial Only: Encoder + Decoder + Trie Beam Search)
  2. Context-Aware (Two-Stage: Spatial Beam Search + ContextLM Rescoring)
Metrics computed:
  - Top-1 Accuracy (%)
  - Top-3 Accuracy (%)
  - Top-5 Accuracy (%)
  - Mean Latency per stroke (ms) for Spatial Pass
  - Mean Latency per stroke (ms) for ContextLM Pass
  - Mean Latency per stroke (ms) Total End-to-End
  - Breakdowns for cases with context vs without context
Outputs structured results to JSON: eval/futo_swipe/swipe_eval_results_1000.json
"""

import json
import numpy as np
import os
import sys
import time

# Ensure tools/futo_swipe is in path
sys.path.insert(0, os.path.abspath('tools/futo_swipe'))
import futo_swipe_pipeline as fsp

def evaluate(dataset_path: str, limit: int = 1000):
    print(f"Loading dataset: {dataset_path} (limit={limit})...")
    items = []
    with open(dataset_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            items.append(json.loads(line))
            if len(items) >= limit:
                break

    print(f"Loaded {len(items)} evaluation samples.")
    print("Initializing FutoSwipeEngine with ONNX Runtime...")
    engine = fsp.FutoSwipeEngine()
    print("Engine ready.")

    spatial_top1 = 0
    spatial_top3 = 0
    spatial_top5 = 0

    context_top1 = 0
    context_top3 = 0
    context_top5 = 0

    spatial_latencies = []
    context_latencies = []
    total_latencies = []

    context_improved_cases = []
    context_degraded_cases = []

    print("\nStarting evaluation run...")
    start_time = time.time()

    for idx, item in enumerate(items):
        target = item['word'].lower()
        trajectory = item['trajectory']
        prev_tokens = item['context_tokens']

        # 1. Measure Spatial-only pass
        t0 = time.perf_counter()
        cands_spatial = engine.decode(trajectory, previous_words=None, beam=100, max_candidates=5)
        t1 = time.perf_counter()
        spatial_ms = (t1 - t0) * 1000.0
        spatial_latencies.append(spatial_ms)

        spatial_words = [c[0].lower() for c in cands_spatial]
        s_hit1 = (len(spatial_words) > 0 and spatial_words[0] == target)
        s_hit3 = (target in spatial_words[:3])
        s_hit5 = (target in spatial_words[:5])

        if s_hit1: spatial_top1 += 1
        if s_hit3: spatial_top3 += 1
        if s_hit5: spatial_top5 += 1

        # 2. Measure Context-aware pass
        t2 = time.perf_counter()
        cands_context = engine.decode(trajectory, previous_words=prev_tokens, beam=100, max_candidates=5)
        t3 = time.perf_counter()
        ctx_ms = (t3 - t2) * 1000.0
        context_latencies.append(ctx_ms)
        total_latencies.append(ctx_ms)

        context_words = [c[0].lower() for c in cands_context]
        c_hit1 = (len(context_words) > 0 and context_words[0] == target)
        c_hit3 = (target in context_words[:3])
        c_hit5 = (target in context_words[:5])

        if c_hit1: context_top1 += 1
        if c_hit3: context_top3 += 1
        if c_hit5: context_top5 += 1

        if not s_hit1 and c_hit1:
            context_improved_cases.append({
                "id": item['id'],
                "target": target,
                "context": item['context'],
                "spatial_top1": spatial_words[0] if spatial_words else None,
                "context_top1": context_words[0] if context_words else None
            })
        elif s_hit1 and not c_hit1:
            context_degraded_cases.append({
                "id": item['id'],
                "target": target,
                "context": item['context'],
                "spatial_top1": spatial_words[0] if spatial_words else None,
                "context_top1": context_words[0] if context_words else None
            })

        if (idx + 1) % 100 == 0 or (idx + 1) == len(items):
            curr_s_acc = spatial_top1 / (idx + 1) * 100.0
            curr_c_acc = context_top1 / (idx + 1) * 100.0
            avg_lat = np.mean(total_latencies)
            print(f"[{idx+1}/{len(items)}] Spatial Top-1: {curr_s_acc:.2f}% | Context Top-1: {curr_c_acc:.2f}% | Latency: {avg_lat:.2f}ms")

    total_eval_time = time.time() - start_time
    total_samples = len(items)

    spatial_p50 = float(np.percentile(spatial_latencies, 50))
    spatial_p95 = float(np.percentile(spatial_latencies, 95))
    spatial_mean = float(np.mean(spatial_latencies))

    context_p50 = float(np.percentile(context_latencies, 50))
    context_p95 = float(np.percentile(context_latencies, 95))
    context_mean = float(np.mean(context_latencies))

    results = {
        "metadata": {
            "dataset": dataset_path,
            "total_samples": total_samples,
            "total_wall_clock_sec": round(total_eval_time, 2),
            "beam_width": 100
        },
        "spatial_baseline": {
            "top1_accuracy": round(spatial_top1 / total_samples * 100.0, 2),
            "top3_accuracy": round(spatial_top3 / total_samples * 100.0, 2),
            "top5_accuracy": round(spatial_top5 / total_samples * 100.0, 2),
            "latency_mean_ms": round(spatial_mean, 2),
            "latency_p50_ms": round(spatial_p50, 2),
            "latency_p95_ms": round(spatial_p95, 2)
        },
        "context_aware": {
            "top1_accuracy": round(context_top1 / total_samples * 100.0, 2),
            "top3_accuracy": round(context_top3 / total_samples * 100.0, 2),
            "top5_accuracy": round(context_top5 / total_samples * 100.0, 2),
            "latency_mean_ms": round(context_mean, 2),
            "latency_p50_ms": round(context_p50, 2),
            "latency_p95_ms": round(context_p95, 2)
        },
        "comparison": {
            "top1_delta_pct": round((context_top1 - spatial_top1) / total_samples * 100.0, 2),
            "top3_delta_pct": round((context_top3 - spatial_top3) / total_samples * 100.0, 2),
            "improved_count": len(context_improved_cases),
            "degraded_count": len(context_degraded_cases),
            "net_gain": len(context_improved_cases) - len(context_degraded_cases)
        },
        "sample_improvements": context_improved_cases[:10],
        "sample_degradations": context_degraded_cases[:10]
    }

    out_file = 'eval/futo_swipe/swipe_eval_results_1000.json'
    with open(out_file, 'w', encoding='utf-8') as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print("\n" + "="*60)
    print("EVALUATION SUMMARY REPORT")
    print("="*60)
    print(f"Dataset: {total_samples} swipes with sentence context")
    print(f"Spatial Baseline Top-1: {results['spatial_baseline']['top1_accuracy']}% (Mean Latency: {results['spatial_baseline']['latency_mean_ms']}ms)")
    print(f"Context-Aware    Top-1: {results['context_aware']['top1_accuracy']}% (Mean Latency: {results['context_aware']['latency_mean_ms']}ms)")
    print(f"Top-1 Improvement Delta: {results['comparison']['top1_delta_pct']:+}%")
    print(f"Improved cases: {results['comparison']['improved_count']}, Degraded: {results['comparison']['degraded_count']} (Net Gain: {results['comparison']['net_gain']})")
    print(f"Spatial Baseline Top-3: {results['spatial_baseline']['top3_accuracy']}%")
    print(f"Context-Aware    Top-3: {results['context_aware']['top3_accuracy']}%")
    print(f"Results saved to: {out_file}")
    print("="*60)

    return results

if __name__ == '__main__':
    dataset = 'eval/futo_swipe/swipe_eval_1000.jsonl'
    limit = 1000
    if len(sys.argv) > 1:
        limit = int(sys.argv[1])
    evaluate(dataset, limit)
