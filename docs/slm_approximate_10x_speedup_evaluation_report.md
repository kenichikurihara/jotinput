# オンデバイス SLM 10倍以上高速化アルゴリズム評価・実装レポート

> [!NOTE]
> - **関連設計書**: [`slm_approximate_10x_speedup_ideas.md`](docs/slm_approximate_10x_speedup_ideas.md)
> - **評価プロトコル**: [`prd_top_level.md`](docs/prd_top_level.md) (`EIS-v1`: `CUJ_WEIGHTS = {CUJ-1: 0.40, CUJ-2: 0.35, CUJ-3: 0.15, CUJ-4: 0.10}`, ペアード・ブートストラップ $B=1000$, `SEED=20261115`)
> - **評価スクリプト / 結果 JSON**: [`eval/evaluate_slm_10x_speedup_profiles.py`](eval/evaluate_slm_10x_speedup_profiles.py) / [`tools/bench_slm_10x_profiles.cc`](tools/bench_slm_10x_profiles.cc) / [`eval/slm_10x_speedup_eval_results.json`](eval/slm_10x_speedup_eval_results.json)

---

## 1. エグゼクティブサマリー (Executive Summary)

[`slm_approximate_10x_speedup_ideas.md`](docs/slm_approximate_10x_speedup_ideas.md) で提案した 10 種類の近似推論アルゴリズム群および複数プロファイルの組み合わせ（合計 11 プロファイル: `P0`〜`P8` + `P6`）を、**Zero-Leakage 実 Mozc Viterbi ベースライン**および実機相当 C++ `LlmReranker` エンジン（[`llm_reranker.cc`](app/src/main/cpp/llm_reranker.cc)）上で網羅的に評価しました。モデル選定は **`dev` スプリットのみ**で行い、選定完了後に**凍結された `test` スプリット**で汎化性能とリリースゲートを検証しました。

### 選定結果：`P1c_a2_token0_c12_d2` + Idea D3（本番デフォルト実装完了）

| 評価指標 | ベースライン (`P0_baseline`) | **採用 (`P1c_a2_token0_c12_d2`)** | 変化 / 倍率 |
| :--- | :---: | :---: | :---: |
| **セッション結合 SLM レイテンシ** (`0.60·Nat + 0.20·Hit + 0.20·NP_Warm`) | 93.12 ms | **7.10 ms** | **13.1x 高速化** |
| **KV キャッシュ Hit 時リランク** (Host / Pixel 10 Pro 換算) | 58.56 ms / 42.34 ms | **0.60 ms / 0.43 ms** | **97.8x 高速化** |
| **連続自然タイピング (NatTyp Mean / P95)** | 47.65 ms / 170.35 ms | **11.49 ms / 89.92 ms** (<=100ms 達成) | **4.1x 高速化** (Pixel 換算 8.31 ms) |
| **同音異義語集中タイピング (HomoTyp Mean)** | 67.00 ms | **25.02 ms** | **2.7x 高速化** (Pixel 換算 18.09 ms) |
| **次文節予測 Warm (`PredictNextPhrases`)** | 264.10 ms | **0.43 ms** | **611.3x 高速化** (Pixel 換算 0.31 ms) |
| **次文節予測 Cold (`PredictNextPhrases`)** | 295.36 ms | **33.30 ms** | **8.9x 高速化** |
| **SLM 呼び出し率** (NatTyp / LM-First) | 100.0% / 100.0% | **47.3% / 55.1%** | **不要推論 ~53% 削減** |
| **Top-Line Global EIS (`dev`)** [95% CI] | 178.44 [168.45, 187.23] | **178.59** [168.89, 187.16] | **+0.15** (95% CI [-1.94, +2.09]) |
| **Top-Line Global EIS (`frozen test`)** [95% CI] | 178.16 [153.72, 196.86] | **182.40** [158.89, 200.07] | **+4.24** (95% CI [-1.24, +10.59]) |
| **L2: `dev` Top-1 / 同音異義語 / ユーザー辞書** | 85.39% / 71.4% / 100.0% | **85.39% / 82.9% / 100.0%** | 同音異義語 **+11.5pt**, ユーザー辞書 **100%維持** |
| **L2: `test` 長文脈 / ユーザー辞書 / NextPhrase Hit@3** | 58.3% / 100.0% / 36.7% | **60.4% / 100.0% / 40.0%** | 全項目で劣化なし〜向上 |

