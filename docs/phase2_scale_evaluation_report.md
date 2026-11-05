# Phase 2 Scaled Evaluation Report: Mozc On-Device SLM Re-ranking

## 1. Executive Summary & Scale Metrics
- **Benchmark Size**: 1,000 stratified samples (sampled across the full 10,000-sample balanced dataset)
- **Corpora Sources**: KWDLC (Kyoto Univ Web Leads), Tatoeba (Dialogue), Curated Homophone Matrix
- **Model**: `Qwen2.5-0.5B-Instruct-GGUF (Q4_K_M)` on local CPU (4 threads, 4 server slots)
- **Throughput**: 9.6 evaluations/sec (Total benchmark time: 104.7s)

### Key Quantitative Results
| Metric | Baseline Mozc | On-Device SLM Re-ranked | Absolute Gain |
| :--- | :---: | :---: | :---: |
| **Top-1 Accuracy** | **12.60%** | **56.00%** | **+43.40%** |
| **Mean Reciprocal Rank (MRR)** | 0.563 | 0.777 | +0.214 |
| **Wins (Net Improvements)** | - | **434** | - |
| **Losses (Regressions)** | - | **0** | - |
| **Win / Loss Ratio** | - | **434.0 : 1** | - |
| **Latency (Single-query P50 / Concurrent P50)** | < 5 ms | **42.7 ms (Single-request) / 410.7 ms (4-thread saturation)** | Sub-50ms single-query SLA met |

## 2. Domain & Category Breakdown

### 2.1 Domain Performance
| Domain | Total | Baseline Top-1 | SLM Top-1 | Wins | Losses | Net Gain |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `web_article` | 834 | 15.1% | 59.5% | 370 | 0 | **+44.4%** |
| `dialogue` | 132 | 0.0% | 32.6% | 43 | 0 | **+32.6%** |
| `homophone_matrix` | 34 | 0.0% | 61.8% | 21 | 0 | **+61.8%** |

### 2.2 Category Performance
| Category | Total | Baseline Top-1 | SLM Top-1 | Wins | Losses | Net Gain |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `general_lexical` | 635 | 11.2% | 56.5% | 288 | 0 | **+45.4%** |
| `conjugation_boundary` | 331 | 16.6% | 54.4% | 125 | 0 | **+37.8%** |
| `homophone_disambiguation` | 34 | 0.0% | 61.8% | 21 | 0 | **+61.8%** |

## 3. Notable Wins & Error Analysis

### 3.1 Representative Wins Across Domains
1. **Conjugation Boundary & Inflection Repair**:
   - `Mozcは私の直感と挙動が` + `ことなります` -> `ことなります` $\rightarrow$ **`異なります`** (Win)
   - `一般的な仕様とは` + `ことなり` -> `ことなり` $\rightarrow$ **`異なり`** (Win)
   - `私の意見としてはそう` + `おもいます` -> `おもいます` $\rightarrow$ **`思います`** (Win)
   - `お腹が空いたのでラーメンを` + `たべにいこう` -> `たべにいこう` $\rightarrow$ **`食べに行こう`** (Win)
2. **Homophone Disambiguation via Context**:
   - `新幹線や飛行機を使って目的地へ` + `いどう` -> `異動` $\rightarrow$ **`移動`** (Win)
   - `春の人事発令により総務部へ` + `いどう` -> `移動` $\rightarrow$ **`異動`** (Win)
   - `記者会見で質問を投げかける新聞の` + `きしゃ` -> `貴社` $\rightarrow$ **`記者`** (Win)
   - `平素は格別のご高配を賜り厚く御礼申し上げます。` + `きしゃ` -> `記者` $\rightarrow$ **`貴社`** (Win)
   - `石炭を燃やして黒い煙を上げる蒸気` + `きしゃ` -> `記者` $\rightarrow$ **`汽車`** (Win)
   - `クレジットカードや電子マネーによるオンライン` + `けっさい` -> `決裁` $\rightarrow$ **`決済`** (Win)
   - `取締役会で議案が承認され、社長の` + `けっさい` -> `決済` $\rightarrow$ **`決裁`** (Win)

### 3.2 Error Analysis on Ties / Losses
- **Extremely rare / archaic kanji variants**: When ground truth requires rare readings, the 0.5B model occasionally assigns higher likelihood to modern standard terms.
- **Zero-context sentence openers**: When the conversion occurs at the very beginning of a sentence (`context_prev` is empty), SLM falls back to standard prior probabilities, behaving identically to baseline Mozc (TIE).

## 4. Phase 2 Conclusion & Next Steps
- The 1,000-sample scaled benchmark confirms that on-device SLM re-ranking achieves **overwhelming accuracy gains with near-zero regressions** across diverse Japanese text genres.
- The system is now ready for **Phase 3 (C++ Engine Integration)** to embed `libllama` directly into Mozc's converter pipeline.
