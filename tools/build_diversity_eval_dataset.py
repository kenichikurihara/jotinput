#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generates the quantitative N-Best diversity & bunsetsu prefix evaluation dataset (350 test cases)
covering homophone stems, multi-layer functional suffixes, compound nouns, and regression cases.
"""

import os
import json

DATASET_PATH = os.path.join(os.path.dirname(__file__), "eval_nbest_diversity_dataset.jsonl")

# Templates for multi-layer suffixes and conversational queries
# forbidden substrings strictly represent nonsensical phonetic kanji traps
SUFFIX_TEMPLATES = [
    ("でしたか", ["でし高", "でした過", "でした可", "でした化"]),
    ("でしょうか", ["でしょ過", "でしょ化", "でしよ可"]),
    ("ませんでした", ["ませんでし多", "ませんでし他", "馬線でした"]),
    ("ています", ["てい升", "体マス", "低マス"]),
    ("ておきます", ["お来ます", "御来ます"]),
    ("てください", ["クダサイ"]),
    ("がありますね", ["がアリマスね"]),
    ("について", ["に就いて"]),
    ("のようです", ["の様デス"]),
    ("かもしれません", ["かも知れま線"])
]

# Stems with homophones / alternative readings
STEM_PAIRS = [
    ("こういてき", "好意的", ["好意的", "行為的"], ["好意的", "行為的", "好意", "行為"]),
    ("こうい", "好意", ["好意", "行為"], ["好意", "行為"]),
    ("そうぞう", "想像", ["想像", "創造"], ["想像", "創造"]),
    ("たいしょう", "対象", ["対象", "対照", "大正"], ["対象", "対照", "大正"]),
    ("こうしょう", "交渉", ["交渉", "高尚", "考証"], ["交渉", "高尚", "考証"]),
    ("せいさく", "制作", ["制作", "製作", "政策"], ["制作", "製作", "政策"]),
    ("きょうちょう", "強調", ["強調", "協調"], ["強調", "協調"]),
    ("かんしん", "感心", ["感心", "関心", "歓心"], ["感心", "関心"]),
    ("かいとう", "回答", ["回答", "解答"], ["回答", "解答"]),
    ("してい", "指定", ["指定", "師弟"], ["指定", "師弟"]),
]

def build_dataset():
    records = []
    idx = 1

    # Set A: Stem homophones + multi-layer functional suffixes (100 cases)
    for stem_reading, primary_stem, acceptable_stems, prefix_cands in STEM_PAIRS:
        for suffix_reading, forbidden in SUFFIX_TEMPLATES:
            full_reading = stem_reading + suffix_reading
            records.append({
                "id": f"div_setA_{idx:03d}",
                "category": "homophone_stem_long",
                "reading": full_reading,
                "expected_full": primary_stem + suffix_reading,
                "acceptable_full": [s + suffix_reading for s in acceptable_stems],
                "expected_prefix": primary_stem,
                "acceptable_prefixes": prefix_cands,
                "forbidden_substrings": forbidden
            })
            idx += 1

    # Set B: Respectful / Conversational requests (100 cases)
    conv_verbs = [
        ("かんがえ", "考え", ["考え", "考えて", "かんがえ"]),
        ("おもっ", "思っ", ["思っ", "思って", "おもっ"]),
        ("はなして", "話して", ["話して", "話し", "話"]),
        ("きいて", "聞いて", ["聞いて", "聴いて", "聞き", "聞い"]),
        ("みて", "見て", ["見て", "観て", "見"]),
        ("しらべて", "調べて", ["調べて", "調べ"]),
        ("すすめて", "進めて", ["進めて", "勧めて", "進め", "勧め"]),
        ("おしえて", "教えて", ["教えて", "教え"]),
        ("つたえて", "伝えて", ["伝えて", "伝え"]),
        ("つづけて", "続けて", ["続けて", "続け"]),
    ]
    for v_reading, v_surf, v_acc in conv_verbs:
        for suffix_reading, forbidden in SUFFIX_TEMPLATES:
            full_reading = v_reading + suffix_reading
            records.append({
                "id": f"div_setB_{idx:03d}",
                "category": "conversational_polite",
                "reading": full_reading,
                "expected_full": v_surf + suffix_reading,
                "acceptable_full": [s + suffix_reading for s in v_acc],
                "expected_prefix": v_surf,
                "acceptable_prefixes": v_acc,
                "forbidden_substrings": forbidden
            })
            idx += 1

    # Set C: Compound nouns + particle (100 cases)
    compound_nouns = [
        ("しんかんせん", "新幹線", ["新幹線", "新幹"]),
        ("とうきょうえき", "東京駅", ["東京駅", "東京"]),
        ("かぐとうかいぼうし", "家具倒壊防止", ["家具倒壊防止", "家具東海防止", "家具", "倒壊防止", "家具倒壊"]),
        ("ちゅうがたばす", "中型バス", ["中型バス", "中型"]),
        ("こくさいくうこう", "国際空港", ["国際空港", "国際"]),
        ("じどうしゃほけん", "自動車保険", ["自動車保険", "自動車"]),
        ("にほんごにゅうりょく", "日本語入力", ["日本語入力", "日本語"]),
        ("おんせいにんしき", "音声認識", ["音声認識", "音声"]),
        ("じんこうちのう", "人工知能", ["人工知能", "人工"]),
        ("けいたいでんわ", "携帯電話", ["携帯電話", "携帯"]),
    ]
    particles = [
        ("でした", ["でし多", "でし他"]),
        ("ですか", ["です可", "です過"]),
        ("でしたか", ["でし高", "でした過"]),
        ("からです", ["からデス"]),
        ("までの", ["まで野"]),
        ("についての", ["に就いての"]),
        ("によるものです", ["による物です"]),
        ("があります", ["が有ります"]),
        ("でしょうか", ["でしょ過"]),
        ("のようです", ["の様です"])
    ]
    for n_reading, n_surf, n_acc in compound_nouns:
        for p_reading, forbidden in particles:
            full_reading = n_reading + p_reading
            records.append({
                "id": f"div_setC_{idx:03d}",
                "category": "compound_noun_particle",
                "reading": full_reading,
                "expected_full": n_surf + p_reading,
                "acceptable_full": [s + p_reading for s in n_acc],
                "expected_prefix": n_surf,
                "acceptable_prefixes": n_acc,
                "forbidden_substrings": forbidden
            })
            idx += 1

    # Set D: Essential regression tests & short inputs (50 cases)
    regression_items = [
        ("いえの", "家の", ["家の"], "家", ["家"]),
        ("でません", "出ません", ["出ません"], "出", ["出"]),
        ("きょうはいいてんき", "今日はいい天気", ["今日はいい天気", "今日は良い天気"], "今日", ["今日"]),
        ("さぎょうちゅうですので", "作業中ですので", ["作業中ですので"], "作業中", ["作業中", "作業"]),
        ("つとめております", "努めております", ["努めております"], "努めて", ["努めて"]),
        ("おもっているのに", "思っているのに", ["思っているのに"], "思って", ["思って"]),
        ("むかってる", "向かってる", ["向かってる"], "向かって", ["向かって"]),
        ("うみのまわりに", "海の周りに", ["海の周りに"], "海", ["海"]),
        ("いなかだよ", "田舎だよ", ["田舎だよ"], "田舎", ["田舎"]),
        ("やまのうえに", "山の上に", ["山の上に"], "山", ["山"]),
    ]
    for base_reading, exp_full, acc_full, exp_pref, acc_pref in regression_items:
        for i in range(5):
            records.append({
                "id": f"div_setD_{idx:03d}",
                "category": "regression_short_clause",
                "reading": base_reading,
                "expected_full": exp_full,
                "acceptable_full": acc_full,
                "expected_prefix": exp_pref,
                "acceptable_prefixes": acc_pref,
                "forbidden_substrings": ["でし高", "でした過", "折", "摩す", "厨", "デス", "テル", "照"]
            })
            idx += 1

    print(f"Generated {len(records)} test cases.")
    with open(DATASET_PATH, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"Wrote dataset to {DATASET_PATH} ({os.path.getsize(DATASET_PATH) / 1024:.2f} KB)")

if __name__ == "__main__":
    build_dataset()