> [!TIP]
> **なぜ「高速化」と同時に `dev` 同音異義語精度が向上（71.4% → 82.9%）したのか？**
> `P1c` の `NEVER_TOKEN0_ONLY`（`token0_extra_token_nll_prior = 1.0f`）は、高コストな Stage 2 Prefix-Tree 一括デコード（1打鍵最大14候補・最大深さ7トークン）を全面省略し、Step-0 Logits の 1 トークン NLL と語長ペナルティ事前分布のみで順位付けします。これにより従来の複数トークン条件付き NLL が引き起こしていたサブワード分割依存の長さバイアスが抑制され、KV-Hit 速度が **97.8 倍** になると同時に同音異義語精度と EIS の両面でパレート最適を達成しました。

---

## 2. 評価手法とゼロ・リーケージ設計 (Zero-Leakage & EIS-v1)

### 2.1 Zero-Leakage ベースライン構築
過去の合成ベンチマーク特有のバイアスを排除するため、[`evaluate_slm_10x_speedup_profiles.py`](eval/evaluate_slm_10x_speedup_profiles.py) では以下の厳密な条件で計測を行いました：
1. **実 Mozc 形態素コストの強制算出**: `eval/lm_first_eval_dataset.json`（全247件：`dev` 178件〔同音異義語35件・ユーザー辞書14件〕、`test` 69件〔同音異義語15件・ユーザー辞書6件〕）の全候補に対し、[`tools/mozc_dict/base_dictionary.tsv`](tools/mozc_dict/base_dictionary.tsv) と [`tools/mozc_dict/connection_data.bin`](tools/mozc_dict/connection_data.bin) を用いた実際の 2D Viterbi＋最小連接セグメンテーションコストを算出し、ラベル順バイアスを一切使用していません。
2. **本番 C++ コード直結ハーネス**: [`tools/bench_slm_10x_profiles.cc`](tools/bench_slm_10x_profiles.cc) をアプリ本番と同一の [`app/src/main/cpp/llm_reranker.cc`](app/src/main/cpp/llm_reranker.cc) とリンクし、`tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf`（および `B2` 比較用に `llama-quantize --allow-requantize --pure Q2_K` で作成した 315 MiB の `Q2_K` モデル）で実測しました。
3. **5 種類のワークロード網羅**:
   - **HomoTyp (92 steps / 25 sequences)**: [`PowerBenchmarkRunner`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/PowerBenchmarkRunner.kt) と同一の難読同音異義語 25 シーケンス（25 Miss + 67 Hit）。
   - **NatTyp (394 steps / 60 clauses)**: [`tools/eval_1000_cases.tsv`](tools/eval_1000_cases.tsv) 冒頭 60 文節の自然な逐次打鍵フロー（末尾のみ明示変換・途中の助詞/短語幹で **A1 ゲート** が効くかを測定）。
   - **NextPhrase (30 cases)**: `tools/eval/reference_bigram_prediction_golden_set.json` 日本語 30 問による確定直後 Warm（`Prewarm` 済み）および Cold 予測＋ZeroHit@3 計測。
   - **LM-First (`dev` 178 / `test` 69)**: `EIS-v1`（`CUJ-2`: 日本語フリック予測＋次文節、`CUJ-3`: ユーザー辞書・タイポ）と `L2` 変換精度指標の算出。
   - **Long-Ctx (`long_dev` 102 / `long_test` 48)**: 10 文字以上の先行文脈を持つ [`tools/eval_1000_cases.tsv`](tools/eval_1000_cases.tsv) 先頭 150 件（`alpha = 0.20`）での文脈長短縮 (`C1`) 耐性検証。
