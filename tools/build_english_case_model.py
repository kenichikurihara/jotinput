#!/usr/bin/env python3
"""
Builds corpus-derived statistical English capitalization / truecasing models and
unified prefix/typo vocabulary:

1. app/src/main/resources/english/english_case_lexicon.tsv
   - Empirical P_lex(surface | lower_word) aggregated across:
     (A) General English prose: NLTK Brown/Reuters/Webtext (or immutable NLTK seed)
     (B) Debian English prose: /var/lib/apt/lists/*Translation-en & third-party Packages
     (C) Multi-LLM tokenizer consensus: ggml-vocab-*.gguf (>=3 tokenizers) & docs/*.md prose
     (D) Mozc OSS English dictionary surfaces
     (E) FUTO Swipe vocab.txt + Aspell en_US
   - Includes apostrophe-stripped contraction mappings (e.g., im -> I'm, ive -> I've).
   - Filters out regular noun possessives ('s) so prefix completions stay clean.
2. app/src/main/resources/english/english_lower_vocab.txt
   - Known lowercase English vocabulary with log-frequency (`word\\tlog_freq`),
     strictly guarded against acronym pollution for OOV acronym detection.
3. app/src/main/resources/english/english_acro_char_lm.tsv
   - 2-state Character Bigram LM log-likelihood ratios: log P_acro(b|a) - log P_word(b|a).
4. app/src/main/resources/english/english_abbrev_eos.tsv
   - Empirical P(EOS | token, '.') for Punkt-style sentence boundary disambiguation.
5. tools/models/english_spelling/vocab.txt
   - Unified vocabulary sorted by descending corpus frequency (preserving canonical casing)
     for native C++ EnglishSpellEngine SymSpell & prefix completion.
"""

import glob
import math
import os
import re
import struct
import subprocess
from collections import Counter, defaultdict

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FUTO_VOCAB_PATH = os.path.join(REPO_ROOT, "tools", "futo_swipe", "vocab.txt")
MOZC_OSS_DIR = os.path.join(REPO_ROOT, "tools", "mozc", "src", "data", "dictionary_oss")
GGUF_VOCAB_GLOB = os.path.join(REPO_ROOT, "tools", "llama_cpp_src", "models", "ggml-vocab-*.gguf")
DOCS_GLOB = os.path.join(REPO_ROOT, "docs", "*.md")
SEED_LEXICON_PATH = os.path.join(REPO_ROOT, "tools", "models", "english_spelling", "nltk_case_lexicon_seed.tsv")
SEED_LOWER_PATH = os.path.join(REPO_ROOT, "tools", "models", "english_spelling", "nltk_lower_vocab_seed.txt")
SPELL_VOCAB_PATH = os.path.join(REPO_ROOT, "tools", "models", "english_spelling", "vocab.txt")
OUT_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "resources", "english")

WORD_RE = re.compile(r"^[A-Za-z][A-Za-z0-9'\-]{1,28}$")
ALPHA_ONLY_RE = re.compile(r"^[A-Za-z]+$")
URL_EMAIL_SLUG_RE = re.compile(
    r"https?://\S+|www\.\S+|[a-z0-9_.\-]+@[a-z0-9_.\-]+|\b[a-z0-9]+(?:-[a-z0-9]+){2,}\b"
)
PROSE_TOK_RE = re.compile(r"(?<![/\.\-_])\b([A-Za-z][A-Za-z0-9']{1,28})\b(?![/\.\-_])")
CODE_AND_QUOTES_RE = re.compile(r"```.*?```|`[^`]+`|'[^'\n]+'|\"[^\"\n]+\"", re.S)
DOCS_TOK_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9]{2,18})\b")

KOTLIN_IDENTIFIER_SUFFIXES = (
    "Activity", "Service", "Manager", "Repository", "Engine", "Test",
    "Judgement", "Detector", "Processor", "Arbitrator", "Adapter",
    "Downloader", "Tracker", "View", "Model", "Policy", "Result",
    "Event", "Store", "Bridge", "Helper", "Dialog", "Fragment",
    "Factory", "Builder", "Parser", "Scorer", "Ranker", "Validator",
    "Cache", "State", "Action", "Params", "Config", "Utils",
    "Entry", "Node", "Token", "Candidate", "Candidates", "Table",
    "Spec", "Specs", "Report", "Mode", "Shape", "Variant", "Variants",
)

