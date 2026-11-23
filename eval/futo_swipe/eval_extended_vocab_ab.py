#!/usr/bin/env python3
"""
eval_extended_vocab_ab.py

Reproducible Quantitative Benchmark Comparing:
  - Baseline: FUTO Swipe 32k base vocabulary only
  - Idea A + E: Confidence-gated Compact Dual-Trie (68k+ OOV words + logFreq + truecasing)
  - Idea B + E: Start/End anchor bucket pre-filter + exact CTC Forward rescoring
  - Idea C: Adaptive 500-word OOV LRU cache in Pass 1 (boost=1.2f, no subtree bonus)

Outputs structured summary JSON -> eval/futo_swipe/extended_vocab_ab_results.json
"""

import json
import math
import os
import re
import sys
import time
import numpy as np

sys.path.insert(0, os.path.abspath("tools/futo_swipe"))
import futo_swipe_pipeline as fsp

GATE_BASE_TOT = -0.50
PASS2_BEAM = 40
OOV_PENALTY_SPATIAL = 1.50
OOV_PENALTY_CONTEXT = 2.50
LOG_FREQ_WEIGHT = 0.20
ADAPTIVE_BOOST = 1.20
ADAPTIVE_MAX_BASE_GAP = 4.50
ADAPTIVE_BIAS_FLOOR = -4.20


def ctc_forward_logprob(logits: np.ndarray, seq) -> float:
    L = len(seq)
    if L == 0 or L > 32:
        return -1e9
    S = 2 * L + 1
    NEG = -1e9
    alpha = [NEG] * S
    alpha[0] = float(logits[0, 26])
    alpha[1] = float(logits[0, seq[0]])
    for t in range(1, 32):
        row = logits[t]
        nxt = [NEG] * S
        r26 = float(row[26])
        nxt[0] = alpha[0] + r26
        for s in range(1, S):
            a = alpha[s]
            b = alpha[s - 1]
            v = (a + math.log1p(math.exp(b - a)) if b > -1e8 else a) if a > b else (b + math.log1p(math.exp(a - b)) if a > -1e8 else b)
            if (s & 1) == 1:
                idx = s >> 1
                lbl = seq[idx]
                if idx > 0 and lbl != seq[idx - 1]:
                    c = alpha[s - 2]
                    if c > -1e8:
                        v = (v + math.log1p(math.exp(c - v))) if v > c else (c + math.log1p(math.exp(v - c)))
                nxt[s] = v + float(row[lbl])
            else:
                nxt[s] = v + r26
        alpha = nxt
    a, b = alpha[S - 1], alpha[S - 2]
    return (a + math.log1p(math.exp(b - a)) if b > -1e8 else a) if a > b else (b + math.log1p(math.exp(a - b)) if a > -1e8 else b)


def edit_dist(a: str, b: str) -> int:
    la, lb = len(a), len(b)
    if abs(la - lb) > 4:
        return 99
    prev = list(range(lb + 1))
    for i, ca in enumerate(a, 1):
        curr = [i] + [0] * lb
        for j, cb in enumerate(b, 1):
            cost = 0 if ca == cb else 1
            v = prev[j] + 1
            if curr[j - 1] + 1 < v:
                v = curr[j - 1] + 1
            if prev[j - 1] + cost < v:
                v = prev[j - 1] + cost
            curr[j] = v
        prev = curr
    return prev[lb]


def synth_swipe(key_centers: np.ndarray, word: str, rng: np.random.Generator, jitter: float = 0.018):
    pts, t = [], 0
    coords = [
        (
            key_centers[ord(ch) - ord("a")][0] + rng.normal(0, jitter),
            key_centers[ord(ch) - ord("a")][1] + rng.normal(0, jitter),
        )
        for ch in word.lower()
    ]
    for i, (x0, y0) in enumerate(coords):
        if i == 0:
            pts.append({"x": float(x0), "y": float(y0), "t": t})
            t += 20
        else:
            px, py = coords[i - 1]
            mx = 0.5 * (px + x0) + rng.normal(0, jitter * 0.5)
            my = 0.5 * (py + y0) + rng.normal(0, jitter * 0.5)
            for s in range(1, 6):
                u = s / 5.0
                bx = (1 - u) ** 2 * px + 2 * (1 - u) * u * mx + u ** 2 * x0
                by = (1 - u) ** 2 * py + 2 * (1 - u) * u * my + u ** 2 * y0
                pts.append({"x": float(bx), "y": float(by), "t": t})
                t += 16
    return pts


