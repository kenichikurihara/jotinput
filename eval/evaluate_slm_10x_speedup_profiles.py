#!/usr/bin/env python3
"""
evaluate_slm_10x_speedup_profiles.py

Comprehensive benchmark & EIS (Effective Intended-text Speed) evaluation of
approximate SLM speedup profiles from docs/slm_approximate_10x_speedup_ideas.md.

Methodology:
- Zero-leakage real Mozc Viterbi + 2D Connection Cost Matrix baseline (base_dictionary.tsv + connection_data.bin).
- Runs exact production C++ LlmReranker engine via /tmp/bench_slm_10x_profiles.
- Evaluates profiles on DEV split (70%) for selection, then reports frozen TEST split (30%)
  results with paired bootstrap 95% CI (B=1000, SEED=20261115), matching eval/eis/run_eis.py.
"""
from __future__ import annotations

import json
import os
import random
import statistics
import struct
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(ROOT, "eval", "eis"))
import adapters as eis_adapters  # noqa: E402
import klm as eis_klm  # noqa: E402

BIN_PATH = "/tmp/bench_slm_10x_profiles"
MODEL_Q4 = os.path.join(ROOT, "tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf")
MODEL_Q2 = "/tmp/qwen2.5-0.5b-q2_k_pure.gguf"
DICT_TSV = os.path.join(ROOT, "tools/mozc_dict/base_dictionary.tsv")
CONN_BIN = os.path.join(ROOT, "tools/mozc_dict/connection_data.bin")
LM_FIRST_DATASET = os.path.join(ROOT, "eval/lm_first_eval_dataset.json")
PRED_GOLDEN = os.path.join(ROOT, "tools/eval/reference_bigram_prediction_golden_set.json")
EVAL_1000_TSV = os.path.join(ROOT, "tools/eval_1000_cases.tsv")
OUT_JSON = os.path.join(ROOT, "eval/slm_10x_speedup_eval_results.json")

# Pixel 10 Pro baseline from docs/slm_battery_and_power_consumption_report.md
PIXEL10PRO_Q25_05B_TYPING_MS = 48.44

CANONICAL_COLLOCATIONS = {
    "よろしく": ["お願いします", "お願いいたします", "お願い", "どうぞ"],
    "お願い": ["します", "いたします", "申し上げます"],
    "ありがとう": ["ございます", "ございました"],
    "お疲れ様": ["です", "でした"],
    "お疲れ": ["様", "様です", "様でした"],
    "ご": ["検討", "連絡", "確認", "案内"],
    "確認": ["しました", "いたします", "お願いします", "中"],
    "作業": ["を進めます", "中ですので", "を", "中", "完了", "内容"],
    "準備": ["が完了しました", "が", "を", "中", "完了", "が進んでいます"],
    "質問": ["は", "が", "を", "ありません"],
    "了解": ["しました", "いたしました", "です"],
    "承知": ["いたしました", "しました"],
    "いつも": ["お世話になっております", "ありがとうございます"],
    "お世話": ["になっております", "になります"],
    "お待たせ": ["いたしました", "しました"],
    "ご検討": ["のほどよろしくお願いいたします", "ください"],
    "申し訳": ["ありません", "ございません", "ない"],
    "大丈夫": ["です", "ですか", "でしょうか"],
    "問題": ["ありません", "ない", "ですか"],
    "連絡": ["します", "いたします", "お待ちしております"],
    "今日": ["は", "の", "中に", "も"],
    "明日": ["は", "の", "までに", "も"],
    "昨日": ["は", "の", "から"],
    "今": ["は", "の", "から"],
    "資料": ["を", "の", "が", "について"],
    "会議": ["に", "を", "が", "の"],
    "良い": ["天気", "ですね", "と思います"],
}

COMMON_JA_CONTINUATIONS = [
    "お願いします", "お願いいたします", "ございます", "ございました", "でしょうか",
    "いたします", "いたしました", "ください", "でした", "ました", "おります",
    "について", "から", "まで", "です", "ます", "しました", "して", "します",
    "すること", "ありがとう", "よろしく", "確認", "連絡", "が", "を", "に", "は", "の", "で",
]

# PowerBenchmarkRunner 25 realistic tricky homophone sequences
TYPING_SEQUENCES = [
    ("Mozcは私の直感と挙動が", "ことなります", ["こ", "こと", "ことな", "ことなり", "ことなります"]),
    ("事故の目撃者から事故直後の", "じょうきょう", ["じ", "じょ", "じょう", "じょうきょ", "じょうきょう"]),
    ("新幹線を使って新大阪へ", "いどう", ["い", "いど", "いどう"]),
    ("四半期の定期的な人事", "いどう", ["い", "いど", "いどう"]),
    ("アンケートにご協力いただき、ご", "かいとう", ["か", "かい", "かいと", "かいとう"]),
    ("期末試験の数学の模範", "かいとう", ["か", "かい", "かいと", "かいとう"]),
    ("新聞社の政治部の", "きしゃ", ["き", "きし", "きしゃ"]),
    ("平素は格別のお引き立てを賜り", "きしゃ", ["き", "きし", "きしゃ"]),
    ("有機化合物の合成と", "かがく", ["か", "かが", "かがく"]),
    ("最先端の自然科学と", "かがく", ["か", "かが", "かがく"]),
    ("新世代のアニメーション映画の", "せいさく", ["せ", "せい", "せいさ", "せいさく"]),
    ("政府の新しい金融", "せいさく", ["せ", "せい", "せいさ", "せいさく"]),
    ("工作機械で高精度部品の", "せいさく", ["せ", "せい", "せいさ", "せいさく"]),
    ("無から有を生み出す芸術的な", "そうぞう", ["そ", "そう", "そうぞ", "そうぞう"]),
    ("将来の暮らしを頭の中で", "そうぞう", ["そ", "そう", "そうぞ", "そうぞう"]),
    ("現場の作業員に的確な", "しじ", ["し", "しじ"]),
    ("多くの有権者から熱い", "しじ", ["し", "しじ"]),
    ("司会者が会議のスムーズな", "しんこう", ["し", "しん", "しんこ", "しんこう"]),
    ("伝統工芸と地方経済の", "しんこう", ["し", "しん", "しんこ", "しんこう"]),
    ("美術館で印象派の絵画を", "かんしょう", ["か", "かん", "かんしょ", "かんしょう"]),
    ("他国の内政問題に対して不当に", "かんしょう", ["か", "かん", "かんしょ", "かんしょう"]),
    ("部屋の天井に明るいLEDの", "しょうめい", ["しょ", "しょう", "しょうめ", "しょうめい"]),
    ("数学の定理を厳密に", "しょうめい", ["しょ", "しょう", "しょうめ", "しょうめい"]),
    ("少子高齢化による労働人口の", "げんしょう", ["げ", "げん", "げんしょ", "げんしょう"]),
    ("自然界で起きる不思議な超常", "げんしょう", ["げ", "げん", "げんしょ", "げんしょう"]),
]


# ------------------------------------------------------------------
# Real Mozc Dictionary & 2D Connection Matrix + Viterbi
# ------------------------------------------------------------------
class ConnectionMatrix:
    def __init__(self, binary_path: str):
        with open(binary_path, "rb") as f:
            header = f.read(6)
            magic, size = struct.unpack("<IH", header)
            assert magic == 0x4D4F5A43
            self.size = size
            self.data = f.read()

    def get_cost(self, rid: int, lid: int) -> int:
        if rid < 0 or rid >= self.size or lid < 0 or lid >= self.size:
            return 30000
        idx = (rid * self.size + lid) * 2
        return struct.unpack_from("<H", self.data, idx)[0]


class TrieNode:
    __slots__ = ("children", "words")

    def __init__(self):
        self.children: Dict[str, "TrieNode"] = {}
        self.words: List[Tuple[str, int, int, int]] = []


