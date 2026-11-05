import json
import os
import re
import struct
import time

class TrieNode:
    def __init__(self):
        self.children = {}
        self.words = []  # list of (surface, cost, lid, rid)

class ConnectionMatrix:
    def __init__(self, binary_path):
        with open(binary_path, 'rb') as f:
            header = f.read(6)
            magic, size = struct.unpack('<IH', header)
            assert magic == 0x4D4F5A43, f"Bad magic: {hex(magic)}"
            self.size = size
            self.data = f.read()

    def get_cost(self, rid, lid):
        if rid < 0 or rid >= self.size or lid < 0 or lid >= self.size:
            return 30000
        idx = (rid * self.size + lid) * 2
        return struct.unpack_from('<H', self.data, idx)[0]

def build_trie(dict_path):
    root = TrieNode()
    with open(dict_path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) >= 5:
                reading, lid, rid, cost, surface = parts[0], int(parts[1]), int(parts[2]), int(parts[3]), parts[4]
            elif len(parts) >= 2:
                reading, surface = parts[0], parts[1]
                cost = int(parts[2]) if len(parts) >= 3 else 10000
                lid, rid = 0, 0
            else:
                continue

            node = root
            for ch in reading:
                if ch not in node.children:
                    node.children[ch] = TrieNode()
                node = node.children[ch]
            node.words.append((surface, cost, lid, rid))

    # Add high-priority canonical functional particles & auxiliary verbs with Mozc POS IDs
    canonical_entries = [
        ('は', 'は', 283, 283, 50),
        ('が', 'が', 369, 369, 50),
        ('を', 'を', 379, 379, 50),
        ('に', 'に', 372, 372, 50),
        ('で', 'で', 370, 370, 50),
        ('と', 'と', 371, 371, 50),
        ('の', 'の', 433, 433, 50),
        ('も', 'も', 284, 284, 50),
        ('へ', 'へ', 375, 375, 50),
        ('か', 'か', 325, 325, 50),
        ('や', 'や', 265, 265, 50),
        ('よ', 'よ', 430, 430, 50),
        ('ね', 'ね', 423, 423, 50),
        ('わ', 'わ', 431, 431, 50),
        ('さ', 'さ', 18, 18, 50),
        ('ぞ', 'ぞ', 281, 281, 50),
        ('から', 'から', 368, 368, 50),
        ('まで', 'まで', 314, 314, 50),
        ('より', 'より', 15, 15, 50),
        ('ので', 'ので', 359, 359, 50),
        ('のに', 'のに', 360, 360, 50),
        ('なら', 'なら', 117, 117, 50),
        ('たら', 'たら', 116, 116, 50),
        ('ても', 'ても', 33, 33, 50),
        ('でも', 'でも', 299, 299, 50),
        ('て', 'て', 348, 348, 50),
        ('た', 'た', 142, 142, 50),
        ('だ', 'だ', 165, 165, 50),
        ('です', 'です', 172, 172, 50),
        ('ます', 'ます', 240, 240, 50),
        ('てる', 'てる', 1097, 1097, 50),
        ('でる', 'でる', 1099, 1099, 50),
        ('ている', 'ている', 348, 1084, 50),
        ('おり', 'おり', 1680, 1680, 50),
        ('おる', 'おる', 1580, 1580, 50),
    ]

    for reading, surface, lid, rid, cost in canonical_entries:
        node = root
        for ch in reading:
            if ch not in node.children:
                node.children[ch] = TrieNode()
            node = node.children[ch]
        node.words.insert(0, (surface, cost, lid, rid))

    def sort_node(n):
        n.words.sort(key=lambda x: x[1])
        for child in n.children.values():
            sort_node(child)
    sort_node(root)
    return root

def viterbi_search(root, reading, matrix=None, beam_width=6):
    n = len(reading)
    if n < 3:
        return []

    beam = [[] for _ in range(n + 1)]
    beam[0].append((0, "", [], 0))

    for i in range(n):
        current_paths = beam[i]
        if not current_paths:
            continue

        for path_cost, path_surface, path_words, last_rid in current_paths:
            node = root
            j = i
            while j < n:
                ch = reading[j]
                if ch not in node.children:
                    break
                node = node.children[ch]
                j += 1

                sub_words = node.words
                if sub_words:
                    for surface, w_cost, lid, rid in sub_words[:8]:
                        length_bonus = (j - i) * 1500

                        trans_cost = 0
                        if matrix is not None:
                            trans_cost = matrix.get_cost(last_rid, lid)

                        new_cost = path_cost + max(w_cost - length_bonus, -50000) + trans_cost
                        next_rid = rid if rid > 0 else last_rid
                        beam[j].append((new_cost, path_surface + surface, path_words + [surface], next_rid))

            single_char = reading[i]
            fallback_cost = path_cost + 18000
            beam[i + 1].append((fallback_cost, path_surface + single_char, path_words + [single_char], last_rid))

        for k in range(1, n + 1):
            if len(beam[k]) > beam_width * 2:
                beam[k].sort(key=lambda x: x[0])
                beam[k] = beam[k][:beam_width * 2]

    final_paths = beam[n]
    if matrix is not None:
        final_paths.sort(key=lambda x: x[0] + matrix.get_cost(x[3], 0))  # EOS transition
    else:
        final_paths.sort(key=lambda x: x[0])

    seen = set()
    results = []
    for cost, surface, words, rid in final_paths:
        if surface and surface != reading and not all('\u3040' <= c <= '\u309f' for c in surface) and surface not in seen:
            seen.add(surface)
            results.append((surface, cost))
            if len(results) >= beam_width:
                break
    return results

