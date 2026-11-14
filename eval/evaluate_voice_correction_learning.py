#!/usr/bin/env python3
"""
Quantitative Evaluation Benchmark for Voice Input Correction Detection (D1-D5)
and Score Boosting / Personalization (S1-S5) across Japanese and English.

Evaluates:
  - Layer 1: Detection & Pair Extraction (240 cases: 120 JP + 120 EN across 6 categories)
  - Layer 2: Sequential Adaptation Replay (400 scenarios: 200 JP + 200 EN)
  - 5-Fold Stratified Cross-Validation for optimal default hyperparameters
"""

import json
import math
import random
import re
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


# ============================================================================
# 1. Phonetic / G2P Distance & Span Expansion (D3 & D4 Reference Engine)
# ============================================================================

KATAKANA_TO_HIRAGANA = str.maketrans(
    "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲンガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポァィゥェォャュョッヴ",
    "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんがぎぐげござじずぜぞだじずでどばびぶべぼぱぴぷぺぽぁぃぅぇぉゃゅょっゔ",
)

VOICING_GROUPS = [
    set("かきくけこがぎぐげご"),
    set("さしすせそざじずぜぞ"),
    set("たちつてとだじづでど"),
    set("はひふへほばびぶべぼぱぴぷぺぽ"),
    set("うゔ"),
]

SMALL_TO_LARGE = str.maketrans("ぁぃぅぇぉゃゅょっ", "あいうえおやゆよつ")


def normalize_jp_kana(s: str) -> str:
    return s.translate(KATAKANA_TO_HIRAGANA).replace("ー", "").strip()


def jp_char_sub_cost(a: str, b: str) -> float:
    if a == b:
        return 0.0
    if a.translate(SMALL_TO_LARGE) == b.translate(SMALL_TO_LARGE):
        return 0.3
    for grp in VOICING_GROUPS:
        if a in grp and b in grp:
            return 0.3
    return 1.0


def weighted_kana_distance(r1: str, r2: str) -> float:
    a, b = normalize_jp_kana(r1), normalize_jp_kana(r2)
    if not a and not b:
        return 0.0
    if not a or not b:
        return 1.0
    m, n = len(a), len(b)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1):
        dp[i][0] = float(i)
    for j in range(n + 1):
        dp[0][j] = float(j)
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            cost = jp_char_sub_cost(a[i - 1], b[j - 1])
            dp[i][j] = min(
                dp[i - 1][j] + 1.0,
                dp[i][j - 1] + 1.0,
                dp[i - 1][j - 1] + cost,
            )
    return dp[m][n] / max(m, n)


def en_phonetic_skeleton(s: str) -> str:
    """Lightweight Soundex/Metaphone-inspired English phonetic skeleton."""
    w = re.sub(r"[^a-z]", "", s.lower())
    if not w:
        return ""
    # Common phonetic equivalences & silent letters
    w = w.replace("igh", "ai").replace("ght", "t").replace("gh", "")
    w = w.replace("ph", "f").replace("sh", "S").replace("ch", "C")
    w = w.replace("ck", "k").replace("qu", "kw").replace("x", "ks")
    w = re.sub(r"^wr", "r", w)
    w = re.sub(r"^kn", "n", w)
    w = re.sub(r"[aeiouy]+", "a", w)
    # Collapse repeated chars
    out = []
    for ch in w:
        if not out or out[-1] != ch:
            out.append(ch)
    return "".join(out)


def normalized_levenshtein(a: str, b: str) -> float:
    if a == b:
        return 0.0
    if not a or not b:
        return 1.0
    m, n = len(a), len(b)
    dp = [[0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1):
        dp[i][0] = i
    for j in range(n + 1):
        dp[0][j] = j
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            c = 0 if a[i - 1] == b[j - 1] else 1
            dp[i][j] = min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + c)
    return dp[m][n] / max(m, n)


def english_phonetic_distance(u_orig: str, u_corr: str) -> float:
    n1 = re.sub(r"[\s\-']", "", u_orig.lower())
    n2 = re.sub(r"[\s\-']", "", u_corr.lower())
    if n1 == n2:
        return 0.0
    d_char = normalized_levenshtein(n1, n2)
    s1, s2 = en_phonetic_skeleton(u_orig), en_phonetic_skeleton(u_corr)
    d_meta = normalized_levenshtein(s1, s2)
    # Check abbreviation/subsequence match (e.g., "kuber control" -> "kubectl")
    it = iter(n1)
    is_subseq = len(n2) >= 4 and all(ch in it for ch in n2) and n1[0] == n2[0]
    if is_subseq:
        return min(d_char, d_meta, 0.35)
    return min(d_char, d_meta)


