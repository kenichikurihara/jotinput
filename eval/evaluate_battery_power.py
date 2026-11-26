#!/usr/bin/env python3
"""
Empirical On-Device Battery & Power Consumption Benchmark for MyASR Reranking.
Measures and compares on the connected physical Pixel 10 Pro (Tensor G5):
  1. Rerank OFF (Mozc Viterbi + 2D Connection Matrix + Trie/SQLite only)
  2. Rerank ON: Qwen2.5-0.5B-Instruct (KV Cache Hit / Continuous Typing / KV Cache Miss)
  3. Rerank ON: Qwen2.5-1.5B-Instruct (KV Cache Hit / Continuous Typing / KV Cache Miss)

Collects:
  - Hardware ODPM (On-Device Power Monitor) CPU energy attribution via `dumpsys batterystats` (mAh)
  - Process total CPU time across all threads (`Process.getElapsedCpuTime()` and `u=... s=...`)
  - Per-cluster CPU frequency residency (Little / Mid / Mid-High / Prime cores)
  - Wall-clock latency per conversion (ms)
"""

import json
import os
import re
import subprocess
import sys
import time
from typing import Dict, Any, Optional


def run_adb(serial: str, cmd: str, timeout: int = 60) -> str:
    full_cmd = ["adb", "-s", serial, "shell", cmd]
    res = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout)
    return res.stdout


def wait_for_log(serial: str, pattern: str, timeout_sec: float = 120.0) -> Optional[str]:
    t0 = time.time()
    while time.time() - t0 < timeout_sec:
        out = run_adb(serial, "logcat -d -s PowerBench:I")
        for line in reversed(out.splitlines()):
            if pattern in line:
                return line
        time.sleep(0.3)
    return None


def parse_time_str_ms(s: str) -> float:
    total_ms = 0.0
    m_min = re.search(r"(\d+)m\s*", s)
    if m_min:
        total_ms += int(m_min.group(1)) * 60000.0
    m_sec = re.search(r"(\d+)s\s*", s)
    if m_sec:
        total_ms += int(m_sec.group(1)) * 1000.0
    m_ms = re.search(r"(\d+)ms", s)
    if m_ms:
        total_ms += int(m_ms.group(1))
    return total_ms


def parse_batterystats(dump: str, uid_tag: str = "u0a479") -> Dict[str, Any]:
    result: Dict[str, Any] = {
        "uid_total_mah": 0.0,
        "uid_cpu_mah": 0.0,
        "global_cpu_mah": 0.0,
        "global_screen_mah": 0.0,
        "usr_cpu_ms": 0.0,
        "krn_cpu_ms": 0.0,
        "cluster_times_ms": {
            "little_c0": 0,
            "mid_c1": 0,
            "mid_high_c2": 0,
            "prime_c3": 0,
        }
    }

    # Global power use
    m_glob_cpu = re.search(r"^\s*cpu:\s*([0-9.]+)", dump, re.MULTILINE)
    if m_glob_cpu:
        result["global_cpu_mah"] = float(m_glob_cpu.group(1))
    m_glob_scr = re.search(r"^\s*screen:\s*([0-9.]+)", dump, re.MULTILINE)
    if m_glob_scr:
        result["global_screen_mah"] = float(m_glob_scr.group(1))

    # UID power line: e.g. "  UID u0a479: 4.36 bg: 3.74\n      screen=0.620 cpu=3.74 ..."
    uid_pattern = re.compile(
        rf"^\s*UID {re.escape(uid_tag)}:\s*([0-9.]+).*?\n([^\n]*)",
        re.MULTILINE
    )
    m_uid = uid_pattern.search(dump)
    if m_uid:
        result["uid_total_mah"] = float(m_uid.group(1))
        line2 = m_uid.group(2)
        m_cpu = re.search(r"\bcpu=([0-9.]+)", line2)
        if m_cpu:
            result["uid_cpu_mah"] = float(m_cpu.group(1))
        m_scr = re.search(r"\bscreen=([0-9.]+)", line2)
        if m_scr:
            result["uid_screen_mah"] = float(m_scr.group(1))

    # Detailed UID section
    sec_pattern = re.compile(rf"^  {re.escape(uid_tag)}:\n(.*?)(?=^\S|\Z)", re.MULTILINE | re.DOTALL)
    m_sec = sec_pattern.search(dump)
    if m_sec:
        sec = m_sec.group(1)
        m_cpu_time = re.search(r"Total cpu time:\s*u=([^\n]+?)\s+s=([^\n]+)", sec)
        if m_cpu_time:
            result["usr_cpu_ms"] = parse_time_str_ms(m_cpu_time.group(1))
            result["krn_cpu_ms"] = parse_time_str_ms(m_cpu_time.group(2))

        m_freq = re.search(r"Total cpu time per freq:\s*([0-9 ]+)", sec)
        if m_freq:
            vals = [int(x) for x in m_freq.group(1).strip().split()]
            # Pixel 10 Pro has 22 + 24 + 24 + 23 = 93 states
            if len(vals) >= 93:
                result["cluster_times_ms"] = {
                    "little_c0": sum(vals[0:22]),
                    "mid_c1": sum(vals[22:46]),
                    "mid_high_c2": sum(vals[46:70]),
                    "prime_c3": sum(vals[70:93]),
                }
    return result


