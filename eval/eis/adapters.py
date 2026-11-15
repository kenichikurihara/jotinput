"""Adapters: turn cached evaluation outputs into per-item (chars, seconds) records per CUJ.

Phase 1 uses ONLY outputs already stored in the repo (no model inference). Each adapter returns
{"cuj": str, "items": [{"id", "split", "variants": {name: (chars, secs)}}], "status": str, "notes": [..]}.
"""
from __future__ import annotations

import hashlib
import json
import os
from typing import Any

from klm import KLM, est_kana_keystrokes, voice_item_time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
EVAL = os.path.join(ROOT, "eval")

VOICE_TAIL_LATENCY_S = 0.25      # docs/streaming_voice_input_architecture_and_deliberation.md (200-250 ms)
MOZC_LATENCY_S = 0.0005          # eval/slm_mandatory_rerank_eval_results.json baseline latency (~0.5 ms)
SWIPE_LATENCY_S = 0.00419        # on-device ContextLM swipe latency (docs/futo_swipe_phase1_evaluation_report.md)
TEST_FRACTION_PERCENT = 30       # frozen test split = 30% of items (stable hash of id)


def split_of(item_id: str) -> str:
    h = int(hashlib.md5(item_id.encode("utf-8")).hexdigest(), 16) % 100
    return "test" if h < TEST_FRACTION_PERCENT else "dev"