def run_evaluation():
    dict_path = 'tools/mozc_dict/base_dictionary.tsv' if os.path.exists('tools/mozc_dict/base_dictionary.tsv') else 'app/src/main/assets/mozc/base_dictionary.tsv'
    bin_path = '/tmp/connection_data.bin'

    if not os.path.exists(bin_path):
        txt_path = 'tools/mozc/src/data/dictionary_oss/connection_single_column.txt'
        print(f"Compiling binary connection matrix from {txt_path} -> {bin_path}...")
        with open(txt_path, 'r', encoding='utf-8') as f_in, open(bin_path, 'wb') as f_out:
            size = int(f_in.readline().strip())
            header = struct.pack('<IH', 0x4D4F5A43, size)
            f_out.write(header)
            for line in f_in:
                c = int(line.strip())
                f_out.write(struct.pack('<H', min(max(c, 0), 32767)))
        print("Compilation complete.")

    print(f"Loading base dictionary from {dict_path}...")
    root = build_trie(dict_path)
    print("Loading binary ConnectionMatrix (14.28 MB)...")
    matrix = ConnectionMatrix(bin_path)

    # -------------------------------------------------------------
    # Benchmark Suite 1: Continuous Conversational Phrases (50 items)
    # -------------------------------------------------------------
    phrase_dataset = [
        # Homophone traps & user-reported ambiguous phrases
        ("うみのまわりに", ["海の周りに", "海のまわりに"]),
        ("いなかだよ", ["田舎だよ"]),
        ("きょうはいいてんき", ["今日はいい天気", "今日は良い天気"]),
        ("さぎょうちゅうですので", ["作業中ですので"]),
        ("つとめております", ["努めております"]),
        ("おもっているのに", ["思っているのに"]),
        ("ちゅうがたばすです", ["中型バスです"]),
        ("かぐとうかいぼうしの", ["家具倒壊防止の"]),
        ("むかっている", ["向かっている"]),
        ("むかってる", ["向かってる"]),
        ("まってる", ["待ってる"]),
        ("しってる", ["知ってる"]),
        ("やってる", ["やってる"]),
        ("はしってる", ["走ってる"]),
        ("みてる", ["見てる"]),
        ("もってる", ["持ってる"]),
        ("でんわしています", ["電話しています"]),
        ("よろしくおねがいします", ["よろしくお願いします", "よろしくお願い致します"]),
        ("ありがとうございます", ["ありがとうございます"]),
        ("あめのひに", ["雨の日に", "雨のひに"]),
        ("かわのながれ", ["川の流れ"]),
        ("やまのうえに", ["山の上に"]),
        ("ほんをよむ", ["本を読む", "ほんを読む"]),
        ("みずをのむ", ["水を飲む"]),
        ("くるまにのる", ["車に乗る"]),
        ("いえにかえる", ["家に帰る"]),
        ("あさごはんをたべる", ["朝ご飯を食べる", "朝ごはんを食べる"]),
        ("ひるごはんをたべる", ["昼ご飯を食べる", "昼ごはんを食べる"]),
        ("ばんごはんをたべる", ["晩ご飯を食べる", "晩ごはんを食べる"]),
        ("おちゃをのむ", ["お茶を飲む"]),
        ("てがみをかく", ["手紙を書く"]),
        ("えきにつく", ["駅に着く"]),
        ("でんしゃにのる", ["電車に乗る"]),
        ("かいぎがおわる", ["会議が終わる"]),
        ("しごとがはじまる", ["仕事が始まる"]),
        ("あたまがいたい", ["頭が痛い"]),
        ("おなかがすいた", ["お腹が空いた"]),
        ("めをあける", ["目を開ける"]),
        ("はるがきた", ["春が来た"]),
        ("なつのうみ", ["夏の海"]),
        ("あきのかぜ", ["秋の風"]),
        ("ふゆのゆき", ["冬の雪"]),
        ("いぬのさんぽ", ["犬の散歩"]),
        ("ねこのこえ", ["猫の声"]),
        ("とりがなく", ["鳥が鳴く"]),
        ("そらをみる", ["空を見る"]),
        ("うたをうたう", ["歌を歌う"]),
        ("みちをあるく", ["道を歩く"]),
        ("まちをあるく", ["街を歩く", "町を歩く"]),
        ("ほんをかう", ["本を買う"]),
    ]

    print("\n========================================================")
    print(" SUITE 1: Conversational Phrase & Collocation Benchmark (50 items)")
    print("========================================================")

    results_phrase = []
    top1_no, top3_no, mrr_no = 0, 0, 0.0
    top1_with, top3_with, mrr_with = 0, 0, 0.0
    lat_no_list, lat_with_list = [], []

    for reading, expected_surfaces in phrase_dataset:
        t0 = time.perf_counter()
        cands_no = viterbi_search(root, reading, matrix=None, beam_width=6)
        t_no = (time.perf_counter() - t0) * 1000.0
        lat_no_list.append(t_no)

        t0 = time.perf_counter()
        cands_with = viterbi_search(root, reading, matrix=matrix, beam_width=6)
        t_with = (time.perf_counter() - t0) * 1000.0
        lat_with_list.append(t_with)

        surfs_no = [c[0] for c in cands_no]
        surfs_with = [c[0] for c in cands_with]

        # Rank without
        rank_no = -1
        for idx, s in enumerate(surfs_no):
            if s in expected_surfaces:
                rank_no = idx + 1
                break
        if rank_no == 1:
            top1_no += 1
        if 1 <= rank_no <= 3:
            top3_no += 1
        if rank_no > 0:
            mrr_no += 1.0 / rank_no

        # Rank with
        rank_with = -1
        for idx, s in enumerate(surfs_with):
            if s in expected_surfaces:
                rank_with = idx + 1
                break
        if rank_with == 1:
            top1_with += 1
        if 1 <= rank_with <= 3:
            top3_with += 1
        if rank_with > 0:
            mrr_with += 1.0 / rank_with

        results_phrase.append({
            "reading": reading,
            "expected": expected_surfaces,
            "without_matrix": {"top1": surfs_no[0] if surfs_no else "", "rank": rank_no, "candidates": surfs_no[:3]},
            "with_matrix": {"top1": surfs_with[0] if surfs_with else "", "rank": rank_with, "candidates": surfs_with[:3]},
        })

    n_phrases = len(phrase_dataset)
    acc1_no = (top1_no / n_phrases) * 100.0
    acc3_no = (top3_no / n_phrases) * 100.0
    mrr_no_val = mrr_no / n_phrases
    avg_lat_no = sum(lat_no_list) / len(lat_no_list)

    acc1_with = (top1_with / n_phrases) * 100.0
    acc3_with = (top3_with / n_phrases) * 100.0
    mrr_with_val = mrr_with / n_phrases
    avg_lat_with = sum(lat_with_list) / len(lat_with_list)

    print(f"Baseline (Without Matrix) -> Top-1: {acc1_no:5.1f}% | Top-3: {acc3_no:5.1f}% | MRR: {mrr_no_val:.3f} | Latency: {avg_lat_no:.3f} ms")
    print(f"With Connection Matrix    -> Top-1: {acc1_with:5.1f}% | Top-3: {acc3_with:5.1f}% | MRR: {mrr_with_val:.3f} | Latency: {avg_lat_with:.3f} ms")
    print(f"Delta (Improvement)       -> Top-1: +{acc1_with - acc1_no:4.1f}% | Top-3: +{acc3_with - acc3_no:4.1f}% | MRR: +{mrr_with_val - mrr_no_val:.3f}")

    # -------------------------------------------------------------
    # Benchmark Suite 2: Mozc Official Evaluation Dataset (682 items)
    # -------------------------------------------------------------
    eval_tsv_path = 'tools/mozc/src/data/dictionary_oss/evaluation.tsv'
    eval_cases = []
    if os.path.exists(eval_tsv_path):
        with open(eval_tsv_path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                parts = line.split('\t')
                if len(parts) >= 5:
                    status, inp, outp, cmd, arg = parts[0], parts[1], parts[2], parts[3], parts[4]
                    eval_cases.append((inp, outp, cmd, arg))

    print("\n========================================================")
    print(f" SUITE 2: Mozc Official OSS Evaluation Suite ({len(eval_cases)} items)")
    print("========================================================")

    pass_mozc_no = 0
    pass_mozc_with = 0
    total_mozc = 0

    for inp, outp, cmd, arg in eval_cases:
        cands_no = viterbi_search(root, inp, matrix=None, beam_width=5)
        cands_with = viterbi_search(root, inp, matrix=matrix, beam_width=5)

        top_no = cands_no[0][0] if cands_no else ''
        top_with = cands_with[0][0] if cands_with else ''

        ok_no = False
        ok_with = False

        if cmd == 'Conversion Match':
            pat = re.compile(arg)
            ok_no = bool(pat.search(top_no))
            ok_with = bool(pat.search(top_with))
        elif cmd == 'Conversion Not Match':
            pat = re.compile(arg)
            ok_no = not bool(pat.search(top_no))
            ok_with = not bool(pat.search(top_with))
        elif cmd == 'Conversion Expected':
            ok_no = (top_no == outp)
            ok_with = (top_with == outp)
        elif cmd.startswith('Conversion Expected '):
            try:
                top_limit = int(cmd.split(' ')[-1])
                ok_no = outp in [c[0] for c in cands_no[:top_limit]]
                ok_with = outp in [c[0] for c in cands_with[:top_limit]]
            except Exception:
                continue
        else:
            continue

        if ok_no:
            pass_mozc_no += 1
        if ok_with:
            pass_mozc_with += 1
        total_mozc += 1

    mozc_pct_no = (pass_mozc_no / total_mozc) * 100.0 if total_mozc else 0
    mozc_pct_with = (pass_mozc_with / total_mozc) * 100.0 if total_mozc else 0

    print(f"Baseline (Without Matrix) -> Pass Rate: {pass_mozc_no} / {total_mozc} ({mozc_pct_no:5.2f}%)")
    print(f"With Connection Matrix    -> Pass Rate: {pass_mozc_with} / {total_mozc} ({mozc_pct_with:5.2f}%)")
    print(f"Delta (Improvement)       -> Pass Rate: +{mozc_pct_with - mozc_pct_no:5.2f}% (+{pass_mozc_with - pass_mozc_no} sentences)")

    # -------------------------------------------------------------
    # Spotlight Showcase: User Critical Test Cases
    # -------------------------------------------------------------
    print("\n========================================================")
    print(" SPOTLIGHT: User-Reported Critical Cases")
    print("========================================================")
    spotlights = [
        ("うみのまわりに", ["海の周りに", "海のまわりに"]),
        ("いなかだよ", ["田舎だよ"]),
        ("きょうはいいてんき", ["今日はいい天気", "今日は良い天気"]),
        ("さぎょうちゅうですので", ["作業中ですので"]),
        ("やまのうえに", ["山の上に"]),
        ("あめのひに", ["雨の日に", "雨のひに"]),
    ]
    for r, exp in spotlights:
        cands_no = viterbi_search(root, r, matrix=None, beam_width=3)
        cands_with = viterbi_search(root, r, matrix=matrix, beam_width=3)
        top_no = cands_no[0][0] if cands_no else "NONE"
        top_with = cands_with[0][0] if cands_with else "NONE"
        print(f"Input: 「{r}」")
        print(f"  Target:            {exp}")
        print(f"  Without Matrix:    「{top_no}」 ({'PASS' if top_no in exp else 'FAIL'})")
        print(f"  With Conn Matrix:  「{top_with}」 ({'PASS' if top_with in exp else 'FAIL'})")

    # Export structured report
    output_report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "phrase_benchmark": {
            "total_items": n_phrases,
            "without_matrix": {"top1_accuracy": acc1_no, "top3_recall": acc3_no, "mrr": mrr_no_val, "latency_ms": avg_lat_no},
            "with_matrix": {"top1_accuracy": acc1_with, "top3_recall": acc3_with, "mrr": mrr_with_val, "latency_ms": avg_lat_with},
            "delta": {
                "top1_improvement": acc1_with - acc1_no,
                "top3_improvement": acc3_with - acc3_no,
                "mrr_improvement": mrr_with_val - mrr_no_val
            },
            "detailed_results": results_phrase
        },
        "mozc_benchmark": {
            "total_items": total_mozc,
            "without_matrix_pass": pass_mozc_no,
            "without_matrix_pct": mozc_pct_no,
            "with_matrix_pass": pass_mozc_with,
            "with_matrix_pct": mozc_pct_with,
            "delta_pct": mozc_pct_with - mozc_pct_no,
            "delta_items": pass_mozc_with - pass_mozc_no
        }
    }
    with open('eval/connection_matrix_eval_results.json', 'w', encoding='utf-8') as f:
        json.dump(output_report, f, ensure_ascii=False, indent=2)
    print("\nSaved evaluation results to eval/connection_matrix_eval_results.json")

if __name__ == '__main__':
    run_evaluation()