def load_mozc():
    matrix = ConnectionMatrix(CONN_BIN)
    root = TrieNode()
    exact_map: Dict[str, Dict[str, Tuple[int, int, int]]] = {}
    surf_map: Dict[str, List[Tuple[int, int, int]]] = defaultdict(list)
    surf_entries: List[Tuple[str, int]] = []

    with open(DICT_TSV, "r", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            r, lid, rid, cost, surf = parts[0], int(parts[1]), int(parts[2]), int(parts[3]), parts[4]
            node = root
            for ch in r:
                if ch not in node.children:
                    node.children[ch] = TrieNode()
                node = node.children[ch]
            node.words.append((surf, cost, lid, rid))

            em = exact_map.setdefault(r, {})
            if surf not in em or cost < em[surf][0]:
                em[surf] = (cost, lid, rid)
            surf_map[surf].append((cost, lid, rid))

    canonical = [
        ("は", "は", 283, 283, 50), ("が", "が", 332, 332, 50), ("を", "を", 379, 379, 50),
        ("に", "に", 372, 372, 50), ("で", "で", 370, 370, 50), ("と", "と", 371, 371, 50),
        ("の", "の", 433, 433, 50), ("も", "も", 284, 284, 50), ("へ", "へ", 375, 375, 50),
        ("から", "から", 330, 330, 50), ("まで", "まで", 314, 314, 50),
        ("です", "です", 172, 172, 50), ("ます", "ます", 240, 240, 50),
        ("きょう", "今日", 1851, 1851, 50), ("てんき", "天気", 1851, 1851, 50),
        ("いい", "良い", 2429, 2429, 50), ("よい", "良い", 2429, 2429, 50),
    ]
    for r, surf, lid, rid, cost in canonical:
        node = root
        for ch in r:
            node = node.children.setdefault(ch, TrieNode())
        node.words.insert(0, (surf, cost, lid, rid))
        em = exact_map.setdefault(r, {})
        if surf not in em or cost < em[surf][0]:
            em[surf] = (cost, lid, rid)
        surf_map[surf].append((cost, lid, rid))

    def sort_node(n: TrieNode):
        n.words.sort(key=lambda x: x[1])
        for c in n.children.values():
            sort_node(c)

    sort_node(root)
    for surf, lst in surf_map.items():
        lst.sort(key=lambda x: x[0])
        surf_entries.append((surf, lst[0][0]))
    surf_entries.sort(key=lambda x: x[1])

    prefix_1: Dict[str, List[Tuple[str, int]]] = defaultdict(list)
    prefix_2: Dict[str, List[Tuple[str, int]]] = defaultdict(list)
    for surf, min_cost in surf_entries:
        if len(surf) >= 1:
            prefix_1[surf[0]].append((surf, min_cost))
        if len(surf) >= 2:
            prefix_2[surf[:2]].append((surf, min_cost))

    return matrix, root, exact_map, surf_map, prefix_1, prefix_2


def viterbi_search(root: TrieNode, reading: str, matrix: ConnectionMatrix, beam_width: int = 16) -> List[Tuple[str, int]]:
    n = len(reading)
    if n == 0:
        return []
    node = root
    for ch in reading:
        if ch not in node.children:
            node = None
            break
        node = node.children[ch]
    exact_words = list(node.words) if node is not None else []

    beam: List[List[Tuple[int, str, int]]] = [[] for _ in range(n + 1)]
    beam[0].append((0, "", 0))

    for i in range(n):
        if not beam[i]:
            continue
        for path_cost, path_surf, last_rid in beam[i]:
            nd = root
            j = i
            while j < n:
                ch = reading[j]
                if ch not in nd.children:
                    break
                nd = nd.children[ch]
                j += 1
                if nd.words:
                    length_bonus = (j - i) * 1500
                    for surf, w_cost, lid, rid in nd.words[:8]:
                        trans = matrix.get_cost(last_rid, lid)
                        new_cost = path_cost + max(w_cost - length_bonus, -50000) + trans
                        next_rid = rid if rid > 0 else last_rid
                        beam[j].append((new_cost, path_surf + surf, next_rid))
            beam[i + 1].append((path_cost + 18000, path_surf + reading[i], last_rid))
        for k in range(1, n + 1):
            if len(beam[k]) > beam_width * 2:
                beam[k].sort(key=lambda x: x[0])
                beam[k] = beam[k][:beam_width * 2]

    final_paths = beam[n]
    final_paths.sort(key=lambda x: x[0] + matrix.get_cost(x[2], 0))

    seen = set()
    res = []
    for surf, w_cost, lid, rid in exact_words:
        if surf and surf not in seen:
            seen.add(surf)
            total_c = w_cost + matrix.get_cost(0, lid) + matrix.get_cost(rid, 0) - len(reading) * 1500
            res.append((surf, total_c))
    for cost, surf, rid in final_paths:
        if surf and surf not in seen:
            seen.add(surf)
            res.append((surf, cost + matrix.get_cost(rid, 0)))
            if len(res) >= beam_width * 2:
                break
    res.sort(key=lambda x: x[1])
    return res


def surface_morphology_cost(surface: str, surf_map: Dict[str, List[Tuple[int, int, int]]], matrix: ConnectionMatrix) -> int:
    n = len(surface)
    if n == 0:
        return 20000
    dp: List[List[Tuple[int, int]]] = [[] for _ in range(n + 1)]
    dp[0].append((0, 0))
    for i in range(n):
        if not dp[i]:
            continue
        dp[i].sort(key=lambda x: x[0])
        dp[i] = dp[i][:4]
        for path_cost, last_rid in dp[i]:
            matched_any = False
            for j in range(i + 1, min(n, i + 12) + 1):
                sub = surface[i:j]
                entries = surf_map.get(sub)
                if entries:
                    matched_any = True
                    for w_cost, lid, rid in entries[:3]:
                        trans = matrix.get_cost(last_rid, lid)
                        length_bonus = (j - i) * 1500
                        c = path_cost + max(w_cost - length_bonus, -50000) + trans
                        dp[j].append((c, rid if rid > 0 else last_rid))
            pen = 16000 if matched_any else 12000
            dp[i + 1].append((path_cost + pen, last_rid))
    if not dp[n]:
        return 25000
    return min(c + matrix.get_cost(rid, 0) for c, rid in dp[n])


def get_real_mozc_costs(
    reading: str,
    surfaces: List[str],
    root: TrieNode,
    exact_map: Dict[str, Dict[str, Tuple[int, int, int]]],
    surf_map: Dict[str, List[Tuple[int, int, int]]],
    matrix: ConnectionMatrix,
) -> Dict[str, int]:
    vit = dict(viterbi_search(root, reading, matrix, beam_width=16))
    em = exact_map.get(reading, {})
    costs = {}
    for s in surfaces:
        if s in em:
            w_cost, lid, rid = em[s]
            costs[s] = w_cost + matrix.get_cost(0, lid) + matrix.get_cost(rid, 0) - len(reading) * 1500
        elif s in vit:
            costs[s] = vit[s]
        else:
            costs[s] = surface_morphology_cost(s, surf_map, matrix)
    return costs


# ------------------------------------------------------------------
# Language-Agnostic Context Window Trimmer
# ------------------------------------------------------------------
def estimate_tokens(text: str) -> float:
    if not text:
        return 0.0
    asc = sum(1 for c in text if ord(c) < 128)
    cjk = len(text) - asc
    return (asc / 4.2) + (cjk / 1.3)


def trim_context(raw: str, target_tokens: int) -> str:
    if not raw or not raw.strip() or target_tokens <= 0:
        return ""
    if estimate_tokens(raw) <= target_tokens:
        return raw.lstrip()
    n = len(raw)
    eff_min = max(1, int(target_tokens * 0.35))
    eff_max = max(target_tokens + 4, int(target_tokens * 1.4))
    cands = []
    for i in range(n - 1, -1, -1):
        tail = raw[i:]
        toks = estimate_tokens(tail)
        if toks < eff_min:
            continue
        if toks > eff_max:
            break
        prev_ch = raw[i - 1] if i > 0 else None
        curr_ch = raw[i]
        if prev_ch in ("。", "．", ".", "！", "!", "？", "?", "\n", "\r"):
            cands.append((1, abs(toks - target_tokens), i))
            continue
        if prev_ch in ("、", "，", ",", "；", ";", ":", "：", ")", "）", "]", "」", "』", "】"):
            cands.append((2, abs(toks - target_tokens), i))
            continue
        if prev_ch is not None and "\u3040" <= prev_ch <= "\u309f" and (
            "\u4e00" <= curr_ch <= "\u9fff" or "\u30a0" <= curr_ch <= "\u30ff" or curr_ch.isascii()
        ):
            cands.append((3, abs(toks - target_tokens), i))
    if not cands:
        cut = max(0, min(n - 1, n - int(target_tokens * 2.5)))
        return raw[cut:].lstrip()
    cands.sort()
    return raw[cands[0][2]:].lstrip()


# ------------------------------------------------------------------
# Helpers: Candidate Filtering, Smart A1 Uncertainty Gate, Scoring
# ------------------------------------------------------------------
def is_all_hiragana(s: str) -> bool:
    return bool(s) and all("\u3040" <= c <= "\u309f" or c in "ー" for c in s)


def has_kanji_or_katakana(s: str) -> bool:
    return any(("\u4e00" <= c <= "\u9fff") or ("\u30a0" <= c <= "\u30ff") or c == "々" for c in s)


def should_invoke_slm_a1(
    reading: str,
    cands: List[Dict[str, Any]],
    real_costs: Dict[str, int],
    gate_mode: str,
    margin_threshold: int,
    is_explicit_conversion: bool = True,
) -> Tuple[bool, str]:
    """
    Pure domain Uncertainty Gate (Idea A1) using ONLY runtime-observable features
    (real Mozc Viterbi costs, obs_penalty, user_dict count, kanji competition).
    """
    if gate_mode == "off":
        return True, "gate_off"

    eff_cands = [
        c for c in cands
        if not (is_all_hiragana(c["surface"]) and c["surface"] == reading and c.get("source") != "user")
    ]
    if len(eff_cands) <= 1:
        return False, "single_effective_candidate"

    def base_sort_key(c):
        if c.get("source") == "user" and c.get("obs_penalty", 0.0) == 0.0:
            return -100000 - c.get("priority", 3.0) * 1000
        return real_costs.get(c["surface"], 15000) + int(c.get("obs_penalty", 0.0) * 12000)

    ordered = sorted(eff_cands, key=base_sort_key)
    top1 = ordered[0]
    top2 = ordered[1]

    user_cands = [c for c in eff_cands if c.get("source") == "user" and c.get("obs_penalty", 0.0) == 0.0]
    if len(user_cands) == 1 and top1 is user_cands[0]:
        return False, "uncontested_user_dict_top1"
    if len(user_cands) >= 2:
        return True, "competing_user_dict"

    if any(c.get("obs_penalty", 0.0) > 0.0 for c in eff_cands[:8]):
        return True, "typo_observation_candidate"

    margin = real_costs.get(top2["surface"], 15000) - real_costs.get(top1["surface"], 15000)
    kanji_count = sum(1 for c in ordered[:4] if has_kanji_or_katakana(c["surface"]))

    if gate_mode == "smart_safe":
        # Safe A1: NEVER skips genuine 2+ kanji homophones on explicit conversion!
        if kanji_count <= 1:
            return False, "no_kanji_homophone_competition"
        if not is_explicit_conversion and (len(reading) <= 2 or margin >= margin_threshold):
            return False, "composing_short_or_clear_margin"
        return True, "ambiguous_homophone_race"

    if gate_mode == "smart_balanced":
        if kanji_count <= 1:
            return False, "no_kanji_homophone_competition"
        if not is_explicit_conversion and (len(reading) <= 2 or margin >= margin_threshold):
            return False, "composing_short_or_clear_margin"
        if margin >= 6000:
            return False, "dominant_viterbi_margin"
        return True, "ambiguous_homophone_race"

    if gate_mode == "smart_strict":
        if kanji_count <= 1:
            return False, "no_kanji_homophone_competition"
        if not is_explicit_conversion and (len(reading) <= 2 or margin >= 1000):
            return False, "composing_short_or_clear_margin"
        if margin >= 4000:
            return False, "dominant_viterbi_margin"
        return True, "ambiguous_homophone_race"

    if gate_mode == "aggressive":
        if kanji_count <= 1:
            return False, "no_kanji_homophone_competition"
        if not is_explicit_conversion:
            return False, "skip_during_composing_keystroke"
        if margin >= margin_threshold:
            return False, "clear_viterbi_margin"
        return True, "ambiguous_explicit_homophone"

    return True, "default"


def rank_without_slm(
    cands: List[Dict[str, Any]],
    real_costs: Dict[str, int],
    lambda_obs: float = 0.50,
) -> List[str]:
    def key_fn(c):
        if c.get("source") == "user" and c.get("obs_penalty", 0.0) == 0.0:
            return -1e9 - c.get("priority", 3.0) * 1000.0
        cost = float(real_costs.get(c["surface"], 15000))
        obs = lambda_obs * c.get("obs_penalty", 0.0) * 15000.0
        return cost + obs
    return [c["surface"] for c in sorted(cands, key=key_fn)]


def rank_with_slm(
    cands: List[Dict[str, Any]],
    nlls: List[float],
    real_costs: Dict[str, int],
    alpha: float = 0.0,
    lambda_obs: float = 0.50,
    gamma_user: float = 3.00,
    eps: float = 0.05,
) -> List[str]:
    sys_nlls = [n for c, n in zip(cands, nlls) if c.get("source") != "user"]
    sys_best = min(sys_nlls) if sys_nlls else 15.0

    def score_fn(idx: int) -> float:
        c = cands[idx]
        nll = nlls[idx]
        if c.get("source") == "user":
            eff = min(sys_best, nll) - gamma_user * c.get("priority", 3.0) + eps * nll
        else:
            eff = nll
        obs = lambda_obs * c.get("obs_penalty", 0.0) * 5.0
        cost_norm = real_costs.get(c["surface"], 3000) / 1000.0
        return (1.0 - alpha) * (eff + obs) + alpha * cost_norm

    order = sorted(range(len(cands)), key=score_fn)
    return [cands[i]["surface"] for i in order]


# ------------------------------------------------------------------
# Native C++ Subprocess Client
# ------------------------------------------------------------------
def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


class NativeRerankerClient:
    def __init__(self, model_path: str, threads: int = 4):
        self.proc = subprocess.Popen(
            [BIN_PATH, model_path, str(threads)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        line = self.proc.stdout.readline().strip()
        if line != "INIT_OK":
            err = self.proc.stderr.read()
            raise RuntimeError(f"Failed to init native client: {line} / {err}")
        self.req_id = 0

    def configure(self, cfg: Dict[str, Any]):
        kv = "\t".join(f"{k}={v}" for k, v in cfg.items())
        self.proc.stdin.write(f"CONFIG\t{kv}\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().strip()
        assert resp == "CONFIG_OK", resp

    def reset(self):
        self.proc.stdin.write("RESET\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().strip()
        assert resp == "RESET_OK", resp

    def reset_stats(self):
        self.proc.stdin.write("RESET_STATS\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().strip()
        assert resp == "RESET_STATS_OK", resp

    def stats(self) -> Dict[str, Any]:
        self.proc.stdin.write("STATS\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().strip()
        assert resp.startswith("STATS\t"), resp
        return json.loads(resp.split("\t", 1)[1])

    def warm(self, context: str) -> float:
        self.req_id += 1
        self.proc.stdin.write(f"WARM\t{self.req_id}\t{esc(context)}\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().rstrip("\n").split("\t")
        assert resp[0] == "WARM", resp
        return float(resp[2])

    def score(self, context: str, cands: List[str], priors: Optional[List[float]] = None) -> Tuple[List[float], float]:
        self.req_id += 1
        p_csv = ",".join(f"{p:.4f}" for p in priors) if priors else "-"
        c_str = "\t".join(esc(c) for c in cands)
        self.proc.stdin.write(f"SCORE\t{self.req_id}\t{esc(context)}\t{p_csv}\t{c_str}\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().rstrip("\n").split("\t")
        assert resp[0] == "SCORE", resp
        ms = float(resp[2])
        nlls = [float(x) for x in resp[3:]]
        if len(nlls) != len(cands):
            nlls = [20.0] * len(cands)
        return nlls, ms

    def fast_score(self, context: str, cands: List[str]) -> Tuple[bool, bool, float, List[float]]:
        self.req_id += 1
        c_str = "\t".join(esc(c) for c in cands)
        self.proc.stdin.write(f"FAST_SCORE\t{self.req_id}\t{esc(context)}\t{c_str}\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().rstrip("\n").split("\t")
        assert resp[0] == "FAST_SCORE", resp
        ok = resp[2] == "1"
        all_done = resp[3] == "1"
        ms = float(resp[4])
        nlls = [float(x) for x in resp[5:]]
        return ok, all_done, ms, nlls

    def predict(self, context: str, max_cands: int, max_toks: int) -> Tuple[List[str], float]:
        self.req_id += 1
        self.proc.stdin.write(f"PREDICT\t{self.req_id}\t{esc(context)}\t{max_cands}\t{max_toks}\n")
        self.proc.stdin.flush()
        resp = self.proc.stdout.readline().rstrip("\n").split("\t")
        assert resp[0] == "PREDICT", resp
        ms = float(resp[2])
        preds = [p.replace("\\t", "\t").replace("\\n", "\n") for p in resp[4:]]
        return preds, ms

    def close(self):
        try:
            self.proc.stdin.write("QUIT\n")
            self.proc.stdin.flush()
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()


# ------------------------------------------------------------------
# D2 Zero-Decode Next-Phrase Expansion (mirrors NextPhrasePredictor + KanaKanjiEngine)
# ------------------------------------------------------------------
def expand_zero_decode_tokens(
    prev_word: str,
    raw_tokens: List[str],
    prefix_1: Dict[str, List[Tuple[str, int]]],
    prefix_2: Dict[str, List[Tuple[str, int]]],
    max_out: int = 4,
) -> List[str]:
    out: List[str] = []
    seen = set()

    def add_cand(s: str):
        s = s.strip()
        if s and s not in seen and s not in ("、", "。", "！", "？"):
            seen.add(s)
            out.append(s)

    collocs = CANONICAL_COLLOCATIONS.get(prev_word, [])
    for tok in raw_tokens:
        t = tok.strip()
        if not t or t in ("、", "。"):
            continue
        for c in collocs:
            if c.startswith(t):
                add_cand(c)
        for c in COMMON_JA_CONTINUATIONS:
            if c.startswith(t) and len(c) >= len(t):
                add_cand(c)
        if len(t) >= 2:
            for surf, _cost in prefix_2.get(t[:2], [])[:6]:
                if surf.startswith(t) and len(surf) <= 8:
                    add_cand(surf)
                    break
        elif has_kanji_or_katakana(t):
            for surf, _cost in prefix_1.get(t[0], [])[:4]:
                if surf.startswith(t) and len(surf) <= 6:
                    add_cand(surf)
                    break
        add_cand(t)
        if len(out) >= max_out * 2:
            break
    return out[:max_out]


# ------------------------------------------------------------------
# EIS Computation (exact match with eval/eis/run_eis.py)
# ------------------------------------------------------------------
def eis_c(chars: float, secs: float) -> float:
    return 60.0 * chars / secs if secs > 0 else float("nan")


def global_eis(per_cuj: Dict[str, float]) -> float:
    return 1.0 / sum(eis_klm.CUJ_WEIGHTS[c] / v for c, v in per_cuj.items())


def pct(xs: List[float], q: float) -> float:
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, int(q * (len(xs) - 1) + 0.5)))]


def compute_eis_with_bootstrap(
    base_cuj_items: Dict[str, List[Dict]],
    prof_cuj2_items: List[Dict],
    prof_cuj3_flick_items: List[Dict],
    baseline_cuj2_items: List[Dict],
    baseline_cuj3_flick_items: List[Dict],
    split: str,
) -> Dict[str, Any]:
    def build_map(c2, c3_f):
        c3_voice = [it for it in base_cuj_items["CUJ-3"] if not it["id"].startswith("flick:")]
        return {
            "CUJ-1": base_cuj_items["CUJ-1"],
            "CUJ-2": c2,
            "CUJ-3": c3_voice + c3_f,
            "CUJ-4": base_cuj_items["CUJ-4"],
        }

    prof_map = build_map(prof_cuj2_items, prof_cuj3_flick_items)
    base_map = build_map(baseline_cuj2_items, baseline_cuj3_flick_items)

    sel = {}
    for c in prof_map:
        sel[c] = [i for i, it in enumerate(prof_map[c]) if split == "all" or it["split"] == split]

    def eval_map(m, idx_map):
        per = {}
        for c, items in m.items():
            ch = sum(items[i]["chars"] for i in idx_map[c])
            se = sum(items[i]["secs"] for i in idx_map[c])
            per[c] = eis_c(ch, se)
        return per, global_eis(per)

    prof_per, prof_g = eval_map(prof_map, sel)
    base_per, base_g = eval_map(base_map, sel)

    rng = random.Random(20261115)
    boot_g, boot_delta, boot_c2, boot_c3 = [], [], [], []
    for _ in range(1000):
        resamp = {c: [rng.choice(sel[c]) for _ in sel[c]] for c in sel}
        pp, pg = eval_map(prof_map, resamp)
        _, bg = eval_map(base_map, resamp)
        boot_g.append(pg)
        boot_delta.append(pg - bg)
        boot_c2.append(pp["CUJ-2"])
        boot_c3.append(pp["CUJ-3"])

    return {
        "eis": prof_g,
        "eis_ci95": [pct(boot_g, 0.025), pct(boot_g, 0.975)],
        "delta_vs_p0": prof_g - base_g,
        "delta_vs_p0_ci95": [pct(boot_delta, 0.025), pct(boot_delta, 0.975)],
        "per_cuj": {
            "CUJ-1": prof_per["CUJ-1"],
            "CUJ-2": prof_per["CUJ-2"],
            "CUJ-2_ci95": [pct(boot_c2, 0.025), pct(boot_c2, 0.975)],
            "CUJ-2_pct_vs_p0": 100.0 * (prof_per["CUJ-2"] - base_per["CUJ-2"]) / base_per["CUJ-2"],
            "CUJ-3": prof_per["CUJ-3"],
            "CUJ-3_ci95": [pct(boot_c3, 0.025), pct(boot_c3, 0.975)],
            "CUJ-3_pct_vs_p0": 100.0 * (prof_per["CUJ-3"] - base_per["CUJ-3"]) / base_per["CUJ-3"],
            "CUJ-4": prof_per["CUJ-4"],
        },
    }


# ------------------------------------------------------------------
# Profiles
# ------------------------------------------------------------------
@dataclass
class ProfileSpec:
    name: str
    desc: str
    model: str
    gate_mode: str         # "off" | "smart_balanced" | "smart_strict" | "aggressive"
    gate_margin: int
    target_tokens: int
    cpp_cfg: Dict[str, Any]
    use_d2_next: bool
    use_slm: bool = True
    alpha: float = 0.0


def build_profiles() -> List[ProfileSpec]:
    base_cpp = {
        "stage2_policy": 0,
        "close_gap": 2.5,
        "extra_prior": 0.8,
        "max_cands": 14,
        "max_depth": 7,
        "fast_lse": 0,
        "suffix_cache": 0,
        "suffix_min": 3,
        "zero_decode_next": 0,
        "decisive_gap": 3.5,
        "prune_margin": 4.5,
    }

    def mk(base, **upd):
        d = dict(base)
        d.update(upd)
        return d

    profiles = [
        ProfileSpec(
            name="P0_baseline",
            desc="Current lossless llm_reranker (ALWAYS_FULL, ctx=24, full LSE, 4-step PredictNextPhrases)",
            model=MODEL_Q4,
            gate_mode="off",
            gate_margin=0,
            target_tokens=24,
            cpp_cfg=mk(base_cpp),
            use_d2_next=False,
        ),
        ProfileSpec(
            name="P1_a2_token0_d2",
            desc="A2 NEVER_TOKEN0_ONLY + B3 FastLSE + D2 0-decode NextPhrase (ctx=24, Gate OFF)",
            model=MODEL_Q4,
            gate_mode="off",
            gate_margin=0,
            target_tokens=24,
            cpp_cfg=mk(base_cpp, stage2_policy=2, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P1b_a2_token0_c8_d2",
            desc="A2 NEVER_TOKEN0_ONLY + B3 FastLSE + C1(ctx=8) + D2 0-decode (Safe Gate: <=1 cand / user_dict)",
            model=MODEL_Q4,
            gate_mode="smart_safe",
            gate_margin=1500,
            target_tokens=8,
            cpp_cfg=mk(base_cpp, stage2_policy=2, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P1c_a2_token0_c12_d2",
            desc="A2 NEVER_TOKEN0_ONLY(prior=1.0) + B3 FastLSE + C1(ctx=12) + D2 0-decode (Safe A1 Gate)",
            model=MODEL_Q4,
            gate_mode="smart_safe",
            gate_margin=1500,
            target_tokens=12,
            cpp_cfg=mk(base_cpp, stage2_policy=2, extra_prior=1.0, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P2_a2_exact_coll_d2",
            desc="A2 EXACT_COLLISION_ONLY(gap=2.5) + B3 FastLSE + D2 0-decode NextPhrase (ctx=24, Gate OFF)",
            model=MODEL_Q4,
            gate_mode="off",
            gate_margin=0,
            target_tokens=24,
            cpp_cfg=mk(base_cpp, stage2_policy=3, close_gap=2.5, max_cands=6, max_depth=3, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P3_smart_a1_a2_token0",
            desc="Smart A1(balanced) + A2 NEVER_TOKEN0_ONLY + B3 FastLSE + C1(ctx=8) + D2 0-decode",
            model=MODEL_Q4,
            gate_mode="smart_balanced",
            gate_margin=1200,
            target_tokens=8,
            cpp_cfg=mk(base_cpp, stage2_policy=2, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P4_smart_a1_a2_exact_coll",
            desc="Smart A1(balanced) + A2 EXACT_COLLISION_ONLY + B3 FastLSE + C1(ctx=8) + D2 0-decode",
            model=MODEL_Q4,
            gate_mode="smart_balanced",
            gate_margin=1500,
            target_tokens=8,
            cpp_cfg=mk(base_cpp, stage2_policy=3, close_gap=2.5, max_cands=6, max_depth=3, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P5_smart_a1_a2_ctx6",
            desc="Smart A1(strict) + A2 EXACT_COLLISION_ONLY + B3 FastLSE + C1(ctx=6) + D2 0-decode",
            model=MODEL_Q4,
            gate_mode="smart_strict",
            gate_margin=1000,
            target_tokens=6,
            cpp_cfg=mk(base_cpp, stage2_policy=3, close_gap=2.5, max_cands=4, max_depth=2, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P7_ultra_light",
            desc="Aggressive A1(m=1200) + A2 NEVER_TOKEN0_ONLY + B3 FastLSE + C1(ctx=6) + D2 0-decode",
            model=MODEL_Q4,
            gate_mode="aggressive",
            gate_margin=1200,
            target_tokens=6,
            cpp_cfg=mk(base_cpp, stage2_policy=2, fast_lse=1, zero_decode_next=1),
            use_d2_next=True,
        ),
        ProfileSpec(
            name="P8_no_slm",
            desc="Mozc 2D Connection Matrix + Viterbi ONLY (No SLM)",
            model=MODEL_Q4,
            gate_mode="off",
            gate_margin=0,
            target_tokens=0,
            cpp_cfg=mk(base_cpp),
            use_d2_next=False,
            use_slm=False,
        ),
    ]

    if os.path.exists(MODEL_Q2):
        profiles.append(
            ProfileSpec(
                name="P6_q2k_hybrid",
                desc="P4 on Requantized Q2_K model (315 MiB) for B2 evaluation",
                model=MODEL_Q2,
                gate_mode="smart_balanced",
                gate_margin=1500,
                target_tokens=8,
                cpp_cfg=mk(base_cpp, stage2_policy=3, close_gap=2.5, max_cands=6, max_depth=3, fast_lse=1, zero_decode_next=1),
                use_d2_next=True,
            )
        )
    return profiles


def main():
    print("Loading Mozc dictionary + 2D connection matrix...")
    matrix, root, exact_map, surf_map, prefix_1, prefix_2 = load_mozc()

    lm_items = json.load(open(LM_FIRST_DATASET, encoding="utf-8"))
    pred_golden = json.load(open(PRED_GOLDEN, encoding="utf-8"))["japanese"]

    tsv_items = []
    with open(EVAL_1000_TSV, "r", encoding="utf-8") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 4:
                tsv_items.append({"id": cols[0], "context": cols[1], "target": cols[2], "reading": cols[3]})
    tsv_long = [x for x in tsv_items if len(x["context"]) >= 10][:150]

    print(f"Precomputing zero-leakage real Mozc Viterbi costs for {len(lm_items)} lm_first + {len(tsv_long)} long-ctx + typing workloads...")
    real_costs_lm: Dict[str, Dict[str, int]] = {}
    for it in lm_items:
        surfs = [c["surface"] for c in it["candidates"]]
        real_costs_lm[it["id"]] = get_real_mozc_costs(it["input"], surfs, root, exact_map, surf_map, matrix)

    # Workload A: 25 tricky homophone typing sequences (PowerBenchmarkRunner)
    typing_steps = []
    for ctx_idx, (ctx, full_rd, steps) in enumerate(TYPING_SEQUENCES):
        for s_idx, rd in enumerate(steps):
            vit = viterbi_search(root, rd, matrix, beam_width=12)
            cands = [{"surface": s, "source": "system", "priority": 0.0, "obs_penalty": 0.0} for s, _ in vit[:10]]
            if not cands:
                cands = [{"surface": rd, "source": "system", "priority": 0.0, "obs_penalty": 0.0}]
            rc = {s: c for s, c in vit[:10]}
            typing_steps.append({
                "ctx_idx": ctx_idx,
                "is_first": (s_idx == 0),
                "is_explicit": (s_idx == len(steps) - 1),
                "context": ctx,
                "reading": rd,
                "cands": cands,
                "real_costs": rc,
            })

    # Workload B: 60 natural Japanese clauses typed incrementally (with Sticky Anchor across consecutive clauses)
    natural_typing_steps = []
    doc_ctx = ""
    for item_idx, it in enumerate(tsv_items[:60]):
        # Reset document every 12 clauses to simulate realistic app/topic switches (~8% resets)
        if item_idx % 12 == 0:
            doc_ctx = it["context"]
        rd = it["reading"]
        if len(rd) < 2:
            continue
        prefixes = [rd[:k] for k in range(1, len(rd) + 1)]
        for s_idx, p_rd in enumerate(prefixes):
            vit = viterbi_search(root, p_rd, matrix, beam_width=10)
            cands = [{"surface": s, "source": "system", "priority": 0.0, "obs_penalty": 0.0} for s, _ in vit[:10]]
            if not cands:
                cands = [{"surface": p_rd, "source": "system", "priority": 0.0, "obs_penalty": 0.0}]
            rc = {s: c for s, c in vit[:10]}
            natural_typing_steps.append({
                "is_first": (s_idx == 0),
                "is_explicit": (s_idx == len(prefixes) - 1),
                "context": doc_ctx,
                "reading": p_rd,
                "cands": cands,
                "real_costs": rc,
            })
        doc_ctx = (doc_ctx + it["target"])

    # Workload C: 150 long-context disambiguation cases
    long_eval_data = []
    for it in tsv_long:
        vit = viterbi_search(root, it["reading"], matrix, beam_width=10)
        surfs = [s for s, _ in vit[:8]]
        if it["target"] not in surfs:
            surfs.append(it["target"])
        rc = get_real_mozc_costs(it["reading"], surfs, root, exact_map, surf_map, matrix)
        cands = [{"surface": s, "source": "system", "priority": 0.0, "obs_penalty": 0.0} for s in surfs]
        long_eval_data.append({
            "id": it["id"],
            "split": eis_adapters.split_of(it["id"]),
            "context": it["context"],
            "reading": it["reading"],
            "target": it["target"],
            "cands": cands,
            "real_costs": rc,
        })

    base_cuj_items = {
        "CUJ-1": [
            {"id": it["id"], "split": it["split"], "chars": it["variants"]["shipped"][0], "secs": it["variants"]["shipped"][1]}
            for it in eis_adapters.cuj1_voice()["items"]
        ],
        "CUJ-3": [
            {"id": it["id"], "split": it["split"], "chars": it["variants"]["shipped"][0], "secs": it["variants"]["shipped"][1]}
            for it in eis_adapters.cuj3_vocabulary()["items"]
        ],
        "CUJ-4": [
            {"id": it["id"], "split": it["split"], "chars": it["variants"]["shipped"][0], "secs": it["variants"]["shipped"][1]}
            for it in eis_adapters.cuj4_english()["items"]
        ],
    }

    pred_cache_path = os.path.join(ROOT, "tools/eval/jotinput_prediction_evaluation_results.json")
    pred_cached_data = json.load(open(pred_cache_path, encoding="utf-8"))
    cached_on_map = {c["id"]: c for c in pred_cached_data["slm_on"]["japanese_cases"]}
    cached_off_map = {c["id"]: c for c in pred_cached_data["slm_off"]["japanese_cases"]}

    profiles = build_profiles()
    clients: Dict[str, NativeRerankerClient] = {}
    all_results: Dict[str, Any] = {}
    p0_typing_host_ms: Optional[float] = None

    try:
        for prof in profiles:
            print(f"\n================ Evaluating {prof.name}: {prof.desc} ================")
            if prof.use_slm:
                if prof.model not in clients:
                    clients[prof.model] = NativeRerankerClient(prof.model, threads=4)
                client = clients[prof.model]
                client.configure(prof.cpp_cfg)
                client.reset()
                client.score("今日は良い", ["天気", "転機", "電気"])
                client.reset()
                client.reset_stats()
            else:
                client = None

            # --- 1A. Homophone Continuous Typing Benchmark (PowerBenchmarkRunner 25 seqs) ---
            typing_latencies, hit_latencies, miss_latencies = [], [], []
            slm_called_typing = 0
            warmed_ctx_set = set()
            for rep in range(2):
                if client:
                    client.reset()
                warmed_ctx_set.clear()
                for step in typing_steps:
                    t_ctx = trim_context(step["context"], prof.target_tokens) if prof.target_tokens > 0 else ""
                    eff_ctx = t_ctx if t_ctx.strip() else "<s>"
                    if not prof.use_slm:
                        lat_ms = 0.55
                        slm_ran = False
                    else:
                        invoke, _ = should_invoke_slm_a1(
                            step["reading"], step["cands"], step["real_costs"],
                            prof.gate_mode, prof.gate_margin, is_explicit_conversion=step["is_explicit"]
                        )
                        if not invoke:
                            lat_ms = 0.55
                            slm_ran = False
                        else:
                            surfs = [c["surface"] for c in step["cands"]]
                            ok, all_done, fast_ms, _ = client.fast_score(eff_ctx, surfs)
                            if ok and all_done:
                                lat_ms = fast_ms + 0.55
                            else:
                                _, sc_ms = client.score(eff_ctx, surfs)
                                lat_ms = sc_ms + 0.55
                            slm_called_typing += 1
                            slm_ran = True
                    typing_latencies.append(lat_ms)
                    if slm_ran:
                        if eff_ctx in warmed_ctx_set:
                            hit_latencies.append(lat_ms)
                        else:
                            miss_latencies.append(lat_ms)
                            warmed_ctx_set.add(eff_ctx)
                    elif step["is_first"]:
                        miss_latencies.append(lat_ms)
                    else:
                        hit_latencies.append(lat_ms)

            typing_mean_ms = statistics.mean(typing_latencies)
            typing_p95_ms = pct(typing_latencies, 0.95)
            hit_mean_ms = statistics.mean(hit_latencies)
            miss_mean_ms = statistics.mean(miss_latencies)
            if prof.name == "P0_baseline":
                p0_typing_host_ms = typing_mean_ms
            typing_stats = client.stats() if client else {}

            # --- 1B. Natural Multi-Clause Continuous Typing Benchmark ---
            if client:
                client.reset()
                client.reset_stats()
            nat_latencies = []
            nat_slm_calls = 0
            for step in natural_typing_steps:
                t_ctx = trim_context(step["context"], prof.target_tokens) if prof.target_tokens > 0 else ""
                eff_ctx = t_ctx if t_ctx.strip() else "<s>"
                if not prof.use_slm:
                    lat_ms = 0.55
                else:
                    invoke, _ = should_invoke_slm_a1(
                        step["reading"], step["cands"], step["real_costs"],
                        prof.gate_mode, prof.gate_margin, is_explicit_conversion=step["is_explicit"]
                    )
                    if not invoke:
                        lat_ms = 0.55
                    else:
                        surfs = [c["surface"] for c in step["cands"]]
                        ok, all_done, fast_ms, _ = client.fast_score(eff_ctx, surfs)
                        if ok and all_done:
                            lat_ms = fast_ms + 0.55
                        else:
                            _, sc_ms = client.score(eff_ctx, surfs)
                            lat_ms = sc_ms + 0.55
                        nat_slm_calls += 1
                nat_latencies.append(lat_ms)
            nat_typing_mean_ms = statistics.mean(nat_latencies)
            nat_typing_p95_ms = pct(nat_latencies, 0.95)
            nat_stats = client.stats() if client else {}

            # --- 2. Next-Phrase Prediction Benchmark (Post-Commit Warm & Cold) ---
            if client:
                client.reset()
                client.reset_stats()
            pred_warm_latencies = []
            pred_cold_latencies = []
            pred_zero_hits = 0
            pred_items_for_eis = []

            for item in pred_golden:
                cid = item["id"]
                prev_w = item["prev_word"]
                tgt = item["target_word"]
                rd = item["target_reading"]
                t_ctx = trim_context(prev_w, prof.target_tokens) if prof.target_tokens > 0 else ""
                eff_ctx = t_ctx if t_ctx.strip() else "<s>"
                fast_preds = CANONICAL_COLLOCATIONS.get(prev_w, [])

                if not prof.use_slm:
                    z_cands = fast_preds[:3]
                    warm_ms = 0.15
                    cold_ms = 0.15
                else:
                    # Measure post-commit WARM prediction + zero-query rerank
                    w_ms = client.warm(eff_ctx)
                    if prof.use_d2_next:
                        raw_toks, np_ms = client.predict(eff_ctx, max_cands=8, max_toks=1)
                        d2_exp = expand_zero_decode_tokens(prev_w, raw_toks, prefix_1, prefix_2, max_out=4)
                        combined = []
                        seen_c = set()
                        fast_set = set(fast_preds)
                        for c in fast_preds + d2_exp:
                            if c and c not in seen_c:
                                seen_c.add(c)
                                combined.append(c)
                        if len(combined) > 1:
                            c8 = combined[:8]
                            ok, all_done, fs_ms, nlls = client.fast_score(eff_ctx, c8)
                            if ok and all_done:
                                rr_ms = fs_ms
                            else:
                                nlls, rr_ms = client.score(eff_ctx, c8)
                            # Length-normalized + collocation prior scoring (mirrors evaluate_predictions_slm_comparison.py)
                            def np_score(idx_c):
                                s, nll = idx_c[1], idx_c[2]
                                eff_nll = nll / (max(1, len(s)) ** 0.70)
                                prior = (idx_c[0] * 0.4) if s in fast_set else 3.5
                                return 0.25 * prior + 0.75 * eff_nll
                            scored = sorted(
                                [(i, c8[i], nlls[i]) for i in range(len(c8))],
                                key=np_score
                            )
                            z_cands = [x[1] for x in scored[:3]]
                        else:
                            rr_ms = 0.0
                            z_cands = combined[:3]
                    else:
                        slm_cands, np_ms = client.predict(eff_ctx, max_cands=3, max_toks=5)
                        combined = []
                        seen_c = set()
                        fast_set = set(fast_preds)
                        for c in fast_preds + slm_cands:
                            if c and c not in seen_c:
                                seen_c.add(c)
                                combined.append(c)
                        if len(combined) > 1:
                            c8 = combined[:8]
                            nlls, rr_ms = client.score(eff_ctx, c8)
                            def np_score_base(idx_c):
                                s, nll = idx_c[1], idx_c[2]
                                eff_nll = nll / (max(1, len(s)) ** 0.70)
                                prior = (idx_c[0] * 0.4) if s in fast_set else 3.5
                                return 0.25 * prior + 0.75 * eff_nll
                            scored = sorted(
                                [(i, c8[i], nlls[i]) for i in range(len(c8))],
                                key=np_score_base
                            )
                            z_cands = [x[1] for x in scored[:3]]
                        else:
                            rr_ms = 0.0
                            z_cands = combined[:3]

                    warm_ms = np_ms + rr_ms
                    cold_ms = w_ms + warm_ms

                pred_warm_latencies.append(warm_ms)
                pred_cold_latencies.append(cold_ms)

                def find_rank(cands_list):
                    if tgt in cands_list:
                        return cands_list.index(tgt) + 1
                    for idx, s in enumerate(cands_list):
                        if s.startswith(tgt):
                            return idx + 1
                    return None

                z_rank = find_rank(z_cands)
                if z_rank is not None:
                    pred_zero_hits += 1

                c_src = cached_on_map.get(cid, {}) if prof.use_slm else cached_off_map.get(cid, {})
                preds_obj = c_src.get("predictions", {})
                o_cands = preds_obj.get("one_char", {}).get("no_history", [])
                t_cands = preds_obj.get("two_char", {}).get("no_history", [])

                z_wait = eis_klm.KLM.wait(warm_ms / 1000.0, eis_klm.KLM.wait_free_key)
                if z_rank is not None:
                    sec = eis_klm.KLM.select_cost(z_rank) + z_wait
                else:
                    o_rank = find_rank(o_cands)
                    t_rank = find_rank(t_cands)
                    k_wait = eis_klm.KLM.wait(typing_mean_ms / 1000.0, eis_klm.KLM.wait_free_key)
                    if o_rank is not None:
                        q_keys = eis_klm.est_kana_keystrokes(preds_obj["one_char"]["query"])
                        sec = eis_klm.KLM.K_f * q_keys + k_wait + eis_klm.KLM.select_cost(o_rank)
                    elif t_rank is not None:
                        q_keys = eis_klm.est_kana_keystrokes(preds_obj["two_char"]["query"])
                        sec = eis_klm.KLM.K_f * q_keys + k_wait + eis_klm.KLM.select_cost(t_rank)
                    else:
                        full_keys = eis_klm.est_kana_keystrokes(rd)
                        sec = eis_klm.KLM.K_f * full_keys + k_wait + eis_klm.KLM.select_cost(2)

                pred_items_for_eis.append({
                    "id": f"pred:{cid}",
                    "split": eis_adapters.split_of(cid),
                    "chars": len(tgt),
                    "secs": sec,
                })

            pred_warm_mean_ms = statistics.mean(pred_warm_latencies)
            pred_cold_mean_ms = statistics.mean(pred_cold_latencies)
            pred_stats = client.stats() if client else {}

            # --- 3. EIS-Bench v1 CUJ-2 Conversion + CUJ-3 User Dict (247 cases) ---
            if client:
                client.reset()
                client.reset_stats()
            conv_items_for_eis = []
            cuj3_flick_items = []
            lm_latencies = []
            lm_records = []
            slm_called_lm = 0

            scale_to_pixel = (PIXEL10PRO_Q25_05B_TYPING_MS / p0_typing_host_ms) if (p0_typing_host_ms and p0_typing_host_ms > 0) else 1.0

            for it in lm_items:
                iid = it["id"]
                cat = it["category"]
                ctx = it["context"]
                rd = it["input"]
                tgt = it["target"]
                cands = it["candidates"]
                rc = real_costs_lm[iid]

                if not prof.use_slm:
                    surfs = rank_without_slm(cands, rc)
                    lat_ms = 0.55
                else:
                    invoke, _ = should_invoke_slm_a1(
                        rd, cands, rc, prof.gate_mode, prof.gate_margin, is_explicit_conversion=True
                    )
                    if not invoke:
                        surfs = rank_without_slm(cands, rc)
                        lat_ms = 0.55
                    else:
                        t_ctx = trim_context(ctx, prof.target_tokens) if prof.target_tokens > 0 else ""
                        eff_ctx = t_ctx if t_ctx.strip() else "<s>"
                        c_surfs = [c["surface"] for c in cands]
                        priors = []
                        for c in cands:
                            if c.get("source") == "user" and c.get("obs_penalty", 0.0) == 0.0:
                                priors.append(0.0)
                            else:
                                dp = max(0, min(15000, rc.get(c["surface"], 3000))) / 3000.0
                                op = 0.50 * c.get("obs_penalty", 0.0) * 5.0 * 0.07
                                priors.append(dp + op)
                        nlls, sc_ms = client.score(eff_ctx, c_surfs, priors)
                        surfs = rank_with_slm(cands, nlls, rc, alpha=prof.alpha)
                        lat_ms = sc_ms + 0.55
                        slm_called_lm += 1

                lm_latencies.append(lat_ms)
                rank = (surfs.index(tgt) + 1) if tgt in surfs else None
                lm_records.append({"id": iid, "split": eis_adapters.split_of(iid), "category": cat, "rank": rank})

                eff_lat_s = (lat_ms * scale_to_pixel) / 1000.0 if prof.use_slm else (0.55 / 1000.0)
                t_full = (
                    eis_klm.KLM.K_f * eis_klm.est_kana_keystrokes(rd)
                    + eis_klm.KLM.wait(eff_lat_s, eis_klm.KLM.wait_free_key)
                    + eis_klm.KLM.select_cost(rank)
                )
                if rank is None:
                    t_full += eis_klm.KLM.K_f * eis_klm.est_kana_keystrokes(tgt)

                if cat in ("phrase", "natural") and len(rd) >= 4 and rank is not None and rank <= 3:
                    pref_inp = rd[:max(2, len(rd) // 2)]
                    t_ship = (
                        eis_klm.KLM.K_f * eis_klm.est_kana_keystrokes(pref_inp)
                        + eis_klm.KLM.wait(eff_lat_s, eis_klm.KLM.wait_free_key)
                        + eis_klm.KLM.select_cost(2)
                    )
                else:
                    t_ship = t_full

                rec = {"id": f"flick:{iid}", "split": eis_adapters.split_of(iid), "chars": len(tgt), "secs": t_ship}
                if cat == "user_dict":
                    R = eis_klm.KLM.register_cost / eis_klm.KLM.reuse_count
                    rec["secs"] = t_ship + R
                    cuj3_flick_items.append(rec)
                else:
                    conv_items_for_eis.append(rec)

            lm_stats = client.stats() if client else {}

            total_conv_ch = sum(x["chars"] for x in conv_items_for_eis)
            total_pred_ch = sum(x["chars"] for x in pred_items_for_eis)
            scale_pred = total_conv_ch / total_pred_ch if total_pred_ch > 0 else 1.0
            scaled_pred_items = [
                {"id": x["id"], "split": x["split"], "chars": x["chars"] * scale_pred, "secs": x["secs"] * scale_pred}
                for x in pred_items_for_eis
            ]
            cuj2_all = conv_items_for_eis + scaled_pred_items

            # --- 4. Long-Context 150-item Disambiguation Check (alpha=0.20 production default) ---
            if client:
                client.reset()
                client.reset_stats()
            long_recs = []
            long_lats = []
            for it in long_eval_data:
                if not prof.use_slm:
                    surfs = rank_without_slm(it["cands"], it["real_costs"])
                    lat_ms = 0.55
                else:
                    invoke, _ = should_invoke_slm_a1(
                        it["reading"], it["cands"], it["real_costs"], prof.gate_mode, prof.gate_margin, True
                    )
                    if not invoke:
                        surfs = rank_without_slm(it["cands"], it["real_costs"])
                        lat_ms = 0.55
                    else:
                        t_ctx = trim_context(it["context"], prof.target_tokens) if prof.target_tokens > 0 else ""
                        eff_ctx = t_ctx if t_ctx.strip() else "<s>"
                        c_surfs = [c["surface"] for c in it["cands"]]
                        nlls, sc_ms = client.score(eff_ctx, c_surfs)
                        surfs = rank_with_slm(it["cands"], nlls, it["real_costs"], alpha=0.20)
                        lat_ms = sc_ms + 0.55
                long_lats.append(lat_ms)
                rk = (surfs.index(it["target"]) + 1) if it["target"] in surfs else None
                long_recs.append({"split": it["split"], "rank": rk})
            long_stats = client.stats() if client else {}

            def summarize_l2(recs, spl):
                sub = [r for r in recs if spl == "all" or r["split"] == spl]
                n = len(sub)
                top1 = 100.0 * sum(1 for r in sub if r["rank"] == 1) / n
                top3 = 100.0 * sum(1 for r in sub if r["rank"] is not None and r["rank"] <= 3) / n
                mrr = sum((1.0 / r["rank"]) if r["rank"] else 0.0 for r in sub) / n
                ud = [r for r in sub if r.get("category") == "user_dict"]
                ud_top1 = (100.0 * sum(1 for r in ud if r["rank"] == 1) / len(ud)) if ud else 100.0
                homo = [r for r in sub if r.get("category") == "homophone"]
                homo_top1 = (100.0 * sum(1 for r in homo if r["rank"] == 1) / len(homo)) if homo else 0.0
                return {
                    "n": n, "top1": top1, "top3": top3, "mrr": mrr,
                    "user_dict_top1": ud_top1, "homophone_top1": homo_top1,
                }

            all_results[prof.name] = {
                "name": prof.name,
                "desc": prof.desc,
                "model": os.path.basename(prof.model),
                "use_slm": prof.use_slm,
                "latency": {
                    "host_typing_mean_ms": typing_mean_ms,
                    "host_typing_p95_ms": typing_p95_ms,
                    "host_hit_mean_ms": hit_mean_ms,
                    "host_miss_mean_ms": miss_mean_ms,
                    "host_nat_typing_mean_ms": nat_typing_mean_ms,
                    "host_nat_typing_p95_ms": nat_typing_p95_ms,
                    "host_next_phrase_warm_ms": pred_warm_mean_ms,
                    "host_next_phrase_cold_ms": pred_cold_mean_ms,
                    "host_lm_first_mean_ms": statistics.mean(lm_latencies),
                    "host_long_ctx_mean_ms": statistics.mean(long_lats),
                    "pixel_est_typing_mean_ms": typing_mean_ms * scale_to_pixel if prof.use_slm else 0.55,
                    "pixel_est_typing_p95_ms": typing_p95_ms * scale_to_pixel if prof.use_slm else 0.55,
                    "pixel_est_hit_mean_ms": hit_mean_ms * scale_to_pixel if prof.use_slm else 0.55,
                    "pixel_est_nat_typing_mean_ms": nat_typing_mean_ms * scale_to_pixel if prof.use_slm else 0.55,
                    "pixel_est_next_phrase_warm_ms": pred_warm_mean_ms * scale_to_pixel if prof.use_slm else 0.15,
                },
                "ops": {
                    "typing_slm_call_rate": slm_called_typing / len(typing_latencies),
                    "nat_typing_slm_call_rate": nat_slm_calls / len(nat_latencies),
                    "lm_slm_call_rate": slm_called_lm / len(lm_items),
                    "typing_stats": typing_stats,
                    "nat_stats": nat_stats,
                    "pred_stats": pred_stats,
                    "lm_stats": lm_stats,
                    "long_stats": long_stats,
                },
                "l2": {
                    "dev": summarize_l2(lm_records, "dev"),
                    "test": summarize_l2(lm_records, "test"),
                    "all": summarize_l2(lm_records, "all"),
                    "long_dev": summarize_l2(long_recs, "dev"),
                    "long_test": summarize_l2(long_recs, "test"),
                    "next_phrase_zero_hit_at_3_pct": 100.0 * pred_zero_hits / len(pred_golden),
                },
                "_cuj2": cuj2_all,
                "_cuj3_f": cuj3_flick_items,
            }

            print(
                f"  HomoTyp={typing_mean_ms:.2f}ms (Hit={hit_mean_ms:.2f}ms) | "
                f"NatTyp={nat_typing_mean_ms:.2f}ms (P95={nat_typing_p95_ms:.2f}ms) | "
                f"NP_Warm={pred_warm_mean_ms:.2f}ms | NP_Cold={pred_cold_mean_ms:.2f}ms"
            )
            print(
                f"  DEV Top1={all_results[prof.name]['l2']['dev']['top1']:.2f}% "
                f"(Homo={all_results[prof.name]['l2']['dev']['homophone_top1']:.1f}%, "
                f"User={all_results[prof.name]['l2']['dev']['user_dict_top1']:.1f}%, "
                f"Long={all_results[prof.name]['l2']['long_dev']['top1']:.1f}%) | "
                f"NP ZeroHit@3={all_results[prof.name]['l2']['next_phrase_zero_hit_at_3_pct']:.1f}%"
            )

        p0 = all_results["P0_baseline"]
        p0_lat = p0["latency"]
        for name, res in all_results.items():
            res["eis_dev"] = compute_eis_with_bootstrap(
                base_cuj_items, res["_cuj2"], res["_cuj3_f"], p0["_cuj2"], p0["_cuj3_f"], "dev"
            )
            res["eis_test"] = compute_eis_with_bootstrap(
                base_cuj_items, res["_cuj2"], res["_cuj3_f"], p0["_cuj2"], p0["_cuj3_f"], "test"
            )
            lat = res["latency"]
            # Real-session combined SLM time: 60% natural typing + 20% KV hit + 20% post-commit warm NextPhrase
            p0_session = (
                0.60 * p0_lat["host_nat_typing_mean_ms"]
                + 0.20 * p0_lat["host_hit_mean_ms"]
                + 0.20 * p0_lat["host_next_phrase_warm_ms"]
            )
            cur_session = (
                0.60 * lat["host_nat_typing_mean_ms"]
                + 0.20 * lat["host_hit_mean_ms"]
                + 0.20 * lat["host_next_phrase_warm_ms"]
            )
            res["speedup"] = {
                "hit_mean_x": p0_lat["host_hit_mean_ms"] / max(0.01, lat["host_hit_mean_ms"]),
                "nat_typing_mean_x": p0_lat["host_nat_typing_mean_ms"] / max(0.01, lat["host_nat_typing_mean_ms"]),
                "homo_typing_mean_x": p0_lat["host_typing_mean_ms"] / max(0.01, lat["host_typing_mean_ms"]),
                "next_phrase_warm_x": p0_lat["host_next_phrase_warm_ms"] / max(0.01, lat["host_next_phrase_warm_ms"]),
                "next_phrase_cold_x": p0_lat["host_next_phrase_cold_ms"] / max(0.01, lat["host_next_phrase_cold_ms"]),
                "session_combined_x": p0_session / max(0.01, cur_session),
            }
        for res in all_results.values():
            res.pop("_cuj2", None)
            res.pop("_cuj3_f", None)

        with open(OUT_JSON, "w", encoding="utf-8") as f:
            json.dump(all_results, f, ensure_ascii=False, indent=2)

        print("\n==================== SUMMARY (DEV -> FROZEN TEST) ====================")
        header = f"{'Profile':<25} {'Spd(Ses)':>8} {'Spd(Nat)':>8} {'Spd(Hit)':>8} {'Spd(NPw)':>8} {'Nat(ms)':>7} {'NPw(ms)':>7} {'DEV EIS':>8} {'dEIS(dev)':>10} {'TST EIS':>8} {'dEIS(tst)':>10}"
        print(header)
        print("-" * len(header))
        for name, res in all_results.items():
            ed = res["eis_dev"]
            et = res["eis_test"]
            sp = res["speedup"]
            lat = res["latency"]
            print(
                f"{name:<25} {sp['session_combined_x']:>7.1f}x {sp['nat_typing_mean_x']:>7.1f}x "
                f"{sp['hit_mean_x']:>7.1f}x {sp['next_phrase_warm_x']:>7.1f}x "
                f"{lat['host_nat_typing_mean_ms']:>6.2f} {lat['host_next_phrase_warm_ms']:>6.2f} "
                f"{ed['eis']:>8.2f} {ed['delta_vs_p0']:>+9.2f} "
                f"{et['eis']:>8.2f} {et['delta_vs_p0']:>+9.2f}"
            )
            st = res["ops"].get("nat_stats", {})
            print(
                f"   ops: nat_slm={res['ops']['nat_typing_slm_call_rate']*100:.1f}% "
                f"s2_calls={st.get('stage2_calls',0)} s2_skip={st.get('stage2_skipped_calls',0)} "
                f"DEV_Top1={res['l2']['dev']['top1']:.2f}% Homo={res['l2']['dev']['homophone_top1']:.1f}% "
                f"Long={res['l2']['long_dev']['top1']:.1f}% NP_Hit3={res['l2']['next_phrase_zero_hit_at_3_pct']:.1f}%"
            )
        print(f"\nSaved full JSON results to {OUT_JSON}")
    finally:
        for cl in clients.values():
            cl.close()


if __name__ == "__main__":
    main()
