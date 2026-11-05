import sys
import os
import json
import time

sys.path.insert(0, './eval')
from evaluate_slm_mandatory_rerank import (
    SlmScorerClient, build_trie, ConnectionMatrix, viterbi_search, rerank_candidates
)

def run_suite(model_path, model_name, homophone_dataset, phrase_dataset, root, matrix):
    print(f"\n=======================================================")
    print(f"Evaluating {model_name} ({model_path})...")
    print(f"=======================================================")
    
    cmd = f"./tools/slm_scorer_cli {model_path}"
    class CustomScorerClient(SlmScorerClient):
        def __init__(self, cli_cmd):
            import subprocess
            self.proc = subprocess.Popen(
                cli_cmd.split(),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1
            )
            line = self.proc.stderr.readline()
            if "Model initialized successfully" not in line:
                raise RuntimeError(f"SLM Scorer failed to initialize: {line}")
            print(f"[{model_name}] Initialized: {line.strip()}")
            self.counter = 0

    scorer = CustomScorerClient(cmd)

    # Suite 1: Homophone
    top1_base_1 = 0
    top3_base_1 = 0
    top1_slm_1 = 0
    top3_slm_1 = 0
    lat_slm_1 = []
    res_suite1 = []

    for ctx, reading, targets in homophone_dataset:
        cands_base = viterbi_search(root, reading, matrix=matrix, beam_width=8)
        cand_surfs = [c[0] for c in cands_base[:8]]

        logprobs, t_slm = scorer.score(ctx, cand_surfs)
        lat_slm_1.append(t_slm)

        reranked_surfs, scored_details = rerank_candidates(cands_base[:8], logprobs, alpha=0.05)

        rank_base = -1
        for idx, s in enumerate(cand_surfs):
            if s in targets:
                rank_base = idx + 1
                break
        if rank_base == 1: top1_base_1 += 1
        if 1 <= rank_base <= 3: top3_base_1 += 1

        rank_slm = -1
        for idx, s in enumerate(reranked_surfs):
            if s in targets:
                rank_slm = idx + 1
                break
        if rank_slm == 1: top1_slm_1 += 1
        if 1 <= rank_slm <= 3: top3_slm_1 += 1

        res_suite1.append({
            "context": ctx,
            "reading": reading,
            "targets": targets,
            "baseline_top": cand_surfs[0] if cand_surfs else "",
            "baseline_rank": rank_base,
            "slm_top": reranked_surfs[0] if reranked_surfs else "",
            "slm_rank": rank_slm,
            "lat_ms": t_slm
        })

    # Suite 2: Collocations
    top1_base_2 = 0
    top3_base_2 = 0
    top1_slm_2 = 0
    top3_slm_2 = 0
    lat_slm_2 = []
    res_suite2 = []

    for reading, targets in phrase_dataset:
        cands_base = viterbi_search(root, reading, matrix=matrix, beam_width=6)
        cand_surfs = [c[0] for c in cands_base[:6]]

        logprobs, t_slm = scorer.score("", cand_surfs)
        lat_slm_2.append(t_slm)

        reranked_surfs, scored_details = rerank_candidates(cands_base[:6], logprobs, alpha=0.05)

        rank_base = -1
        for idx, s in enumerate(cand_surfs):
            if s in targets:
                rank_base = idx + 1
                break
        if rank_base == 1: top1_base_2 += 1
        if 1 <= rank_base <= 3: top3_base_2 += 1

        rank_slm = -1
        for idx, s in enumerate(reranked_surfs):
            if s in targets:
                rank_slm = idx + 1
                break
        if rank_slm == 1: top1_slm_2 += 1
        if 1 <= rank_slm <= 3: top3_slm_2 += 1

        res_suite2.append({
            "reading": reading,
            "targets": targets,
            "baseline_top": cand_surfs[0] if cand_surfs else "",
            "baseline_rank": rank_base,
            "slm_top": reranked_surfs[0] if reranked_surfs else "",
            "slm_rank": rank_slm,
            "lat_ms": t_slm
        })

    all_lats = lat_slm_1 + lat_slm_2
    all_lats_sorted = sorted(all_lats)

    results = {
        "model": model_name,
        "suite1_homophone": {
            "total": len(homophone_dataset),
            "baseline_top1": top1_base_1 / len(homophone_dataset),
            "baseline_top3": top3_base_1 / len(homophone_dataset),
            "slm_top1": top1_slm_1 / len(homophone_dataset),
            "slm_top3": top3_slm_1 / len(homophone_dataset),
            "avg_lat_ms": sum(lat_slm_1) / len(lat_slm_1)
        },
        "suite2_collocation": {
            "total": len(phrase_dataset),
            "baseline_top1": top1_base_2 / len(phrase_dataset),
            "baseline_top3": top3_base_2 / len(phrase_dataset),
            "slm_top1": top1_slm_2 / len(phrase_dataset),
            "slm_top3": top3_slm_2 / len(phrase_dataset),
            "avg_lat_ms": sum(lat_slm_2) / len(lat_slm_2)
        },
        "combined_top1": ((top1_slm_1 + top1_slm_2) / (len(homophone_dataset) + len(phrase_dataset))),
        "overall_avg_lat_ms": sum(all_lats) / len(all_lats),
        "overall_p50_lat_ms": all_lats_sorted[int(len(all_lats) * 0.50)],
        "overall_p95_lat_ms": all_lats_sorted[int(len(all_lats) * 0.95)],
        "suite1_details": res_suite1,
        "suite2_details": res_suite2
    }

    scorer.close()
    return results