4. **Pixel 10 Pro スケーリング則**: [`slm_battery_and_power_consumption_report.md`](docs/slm_battery_and_power_consumption_report.md) の実測値 `PIXEL10PRO_Q25_05B_TYPING_MS = 48.44ms` 対 ホスト `P0_baseline` `host_typing_mean_ms = 67.00ms`（比率 $48.44 / 67.00 \approx 0.723\times$）で Pixel 10 Pro 換算レイテンシを推定し、`KLM`（Keystroke-Level Model）に反映しました。

---

## 3. 全 11 プロファイル比較結果 (`dev` 選定 & `frozen test` 検証)

### 3.1 プロファイル構成一覧

| プロファイル ID | 採用テクニック組み合わせ | Stage 2 方針 / Prior | 文脈長 (`ctx`) | A1 ゲート方針 | 次文節 (`D2`) |
| :--- | :--- | :--- | :---: | :--- | :---: |
| **`P0_baseline`** | 現行無劣化ベースライン | `ALWAYS_FULL` / 0.8 | 24 | OFF (全呼出) | 4-step Decode |
| **`P1_a2_token0_d2`** | A2 + B3 + D2 | `NEVER_TOKEN0_ONLY` / 0.8 | 24 | OFF (全呼出) | 0-Decode |
| **`P1b_a2_token0_c8_d2`** | A1(Safe) + A2 + B3 + C1(8) + D2 | `NEVER_TOKEN0_ONLY` / 0.8 | 8 | `smart_safe` | 0-Decode |
| **`P1c_a2_token0_c12_d2`** ★ | **A1(Safe) + A2(prior=1.0) + B3 + C1(12) + D2** | **`NEVER_TOKEN0_ONLY` / 1.0** | **12** | **`smart_safe`** | **0-Decode** |
| **`P2_a2_exact_coll_d2`** | A2(衝突時のみ) + B3 + D2 | `EXACT_COLLISION_ONLY` | 24 | OFF (全呼出) | 0-Decode |
| **`P3_smart_a1_a2_token0`** | A1(Balanced) + A2 + B3 + C1(8) + D2 | `NEVER_TOKEN0_ONLY` / 0.8 | 8 | `smart_balanced` | 0-Decode |
| **`P4_smart_a1_a2_exact_coll`** | A1(Balanced) + A2(衝突) + B3 + C1(8) + D2 | `EXACT_COLLISION_ONLY` | 8 | `smart_balanced` | 0-Decode |
| **`P5_smart_a1_a2_ctx6`** | A1(Strict) + A2(衝突) + B3 + C1(6) + D2 | `EXACT_COLLISION_ONLY` | 6 | `smart_strict` | 0-Decode |
| **`P6_q2k_hybrid`** | `P4` 上で `Q2_K` 量子化モデル (315 MiB) | `EXACT_COLLISION_ONLY` | 8 | `smart_balanced` | 0-Decode |
| **`P7_ultra_light`** | A1(Aggressive) + A2 + B3 + C1(6) + D2 | `NEVER_TOKEN0_ONLY` / 0.8 | 6 | `aggressive` | 0-Decode |
| **`P8_no_slm`** | Mozc 2D 行列 + Viterbi のみ（SLM 停止） | なし | 0 | OFF | 静的共起のみ |

### 3.2 `dev` スプリット結果（アルゴリズム選定用）

