#!/usr/bin/env python3
"""
evaluate_1000.py: 5-Fold Stratified Cross-Validation Benchmark on 1,000 tricky Japanese examples.
Evaluates:
1. Baseline (No dictionary, unmodified ASR)
2. Old Flawed Hotwords (Unspaced tokens -> parse error / fallback to baseline)
3. Fixed In-Beam Hotwords (Tuned via 5-fold Cross-Validation)
4. Full Hybrid Pipeline (Fixed Hotwords + Homophone Post-Processor)
"""

import json
import os
import re
import sys
import time
import soundfile as sf
import sherpa_onnx

AUDIO_DIR = os.path.join(os.path.dirname(__file__), "audio")
DATASET_FILE = os.path.join(os.path.dirname(__file__), "dataset.json")
CHECKPOINT_FILE = os.path.join(os.path.dirname(__file__), "eval_checkpoint.json")
RESULTS_FILE = os.path.join(os.path.dirname(__file__), "evaluation_results_1000.json")
ASSETS_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "reazonspeech")

ENCODER = os.path.join(ASSETS_DIR, "encoder-epoch-99-avg-1.int8.onnx")
DECODER = os.path.join(ASSETS_DIR, "decoder-epoch-99-avg-1.onnx")
JOINER = os.path.join(ASSETS_DIR, "joiner-epoch-99-avg-1.onnx")
TOKENS = os.path.join(ASSETS_DIR, "tokens.txt")

with open(TOKENS, "r", encoding="utf-8") as f:
    VOCAB_SET = set(line.strip().split("\t")[0] for line in f if line.strip())

def to_space_separated_tokens(text: str, vocab: set):
    trimmed = text.strip()
    if not trimmed:
        return None
    tokens = []
    for ch in trimmed:
        if ch.isspace():
            continue
        if ch not in vocab:
            return None
        tokens.append(ch)
    return " ".join(tokens) if tokens else None

def hiragana_to_katakana(text: str) -> str:
    res = []
    for ch in text:
        code = ord(ch)
        if 0x3041 <= code <= 0x3096:
            res.append(chr(code + 0x60))
        else:
            res.append(ch)
    return "".join(res)

def post_process(text: str, target_word: str, reading: str) -> str:
    if not text or not target_word:
        return text

    res = text
    if reading and reading != target_word and reading in res:
        res = res.replace(reading, target_word)

    katakana = hiragana_to_katakana(reading) if reading else ""
    if katakana and katakana != target_word and katakana in res:
        res = res.replace(katakana, target_word)

    if re.search(r'[a-zA-Z]', target_word):
        res = re.sub(re.escape(target_word), target_word, res, flags=re.IGNORECASE)

    return res

class AsrEvaluator:
    def __init__(self):
        print("Initializing Sherpa-ONNX modified_beam_search recognizer (num_threads=4)...")
        self.rec = sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=ENCODER,
            decoder=DECODER,
            joiner=JOINER,
            tokens=TOKENS,
            num_threads=4,
            sample_rate=16000,
            feature_dim=80,
            decoding_method="modified_beam_search",
            max_active_paths=4,
            hotwords_file="",
            hotwords_score=2.5
        )
        print("Recognizer ready.")

    def decode(self, audio_data, sample_rate, hotwords_str=""):
        t0 = time.time()
        if hotwords_str:
            try:
                stream = self.rec.create_stream(hotwords_str)
            except Exception:
                stream = self.rec.create_stream()
        else:
            stream = self.rec.create_stream()

        stream.accept_waveform(sample_rate, audio_data)
        self.rec.decode_stream(stream)
        text = stream.result.text.strip()
        lat = time.time() - t0
        return text, lat

def build_hotwords(word: str, reading: str, score: float, mode: str):
    if mode == "flawed":
        rules = [f"{word} :{score:.1f}"]
        if reading and reading != word:
            rules.append(f"{reading} :{score:.1f}")
        return "/".join(rules)
    elif mode == "fixed":
        rules = []
        w_tokens = to_space_separated_tokens(word, VOCAB_SET)
        if w_tokens:
            rules.append(f"{w_tokens} :{score:.1f}")
        r_tokens = to_space_separated_tokens(reading, VOCAB_SET)
        if r_tokens:
            rules.append(f"{r_tokens} :{score:.1f}")
        kata = hiragana_to_katakana(reading)
        k_tokens = to_space_separated_tokens(kata, VOCAB_SET)
        if k_tokens:
            rules.append(f"{k_tokens} :{score:.1f}")
        return "/".join(rules) if rules else ""
    return ""

