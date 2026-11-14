#!/usr/bin/env python3
"""
Builds corpus-derived statistical English capitalization / truecasing models:
1. app/src/main/resources/english/english_case_lexicon.tsv
   - Empirical P_lex(surface | lower_word) from Brown, Reuters, Webtext, NPS Chat,
     and tools/futo_swipe/vocab.txt (excluding sentence-initial tokens and ALL-CAPS headlines).
   - Includes apostrophe-stripped contraction mappings (e.g., im -> I'm, ive -> I've).
2. app/src/main/resources/english/english_lower_vocab.txt
   - Known lowercase English vocabulary (for OOV acronym gating).
3. app/src/main/resources/english/english_acro_char_lm.tsv
   - 2-state Character Bigram LM log-likelihood ratios: log P_acro(b|a) - log P_word(b|a).
4. app/src/main/resources/english/english_abbrev_eos.tsv
   - Empirical P(EOS | token, '.') for Punkt-style sentence boundary disambiguation.
"""

import math
import os
import re
from collections import Counter, defaultdict

import nltk

NLTK_DIR = os.environ.get("NLTK_DATA", "/tmp/nltk_priv")
nltk.data.path.insert(0, NLTK_DIR)

from nltk.corpus import brown, reuters, webtext

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FUTO_VOCAB_PATH = os.path.join(REPO_ROOT, "tools", "futo_swipe", "vocab.txt")
OUT_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "resources", "english")

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]*$")
ALPHA_ONLY_RE = re.compile(r"^[A-Za-z]+$")


