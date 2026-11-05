# Context-Aware Speech Recognition Evaluation Report

This report presents the quantitative evaluation and comparative analysis of **Context-Aware Speech Recognition** evaluated across our 1,000-utterance tricky Japanese benchmark.

---

## 📊 1. Executive Summary

By leveraging the preceding text box context (`InputConnection.getTextBeforeCursor(500, 0)`), JotInput overcomes the fundamental blindspot of traditional isolated speech recognition: **resolving acoustically indistinguishable Kanji orthographic competitors and semantic homophones**.

### Key Benchmark Highlights
- **Overall Target Word Recall Rate**: Rose from **54.0%** (Baseline) and **80.6%** (Hybrid without Context) to **84.5%** (**+30.5% gain** over Baseline, **+3.9% gain** over Hybrid).
- **People Names (Rare Kanji Variants)**: Jumped from **23.2%** (Baseline) and **66.8%** (Hybrid without Context) to **76.0%** (**+9.2% absolute gain** over Hybrid, **+52.8% gain** over Baseline).
- **Homophones**: Reached **96.0%** recall (disambiguating business greetings, office return, and news reporting contexts).
- **Technical Terms**: Rose from **25.5%** (Baseline) and **73.0%** (Hybrid without Context) to **77.5%**.
- **Character Error Rate (CER)**: Decreased from **6.65%** (Baseline) to **4.42%**.
- **False Positive Rate (FPR)**: Maintained strictly at **0.0%** across 150 negative controls.
- **Latency Overhead**: **0.014 ms per utterance**, ensuring immediate, stutter-free transcription insertion.

---

## 📈 2. Overall Performance Comparison

| Metric | M1: Baseline | M2: Fixed Hotwords | M3: Hybrid (No Context) | M4: Context-Aware Hybrid (With Context) | $\Delta$ (vs. Hybrid) | $\Delta$ (vs. Baseline) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **True Positives (TP)** | 459 / 850 | 505 / 850 | 685 / 850 | **718 / 850** | **+33** | **+259** |
| **False Negatives (FN)** | 391 | 345 | 165 | **132** | **-33** | **-259** |
| **True Negatives (TN)** | 150 / 150 | 150 / 150 | 150 / 150 | **150 / 150** | 0 | 0 |
| **False Positives (FP)** | 0 | 0 | 0 | **0** | 0 | 0 |
| **Precision** | 100.0% | 100.0% | 100.0% | **100.0%** | +0.0% | +0.0% |
| **Recall (TWRR)** | 54.0% | 59.4% | 80.6% | **84.5%** | **+3.9%** | **+30.5%** |
| **Overall Accuracy** | 60.9% | 65.5% | 83.5% | **86.8%** | **+3.3%** | **+25.9%** |
| **F1 Score** | 70.1% | 74.5% | 89.2% | **91.6%** | **+2.4%** | **+21.5%** |
| **Average CER** | 6.65% | 6.82% | 4.68% | **4.42%** | **-0.26%** | **-2.23%** |
| **FPR on Negatives** | 0.00% | 0.00% | 0.00% | **0.00%** | 0.00% | 0.00% |
| **Processing Latency** | - | - | <0.01 ms | **0.014 ms** | +0.004 ms | - |

---

## 📂 3. Category Breakdown: With vs. Without Context

The 1,000-utterance test suite breaks down into five distinct domain categories. Context provides substantial boosts in the most difficult categories:

| Category | Size | Baseline Accuracy | Fixed Hotwords | Hybrid (No Context) | Context-Aware Hybrid (With Context) | Gain from Context |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **People Names** | 250 | 23.2% | 29.2% | 66.8% | **76.0%** | **+9.2%** |
| **Homophones** | 250 | 92.4% | 94.8% | 95.6% | **96.0%** | **+0.4%** |
| **Place Names** | 150 | 79.3% | 84.7% | 88.7% | **88.7%** | +0.0% |
| **Technical Terms** | 200 | 25.5% | 34.0% | 73.0% | **77.5%** | **+4.5%** |
| **Negative Controls** | 150 | 100.0% | 100.0% | 100.0% | **100.0%** | 0.0% |

