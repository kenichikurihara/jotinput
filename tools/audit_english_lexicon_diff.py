#!/usr/bin/env python3
"""
Audits diffs and safety thresholds between git HEAD and the working-tree
corpus-derived English lexicon artifacts:
  - app/src/main/resources/english/english_case_lexicon.tsv
  - app/src/main/resources/english/english_lower_vocab.txt
  - tools/models/english_spelling/vocab.txt
"""

import argparse
import os
import subprocess
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CASE_REL = "app/src/main/resources/english/english_case_lexicon.tsv"
LOWER_REL = "app/src/main/resources/english/english_lower_vocab.txt"
SPELL_REL = "tools/models/english_spelling/vocab.txt"

CAPS = {
    CASE_REL: {"max_entries": 45_000, "max_bytes": int(1.8 * 1024 * 1024)},
    LOWER_REL: {"max_entries": 85_000, "max_bytes": int(1.4 * 1024 * 1024)},
    SPELL_REL: {"max_entries": 120_000, "max_bytes": int(1.3 * 1024 * 1024)},
}

CANARY_LEXICON_SURFACES = [
    ("tailscale", "Tailscale"),
    ("kubernetes", "Kubernetes"),
    ("postgresql", "PostgreSQL"),
    ("typescript", "TypeScript"),
    ("wireguard", "WireGuard"),
    ("cloudflare", "Cloudflare"),
    ("github", "GitHub"),
    ("gitlab", "GitLab"),
    ("fastapi", "FastAPI"),
    ("pytorch", "PyTorch"),
    ("tensorflow", "TensorFlow"),
    ("terraform", "Terraform"),
    ("prometheus", "Prometheus"),
    ("grafana", "Grafana"),
    ("webassembly", "WebAssembly"),
    ("im", "I'm"),
    ("dont", "don't"),
    ("lets", "let's"),
]

CANARY_LOWER_WORDS = ["the", "tail", "tails", "an"]