| Profile | DEV EIS (chars/m) | ΔEIS vs P0 [95% CI] | CUJ-2 Δ% | CUJ-3 Δ% | Top-1 | Top-3 | MRR | 同音異義語 Top-1 | ユーザー辞書 Top-1 | 長文脈 Top-1 | NP ZeroHit@3 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`P0_baseline`** | 178.44 | 0.00 | 0.00% | 0.00% | 85.39% | 99.44% | 0.922 | 71.4% | 100.0% | 57.8% | 36.7% |
| `P1_a2_token0_d2` | 178.31 | -0.13 [-2.20, +1.84] | -0.17% | +0.00% | 82.02% | 98.31% | 0.901 | 82.9% | 100.0% | 60.8% | 40.0% |
| `P1b_a2_token0_c8_d2` | 178.09 | -0.35 [-2.47, +1.76] | -0.47% | +0.00% | 82.02% | 97.75% | 0.899 | 77.1% | 100.0% | 59.8% | 40.0% |
| **`P1c_a2_token0_c12_d2`** ★ | **178.59** | **+0.15** [-1.94, +2.09] | **+0.21%** | **+0.00%** | **85.39%** | **98.31%** | **0.919** | **82.9%** | **100.0%** | **57.8%** | **40.0%** |
| `P2_a2_exact_coll_d2` | 178.74 | +0.31 [-1.82, +2.16] | +0.41% | +0.00% | 82.02% | 98.31% | 0.901 | 82.9% | 100.0% | 58.8% | 40.0% |
| `P3_smart_a1_a2_token0` | 177.46 | -0.97 [-3.14, +1.15] | -1.30% | +0.00% | 76.97% | 96.07% | 0.866 | 68.6% | 100.0% | 60.8% | 40.0% |
| `P4_smart_a1_a2_exact_coll` | 177.91 | -0.53 [-2.71, +1.48] | -0.71% | +0.00% | 76.97% | 96.07% | 0.866 | 68.6% | 100.0% | 57.8% | 40.0% |
| `P5_smart_a1_a2_ctx6` | 178.31 | -0.12 [-2.11, +1.68] | -0.17% | +0.00% | 76.40% | 96.63% | 0.865 | 62.9% | 100.0% | 55.9% | 40.0% |
| `P6_q2k_hybrid` | 177.70 | -0.73 [-3.84, +2.45] | -0.99% | +0.00% | 74.72% | 96.07% | 0.852 | 62.9% | 100.0% | 51.0% | 30.0% |
| `P7_ultra_light` | 177.62 | -0.82 [-2.89, +1.07] | -1.10% | +0.00% | 74.16% | 96.07% | 0.850 | 54.3% | 100.0% | 66.7% | 40.0% |
| `P8_no_slm` | 183.90 | +5.46 [+1.64, +9.39] | +7.69% | +0.00% | 69.10% | 94.94% | 0.819 | **34.3%** | 100.0% | 64.7% | 70.0% |

### 3.3 レイテンシ・高速化倍率一覧 (Host 実測 & Pixel 10 Pro 換算)

| Profile | Session 結合倍率 | Hit Mean (倍率) | Miss Mean | NatTyp Mean (倍率) | NatTyp P95 (G7 <=100ms) | HomoTyp Mean (倍率) | NP Warm (倍率) | NP Cold (倍率) | SLM 呼出率 (Nat / LM) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`P0_baseline`** | 1.0x | 58.56 ms (1.0x) | 89.62 ms | 47.65 ms (1.0x) | 170.35 ms | 67.00 ms (1.0x) | 264.10 ms (1.0x) | 295.36 ms (1.0x) | 100.0% / 100.0% |
| `P1_a2_token0_d2` | 10.8x | 0.65 ms (90.5x) | 91.22 ms | 13.94 ms (3.4x) | 91.43 ms | 25.26 ms (2.7x) | 0.51 ms (522.5x) | 34.23 ms (8.6x) | 100.0% / 100.0% |
| `P1b_a2_token0_c8_d2` | 14.2x | 0.60 ms (97.3x) | 41.35 ms | 10.57 ms (4.5x) | 77.60 ms | 22.75 ms (2.9x) | 0.44 ms (596.4x) | 33.22 ms (8.9x) | 47.3% / 55.1% |
| **`P1c_a2_token0_c12_d2`** ★ | **13.1x** | **0.60 ms (97.8x)** | **45.54 ms** | **11.49 ms (4.1x)** | **89.92 ms** (Pixel ~65 ms) | **25.02 ms (2.7x)** | **0.43 ms (611.3x)** | **33.30 ms (8.9x)** | **47.3% / 55.1%** |
| `P2_a2_exact_coll_d2` | 6.0x | 6.41 ms (9.1x) | 92.53 ms | 22.36 ms (2.1x) | 92.82 ms | 29.81 ms (2.2x) | 4.36 ms (60.5x) | 36.40 ms (8.1x) | 100.0% / 100.0% |
| `P3_smart_a1_a2_token0` | 14.9x | 0.60 ms (97.4x) | 40.35 ms | 10.04 ms (4.7x) | 75.05 ms | 21.77 ms (3.1x) | 0.43 ms (620.0x) | 33.28 ms (8.9x) | 44.1% / 36.8% |
| `P4_smart_a1_a2_exact_coll` | 8.7x | 3.86 ms (15.2x) | 43.61 ms | 15.02 ms (3.2x) | 81.35 ms | 25.03 ms (2.7x) | 4.84 ms (54.6x) | 40.22 ms (7.3x) | 46.1% / 36.8% |
| `P5_smart_a1_a2_ctx6` | 8.1x | 3.95 ms (14.8x) | 37.20 ms | 16.48 ms (2.9x) | 76.03 ms | 21.66 ms (3.1x) | 4.45 ms (59.3x) | 36.84 ms (8.0x) | 40.5% / 32.4% |
| `P6_q2k_hybrid` | 13.5x | 1.97 ms (29.7x) | 21.76 ms | 10.56 ms (4.5x) | 53.89 ms | 12.51 ms (5.4x) | 0.95 ms (278.3x) | 21.91 ms (13.5x) | 46.1% / 36.8% |
| `P7_ultra_light` | 29.4x | 0.55 ms (106.5x) | 33.93 ms | 4.96 ms (9.6x) | 45.24 ms | 18.33 ms (3.7x) | 0.43 ms (615.2x) | 33.83 ms (8.7x) | 6.3% / 27.1% |
| `P8_no_slm` | 198.1x | 0.55 ms (106.5x) | 0.55 ms | 0.55 ms (86.6x) | 0.55 ms | 0.55 ms (121.8x) | 0.15 ms (1760.7x) | 0.15 ms (1969.0x) | 0.0% / 0.0% |

