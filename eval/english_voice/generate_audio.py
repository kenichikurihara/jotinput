#!/usr/bin/env python3
"""generate_audio.py: Synthesizes 16kHz mono WAV files for the 300 English benchmark sentences using local Piper TTS."""

import json
import os
import subprocess
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATASET_FILE = os.path.join(SCRIPT_DIR, "dataset.json")
AUDIO_DIR = os.path.join(SCRIPT_DIR, "audio")
MODEL_PATH = os.path.join(SCRIPT_DIR, "..", "..", "tools", "models", "piper_model", "en-us-lessac-low.onnx")
PIPER_BIN = os.path.expanduser("~/.local/bin/piper")

def main():
    os.makedirs(AUDIO_DIR, exist_ok=True)
    if not os.path.exists(MODEL_PATH):
        raise FileNotFoundError(f"Piper model not found at {MODEL_PATH}")

    with open(DATASET_FILE, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    total = len(dataset)
    print(f"Generating 16kHz mono audio for {total} English sentences...")
    t0 = time.time()
    generated = 0
    skipped = 0

    for idx, item in enumerate(dataset):
        item_id = item["id"]
        sentence = item["sentence"]
        wav_path = os.path.join(AUDIO_DIR, f"{item_id}.wav")

        if os.path.exists(wav_path) and os.path.getsize(wav_path) > 1000:
            skipped += 1
            continue

        cmd = [PIPER_BIN, "--model", MODEL_PATH, "--output_file", wav_path]
        proc = subprocess.run(cmd, input=sentence, text=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        generated += 1

        if (idx + 1) % 50 == 0 or (idx + 1) == total:
            elapsed = time.time() - t0
            print(f"[{idx + 1}/{total}] Processed ({generated} generated, {skipped} skipped, {elapsed:.1f}s elapsed)...")

    elapsed = time.time() - t0
    print(f"Complete! All {total} audio files ready in {AUDIO_DIR} ({elapsed:.1f}s total).")

if __name__ == "__main__":
    main()
