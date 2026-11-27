#!/usr/bin/env python3
"""evaluate_user_dict.py: Benchmarks English Voice Input User-Dictionary Prioritization.

Evaluates on real NVIDIA Parakeet TDT-CTC 110M transcriptions:
1. 68 positive utterances (CamelCase, bilingual Kana-reading entries, Japanese proper nouns,
   acronyms, spoken separators, possessives, multi-word phrases, context-assisted disambiguation)
2. 42 adversarial negative utterances (phonetic lookalikes & common lowercase words)
3. 300-sentence baseline English voice non-regression dataset

Compares:
- Baseline (current JotInput: 0% user-dictionary resolution in English voice path)
- Improved Resolver (SLM-OFF: pure domain phonetic/multi-word/frequency/DMN)
- Improved Resolver (SLM-ON: with real Qwen2.5-0.5B SLM coherence gating via slm_scorer_cli)
"""

import json
import os
import re
import subprocess
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import sherpa_onnx
import soundfile as sf

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
DATASET_FILE = os.path.join(SCRIPT_DIR, "user_dict_dataset.json")
RAW_CACHE_FILE = os.path.join(SCRIPT_DIR, "parakeet_raw_cache.json")
BASELINE_300_FILE = os.path.join(SCRIPT_DIR, "english_voice_eval_results.json")
RESULTS_FILE = os.path.join(SCRIPT_DIR, "user_dict_eval_results.json")
AUDIO_DIR = os.path.join(SCRIPT_DIR, "audio")
PARAKEET_DIR = os.path.join(REPO_ROOT, "tools", "models", "parakeet-en-110m")
SLM_CLI = os.path.join(REPO_ROOT, "tools", "slm_scorer_cli")
SLM_MODEL = os.path.join(REPO_ROOT, "tools", "models", "qwen2.5-0.5b-instruct-q4_k_m.gguf")