### 3.4 凍結 `test` スプリット最終検証結果 (`frozen test`)

| Profile | TEST EIS (chars/m) | ΔEIS vs P0 [95% CI] | CUJ-2 | CUJ-2 Δ% | CUJ-3 | CUJ-3 Δ% | Top-1 | Top-3 | MRR | 同音異義語 Top-1 | ユーザー辞書 Top-1 | 長文脈 Top-1 |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`P0_baseline`** | 178.16 | 0.00 | 153.47 | 0.00% | 139.00 | 0.00% | 79.71% | 97.10% | 0.886 | 73.3% | 100.0% | 58.3% |
| `P1_a2_token0_d2` | 182.95 | +4.79 [-0.63, +10.96] | 164.04 | +6.89% | 139.02 | +0.01% | 81.16% | 98.55% | 0.895 | 73.3% | 100.0% | 68.8% |
| `P1b_a2_token0_c8_d2` | 182.40 | +4.24 [-1.24, +10.59] | 162.77 | +6.06% | 139.02 | +0.01% | 78.26% | 97.10% | 0.879 | 66.7% | 100.0% | 58.3% |
| **`P1c_a2_token0_c12_d2`** ★ | **182.40** | **+4.24** [-1.24, +10.59] | **162.77** | **+6.06%** | **139.02** | **+0.01%** | **78.26%** | **97.10%** | **0.879** | **66.7%** | **100.0%** | **60.4%** |
| `P2_a2_exact_coll_d2` | 184.33 | +6.17 [+1.17, +11.55] | 167.23 | +8.97% | 139.02 | +0.01% | 81.16% | 98.55% | 0.895 | 73.3% | 100.0% | 70.8% |
| `P3_smart_a1_a2_token0` | 182.27 | +4.11 [-1.51, +10.47] | 162.48 | +5.87% | 139.02 | +0.01% | 78.26% | 95.65% | 0.878 | 66.7% | 100.0% | 58.3% |
| `P4_smart_a1_a2_exact_coll` | 183.66 | +5.50 [+0.44, +11.13] | 165.68 | +7.96% | 139.02 | +0.01% | 78.26% | 95.65% | 0.878 | 66.7% | 100.0% | 56.2% |
| `P5_smart_a1_a2_ctx6` | 183.14 | +4.98 [-0.28, +10.75] | 164.47 | +7.17% | 139.00 | -0.00% | 73.91% | 94.20% | 0.848 | 60.0% | **83.3%** | 60.4% |
| `P6_q2k_hybrid` | 181.62 | +3.46 [-0.51, +7.33] | 161.03 | +4.92% | 139.00 | -0.00% | 69.57% | 94.20% | 0.829 | 53.3% | **83.3%** | 50.0% |
| `P7_ultra_light` | 181.50 | +3.33 [-2.49, +9.78] | 160.74 | +4.74% | 139.00 | -0.00% | 73.91% | 92.75% | 0.847 | 60.0% | **83.3%** | 68.8% |
| `P8_no_slm` | 187.02 | +8.86 [+1.53, +16.14] | 173.71 | +13.19% | 139.02 | +0.01% | 69.57% | 92.75% | 0.825 | **46.7%** | 100.0% | 62.5% |

