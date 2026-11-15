#!/usr/bin/env python3
"""EIS-v1 runner: computes the top-level North Star metric from cached evaluation outputs.

  python3 eval/eis/run_eis.py                 # print summary, write eval/eis/eis_results.json
  python3 eval/eis/run_eis.py --report        # also (re)generate docs/eis_baseline_report.md

See docs/prd_top_level.md for the definition. Pure stdlib, deterministic (seeded bootstrap).
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
from adapters import ADAPTERS, ROOT, cuj3_vocabulary  # noqa: E402
from klm import CUJ_WEIGHTS, EIS_VERSION  # noqa: E402

# (name, {cuj: variant}) -- everything else stays "shipped"
ABLATIONS = [
    ("ユーザー辞書なし (CUJ-3: 単語を登録しない)", {"CUJ-3": "no_dictionary"}),
    ("Swipe ContextLM OFF (CUJ-4: 空間モデルのみ)", {"CUJ-4": "spatial_only"}),
    ("音声に辞書バイアス適用 (CUJ-1: hybrid)", {"CUJ-1": "with_dictionary"}),
]
BOOT = 1000
SEED = 20261115


def eis_c(chars: float, secs: float) -> float:
    return 60.0 * chars / secs if secs > 0 else float("nan")


def global_eis(per_cuj: dict[str, float]) -> float:
    return 1.0 / sum(CUJ_WEIGHTS[c] / v for c, v in per_cuj.items())


def sums(items, variant, idx):
    ch = sum(items[i]["variants"][variant][0] for i in idx)
    se = sum(items[i]["variants"][variant][1] for i in idx)
    return ch, se


def evaluate(data: dict, split: str, choice: dict[str, str], idx_map: dict[str, list[int]]):
    per = {}
    for cuj, d in data.items():
        per[cuj] = eis_c(*sums(d["items"], choice.get(cuj, "shipped"), idx_map[cuj]))
    return per, global_eis(per)


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, max(0, int(q * (len(xs) - 1) + 0.5)))]


def run(split: str):
    data = {c: f() for c, f in ADAPTERS.items()}
    sel = {}
    for c, d in data.items():
        sel[c] = [i for i, it in enumerate(d["items"]) if split == "all" or it["split"] == split]
    base_per, base_g = evaluate(data, split, {}, sel)
    rng = random.Random(SEED)
    boots = {"global": [], **{c: [] for c in data}}
    abl_boots = {n: [] for n, _ in ABLATIONS}
    for _ in range(BOOT):
        resample = {c: [rng.choice(sel[c]) for _ in sel[c]] for c in data}  # paired across variants
        per, g = evaluate(data, split, {}, resample)
        boots["global"].append(g)
        for c in data:
            boots[c].append(per[c])
        for name, ch in ABLATIONS:
            _, ga = evaluate(data, split, ch, resample)
            abl_boots[name].append(ga - g)  # delta vs shipped (negative = ablation worse)
    # Sensitivity of the dictionary benefit to the assumed reuse count (v1 assumption).
    sens = []
    for n in (1, 3, 5, 7, 10, 20):
        d3 = cuj3_vocabulary(reuse=n)
        alt = dict(data)
        alt["CUJ-3"] = d3
        per_s, g_s = evaluate(alt, split, {}, sel)
        _, g_nd = evaluate(alt, split, {"CUJ-3": "no_dictionary"}, sel)
        sens.append({"reuse": n, "eis_with_dictionary": g_s, "eis_without": g_nd, "delta": g_nd - g_s,
                     "eis3_with": per_s["CUJ-3"],
                     "eis3_without": evaluate(alt, split, {"CUJ-3": "no_dictionary"}, sel)[0]["CUJ-3"]})
    out = {
        "reuse_sensitivity": sens,
        "version": EIS_VERSION, "split": split, "bootstrap": {"B": BOOT, "seed": SEED},
        "n_items": {c: len(sel[c]) for c in data},
        "eis": {"value": base_g, "ci95": [pct(boots["global"], .025), pct(boots["global"], .975)]},
        "cuj": {c: {"weight": CUJ_WEIGHTS[c], "eis": base_per[c],
                    "ci95": [pct(boots[c], .025), pct(boots[c], .975)],
                    "status": data[c]["status"], "notes": data[c]["notes"]} for c in data},
        "ablations": [],
    }
    for name, ch in ABLATIONS:
        per, g = evaluate(data, split, ch, sel)
        out["ablations"].append({"name": name, "eis": g, "delta": g - base_g,
                                 "delta_ci95": [pct(abl_boots[name], .025), pct(abl_boots[name], .975)]})
    return out


GUARDRAILS = [
    ("G1 完全オフライン", "コードレビュー/マニフェスト"),
    ("G2 音声句読点の非回帰", "./gradlew testDebugUnitTest (JapanesePunctuationEvaluationTest)"),
    ("G3 テキストを壊さない", "VoiceInputArbitratorTest, AlloyVerificationTest"),
    ("G4 ユーザーデータ永続性", "辞書永続性テスト"),
    ("G5 クラッシュ/ANR 0", "deploy.sh + logcat (両ターゲット)"),
    ("G6 CUJ-0 完走", "オンボーディング DMN/Alloy + 実機"),
    ("G7 応答性上限", "ImeLatencyAndAsyncTest ほか"),
    ("G8 APK ≤ 110MB / 電力", "CI / ODPM"),
    ("G9 DDD 境界", "DddArchitectureKonsistTest"),
]


def render_report(test: dict, dev: dict) -> str:
    L = []
    a = L.append
    a(f"# EIS-v1 ベースラインレポート（Phase 1）")
    a("")
    a("> 自動生成: `python3 eval/eis/run_eis.py --report`。定義は [`prd_top_level.md`](prd_top_level.md)。")
    a("> 入力はリポジトリ内のキャッシュ済み評価出力のみ（モデル推論なし）。**絶対値は KLM 仮定定数（EIS-v1）に依存**し、")
    a("> 実測較正前（Phase 3）は **版内の相対比較・回帰検知** にのみ使うこと。")
    a("")
    a(f"## 1. ヘッドライン（凍結 test 分割, bootstrap B={test['bootstrap']['B']}）")
    a("")
    e = test["eis"]
    a(f"**EIS = {e['value']:.1f} 文字/分**（95% CI {e['ci95'][0]:.1f} – {e['ci95'][1]:.1f}）")
    a("")
    a("| CUJ | 重み | EIS_c (文字/分) | 95% CI | dev EIS_c | n (test) | 状態 |")
    a("| :--- | :---: | :---: | :---: | :---: | :---: | :--- |")
    for c, v in test["cuj"].items():
        a(f"| {c} | {v['weight']:.2f} | {v['eis']:.1f} | {v['ci95'][0]:.1f} – {v['ci95'][1]:.1f} | "
          f"{dev['cuj'][c]['eis']:.1f} | {test['n_items'][c]} | {v['status']} |")
    a("")
    a(f"dev 分割の全体 EIS = {dev['eis']['value']:.1f}（参考。チューニングは dev のみで行う）。")
    a("")
    a("## 2. アブレーション（EIS の感度確認）")
    a("")
    a("全体 EIS の変化（test 分割, paired bootstrap 95% CI）。各機能の価値が **文字/分** という共通単位で比較できることを示す。")
    a("")
    a("| 変更 | 全体 EIS | Δ | Δ 95% CI |")
    a("| :--- | :---: | :---: | :---: |")
    for ab in test["ablations"]:
        a(f"| {ab['name']} | {ab['eis']:.1f} | {ab['delta']:+.2f} | {ab['delta_ci95'][0]:+.2f} … {ab['delta_ci95'][1]:+.2f} |")
    a("")
    a("### 2.1 辞書登録の効果は「登録語の再利用回数」の仮定に依存する")
    a("")
    a("登録コスト（12 秒）を再利用回数 n で償却するモデル。`delta = 辞書なし − 辞書あり`（正なら辞書なしの方が速い＝登録が元を取れていない）。")
    a("")
    a("| 再利用回数 n | EIS_3 辞書あり | EIS_3 辞書なし | 全体 EIS 辞書あり | 全体 EIS 辞書なし | delta |")
    a("| :---: | :---: | :---: | :---: | :---: | :---: |")
    for s_ in test["reuse_sensitivity"]:
        a(f"| {s_['reuse']} | {s_['eis3_with']:.1f} | {s_['eis3_without']:.1f} | {s_['eis_with_dictionary']:.1f} | {s_['eis_without']:.1f} | {s_['delta']:+.2f} |")
    a("")
    a("損益分岐は表の delta の符号が反転する n 付近（1 回の使用あたりの訂正時間の節約量と、登録コストの比で決まる）。**この結果は『辞書機能は不要』という意味ではない**。")
    a("連絡先・社名など固有名詞は何度も使われるため、n の実測（端末内ローカル統計）が Phase 3 の重要項目になる。")
    a("")
    a("## 3. ガードレール（本実行では未検証）")
    a("")
    a("EIS 計算はガードレールを評価しない。リリース判定では下記をすべて合格させること（PRD §5.4）。")
    a("")
    a("| ガードレール | 検証手段 | 本実行 |")
    a("| :--- | :--- | :---: |")
    for g, m in GUARDRAILS:
        a(f"| {g} | {m} | UNVERIFIED |")
    a("")
    a("## 4. 既知の限界（解釈時に必ず読むこと）")
    a("")
    for c, v in test["cuj"].items():
        for n in v["notes"]:
            a(f"- **{c}**: {n}")
    a("- 全 CUJ 共通: KLM 定数は仮定値（PRD §5.3）。重み $w_c$ は PO 承認済みの方針値。")
    a("- 現在の Phase 1 は **既存評価資産の再解釈** であり、新規計測ではない。Phase 2 で実エンジン（KanaKanjiEngine+リランカー）を回し、Phase 3 で実機遅延と定数を較正する。")
    a("")
    return "\n".join(L)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()
    test, dev = run("test"), run("dev")
    res = {"test": test, "dev": dev}
    path = os.path.join(os.path.dirname(__file__), "eis_results.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    e = test["eis"]
    print(f"{EIS_VERSION} test: EIS={e['value']:.1f} chars/min  CI95=[{e['ci95'][0]:.1f}, {e['ci95'][1]:.1f}]  (dev {dev['eis']['value']:.1f})")
    for c, v in test["cuj"].items():
        print(f"  {c} w={v['weight']:.2f} EIS_c={v['eis']:.1f} CI=[{v['ci95'][0]:.1f},{v['ci95'][1]:.1f}] n={test['n_items'][c]} {v['status']}")
    for ab in test["ablations"]:
        print(f"  ablation {ab['name']}: delta={ab['delta']:+.2f} CI=[{ab['delta_ci95'][0]:+.2f},{ab['delta_ci95'][1]:+.2f}]")
    if args.report:
        rp = os.path.join(ROOT, "docs", "eis_baseline_report.md")
        with open(rp, "w", encoding="utf-8") as f:
            f.write(render_report(test, dev))
        print("wrote", rp)


if __name__ == "__main__":
    main()