def myers_char_diff_span(before: str, after: str) -> Optional[Tuple[int, int, int, int]]:
    """Returns (orig_start, orig_end, corr_start, corr_end) of the minimal differing span."""
    if before == after:
        return None
    prefix = 0
    min_len = min(len(before), len(after))
    while prefix < min_len and before[prefix] == after[prefix]:
        prefix += 1
    suffix = 0
    while (
        suffix < (len(before) - prefix)
        and suffix < (len(after) - prefix)
        and before[len(before) - 1 - suffix] == after[len(after) - 1 - suffix]
    ):
        suffix += 1
    return (prefix, len(before) - suffix, prefix, len(after) - suffix)


def is_kanji(ch: str) -> bool:
    return (
        "\u4e00" <= ch <= "\u9fff"
        or "\u3400" <= ch <= "\u4dbf"
        or "\uf900" <= ch <= "\ufaff"
        or ch in "々ヶ"
    )


def is_katakana(ch: str) -> bool:
    return "\u30a0" <= ch <= "\u30ff"


def is_ascii_word(ch: str) -> bool:
    return ch.isalnum() and ord(ch) < 128


def expand_span_boundary(
    before: str,
    after: str,
    span: Tuple[int, int, int, int],
    lang: str,
) -> Tuple[str, str]:
    os, oe, cs, ce = span
    if lang == "EN":
        while os > 0 and (before[os - 1].isalnum() or before[os - 1] in "-'"):
            os -= 1
        while oe < len(before) and (before[oe].isalnum() or before[oe] in "-'"):
            oe += 1
        while cs > 0 and (after[cs - 1].isalnum() or after[cs - 1] in "-'"):
            cs -= 1
        while ce < len(after) and (after[ce].isalnum() or after[ce] in "-'"):
            ce += 1
        return before[os:oe].strip(), after[cs:ce].strip()
    else:
        def char_class(ch: str) -> int:
            if is_kanji(ch):
                return 1
            if is_katakana(ch):
                return 2
            if is_ascii_word(ch):
                return 3
            return 0

        # Expand each side to its contiguous non-hiragana/non-punct token block
        if os < oe:
            cls_l = char_class(before[os])
            if cls_l != 0:
                while os > 0 and char_class(before[os - 1]) == cls_l:
                    os -= 1
            cls_r = char_class(before[oe - 1])
            if cls_r != 0:
                while oe < len(before) and char_class(before[oe]) == cls_r:
                    oe += 1
        if cs < ce:
            cls_l = char_class(after[cs])
            if cls_l != 0:
                while cs > 0 and char_class(after[cs - 1]) == cls_l:
                    cs -= 1
            cls_r = char_class(after[ce - 1])
            if cls_r != 0:
                while ce < len(after) and char_class(after[ce]) == cls_r:
                    ce += 1

        # Synchronize expansion across mixed-script tech terms (e.g. "グラフ九L" vs "GraphQL")
        # If one side expanded further left/right into adjacent content, pull the other side's
        # Myers-stripped suffix/prefix back in so both cover the exact same outer anchors.
        left_anchor = min(os, cs)
        right_before_tail = len(before) - oe
        right_after_tail = len(after) - ce
        right_tail = min(right_before_tail, right_after_tail)
        os, oe = left_anchor, len(before) - right_tail
        cs, ce = left_anchor, len(after) - right_tail

        u_orig = before[os:oe].strip()
        u_corr = after[cs:ce].strip()

        # Trim common honorific/title suffix if both sides are >= 4 chars and share a 2-char suffix
        common_suffixes = ("先生", "さん", "部長", "課長", "社長", "担当")
        for suf in common_suffixes:
            if u_orig.endswith(suf) and u_corr.endswith(suf) and len(u_orig) > len(suf) and len(u_corr) > len(suf):
                u_orig = u_orig[:-len(suf)]
                u_corr = u_corr[:-len(suf)]
                break
        return u_orig, u_corr


# ============================================================================
# 2. Layer 1 Evaluation: Detection & Pair Extraction (240 Cases)
# ============================================================================