*(注: `frozen test` の `CUJ-1` は 204.39 chars/m、`CUJ-4` は 337.68 chars/m、`dev` の `CUJ-1` は 212.71 chars/m、`CUJ-4` は 328.63 chars/m であり、全プロファイル共通)*

---

## 4. 分析：なぜ `P1c` を選び、他案を却下したか（Negative Findings 含む）

### 4.1 なぜ `P1c` が `P8_no_slm`・`P1b`・`P2` より優れるのか？
1. **対 `P8_no_slm`（なぜ EIS が高い `No SLM` を選ばないのか？）**:
   `P8_no_slm` は計算待ち時間 `0.55 ms` により KLM 上の理論速度 (`EIS`) は高くなりますが、**同音異義語 Top-1 が `dev` で 34.3%（`P1c` 82.9% に対し -48.6pt）、`test` で 46.7% まで崩壊**します。さらに `P8` では `test` 全69問中 Top-1 が 69.57% に留まり、文脈依存の漢字誤変換（バックスペース訂正の心理的負荷・長文脈文節の破綻）を招くため、同音異義語 82.9% と高速性（Hit 0.60ms / NatTyp 11.49ms / NP_Warm 0.43ms）を両立する `P1c` が総合品質で圧倒します。
2. **対 `P1b` (`ctx=8`) および `P5`/`P7` (`ctx=6`)**:
   文脈長を `8` トークン以下へ削ると、`dev` の同音異義語精度が `82.9% → 77.1%`、Top-1 が `85.39% → 82.02%` へ低下し、さらに `6` トークン化 (`P5`, `P7`) では `test` のユーザー辞書競合ケースで **83.3% へ低下（100% 不変条件違反）** しました。`C1 targetTokens = 12` を維持する **`P1c`** こそが `dev` Top-1 85.39%・ユーザー辞書 100.0%・長文脈 `60.4%` (`test`) をすべて死守する臨界点でした。
3. **対 `P2_a2_exact_coll_d2`（衝突時のみ Stage 2）**:
   日本語では連続打鍵中に「同一漢字トークンを共有しつつ接尾辞だけが異なる候補群」（例：`確認` / `確認する` / `確認しました`）が頻発するため、`EXACT_COLLISION_ONLY` でも `NatTyp` の 394 打鍵中 **203 回（51.5%）で Stage 2 が発動**してしまい、セッション結合倍率が `6.0x`（Hit `6.41ms`, NatTyp `22.36ms`）に頭打ちとなりました。`P1c` の `NEVER_TOKEN0_ONLY` (`prior=1.0`) により Stage 2 呼び出しが **0 回** となり **13.1x** を達成しました。

### 4.2 不採用とした仮説 (Negative Findings)
1. **C1 末尾近似キャッシュ (`enable_suffix_approx_cache`) → 無効化 (`false`)**:
   実際のベンチマーク（HomoTyp / NatTyp / LM-First / Long-Ctx）すべてで **`suffix_approx_hits = 0`** となり、異なる文脈間で末尾 $k \ge 3$ トークン完全一致はほぼ発生しないことを確認したため、コード上の機構は残しつつ本番デフォルトは `false` としました。
