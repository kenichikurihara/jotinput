#!/usr/bin/env python3
"""evaluate_english_voice.py: Benchmarks NVIDIA Parakeet TDT-CTC 110M across the 300 English speech samples."""

import difflib
import json
import os
import re
import time
import sherpa_onnx
import soundfile as sf

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_FILE = os.path.join(SCRIPT_DIR, "dataset.json")
AUDIO_DIR = os.path.join(SCRIPT_DIR, "audio")
RESULTS_FILE = os.path.join(SCRIPT_DIR, "english_voice_eval_results.json")
MODEL_DIR = os.path.join(SCRIPT_DIR, "..", "..", "tools", "models", "parakeet-en-110m")

def compute_cer(ref: str, hyp: str) -> float:
    if not ref:
        return 0.0 if not hyp else 1.0
    sm = difflib.SequenceMatcher(None, ref, hyp)
    dist = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "replace":
            dist += max(i2 - i1, j2 - j1)
        elif tag == "delete":
            dist += (i2 - i1)
        elif tag == "insert":
            dist += (j2 - j1)
    return dist / len(ref)

def compute_wer(ref: str, hyp: str) -> float:
    ref_words = re.findall(r"\b\w+\b", ref.lower())
    hyp_words = re.findall(r"\b\w+\b", hyp.lower())
    if not ref_words:
        return 0.0 if not hyp_words else 1.0
    sm = difflib.SequenceMatcher(None, ref_words, hyp_words)
    dist = 0
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "replace":
            dist += max(i2 - i1, j2 - j1)
        elif tag == "delete":
            dist += (i2 - i1)
        elif tag == "insert":
            dist += (j2 - j1)
    return dist / len(ref_words)

def main():
    if not os.path.exists(MODEL_DIR):
        raise FileNotFoundError(f"Parakeet model not found at {MODEL_DIR}")

    model_path = os.path.join(MODEL_DIR, "model.int8.onnx")
    tokens_path = os.path.join(MODEL_DIR, "tokens.txt")

    print("Initializing sherpa-onnx FastConformer-CTC recognizer...")
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=model_path,
        tokens=tokens_path,
        num_threads=2,
        provider="cpu"
    )

    with open(DATASET_FILE, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    total = len(dataset)
    print(f"Evaluating {total} English speech samples...")
    t_start = time.time()

    per_item = {}
    cat_stats = {}
    total_audio_s = 0.0
    total_decode_s = 0.0
    total_wer = 0.0
    total_cer = 0.0

    for idx, item in enumerate(dataset):
        iid = item["id"]
        cat = item["category"]
        gt = item["sentence"]
        wav_path = os.path.join(AUDIO_DIR, f"{iid}.wav")

        if not os.path.exists(wav_path):
            raise FileNotFoundError(f"Missing audio file: {wav_path}")

        samples, sr = sf.read(wav_path, dtype="float32")
        audio_len = len(samples) / sr
        total_audio_s += audio_len

        stream = rec.create_stream()
        t0 = time.time()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        lat = time.time() - t0
        total_decode_s += lat

        pred = stream.result.text.strip()
        cer = compute_cer(gt, pred)
        wer = compute_wer(gt, pred)
        rtf = lat / audio_len if audio_len > 0 else 0.0

        total_wer += wer
        total_cer += cer

        if cat not in cat_stats:
            cat_stats[cat] = {"count": 0, "wer": 0.0, "cer": 0.0, "audio_s": 0.0, "decode_s": 0.0}
        cat_stats[cat]["count"] += 1
        cat_stats[cat]["wer"] += wer
        cat_stats[cat]["cer"] += cer
        cat_stats[cat]["audio_s"] += audio_len
        cat_stats[cat]["decode_s"] += lat

        per_item[iid] = {
            "category": cat,
            "ground_truth": gt,
            "prediction": pred,
            "cer": round(cer, 4),
            "wer": round(wer, 4),
            "audio_len_s": round(audio_len, 3),
            "latency_s": round(lat, 4),
            "rtf": round(rtf, 4)
        }

        if (idx + 1) % 50 == 0 or (idx + 1) == total:
            print(f"[{idx + 1}/{total}] Avg WER: {total_wer/(idx+1)*100:.2f}%, Avg RTF: {total_decode_s/total_audio_s:.4f}...")

    summary = {
        "total_samples": total,
        "total_audio_duration_s": round(total_audio_s, 2),
        "total_decode_time_s": round(total_decode_s, 2),
        "avg_wer_pct": round(total_wer / total * 100.0, 2),
        "avg_cer_pct": round(total_cer / total * 100.0, 2),
        "overall_rtf": round(total_decode_s / total_audio_s, 4),
        "categories": {}
    }

    for cat, st in cat_stats.items():
        cnt = st["count"]
        summary["categories"][cat] = {
            "count": cnt,
            "wer_pct": round(st["wer"] / cnt * 100.0, 2),
            "cer_pct": round(st["cer"] / cnt * 100.0, 2),
            "rtf": round(st["decode_s"] / st["audio_s"], 4)
        }

    results = {
        "summary": summary,
        "items": per_item
    }

    with open(RESULTS_FILE, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("\n=== English Voice ASR Evaluation Summary ===")
    print(f"Total Samples: {total}")
    print(f"Avg WER: {summary['avg_wer_pct']}% | Avg CER: {summary['avg_cer_pct']}%")
    print(f"Overall RTF: {summary['overall_rtf']} (~{1/summary['overall_rtf']:.1f}x real-time)")
    for cat, cst in summary["categories"].items():
        print(f"  - {cat:20s}: WER {cst['wer_pct']:5.2f}% | CER {cst['cer_pct']:5.2f}% | RTF {cst['rtf']:.4f}")
    print(f"Results saved to {RESULTS_FILE}")

if __name__ == "__main__":
    main()