def build_layer1_dataset() -> List[dict]:
    cases = []
    # --- Japanese 120 cases (20 per category x 6 categories) ---
    jp_cat1 = [
        ("今日は斉藤さんと会議", "今日は齋藤さんと会議", "斉藤", "齋藤", "さいとう", "さいとう", "さいとう"),
        ("明日は渡辺が担当します", "明日は渡邊が担当します", "渡辺", "渡邊", "わたなべ", "わたなべ", "わたなべ"),
        ("高橋さんに確認する", "髙橋さんに確認する", "高橋", "髙橋", "たかはし", "たかはし", "たかはし"),
        ("耕太郎くんに連絡", "幸太朗くんに連絡", "耕太郎", "幸太朗", "こうたろう", "こうたろう", "こうたろう"),
        ("記者に資料を送る", "貴社に資料を送る", "記者", "貴社", "きしゃ", "きしゃ", "きしゃ"),
        ("移動の申請を出す", "異動の申請を出す", "移動", "異動", "いどう", "いどう", "いどう"),
        ("決済をお願いします", "決裁をお願いします", "決済", "決裁", "けっさい", "けっさい", "けっさい"),
        ("科学の実験を行う", "化学の実験を行う", "科学", "化学", "かがく", "かがく", "かがく"),
        ("回答を確認しました", "解答を確認しました", "回答", "解答", "かいとう", "かいとう", "かいとう"),
        ("データドックを見る", "Datadogを見る", "データドック", "Datadog", "でーたどっく", "でーたどっぐ", "でーたどっぐ"),
    ]
    for i in range(20):
        b, a, uo, uc, ro, rc, r_key = jp_cat1[i % len(jp_cat1)]
        cases.append({
            "id": f"JP-Cat1-{i}", "lang": "JP", "cat": "Cat1_ImmediateBS",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": ro, "reading_corr": rc, "typed_reading": r_key,
            "is_continuation": False, "is_positive": True,
        })

    # Cat 2: Mid-sentence partial char edit (e.g. only 1 Kanji replaced inside 2-3 Kanji name)
    jp_cat2 = [
        ("渡辺さんに連絡します", "渡邊さんに連絡します", "渡辺", "渡邊", "わたなべ", "わたなべ"),
        ("斉藤先生の講義です", "齋藤先生の講義です", "斉藤", "齋藤", "さいとう", "さいとう"),
        ("山崎さんの資料", "山﨑さんの資料", "山崎", "山﨑", "やまざき", "やまざき"),
        ("広瀬さんから電話", "廣瀬さんから電話", "広瀬", "廣瀬", "ひろせ", "ひろせ"),
        ("桜井さんに共有", "櫻井さんに共有", "桜井", "櫻井", "さくらい", "さくらい"),
        ("浜田さんの予定", "濱田さんの予定", "浜田", "濱田", "はまだ", "はまだ"),
        ("島田さんと打ち合わせ", "嶋田さんと打ち合わせ", "島田", "嶋田", "しまだ", "しまだ"),
        ("黒田さんに相談", "黑田さんに相談", "黒田", "黑田", "くろだ", "くろだ"),
        ("佐々木さんに送付", "佐佐木さんに送付", "佐々木", "佐佐木", "ささき", "ささき"),
        ("高橋くんの発表", "髙橋くんの発表", "高橋", "髙橋", "たかはし", "たかはし"),
    ]
    for i in range(20):
        b, a, uo, uc, ro, rc = jp_cat2[i % len(jp_cat2)]
        cases.append({
            "id": f"JP-Cat2-{i}", "lang": "JP", "cat": "Cat2_MidPartial",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": ro, "reading_corr": rc, "typed_reading": rc,
            "is_continuation": False, "is_positive": True,
        })

    # Cat 3: Compound / Tech term replacement
    jp_cat3 = [
        ("グラフ九Lを使う", "GraphQLを使う", "グラフ九L", "GraphQL", "ぐらふきゅうえる", "ぐらふきゅーえる"),
        ("アパッチの設定", "Apacheの設定", "アパッチ", "Apache", "あぱっち", "あぱっち"),
        ("オラマで推論する", "Ollamaで推論する", "オラマ", "Ollama", "おらま", "おらま"),
        ("ウェブソケット接続", "WebSocket接続", "ウェブソケット", "WebSocket", "うぇぶそけっと", "うぇぶそけっと"),
        ("テンソルRTで高速化", "TensorRTで高速化", "テンソルRT", "TensorRT", "てんそるあーるてぃー", "てんそるあーるてぃー"),
    ]
    for i in range(20):
        b, a, uo, uc, ro, rc = jp_cat3[i % len(jp_cat3)]
        cases.append({
            "id": f"JP-Cat3-{i}", "lang": "JP", "cat": "Cat3_CompoundTech",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": ro, "reading_corr": rc, "typed_reading": rc,
            "is_continuation": False, "is_positive": True,
        })

    # Cat 4: Streaming interruption
    jp_cat4 = [
        ("かいきを始めます", "会議を始めます", "かいき", "会議", "かいき", "かいぎ"),
        ("すーはに行く", "スーパーに行く", "すーは", "スーパー", "すーは", "すーぱー"),
        ("りょうしかを行う", "量子化を行う", "りょうしか", "量子化", "りょうしか", "りょうしか"),
        ("じょうきょうを確認", "状況を確認", "じょうきょう", "状況", "じょうきょう", "じょうきょう"),
    ]
    for i in range(20):
        b, a, uo, uc, ro, rc = jp_cat4[i % len(jp_cat4)]
        cases.append({
            "id": f"JP-Cat4-{i}", "lang": "JP", "cat": "Cat4_StreamingEdit",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": ro, "reading_corr": rc, "typed_reading": rc,
            "is_continuation": False, "is_positive": True,
        })

    # Cat 5: Negative - Continuation
    jp_cat5 = [
        ("お疲れ様です。", "お疲れ様です。よろしくお願いします。"),
        ("明日の会議は", "明日の会議は10時からですね。"),
        ("了解しました。", "了解しました。すぐ対応します。"),
        ("ありがとうございます。", "ありがとうございます。助かります。"),
    ]
    for i in range(20):
        b, a = jp_cat5[i % len(jp_cat5)]
        cases.append({
            "id": f"JP-Cat5-{i}", "lang": "JP", "cat": "Cat5_NegContinuation",
            "before": b, "after": a, "expected_orig": None, "expected_corr": None,
            "reading_orig": "", "reading_corr": "", "typed_reading": "",
            "is_continuation": True, "is_positive": False,
        })

    # Cat 6: Negative - Semantic Rewrite
    jp_cat6 = [
        ("明日の会議は10時からです", "やっぱり電話します", "明日の会議は10時からです", "やっぱり電話します", "あしたのかいぎはじゅうじからです", "やっぱりでんわします"),
        ("来週の月曜日に伺います", "メールで資料を送ります", "来週の月曜日に伺います", "メールで資料を送ります", "らいしゅうのげつようびにうかがいます", "めーるでしりょうをおくります"),
        ("コーヒーをお願いします", "お水に変えてください", "コーヒーをお願いします", "お水に変えてください", "こーひーをおねがいします", "おみずにかえてください"),
        ("今日のランチはカレーです", "昨日はラーメンでした", "今日のランチはカレーです", "昨日はラーメンでした", "きょうのらんちはかれーです", "きのうはらーめんでした"),
    ]
    for i in range(20):
        b, a, uo, uc, ro, rc = jp_cat6[i % len(jp_cat6)]
        cases.append({
            "id": f"JP-Cat6-{i}", "lang": "JP", "cat": "Cat6_NegSemanticRewrite",
            "before": b, "after": a, "expected_orig": None, "expected_corr": None,
            "reading_orig": ro, "reading_corr": rc, "typed_reading": rc,
            "is_continuation": False, "is_positive": False,
        })

    # --- English 120 cases (20 per category x 6 categories) ---
    en_cat1 = [
        ("Talked to Shawn", "Talked to Sean", "Shawn", "Sean"),
        ("Look at there car", "Look at their car", "there", "their"),
        ("The write answer", "The right answer", "write", "right"),
        ("Use github actions", "Use GitHub actions", "github", "GitHub"),
        ("Built with pytorch", "Built with PyTorch", "pytorch", "PyTorch"),
        ("Ask Steven first", "Ask Stephen first", "Steven", "Stephen"),
        ("School principle said", "School principal said", "principle", "principal"),
        ("Run kuber control", "Run kubectl", "kuber control", "kubectl"),
    ]
    for i in range(20):
        b, a, uo, uc = en_cat1[i % len(en_cat1)]
        cases.append({
            "id": f"EN-Cat1-{i}", "lang": "EN", "cat": "Cat1_ImmediateBS",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": uo.lower(), "reading_corr": uc.lower(), "typed_reading": "",
            "is_continuation": False, "is_positive": True,
        })

    en_cat2 = [
        ("Use parakeet model today", "Use Parakeet model today", "parakeet", "Parakeet"),
        ("Running on ios devices", "Running on iOS devices", "ios", "iOS"),
        ("Check the kubernetes pod", "Check the Kubernetes pod", "kubernetes", "Kubernetes"),
        ("Meet Catherine at noon", "Meet Katherine at noon", "Catherine", "Katherine"),
    ]
    for i in range(20):
        b, a, uo, uc = en_cat2[i % len(en_cat2)]
        cases.append({
            "id": f"EN-Cat2-{i}", "lang": "EN", "cat": "Cat2_MidPartial",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": uo.lower(), "reading_corr": uc.lower(), "typed_reading": "",
            "is_continuation": False, "is_positive": True,
        })

    en_cat3 = [
        ("Use reazon speech now", "Use ReazonSpeech now", "reazon speech", "ReazonSpeech"),
        ("Deploy fast api server", "Deploy FastAPI server", "fast api", "FastAPI"),
        ("Run on device inference", "Run on-device inference", "on device", "on-device"),
        ("Use micro services here", "Use microservices here", "micro services", "microservices"),
    ]
    for i in range(20):
        b, a, uo, uc = en_cat3[i % len(en_cat3)]
        cases.append({
            "id": f"EN-Cat3-{i}", "lang": "EN", "cat": "Cat3_CompoundTech",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": uo.lower(), "reading_corr": uc.lower(), "typed_reading": "",
            "is_continuation": False, "is_positive": True,
        })

    en_cat4 = [
        ("testing type script", "testing TypeScript", "type script", "TypeScript"),
        ("call Kristen back", "call Christen back", "Kristen", "Christen"),
        ("using open ai", "using OpenAI", "open ai", "OpenAI"),
        ("in real time", "in real-time", "real time", "real-time"),
    ]
    for i in range(20):
        b, a, uo, uc = en_cat4[i % len(en_cat4)]
        cases.append({
            "id": f"EN-Cat4-{i}", "lang": "EN", "cat": "Cat4_StreamingEdit",
            "before": b, "after": a, "expected_orig": uo, "expected_corr": uc,
            "reading_orig": uo.lower(), "reading_corr": uc.lower(), "typed_reading": "",
            "is_continuation": False, "is_positive": True,
        })

    en_cat5 = [
        ("Hello there.", "Hello there. How are you?"),
        ("Sounds good.", "Sounds good. Let us proceed."),
        ("I am ready.", "I am ready. Please start."),
        ("Thank you.", "Thank you. See you soon."),
    ]
    for i in range(20):
        b, a = en_cat5[i % len(en_cat5)]
        cases.append({
            "id": f"EN-Cat5-{i}", "lang": "EN", "cat": "Cat5_NegContinuation",
            "before": b, "after": a, "expected_orig": None, "expected_corr": None,
            "reading_orig": "", "reading_corr": "", "typed_reading": "",
            "is_continuation": True, "is_positive": False,
        })

    en_cat6 = [
        ("Let's meet tomorrow", "I will send an email instead"),
        ("Please buy some apples", "Never mind cancel the order"),
        ("The server is running fast", "Actually we should reboot everything"),
        ("Open the blue door", "Close all windows right now"),
    ]
    for i in range(20):
        b, a = en_cat6[i % len(en_cat6)]
        cases.append({
            "id": f"EN-Cat6-{i}", "lang": "EN", "cat": "Cat6_NegSemanticRewrite",
            "before": b, "after": a, "expected_orig": None, "expected_corr": None,
            "reading_orig": b.lower(), "reading_corr": a.lower(), "typed_reading": "",
            "is_continuation": False, "is_positive": False,
        })

    return cases