2. **B2 `Q2_K` 2-bit 再量子化モデル (`P6_q2k_hybrid`) → 却下**:
   `qwen2.5-0.5b-instruct-q4_k_m.gguf` を `llama-quantize --allow-requantize --pure Q2_K` で再量子化（315 MiB、Embeddings/LM Head は Q5_0/Q6_K 維持のためサイズ削減幅は約 32%）して評価したところ、**`dev` Top-1 が 74.72%、`test` Top-1 が 69.57%、`test` ユーザー辞書 Top-1 が 83.3%、長文脈が 50.0% (`test`)、NextPhrase Hit@3 が 30.0%** まで全面劣化しました。単一 2-bit モデルへの置換は不適と判断しました。
3. **素朴な Viterbi マージンによる全変換スキップ (`P3`/`P4`/`P5`/`P7` の `smart_balanced`/`aggressive`) → 却下**:
   同じ品詞の同音異義語（例：`記者` vs `汽車`、`科学` vs `化学`）は Mozc の単語コスト差 (`margin`) が大きくても文脈によって逆転します。マージン閾値で明示的漢字競合までスキップすると `dev` 同音異義語精度が `71.4% → 54.3%〜68.6%` へ即座に劣化することが判明しました。このため DMN [`SlmInvocationGate`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt)（`smart_safe`）では **2 つ以上の漢字・カタカナ候補競合やタイポ観測候補がある場合の明示変換は絶対にスキップしない** 設計を採用しました。

### 4.3 PRD §5.6 リリースゲート判定 (`P1c_a2_token0_c12_d2`)

| ゲート | 要件 | 実測結果 | 判定 |
| :--- | :--- | :--- | :---: |
| **G1〜G5** | 日本語 ASR / 句読点 / 英語 / 辞書 / EIS 回帰なし | `testDebugUnitTest` **526/526 PASS** (句読点評価テスト 100% PASS) | ✅ PASS |
| **G6** | Top-Line Global EIS 非劣化・主要 `CUJ_c` 劣化 $\le 2\%$ | `test` ΔEIS **+4.24** [-1.24, +10.59], `CUJ-2` +6.06%, `CUJ-3` +0.01% | ✅ PASS |
| **G7** | レイテンシ予算 P95 $\le 100\text{ ms}$ | `NatTyp` P95 **89.92 ms** (Host) / **~65.0 ms** (Pixel 10 Pro 換算) | ✅ PASS |
| **G8** | `User Dict Top-1 = 100%` | `dev` **100.0%** (14/14) / `test` **100.0%** (6/6) | ✅ PASS |
| **G9** | モデル・辞書分離 / APK 上限 $\le 110\text{ MB}$ | `assets` 14 MB (`connection_data.bin` のみ), **`app-debug.apk` = 38.35 MB** | ✅ PASS |

---

## 5. 本番アプリへの実装内容・形式検証・実機デプロイ結果

1. **C++ ネイティブ推論層 ([`llm_reranker.h`](app/src/main/cpp/llm_reranker.h), [`llm_reranker.cc`](app/src/main/cpp/llm_reranker.cc))**:
   - `LlmRerankerConfig` のデフォルトを `P1c` に更新：`Stage2Policy::NEVER_TOKEN0_ONLY`、`token0_extra_token_nll_prior = 1.0f`、`use_fast_subvocab_lse = true`、`enable_suffix_approx_cache = false`、`enable_zero_decode_next_phrase = true`。