def main():
    dict_path = 'app/src/test/resources/mozc/base_dictionary.tsv'
    bin_path = '/tmp/connection_data.bin'

    print("Building trie from", dict_path)
    root = build_trie(dict_path)
    print("Loading connection matrix from", bin_path)
    matrix = ConnectionMatrix(bin_path)

    with open('eval/evaluate_slm_mandatory_rerank.py', 'r', encoding='utf-8') as f:
        lines = f.readlines()

    h_str = "".join(lines[308:385]).strip()
    c_str = "".join(lines[465:517]).strip()

    loc = {}
    exec(h_str, {}, loc)
    exec(c_str, {}, loc)
    homophone_dataset = loc['homophone_dataset']
    phrase_dataset = loc['phrase_dataset']

    print(f"Loaded {len(homophone_dataset)} homophone cases and {len(phrase_dataset)} collocation cases.")

    res_0_5b = run_suite(
        "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "Qwen2.5-0.5B-Instruct (469MB)",
        homophone_dataset, phrase_dataset, root, matrix
    )

    res_1_5b = run_suite(
        "./tools/models/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        "Qwen2.5-1.5B-Instruct (1117MB)",
        homophone_dataset, phrase_dataset, root, matrix
    )

    summary = {
        "qwen2.5-0.5b": res_0_5b,
        "qwen2.5-1.5b": res_1_5b
    }

    with open("eval/qwen_model_comparison_results.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n=================== BENCHMARK SUMMARY ===================")
    for key, data in summary.items():
        print(f"\nModel: {data['model']}")
        print(f"  - Suite 1 (Homophone):   Baseline Top-1: {data['suite1_homophone']['baseline_top1']*100:.1f}% -> SLM: {data['suite1_homophone']['slm_top1']*100:.1f}% (Top-3: {data['suite1_homophone']['slm_top3']*100:.1f}%)")
        print(f"  - Suite 2 (Collocation): Baseline Top-1: {data['suite2_collocation']['baseline_top1']*100:.1f}% -> SLM: {data['suite2_collocation']['slm_top1']*100:.1f}% (Top-3: {data['suite2_collocation']['slm_top3']*100:.1f}%)")
        print(f"  - Combined Top-1:        {data['combined_top1']*100:.1f}%")
        print(f"  - Latency: Avg={data['overall_avg_lat_ms']:.2f}ms | p50={data['overall_p50_lat_ms']:.2f}ms | p95={data['overall_p95_lat_ms']:.2f}ms")
    print("=========================================================")

if __name__ == '__main__':
    main()