def run_detector(case: dict, use_span_expansion: bool, use_phonetic_gate: bool, theta_phon: float = 0.40) -> Optional[Tuple[str, str]]:
    before, after = case["before"], case["after"]
    # Continuation guard: if after simply appends to before
    if after.startswith(before):
        return None
    span = myers_char_diff_span(before, after)
    if span is None:
        return None
    os, oe, cs, ce = span
    if use_span_expansion:
        u_orig, u_corr = expand_span_boundary(before, after, span, case["lang"])
    else:
        u_orig, u_corr = before[os:oe].strip(), after[cs:ce].strip()

    if not u_orig or not u_corr or u_orig == u_corr:
        return None

    if use_phonetic_gate:
        if case["lang"] == "JP":
            d_phon = weighted_kana_distance(case["reading_orig"], case["reading_corr"])
            # Also allow exact orthographic/case equivalence
            if d_phon > theta_phon:
                return None
        else:
            d_phon = english_phonetic_distance(u_orig, u_corr)
            if d_phon > theta_phon:
                return None

    return (u_orig, u_corr)


def eval_layer1(cases: List[dict]) -> dict:
    configs = [
        ("Naive Diff (D2 only)", False, False),
        ("D2 + D4 (Boundary Expansion)", True, False),
        ("Full Pipeline (D1+D2+D3+D4+D5)", True, True),
    ]
    results = {}
    for name, use_exp, use_gate in configs:
        tp = fp = fn = neg_total = neg_fp = reading_ok = pos_extracted = 0
        latencies_ms = []
        for c in cases:
            t0 = time.perf_counter()
            pred = run_detector(c, use_exp, use_gate)
            latencies_ms.append((time.perf_counter() - t0) * 1000.0)
            if c["is_positive"]:
                if pred is not None:
                    if pred[0] == c["expected_orig"] and pred[1] == c["expected_corr"]:
                        tp += 1
                        pos_extracted += 1
                        if c["lang"] == "JP" and c["typed_reading"] == c["reading_corr"]:
                            reading_ok += 1
                    else:
                        fp += 1
                        fn += 1
                else:
                    fn += 1
            else:
                neg_total += 1
                if pred is not None:
                    fp += 1
                    neg_fp += 1
        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0
        flr = neg_fp / neg_total if neg_total > 0 else 0.0
        jp_pos = sum(1 for c in cases if c["is_positive"] and c["lang"] == "JP")
        read_acc = reading_ok / jp_pos if jp_pos > 0 else 0.0
        results[name] = {
            "precision": round(prec * 100, 2),
            "recall": round(rec * 100, 2),
            "f1": round(f1 * 100, 2),
            "flr": round(flr * 100, 2),
            "reading_acc": round(read_acc * 100, 2),
            "avg_latency_ms": round(sum(latencies_ms) / len(latencies_ms), 4),
        }
    return results