def _load(rel: str) -> Any:
    with open(os.path.join(EVAL, rel), encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- CUJ-1 / CUJ-3 voice (JP)
def _voice_inputs():
    ds = {x["id"]: x for x in _load("dataset.json")}
    preds = _load("eval_checkpoint.json")["test_predictions"]
    return ds, preds


def cuj1_voice() -> dict:
    ds, preds = _voice_inputs()
    items = []
    for iid, d in ds.items():
        if d["category"] not in ("homophones", "negative_control"):
            continue
        v = {}
        for name, key in (("shipped", "baseline"), ("with_dictionary", "hybrid")):
            p = preds[key][iid]
            v[name] = voice_item_time(d["sentence"], p["text"], VOICE_TAIL_LATENCY_S)
        items.append({"id": iid, "split": split_of(iid), "variants": v})
    return {
        "cuj": "CUJ-1", "items": items, "status": "OK",
        "notes": [
            "Source: eval/dataset.json (homophones 250 + negative_control 150) x eval_checkpoint.json predictions.",
            "Audio is synthetic TTS (eval/audio); real-speech gap is unmeasured (Phase 3).",
            "Punctuation stripped before alignment; punctuation quality is covered by JapanesePunctuationEvaluationTest (guardrail G2), not by EIS-v1 Phase 1.",
            "Filler words, streaming flicker and wait time beyond a constant tail latency are not yet modelled.",
        ],
    }


def cuj3_vocabulary(reuse: int | None = None) -> dict:
    reuse = reuse or KLM.reuse_count
    ds, preds = _voice_inputs()
    R = KLM.register_cost / reuse
    items = []
    for iid, d in ds.items():
        if d["category"] not in ("people_names", "place_names", "tech_terms"):
            continue
        reg = voice_item_time(d["sentence"], preds["hybrid"][iid]["text"], VOICE_TAIL_LATENCY_S)
        unreg = voice_item_time(d["sentence"], preds["baseline"][iid]["text"], VOICE_TAIL_LATENCY_S)
        items.append({"id": iid, "split": split_of(iid), "variants": {
            "shipped": (reg[0], reg[1] + R),          # amortised registration + dictionary-biased voice
            "no_dictionary": unreg,                   # counterfactual: user never registers the word
        }})
    # Flick path with user dictionary (lm_first user_dict category)
    items += _flick_items(["user_dict"], cuj="CUJ-3", with_user_counterfactual=True, reuse=reuse)
    return {
        "cuj": "CUJ-3", "items": items, "status": "OK",
        "notes": [
            f"Registration cost {KLM.register_cost:.0f}s amortised over {reuse} uses (v1 assumption; see reuse-count sensitivity in the report - the sign of the dictionary benefit depends on it).",
            "Voice side: dictionary-registered (hybrid) predictions; counterfactual no_dictionary = baseline predictions.",
            "Flick side: lm_first user_dict items (20); counterfactual removes user candidates.",
            "Voice-correction learning (repeat-occurrence benefit) is NOT yet in EIS: only layer-level rates exist in eval/voice_correction_eval_results.json (Phase 2).",
        ],
    }


# ---------------------------------------------------------------- CUJ-2 flick
def _rank(surfs: list[str], target: str):
    for i, s in enumerate(surfs):
        if s == target:
            return i + 1
    return None


def _flick_time(inp: str, target: str, rank, latency_s: float):
    t = KLM.K_f * est_kana_keystrokes(inp) + KLM.wait(latency_s, KLM.wait_free_key)
    t += KLM.select_cost(rank)
    if rank is None:
        t += KLM.K_f * est_kana_keystrokes(target)
    return len(target), t


def _lm_first_score(c, nll, sys_best, a, lam, gam, eps=0.05):
    # Mirrors eval/tune_and_eval_lm_first.compute_lm_first_score (kept in sync by test_eis.py).
    if c.get("source") == "user":
        eff = min(sys_best, nll) - gam * c.get("priority", 0.0) + eps * nll
    else:
        eff = nll
    obs = lam * c.get("obs_penalty", 0.0) * 5.0
    return (1 - a) * (eff + obs) + a * (c.get("mozc_cost", 3000) / 1000.0)


def _flick_items(categories, cuj, with_user_counterfactual=False, reuse=None):
    cfg = _load("lm_first_tuning_results.json")["best_config"]
    slm_lat = _load("slm_mandatory_rerank_eval_results.json")["suite_1_homophones"]["slm_rerank"]["latency_ms"] / 1000.0
    out = []
    for it in _load("lm_first_nll_cache.json"):
        if it["category"] not in categories:
            continue
        cands, nlls, tgt = it["candidates"], it["nlls"], it["target"]
        sys_best = min([n for c, n in zip(cands, nlls) if c.get("source") != "user"] or [15.0])
        # alpha is forced to 0: in this dataset mozc_cost is index-derived (target-first, see
        # eval/build_lm_first_eval_dataset.py), so any alpha>0 leaks the label order into the ranking.
        scored = sorted(range(len(cands)), key=lambda i: _lm_first_score(
            cands[i], nlls[i], sys_best, 0.0, cfg["lambda_obs"], cfg["gamma_user"]))
        slm_surfs = [cands[i]["surface"] for i in scored]
        v = {"shipped": _flick_time(it["input"], tgt, _rank(slm_surfs, tgt), slm_lat)}
        if with_user_counterfactual:
            R = KLM.register_cost / (reuse or KLM.reuse_count)
            ch, t = v["shipped"]
            v["shipped"] = (ch, t + R)
            non_user = [s for s, c in zip(slm_surfs, [cands[i] for i in scored]) if c.get("source") != "user"]
            v["no_dictionary"] = _flick_time(it["input"], tgt, _rank(non_user, tgt), slm_lat)
        out.append({"id": f"flick:{it['id']}", "split": split_of(it["id"]), "variants": v})
    return out


def cuj2_flick() -> dict:
    items = _flick_items(["homophone", "phrase", "diagnostic", "observation", "natural"], cuj="CUJ-2")
    return {
        "cuj": "CUJ-2", "items": items, "status": "OK",
        "notes": [
            "Source: eval/lm_first_nll_cache.json (cached SLM NLLs), ranked with lambda_obs/gamma_user from best_config but alpha=0 (leak guard).",
            "LEAK: mozc_cost in this dataset is assigned by candidate index (target first), so a Mozc-only baseline and alpha>0 are invalid here; the 'SLM OFF' ablation is therefore NOT reported. Phase 2 must use the real engine.",
            "Candidate lists are small (2-4 per item), so rank-1 rates are optimistic vs live Viterbi N-best; long-phrase headroom (docs/long_phrase_conversion_headroom_report.md: 33.1% Top-1) is NOT captured. Phase 2 must run the real KanaKanjiEngine on tools/eval_1000_cases.tsv.",
        ],
    }


# ---------------------------------------------------------------- CUJ-4 English
def cuj4_english() -> dict:
    r = _load("futo_swipe/swipe_eval_results_1000.json")
    n = r["metadata"]["total_samples"]
    chars = KLM.avg_en_word_len + 1  # + auto space
    items = []

    def word_time(rank):
        t = KLM.G_s + KLM.wait(SWIPE_LATENCY_S, KLM.wait_free_key)
        if rank == 1:
            return t
        if rank is None:
            return t + KLM.L + KLM.D * KLM.avg_en_word_len + KLM.K_q * KLM.avg_en_word_len + KLM.select_cost(1)
        return t + KLM.select_cost(rank)

    def ranks(stats):
        n1 = round(stats["top1_accuracy"] / 100 * n)
        n3 = round(stats["top3_accuracy"] / 100 * n)
        n5 = round(stats["top5_accuracy"] / 100 * n)
        return [1] * n1 + [2] * (n3 - n1) + [4] * (n5 - n3) + [None] * (n - n5)

    ctx, sp = ranks(r["context_aware"]), ranks(r["spatial_baseline"])
    for i in range(n):  # pseudo-items reconstructed from aggregate rank counts; paired by index
        iid = f"swipe:{i:04d}"
        items.append({"id": iid, "split": split_of(iid), "variants": {
            "shipped": (chars, word_time(ctx[i])),
            "spatial_only": (chars, word_time(sp[i])),
        }})
    return {
        "cuj": "CUJ-4", "items": items, "status": "PARTIAL",
        "notes": [
            "English VOICE (Parakeet) side is NO_DATA (Phase 1.5); EIS_4 currently = touch (swipe) only.",
            "Swipe items are pseudo-items reconstructed from aggregate Top-1/3/5 counts (raw per-sample outputs are not in the repo), so bootstrap CIs reflect sampling of the 1,000 words but ranks are not paired to real samples.",
            "Tap-typing, spell-correction and capitalisation suites are not yet included.",
        ],
    }


ADAPTERS = {"CUJ-1": cuj1_voice, "CUJ-2": cuj2_flick, "CUJ-3": cuj3_vocabulary, "CUJ-4": cuj4_english}