def is_all_caps_headline(sent):
    alpha_toks = [t for t in sent if any(c.isalpha() for c in t)]
    if len(alpha_toks) < 2:
        return False
    upper_cnt = sum(1 for t in alpha_toks if len(t) > 1 and t.isupper())
    return (upper_cnt / len(alpha_toks)) > 0.40


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    case_counts = defaultdict(Counter)
    lower_vocab_counts = Counter()
    acro_types = Counter()
    word_types = Counter()

    # For Punkt-style abbreviation EOS statistics:
    # Track tokens immediately preceding '.' when '.' is mid-sentence vs end-of-sentence.
    period_mid_counts = Counter()
    period_eos_counts = Counter()

    all_sents = (
        list(brown.sents())
        + list(reuters.sents())
        + list(webtext.sents())
    )

    for sent in all_sents:
        if not sent or is_all_caps_headline(sent):
            continue

        # Find first alphabetic token index (sentence-initial position)
        first_idx = 0
        while first_idx < len(sent) and not any(c.isalpha() for c in sent[first_idx]):
            first_idx += 1

        # Abbreviation / period statistics within sentence
        for i, tok in enumerate(sent):
            # Case 1: tokenized with trailing period e.g. "Mr." or "U.S."
            if len(tok) >= 2 and tok.endswith(".") and any(c.isalpha() for c in tok):
                stem = tok[:-1].lower()
                if ALPHA_ONLY_RE.match(stem):
                    if i < len(sent) - 1:
                        period_mid_counts[stem] += 1
                    else:
                        period_eos_counts[stem] += 1
            # Case 2: separate "." token following a word
            if tok == "." and i > 0:
                prev = sent[i - 1].lower()
                if ALPHA_ONLY_RE.match(prev):
                    if i < len(sent) - 1:
                        period_mid_counts[prev] += 1
                    else:
                        period_eos_counts[prev] += 1

            # Skip sentence-initial token or quoted-utterance-initial token for truecasing counts
            if i <= first_idx:
                continue
            if i > 0 and sent[i - 1] in {'"', "''", "``", ":", ";", "--", "-"}:
                continue

            if not WORD_RE.match(tok):
                continue

            low = tok.lower()
            case_counts[low][tok] += 1

            if tok.islower() and ALPHA_ONLY_RE.match(tok):
                lower_vocab_counts[low] += 1
                if 2 <= len(tok) <= 10:
                    word_types[low] += 1
            elif len(tok) >= 2 and tok.isupper() and ALPHA_ONLY_RE.match(tok):
                if 2 <= len(tok) <= 6:
                    acro_types[low] += 1

            # Apostrophe-omitted contraction mapping (e.g. I'm -> im, I've -> ive)
            if "'" in tok:
                stripped = tok.replace("'", "")
                stripped_low = stripped.lower()
                if stripped_low and tok != stripped_low and ALPHA_ONLY_RE.match(stripped_low):
                    # Only map if the token has uppercase (e.g. I'm, I've, I'll, I'd, O'Brien)
                    if any(c.isupper() for c in tok):
                        case_counts[stripped_low][tok] += 1

    # Merge FUTO Swipe vocab.txt (32,768 words ranked by Zipf frequency)
    # Note: Do NOT add vocab.txt entries to lower_vocab_counts, because vocab.txt
    # lowercases acronyms (e.g. cpu, html, pdf, xml).
    if os.path.exists(FUTO_VOCAB_PATH):
        with open(FUTO_VOCAB_PATH, "r", encoding="utf-8") as f:
            for rank, line in enumerate(f):
                w = line.strip()
                if not w or not WORD_RE.match(w):
                    continue
                pseudo_count = max(1, int(500.0 / (rank + 10)))
                low = w.lower()
                if w[0].isupper() or "'" in w:
                    case_counts[low][w] += pseudo_count
                if "'" in w and any(c.isupper() for c in w):
                    stripped_low = w.replace("'", "").lower()
                    if stripped_low and ALPHA_ONLY_RE.match(stripped_low):
                        case_counts[stripped_low][w] += pseudo_count

    # 1. Write english_case_lexicon.tsv
    lexicon_path = os.path.join(OUT_DIR, "english_case_lexicon.tsv")
    lex_entries = 0
    with open(lexicon_path, "w", encoding="utf-8") as f:
        for low in sorted(case_counts.keys()):
            if len(low) > 32:
                continue
            counts = case_counts[low]
            total = sum(counts.values())
            if total < 2:
                continue
            # Keep variants with probability >= 0.03 (and at least top 4)
            variants = []
            has_non_lower = False
            for surf, cnt in counts.most_common(4):
                prob = cnt / total
                if prob >= 0.03:
                    variants.append((surf, prob))
                    if surf != low:
                        has_non_lower = True
            if not has_non_lower:
                continue
            # Renormalize kept variants
            v_sum = sum(p for _, p in variants)
            var_str = ",".join(f"{s}:{p/v_sum:.4f}" for s, p in variants)
            log_freq = math.log1p(total)
            f.write(f"{low}\t{log_freq:.3f}\t{var_str}\n")
            lex_entries += 1

    # 2. Write english_lower_vocab.txt
    lower_vocab_path = os.path.join(OUT_DIR, "english_lower_vocab.txt")
    lower_entries = 0
    with open(lower_vocab_path, "w", encoding="utf-8") as f:
        for low in sorted(lower_vocab_counts.keys()):
            if lower_vocab_counts[low] >= 2 and 2 <= len(low) <= 24:
                f.write(f"{low}\n")
                lower_entries += 1

    # 3. Train & write 2-state Character Bigram Acronym LM (english_acro_char_lm.tsv)
    def train_char_bigram(type_counts, alpha=0.5):
        bg = defaultdict(float)
        uni = defaultdict(float)
        for w, c in type_counts.items():
            s = "^" + w + "$"
            for a, b in zip(s, s[1:]):
                bg[(a, b)] += 1.0
                uni[a] += 1.0
        V = 27
        probs = {}
        for a in "^abcdefghijklmnopqrstuvwxyz":
            denom = uni[a] + alpha * V
            for b in "abcdefghijklmnopqrstuvwxyz$":
                probs[(a, b)] = math.log((bg[(a, b)] + alpha) / denom)
        return probs

    p_acro = train_char_bigram({w: c for w, c in acro_types.items() if c >= 2})
    p_word = train_char_bigram({w: c for w, c in word_types.items() if c >= 3})

    char_lm_path = os.path.join(OUT_DIR, "english_acro_char_lm.tsv")
    with open(char_lm_path, "w", encoding="utf-8") as f:
        for a in "^abcdefghijklmnopqrstuvwxyz":
            for b in "abcdefghijklmnopqrstuvwxyz$":
                llr = p_acro[(a, b)] - p_word[(a, b)]
                f.write(f"{a}{b}\t{llr:.4f}\n")

    # 4. Write corpus-derived Punkt-style abbreviation EOS probabilities (english_abbrev_eos.tsv)
    abbrev_path = os.path.join(OUT_DIR, "english_abbrev_eos.tsv")
    abbrev_entries = 0
    with open(abbrev_path, "w", encoding="utf-8") as f:
        all_stems = set(period_mid_counts.keys()) | set(period_eos_counts.keys())
        for stem in sorted(all_stems):
            if len(stem) > 8:
                continue
            mid = period_mid_counts[stem]
            eos = period_eos_counts[stem]
            tot = mid + eos
            if tot >= 15 and mid > eos:
                # Smoothed P(EOS | stem, '.')
                p_eos = (eos + 0.5) / (tot + 1.0)
                if p_eos < 0.20:
                    f.write(f"{stem}\t{p_eos:.4f}\t{tot}\n")
                    abbrev_entries += 1

    print(f"Generated english_case_lexicon.tsv: {lex_entries} entries ({os.path.getsize(lexicon_path)} bytes)")
    print(f"Generated english_lower_vocab.txt:  {lower_entries} entries ({os.path.getsize(lower_vocab_path)} bytes)")
    print(f"Generated english_acro_char_lm.tsv: ({os.path.getsize(char_lm_path)} bytes)")
    print(f"Generated english_abbrev_eos.tsv:   {abbrev_entries} entries ({os.path.getsize(abbrev_path)} bytes)")


if __name__ == "__main__":
    main()