### Category Observations
1. **People Names (`+9.2%` gain)**:
   - When a person's name is registered (e.g. `賢一`, `庄太`, `翔平`, `榛名`, `颯太`, `陽平`, `壮介`, `由衣`, `渡邊`), baseline acoustic models consistently produce the most popular Kanji competitor (e.g. `健一`, `翔太`, `将平`, `春菜`, `壮太`, `洋平`, `宗介`, `結衣`, `渡辺`).
   - Because these competitors are valid Kanji words, standard phonetic substitution cannot trigger without context.
   - When the user's preceding text establishes the person's name, the context engine resolves the competitor with 100% precision.
2. **Technical Terms (`+4.5%` gain)**:
   - English and compound technical terms often produce phonetic transliterations in the acoustic model (e.g. `GraphQL` $\rightarrow$ `グラフ九L`, `Datadog` $\rightarrow$ `データドック`, `Apache` $\rightarrow$ `アパッシュ`, `TensorRT` $\rightarrow$ `テンソルRT`, `CI/CD` $\rightarrow$ `CICD`).
   - Context presence immediately resolves the transliterations back to the intended technical keyword.
3. **Homophones (`+0.4%` gain)**:
   - Business greeting contexts (`平素`, `御礼`, `案件`, `協業`) successfully disambiguate `記者` $\rightarrow$ `貴社`.

---

## 🔍 4. Qualitative Examples: Before vs. After Context

```carousel
### Example 1: Homophone Disambiguation (Business vs. Press)
- **Preceding Context**: `"平素は格別のご高配を賜り厚く御礼申し上げます。今後の協業案件に関しまして、"`
- **Target Word**: `貴社` (きしゃ)
- **Speech Utterance**: `"貴社の益々のご発展を心よりお祈り申し上げます。"`
- **Baseline Output**: `"記者のますますのご発展を心よりお祈り申し上げます"` ❌
- **Hybrid (No Context)**: `"記者のますますのご発展を心よりお祈り申し上げます"` ❌
- **Context-Aware**: `"貴社のますますのご発展を心よりお祈り申し上げます"` ✅
<!-- slide -->
### Example 2: Person Name Kanji Variant Resolution
- **Preceding Context**: `"先日のミーティングについてご連絡いたします。今回ご紹介いただいた賢一さんの件ですが、"`
- **Target Word**: `賢一` (けんいち)
- **Speech Utterance**: `"賢一さんの助言のおかげで問題が無事に解決しました。"`
- **Baseline Output**: `"健一さんの助言のおかげで問題が無事に解決しました"` ❌
- **Hybrid (No Context)**: `"健一さんの助言のおかげで問題が無事に解決しました"` ❌
- **Context-Aware**: `"賢一さんの助言のおかげで問題が無事に解決しました"` ✅
<!-- slide -->
### Example 3: Technical Term Transliteration Disambiguation
- **Preceding Context**: `"モバイルアプリのバックエンド連携を改善しています。特にGraphQLのスキーマ定義について、"`
- **Target Word**: `GraphQL` (ぐらふきゅーえる)
- **Speech Utterance**: `"柔軟なデータ取得のためにGraphQLのAPIスキーマを設計しました。"`
- **Baseline Output**: `"柔軟なデータ取得のためにグラフ九LのAPIスキーマを設計しました"` ❌
- **Hybrid (No Context)**: `"柔軟なデータ取得のためにグラフ九LのAPIスキーマを設計しました"` ❌
- **Context-Aware**: `"柔軟なデータ取得のためにGraphQLのAPIスキーマを設計しました"` ✅
<!-- slide -->
### Example 4: Smart Latin Word Boundary Spacing
- **Preceding Context**: `"Android"`
- **Target Word**: `JotInput`
- **Speech Utterance**: `"JotInput"`
- **Without Context**: `"AndroidJotInput"` (concatenated without spacing) ❌
- **Context-Aware**: `"Android JotInput"` (smart space inserted automatically) ✅
```

---

## 5. 🚀 Conclusion

Incorporating text box context directly into speech recognition decoding and post-processing provides a **decisive leap in accuracy**:
- Elevates overall recognition recall to **84.5%**.
- Solves the single most persistent failure mode of Japanese ASR: distinguishing between homophonic Kanji names and homophones.
- Adds virtually **zero latency** (~0.014 ms), making it an ideal zero-cost optimization for on-device voice keyboards.