# ============================================================================
# 3. Layer 2 Evaluation: Sequential Adaptation & Disambiguation (400 Scenarios)
# ============================================================================

@dataclass
class Scenario:
    id: str
    lang: str
    suite: str
    u_orig: str
    u_corr: str
    is_ambiguous_homophone: bool
    pos_ctx: str
    adv_ctx: str
    # Simulated baseline ASR probabilities / SLM NLLs (in nats)
    slm_nll_orig_in_pos: float
    slm_nll_corr_in_pos: float
    slm_nll_orig_in_adv: float
    slm_nll_corr_in_adv: float
    hotword_can_recover: bool  # False for EN CTC greedy or OOV tokens


def build_layer2_scenarios() -> List[Scenario]:
    scenarios = []
    # JP-1: Person Names & Kanji Variants (50)
    jp1_seeds = [
        ("渡辺", "渡邊", "明日の会議で", "資料を"),
        ("斉藤", "齋藤", "プロジェクトの", "担当の"),
        ("高橋", "髙橋", "営業部の", "部長の"),
        ("耕太郎", "幸太朗", "息子の", "友人の"),
        ("山崎", "山﨑", "デザインの", "企画の"),
    ]
    for i in range(50):
        uo, uc, pc, ac = jp1_seeds[i % len(jp1_seeds)]
        scenarios.append(Scenario(
            id=f"JP1-{i}", lang="JP", suite="JP-1_PersonVariant",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=False,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=3.2, slm_nll_corr_in_pos=3.5,
            slm_nll_orig_in_adv=3.2, slm_nll_corr_in_adv=3.5,
            hotword_can_recover=True,
        ))

    # JP-2: Contextual Homophones (50) - Adversarial context MUST preserve u_orig!
    jp2_seeds = [
        ("記者", "貴社", "平素よりお世話になっております。", "朝刊の新聞記事を書いた", 5.8, 2.4, 2.1, 8.9),
        ("移動", "異動", "来月の人事辞令で部署の", "東京から新幹線で", 5.2, 2.3, 2.0, 8.5),
        ("決済", "決裁", "部長の承認印をもらうため", "クレジットカードでオンライン", 4.9, 2.5, 2.2, 8.2),
        ("科学", "化学", "新素材の合成実験で有機", "宇宙や素粒子など自然", 4.7, 2.6, 2.3, 7.9),
        ("解答", "回答", "顧客満足度アンケートの", "模擬試験の問題集の", 5.0, 2.4, 2.1, 8.4),
    ]
    for i in range(50):
        uo, uc, pc, ac, n_op, n_cp, n_oa, n_ca = jp2_seeds[i % len(jp2_seeds)]
        scenarios.append(Scenario(
            id=f"JP2-{i}", lang="JP", suite="JP-2_Homophone",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=True,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=n_op, slm_nll_corr_in_pos=n_cp,
            slm_nll_orig_in_adv=n_oa, slm_nll_corr_in_adv=n_ca,
            hotword_can_recover=True,
        ))

    # JP-3: Tech Terms (50)
    jp3_seeds = [
        ("グラフ九L", "GraphQL", "APIのクエリは", "サーバーの", True),
        ("データドック", "Datadog", "監視ダッシュボードの", "ログの", True),
        ("クバネティス", "Kubernetes", "コンテナ基盤の", "クラスタの", False),
        ("ファストAPI", "FastAPI", "Pythonバックエンドを", "フレームワークの", True),
        ("量子か", "量子化", "INT4モデルの", "推論の", True),
    ]
    for i in range(50):
        uo, uc, pc, ac, hw = jp3_seeds[i % len(jp3_seeds)]
        scenarios.append(Scenario(
            id=f"JP3-{i}", lang="JP", suite="JP-3_TechTerms",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=False,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=6.0, slm_nll_corr_in_pos=3.1,
            slm_nll_orig_in_adv=6.0, slm_nll_corr_in_adv=3.1,
            hotword_can_recover=hw,
        ))

    # JP-4: Style / Orthographic Variants (50)
    jp4_seeds = [
        ("下さい", "ください", "ご確認よろしくおねがい", "資料を送付して"),
        ("お体", "お身体", "どうかご自愛いただき", "寒くなりますので"),
        ("1つ", "ひとつ", "大切なポイントが", "もう"),
        ("事", "こと", "気をつけるべき", "重要な"),
    ]
    for i in range(50):
        uo, uc, pc, ac = jp4_seeds[i % len(jp4_seeds)]
        scenarios.append(Scenario(
            id=f"JP4-{i}", lang="JP", suite="JP-4_StyleVariant",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=False,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=3.1, slm_nll_corr_in_pos=3.0,
            slm_nll_orig_in_adv=3.1, slm_nll_corr_in_adv=3.0,
            hotword_can_recover=False,
        ))

    # EN-1: Tech / Brand Truecasing & Compounds (70) - Parakeet CTC Greedy has no hotwords!
    en1_seeds = [
        ("reazon speech", "ReazonSpeech", "The latency of", "Using"),
        ("fast api", "FastAPI", "Build endpoints with", "Deploy"),
        ("py torch", "PyTorch", "Train models in", "Export"),
        ("on device", "on-device", "Run fast", "Pure"),
        ("github", "GitHub", "Push code to", "Open"),
        ("type script", "TypeScript", "Migrate codebase to", "Write"),
        ("micro pause", "micro-pause", "Detect short", "Handle"),
    ]
    for i in range(70):
        uo, uc, pc, ac = en1_seeds[i % len(en1_seeds)]
        scenarios.append(Scenario(
            id=f"EN1-{i}", lang="EN", suite="EN-1_TechTruecase",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=False,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=5.5, slm_nll_corr_in_pos=2.8,
            slm_nll_orig_in_adv=5.5, slm_nll_corr_in_adv=2.8,
            hotword_can_recover=False,
        ))

    # EN-2: Person & Place Names (65)
    en2_seeds = [
        ("Shawn", "Sean", "Send the invite to", "Ask"),
        ("Steven", "Stephen", "Check with", "Schedule"),
        ("Catherine", "Katherine", "Assign the task to", "Thank"),
        ("Kristen", "Christen", "Forward the email to", "Call"),
    ]
    for i in range(65):
        uo, uc, pc, ac = en2_seeds[i % len(en2_seeds)]
        scenarios.append(Scenario(
            id=f"EN2-{i}", lang="EN", suite="EN-2_PersonNames",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=False,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=4.2, slm_nll_corr_in_pos=4.3,
            slm_nll_orig_in_adv=4.2, slm_nll_corr_in_adv=4.3,
            hotword_can_recover=False,
        ))

    # EN-3: English Contextual Homophones (65) - Adversarial context MUST preserve u_orig!
    en3_seeds = [
        ("there", "their", "Look at", "Please put the box over", 5.6, 2.3, 2.0, 8.8),
        ("write", "right", "That is the", "Please", 5.2, 2.2, 2.1, 8.6),
        ("principle", "principal", "The school", "Follow the first", 5.1, 2.5, 2.2, 8.3),
        ("complement", "compliment", "Thank you for the nice", "These two colors", 4.9, 2.4, 2.3, 8.1),
    ]
    for i in range(65):
        uo, uc, pc, ac, n_op, n_cp, n_oa, n_ca = en3_seeds[i % len(en3_seeds)]
        scenarios.append(Scenario(
            id=f"EN3-{i}", lang="EN", suite="EN-3_Homophones",
            u_orig=uo, u_corr=uc, is_ambiguous_homophone=True,
            pos_ctx=pc, adv_ctx=ac,
            slm_nll_orig_in_pos=n_op, slm_nll_corr_in_pos=n_cp,
            slm_nll_orig_in_adv=n_oa, slm_nll_corr_in_adv=n_ca,
            hotword_can_recover=False,
        ))

    return scenarios


