#!/usr/bin/env python3
"""
build_eval_dataset.py

Constructs the Phase 1 quantitative evaluation dataset for FUTO Swipe.
Extracts 1,000 real QWERTY swipe cases with preceding sentence context from futo-org/swipe.futo.org.

Output format per line (JSONL):
{
  "id": int,
  "session": str,
  "word": str,                    # Ground truth word (clean lowercase)
  "raw_word": str,                # Original typed surface (may include punctuation)
  "context": str,                 # Immediate preceding context (1-3 words)
  "context_tokens": [str],        # Tokenized preceding words
  "canvas_width": float,
  "canvas_height": float,
  "trajectory": [ {"x": float, "y": float, "t": int}, ... ]
}
"""

import glob
import json
import os
import re

def clean_word(w: str) -> str:
    # Strip leading/trailing punctuation and lowercase
    w = re.sub(r'^[^\w]+|[^\w]+$', '', w)
    return w.lower()

def main():
    input_files = [
        'eval/futo_swipe/raw_test_10mb.jsonl',
        'eval/futo_swipe/raw_test_next20mb.jsonl'
    ]
    output_path = 'eval/futo_swipe/swipe_eval_1000.jsonl'

    sessions = {}
    total_raw = 0

    for file_path in input_files:
        if not os.path.exists(file_path):
            continue
        with open(file_path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                    session_id = record.get('session')
                    if not session_id:
                        continue
                    sessions.setdefault(session_id, []).append(record)
                    total_raw += 1
                except Exception:
                    pass

    print(f"Loaded {total_raw} raw records across {len(sessions)} sessions.")

    eval_items = []
    item_id = 0

    # Sort records inside each session by timestamp
    for session_id, records in sessions.items():
        records.sort(key=lambda r: r.get('timestamp', 0))

        for idx, rec in enumerate(records):
            raw_word = rec.get('word', '')
            clean = clean_word(raw_word)
            data = rec.get('data', [])

            # Filter out invalid entries: empty word, purely non-alpha, or trajectory too short (< 4 points)
            if not clean or not clean.isalpha() or len(data) < 4:
                continue

            # Compute preceding context (up to 3 words)
            context_words = []
            for prev_idx in range(max(0, idx - 3), idx):
                prev_clean = clean_word(records[prev_idx].get('word', ''))
                if prev_clean and prev_clean.isalpha():
                    context_words.append(prev_clean)

            context_str = " ".join(context_words)

            eval_item = {
                "id": item_id,
                "session": session_id,
                "word": clean,
                "raw_word": raw_word,
                "context": context_str,
                "context_tokens": context_words,
                "canvas_width": rec.get("canvas_width", 400.0),
                "canvas_height": rec.get("canvas_height", 200.0),
                "point_count": len(data),
                "trajectory": [
                    {"x": p["x"], "y": p["y"], "t": p.get("t", 0)}
                    for p in data
                ]
            }
            eval_items.append(eval_item)
            item_id += 1

            if len(eval_items) >= 1000:
                break
        if len(eval_items) >= 1000:
            break

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, 'w', encoding='utf-8') as out_f:
        for it in eval_items:
            out_f.write(json.dumps(it, ensure_ascii=False) + '\n')

    print(f"Successfully generated {len(eval_items)} evaluation cases -> {output_path}")

    # Display dataset stats
    with_context = sum(1 for x in eval_items if x['context'])
    avg_points = sum(x['point_count'] for x in eval_items) / len(eval_items) if eval_items else 0
    print(f"  - Cases with preceding context: {with_context} ({with_context/len(eval_items)*100:.1f}%)")
    print(f"  - Average trajectory points per swipe: {avg_points:.1f}")

if __name__ == '__main__':
    main()
