#!/usr/bin/env python3
"""
generate_audio.py: Generates 16kHz mono WAV audio files for all 1,000 benchmark sentences using edge-tts.
"""

import asyncio
import json
import os
import subprocess
import time
import edge_tts

AUDIO_DIR = os.path.join(os.path.dirname(__file__), "audio")
DATASET_FILE = os.path.join(os.path.dirname(__file__), "dataset.json")
CONCURRENCY = 20
VOICE = "ja-JP-NanamiNeural"  # Natural Japanese voice

async def synthesize_item(sem, item, total_items, counter):
    item_id = item["id"]
    wav_path = os.path.join(AUDIO_DIR, f"{item_id}.wav")
    mp3_path = os.path.join(AUDIO_DIR, f"{item_id}.mp3")

    if os.path.exists(wav_path) and os.path.getsize(wav_path) > 1000:
        counter[0] += 1
        if counter[0] % 100 == 0 or counter[0] == total_items:
            print(f"[{counter[0]}/{total_items}] Audio ready ({item_id})")
        return

    sentence = item["sentence"]

    async with sem:
        for attempt in range(3):
            try:
                communicate = edge_tts.Communicate(sentence, VOICE)
                await communicate.save(mp3_path)

                # Convert to 16kHz mono 16-bit WAV
                subprocess.run(
                    ["ffmpeg", "-y", "-i", mp3_path, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav_path],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=True
                )
                if os.path.exists(mp3_path):
                    os.remove(mp3_path)
                break
            except Exception as e:
                if attempt == 2:
                    print(f"Error synthesizing {item_id}: {e}")
                await asyncio.sleep(1.0)

    counter[0] += 1
    if counter[0] % 100 == 0 or counter[0] == total_items:
        print(f"[{counter[0]}/{total_items}] Generated audio ({item_id})")

async def main():
    os.makedirs(AUDIO_DIR, exist_ok=True)
    with open(DATASET_FILE, "r", encoding="utf-8") as f:
        dataset = json.load(f)

    total = len(dataset)
    print(f"Synthesizing audio for {total} sentences (concurrency={CONCURRENCY})...")
    t0 = time.time()

    sem = asyncio.Semaphore(CONCURRENCY)
    counter = [0]
    tasks = [synthesize_item(sem, item, total, counter) for item in dataset]
    await asyncio.gather(*tasks)

    elapsed = time.time() - t0
    print(f"Audio generation completed in {elapsed:.1f} seconds.")

if __name__ == "__main__":
    asyncio.run(main())