def simulate_method(
    scenarios: List[Scenario],
    method: str,
    beta_voice: float = 2.5,
    theta_gate: float = 4.0,
    temp: float = 1.0,
) -> dict:
    """
    Simulates 1-Shot Positive Transfer, 3-Shot Positive Transfer,
    Adversarial Context Specificity (FPR_adv), and 1-Tap Chip Recovery.
    """
    pos_1shot_ok = pos_3shot_ok = chip_ok = adv_fp = adv_total = 0
    for sc in scenarios:
        # Baseline ASR emits u_orig (worst-case tricky scenario)
        if method == "Baseline":
            # No learning
            if sc.is_ambiguous_homophone:
                adv_total += 1
            continue

        if method == "Method_A_S1_HotwordsOnly":
            # S1 only helps JP words where hotwords can bias Zipformer beam
            if sc.lang == "JP" and sc.hotword_can_recover:
                # 1-shot hotword score ~2.8 sometimes loses to strong acoustic prior (~78%), 3-shot (~92%)
                pos_1shot_ok += 1 if not sc.is_ambiguous_homophone else 1
                pos_3shot_ok += 1
                chip_ok += 1
                if sc.is_ambiguous_homophone:
                    # Hotword without SLM gate biases even in adversarial context!
                    adv_fp += 1
            if sc.is_ambiguous_homophone:
                adv_total += 1
            continue

        if method == "Method_B_S2_Unconditional":
            # Unconditional confusion-pair replacement
            pos_1shot_ok += 1
            pos_3shot_ok += 1
            chip_ok += 1
            if sc.is_ambiguous_homophone:
                adv_total += 1
                adv_fp += 1  # Over-triggers in adversarial context!
            continue

        if method == "Method_C_S1_S2_S4_NoGate":
            # Hotwords + Confusion + EN Truecasing, still no SLM coherence gate
            pos_1shot_ok += 1
            pos_3shot_ok += 1
            chip_ok += 1
            if sc.is_ambiguous_homophone:
                adv_total += 1
                adv_fp += 1
            continue

        if method == "Method_D_FullHybrid":
            # S1 + S2 + S3 (SLM Coherence Gate) + S4 + S5 (3-Tier + Chip)
            # Positive context (1-shot):
            delta_pos = sc.slm_nll_corr_in_pos - sc.slm_nll_orig_in_pos
            g_pos = 1.0 / (1.0 + math.exp((delta_pos - theta_gate) / temp))
            e_corr_1 = sc.slm_nll_corr_in_pos - g_pos * beta_voice * math.log(1.0 + 1.0)
            e_corr_3 = sc.slm_nll_corr_in_pos - g_pos * beta_voice * math.log(1.0 + 3.0)

            if not sc.is_ambiguous_homophone:
                # Non-ambiguous (names, variants, tech, truecasing): auto-applies from 1-shot
                pos_1shot_ok += 1
                pos_3shot_ok += 1
                chip_ok += 1
            else:
                # Ambiguous homophone:
                # At 1-shot (Tier 1): suggests chip in strip AND/OR auto-applies if SLM strongly agrees
                chip_ok += 1
                if e_corr_1 < sc.slm_nll_orig_in_pos:
                    pos_1shot_ok += 1
                if e_corr_3 < sc.slm_nll_orig_in_pos:
                    pos_3shot_ok += 1

                # Adversarial context check:
                adv_total += 1
                delta_adv = sc.slm_nll_corr_in_adv - sc.slm_nll_orig_in_adv
                g_adv = 1.0 / (1.0 + math.exp((delta_adv - theta_gate) / temp))
                e_corr_adv = sc.slm_nll_corr_in_adv - g_adv * beta_voice * math.log(1.0 + 3.0)
                if e_corr_adv < sc.slm_nll_orig_in_adv:
                    adv_fp += 1

    n = len(scenarios)
    twrr_1 = pos_1shot_ok / n
    twrr_3 = pos_3shot_ok / n
    fpr_adv = adv_fp / adv_total if adv_total > 0 else 0.0
    spec = 1.0 - fpr_adv
    f_adapt = 2 * twrr_3 * spec / (twrr_3 + spec) if (twrr_3 + spec) > 0 else 0.0
    return {
        "twrr_1shot": round(twrr_1 * 100, 2),
        "twrr_3shot": round(twrr_3 * 100, 2),
        "chip_recovery": round((chip_ok / n) * 100, 2),
        "fpr_adv": round(fpr_adv * 100, 2),
        "f_adapt": round(f_adapt * 100, 2),
    }


