# 5-Fold Stratified Cross-Validation & Alpha Hyperparameter Optimization Report

## 1. Executive Summary

We performed **5-fold stratified cross-validation** across the **1,000-sample scale benchmark dataset** ([`tools/eval_1000_cases.tsv`](tools/eval_1000_cases.tsv)) using our native C++ execution harness ([`tools/cv_optimize_alpha.cc`](tools/cv_optimize_alpha.cc)) linking `llama.cpp` and Qwen2.5-0.5B on 8 CPU threads.

The objective was to evaluate the hybrid scoring function:
$$\text{Score}(i) = \alpha \cdot \text{RankCost}_{\text{Mozc}}(i) + (1 - \alpha) \cdot (-\log P(W_i \mid C))$$
and systematically determine the optimal balance $\alpha^*$ between baseline Mozc ranking and on-device SLM log-likelihood.

---

## 2. 5-Fold Cross-Validation Protocol

- **Dataset**: 1,000 stratified samples (250 names, 250 homophones, 150 places, 200 tech, 150 general negatives).
- **Folds**: $K = 5$ folds (Train: 800 samples, Test: 200 samples per fold).
- **Search Space**: $\alpha \in [0.00, 1.00]$ with step $0.01$ (101 grid points).
- **Out-of-Fold (OOF)**: Every sample is evaluated exactly once on the test fold when the model was optimized on the other 4 folds.

---

## 3. Empirical Results

### 3.1 Condition A: Context-Aware SLM (With Context Prefix)

| Fold | Optimal $\alpha^*$ | Train Accuracy (800 cases) | Test Accuracy (200 cases) |
| :--- | :---: | :---: | :---: |
| **Fold 1** | **0.00** | 65.88% (527 / 800) | **70.50%** (141 / 200) |
| **Fold 2** | **0.00** | 66.62% (533 / 800) | **67.50%** (135 / 200) |
| **Fold 3** | **0.00** | 67.25% (538 / 800) | **65.00%** (130 / 200) |
| **Fold 4** | **0.00** | 67.25% (538 / 800) | **65.00%** (130 / 200) |
| **Fold 5** | **0.00** | 67.00% (536 / 800) | **66.00%** (132 / 200) |
| **Mean ± Std** | **0.000 ± 0.00** | **66.80%** | **66.80% ± 2.06%** |
| **Out-of-Fold (OOF)** | — | — | **66.80% (668 / 1,000)** |

*Baseline Mozc (No SLM, $\alpha = 1.00$): **12.60%** (126 / 1,000). Total Gain: **+54.20% absolute points**.*

---

### 3.2 Condition B: Context-Free SLM (Forced Empty Context `<s>`)

| Fold | Optimal $\alpha^*$ | Train Accuracy (800 cases) | Test Accuracy (200 cases) |
| :--- | :---: | :---: | :---: |
| **Fold 1** | **0.00** | 50.00% (400 / 800) | **55.50%** (111 / 200) |
| **Fold 2** | **0.00** | 53.75% (430 / 800) | **40.50%** (81 / 200) |
| **Fold 3** | **0.00** | 51.00% (408 / 800) | **51.50%** (103 / 200) |
| **Fold 4** | **0.00** | 50.75% (406 / 800) | **52.50%** (105 / 200) |
| **Fold 5** | **0.00** | 50.00% (400 / 800) | **55.50%** (111 / 200) |
| **Mean ± Std** | **0.000 ± 0.00** | **51.10%** | **51.10% ± 5.54%** |
| **Out-of-Fold (OOF)** | — | — | **51.10% (511 / 1,000)** |

*Baseline Mozc (No SLM, $\alpha = 1.00$): **12.60%** (126 / 1,000). Total Gain: **+38.50% absolute points**.*

---

## 4. Alpha Sensitivity Analysis (Full 1,000 Dataset)

| $\alpha$ Value | Context-Aware Top-1 | Delta vs Baseline | Context-Free Top-1 | Delta vs Baseline |
| :---: | :---: | :---: | :---: | :---: |
| **0.00 (Pure SLM)** | **66.80% (668 / 1,000)** | **+54.20%** | **51.10% (511 / 1,000)** | **+38.50%** |
| **0.05** | 66.40% (664 / 1,000) | +53.80% | 50.90% (509 / 1,000) | +38.30% |
| **0.10** | 66.30% (663 / 1,000) | +53.70% | 50.60% (506 / 1,000) | +38.00% |
| **0.15** | 65.70% (657 / 1,000) | +53.10% | 49.80% (498 / 1,000) | +37.20% |
| **0.20 (App Default)** | 65.00% (650 / 1,000) | +52.40% | 49.70% (497 / 1,000) | +37.10% |
| **0.25** | 64.30% (643 / 1,000) | +51.70% | 49.40% (494 / 1,000) | +36.80% |
| **0.30** | 63.70% (637 / 1,000) | +51.10% | 48.50% (485 / 1,000) | +35.90% |
| **0.40** | 62.30% (623 / 1,000) | +49.70% | 47.40% (474 / 1,000) | +34.80% |
| **0.50** | 60.10% (601 / 1,000) | +47.50% | 44.30% (443 / 1,000) | +31.70% |
| **0.60** | 56.50% (565 / 1,000) | +43.90% | 38.70% (387 / 1,000) | +26.10% |
| **0.70** | 49.50% (495 / 1,000) | +36.90% | 30.40% (304 / 1,000) | +17.80% |
| **0.80** | 35.30% (353 / 1,000) | +22.70% | 20.40% (204 / 1,000) | +7.80% |
| **0.90** | 16.30% (163 / 1,000) | +3.70% | 12.90% (129 / 1,000) | +0.30% |
| **1.00 (Pure Mozc)** | 12.60% (126 / 1,000) | +0.00% | 12.60% (126 / 1,000) | +0.00% |

---

## 5. Mathematical & Architectural Takeaways

1. **Why $\alpha^* = 0.00$ Universally Wins across All Folds**:
   - Mozc's initial unigram rank order in open-domain tricky sentences is correct only 12.60% of the time because it lacks semantic context.
   - Any non-zero $\alpha$ heavily penalizes valid candidates that happen to be indexed at rank 2 or 3 in Mozc's candidate pool, pulling the prediction towards the flawed baseline.
   - The calibrated log-likelihood of Qwen2.5-0.5B accurately discriminates between candidates; hence trusting the SLM completely ($\alpha = 0.00$) yields the highest empirical accuracy (66.80% Context-Aware / 51.10% Context-Free).
2. **Production Regularization Recommendation ($\alpha = 0.05 \sim 0.10$)**:
   - While pure mathematical optimization yields $\alpha^* = 0.00$, in practical IME typing where SLM log-probabilities between candidate 0 and candidate 1 are within floating-point noise ($\Delta < 0.05$), keeping a slight Mozc prior ($\alpha \approx 0.05$) acts as an effective tie-breaker with almost zero accuracy penalty (66.40% vs 66.80%).