def run_benchmark_case(
    serial: str,
    name: str,
    engine: str,
    workload: str,
    iters: int
) -> Dict[str, Any]:
    print(f"\n=== Running Case: {name} (engine={engine}, workload={workload}, iters={iters}) ===")
    # Wake screen & keep on
    run_adb(serial, "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")

    # 1. Prepare & warm up model BEFORE resetting battery stats
    run_adb(serial, "logcat -c")
    prep_cmd = (
        f"am start -n io.github.kenichikurihara.jotinput/com.example.myasr.MainActivity "
        f"--es bench_action prepare --es bench_engine {engine}"
    )
    run_adb(serial, prep_cmd)
    prep_line = wait_for_log(serial, "BENCH_PREPARE_DONE", timeout_sec=45.0)
    print(f"  Prepare completed: {prep_line}")
    time.sleep(1.0)

    # Read battery voltage (uV -> V)
    v_str = run_adb(serial, "cat /sys/class/power_supply/battery/voltage_now").strip()
    voltage_v = float(v_str) / 1_000_000.0 if v_str.isdigit() else 3.85

    # 2. Reset batterystats and unplug battery for clean ODPM attribution
    run_adb(serial, "logcat -c")
    run_adb(serial, "dumpsys batterystats --reset && cmd battery unplug")

    # 3. Trigger benchmark run
    run_cmd = (
        f"am start -n io.github.kenichikurihara.jotinput/com.example.myasr.MainActivity "
        f"--es bench_action run --es bench_engine {engine} "
        f"--es bench_workload {workload} --ei bench_iters {iters}"
    )
    run_adb(serial, run_cmd)

    done_line = wait_for_log(serial, "BENCH_RUN_DONE", timeout_sec=180.0)
    # Immediately snapshot batterystats and restore battery state
    bstats_out = run_adb(serial, "dumpsys batterystats io.github.kenichikurihara.jotinput")
    run_adb(serial, "cmd battery reset")

    if not done_line:
        raise RuntimeError(f"Timed out waiting for BENCH_RUN_DONE in {name}")

    json_str = done_line.split("BENCH_RUN_DONE", 1)[1].strip()
    app_metrics = json.loads(json_str)
    bs_metrics = parse_batterystats(bstats_out, "u0a479")

    convs = app_metrics["conversions"]
    uid_cpu_mah = bs_metrics["uid_cpu_mah"]
    mah_per_conv = uid_cpu_mah / convs if convs > 0 else 0.0
    mah_per_1000_conv = mah_per_conv * 1000.0

    # Energy in Joules (using standard nominal 3.85V and measured voltage)
    # 1 mAh at V volts = V * 3.6 Joules
    joules_total_nominal = uid_cpu_mah * 3.85 * 3.6
    joules_per_conv = joules_total_nominal / convs if convs > 0 else 0.0

    combined = {
        "name": name,
        "engine": engine,
        "workload": workload,
        "iters": iters,
        "voltage_v": round(voltage_v, 3),
        "app": app_metrics,
        "batterystats": bs_metrics,
        "derived": {
            "uid_cpu_mah_total": uid_cpu_mah,
            "mah_per_conv": mah_per_conv,
            "mah_per_1000_conv": mah_per_1000_conv,
            "pct_of_4497mah_per_1000_conv": (mah_per_1000_conv / 4497.0) * 100.0,
            "joules_total": joules_total_nominal,
            "joules_per_conv": joules_per_conv,
            "mwh_per_1000_conv": mah_per_1000_conv * 3.85,
        }
    }
    print(f"  Result: wallPerConv={app_metrics['wallPerConvMs']:.2f}ms, "
          f"cpuPerConv={app_metrics['cpuPerConvMs']:.2f}ms, "
          f"uid_cpu_mAh={uid_cpu_mah:.4f} mAh ({mah_per_1000_conv:.3f} mAh / 1000 convs, "
          f"{joules_per_conv:.4f} J/conv)")
    return combined


def main():
    serial = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ADB_DEVICE_ID", "<device-ip>:<port>")
    cases = [
        ("1. リランクなし (OFF: Mozc Viterbi + 2D連接行列のみ)", "none", "miss", 500),
        ("2. Qwen2.5-0.5B (KVキャッシュHit: 同一文脈連続入力)", "local_slm", "hit", 500),
        ("3. Qwen2.5-0.5B (実タイピング: 1文脈につき5打鍵=Hit率80%)", "local_slm", "typing", 250),
        ("4. Qwen2.5-0.5B (KVキャッシュMiss: 毎回新規文脈で確定)", "local_slm", "miss", 200),
        ("5. Qwen2.5-1.5B (KVキャッシュHit: 同一文脈連続入力)", "local_slm_1_5b", "hit", 300),
        ("6. Qwen2.5-1.5B (実タイピング: 1文脈につき5打鍵=Hit率80%)", "local_slm_1_5b", "typing", 150),
        ("7. Qwen2.5-1.5B (KVキャッシュMiss: 毎回新規文脈で確定)", "local_slm_1_5b", "miss", 100),
    ]

    results = []
    for name, engine, workload, iters in cases:
        res = run_benchmark_case(serial, name, engine, workload, iters)
        results.append(res)

    # Restore default setting on device (LOCAL_SLM enabled)
    run_adb(serial, "am start -n io.github.kenichikurihara.jotinput/com.example.myasr.MainActivity --es bench_action prepare --es bench_engine local_slm")

    out_path = "eval/battery_power_eval_results.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\nSaved full results to {out_path}")


if __name__ == "__main__":
    main()