def load_corpus_log_freqs() -> Dict[str, float]:
    d: Dict[str, float] = {}
    for rel in [
        "app/src/main/resources/english/english_lower_vocab.txt",
        "app/src/main/resources/english/english_case_lexicon.tsv",
    ]:
        p = os.path.join(REPO_ROOT, rel)
        if not os.path.exists(p):
            continue
        with open(p, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.strip().split("\t")
                if not parts:
                    continue
                k = parts[0].strip().lower()
                if len(parts) >= 2:
                    try:
                        d[k] = float(parts[1])
                    except ValueError:
                        d.setdefault(k, 2.0)
                else:
                    d.setdefault(k, 2.0)
    return d


CARDINAL_WORDS = {
    "zero": "0", "one": "1", "two": "2", "three": "3", "four": "4",
    "five": "5", "six": "6", "seven": "7", "eight": "8", "nine": "9",
    "ten": "10", "eleven": "11", "twelve": "12", "thirteen": "13",
    "fourteen": "14", "fifteen": "15", "sixteen": "16", "seventeen": "17",
    "eighteen": "18", "nineteen": "19", "twenty": "20",
}
DIGIT_TO_WORD = {v: k for k, v in CARDINAL_WORDS.items()}


def has_cjk_or_kana(s: str) -> bool:
    for ch in s:
        code = ord(ch)
        if (
            0x3040 <= code <= 0x30FF
            or 0x4E00 <= code <= 0x9FFF
            or 0x3400 <= code <= 0x4DBF
            or 0xF900 <= code <= 0xFAFF
            or 0xFF00 <= code <= 0xFFEF
        ):
            return True
    return False


def is_eligible_for_english_voice(entry: dict) -> bool:
    if not entry.get("is_enabled", True):
        return False
    word = entry["word"].strip()
    if not word or has_cjk_or_kana(word):
        return False
    if not any("a" <= ch.lower() <= "z" for ch in word):
        return False
    compact = re.sub(r"[^A-Za-z0-9]", "", word)
    if len(compact) >= 2:
        return True
    # Allow 1-letter + spoken symbol terms like "C++", "C#", "F#"
    return len(re.sub(r"\s+", "", word)) >= 2 and expand_spoken_symbols(word) != word.lower()


def expand_spoken_symbols(word: str) -> str:
    s = word.strip().lower()
    s = s.replace(".", " dot ")
    s = s.replace("/", " slash ")
    s = s.replace("+", " plus ")
    s = s.replace("#", " sharp ")
    s = s.replace("-", " ")
    tokens = []
    for tok in s.split():
        if tok in DIGIT_TO_WORD:
            tokens.append(DIGIT_TO_WORD[tok])
        else:
            tokens.append(tok)
    return " ".join(tokens)


def normalize_compact_alnum(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def english_phonetic_key(raw: str) -> str:
    w = re.sub(r"[^a-z]", "", raw.lower())
    if not w:
        return ""
    if w.startswith(("kn", "gn", "pn")):
        w = w[1:]
    elif w.startswith("wr"):
        w = w[1:]
    elif w.startswith("ps"):
        w = w[1:]
    elif w.startswith("wh"):
        w = "w" + w[2:]

    if len(w) >= 4 and w.endswith("mb"):
        w = w[:-1]
    if w.endswith("que"):
        w = w[:-3] + "k"
    w = re.sub(r"([kpfsc])ed$", r"\1t", w)
    if len(w) >= 4 and w.endswith("e") and w[-2] not in "aeiouy":
        w = w[:-1]

    w = w.replace("igh", "a").replace("ght", "t")
    w = re.sub(r"(?<=[aeiouy])gh", "", w)
    w = w.replace("ph", "f")
    w = w.replace("tion", "San").replace("sion", "San")
    w = w.replace("sh", "S").replace("ch", "C").replace("th", "T")
    w = w.replace("ck", "k").replace("qu", "kw").replace("q", "k").replace("x", "ks")
    w = re.sub(r"dg([eiy])", r"j\1", w)
    w = re.sub(r"sc([eiy])", r"s\1", w)
    w = re.sub(r"c([eiy])", r"s\1", w)
    w = w.replace("c", "k")
    w = re.sub(r"(?<=[aeiouy])h(?![aeiouy])", "", w)

    prefix_y = ""
    if len(w) >= 2 and w[0] == "y" and w[1] in "aeiou":
        prefix_y = "y"
        w = w[1:]
    w = re.sub(r"[aeiouy]+", "a", w)
    w = prefix_y + w

    out = []
    for ch in w:
        if not out or out[-1] != ch:
            out.append(ch)
    return "".join(out)


SIMILAR_025 = {
    frozenset(("s", "z")),
    frozenset(("t", "d")),
    frozenset(("p", "b")),
    frozenset(("k", "g")),
    frozenset(("f", "v")),
    frozenset(("C", "j")),
    frozenset(("T", "t")),
    frozenset(("T", "d")),
}
SIMILAR_030 = {
    frozenset(("T", "f")),
    frozenset(("g", "j")),
    frozenset(("m", "n")),
    frozenset(("w", "v")),
}


def onset_compatible(c1: str, c2: str) -> bool:
    return c1 == c2 or frozenset((c1, c2)) in SIMILAR_025


def weighted_phonetic_distance(k1: str, k2: str) -> float:
    if k1 == k2:
        return 0.0
    if not k1 or not k2:
        return 1.0
    m, n = len(k1), len(k2)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    for i in range(1, m + 1):
        indel = 0.5 if (k1[i - 1] == "r" and i > 1 and k1[i - 2] == "a") else 1.0
        dp[i][0] = dp[i - 1][0] + indel
    for j in range(1, n + 1):
        indel = 0.5 if (k2[j - 1] == "r" and j > 1 and k2[j - 2] == "a") else 1.0
        dp[0][j] = dp[0][j - 1] + indel

    for i in range(1, m + 1):
        c1 = k1[i - 1]
        del_cost = 0.5 if (c1 == "r" and i > 1 and k1[i - 2] == "a") else 1.0
        for j in range(1, n + 1):
            c2 = k2[j - 1]
            ins_cost = 0.5 if (c2 == "r" and j > 1 and k2[j - 2] == "a") else 1.0
            if c1 == c2:
                sub = 0.0
            elif frozenset((c1, c2)) in SIMILAR_025:
                sub = 0.25
            elif frozenset((c1, c2)) in SIMILAR_030:
                sub = 0.30
            else:
                sub = 1.0
            dp[i][j] = min(dp[i - 1][j] + del_cost, dp[i][j - 1] + ins_cost, dp[i - 1][j - 1] + sub)
    return dp[m][n] / max(m, n)


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


def aligned_token_subsequence_match(span_tokens_alpha: List[str], target_alpha: str) -> bool:
    if len(target_alpha) < 5 or len(span_tokens_alpha) < 2:
        return False
    total_span_len = sum(len(t) for t in span_tokens_alpha)
    if total_span_len == 0 or total_span_len / len(target_alpha) > 1.80:
        return False

    if not span_tokens_alpha[0]:
        return False
    fk1 = english_phonetic_key(span_tokens_alpha[0][:2])
    fk2 = english_phonetic_key(target_alpha[:2])
    if span_tokens_alpha[0][0] != target_alpha[0] and (not fk1 or not fk2 or not onset_compatible(fk1[0], fk2[0])):
        return False

    t_idx = 0
    for tok in span_tokens_alpha:
        matched_in_tok = 0
        for ch in tok:
            if t_idx < len(target_alpha) and ch == target_alpha[t_idx]:
                t_idx += 1
                matched_in_tok += 1
        min_req = 1 if len(tok) == 1 else 2
        if matched_in_tok < min_req:
            return False
    return t_idx == len(target_alpha)


@dataclass
class Token:
    raw: str
    start: int
    end: int
    lower: str
    break_char: Optional[str]


PUNCT_BREAK_CHARS = set(",.;:!?()[]{}\"\n")


def tokenize_with_offsets(text: str) -> List[Token]:
    tokens: List[Token] = []
    i = 0
    n = len(text)
    pending_break: Optional[str] = None
    while i < n:
        ch = text[i]
        if ch.isspace():
            i += 1
            continue
        if ch in PUNCT_BREAK_CHARS:
            pending_break = ch
            i += 1
            continue
        start = i
        while i < n and not text[i].isspace():
            if text[i] in PUNCT_BREAK_CHARS:
                if text[i] == "." and i + 1 < n and text[i + 1].isalnum():
                    i += 1
                    continue
                break
            i += 1
        raw = text[start:i]
        if any(c.isalnum() for c in raw):
            tokens.append(Token(raw=raw, start=start, end=i, lower=raw.lower(), break_char=pending_break))
            pending_break = None
        if i < n and text[i] in PUNCT_BREAK_CHARS:
            pending_break = text[i]
            i += 1
    return tokens


def strip_possessive(tokens: List[Token]) -> Tuple[List[str], str]:
    raws = [t.raw for t in tokens]
    suffix = ""
    last = raws[-1]
    if last.endswith("'s") or last.endswith("’s"):
        raws[-1] = last[:-2]
        suffix = last[-2:]
    return raws, suffix


def is_mixed_case_or_all_caps_or_symbol(word: str) -> bool:
    if any(ch in "./+-#0123456789" for ch in word):
        return True
    has_upper = any("A" <= ch <= "Z" for ch in word)
    has_lower = any("a" <= ch <= "z" for ch in word)
    if has_upper and has_lower and any("A" <= ch <= "Z" for ch in word[1:]):
        return True
    if has_upper and not has_lower and len(word) >= 2:
        return True
    return False


def extract_diverging_pair_window(
    context_before: str,
    orig_text: str,
    corr_text: str,
    max_trailing_words: int = 4,
) -> Tuple[str, str, str]:
    """Splits shared prefix into context and caps trailing context so Stage 2 evaluates the diverging span."""
    min_len = min(len(orig_text), len(corr_text))
    prefix_len = 0
    while prefix_len < min_len and orig_text[prefix_len] == corr_text[prefix_len]:
        prefix_len += 1

    # Snap back to whitespace boundary if prefix stopped mid-word
    if 0 < prefix_len < min_len and not orig_text[prefix_len - 1].isspace() and not orig_text[prefix_len].isspace():
        ws = orig_text.rfind(" ", 0, prefix_len)
        prefix_len = (ws + 1) if ws >= 0 else 0

    shared_prefix = orig_text[:prefix_len]
    orig_rem = orig_text[prefix_len:]
    corr_rem = corr_text[prefix_len:]

    # Common trailing suffix after the diverging edit
    rem_min = min(len(orig_rem), len(corr_rem))
    suf_len = 0
    while suf_len < rem_min and orig_rem[len(orig_rem) - 1 - suf_len] == corr_rem[len(corr_rem) - 1 - suf_len]:
        suf_len += 1
    orig_div_len = len(orig_rem) - suf_len
    corr_div_len = len(corr_rem) - suf_len
    common_tail = orig_rem[orig_div_len:]

    # Cap trailing common tail to max_trailing_words
    if common_tail:
        words_seen = 0
        in_word = False
        cut = len(common_tail)
        for idx, ch in enumerate(common_tail):
            if ch.isspace():
                if in_word:
                    words_seen += 1
                    in_word = False
                    if words_seen >= max_trailing_words:
                        cut = idx
                        break
            else:
                in_word = True
        common_tail = common_tail[:cut]

    orig_win = orig_rem[:orig_div_len] + common_tail
    corr_win = corr_rem[:corr_div_len] + common_tail

    # Move leading space before diverging token onto candidates so BPE tokenizes naturally with context
    ctx_full = (context_before + shared_prefix).strip()
    if shared_prefix.endswith((" ", "\n", "\t")) and ctx_full:
        orig_win = " " + orig_win.lstrip()
        corr_win = " " + corr_win.lstrip()
    elif context_before.endswith((" ", "\n", "\t")) and not shared_prefix and ctx_full:
        orig_win = " " + orig_win.lstrip()
        corr_win = " " + corr_win.lstrip()

    return ctx_full[-160:], orig_win, corr_win


class SlmScorer:
    def __init__(self, enabled: bool = True):
        self.enabled = enabled and os.path.exists(SLM_CLI) and os.path.exists(SLM_MODEL)
        self.proc = None
        if self.enabled:
            self.proc = subprocess.Popen(
                [SLM_CLI, SLM_MODEL],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
            )
        self.cache: Dict[Tuple[str, str, str], float] = {}
        self.call_count = 0

    def score_delta(self, context: str, orig: str, corr: str) -> Optional[float]:
        if not self.enabled or self.proc is None:
            return None
        ctx_win, o_win, c_win = extract_diverging_pair_window(context, orig, corr)
        if not o_win.strip() or not c_win.strip() or o_win == c_win:
            return None
        key = (ctx_win, o_win, c_win)
        if key in self.cache:
            return self.cache[key]
        ctx_clean = ctx_win.replace("\t", " ").replace("\n", " ")
        o_clean = o_win.replace("\t", " ").replace("\n", " ")
        c_clean = c_win.replace("\t", " ").replace("\n", " ")
        self.proc.stdin.write(f"q\t{ctx_clean}\t{o_clean}\t{c_clean}\n")
        self.proc.stdin.flush()
        line = self.proc.stdout.readline().strip()
        parts = line.split("\t")
        self.call_count += 1
        if len(parts) >= 4:
            lp_orig = float(parts[2])
            lp_corr = float(parts[3])
            delta_nll = (-lp_corr) - (-lp_orig)
            self.cache[key] = delta_nll
            return delta_nll
        return None

    def close(self):
        if self.proc is not None:
            self.proc.stdin.close()
            self.proc.wait()
            self.proc = None


def resolve_user_dict(
    text: str,
    context_before: str,
    entries: List[dict],
    log_freqs: Dict[str, float],
    slm_scorer: Optional[SlmScorer] = None,
    slm_theta_normal: float = 4.0,
    slm_theta_high: float = 4.5,
) -> Tuple[str, List[dict], List[dict]]:
    eligible = [e for e in entries if is_eligible_for_english_voice(e)]
    if not text.strip() or not eligible:
        return text, [], []

    tokens = tokenize_with_offsets(text)
    if not tokens:
        return text, [], []

    targets = []
    for e in eligible:
        w = e["word"].strip()
        forms = [w]
        r = e.get("reading", "").strip()
        if r and not has_cjk_or_kana(r) and r.lower() != w.lower() and any("a" <= c.lower() <= "z" for c in r):
            forms.append(r)
        expanded = expand_spoken_symbols(w)
        if expanded != w.lower():
            forms.append(expanded)
        form_infos = []
        for fm in forms:
            c_alnum = normalize_compact_alnum(fm)
            c_alpha = re.sub(r"[^a-z]", "", fm.lower())
            pkey = english_phonetic_key(c_alpha)
            form_infos.append((fm, c_alnum, c_alpha, pkey))
        targets.append((e, w, normalize_compact_alnum(w), re.sub(r"[^a-z]", "", w.lower()), form_infos))

    candidates = []
    n_tok = len(tokens)
    slm_eval_count = 0

    for start_i in range(n_tok):
        for span_len in range(1, min(6, n_tok - start_i) + 1):
            span_toks = tokens[start_i : start_i + span_len]
            if span_len > 1 and span_toks[-1].break_char not in (None, "."):
                break
            has_dot_break = any(t.break_char == "." for t in span_toks[1:])
            span_start = span_toks[0].start
            span_end = span_toks[-1].end
            orig_slice = text[span_start:span_end]

            stripped_raws, suffix = strip_possessive(span_toks)
            variants = [(stripped_raws, suffix, False)]
            if not suffix and len(stripped_raws[-1]) >= 5 and stripped_raws[-1][-1].lower() == "s":
                alt_raws = list(stripped_raws)
                alt_raws[-1] = alt_raws[-1][:-1]
                # For single common words (e.g. "consists"), test bare target without 's'
                if span_len == 1 and log_freqs.get(stripped_raws[0].lower(), 0.0) >= 3.0:
                    variants.append((alt_raws, "", True))
                else:
                    variants.append((alt_raws, "s", True))

            for cand_raws, cand_suffix, is_plural_strip in variants:
                cand_cardinal_raws = [CARDINAL_WORDS.get(r.lower(), r) for r in cand_raws]
                span_alnum = normalize_compact_alnum("".join(cand_raws))
                span_alnum_card = normalize_compact_alnum("".join(cand_cardinal_raws))
                span_alpha = re.sub(r"[^a-z]", "", "".join(cand_raws).lower())
                span_alphas = [re.sub(r"[^a-z]", "", r.lower()) for r in cand_raws]
                if not span_alnum:
                    continue

                is_oov_span = (span_len == 1 and cand_raws[0].lower() not in log_freqs)

                for entry, target_word, target_alnum, target_alpha, form_infos in targets:
                    if has_dot_break and "." not in target_word:
                        continue
                    is_short_target = len(target_alnum) <= 3
                    proposed = target_word + cand_suffix
                    if orig_slice == proposed:
                        continue

                    best_match = None
                    for fm, fm_alnum, fm_alpha, fm_pkey in form_infos:
                        is_short_form = len(fm_alnum) <= 3
                        # 1. Exact normalized match
                        if span_alnum == fm_alnum or span_alnum_card == fm_alnum:
                            if is_short_form and span_len > 1 and not all(len(a) == 1 for a in span_alphas):
                                continue
                            if is_plural_strip and len(target_alnum) <= 3:
                                continue
                            cand_m = (0, "EXACT_NORMALIZED", 0.0, 0.0)
                            if best_match is None or cand_m < best_match:
                                best_match = cand_m
                            continue

                        if is_short_target or (is_plural_strip and len(target_alnum) <= 4):
                            continue
                        if not span_alpha or not fm_alpha:
                            continue

                        ratio = min(len(span_alpha), len(fm_alpha)) / max(len(span_alpha), len(fm_alpha))
                        span_pkey = english_phonetic_key(span_alpha)
                        if ratio >= 0.65 and len(span_pkey) >= 3 and len(fm_pkey) >= 3:
                            pdist = weighted_phonetic_distance(span_pkey, fm_pkey)
                            cdist = normalized_levenshtein(span_alpha, fm_alpha)
                            if pdist == 0.0 and cdist <= 0.45:
                                cand_m = (1, "PHONETIC_EXACT", pdist, cdist)
                                if best_match is None or cand_m < best_match:
                                    best_match = cand_m
                                continue
                            # Phonetic near-miss requires compatible onset consonant/vowel
                            if onset_compatible(span_pkey[0], fm_pkey[0]):
                                if pdist <= 0.08:
                                    max_cdist = 0.68
                                elif is_oov_span and len(fm_alpha) >= 6 and len(span_alpha) >= 6 and pdist <= 0.12:
                                    max_cdist = 0.58
                                elif pdist <= 0.10:
                                    max_cdist = 0.55
                                else:
                                    max_cdist = 0.48
                                if pdist <= 0.22 and cdist <= max_cdist:
                                    cand_m = (2, "PHONETIC_NEAR_MISS", pdist, cdist)
                                    if best_match is None or cand_m < best_match:
                                        best_match = cand_m
                                    continue
                                if is_oov_span and len(span_alpha) >= 6 and len(fm_alpha) >= 6 and span_alpha[0] == fm_alpha[0] and cdist <= 0.18:
                                    cand_m = (2, "PHONETIC_NEAR_MISS", pdist, cdist)
                                    if best_match is None or cand_m < best_match:
                                        best_match = cand_m
                                    continue

                        if span_len >= 2 and aligned_token_subsequence_match(span_alphas, fm_alpha):
                            cand_m = (3, "ABBREVIATION_SUBSEQUENCE", 0.10, 0.40)
                            if best_match is None or cand_m < best_match:
                                best_match = cand_m

                    if best_match is None:
                        continue

                    rank_m, match_kind, pdist, cdist = best_match

                    # Corpus-frequency collision detection
                    is_case_only_common = False
                    is_common_collision = False
                    if match_kind == "EXACT_NORMALIZED":
                        if span_len == 1 and not is_mixed_case_or_all_caps_or_symbol(target_word):
                            lf = log_freqs.get(span_toks[0].lower, 0.0)
                            if lf >= 4.5:
                                is_case_only_common = True
                                is_common_collision = True
                    else:
                        if span_len == 1:
                            lf = log_freqs.get(cand_raws[0].lower(), 0.0)
                            if lf >= 3.0:
                                is_common_collision = True
                        else:
                            tok_lfs = [log_freqs.get(r.lower(), 0.0) for r in cand_raws]
                            if all(lf >= 3.5 for lf in tok_lfs) or (any(lf >= 8.5 for lf in tok_lfs) and pdist > 0.05):
                                is_common_collision = True

                    # Tight phonetic ceiling when colliding with common English words
                    if is_common_collision and match_kind != "EXACT_NORMALIZED" and pdist > 0.12:
                        continue

                    in_context = target_word.lower() in context_before.lower()
                    slm_ok = False
                    slm_avail = slm_scorer is not None and slm_scorer.enabled and slm_eval_count < 3
                    is_high = entry["priority"] == "HIGH"

                    if is_common_collision and not in_context and slm_avail:
                        proposed_sentence = text[:span_start] + proposed + text[span_end:]
                        delta_nll = slm_scorer.score_delta(context_before, text, proposed_sentence)
                        slm_eval_count += 1
                        theta = 1.0 if is_case_only_common else (slm_theta_high if is_high else slm_theta_normal)
                        if delta_nll is not None and delta_nll <= theta:
                            slm_ok = True

                    # DMN Decision Policy
                    if is_short_target and match_kind != "EXACT_NORMALIZED":
                        action = "KEEP_ORIGINAL"
                    elif in_context:
                        action = "AUTO_REPLACE"
                    elif not is_common_collision:
                        action = "AUTO_REPLACE"
                    elif slm_avail and slm_ok:
                        action = "AUTO_REPLACE"
                    elif is_case_only_common:
                        action = "KEEP_ORIGINAL"
                    else:
                        action = "SUGGEST_CHIP"

                    if action in ("AUTO_REPLACE", "SUGGEST_CHIP"):
                        prio_val = {"HIGH": 3, "NORMAL": 2, "LOW": 1}[entry["priority"]]
                        candidates.append({
                            "start": span_start,
                            "end": span_end,
                            "orig": orig_slice,
                            "proposed": proposed,
                            "target": target_word,
                            "action": action,
                            "match_kind": match_kind,
                            "is_common_collision": is_common_collision,
                            "rank": (rank_m, round(pdist + cdist, 4), -prio_val, -span_len),
                        })

    candidates.sort(key=lambda c: (c["rank"], c["start"]))
    chosen_auto = []
    chosen_chips = []
    occupied: List[Tuple[int, int]] = []

    def overlaps(s: int, e: int) -> bool:
        return any(not (e <= os_ or s >= oe_) for os_, oe_ in occupied)

    for c in candidates:
        if overlaps(c["start"], c["end"]):
            continue
        if c["action"] == "AUTO_REPLACE":
            chosen_auto.append(c)
            occupied.append((c["start"], c["end"]))
        elif c["action"] == "SUGGEST_CHIP":
            chosen_chips.append(c)
            occupied.append((c["start"], c["end"]))

    chosen_auto.sort(key=lambda c: c["start"])
    out = []
    cur = 0
    for c in chosen_auto:
        out.append(text[cur:c["start"]])
        out.append(c["proposed"])
        cur = c["end"]
    out.append(text[cur:])
    return "".join(out), chosen_auto, chosen_chips


def evaluate():
    log_freqs = load_corpus_log_freqs()
    with open(DATASET_FILE, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    entries = dataset["dictionary_entries"]
    pos_items = dataset["positive_cases"]
    neg_items = dataset["adversarial_negatives"]

    if os.path.exists(RAW_CACHE_FILE):
        with open(RAW_CACHE_FILE, "r", encoding="utf-8") as f:
            raw_cache = json.load(f)
    else:
        rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=os.path.join(PARAKEET_DIR, "model.int8.onnx"),
            tokens=os.path.join(PARAKEET_DIR, "tokens.txt"),
            num_threads=2,
            provider="cpu",
        )
        raw_cache = {}
        for it in pos_items + neg_items:
            iid = it["id"]
            samples, sr = sf.read(os.path.join(AUDIO_DIR, f"{iid}.wav"), dtype="float32")
            st = rec.create_stream()
            st.accept_waveform(sr, samples)
            rec.decode_stream(st)
            raw_cache[iid] = st.result.text.strip()

    with open(BASELINE_300_FILE, "r", encoding="utf-8") as f:
        baseline_300 = json.load(f)["items"]

    def contains_target(text: str, target: str, ignore_case: bool = False) -> bool:
        if ignore_case:
            return target.lower() in text.lower()
        return target in text

    def run_mode(name: str, use_resolver: bool, slm_scorer: Optional[SlmScorer]):
        pos_exact = 0
        pos_ci = 0
        pos_chip_recall = 0
        lat_ms_list = []
        pos_details = []

        for it in pos_items:
            iid = it["id"]
            raw = raw_cache[iid]
            target = it["target"]
            ctx = it.get("context_before", "")
            t0 = time.perf_counter()
            if not use_resolver:
                out, autos, chips = raw, [], []
            else:
                out, autos, chips = resolve_user_dict(raw, ctx, entries, log_freqs, slm_scorer)
            lat_ms_list.append((time.perf_counter() - t0) * 1000.0)

            hit_exact = contains_target(out, target, ignore_case=False)
            hit_ci = contains_target(out, target, ignore_case=True)
            chip_hit = hit_exact or any(target in c["proposed"] for c in chips)
            pos_exact += int(hit_exact)
            pos_ci += int(hit_ci)
            pos_chip_recall += int(chip_hit)
            pos_details.append({
                "id": iid, "category": it["category"], "target": target,
                "context_before": ctx, "raw": raw, "out": out,
                "hit_exact": hit_exact, "chip_hit": chip_hit,
                "autos": autos, "chips": chips,
            })

        neg_fp_auto = 0
        neg_fp_chip = 0
        neg_details = []
        neg_all = []
        for it in neg_items:
            iid = it["id"]
            raw = raw_cache[iid]
            t0 = time.perf_counter()
            if not use_resolver:
                out, autos, chips = raw, [], []
            else:
                out, autos, chips = resolve_user_dict(raw, "", entries, log_freqs, slm_scorer)
            lat_ms_list.append((time.perf_counter() - t0) * 1000.0)
            neg_all.append({"id": iid, "lookalike": it["lookalike_for"], "raw": raw, "out": out})
            if out != raw or autos:
                neg_fp_auto += 1
                neg_details.append({"id": iid, "lookalike": it["lookalike_for"], "raw": raw, "out": out, "autos": autos})
            if chips:
                neg_fp_chip += 1

        base300_fp = 0
        base300_tp = 0
        base300_fp_details = []
        base300_tp_details = []
        for iid, item in baseline_300.items():
            raw = item["prediction"]
            gt = item["ground_truth"]
            if not use_resolver:
                out, autos, _ = raw, [], []
            else:
                out, autos, _ = resolve_user_dict(raw, "", entries, log_freqs, slm_scorer)
            if out != raw:
                all_gt_matched = all(a["target"].lower() in gt.lower() for a in autos)
                if all_gt_matched:
                    base300_tp += 1
                    base300_tp_details.append({"id": iid, "gt": gt, "raw": raw, "out": out, "autos": autos})
                else:
                    base300_fp += 1
                    base300_fp_details.append({"id": iid, "gt": gt, "raw": raw, "out": out, "autos": autos})

        n_pos = len(pos_items)
        n_neg = len(neg_items)
        n_b300 = len(baseline_300)
        summary = {
            "mode": name,
            "pos_exact_twrr_pct": round(pos_exact / n_pos * 100, 2),
            "pos_exact_count": f"{pos_exact}/{n_pos}",
            "pos_ci_twrr_pct": round(pos_ci / n_pos * 100, 2),
            "pos_chip_recall_pct": round(pos_chip_recall / n_pos * 100, 2),
            "pos_chip_count": f"{pos_chip_recall}/{n_pos}",
            "adv_auto_fpr_pct": round(neg_fp_auto / n_neg * 100, 2),
            "adv_auto_fp_count": f"{neg_fp_auto}/{n_neg}",
            "adv_chip_count": f"{neg_fp_chip}/{n_neg}",
            "baseline300_tp_count": base300_tp,
            "baseline300_fpr_pct": round(base300_fp / n_b300 * 100, 2),
            "baseline300_fp_count": f"{base300_fp}/{n_b300}",
            "avg_latency_ms": round(sum(lat_ms_list) / len(lat_ms_list), 3),
        }
        return summary, pos_details, neg_details, neg_all, base300_fp_details, base300_tp_details

    s_base, _, _, _, _, _ = run_mode("Baseline (Current JotInput)", False, None)
    s_no_slm, pos_no_slm, neg_no_slm, _, _, _ = run_mode("Improved (SLM-OFF)", True, None)
    slm = SlmScorer(enabled=True)
    try:
        s_slm, pos_slm, neg_slm, neg_all_slm, b300_fp_slm, b300_tp_slm = run_mode("Improved (SLM-ON)", True, slm)
        slm_delta_cache = {f"{k[0]}|||{k[1]}|||{k[2]}": round(v, 4) for k, v in slm.cache.items()}
    finally:
        slm.close()

    for s in [s_base, s_no_slm, s_slm]:
        print(
            f"[{s['mode']}] "
            f"Exact TWRR={s['pos_exact_twrr_pct']}% ({s['pos_exact_count']}) | "
            f"CI TWRR={s['pos_ci_twrr_pct']}% | "
            f"Chip+Auto={s['pos_chip_recall_pct']}% ({s['pos_chip_count']}) | "
            f"Adv FPR={s['adv_auto_fpr_pct']}% ({s['adv_auto_fp_count']}, chips={s['adv_chip_count']}) | "
            f"Base300 TP={s['baseline300_tp_count']} FPR={s['baseline300_fpr_pct']}% ({s['baseline300_fp_count']}) | "
            f"AvgLat={s['avg_latency_ms']}ms"
        )

    print("\n--- Residual Positive Misses (SLM-ON) ---")
    for d in pos_slm:
        if not d["hit_exact"]:
            print(f"  {d['id']} target={d['target']:15s} | RAW={d['raw']} -> OUT={d['out']} | chips={d['chips']}")

    print("\n--- Adversarial False Positives (SLM-ON) ---")
    for d in neg_slm:
        print(f"  {d['id']} ({d['lookalike']}): {d['raw']} -> {d['out']}")

    print("\n--- Baseline-300 True Positive Fixes (SLM-ON) ---")
    for d in b300_tp_slm:
        print(f"  {d['id']}: {d['raw']} -> {d['out']}")

    print("\n--- Baseline-300 False Positives (SLM-ON) ---")
    for d in b300_fp_slm:
        print(f"  {d['id']}: {d['raw']} -> {d['out']} (GT={d['gt']})")

    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(
            {
                "summaries": [s_base, s_no_slm, s_slm],
                "pos_slm_off": pos_no_slm,
                "pos_slm_on": pos_slm,
                "neg_slm_on_all": neg_all_slm,
                "neg_slm_on_fps": neg_slm,
                "baseline300_tp": b300_tp_slm,
                "baseline300_fp": b300_fp_slm,
                "slm_delta_cache": slm_delta_cache,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )


if __name__ == "__main__":
    evaluate()