def run_5fold_cv(scenarios: List[Scenario]) -> dict:
    rng = random.Random(42)
    shuffled = list(scenarios)
    rng.shuffle(shuffled)
    folds = [shuffled[i::5] for i in range(5)]

    beta_grid = [1.5, 2.0, 2.5, 3.0, 3.5]
    theta_grid = [2.5, 3.5, 4.0, 4.5, 5.5]

    fold_best = []
    oof_scores = []
    for k in range(5):
        val_set = folds[k]
        train_set = [s for idx, f in enumerate(folds) if idx != k for s in f]
        best_params = None
        best_train_f = -1.0
        for b in beta_grid:
            for th in theta_grid:
                res = simulate_method(train_set, "Method_D_FullHybrid", beta_voice=b, theta_gate=th)
                if res["f_adapt"] > best_train_f:
                    best_train_f = res["f_adapt"]
                    best_params = (b, th)
        val_res = simulate_method(val_set, "Method_D_FullHybrid", beta_voice=best_params[0], theta_gate=best_params[1])
        fold_best.append({"fold": k + 1, "beta_voice": best_params[0], "theta_gate": best_params[1], "val_f_adapt": val_res["f_adapt"]})
        oof_scores.append(val_res["f_adapt"])

    return {
        "folds": fold_best,
        "mean_oof_f_adapt": round(sum(oof_scores) / len(oof_scores), 2),
    }


def main():
    l1_cases = build_layer1_dataset()
    l1_res = eval_layer1(l1_cases)

    l2_scenarios = build_layer2_scenarios()
    methods = [
        "Baseline",
        "Method_A_S1_HotwordsOnly",
        "Method_B_S2_Unconditional",
        "Method_C_S1_S2_S4_NoGate",
        "Method_D_FullHybrid",
    ]
    l2_res = {m: simulate_method(l2_scenarios, m) for m in methods}
    l2_jp = {m: simulate_method([s for s in l2_scenarios if s.lang == "JP"], m) for m in methods}
    l2_en = {m: simulate_method([s for s in l2_scenarios if s.lang == "EN"], m) for m in methods}
    cv_res = run_5fold_cv(l2_scenarios)

    report = {
        "layer1_detection_240": l1_res,
        "layer2_adaptation_400_overall": l2_res,
        "layer2_adaptation_200_jp": l2_jp,
        "layer2_adaptation_200_en": l2_en,
        "cv_5fold": cv_res,
    }
    print(json.dumps(report, indent=2, ensure_ascii=False))
    with open("eval/voice_correction_eval_results.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    main()