def main():
    t_start = time.time()
    engine = fsp.FutoSwipeEngine()

    futo_lower = set(w.lower() for w in engine.vocabulary)
    for w in engine.vocabulary:
        if len(w) >= 4 and (w.endswith("'s") or w.endswith("'S") or w.endswith("\u2019s")):
            stem = w[:-2].lower()
            if stem.isalpha() and stem.isascii():
                futo_lower.add(stem)

    ext_all = {}
    with open("app/src/main/resources/english/english_case_lexicon.tsv", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if len(parts) >= 3:
                k = parts[0]
                lf = float(parts[1])
                surf = parts[2].split(",")[0].rsplit(":", 1)[0]
                if k == surf.lower() and k.isalpha() and k.isascii() and len(k) >= 2:
                    ext_all[k] = (surf, lf)
    with open("app/src/main/resources/english/english_lower_vocab.txt", encoding="utf-8") as f:
        for line in f:
            parts = line.strip().split("\t")
            if parts:
                k = parts[0]
                lf = float(parts[1]) if len(parts) > 1 else 2.0
                if k.isalpha() and k.isascii() and len(k) >= 2:
                    if k not in ext_all:
                        ext_all[k] = (k, lf)
                    elif lf > ext_all[k][1]:
                        ext_all[k] = (ext_all[k][0], lf)

    oov_ext = {k: v for k, v in ext_all.items() if k not in futo_lower and len(k) >= 3}

    # Build Idea A Trie & Idea B Buckets
    ext_root = fsp.LexiconNode("", -1)
    for k, (surf, lf) in oov_ext.items():
        node = ext_root
        for ch in k:
            idx = ord(ch) - ord("a")
            if idx not in node.children:
                node.children[idx] = fsp.LexiconNode(node.text + ch, idx)
            node = node.children[idx]
        node.surfaces.append((surf, 40000, lf, False))
        node.has_plain_surface = True

    buckets = [[[] for _ in range(26)] for _ in range(26)]
    for k, (surf, lf) in oov_ext.items():
        c0 = ord(k[0]) - ord("a")
        c1 = ord(k[-1]) - ord("a")
        seq = [ord(ch) - ord("a") for ch in k]
        buckets[c0][c1].append((k, surf, lf, seq))

    # Datasets
    ds_1000 = [json.loads(line) for line in open("eval/futo_swipe/swipe_eval_1000.jsonl", encoding="utf-8") if line.strip()]

    sessions = {}
    for path in ["eval/futo_swipe/raw_test_10mb.jsonl", "eval/futo_swipe/raw_test_next20mb.jsonl"]:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    rec = json.loads(line)
                except Exception:
                    continue
                sid = rec.get("session")
                if sid:
                    sessions.setdefault(sid, []).append(rec)

    ds_real_oov = []
    seen_keys = set()
    for sid, recs in sessions.items():
        recs.sort(key=lambda r: r.get("timestamp", 0))
        for idx, rec in enumerate(recs):
            key = (sid, rec.get("timestamp"), rec.get("word"))
            if key in seen_keys:
                continue
            seen_keys.add(key)
            clean = re.sub(r"^[^\w]+|[^\w]+$", "", rec.get("word", "")).lower()
            data = rec.get("data", [])
            if clean and clean.isalpha() and len(data) >= 4 and clean in oov_ext:
                ctx = []
                for p_idx in range(max(0, idx - 3), idx):
                    pc = re.sub(r"^[^\w]+|[^\w]+$", "", recs[p_idx].get("word", "")).lower()
                    if pc and pc.isalpha():
                        ctx.append(pc)
                ds_real_oov.append({
                    "word": clean,
                    "expected_surface": oov_ext[clean][0],
                    "context_tokens": ctx,
                    "trajectory": [{"x": p["x"], "y": p["y"], "t": p.get("t", 0)} for p in data],
                })

    tech_words = [
        "tailscale", "kubernetes", "postgresql", "wireguard", "typescript",
        "homebrew", "dockerfile", "cloudflare", "graphql", "terraform", "prometheus",
        "pytorch", "vercel", "neovim", "alacritty", "ghostty", "fastapi",
        "eslint", "prettier", "webpack", "kotlin", "gradle", "onnx", "llama",
        "chatgpt", "openai", "gitlab", "bitbucket", "podman", "ansible",
        "grafana", "datadog", "sentry", "figma", "webkit", "systemd",
        "wayland", "pipewire", "flatpak", "appimage", "debian", "fedora",
        "macos", "airpods", "oauth", "jwt", "grpc", "protobuf", "websocket",
        "yaml", "toml", "markdown", "regex", "async", "coroutine", "refactor",
        "serverless", "blockchain", "cryptocurrency", "llm", "tokenizer",
        "github", "docker", "stripe", "notion", "slack", "discord", "spotify", "firefox",
    ]
    tech_oov = [w for w in tech_words if w in oov_ext]
    rng = np.random.default_rng(42)
    ds_tech_oov = [
        {"word": w, "expected_surface": oov_ext[w][0], "context_tokens": [], "trajectory": synth_swipe(engine.key_centers, w, rng)}
        for w in tech_oov
        for _ in range(2)
    ]

    def precompute(items):
        cached = []
        for it in items:
            resampled = fsp.resample_trace(it["trajectory"])
            px0, py0 = float(resampled[0, 0]), float(resampled[1, 0])
            px1, py1 = float(resampled[0, 63]), float(resampled[1, 63])
            enc_out = engine.enc_sess.run(None, {
                "features": np.expand_dims(resampled, 0),
                "layout_keys": np.expand_dims(engine.layout_keys, 0),
                "layout_mask": np.expand_dims(engine.layout_mask, 0),
            })
            combined = np.zeros((1, 32, 92), dtype=np.float32)
            combined[:, :, :26] = enc_out[0][:, :, :26]
            combined[:, :, 26] = enc_out[0][:, :, 64]
            combined[:, :, 27:91] = enc_out[1][:, :, :64]
            combined[:, :, 91] = enc_out[2][:, :, 0]
            logits = engine.dec_sess.run(None, {"features": combined})[0][0]
            prev = it["context_tokens"] if it["context_tokens"] else None
            state_vec = engine.get_context_state(prev) if prev is not None else None
            w_sp, w_cx = engine.dec_weights, engine.ctx_weights
            states = fsp.ctc_prefix_search(logits, engine.root, beam=100, gamma=w_sp["gamma_prune"], beta=w_sp["beta_prune"])

            def build_base(w_dict, sv):
                gamma, beta = w_dict["gamma"], w_dict["beta"]
                alpha = w_dict["alpha"] if sv is not None else 0.0
                best_common_tot, best_base_tot = -1e9, -1e9
                for node, mass in states.items():
                    if not node.surfaces:
                        continue
                    tot = mass.total
                    if tot > best_base_tot:
                        best_base_tot = tot
                    if any(r < 6000 for (_, r, _, _) in node.surfaces) and tot > best_common_tot:
                        best_common_tot = tot
                cands = []
                for node, mass in states.items():
                    if not node.surfaces or not node.text:
                        continue
                    l, tot = len(node.text), mass.total
                    c0, c1 = ord(node.text[0]) - ord("a"), ord(node.text[-1]) - ord("a")
                    d0_sq = (px0 - float(engine.key_centers[c0, 0])) ** 2 + (py0 - float(engine.key_centers[c0, 1])) ** 2
                    d1_sq = (px1 - float(engine.key_centers[c1, 0])) ** 2 + (py1 - float(engine.key_centers[c1, 1])) ** 2
                    bs = tot / (l ** gamma) + beta * l - 2.0 * d0_sq - 3.5 * d1_sq
                    for surface, vocab_rank, zipf_freq, has_apos in node.surfaces:
                        ts = bs
                        if has_apos and node.has_plain_surface:
                            ts -= 2.5
                        if sv is None:
                            ts += 0.004 * zipf_freq + 0.25 * engine.lookup_unigram_bias(surface)
                        else:
                            ts += 0.0015 * zipf_freq
                            cs = engine.score_word_in_context(sv, surface)
                            tg = best_common_tot - tot
                            if best_common_tot > -0.55 and tg > 1.7 and cs < 1.0:
                                ts -= 0.80 * (tg - 1.7)
                            ts += alpha * cs
                        cands.append((surface, ts))
                cands.sort(key=lambda x: x[1], reverse=True)
                seen, out = set(), []
                for s, sc in cands:
                    lw = s.lower()
                    if lw not in seen:
                        seen.add(lw)
                        out.append((s, sc))
                    if len(out) >= 10:
                        break
                return out, best_base_tot

            sp_cands, best_tot = build_base(w_sp, None)
            cx_cands, _ = build_base(w_cx, state_vec) if state_vec is not None else (sp_cands, best_tot)

            # Pass 2: Idea A
            t_a0 = time.perf_counter()
            w = engine.dec_weights
            states_a = fsp.ctc_prefix_search(logits, ext_root, beam=PASS2_BEAM, gamma=w["gamma_prune"], beta=w["beta_prune"])
            a_raw = []
            for node, mass in states_a.items():
                if not node.surfaces or len(node.text) < 3:
                    continue
                for surf, _, lf, _ in node.surfaces:
                    a_raw.append((node.text, surf, lf, mass.total))
            a_ms = (time.perf_counter() - t_a0) * 1000.0

            # Pass 2: Idea B
            t_b0 = time.perf_counter()
            argm = np.argmax(logits, axis=1)
            raw_chars, prev_c = [], -1
            for c in argm:
                c = int(c)
                if c != 26 and c != prev_c:
                    raw_chars.append(chr(ord("a") + c))
                prev_c = c
            raw_str = "".join(raw_chars)
            char_max = np.max(logits[:, :26], axis=0)
            d0 = (px0 - engine.key_centers[:, 0]) ** 2 + (py0 - engine.key_centers[:, 1]) ** 2
            d1 = (px1 - engine.key_centers[:, 0]) ** 2 + (py1 - engine.key_centers[:, 1]) ** 2
            s0 = list(int(x) for x in np.argsort(d0)[:4])
            s1 = list(int(x) for x in np.argsort(d1)[:4])
            for c in np.argsort(np.max(logits[:5, :26], axis=0))[-2:]:
                if int(c) not in s0:
                    s0.append(int(c))
            for c in np.argsort(np.max(logits[26:, :26], axis=0))[-2:]:
                if int(c) not in s1:
                    s1.append(int(c))
            if raw_str:
                rc0, rc1 = ord(raw_str[0]) - ord("a"), ord(raw_str[-1]) - ord("a")
                if rc0 not in s0:
                    s0.append(rc0)
                if rc1 not in s1:
                    s1.append(rc1)
            raw_len = len(raw_str)
            pool = []
            for c0 in s0:
                for c1 in s1:
                    for k, surf, lf, seq in buckets[c0][c1]:
                        lk = len(k)
                        if raw_len > 0 and abs(lk - raw_len) > max(3, raw_len // 2):
                            continue
                        em_sum = sum(float(char_max[ci]) for ci in seq)
                        proxy = em_sum / lk - (0.8 * edit_dist(raw_str, k) if raw_len > 0 else 0.0)
                        pool.append((proxy, k, surf, lf, seq))
            pool.sort(key=lambda x: x[0], reverse=True)
            b_raw = [(k, surf, lf, ctc_forward_logprob(logits, seq)) for _, k, surf, lf, seq in pool[:50]]
            b_ms = (time.perf_counter() - t_b0) * 1000.0

            cached.append({
                "word": it["word"],
                "expected_surface": it.get("expected_surface", it["word"]),
                "in_futo": it["word"] in futo_lower,
                "in_oov_ext": it["word"] in oov_ext,
                "px0": px0, "py0": py0, "px1": px1, "py1": py1,
                "best_base_tot": best_tot,
                "sp_cands": sp_cands,
                "cx_cands": cx_cands,
                "has_ctx": state_vec is not None,
                "a_raw": a_raw, "a_ms": a_ms,
                "b_raw": b_raw, "b_ms": b_ms,
            })
        return cached

    c_1000 = precompute(ds_1000)
    c_real_oov = precompute(ds_real_oov)
    c_tech_oov = precompute(ds_tech_oov)

    def summarize(ds, method, mode):
        t1 = t3 = t5 = inv_t1 = inv_n = oov_t1 = oov_t3 = oov_t5 = oov_n = exact_case = trig = 0
        inv_reg = 0
        p2_times = []
        pen = OOV_PENALTY_CONTEXT if mode == "ctx" else OOV_PENALTY_SPATIAL
        for it in ds:
            use_ctx = (mode == "ctx" and it["has_ctx"])
            w_dict = engine.ctx_weights if use_ctx else engine.dec_weights
            base_cands = it["cx_cands"] if use_ctx else it["sp_cands"]
            merged = list(base_cands)
            if method != "baseline" and it["best_base_tot"] < GATE_BASE_TOT:
                trig += 1
                raw_list = it["a_raw"] if method == "idea_a" else it["b_raw"]
                p2_times.append(it["a_ms"] if method == "idea_a" else it["b_ms"])
                gamma, beta = w_dict["gamma"], w_dict["beta"]
                eff_pen = OOV_PENALTY_CONTEXT if use_ctx else OOV_PENALTY_SPATIAL
                for k, surf, lf, tot in raw_list:
                    l = len(k)
                    c0, c1 = ord(k[0]) - ord("a"), ord(k[-1]) - ord("a")
                    d0_sq = (it["px0"] - float(engine.key_centers[c0, 0])) ** 2 + (it["py0"] - float(engine.key_centers[c0, 1])) ** 2
                    d1_sq = (it["px1"] - float(engine.key_centers[c1, 0])) ** 2 + (it["py1"] - float(engine.key_centers[c1, 1])) ** 2
                    bs = tot / (l ** gamma) + beta * l - 2.0 * d0_sq - 3.5 * d1_sq
                    merged.append((surf, bs + LOG_FREQ_WEIGHT * lf - eff_pen))
            merged.sort(key=lambda x: x[1], reverse=True)
            seen, out = set(), []
            for w, _ in merged:
                if w.lower() not in seen:
                    seen.add(w.lower())
                    out.append(w)
                if len(out) >= 5:
                    break
            target = it["word"]
            h1 = len(out) > 0 and out[0].lower() == target
            h3 = any(x.lower() == target for x in out[:3])
            h5 = any(x.lower() == target for x in out[:5])
            if h1:
                t1 += 1
            if h3:
                t3 += 1
            if h5:
                t5 += 1
            if h1 and out[0] == it["expected_surface"]:
                exact_case += 1
            if it["in_futo"]:
                inv_n += 1
                if h1:
                    inv_t1 += 1
                elif len(base_cands) > 0 and base_cands[0][0].lower() == target:
                    inv_reg += 1
            if it["in_oov_ext"]:
                oov_n += 1
                if h1:
                    oov_t1 += 1
                if h3:
                    oov_t3 += 1
                if h5:
                    oov_t5 += 1
        n = len(ds)
        return {
            "total": n,
            "top1_pct": round(t1 / n * 100.0, 2),
            "top3_pct": round(t3 / n * 100.0, 2),
            "top5_pct": round(t5 / n * 100.0, 2),
            "in_vocab_total": inv_n,
            "in_vocab_top1": inv_t1,
            "in_vocab_regressions": inv_reg,
            "oov_total": oov_n,
            "oov_top1": oov_t1,
            "oov_top3": oov_t3,
            "oov_top5": oov_t5,
            "oov_top1_pct": round(oov_t1 / oov_n * 100.0, 2) if oov_n else None,
            "oov_top3_pct": round(oov_t3 / oov_n * 100.0, 2) if oov_n else None,
            "oov_top5_pct": round(oov_t5 / oov_n * 100.0, 2) if oov_n else None,
            "exact_case_top1": exact_case,
            "pass2_trigger_pct": round(trig / n * 100.0, 2),
            "pass2_python_mean_ms": round(float(np.mean(p2_times)), 2) if p2_times else 0.0,
        }

    results = {
        "operating_point": {
            "selected_method": "Idea A (Confidence-Gated Compact CSR Dual-Trie) + Idea E + Idea C",
            "gate_base_ctc_log_prob": GATE_BASE_TOT,
            "pass2_beam_width": PASS2_BEAM,
            "oov_penalty_spatial": OOV_PENALTY_SPATIAL,
            "oov_penalty_context": OOV_PENALTY_CONTEXT,
            "log_freq_weight": LOG_FREQ_WEIGHT,
            "extended_oov_words": len(oov_ext),
        },
        "ds_1000_context": {
            m: summarize(c_1000, m, "ctx") for m in ["baseline", "idea_a", "idea_b"]
        },
        "ds_1000_spatial": {
            m: summarize(c_1000, m, "sp") for m in ["baseline", "idea_a", "idea_b"]
        },
        "ds_real_oov_context": {
            m: summarize(c_real_oov, m, "ctx") for m in ["baseline", "idea_a", "idea_b"]
        },
        "ds_real_oov_spatial": {
            m: summarize(c_real_oov, m, "sp") for m in ["baseline", "idea_a", "idea_b"]
        },
        "ds_tech_oov_spatial": {
            m: summarize(c_tech_oov, m, "sp") for m in ["baseline", "idea_a", "idea_b"]
        },
        "elapsed_sec": round(time.time() - t_start, 2),
    }

    out_path = "eval/futo_swipe/extended_vocab_ab_results.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"Saved A/B benchmark results -> {out_path} in {results['elapsed_sec']}s")
    print("ds_1000_context idea_a:", results["ds_1000_context"]["idea_a"])
    print("ds_real_oov_context idea_a vs idea_b:",
          results["ds_real_oov_context"]["idea_a"]["oov_top1_pct"],
          "vs",
          results["ds_real_oov_context"]["idea_b"]["oov_top1_pct"])
    print("ds_tech_oov_spatial idea_a vs idea_b:",
          results["ds_tech_oov_spatial"]["idea_a"]["oov_top1_pct"],
          "vs",
          results["ds_tech_oov_spatial"]["idea_b"]["oov_top1_pct"])


if __name__ == "__main__":
    main()