VALID_CONTRACTIONS = {
    "i'm", "i've", "i'll", "i'd",
    "let's", "don't", "can't", "won't", "it's",
    "that's", "what's", "there's", "here's",
    "you're", "you've", "you'll", "you'd",
    "we're", "we've", "we'll", "we'd",
    "they're", "they've", "they'll", "they'd",
    "didn't", "doesn't", "isn't", "aren't",
    "wasn't", "weren't", "haven't", "hasn't", "hadn't",
    "wouldn't", "couldn't", "shouldn't", "mustn't",
    "ain't", "ma'am", "o'clock", "y'all",
    "he's", "she's", "who's", "how's", "where's", "when's", "why's",
    "he'd", "she'd", "he'll", "she'll", "that'll", "it'll",
    "a's", "abcs",
}

NLTK_PROSE_WEIGHT = 4.0


def is_all_caps_headline(tokens):
    alpha_toks = [t for t in tokens if any(c.isalpha() for c in t)]
    if len(alpha_toks) < 2:
        return False
    upper_cnt = sum(1 for t in alpha_toks if len(t) > 1 and t.isupper())
    return (upper_cnt / len(alpha_toks)) > 0.40


def is_camel_or_mixed(word):
    return any(c.islower() for c in word) and any(c.isupper() for c in word[1:])


def read_gguf_tokens(path):
    try:
        with open(path, "rb") as f:
            if f.read(4) != b"GGUF":
                return []
            _, _, n_kv = struct.unpack("<IQQ", f.read(20))

            def read_str():
                n = struct.unpack("<Q", f.read(8))[0]
                return f.read(n)

            def skip_val(vtype):
                if vtype in (0, 1, 7):
                    f.read(1)
                elif vtype in (2, 3):
                    f.read(2)
                elif vtype in (4, 5, 6):
                    f.read(4)
                elif vtype in (10, 11, 12):
                    f.read(8)
                elif vtype == 8:
                    read_str()
                elif vtype == 9:
                    etype, cnt = struct.unpack("<IQ", f.read(12))
                    for _ in range(cnt):
                        skip_val(etype)

            for _ in range(n_kv):
                key = read_str().decode("utf-8", errors="ignore")
                vtype = struct.unpack("<I", f.read(4))[0]
                if key == "tokenizer.ggml.tokens" and vtype == 9:
                    _, cnt = struct.unpack("<IQ", f.read(12))
                    return [read_str().decode("utf-8", errors="ignore") for _ in range(cnt)]
                skip_val(vtype)
    except Exception:
        return []
    return []


def load_nltk_live(case_counts, lower_prose_counts, seed_lower_set, contraction_keys,
                   acro_types, word_types, period_mid_counts, period_eos_counts):
    try:
        import nltk
        nltk_dir = os.environ.get("NLTK_DATA", "/tmp/nltk_priv")
        nltk.data.path.insert(0, nltk_dir)
        from nltk.corpus import brown, reuters, webtext
        all_sents = list(brown.sents()) + list(reuters.sents()) + list(webtext.sents())
    except Exception:
        return False

    raw_case = defaultdict(Counter)
    raw_lower = Counter()

    for sent in all_sents:
        if not sent or is_all_caps_headline(sent):
            continue

        first_idx = 0
        while first_idx < len(sent) and not any(c.isalpha() for c in sent[first_idx]):
            first_idx += 1

        for i, tok in enumerate(sent):
            if len(tok) >= 2 and tok.endswith(".") and any(c.isalpha() for c in tok):
                stem = tok[:-1].lower()
                if ALPHA_ONLY_RE.match(stem):
                    if i < len(sent) - 1:
                        period_mid_counts[stem] += 1
                    else:
                        period_eos_counts[stem] += 1
            if tok == "." and i > 0:
                prev = sent[i - 1].lower()
                if ALPHA_ONLY_RE.match(prev):
                    if i < len(sent) - 1:
                        period_mid_counts[prev] += 1
                    else:
                        period_eos_counts[prev] += 1

            if i <= first_idx:
                continue
            if i > 0 and sent[i - 1] in {'"', "''", "``", ":", ";", "--", "-"}:
                continue
            if not WORD_RE.match(tok):
                continue

            low = tok.lower()
            raw_case[low][tok] += 1

            if tok.islower() and ALPHA_ONLY_RE.match(tok):
                raw_lower[low] += 1
                if 2 <= len(tok) <= 10:
                    word_types[low] += 1
            elif len(tok) >= 2 and tok.isupper() and ALPHA_ONLY_RE.match(tok):
                if 2 <= len(tok) <= 6:
                    acro_types[low] += 1

            if "'" in tok and low in VALID_CONTRACTIONS:
                stripped_low = tok.replace("'", "").lower()
                if stripped_low and ALPHA_ONLY_RE.match(stripped_low):
                    raw_case[stripped_low][tok] += 1

    for low, cnts in raw_case.items():
        for surf, cnt in cnts.items():
            case_counts[low][surf] += cnt * NLTK_PROSE_WEIGHT
            if surf == low and ALPHA_ONLY_RE.match(surf):
                lower_prose_counts[low] += cnt * NLTK_PROSE_WEIGHT
            if "'" in surf and (cnt / sum(cnts.values())) >= 0.40 and surf.replace("'", "").lower() == low:
                contraction_keys.add(low)

    for low, cnt in raw_lower.items():
        if cnt >= 2 and 2 <= len(low) <= 24:
            seed_lower_set.add(low)
            if low not in case_counts:
                case_counts[low][low] += cnt * NLTK_PROSE_WEIGHT
            lower_prose_counts[low] += cnt * NLTK_PROSE_WEIGHT

    return True