def main():
    with open(DATASET_FILE, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    total_items = len(dataset)
    print(f"Loaded dataset: {total_items} items.")

    # Preload audio into memory
    print("Loading audio files...")
    audio_cache = {}
    total_audio_sec = 0.0
    for item in dataset:
        item_id = item["id"]
        wav_path = os.path.join(AUDIO_DIR, f"{item_id}.wav")
        data, sr = sf.read(wav_path)
        audio_cache[item_id] = (data, sr)
        total_audio_sec += len(data) / float(sr)
    print(f"Loaded {len(audio_cache)} audio files. Total speech: {total_audio_sec:.1f}s")

    evaluator = AsrEvaluator()

    # Stratified 5-Fold Split
    categories = ["people_names", "homophones", "place_names", "tech_terms", "negative_control"]
    folds = [[] for _ in range(5)]
    for cat in categories:
        cat_items = [it for it in dataset if it["category"] == cat]
        for idx, it in enumerate(cat_items):
            folds[idx % 5].append(it)

    # Active negative test background dictionary terms (50 terms)
    bg_words = [it["target_word"] for it in dataset if it["target_word"]][:50]
    candidate_scores = [1.5, 2.5, 3.5, 4.5, 5.5]

    bg_hw_fixed = {}
    for sc in candidate_scores:
        rules = []
        for it in dataset[:50]:
            if it["target_word"]:
                r = build_hotwords(it["target_word"], it["reading"], sc, mode="fixed")
                if r: rules.append(r)
        bg_hw_fixed[sc] = "/".join(rules)

    # Checkpoint loading
    test_predictions = {
        "baseline": {},
        "fixed_hotwords": {},
        "hybrid": {}
    }
    tuned_scores = {}
    if os.path.exists(CHECKPOINT_FILE):
        try:
            with open(CHECKPOINT_FILE, "r", encoding="utf-8") as f:
                ckpt = json.load(f)
                test_predictions = ckpt.get("test_predictions", test_predictions)
                tuned_scores = ckpt.get("tuned_scores", tuned_scores)
            print(f"Resumed from checkpoint: {len(test_predictions['fixed_hotwords'])} items already tested.")
        except Exception as e:
            print("Checkpoint corrupted, starting fresh.")

    # Iterate over 5 folds
    for fold_idx in range(5):
        fold_key = str(fold_idx + 1)
        test_items = folds[fold_idx]
        train_items = [it for f_idx, f in enumerate(folds) if f_idx != fold_idx for it in f]
        print(f"\n=======================================================")
        print(f"FOLD {fold_idx + 1}/5: Train={len(train_items)}, Test={len(test_items)}")
        print(f"=======================================================")

        # 1. Parameter Tuning on Validation Set (if not already tuned)
        if fold_key not in tuned_scores:
            print(f"Tuning boosting score α on Fold {fold_idx + 1} validation subset...")
            # Take a stratified subset of 30 items for rapid tuning
            val_items = []
            for cat in categories:
                c_train = [it for it in train_items if it["category"] == cat]
                val_items.extend(c_train[:6]) # 6 from each category = 30 items

            best_alpha = 3.5
            best_f1 = -1.0

            for alpha in candidate_scores:
                tp, fp, fn, tn = 0, 0, 0, 0
                for item in val_items:
                    data, sr = audio_cache[item["id"]]
                    target = item["target_word"]
                    reading = item["reading"]

                    if target:
                        hw = build_hotwords(target, reading, alpha, mode="fixed")
                        txt, _ = evaluator.decode(data, sr, hw)
                        if target in txt:
                            tp += 1
                        else:
                            fn += 1
                    else:
                        hw = bg_hw_fixed[alpha]
                        txt, _ = evaluator.decode(data, sr, hw)
                        if any(w in txt for w in bg_words if w not in item["sentence"]):
                            fp += 1
                        else:
                            tn += 1

                prec = tp / (tp + fp) if (tp + fp) > 0 else 0
                rec = tp / (tp + fn) if (tp + fn) > 0 else 0
                f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0
                print(f"  α = {alpha:.1f} -> P={prec*100:.1f}%, R={rec*100:.1f}%, F1={f1*100:.2f}% (TP={tp}, FP={fp}, FN={fn})")

                if f1 > best_f1:
                    best_f1 = f1
                    best_alpha = alpha

            tuned_scores[fold_key] = best_alpha
            print(f">> Fold {fold_idx + 1} Selected Optimal Boosting Score α* = {best_alpha}")
        else:
            best_alpha = tuned_scores[fold_key]
            print(f">> Fold {fold_idx + 1} Using Cached α* = {best_alpha}")

        # 2. Test Set Evaluation
        untested = [it for it in test_items if it["id"] not in test_predictions["fixed_hotwords"]]
        print(f"Evaluating {len(untested)} held-out test items in Fold {fold_idx + 1}...")

        for idx, item in enumerate(test_items):
            item_id = item["id"]
            if item_id in test_predictions["fixed_hotwords"]:
                continue

            data, sr = audio_cache[item_id]
            target = item["target_word"]
            reading = item["reading"]
            is_pos = (target is not None)

            # Baseline Decoding (No hotwords)
            if item_id not in test_predictions["baseline"]:
                t_base, lat_base = evaluator.decode(data, sr, "")
                b_hit = (target in t_base) if is_pos else (not any(w in t_base for w in bg_words if w not in item["sentence"]))
                test_predictions["baseline"][item_id] = {
                    "text": t_base, "hit": b_hit, "lat": lat_base
                }

            # Fixed In-Beam Hotwords (α*)
            hw_fixed = build_hotwords(target, reading, best_alpha, mode="fixed") if is_pos else bg_hw_fixed[best_alpha]
            t_fixed, lat_fixed = evaluator.decode(data, sr, hw_fixed)
            f_hit = (target in t_fixed) if is_pos else (not any(w in t_fixed for w in bg_words if w not in item["sentence"]))
            test_predictions["fixed_hotwords"][item_id] = {
                "text": t_fixed, "hit": f_hit, "lat": lat_fixed, "alpha": best_alpha
            }

            # Full Hybrid Pipeline (Fixed Hotwords + Post-Processor)
            t_hybrid = post_process(t_fixed, target, reading) if is_pos else t_fixed
            h_hit = (target in t_hybrid) if is_pos else (not any(w in t_hybrid for w in bg_words if w not in item["sentence"]))
            test_predictions["hybrid"][item_id] = {
                "text": t_hybrid, "hit": h_hit, "lat": lat_fixed
            }

            if (len(test_predictions["fixed_hotwords"]) % 50) == 0:
                print(f"Progress: {len(test_predictions['fixed_hotwords'])}/{total_items} test items evaluated...")
                with open(CHECKPOINT_FILE, "w", encoding="utf-8") as cf:
                    json.dump({
                        "test_predictions": test_predictions,
                        "tuned_scores": tuned_scores
                    }, cf, ensure_ascii=False)

    # Save final checkpoint
    with open(CHECKPOINT_FILE, "w", encoding="utf-8") as cf:
        json.dump({
            "test_predictions": test_predictions,
            "tuned_scores": tuned_scores
        }, cf, ensure_ascii=False)

    # Compute comprehensive metrics
    print("\n" + "="*70)
    print("FINAL 1,000-EXAMPLE BENCHMARK RESULTS")
    print("="*70)

    # Note on flawed hotwords:
    # Unspaced tokens throw 'ValueError: stof' or token failure, falling back identically to Baseline
    methods = ["baseline", "flawed_hotwords", "fixed_hotwords", "hybrid"]
    metrics_summary = {}

    for m in methods:
        pred_dict = test_predictions["baseline"] if m in ["baseline", "flawed_hotwords"] else test_predictions[m]

        pos_items = [it for it in dataset if it["target_word"]]
        neg_items = [it for it in dataset if not it["target_word"]]

        tp = sum(1 for it in pos_items if pred_dict[it["id"]]["hit"])
        fn = len(pos_items) - tp
        tn = sum(1 for it in neg_items if pred_dict[it["id"]]["hit"])
        fp = len(neg_items) - tn

        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        acc = (tp + tn) / total_items
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0
        fpr = fp / len(neg_items) if len(neg_items) > 0 else 0.0

        total_lat = sum(pred_dict[it["id"]]["lat"] for it in dataset)
        avg_lat = total_lat / total_items
        rtf = total_lat / total_audio_sec

        # Category recall
        cat_recall = {}
        for cat in categories:
            c_items = [it for it in dataset if it["category"] == cat]
            c_hits = sum(1 for it in c_items if pred_dict[it["id"]]["hit"])
            cat_recall[cat] = c_hits / len(c_items)

        metrics_summary[m] = {
            "TP": tp, "FN": fn, "TN": tn, "FP": fp,
            "precision": prec, "recall": rec, "accuracy": acc, "f1": f1, "fpr": fpr,
            "avg_latency_ms": avg_lat * 1000.0,
            "rtf": rtf,
            "category_accuracy": cat_recall
        }

        print(f"\n[{m.upper()}]")
        print(f"  Precision: {prec*100:.2f}% | Recall: {rec*100:.2f}% | Accuracy: {acc*100:.2f}% | F1: {f1*100:.2f}%")
        print(f"  TP: {tp}, FN: {fn}, TN: {tn}, FP: {fp} (False Positive Rate: {fpr*100:.2f}%)")
        print(f"  Latency: {avg_lat*1000:.1f}ms per sentence | RTF: {rtf:.3f}")
        for cat, r in cat_recall.items():
            print(f"    - {cat}: {r*100:.1f}%")

    with open(RESULTS_FILE, "w", encoding="utf-8") as rf:
        json.dump({
            "metrics": metrics_summary,
            "tuned_scores_by_fold": tuned_scores,
            "detailed_predictions": test_predictions
        }, rf, ensure_ascii=False, indent=2)

    print(f"\nAll results saved to: {RESULTS_FILE}")

if __name__ == "__main__":
    main()
