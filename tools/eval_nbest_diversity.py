#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Evaluates Kana-Kanji conversion N-Best stem diversity, prefix recall, and suffix noise eviction
over eval_nbest_diversity_dataset.jsonl using the compiled system_dictionary.bin and connection_data.bin.
"""

import os
import sys
import json
import time
import struct
import argparse

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DEFAULT_DATASET = os.path.join(REPO_ROOT, "tools/eval_nbest_diversity_dataset.jsonl")
DEFAULT_DICT = os.path.join(REPO_ROOT, "tools/mozc_dict/system_dictionary.bin")
DEFAULT_MATRIX = os.path.join(REPO_ROOT, "tools/mozc_dict/connection_data.bin")

def load_connection_matrix(matrix_path):
    with open(matrix_path, "rb") as f:
        data = f.read()
    magic, size = struct.unpack_from("<IH", data, 0)
    def get_trans(rid, lid):
        if rid < 0 or rid >= size or lid < 0 or lid >= size:
            return 30000
        return struct.unpack_from("<H", data, 6 + (rid * size + lid) * 2)[0]
    return get_trans

def load_system_dictionary(dict_path):
    with open(dict_path, "rb") as f:
        data = f.read()
    magic1, magic2, ver, _, num_readings, total_tokens, r_off, t_off, s_off, s_len = struct.unpack_from("<IIHHIIIIII", data, 0)

    def find_tokens(target):
        low = 0
        high = num_readings - 1
        t_bytes = target.encode("utf-8")
        while low <= high:
            mid = (low + high) // 2
            rec_off = r_off + mid * 12
            str_off, str_len, t_count, t_start = struct.unpack_from("<IHHI", data, rec_off)
            r_str = data[s_off + str_off : s_off + str_off + str_len]
            if r_str == t_bytes:
                results = []
                for i in range(t_count):
                    tok_off = t_off + (t_start + i) * 12
                    sf_off, sf_len, cost, lid, rid = struct.unpack_from("<IhhHH", data, tok_off)
                    surf = data[s_off + sf_off : s_off + sf_off + sf_len].decode("utf-8")
                    results.append((surf, cost, lid, rid))
                return results
            elif r_str < t_bytes:
                low = mid + 1
            else:
                high = mid - 1
        return []

    return find_tokens

CANONICAL_WORDS = {
    "は": [("は", 50, 283, 283)], "が": [("が", 50, 369, 369)], "を": [("を", 50, 379, 379)],
    "に": [("に", 50, 372, 372)], "で": [("で", 50, 370, 370)], "と": [("と", 50, 371, 371)],
    "の": [("の", 50, 433, 433)], "も": [("も", 50, 284, 284)], "か": [("か", 50, 325, 325)],
    "から": [("から", 50, 368, 368)], "まで": [("まで", 50, 314, 314)],
    "です": [("です", 50, 172, 172)], "ます": [("ます", 50, 240, 240)], "でした": [("でした", 50, 172, 172)],
    "ました": [("ました", 50, 240, 240)], "ません": [("ません", 50, 659, 659)], "た": [("た", 50, 142, 142)],
    "だ": [("だ", 50, 165, 165)], "て": [("て", 50, 348, 348)], "ている": [("ている", 50, 348, 1084)],
    "てる": [("てる", 50, 1097, 1097)], "し": [("し", 50, 0, 0)], "して": [("して", 50, 0, 0)],
    "おきます": [("おきます", 50, 521, 521)], "ておきます": [("ておきます", 50, 348, 521)],
    "ください": [("ください", 50, 1551, 1551)], "てください": [("てください", 50, 348, 1551)],
    "でしょう": [("でしょう", 50, 183, 35)], "でしょうか": [("でしょうか", 50, 183, 325)]
}

def is_content_word(lid):
    if is_suffix(lid): return False
    return (1841 <= lid <= 2195) or (434 <= lid <= 1840) or (2196 <= lid <= 2590) or (12 <= lid <= 28) or (2659 <= lid <= 2671)

def is_suffix(lid):
    return lid in [2032, 1941, 1951, 1975] or (1941 <= lid <= 2034)

def is_functional_word(lid):
    return 29 <= lid <= 433

# Mozc OSS id.def POS ranges (src/data/dictionary_oss/id.def)
def is_auxiliary_verb(lid):
    return 29 <= lid <= 267      # 助動詞

def is_predicate_pos(pos_id):
    return (434 <= pos_id <= 1840) or (2196 <= pos_id <= 2590)  # 動詞 / 形容詞

def is_proper_noun(lid):
    return 1917 <= lid <= 1940   # 名詞,固有名詞

# Engine features (see docs/colloquial_predicate_ranking_design.md)
IDEA_A_PREDICATE_BUNSETSU_BONUS = True
IDEA_B_PROPER_DIVERSITY_KEY = True
PREDICATE_BUNSETSU_BONUS = 2000

class ViterbiPath:
    def __init__(self, cost, surface, words, last_rid, fb, fb_len, closed, second_lid=-1):
        self.second_lid = second_lid
        self.cost = cost
        self.surface = surface
        self.words = words
        self.last_rid = last_rid
        self.fb = fb
        self.fb_len = fb_len
        self.closed = closed

def select_pos_stratified_subwords(unique_words, max_total=24):
    if len(unique_words) <= max_total:
        return unique_words
    
    selected = []
    overflow = []
    pos_pair_counts = {}
    general_noun_count = 0
    proper_noun_count = 0
    symbol_count = 0
    
    for w in unique_words:
        surf, cost, lid, rid = w
        pos_key = (lid, rid)
        cnt = pos_pair_counts.get(pos_key, 0)
        
        keep = False
        if is_functional_word(lid):
            keep = (cnt < 2)
        elif (434 <= lid <= 1840) or (2196 <= lid <= 2590):
            keep = (cnt < 2)
        elif 1917 <= lid <= 1940:
            keep = (proper_noun_count < 4 and cnt < 2)
        elif is_content_word(lid) or is_suffix(lid):
            keep = (general_noun_count < 8 and cnt < 3)
        else:
            keep = (symbol_count < 2)
            
        if keep:
            selected.append(w)
            pos_pair_counts[pos_key] = cnt + 1
            if 1917 <= lid <= 1940:
                proper_noun_count += 1
            elif is_content_word(lid) or is_suffix(lid):
                general_noun_count += 1
            elif not is_functional_word(lid) and not (434 <= lid <= 1840) and not (2196 <= lid <= 2590):
                symbol_count += 1
            if len(selected) >= max_total:
                break
        else:
            overflow.append(w)
            
    if len(selected) < max_total and overflow:
        for w in overflow:
            selected.append(w)
            if len(selected) >= max_total:
                break
    return selected

def viterbi_search(reading, find_tokens, get_trans, beam_width=12):
    n = len(reading)
    if n < 3: return []

    beam = [[] for _ in range(n + 1)]
    beam[0].append(ViterbiPath(0, "", [], 0, "", 0, False))

    for i in range(n):
        for p in beam[i]:
            for j in range(i + 1, n + 1):
                sub = reading[i:j]
                words = list(CANONICAL_WORDS.get(sub, []))
                words.extend(find_tokens(sub))

                # Deduplicate subwords
                unique_words = []
                seen = set()
                for w in words:
                    k = (w[0], w[2], w[3])
                    if k not in seen:
                        seen.add(k)
                        unique_words.append(w)

                stratified = select_pos_stratified_subwords(unique_words, max_total=24)

                for surf, wcost, lid, rid in stratified:
                    lbonus = (j - i - 1) * 2000 if (j - i > 1) else 0
                    if IDEA_A_PREDICATE_BUNSETSU_BONUS and i > 0 and is_auxiliary_verb(lid) and is_predicate_pos(p.last_rid):
                        lbonus += PREDICATE_BUNSETSU_BONUS
                    tcost = get_trans(p.last_rid, lid if lid > 0 else 0)
                    ncost = p.cost + max(-50000, wcost - lbonus) + tcost
                    nrid = rid if rid > 0 else p.last_rid

                    if i == 0:
                        fb = surf
                        fb_len = j
                        closed = False
                    elif not p.closed:
                        if is_suffix(lid):
                            fb = p.fb + surf
                            fb_len = j
                            closed = False
                        elif is_functional_word(lid) or is_content_word(lid):
                            fb = p.fb
                            fb_len = p.fb_len
                            closed = True
                        else:
                            fb = p.fb
                            fb_len = p.fb_len
                            closed = p.closed
                    else:
                        fb = p.fb
                        fb_len = p.fb_len
                        closed = True

                    if i > 0 and not p.closed and is_content_word(lid):
                        second_lid = lid
                    else:
                        second_lid = p.second_lid
                    beam[j].append(ViterbiPath(ncost, p.surface + surf, p.words + [surf], nrid, fb, fb_len, closed, second_lid))

            ch = reading[i]
            fb = p.fb if i > 0 else ch
            fb_len = p.fb_len if i > 0 else 1
            beam[i+1].append(ViterbiPath(p.cost + 18000, p.surface + ch, p.words + [ch], p.last_rid, fb, fb_len, True, p.second_lid))

        for k in range(1, n + 1):
            if len(beam[k]) > beam_width * 2:
                by_key = {}
                for p in beam[k]:
                    k_tup = (p.surface, p.last_rid)
                    if k_tup not in by_key or p.cost < by_key[k_tup].cost:
                        by_key[k_tup] = p
                sorted_p = sorted(by_key.values(), key=lambda x: x.cost)
                beam[k] = sorted_p[:beam_width * 2]

    final = beam[n]
    for p in final:
        p.cost += get_trans(p.last_rid, 0)
    final.sort(key=lambda x: x.cost)

    # Stem Diversity Penalization
    scored_cands = []
    seen_surf = set()
    stem_count = {}
    for p in final:
        if p.surface not in seen_surf and p.surface != reading:
            seen_surf.add(p.surface)
            stem = p.fb if p.fb else p.surface
            if IDEA_B_PROPER_DIVERSITY_KEY:
                stem = (stem, is_proper_noun(p.second_lid))
            cnt = stem_count.get(stem, 0)
            stem_count[stem] = cnt + 1
            penalty = cnt * 3000
            scored_cands.append((p, p.cost + penalty))

    scored_cands.sort(key=lambda x: x[1])

    full_candidates = [sc[0].surface for sc in scored_cands]

    short_candidates = []
    if n >= 5:
        seen_short = set()
        for sc in scored_cands:
            fb = sc[0].fb
            fb_len = sc[0].fb_len
            if fb and 2 <= fb_len < n and fb != reading and fb not in seen_short:
                seen_short.add(fb)
                short_candidates.append(fb)
                if len(short_candidates) >= 4:
                    break

    # Interleave candidates
    results = []
    seen_final = set()
    def try_add(s):
        if s not in seen_final:
            seen_final.add(s)
            results.append(s)

    f_idx = 0
    s_idx = 0
    while f_idx < min(2, len(full_candidates)):
        try_add(full_candidates[f_idx])
        f_idx += 1
    while s_idx < min(2, len(short_candidates)):
        try_add(short_candidates[s_idx])
        s_idx += 1
    while f_idx < len(full_candidates) and len(results) < beam_width + 4:
        try_add(full_candidates[f_idx])
        f_idx += 1
    while s_idx < len(short_candidates) and len(results) < beam_width + 6:
        try_add(short_candidates[s_idx])
        s_idx += 1

    return results, scored_cands, short_candidates

def evaluate():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--dict", default=DEFAULT_DICT)
    parser.add_argument("--matrix", default=DEFAULT_MATRIX)
    parser.add_argument("--beam_width", type=int, default=12)
    parser.add_argument("--baseline", action="store_true", help="Disable Idea A and Idea B to evaluate legacy baseline")
    parser.add_argument("--exclude_hand_phrases", action="store_true",
                        help="Drop hand-added potential-verb phrase entries (cost 100) to evaluate pure Mozc OSS costs")
    parser.add_argument("--show_failures", action="store_true")
    args = parser.parse_args()
    global IDEA_A_PREDICATE_BUNSETSU_BONUS, IDEA_B_PROPER_DIVERSITY_KEY
    if args.baseline:
        IDEA_A_PREDICATE_BUNSETSU_BONUS = False
        IDEA_B_PROPER_DIVERSITY_KEY = False

    print(f"Loading system dictionary from {args.dict}...")
    find_tokens = load_system_dictionary(args.dict)
    if args.exclude_hand_phrases:
        raw_find = find_tokens
        hand = {("いけた", "行けた"), ("いけて", "行けて"), ("いけます", "行けます"), ("いけました", "行けました")}
        def find_tokens(sub, _raw=raw_find):
            return [t for t in _raw(sub) if not ((sub, t[0]) in hand and t[1] == 100)]
    print(f"Loading connection matrix from {args.matrix}...")
    get_trans = load_connection_matrix(args.matrix)

    with open(args.dataset, "r", encoding="utf-8") as f:
        records = [json.loads(line) for line in f]

    print(f"Loaded {len(records)} test cases from {args.dataset}.\n")

    top1_hits = 0
    top3_hits = 0
    prefix_hits = 0
    total_noise_count = 0
    unique_stems_sum = 0
    latencies = []

    for r in records:
        t0 = time.perf_counter()
        cands, scored, short_cands = viterbi_search(r["reading"], find_tokens, get_trans, args.beam_width)
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        latencies.append(elapsed_ms)

        top6 = cands[:6]
        if args.show_failures and (not top6 or top6[0] not in r["acceptable_full"]):
            print(f"  MISS {r['reading']}: expected {r['acceptable_full'][0]} got {top6}")

        # Top-1 & Top-3 Full Accuracy
        if top6:
            if top6[0] in r["acceptable_full"]:
                top1_hits += 1
            if any(c in r["acceptable_full"] for c in top6[:3]):
                top3_hits += 1

        # Prefix Recall
        if any(c in r["acceptable_prefixes"] for c in top6):
            prefix_hits += 1

        # Noise check
        forbidden = r.get("forbidden_substrings", [])
        for c in top6:
            if any(fb in c for fb in forbidden):
                total_noise_count += 1

        # Stem diversity in top 6
        stems = set()
        for c in top6:
            # simple stem heuristic for counting
            stems.add(c[:3])
        unique_stems_sum += len(stems)

    n_cases = len(records)
    latencies.sort()
    mean_lat = sum(latencies) / len(latencies)
    p95_lat = latencies[int(len(latencies) * 0.95)]
    p99_lat = latencies[int(len(latencies) * 0.99)]

    print("=" * 80)
    print("          Viterbi N-Best Diversity & Prefix Candidate Benchmark Report          ")
    print("=" * 80)
    print(f"Test Cases: {n_cases} | Beam Width: {args.beam_width} | Engine: Quotient Stratified Viterbi\n")
    print("[1] Accuracy & Recall")
    print(f"  - Top-1 Full Accuracy      :  {top1_hits / n_cases * 100.0:5.1f}% ({top1_hits} / {n_cases})")
    print(f"  - Top-3 Full Accuracy      :  {top3_hits / n_cases * 100.0:5.1f}% ({top3_hits} / {n_cases})")
    pref_rate = prefix_hits / n_cases * 100.0
    pref_pass = "PASS" if pref_rate >= 90.0 else "FAIL"
    print(f"  - Prefix Recall@6          :  {pref_rate:5.1f}% ({prefix_hits} / {n_cases})  [Target: >= 90.0%] -> {pref_pass}\n")

    print("[2] Diversity & Noise Eviction")
    mean_stems = unique_stems_sum / n_cases
    stem_pass = "PASS" if mean_stems >= 2.0 else "FAIL"
    print(f"  - Mean Unique Stems@6      :  {mean_stems:5.2f} stems / query [Target: >= 2.00]  -> {stem_pass}")
    noise_rate = total_noise_count / (6.0 * n_cases) * 100.0
    noise_pass = "PASS" if total_noise_count == 0 else "FAIL"
    print(f"  - Suffix Noise Rate@6      :  {noise_rate:5.2f}% ({total_noise_count} / {6 * n_cases})   [Target: == 0.0%]  -> {noise_pass}\n")

    print("[3] Latency Benchmark")
    lat_pass = "PASS" if mean_lat < 0.5 else "FAIL"
    print(f"  - Mean Latency             :  {mean_lat:5.3f} ms           [Target: < 0.500]  -> {lat_pass}")
    print(f"  - P95 Latency              :  {p95_lat:5.3f} ms")
    print(f"  - P99 Latency              :  {p99_lat:5.3f} ms\n")

    print("=" * 80)
    overall_status = "ALL BENCHMARKS PASSED" if (pref_pass == "PASS" and stem_pass == "PASS" and noise_pass == "PASS" and lat_pass == "PASS") else "BENCHMARK REGRESSION DETECTED"
    print(f"Result: {overall_status}")
    print("=" * 80)

if __name__ == "__main__":
    evaluate()