def git_show_lines(rel_path):
    res = subprocess.run(
        ["git", "show", f"HEAD:{rel_path}"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if res.returncode != 0:
        return None
    return res.stdout.splitlines()


def read_work_lines(rel_path):
    abs_path = os.path.join(REPO_ROOT, rel_path)
    with open(abs_path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    return lines, os.path.getsize(abs_path)


def parse_case_lexicon(lines):
    out = {}
    for line in lines:
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        key = parts[0]
        try:
            lf = float(parts[1])
        except ValueError:
            lf = 0.0
        top_var = parts[2].split(",", 1)[0]
        colon = top_var.rfind(":")
        top_surf = top_var[:colon] if colon > 0 else top_var
        out[key] = (lf, top_surf, parts[2])
    return out


def parse_lower_vocab(lines):
    out = {}
    for line in lines:
        if not line:
            continue
        parts = line.split("\t")
        key = parts[0]
        try:
            lf = float(parts[1]) if len(parts) > 1 else 2.0
        except ValueError:
            lf = 2.0
        out[key] = lf
    return out


def parse_spell_vocab(lines):
    return [line.strip() for line in lines if line.strip()]


def main():
    parser = argparse.ArgumentParser(description="Audit English lexicon diff and hard safety gates")
    parser.add_argument("--output", help="Write Markdown audit report to path")
    parser.add_argument("--max-removal-pct", type=float, default=0.5, help="Fail if removed entries exceed this %%")
    parser.add_argument("--allow-large-drop", action="store_true", help="Do not fail on removal percentage above threshold")
    args = parser.parse_args()

    work_case_lines, case_bytes = read_work_lines(CASE_REL)
    work_lower_lines, lower_bytes = read_work_lines(LOWER_REL)
    work_spell_lines, spell_bytes = read_work_lines(SPELL_REL)

    head_case_lines = git_show_lines(CASE_REL) or work_case_lines
    head_lower_lines = git_show_lines(LOWER_REL) or work_lower_lines
    head_spell_lines = git_show_lines(SPELL_REL) or work_spell_lines

    work_case = parse_case_lexicon(work_case_lines)
    head_case = parse_case_lexicon(head_case_lines)
    work_lower = parse_lower_vocab(work_lower_lines)
    head_lower = parse_lower_vocab(head_lower_lines)
    work_spell = parse_spell_vocab(work_spell_lines)
    head_spell = parse_spell_vocab(head_spell_lines)

    head_case_bytes = sum(len(l.encode("utf-8")) + 1 for l in head_case_lines)
    head_lower_bytes = sum(len(l.encode("utf-8")) + 1 for l in head_lower_lines)

    added_case_keys = sorted(set(work_case.keys()) - set(head_case.keys()), key=lambda k: (-work_case[k][0], k))
    removed_case_keys = sorted(set(head_case.keys()) - set(work_case.keys()))
    added_lower_keys = sorted(set(work_lower.keys()) - set(head_lower.keys()))
    removed_lower_keys = sorted(set(head_lower.keys()) - set(work_lower.keys()))

    case_removal_pct = (100.0 * len(removed_case_keys) / max(1, len(head_case)))
    lower_removal_pct = (100.0 * len(removed_lower_keys) / max(1, len(head_lower)))

    cap_failures = []
    for rel, count, nbytes in [
        (CASE_REL, len(work_case), case_bytes),
        (LOWER_REL, len(work_lower), lower_bytes),
        (SPELL_REL, len(work_spell), spell_bytes),
    ]:
        max_e = CAPS[rel]["max_entries"]
        max_b = CAPS[rel]["max_bytes"]
        if count > max_e:
            cap_failures.append(f"{rel}: entries {count} > cap {max_e}")
        if nbytes > max_b:
            cap_failures.append(f"{rel}: bytes {nbytes} > cap {max_b}")

    canary_failures = []
    for key, expected_surf in CANARY_LEXICON_SURFACES:
        entry = work_case.get(key)
        if entry is None:
            canary_failures.append(f"Missing lexicon key '{key}' (expected '{expected_surf}')")
        elif entry[1] != expected_surf:
            # Allow variant present cleanly as valid casing if expected is in variants
            var_surfs = [v.rsplit(":", 1)[0] for v in entry[2].split(",")]
            if expected_surf not in var_surfs:
                canary_failures.append(f"Lexicon canary '{key}' missing surface '{expected_surf}' (got {entry[2]})")
            elif entry[1].lower() != expected_surf.lower() or entry[1] != expected_surf:
                # Top surface changed (e.g. Tailscale -> tailscale) is treated as failure for mixed-case canaries
                canary_failures.append(f"Lexicon canary '{key}' top surface drifted: expected '{expected_surf}', got '{entry[1]}'")

    for w in CANARY_LOWER_WORDS:
        if w not in work_lower:
            canary_failures.append(f"Missing lower vocab canary '{w}'")

    report = []
    report.append("## Periodic English Lexicon Diff & Safety Audit\n")
    report.append("### 1. Vocabulary & Budget Summary\n")
    report.append("| Artifact | HEAD Entries | New Entries | Added / Removed | Size Change | Budget Cap |")
    report.append("|---|---:|---:|---:|---:|---|")
    report.append(
        f"| `{CASE_REL}` | {len(head_case):,} | {len(work_case):,} | +{len(added_case_keys):,} / -{len(removed_case_keys):,} ({case_removal_pct:.2f}%) | {(case_bytes - head_case_bytes)/1024:+.1f} KB ({case_bytes/1024:.1f} KB) | <= 45,000 / 1.8 MB |"
    )
    report.append(
        f"| `{LOWER_REL}` | {len(head_lower):,} | {len(work_lower):,} | +{len(added_lower_keys):,} / -{len(removed_lower_keys):,} ({lower_removal_pct:.2f}%) | {(lower_bytes - head_lower_bytes)/1024:+.1f} KB ({lower_bytes/1024:.1f} KB) | <= 85,000 / 1.4 MB |"
    )
    report.append(
        f"| `{SPELL_REL}` | {len(head_spell):,} | {len(work_spell):,} | {len(work_spell) - len(head_spell):+,} net | {spell_bytes/1024:.1f} KB | <= 120,000 / 1.3 MB |\n"
    )

    report.append("### 2. Top-50 Newly Added Case Lexicon Words (by `log_freq`)\n")
    if added_case_keys:
        report.append("| # | Key | `log_freq` | Top Surface & Variants |")
        report.append("|---:|---|---:|---|")
        for idx, k in enumerate(added_case_keys[:50], 1):
            lf, _, var_str = work_case[k]
            report.append(f"| {idx} | `{k}` | {lf:.3f} | `{var_str}` |")
    else:
        report.append("_No new keys added._")
    report.append("")

    report.append(f"### 3. Removed Case Lexicon Keys ({len(removed_case_keys)} total)\n")
    if removed_case_keys:
        preview = ", ".join(f"`{k}`" for k in removed_case_keys[:50])
        suffix = " ..." if len(removed_case_keys) > 50 else ""
        report.append(preview + suffix)
    else:
        report.append("_No keys removed._")
    report.append("")

    report.append("### 4. Safety Gate Results\n")
    report.append(f"- Hard Budget Caps: {'PASS' if not cap_failures else 'FAIL: ' + '; '.join(cap_failures)}")
    report.append(f"- Canary Non-Regression ({len(CANARY_LEXICON_SURFACES) + len(CANARY_LOWER_WORDS)} checks): {'PASS' if not canary_failures else 'FAIL: ' + '; '.join(canary_failures)}")
    removal_failed = (case_removal_pct > args.max_removal_pct or lower_removal_pct > args.max_removal_pct)
    report.append(
        f"- Removal Threshold (<= {args.max_removal_pct:.2f}%): {'PASS' if not removal_failed else ('WARN (allowed via --allow-large-drop)' if args.allow_large_drop else 'FAIL')}"
    )

    text = "\n".join(report) + "\n"
    if args.output:
        os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text)
    else:
        print(text)

    if cap_failures or canary_failures or (removal_failed and not args.allow_large_drop):
        sys.stderr.write("Audit failed:\n" + "\n".join(cap_failures + canary_failures) + "\n")
        if removal_failed and not args.allow_large_drop:
            sys.stderr.write(
                f"Removal pct exceeded threshold ({case_removal_pct:.2f}% / {lower_removal_pct:.2f}% > {args.max_removal_pct:.2f}%)\n"
            )
        sys.exit(1)


if __name__ == "__main__":
    main()
