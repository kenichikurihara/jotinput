#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
evaluate_ieno_demasen_proposals.py
Evaluates 3 architectural proposals for resolving:
1. 'いえの' -> '家の'
2. 'でません' -> '出ません'
Using:
- Focus Suite: User reported cases ('いえの', 'でません', etc.)
- Suite 1: Conversational Collocations Benchmark (50 items)
- Suite 2: Natural Phrases Empty Context Benchmark (from tools/eval_500_natural_empty.tsv, 100 items)
"""

import sys
import os
import struct
import copy
import re

sys.path.append('eval')
from evaluate_connection_matrix import build_trie, ConnectionMatrix

class Evaluator:
    def __init__(self, dict_path, matrix_path):
        self.dict_path = dict_path
        self.matrix = ConnectionMatrix(matrix_path)
        self.base_root = build_trie(dict_path)

    def get_trie_clone(self, remove_1000=False, add_de_705=False, fix_canonical_costs=False):
        # Fresh build
        root = build_trie(self.dict_path)
        if remove_1000:
            if 'せ' in root.children and 'ん' in root.children['せ'].children:
                node_sen = root.children['せ'].children['ん']
                node_sen.words = [w for w in node_sen.words if w[0] != '1000']
        if add_de_705:
            if 'で' in root.children:
                node_de = root.children['で']
                # Add 出 (705, 705, cost=43)
                node_de.words.append(('出', 43, 705, 705))
                node_de.words.sort(key=lambda x: x[1])
            # Also ensure ません (659) is present in trie
            curr = root
            for ch in 'ません':
                if ch not in curr.children:
                    from evaluate_connection_matrix import TrieNode
                    curr.children[ch] = TrieNode()
                curr = curr.children[ch]
            if not any(w[0] == 'ません' for w in curr.words):
                curr.words.append(('ません', 50, 659, 659))
                curr.words.sort(key=lambda x: x[1])
        if fix_canonical_costs:
            # Fix 'の' to Mozc CRF cost 0
            if 'の' in root.children:
                node_no = root.children['の']
                # find 'の' and set cost = 0
                new_words = []
                for w in node_no.words:
                    if w[0] == 'の':
                        new_words.append(('の', 0, 433, 433))
                    else:
                        new_words.append(w)
                node_no.words = sorted(new_words, key=lambda x: x[1])
        return root

    def collect_prefix_words(self, start_node, base_reading, max_count=80):
        out = []
        queue = [(base_reading, start_node)]
        while queue and len(out) < max_count:
            curr_reading, curr_node = queue.pop(0)
            if curr_node is not start_node:
                for w in curr_node.words[:2]:
                    out.append((curr_reading, w[0], w[1]))
                    if len(out) >= max_count:
                        return out
            for ch, child in curr_node.children.items():
                queue.append((curr_reading + ch, child))
        return out

    def viterbi(self, root, reading, beam_width=6):
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
                            trans_cost = self.matrix.get_cost(last_rid, lid)
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
        final_paths.sort(key=lambda x: x[0] + self.matrix.get_cost(x[3], 0))

        seen = set()
        results = []
        for path in final_paths:
            surface = path[1]
            if surface and surface != reading and not self.is_all_hiragana(surface) and surface not in seen:
                seen.add(surface)
                results.append((surface, path[0]))
                if len(results) >= beam_width:
                    break
        return results

    def is_all_hiragana(self, s):
        for ch in s:
            if not ('\u3041' <= ch <= '\u3096' or ch == 'ー'):
                return False
        return True

    def run_get_candidates(self, root, reading, separate_exact=False, beam_width=6):
        """
        Simulates getBaseCandidates in KanaKanjiEngine.kt.
        If separate_exact == False (Current production baseline):
          1. Exact Trie matches
          2. Predictive prefix matches (Trie collectPrefixWords up to 80)
          3. Multi-bunsetsu Viterbi lattice search (beamWidth)
          4. Katakana
        If separate_exact == True (Proposal 1):
          1. Exact Trie matches
          2. Multi-bunsetsu Viterbi lattice search (Exact-length conversion)
          3. Predictive prefix matches (Post-exact prediction section)
          4. Katakana
        """
        result = []
        seen = set()

        def add_c(surface, reading_val, is_pred):
            if surface and not self.is_all_hiragana(surface) and surface not in seen:
                seen.add(surface)
                result.append({'surface': surface, 'reading': reading_val, 'isPrediction': is_pred})

        # Exact node
        curr = root
        for ch in reading:
            if ch in curr.children:
                curr = curr.children[ch]
            else:
                curr = None
                break
        exact_words = curr.words if curr else []
        for w in exact_words:
            add_c(w[0], reading, False)

        # Prefix words
        prefix_words = []
        if curr:
            prefix_words = self.collect_prefix_words(curr, reading, max_count=80)
            prefix_words.sort(key=lambda x: x[2])

        # Viterbi
        viterbi_candidates = self.viterbi(root, reading, beam_width=beam_width)

        if not separate_exact:
            # Baseline order
            for r_val, surf, _ in prefix_words:
                add_c(surf, r_val, True)
            for surf, _ in viterbi_candidates:
                add_c(surf, reading, False)
        else:
            # Proposal 1 order: Exact / Viterbi FIRST, Predictions AFTER
            for surf, _ in viterbi_candidates:
                add_c(surf, reading, False)
            for r_val, surf, _ in prefix_words:
                add_c(surf, r_val, True)

        return result

def main():
    dict_path = 'app/src/test/resources/mozc/base_dictionary.tsv'
    matrix_path = '/tmp/connection_data.bin'
    evaluator = Evaluator(dict_path, matrix_path)

    # 4 Configurations:
    # 0. Baseline (Current implementation)
    # 1. Proposal 1 (Exact-First Architectural Ranking + BeamWidth 12)
    # 2. Proposal 2 (Corpus-Driven Dictionary Repair: Add 出 705, Remove 1000, Fix 'の' cost)
    # 3. Proposal 3 (Hybrid: Proposal 1 + Proposal 2)

    configs = {
        'Baseline (Current)': {
            'separate_exact': False,
            'beam_width': 6,
            'remove_1000': False,
            'add_de_705': False,
            'fix_canonical_costs': False
        },
        'Proposal 1 (Exact-First Ranking + Beam 12)': {
            'separate_exact': True,
            'beam_width': 12,
            'remove_1000': False,
            'add_de_705': False,
            'fix_canonical_costs': False
        },
        'Proposal 2 (Corpus-Driven Lexicon Repair)': {
            'separate_exact': False,
            'beam_width': 6,
            'remove_1000': True,
            'add_de_705': True,
            'fix_canonical_costs': True
        },
        'Proposal 3 (Hybrid: Proposal 1 + 2)': {
            'separate_exact': True,
            'beam_width': 12,
            'remove_1000': True,
            'add_de_705': True,
            'fix_canonical_costs': True
        }
    }

    # Focus suite
    focus_cases = [
        ('いえの', ['家の']),
        ('でません', ['出ません']),
        ('いえにかえる', ['家に帰る']),
        ('でてくる', ['出てくる', '出て来る']),
        ('でました', ['出ました']),
    ]

    # Suite 1: Conversational collocations (50 items)
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
        ("みちをあるく", ["道をあるく", "道を歩く"]),
        ("まちをあるく", ["街を歩く", "町を歩く"]),
    ]

    # Suite 2: Natural empty context phrases (100 items from eval_500_natural_empty.tsv)
    empty_dataset = []
    with open('tools/eval_500_natural_empty.tsv') as f:
        for line in f:
            parts = line.strip().split('\t')
            if len(parts) >= 5:
                reading = parts[3].rstrip('、。')
                expected = parts[4].rstrip('、。')
                if reading and expected and len(reading) >= 3:
                    empty_dataset.append((reading, [expected]))
            elif len(parts) == 4:
                reading = parts[2].rstrip('、。')
                expected = parts[3].rstrip('、。')
                if reading and expected and len(reading) >= 3:
                    empty_dataset.append((reading, [expected]))
            if len(empty_dataset) >= 100:
                break

    print("================================================================================")
    print(" QUANTITATIVE EVALUATION ACROSS ALL PROPOSALS")
    print("================================================================================")

    for cfg_name, cfg in configs.items():
        root = evaluator.get_trie_clone(
            remove_1000=cfg['remove_1000'],
            add_de_705=cfg['add_de_705'],
            fix_canonical_costs=cfg['fix_canonical_costs']
        )
        print(f"\n>>> Configuration: {cfg_name}")

        # Focus evaluation
        print("  [Focus Target Cases]")
        for reading, targets in focus_cases:
            cands = evaluator.run_get_candidates(root, reading, separate_exact=cfg['separate_exact'], beam_width=cfg['beam_width'])
            surfaces = [c['surface'] for c in cands]
            rank = -1
            match_surf = None
            for idx, s in enumerate(surfaces):
                if s in targets:
                    rank = idx + 1
                    match_surf = s
                    break
            top5 = surfaces[:5]
            if rank > 0:
                print(f"    ✓ '{reading}' -> '{match_surf}' ranked #{rank} (Top-5: {top5})")
            else:
                print(f"    ✗ '{reading}' -> NOT FOUND in {len(surfaces)} candidates! (Top-5: {top5})")

        # Suite 1 evaluation
        top1_cnt, top3_cnt, top5_cnt = 0, 0, 0
        rr_sum = 0.0
        for reading, targets in phrase_dataset:
            cands = evaluator.run_get_candidates(root, reading, separate_exact=cfg['separate_exact'], beam_width=cfg['beam_width'])
            surfaces = [c['surface'] for c in cands]
            matched = False
            for idx, s in enumerate(surfaces):
                if s in targets:
                    if idx == 0: top1_cnt += 1
                    if idx < 3: top3_cnt += 1
                    if idx < 5: top5_cnt += 1
                    rr_sum += 1.0 / (idx + 1)
                    matched = True
                    break
        n_s1 = len(phrase_dataset)
        print(f"  [Suite 1: Conversational Collocations (50 items)]")
        print(f"    Top-1: {top1_cnt/n_s1*100:5.1f}% ({top1_cnt}/{n_s1}) | Top-3: {top3_cnt/n_s1*100:5.1f}% | Top-5: {top5_cnt/n_s1*100:5.1f}% | MRR: {rr_sum/n_s1:.3f}")

        # Suite 2 evaluation
        top1_cnt2, top3_cnt2, top5_cnt2 = 0, 0, 0
        rr_sum2 = 0.0
        for reading, targets in empty_dataset:
            cands = evaluator.run_get_candidates(root, reading, separate_exact=cfg['separate_exact'], beam_width=cfg['beam_width'])
            surfaces = [c['surface'] for c in cands]
            matched = False
            for idx, s in enumerate(surfaces):
                if s in targets:
                    if idx == 0: top1_cnt2 += 1
                    if idx < 3: top3_cnt2 += 1
                    if idx < 5: top5_cnt2 += 1
                    rr_sum2 += 1.0 / (idx + 1)
                    matched = True
                    break
        n_s2 = len(empty_dataset)
        print(f"  [Suite 2: Natural Empty Context (100 items)]")
        print(f"    Top-1: {top1_cnt2/n_s2*100:5.1f}% ({top1_cnt2}/{n_s2}) | Top-3: {top3_cnt2/n_s2*100:5.1f}% | Top-5: {top5_cnt2/n_s2*100:5.1f}% | MRR: {rr_sum2/n_s2:.3f}")

if __name__ == '__main__':
    main()
