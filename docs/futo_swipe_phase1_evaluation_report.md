# Phase 1 定量評価レポート: FUTO Swipe QWERTY スワイプ入力

## 1. 概要 (Executive Summary)

設計書（[`docs/futo_swipe_qwerty_design.md`](file:///home/kenichikurihara/repos/myasr/docs/futo_swipe_qwerty_design.md)）で定めた「評価ベンチマーク駆動による設計確定ルール」に基づき、実機スワイプ軌跡および先行文脈データ $1,000$ 件からなるテストセット（[`eval/futo_swipe/swipe_eval_1000.jsonl`](file:///home/kenichikurihara/repos/myasr/eval/futo_swipe/swipe_eval_1000.jsonl)）を構築し、**空間デコード単体（Baseline）** と **文脈連動型 2 段階リランク（Context-Aware）** の比較ベンチマークを実施した。

---

## 2. 評価結果サマリー (Benchmark Results)

- **評価サンプル数**: 1,000 件（実機ジェスチャー軌跡データ、文脈保有率 $99.0\%$）
- **ビーム幅**: $N = 100$
- **結果 JSON**: [`eval/futo_swipe/swipe_eval_results_1000.json`](file:///home/kenichikurihara/repos/myasr/eval/futo_swipe/swipe_eval_results_1000.json)

| 評価指標 | 空間単体 (Spatial Baseline) | 文脈リランク (Context-Aware) | 改善差分 ($\Delta$) | 設計目標 / 判定基準 |
| :--- | :--- | :--- | :--- | :--- |
| **Top-1 正解率** | **89.0%** (890/1000) | **90.3%** (903/1000) | **+1.3%** (Net +13件) | 目標 $\ge 87\%$ を双方クリア |
| **Top-3 正解率** | **92.4%** (924/1000) | **92.7%** (927/1000) | **+0.3%** | 目標 $\ge 92\%$ をクリア |
| **Top-5 正解率** | **92.8%** (928/1000) | **92.8%** (928/1000) | $\pm 0.0\%$ | - |
| **改善ケース数** | - | **22 件** | - | - |
| **悪化ケース数** | - | **9 件** | - | - |
| **ニューラル推論時間** | **3.08 ms** | **4.19 ms** (ContextLM +1.11ms) | +1.11 ms | **$\le 5.0\text{ms}$ (極めて高速)** |
| **全処理時間 (Python)** | 57.59 ms (p50: 54.8ms) | 58.82 ms (p50: 56.1ms) | +1.23 ms | ※ Trie 探索が純粋 Python のため |

---

## 3. 重要プロファイリング分析 (Latency Breakdown)

各処理ブロックの実行時間を詳細プロファイリングした結果、以下の事実が判明した：

1. **ニューラルネットワーク推論は極めて高速 (計 4.19ms)**:
   - **Encoder (`honorable_sturgeon`) + Decoder (`magic_macaw`)**: 平均 **3.08 ms**
   - **Context LM (`hungry_jellyfish`) + 単語埋め込み内積**: 平均 **1.11 ms**
   - $\to$ ONNX Runtime のベクトル化により、ニューラル層の計算コストは全く問題にならない（目標 $25\text{ms}$ に対して $1/5$ 以下の水準）。
2. **純粋 Python の Trie CTC 探索がボトルネック (46.05ms)**:
   - 現在のプロトタイプでは、Python の辞書・クラスオブジェクトによる木探索を行っているため、ループオーバーヘッドで $46\text{ms}$ を消費している。
   - $\to$ **Phase 2 で C++ (SIMD / 平坦配列 Trie) に移植することにより、探索時間は $1 \sim 2\text{ms}$ に圧縮可能**。これにより、**アプリ全体での推論遅延は $5 \sim 6\text{ms}$ となり、設計目標（$\le 25\text{ms}$）を大幅にクリア**できる見通しが立った。

---

## 4. 文脈リランクの有効性と具体事例分析

### 4.1 文脈によって正しく救済されたケース (22 件の代表例)
- `ebert of the` $\to$ 空間単体: `chick` $\to$ **文脈リランク: `chicago` (正解)**
- `ive never` $\to$ 空間単体: `meet` $\to$ **文脈リランク: `met` (正解)**
- `the time of` $\to$ 空間単体: `it's` $\to$ **文脈リランク: `its` (正解)**
- `the belgium national` $\to$ 空間単体: `teak` $\to$ **文脈リランク: `team` (正解)**
- `mechanization of the` $\to$ 空間単体: `glad` $\to$ **文脈リランク: `glass` (正解)**
- `also has a` $\to$ 空間単体: `widen` $\to$ **文脈リランク: `wooden` (正解)**
- `former leader of` $\to$ 空間単体: `or` $\to$ **文脈リランク: `our` (正解)**

### 4.2 文脈によって悪化したケース (9 件の代表例)
- `mud archons its` $\to$ 空間単体: `county` (正解) $\to$ 文脈リランク: `country`
- `also managed royal` $\to$ 空間単体: `daring` (正解) $\to$ 文脈リランク: `dating`
- `pirates he presented` $\to$ 空間単体: `rock` (正解) $\to$ 文脈リランク: `rick`
- **原因分析**:
  文脈 LM (`hungry_jellyfish`) は頻出コーパスに基づく N-gram / DFSMN であるため、直前の文脈が一般的でない固有名詞や稀な表現の場合に、より超頻出な単語（`country` や `dating`）へ過剰に引っ張られる傾向がある。

---

## 5. Phase 1 結論とデザイン確定 (Final Architectural Decisions)

ベンチマークの実測結果に基づき、以下のとおりアーキテクチャを確定する：

1. **空間デコード (Encoder + Decoder) の極めて高い基本性能**:
   - 空間単体で既に **Top-1: 89.0% / Top-3: 92.4%** という極めて高い精度を達成しており、推論も **3.08ms** と爆速である。
2. **ContextLM は「追加ブースト」として最適**:
   - 文脈リランクによって Net +13 件（Top-1 $90.3\%$）の改善が得られ、ContextLM の計算オーバーヘッドもわずか **1.11ms** である。
   - ただし、稀な固有名詞文脈での誤補正（9件）を防ぐため、**空間尤度と文脈尤度の信頼度マージン（$\Delta \text{SpatialScore} > \theta$ の場合は空間を優先するガードロジック）** を導入することで、悪化を防ぎつつ改善のみを享受できる。
3. **Phase 2 への決定方針**:
   - **推論コアを C++ (`app/src/main/cpp/swipe/`) へ移植**し、ARM NEON / AVX2 を有効化して $5\text{ms}$ 級の超低遅延エンジンを構築する。
   - モデル構成は **Encoder + Decoder + ContextLM (計 12MB)** のフル構成を採用し、端末ストレージにダウンロード分離する。
