import json
import os
import subprocess
import time
import xml.etree.ElementTree as ET

DEVICE_ID = "192.168.86.46:43343"

def run_adb(cmd, timeout=15):
    full_cmd = ["adb", "-s", DEVICE_ID] + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout)
    return res.stdout

def get_top1_for_prefix(prefix):
    # 1. Tap input field to focus
    run_adb(["shell", "input", "tap", "540", "700"])
    # 2. Clear previous text
    run_adb(["shell", "input", "keyevent", "123", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67", "67"])
    # 3. Type prefix
    run_adb(["shell", "input", "text", prefix])
    time.sleep(0.35)
    # 4. Tap center suggestion (Top-1)
    run_adb(["shell", "input", "tap", "540", "1510"])
    time.sleep(0.35)
    # 5. Dump and read etKeyboardTest text
    run_adb(["shell", "uiautomator", "dump", "--compressed", "/sdcard/d.xml"])
    xml_data = run_adb(["exec-out", "cat", "/sdcard/d.xml"])
    if not xml_data:
        return ""
    try:
        root = ET.fromstring(xml_data)
        for n in root.iter("node"):
            if n.attrib.get("resource-id") == "com.example.myasr:id/etKeyboardTest":
                return n.attrib.get("text", "").strip()
    except Exception as e:
        print(f"Error parsing XML for prefix {prefix}: {e}")
    return ""

def main():
    target_file = "eval/english_completion/target_100_words.json"
    with open(target_file, "r", encoding="utf-8") as f:
        target_words = json.load(f)

    # Set reference Latin IME
    run_adb(["shell", "ime", "set", "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"])
    run_adb(["shell", "settings", "put", "system", "screen_off_timeout", "1800000"])

    print(f"Starting reliable reference candidate collection for {len(target_words)} words...")
    results = []
    out_path = "eval/english_completion/completion_eval_100.json"

    # Resume capability if already partially done
    if os.path.exists(out_path):
        try:
            with open(out_path, "r", encoding="utf-8") as f:
                results = json.load(f)
            print(f"Resuming from {len(results)} existing entries...")
        except Exception:
            results = []

    start_idx = len(results)

    try:
        for idx in range(start_idx, len(target_words)):
            item = target_words[idx]
            target = item["target"]
            prefix = item["primary_prefix"]

            t0 = time.time()
            top1 = get_top1_for_prefix(prefix)
            dt = time.time() - t0

            is_match = (top1.lower() == target.lower())
            entry = {
                "rank": item["rank"],
                "target": target,
                "prefix": prefix,
                "length": item["length"],
                "expected_top1": top1,
                "is_target_completed": is_match
            }
            results.append(entry)
            status = "MATCH" if is_match else f"DIFF({top1})"
            print(f"[{idx+1:3d}/100] target='{target:12s}' prefix='{prefix:8s}' -> top1='{top1:12s}' [{status}] ({dt:.2f}s)")

            # Periodic save every 5 items
            if (idx + 1) % 5 == 0:
                with open(out_path, "w", encoding="utf-8") as out:
                    json.dump(results, out, indent=2, ensure_ascii=False)

    finally:
        with open(out_path, "w", encoding="utf-8") as out:
            json.dump(results, out, indent=2, ensure_ascii=False)
        print(f"Saved {len(results)} golden records to {out_path}")
        run_adb(["shell", "ime", "set", "com.example.myasr/.VoiceInputMethodService"])
        run_adb(["shell", "settings", "put", "system", "screen_off_timeout", "120000"])
        print("Restored default IME to com.example.myasr/.VoiceInputMethodService and timeout to 2m")

if __name__ == "__main__":
    main()
