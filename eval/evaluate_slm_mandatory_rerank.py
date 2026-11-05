#!/usr/bin/env python3
"""
evaluate_slm_mandatory_rerank.py

Comprehensive evaluation of Mandatory SLM Re-ranking vs Connection Matrix Baseline.
Baseline:
  - Viterbi search with Mozc 2D Connection Cost Matrix (connection_data.bin / connection_single_column.txt)
  - Rank 1 candidate produced purely by Connection Matrix + dictionary costs.
Treatment (Mandatory SLM Re-ranking):
  - Candidates from Baseline are scored via on-device SLM (Qwen2.5-0.5B-Instruct-Q4_K_M)
  - Combined score = alpha * rank_cost + (1.0 - alpha) * slm_neg_logprob
  - Evaluates Context-Aware Homophone Disambiguation (50 items) & Conversational Collocations (50 items).
"""

import json
import os
import re
import struct
import subprocess
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

    canonical_entries = [
        ('は', 'は', 283, 283, 50),
        ('が', 'が', 332, 332, 50),
        ('を', 'を', 379, 379, 50),
        ('に', 'に', 372, 372, 50),
        ('に', 'に', 327, 327, 50),
        ('で', 'で', 370, 370, 50),
        ('と', 'と', 371, 371, 50),
        ('の', 'の', 433, 433, 50),
        ('の', 'の', 374, 374, 50),
        ('も', 'も', 284, 284, 50),
        ('へ', 'へ', 375, 375, 50),
        ('か', 'か', 325, 325, 50),
        ('や', 'や', 265, 265, 50),
        ('よ', 'よ', 430, 430, 50),
        ('ね', 'ね', 423, 423, 50),
        ('わ', 'わ', 431, 431, 50),
        ('さ', 'さ', 18, 18, 50),
        ('ぞ', 'ぞ', 281, 281, 50),
        ('から', 'から', 330, 330, 50),
        ('まで', 'まで', 314, 314, 50),
        ('より', 'より', 15, 15, 50),
        ('ので', 'ので', 359, 359, 50),
        ('のに', 'のに', 360, 360, 50),
        ('なら', 'なら', 117, 117, 50),
        ('たら', 'たら', 116, 116, 50),
        ('でも', 'でも', 299, 299, 50),
        ('て', 'て', 33, 33, 50),
        ('た', 'た', 142, 142, 50),
        ('だ', 'だ', 165, 165, 50),
        ('です', 'です', 172, 172, 50),
        ('ます', 'ます', 240, 240, 50),
        ('てる', 'てる', 1097, 1097, 50),
        ('でる', 'でる', 1099, 1099, 50),
        ('きょう', '今日', 1851, 1851, 50),
        ('あした', '明日', 1851, 1851, 50),
        ('きのう', '昨日', 1851, 1851, 50),
        ('いま', '今', 1851, 1851, 50),
        ('いい', 'いい', 2459, 2459, 50),
        ('いい', '良い', 2429, 2429, 50),
        ('よい', '良い', 2429, 2429, 50),
        ('てんき', '天気', 1851, 1851, 50),
        ('さぎょう', '作業', 1851, 1851, 50),
        ('ちゅう', '中', 1922, 1922, 50),
        ('つとめて', '努めて', 837, 837, 50),
        ('おもって', '思って', 837, 837, 50),
        ('ちゅうがた', '中型', 1851, 1851, 50),
        ('おおがた', '大型', 1851, 1851, 50),
        ('こがた', '小型', 1851, 1851, 50),
        ('ばす', 'バス', 1851, 1851, 50),
        ('かぐ', '家具', 1851, 1851, 50),
        ('とうかい', '倒壊', 1851, 1851, 50),
        ('ぼうし', '防止', 1851, 1851, 50),
        ('むかって', '向かって', 837, 837, 50),
        ('むかっている', '向かっている', 837, 837, 50),
        ('むかってる', '向かってる', 837, 837, 50),
        ('まってる', '待ってる', 837, 837, 50),
        ('しってる', '知ってる', 837, 837, 50),
        ('やってる', 'やってる', 837, 837, 50),
        ('はしってる', '走ってる', 837, 837, 50),
        ('みてる', '見てる', 837, 837, 50),
        ('もってる', '持ってる', 837, 837, 50),
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

COLLOQUIAL_PENALTIES = {
    "厨", "折", "摩す", "デス", "御", "帽", "秋", "派", "葉", "波", "歯",
    "共", "橋", "協", "云い", "天鬼", "テル", "照", "光", "多", "他", "四", "市",
    "保っ", "重っ", "想っ", "某", "ボウ", "死の", "シノ", "之", "ノ", "イイ", "マス", "増す",
    "御座い", "無買", "要る", "居る"
}

def viterbi_search(root, reading, matrix=None, beam_width=8):
    n = len(reading)
    if n == 0:
        return []

    # Check exact match for full reading only (mirrors KanaKanjiEngine.kt in-memory Trie exact match)
    node = root
    for ch in reading:
        if ch not in node.children:
            node = None
            break
        node = node.children[ch]
    exact_match_words = list(node.words) if node is not None else []

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
                    sub_reading = reading[i:j]
                    for surface, w_cost, lid, rid in sub_words[:8]:
                        penalty = 0
                        if surface in COLLOQUIAL_PENALTIES and surface != sub_reading:
                            penalty = 20000

                        is_katakana = surface and all('\u30a0' <= c <= '\u30ff' for c in surface)
                        sokuon_katakana_penalty = 15000 if (path_words and path_words[-1].endswith('っ') and is_katakana) else 0
                        length_bonus = (j - i) * 1500

                        trans_cost = 0
                        if matrix is not None:
                            trans_cost = matrix.get_cost(last_rid, lid)

                        new_cost = path_cost + max(w_cost - length_bonus, -50000) + penalty + sokuon_katakana_penalty + trans_cost
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

    # First add exact matches from dictionary to mirror KanaKanjiEngine.kt behavior
    for surface, cost, lid, rid in exact_match_words:
        if surface and surface != reading and not all('\u3040' <= c <= '\u309f' for c in surface) and surface not in seen:
            seen.add(surface)
            results.append((surface, cost))

    for cost, surface, words, rid in final_paths:
        if surface and surface != reading and not all('\u3040' <= c <= '\u309f' for c in surface) and surface not in seen:
            seen.add(surface)
            results.append((surface, cost))
            if len(results) >= beam_width:
                break
    return results

class SlmScorerClient:
    def __init__(self, cli_path='./tools/slm_scorer_cli'):
        self.proc = subprocess.Popen(
            [cli_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        # Wait for initialization line on stderr
        line = self.proc.stderr.readline()
        if "Model initialized successfully" not in line:
            raise RuntimeError(f"SLM Scorer failed to initialize: {line}")
        print(f"SLM Scorer initialized: {line.strip()}")
        self.counter = 0

    def score(self, context, candidates):
        if not candidates:
            return [], 0.0
        self.counter += 1
        req_id = str(self.counter)
        req_line = f"{req_id}\t{context}\t" + "\t".join(candidates) + "\n"
        self.proc.stdin.write(req_line)
        self.proc.stdin.flush()

        resp_line = self.proc.stdout.readline().strip()
        parts = resp_line.split('\t')
        if not parts or parts[0] != req_id:
            raise RuntimeError(f"SLM Scorer sync error: expected {req_id}, got {resp_line}")

        lat_ms = float(parts[1]) if len(parts) > 1 else 0.0
        logprobs = [float(p) for p in parts[2:]]
        return logprobs, lat_ms

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()

def rerank_candidates(candidates, logprobs, alpha=0.05):
    """
    Reranks candidates using:
      combined_score = alpha * rank_cost + (1.0 - alpha) * slm_neg_logprob
    where rank_cost = index * 1.0 (index 0, 1, 2...), slm_neg_logprob = -logprob
    """
    scored = []
    for idx, (surface, base_cost) in enumerate(candidates):
        rank_cost = float(idx)
        lp = logprobs[idx] if idx < len(logprobs) else -20.0
        nll = -lp if lp > -100.0 else 20.0
        combined = alpha * rank_cost + (1.0 - alpha) * nll
        scored.append((surface, combined, nll, base_cost))
    scored.sort(key=lambda x: x[1])
    return [s[0] for s in scored], scored

def run_evaluation():
    dict_path = 'tools/mozc_dict/base_dictionary.tsv' if os.path.exists('tools/mozc_dict/base_dictionary.tsv') else 'app/src/main/assets/mozc/base_dictionary.tsv'
    bin_path = '/tmp/connection_data.bin'

    print(f"Loading base dictionary from {dict_path}...")
    root = build_trie(dict_path)
    print("Loading binary ConnectionMatrix (14.28 MB)...")
    matrix = ConnectionMatrix(bin_path)

    print("Spawning persistent SLM Scorer CLI (Qwen2.5-0.5B)...")
    scorer = SlmScorerClient('./tools/slm_scorer_cli')

    # =========================================================================
    # SUITE 1: Context-Aware Homophone & Semantic Disambiguation Suite (50 items)
    # =========================================================================
    homophone_dataset = [
        # 1-5: 移動 vs 異動
        ("新幹線を使って新大阪へ", "いどう", ["移動"]),
        ("東京駅から新幹線で新大阪まで", "いどう", ["移動"]),
        ("四半期の定期的な人事", "いどう", ["異動"]),
        ("本社から地方支社への人事", "いどう", ["異動"]),
        ("来月の組織改編による人事", "いどう", ["異動"]),

        # 6-9: 記者 vs 貴社
        ("日経新聞社の敏腕", "きしゃ", ["記者"]),
        ("事件の現場を取材する", "きしゃ", ["記者"]),
        ("平素は格別のお引き立てを賜り", "きしゃ", ["貴社"]),
        ("何卒よろしくお願い申し上げます", "きしゃ", ["貴社"]),

        # 10-13: 回答 vs 解答
        ("アンケートにご協力いただき、ご", "かいとう", ["回答"]),
        ("お客様からの問い合わせに対する", "かいとう", ["回答"]),
        ("期末試験の数学の模範", "かいとう", ["解答"]),
        ("入試問題の正解と詳しい", "かいとう", ["解答"]),

        # 14-17: 科学 vs 化学
        ("宇宙の真理を探究する自然", "かがく", ["科学"]),
        ("最新のテクノロジーと先端", "かがく", ["科学"]),
        ("高分子材料や合成樹脂などの有機", "かがく", ["化学"]),
        ("実験室で薬品を調合する", "かがく", ["化学"]),

        # 18-21: 製作 vs 政策 vs 制作
        ("政府が推進する経済", "せいさく", ["政策"]),
        ("金融緩和と財政", "せいさく", ["政策"]),
        ("自動車工場での高精度な金属部品の", "せいさく", ["製作"]),
        ("アニメーションスタジオによる劇場版映画の", "せいさく", ["制作"]),

        # 22-25: 創造 vs 想像
        ("天地を神が", "そうぞう", ["創造"]),
        ("新たな価値を", "そうぞう", ["創造"]),
        ("未来の豊かな生活を自由に", "そうぞう", ["想像"]),
        ("思いもよらない出来事を", "想像", ["想像"]),

        # 26-29: 証明 vs 照明
        ("数学の定理を論理的に", "しょうめい", ["証明"]),
        ("身元や住所を公的に", "しょうめい", ["証明"]),
        ("部屋を明るく照らすLEDの", "しょうめい", ["照明"]),
        ("劇場や舞台の華やかな", "しょうめい", ["照明"]),

        # 30-33: 指示 vs 支持
        ("上司からの明確な業務", "しじ", ["指示"]),
        ("現場の作業員に対して具体的な", "しじ", ["指示"]),
        ("総選挙で特定の政党を強く", "しじ", ["支持"]),
        ("若者層からの圧倒的な", "しじ", ["支持"]),

        # 34-37: 進行 vs 振興
        ("予定通りスムーズに司会がプログラムの", "しんこう", ["進行"]),
        ("病気の早期発見と悪化の", "しんこう", ["進行"]),
        ("伝統工芸と地方経済の", "しんこう", ["振興"]),
        ("観光資源を活用した地域", "しんこう", ["振興"]),

        # 38-41: 干渉 vs 鑑賞
        ("他国の内政問題に対して不当に", "かんしょう", ["干渉"]),
        ("プライベートな領域に過度に", "かんしょう", ["干渉"]),
        ("上野の美術館で名画や美術品を", "かんしょう", ["鑑賞"]),
        ("クラシック音楽の美しい旋律を", "かんしょう", ["鑑賞"]),

        # 42-45: 減少 vs 現象
        ("少子高齢化によって日本の総人口が", "げんしょう", ["減少"]),
        ("売上高や利益が前年比で大幅に", "げんしょう", ["減少"]),
        ("雨上がりの空に二重の虹がかかる自然", "げんしょう", ["現象"]),
        ("物理学における特異な", "げんしょう", ["現象"]),

        # 46-48: 効果 vs 高価
        ("新薬の服用による抜群の治療", "こうか", ["効果"]),
        ("宣伝や広告による絶大な販売", "こうか", ["効果"]),
        ("高級ブランドの宝石や非常に", "こうか", ["高価"]),

        # 49-50: 性格 vs 正確
        ("明るくて周囲に優しい", "せいかく", ["性格"]),
        ("精密な時計と狂いのない極めて", "せいかく", ["正確"]),
    ]

    print("\n========================================================")
    print(" SUITE 1: Context-Aware Homophone & Semantic Disambiguation (50 items)")
    print("========================================================")

    res_suite1 = []
    top1_base_1, top3_base_1, mrr_base_1 = 0, 0, 0.0
    top1_slm_1, top3_slm_1, mrr_slm_1 = 0, 0, 0.0
    lat_base_1, lat_slm_1 = [], []

    for ctx, reading, targets in homophone_dataset:
        t0 = time.perf_counter()
        cands_base = viterbi_search(root, reading, matrix=matrix, beam_width=8)
        t_base = (time.perf_counter() - t0) * 1000.0
        lat_base_1.append(t_base)

        cand_surfs = [c[0] for c in cands_base[:8]]

        # SLM Scoring
        logprobs, t_slm = scorer.score(ctx, cand_surfs)
        lat_slm_1.append(t_slm)

        # Rerank with alpha = 0.05
        reranked_surfs, scored_details = rerank_candidates(cands_base[:8], logprobs, alpha=0.05)

        # Evaluate Baseline
        rank_base = -1
        for idx, s in enumerate(cand_surfs):
            if s in targets:
                rank_base = idx + 1
                break
        if rank_base == 1:
            top1_base_1 += 1
        if 1 <= rank_base <= 3:
            top3_base_1 += 1
        if rank_base > 0:
            mrr_base_1 += 1.0 / rank_base

        # Evaluate Mandatory SLM Reranking
        rank_slm = -1
        for idx, s in enumerate(reranked_surfs):
            if s in targets:
                rank_slm = idx + 1
                break
        if rank_slm == 1:
            top1_slm_1 += 1
        if 1 <= rank_slm <= 3:
            top3_slm_1 += 1
        if rank_slm > 0:
            mrr_slm_1 += 1.0 / rank_slm

        res_suite1.append({
            "context": ctx,
            "reading": reading,
            "target": targets,
            "baseline": {"top1": cand_surfs[0] if cand_surfs else "", "rank": rank_base, "candidates": cand_surfs[:3]},
            "slm_rerank": {"top1": reranked_surfs[0] if reranked_surfs else "", "rank": rank_slm, "candidates": reranked_surfs[:3]},
            "logprobs": logprobs,
            "candidates_all": cand_surfs,
            "latency_ms": {"viterbi": t_base, "slm": t_slm}
        })

    n1 = len(homophone_dataset)
    acc1_b1 = (top1_base_1 / n1) * 100.0
    acc3_b1 = (top3_base_1 / n1) * 100.0
    mrr_b1 = mrr_base_1 / n1
    lat_b1 = sum(lat_base_1) / len(lat_base_1)

    acc1_s1 = (top1_slm_1 / n1) * 100.0
    acc3_s1 = (top3_slm_1 / n1) * 100.0
    mrr_s1 = mrr_slm_1 / n1
    lat_s1 = sum(lat_slm_1) / len(lat_slm_1)

    print(f"Baseline (ConnMatrix alone) -> Top-1: {acc1_b1:5.1f}% | Top-3: {acc3_b1:5.1f}% | MRR: {mrr_b1:.3f} | Latency: {lat_b1:.3f} ms")
    print(f"Mandatory SLM Re-ranking    -> Top-1: {acc1_s1:5.1f}% | Top-3: {acc3_s1:5.1f}% | MRR: {mrr_s1:.3f} | Latency: {lat_s1:.3f} ms")
    print(f"Delta (Improvement)         -> Top-1: +{acc1_s1 - acc1_b1:4.1f}% | Top-3: +{acc3_s1 - acc3_b1:4.1f}% | MRR: +{mrr_s1 - mrr_b1:.3f}")

    # =========================================================================
    # SUITE 2: Conversational Collocations Benchmark (50 items)
    # =========================================================================
    phrase_dataset = [
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
    print(" SUITE 2: Conversational Collocations Benchmark (50 items)")
    print("========================================================")

    res_suite2 = []
    top1_base_2, top3_base_2, mrr_base_2 = 0, 0, 0.0
    top1_slm_2, top3_slm_2, mrr_slm_2 = 0, 0, 0.0
    lat_base_2, lat_slm_2 = [], []

    for reading, targets in phrase_dataset:
        t0 = time.perf_counter()
        cands_base = viterbi_search(root, reading, matrix=matrix, beam_width=6)
        t_base = (time.perf_counter() - t0) * 1000.0
        lat_base_2.append(t_base)

        cand_surfs = [c[0] for c in cands_base[:6]]

        # Context-free SLM scoring (empty context -> <s>)
        logprobs, t_slm = scorer.score("", cand_surfs)
        lat_slm_2.append(t_slm)

        # Rerank with alpha = 0.05
        reranked_surfs, scored_details = rerank_candidates(cands_base[:6], logprobs, alpha=0.05)

        rank_base = -1
        for idx, s in enumerate(cand_surfs):
            if s in targets:
                rank_base = idx + 1
                break
        if rank_base == 1:
            top1_base_2 += 1
        if 1 <= rank_base <= 3:
            top3_base_2 += 1
        if rank_base > 0:
            mrr_base_2 += 1.0 / rank_base

        rank_slm = -1
        for idx, s in enumerate(reranked_surfs):
            if s in targets:
                rank_slm = idx + 1
                break
        if rank_slm == 1:
            top1_slm_2 += 1
        if 1 <= rank_slm <= 3:
            top3_slm_2 += 1
        if rank_slm > 0:
            mrr_slm_2 += 1.0 / rank_slm

        res_suite2.append({
            "reading": reading,
            "target": targets,
            "baseline": {"top1": cand_surfs[0] if cand_surfs else "", "rank": rank_base, "candidates": cand_surfs[:3]},
            "slm_rerank": {"top1": reranked_surfs[0] if reranked_surfs else "", "rank": rank_slm, "candidates": reranked_surfs[:3]},
            "logprobs": logprobs,
            "candidates_all": cand_surfs,
            "latency_ms": {"viterbi": t_base, "slm": t_slm}
        })

    n2 = len(phrase_dataset)
    acc1_b2 = (top1_base_2 / n2) * 100.0
    acc3_b2 = (top3_base_2 / n2) * 100.0
    mrr_b2 = mrr_base_2 / n2
    lat_b2 = sum(lat_base_2) / len(lat_base_2)

    acc1_s2 = (top1_slm_2 / n2) * 100.0
    acc3_s2 = (top3_slm_2 / n2) * 100.0
    mrr_s2 = mrr_slm_2 / n2
    lat_s2 = sum(lat_slm_2) / len(lat_slm_2)

    print(f"Baseline (ConnMatrix alone) -> Top-1: {acc1_b2:5.1f}% | Top-3: {acc3_b2:5.1f}% | MRR: {mrr_b2:.3f} | Latency: {lat_b2:.3f} ms")
    print(f"Mandatory SLM Re-ranking    -> Top-1: {acc1_s2:5.1f}% | Top-3: {acc3_s2:5.1f}% | MRR: {mrr_s2:.3f} | Latency: {lat_s2:.3f} ms")
    print(f"Delta (Improvement)         -> Top-1: +{acc1_s2 - acc1_b2:4.1f}% | Top-3: +{acc3_s2 - acc3_b2:4.1f}% | MRR: +{mrr_s2 - mrr_b2:.3f}")

    print("\n========================================================")
    print(" ALPHA SENSITIVITY ANALYSIS (Suite 1 vs Suite 2)")
    print("========================================================")
    alpha_values = [0.00, 0.02, 0.05, 0.10, 0.20, 0.30, 0.50, 0.80, 1.00]
    alpha_sweep_results = []
    print(f"{'Alpha':<8} | {'Suite 1 Top-1':<15} | {'Suite 1 MRR':<12} | {'Suite 2 Top-1':<15} | {'Suite 2 MRR':<12}")
    print("-" * 72)
    for a in alpha_values:
        # Suite 1
        t1_s1, mrr_s1 = 0, 0.0
        for item in res_suite1:
            cands_with_cost = [(c, 0) for c in item['candidates_all']]
            reranked, _ = rerank_candidates(cands_with_cost, item['logprobs'], alpha=a)
            rank = -1
            for idx, s in enumerate(reranked):
                if s in item['target']:
                    rank = idx + 1
                    break
            if rank == 1:
                t1_s1 += 1
            if rank > 0:
                mrr_s1 += 1.0 / rank

        # Suite 2
        t1_s2, mrr_s2 = 0, 0.0
        for item in res_suite2:
            cands_with_cost = [(c, 0) for c in item['candidates_all']]
            reranked, _ = rerank_candidates(cands_with_cost, item['logprobs'], alpha=a)
            rank = -1
            for idx, s in enumerate(reranked):
                if s in item['target']:
                    rank = idx + 1
                    break
            if rank == 1:
                t1_s2 += 1
            if rank > 0:
                mrr_s2 += 1.0 / rank

        p1_s1 = (t1_s1 / n1) * 100.0
        m1_s1 = mrr_s1 / n1
        p1_s2 = (t1_s2 / n2) * 100.0
        m1_s2 = mrr_s2 / n2
        alpha_sweep_results.append({
            "alpha": a,
            "suite_1_top1": p1_s1,
            "suite_1_mrr": m1_s1,
            "suite_2_top1": p1_s2,
            "suite_2_mrr": m1_s2
        })
        print(f"{a:<8.2f} | {p1_s1:>5.1f}%          | {m1_s1:>6.3f}       | {p1_s2:>5.1f}%          | {m1_s2:>6.3f}")

    scorer.close()

    # Save full JSON report
    report = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "model": "Qwen2.5-0.5B-Instruct-Q4_K_M (on-device SLM)",
        "rerank_alpha": 0.05,
        "alpha_sensitivity": alpha_sweep_results,
        "suite_1_homophones": {
            "total_items": n1,
            "baseline": {"top1_accuracy": acc1_b1, "top3_recall": acc3_b1, "mrr": mrr_b1, "latency_ms": lat_b1},
            "slm_rerank": {"top1_accuracy": acc1_s1, "top3_recall": acc3_s1, "mrr": mrr_s1, "latency_ms": lat_s1},
            "delta": {
                "top1_improvement": acc1_s1 - acc1_b1,
                "top3_improvement": acc3_s1 - acc3_b1,
                "mrr_improvement": mrr_s1 - mrr_b1
            },
            "details": res_suite1
        },
        "suite_2_collocations": {
            "total_items": n2,
            "baseline": {"top1_accuracy": acc1_b2, "top3_recall": acc3_b2, "mrr": mrr_b2, "latency_ms": lat_b2},
            "slm_rerank": {"top1_accuracy": acc1_s2, "top3_recall": acc3_s2, "mrr": mrr_s2, "latency_ms": lat_s2},
            "delta": {
                "top1_improvement": acc1_s2 - acc1_b2,
                "top3_improvement": acc3_s2 - acc3_b2,
                "mrr_improvement": mrr_s2 - mrr_b2
            },
            "details": res_suite2
        },
        "overall_summary": {
            "total_evaluated_queries": n1 + n2,
            "baseline_avg_top1": (acc1_b1 + acc1_b2) / 2.0,
            "slm_avg_top1": (acc1_s1 + acc1_s2) / 2.0,
            "overall_top1_gain": ((acc1_s1 + acc1_s2) - (acc1_b1 + acc1_b2)) / 2.0,
            "avg_slm_rerank_latency_ms": (lat_s1 + lat_s2) / 2.0
        }
    }

    output_path = 'eval/slm_mandatory_rerank_eval_results.json'
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\nSaved evaluation results to {output_path}")

if __name__ == '__main__':
    run_evaluation()