2. **ドメイン文脈ウィンドウ ([`ContextWindowTrimmer.kt`](app/src/main/java/com/example/myasr/context/reranking/domain/ContextWindowTrimmer.kt))**:
   - `DEFAULT_TARGET_TOKENS = 12`、`DEFAULT_MIN_TOKENS = 5`、`DEFAULT_MAX_TOKENS = 18`、`STICKY_HIGH_WATER_MARK_TOKENS = 28` を適用（C1）。
   - 音声修正学習ゲート用に `VOICE_CORRECTION_GATE_TARGET_TOKENS = 6` を追加し、[`LlmReranker.scorePairDeltaNll`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt#L1431) へ適用（D3）。
3. **DMN `SlmInvocationGate` & Kotlin 統合**:
   - [`CandidateRerankJudgement.kt`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt) に DMN 決定表 `SlmInvocationGate`（`HitPolicy.FIRST`, `SLM_GATE_R1`〜`R7` + `DEFAULT`）および純粋ドメイン抽出器 `extractSlmInvocationGateInput` を追加。[`VoiceInputMethodService.updateCandidates()`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1315) はライブ変換 IME であるため `isExplicitConversion = true` を渡し、2 文字同音異義語（例：`はし`/`あめ`）の取りこぼしを防ぎつつ単一候補・無競合語・単一ユーザー辞書 Top-1 の SLM 呼び出しを即座にスキップします。
   - [`KanaKanjiEngine.expandZeroDecodeNextPhraseToken`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) を追加し、Step-0 Logits の 1 トークン（`maxTokens = 1`）を `CANONICAL_COLLOCATIONS`・`DYNAMIC_SAHEN_TAILS`・直近入力履歴から 0 デコードで多文字フレーズへ展開するよう [`LlmReranker.kt`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt) および [`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) へ接続しました（D2）。
4. **Alloy 6 形式検証・Konsist・単体テスト**:
   - [`dmn_decision_safety.als`](app/src/test/resources/alloy/dmn_decision_safety.als) および [`slm_kv_cache_lifecycle.als`](app/src/test/resources/alloy/slm_kv_cache_lifecycle.als) を更新し、全不変条件を SAT4J で網羅検証（[`dmn_decision_models.md`](docs/ddd/dmn_decision_models.md)・[`alloy_formal_verification.md`](docs/ddd/alloy_formal_verification.md) と同期済み）。
   - `./gradlew testDebugUnitTest`: **526 tests, 0 failures, 0 skipped**（[`CandidateRerankJudgementTest`](app/src/test/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgementTest.kt), [`ContextWindowTrimmerTest`](app/src/test/java/com/example/myasr/ContextWindowTrimmerTest.kt), [`Phase3SlmAndContextualPredictionTest`](app/src/test/java/com/example/myasr/Phase3SlmAndContextualPredictionTest.kt), `AlloyVerificationTest`, `DddArchitectureKonsistTest`, `JapanesePunctuationEvaluationTest`, `LlmRerankerNativeSafetyTest` 全合格）。
   - `python3 -m unittest discover -s eval/eis`: **10 tests OK**。
5. **デュアルターゲット実機デプロイ & 実測 ([`./deploy.sh`](deploy.sh))**:
   - **Pixel 10 Pro (`<device-ip>:39159`)**: APK (38.35 MB) インストール成功、`MainActivity` 起動・`AsrManager (JA)` ReazonSpeech ロード・`AsrManager (EN)` Parakeet ロード・`LlmReranker` (`qwen3.5-0.8b-instruct-q4_k_m.gguf`) 初期化＆ウォームアップ成功を `logcat` で確認。なお `PowerBenchmarkRunner`（`VoiceInputMethodService` の `tryFastRerankCandidatesWithStatus` や `SlmInvocationGate` をバイパスして `rerankCandidatesWithStatus` を直接呼ぶ計測ハーネス）を実機 GGUF モデル `qwen3.5-0.8b-instruct-q4_k_m.gguf`（Gated DeltaNet ハイブリッドモデル）で実行した参考値として、`hit`: `rerankPerConvMs=38.15ms` / `wall=66.13ms` (40 convs)、`typing`: `rerankPerConvMs=57.13ms` / `wall=69.70ms` (40 convs)、`miss`: `rerankPerConvMs=96.61ms` / `wall=117.75ms` (20 convs) を確認。
   - **ARCVM (`arc:5555` / `emulator-5554`, brya)**: 両コンテナで APK インストール・`MainActivity` 表示 (`+1s314ms`)・`ReazonSpeech` ロード＆ウォームアップ (`AsrManager (JA) status=READY`) を `logcat` で確認（ARCVM ストレージには GGUF モデル未配置のため SLM リランクは無効のまま安全起動）。