def load_nltk_seed(case_counts, lower_prose_counts, seed_lower_set, contraction_keys):
    if os.path.exists(SEED_LEXICON_PATH):
        with open(SEED_LEXICON_PATH, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.strip().split("\t")
                if len(parts) < 3:
                    continue
                low, log_freq, var_str = parts[0], float(parts[1]), parts[2]
                tot = max(2.0, math.expm1(log_freq)) * NLTK_PROSE_WEIGHT
                for vp in var_str.split(","):
                    colon = vp.rfind(":")
                    if colon <= 0:
                        continue
                    surf = vp[:colon]
                    prob = float(vp[colon + 1:])
                    case_counts[low][surf] += tot * prob
                    if surf == low and ALPHA_ONLY_RE.match(surf):
                        lower_prose_counts[low] += tot * prob
                    if "'" in surf and prob >= 0.40 and surf.replace("'", "").lower() == low:
                        contraction_keys.add(low)

    if os.path.exists(SEED_LOWER_PATH):
        with open(SEED_LOWER_PATH, "r", encoding="utf-8") as f:
            for line in f:
                w = line.strip().split("\t")[0]
                if not w:
                    continue
                seed_lower_set.add(w)
                if w not in case_counts:
                    case_counts[w][w] += 3.0 * NLTK_PROSE_WEIGHT
                lower_prose_counts[w] += 3.0 * NLTK_PROSE_WEIGHT


def load_debian_prose(case_counts, lower_prose_counts, contraction_keys):
    apt_files = sorted(glob.glob("/var/lib/apt/lists/*Translation-en"))
    for p in sorted(glob.glob("/var/lib/apt/lists/*Packages*")):
        if "deb.debian.org_debian_dists" not in os.path.basename(p):
            apt_files.append(p)

    for p in apt_files:
        with open(p, "r", encoding="utf-8", errors="ignore") as f:
            in_desc = False
            buf = []
            for line in f:
                if line.startswith("Description:") or line.startswith("Description-en:"):
                    in_desc = True
                    buf = [line.split(":", 1)[1]]
                elif in_desc and line.startswith(" "):
                    stripped_line = line.strip()
                    if stripped_line == "." or stripped_line.startswith(("*", "-")):
                        buf.append("\n" + stripped_line)
                    else:
                        buf.append(" " + stripped_line)
                else:
                    if in_desc and buf:
                        full_text = URL_EMAIL_SLUG_RE.sub(" ", "".join(buf))
                        for sent in re.split(r"(?<=[.!?:;])\s+|\n+", full_text.strip()):
                            toks = PROSE_TOK_RE.findall(sent)
                            if is_all_caps_headline(toks):
                                continue
                            for idx, tok in enumerate(toks):
                                if idx == 0:
                                    continue
                                low = tok.lower()
                                w_deb = 0.05 if low in contraction_keys else 1.0
                                case_counts[low][tok] += w_deb
                                if tok.islower() and ALPHA_ONLY_RE.match(tok):
                                    lower_prose_counts[low] += w_deb
                                if "'" in tok and low in VALID_CONTRACTIONS:
                                    case_counts[low.replace("'", "")][tok] += 1.0
                        buf = []
                    in_desc = False


def load_gguf_and_docs(case_counts, contraction_keys):
    gguf_surf_files = Counter()
    for path in sorted(glob.glob(GGUF_VOCAB_GLOB)):
        seen = set()
        for t in read_gguf_tokens(path):
            if t.startswith("\u2581") or t.startswith("\u0120"):
                w = t[1:]
                if WORD_RE.match(w) and w not in seen:
                    seen.add(w)
                    gguf_surf_files[w] += 1

    for w, cnt in gguf_surf_files.items():
        if cnt < 3:
            continue
        low = w.lower()
        if low in contraction_keys:
            continue
        if is_camel_or_mixed(w):
            case_counts[low][w] += cnt * 0.5
        elif low not in case_counts and w.islower():
            case_counts[low][low] += cnt * 0.5

    docs_mixed = Counter()
    for path in sorted(glob.glob(DOCS_GLOB)):
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            txt = CODE_AND_QUOTES_RE.sub(" ", f.read())
        for sent in re.split(r"(?<=[.!?])\s+|\n", txt):
            if sent.lstrip().startswith("|"):
                continue
            toks = DOCS_TOK_RE.findall(sent)
            for idx, tok in enumerate(toks):
                if idx == 0:
                    continue
                if tok[0].isupper() and is_camel_or_mixed(tok) and not tok.isupper():
                    if not tok.endswith(KOTLIN_IDENTIFIER_SUFFIXES):
                        docs_mixed[tok] += 1

    for tok, cnt in docs_mixed.items():
        if cnt >= 3:
            low = tok.lower()
            if low not in contraction_keys:
                case_counts[low][tok] += min(cnt * 0.5, 5.0)


def load_mozc_oss_english(case_counts, contraction_keys):
    for p in sorted(glob.glob(os.path.join(MOZC_OSS_DIR, "dictionary*.txt"))):
        with open(p, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 5:
                    surf = parts[4].strip()
                    if WORD_RE.match(surf) and not surf.islower():
                        low = surf.lower()
                        if low not in contraction_keys:
                            case_counts[low][surf] += 2.0


def load_futo_and_aspell(case_counts, lower_prose_counts, seed_lower_set):
    if os.path.exists(FUTO_VOCAB_PATH):
        with open(FUTO_VOCAB_PATH, "r", encoding="utf-8") as f:
            for rank, line in enumerate(f):
                w = line.strip()
                if not w or not WORD_RE.match(w):
                    continue
                pseudo_count = max(1.0, 1500.0 / (rank + 10))
                low = w.lower()
                if w[0].isupper() or ("'" in w and low in VALID_CONTRACTIONS):
                    case_counts[low][w] += pseudo_count
                    if "'" in w and low in VALID_CONTRACTIONS:
                        stripped_low = w.replace("'", "").lower()
                        if stripped_low and ALPHA_ONLY_RE.match(stripped_low):
                            case_counts[stripped_low][w] += min(pseudo_count, 5.0)
                elif low not in case_counts:
                    case_counts[low][low] += pseudo_count
                elif low in seed_lower_set:
                    existing = case_counts[low]
                    non_low = sum(c for s, c in existing.items() if s != low)
                    if existing[low] >= non_low:
                        case_counts[low][low] += pseudo_count

    aspell_low_set = set()
    try:
        out = subprocess.check_output(["aspell", "-d", "en_US", "dump", "master"], text=True)
        for line in out.splitlines():
            w = line.strip()
            if not w or not WORD_RE.match(w):
                continue
            if w.endswith("'s") and w.lower() not in VALID_CONTRACTIONS:
                continue
            low = w.lower()
            if w.islower() and ALPHA_ONLY_RE.match(w):
                aspell_low_set.add(w)
                if low not in case_counts:
                    case_counts[low][w] += 3.0
                lower_prose_counts[w] += 3.0
            elif w[0].isupper() and not w.isupper() and low not in case_counts:
                case_counts[low][w] += 2.0
    except Exception:
        pass
    return aspell_low_set


def write_nltk_derived_auxiliary_models(acro_types, word_types, period_mid_counts, period_eos_counts):
    def train_char_bigram(type_counts, alpha=0.5):
        bg = defaultdict(float)
        uni = defaultdict(float)
        for w in type_counts:
            s = "^" + w + "$"
            for a, b in zip(s, s[1:]):
                bg[(a, b)] += 1.0
                uni[a] += 1.0
        vocab_size = 27
        probs = {}
        for a in "^abcdefghijklmnopqrstuvwxyz":
            denom = uni[a] + alpha * vocab_size
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
                p_eos = (eos + 0.5) / (tot + 1.0)
                if p_eos < 0.20:
                    f.write(f"{stem}\t{p_eos:.4f}\t{tot}\n")
                    abbrev_entries += 1

    print(f"Regenerated english_acro_char_lm.tsv ({os.path.getsize(char_lm_path)} bytes)")
    print(f"Regenerated english_abbrev_eos.tsv: {abbrev_entries} entries ({os.path.getsize(abbrev_path)} bytes)")


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(SPELL_VOCAB_PATH), exist_ok=True)

    case_counts = defaultdict(Counter)
    lower_prose_counts = Counter()
    seed_lower_set = set()
    contraction_keys = set()

    acro_types = Counter()
    word_types = Counter()
    period_mid_counts = Counter()
    period_eos_counts = Counter()

    used_live_nltk = load_nltk_live(
        case_counts, lower_prose_counts, seed_lower_set, contraction_keys,
        acro_types, word_types, period_mid_counts, period_eos_counts
    )
    if not used_live_nltk:
        load_nltk_seed(case_counts, lower_prose_counts, seed_lower_set, contraction_keys)

    load_debian_prose(case_counts, lower_prose_counts, contraction_keys)
    load_gguf_and_docs(case_counts, contraction_keys)
    load_mozc_oss_english(case_counts, contraction_keys)
    aspell_low_set = load_futo_and_aspell(case_counts, lower_prose_counts, seed_lower_set)

    lexicon_path = os.path.join(OUT_DIR, "english_case_lexicon.tsv")
    lower_vocab_path = os.path.join(OUT_DIR, "english_lower_vocab.txt")

    lex_entries = 0
    lower_entries = 0
    unified_vocab = []

    with open(lexicon_path, "w", encoding="utf-8") as f_lex, \
            open(lower_vocab_path, "w", encoding="utf-8") as f_low:
        for low in sorted(case_counts.keys()):
            if len(low) > 28:
                continue
            if low.endswith("'s") and low not in VALID_CONTRACTIONS:
                continue

            counts = case_counts[low]
            total = sum(counts.values())
            if total < 3.0:
                continue

            log_freq = math.log1p(total)
            low_cnt = counts.get(low, 0.0)
            lp_cnt = lower_prose_counts.get(low, 0.0)

            if ALPHA_ONLY_RE.match(low) and 2 <= len(low) <= 24:
                in_general_lower = (low in seed_lower_set) or (low in aspell_low_set)
                if (low_cnt / total >= 0.65 and (in_general_lower or lp_cnt >= 10.0)) or (
                    low in seed_lower_set and low in aspell_low_set and low_cnt / total >= 0.20
                ):
                    f_low.write(f"{low}\t{log_freq:.3f}\n")
                    lower_entries += 1

            variants = []
            has_non_lower = False
            for surf, cnt in counts.most_common(4):
                prob = cnt / total
                if prob >= 0.03:
                    variants.append((surf, prob))
                    if surf != low:
                        has_non_lower = True

            if has_non_lower:
                v_sum = sum(p for _, p in variants)
                var_str = ",".join(f"{s}:{p / v_sum:.4f}" for s, p in variants)
                f_lex.write(f"{low}\t{log_freq:.3f}\t{var_str}\n")
                lex_entries += 1

            top_surf = counts.most_common(1)[0][0]
            # Exclude apostrophe-stripped contraction aliases (e.g., dont -> don't stays in lexicon only)
            if top_surf.lower() == low:
                unified_vocab.append((total, top_surf))

    unified_vocab.sort(key=lambda item: (-item[0], item[1]))
    seen_spell_lower = set()
    spell_count = 0
    with open(SPELL_VOCAB_PATH, "w", encoding="utf-8") as f_spell:
        for _, surf in unified_vocab:
            low = surf.lower()
            if len(low) < 2 and low not in ("a", "i"):
                continue
            if low in seen_spell_lower:
                continue
            seen_spell_lower.add(low)
            f_spell.write(f"{surf}\n")
            spell_count += 1

    if used_live_nltk:
        write_nltk_derived_auxiliary_models(acro_types, word_types, period_mid_counts, period_eos_counts)

    print(f"Generated english_case_lexicon.tsv: {lex_entries} entries ({os.path.getsize(lexicon_path)} bytes)")
    print(f"Generated english_lower_vocab.txt:  {lower_entries} entries ({os.path.getsize(lower_vocab_path)} bytes)")
    print(f"Generated english_spelling/vocab.txt: {spell_count} entries ({os.path.getsize(SPELL_VOCAB_PATH)} bytes)")


if __name__ == "__main__":
    main()
