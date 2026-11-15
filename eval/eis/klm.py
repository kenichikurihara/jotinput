"""EIS-v1 user-behaviour model (Keystroke-Level Model for mobile IME).

Single source of truth for the constants in docs/prd_top_level.md section 5.3.
Changing ANY constant here requires bumping EIS_VERSION (and the PRD) in the same commit.
"""
from __future__ import annotations

import difflib
import re
import unicodedata
from dataclasses import dataclass

EIS_VERSION = "EIS-v1"

# CUJ weights w_c = assumed share of intended characters (PRD 4.2). Owner-approved 2026-11-15.
CUJ_WEIGHTS = {"CUJ-1": 0.40, "CUJ-2": 0.35, "CUJ-3": 0.15, "CUJ-4": 0.10}
# CUJ-4 internal split (English voice vs touch).
CUJ4_VOICE_SHARE = 0.5


@dataclass(frozen=True)
class Klm:
    K_f: float = 0.40          # one flick keystroke (s)
    K_q: float = 0.30          # one QWERTY tap (s)
    G_s: float = 0.80          # one swipe word (s)
    S_mora: float = 7.0        # Japanese speech rate (mora/s)
    S_en_wps: float = 2.5      # English speech rate (words/s)
    C_1: float = 0.40          # confirm top-1
    C_step: float = 0.30       # each extra rank in strip (2<=k<=5)
    C_list_base: float = 2.0   # expanded list base (k>5)
    C_none_base: float = 1.5   # candidate absent: re-segment/retype base
    L: float = 1.5             # find + locate one error span
    D: float = 0.20            # delete one char
    voice_overhead: float = 0.8  # tap mic + tap done
    wait_free_key: float = 0.10   # latency below this is imperceptible (key ops)
    wait_free_voice: float = 0.20  # same for voice commit
    # Dictionary lifecycle (CUJ-3)
    register_cost: float = 12.0    # type surface+reading in dictionary UI (s)
    reuse_count: int = 3           # uses per registered word amortising the registration
    avg_en_word_len: float = 4.7   # characters, excl. space

    def wait(self, latency_s: float, free: float) -> float:
        return max(0.0, latency_s - free)

    def select_cost(self, k: int | None) -> float:
        """Cost of picking the correct candidate at rank k (1-based); None = absent."""
        if k is None or k <= 0:
            return self.C_none_base + 2 * self.C_1  # + typing cost added by caller
        if k == 1:
            return self.C_1
        if k <= 5:
            return self.C_1 + self.C_step * (k - 1)
        return self.C_list_base + self.C_step * (k - 5)


KLM = Klm()

_PUNCT = "、。，．,.！？!?・「」『』（）() \u3000\n"
_KANA = re.compile(r"[\u3040-\u30ff\u31f0-\u31ff]")
_KANJI = re.compile(r"[\u4e00-\u9fff々]")


def normalize_for_asr(text: str) -> str:
    """Strip punctuation/space; punctuation is covered by the dedicated punctuation suite."""
    text = unicodedata.normalize("NFKC", text)
    return "".join(ch for ch in text if ch not in _PUNCT)


def est_moras(text: str) -> float:
    """Rough mora estimate: kana=1, kanji=2, other (latin/digit)=1."""
    n = 0.0
    for ch in text:
        if _KANJI.match(ch):
            n += 2
        elif ch in "ゃゅょャュョぁぃぅぇぉ":
            n += 0
        else:
            n += 1
    return n


def est_kana_keystrokes(text: str) -> float:
    """Flick keystrokes to type the reading of `text` (kanji~2 kana; voiced marks add ~0.3)."""
    n = 0.0
    for ch in text:
        if _KANJI.match(ch):
            n += 2.3
        elif _KANA.match(ch):
            n += 1.3 if ch in "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽガギグゲゴザジズゼゾダヂヅデドバビブベボパピプペポ" else 1.0
        else:
            n += 1.0
    return n


def error_spans(gt: str, pred: str, merge_gap: int = 1):
    """Character-level diff -> list of (gt_span, pred_span); adjacent errors merged."""
    sm = difflib.SequenceMatcher(a=gt, b=pred, autojunk=False)
    ops = [op for op in sm.get_opcodes()]
    spans = []
    cur = None
    for tag, i1, i2, j1, j2 in ops:
        if tag == "equal":
            if cur is not None and (i2 - i1) > merge_gap:
                spans.append(cur)
                cur = None
            elif cur is not None:  # short equal run inside an error cluster
                cur = (cur[0], i2, cur[2], j2)
            continue
        if cur is None:
            cur = (i1, i2, j1, j2)
        else:
            cur = (cur[0], i2, cur[2], j2)
    if cur is not None:
        spans.append(cur)
    return [(gt[a:b], pred[c:d]) for a, b, c, d in spans]


def retype_cost(gt_span: str, k: Klm = KLM) -> float:
    """Re-enter a corrected span via flick; assumes the right candidate is rank 2 (v1 assumption)."""
    return k.K_f * est_kana_keystrokes(gt_span) + k.select_cost(2)


def correction_cost(gt: str, pred: str, k: Klm = KLM) -> float:
    total = 0.0
    for gt_span, pred_span in error_spans(gt, pred):
        total += k.L + k.D * len(pred_span) + retype_cost(gt_span, k)
    return total


def voice_item_time(gt: str, pred: str, latency_s: float, k: Klm = KLM):
    """Return (chars, seconds) for one spoken sentence."""
    g, p = normalize_for_asr(gt), normalize_for_asr(pred)
    produce = k.voice_overhead + est_moras(g) / k.S_mora
    wait = k.wait(latency_s, k.wait_free_voice)
    correct = correction_cost(g, p, k)
    return len(g), produce + wait + correct


def retype_cost_en(gt_span: str, k: Klm = KLM) -> float:
    """Re-enter a corrected English span via QWERTY tap + auto-suggest."""
    return k.K_q * len(gt_span) + k.select_cost(1)


def correction_cost_en(gt: str, pred: str, k: Klm = KLM) -> float:
    total = 0.0
    for gt_span, pred_span in error_spans(gt, pred):
        total += k.L + k.D * len(pred_span) + retype_cost_en(gt_span, k)
    return total


def voice_item_time_en(gt: str, pred: str, latency_s: float, k: Klm = KLM):
    """Return (chars, seconds) for one spoken English sentence."""
    words = re.findall(r"\b\w+\b", gt)
    num_words = max(1, len(words))
    produce = k.voice_overhead + num_words / k.S_en_wps
    wait = k.wait(latency_s, k.wait_free_voice)
    correct = correction_cost_en(gt, pred, k)
    return len(gt), produce + wait + correct

