---
name: myasr-eval
description: >-
  Comprehensive guide, execution commands, and dataset taxonomy for evaluating JotInput's (Japanese On-device Text Input)
  speech recognition (ASR), Kana-Kanji conversion, SLM candidate re-ranking, spoken voice
  punctuation, streaming prefix freeze, and English QWERTY neural swipe typing models.
---

# JotInput Evaluation Suites & Benchmarking Guide

This skill documents all evaluation suites, datasets, metrics, and command-line execution procedures available in the JotInput repository. Use this whenever designing, running, or verifying performance and regression tests across any subsystem.

--------------------------------------------------------------------------------

## 📊 Overview of Evaluation Categories

| Subsystem | Evaluation Focus | Primary Datasets & Tests | Metrics | Execution Tool |
| :--- | :--- | :--- | :--- | :--- |
| **1. Japanese ASR & Custom Dictionary** | Acoustic decoding, hotword biasing, phonetic substitution | `eval/dataset.json` (1,000 cases), 20-case custom dict matrix | TWRR, CER, FPR, RTF | Python (`eval/evaluate_1000.py`) |
| **2. Spoken Punctuation & Boundary Input** | Spoken punctuation commands (`、`, `。`, `！`, 改行) vs protected homophones | [`JapanesePunctuationEvaluationTest.kt`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) (67+ cases) | 100% Pass, False-Positive Rate | Gradle JVM UnitTest |
| **3. Kana-Kanji Conversion & Bigram Costs** | Mozc Viterbi, Bigram connection matrix, morpheme splitting | `eval/connection_matrix_eval_results.json`, [`ConnectionMatrixTest.kt`](app/src/test/java/com/example/myasr/ConnectionMatrixTest.kt) | Top-1 / Top-3 Accuracy, Boundary Acc | Python & JVM UnitTest |
| **4. On-Device SLM Candidate Re-ranking** | Disambiguating homophones, context-free evaluation, Gemini Nano vs Local SLM | `tools/eval_1000_cases.tsv`, `tools/eval_500_natural_empty.tsv`, `tools/eval_dataset_10000.jsonl` | Top-1, Top-3, MRR, Latency, Energy (mJ) | C++ CLI & Python |
| **5. Multimodal Voice & Touch Arbitration** | State machine transitions, voice streaming while typing, Undo/Delete/Space/Punctuation | [`VoiceInputArbitratorTest.kt`](app/src/test/java/com/example/myasr/VoiceInputArbitratorTest.kt), [`FutoSwipeIntegrationTest.kt`](app/src/test/java/com/example/myasr/FutoSwipeIntegrationTest.kt) | 100% Pass, Zero Inconsistency | Gradle JVM UnitTest |
| **6. Streaming Prefix Freeze & Filler Removal** | Live speech partial commit, flicker suppression, filler filtering | [`StreamingPrefixFreezeEvaluationTest.kt`](app/src/test/java/com/example/myasr/StreamingPrefixFreezeEvaluationTest.kt), 120-case filler suite | Edit Distance, Revision Rate, Latency | Gradle JVM UnitTest |
| **7. English QWERTY Neural Swipe Typing** | 1D-CNN/DFSMN spatial CTC beam search + ContextLM re-ranking | `eval/futo_swipe/swipe_eval_1000.jsonl` (1,000 cases) | Top-1 / Top-3 / Top-5 Acc, NN Latency (ms) | Python & Native C++ |

--------------------------------------------------------------------------------

## 1. Japanese ASR & Custom Dictionary Biasing

### Purpose
Evaluates ReazonSpeech Zipformer int8 acoustic recognition accuracy, in-beam hotword biasing, and dictionary post-processing homophone substitutions.

### Key Datasets & Scripts
- **Dataset**: `eval/dataset.json` (1,000 synthetic & real tricky Japanese audio phrases) and `eval/dataset_with_context.json`.
- **Audio Generator**: `eval/generate_audio.py`
- **Evaluation Runner**: `eval/evaluate_1000.py`

### How to Run
```bash
python3 eval/evaluate_1000.py \
  --model-dir tools/models/reazonspeech \
  --dataset eval/dataset.json \
  --output eval/evaluation_results_1000.json
```

### Reference Documentation
- [`docs/evaluation_report_1000.md`](docs/evaluation_report_1000.md)
- [`docs/dictionary_improvement_plan.md`](docs/dictionary_improvement_plan.md)

--------------------------------------------------------------------------------

## 2. Spoken Punctuation & Sentence Boundary Non-Regression Suite

### Mandatory Quality Rule (AGENTS.md / GEMINI.md)
Every build or PR touching ASR, VAD, punctuation, or IME MUST pass 100% on unit tests. Never weaken vocabulary protection rules for spoken commands.

### Test Matrix
- **Test Class**: [`app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) (67+ deterministic tests)
- **Protected Words**:
  - `てん` (点): 天気, 天然, 満点, 交差点, 重点, 10点, 頂点, 減点, 起点
  - `まる` (丸): まるで, 一丸, 日の丸, まるまる, 丸ごと, 丸見え
  - `から` (接続詞 vs 格助詞): ここから, 駅から, ですから, だから, これから
  - `ます` / `です`: ますます, すでに, ですが

### How to Run
```bash
./gradlew testDebugUnitTest --tests "com.example.myasr.JapanesePunctuationEvaluationTest"
```

### Reference Documentation
- [`docs/punctuation_and_voice_boundary_evaluation_criteria.md`](docs/punctuation_and_voice_boundary_evaluation_criteria.md)
- [`docs/voice_punctuation_and_sentence_boundary_input.md`](docs/voice_punctuation_and_sentence_boundary_input.md)

--------------------------------------------------------------------------------

## 3. Kana-Kanji Conversion & Bigram Connection Matrix Benchmark

### Purpose
Benchmarks Mozc-derived Viterbi candidate ordering against full $2672 \times 2672$ bigram connection cost matrices and mmap dictionaries.

### Key Datasets & Scripts
- **Runner**: `eval/evaluate_connection_matrix.py`
- **Output Report**: `eval/connection_matrix_eval_results.json`
- **JVM Unit Test**: [`app/src/test/java/com/example/myasr/ConnectionMatrixTest.kt`](app/src/test/java/com/example/myasr/ConnectionMatrixTest.kt)

### How to Run
```bash
# Python benchmark comparing Viterbi with/without connection matrix:
python3 eval/evaluate_connection_matrix.py

# Android unit test:
./gradlew testDebugUnitTest --tests "com.example.myasr.ConnectionMatrixTest"
```

### Reference Documentation
- [`docs/connection_matrix_evaluation_report.md`](docs/connection_matrix_evaluation_report.md)
- [`docs/system_design_gboard_style_mmap_lexicon.md`](docs/system_design_gboard_style_mmap_lexicon.md)

--------------------------------------------------------------------------------

## 4. On-Device SLM Candidate Re-ranking Evaluation

### Purpose
Evaluates homophone semantic disambiguation across Qwen2.5-0.5B, Qwen2.5-1.5B, and Gemini Nano (AICore).

### Standard Datasets
1. **`tools/eval_1000_cases.tsv`**: 1,000 realistic Japanese sentences with preceding context, challenging homophones (`異動` vs `移動`, `確信` vs `核心`, `対象` vs `対照`).
2. **`tools/eval_500_natural_empty.tsv`**: 500 isolated phrase inputs with zero preceding context (`""`), testing Context-Free fallback stability (`<s>`).
3. **`tools/eval_dataset_10000.jsonl`**: 10,000-sample large-scale scaled corpus.

### C++ High-Speed Evaluation Tools
- **`tools/slm_scorer_cli` / `tools/eval_context_free_slm`**:
  Direct native evaluation of KV cache reuse, token-0 pruning, and joint NLL scoring.
- **`tools/cv_optimize_alpha.cc`**: 5-fold cross-validation tool for parameter $\alpha$ (balance between dictionary cost and SLM log-likelihood).

### How to Run
```bash
# Python evaluation of SLM mandatory rerank:
python3 eval/evaluate_slm_mandatory_rerank.py

# Python evaluation of Local SLM vs Gemini Nano:
python3 eval/evaluate_slm_vs_nano.py

# Evaluate Qwen 1.5B:
python3 eval/evaluate_qwen1_5b.py

# Native C++ Context-Free SLM benchmark:
g++ -O3 tools/eval_context_free_slm.cc -o tools/eval_context_free_slm
./tools/eval_context_free_slm tools/eval_500_natural_empty.tsv
```

### Reference Documentation
- [`docs/rerank_nano2_kekka_sakujo_debug_and_ideas.md`](docs/rerank_nano2_kekka_sakujo_debug_and_ideas.md)
- [`docs/context_free_slm_reranking_evaluation.md`](docs/context_free_slm_reranking_evaluation.md)
- [`docs/slm_battery_and_power_consumption_report.md`](docs/slm_battery_and_power_consumption_report.md)

--------------------------------------------------------------------------------

## 5. Multimodal Voice & Touch Arbitration (JVM Deterministic Suite)

### Purpose
Verifies race-condition-free interaction between continuous streaming voice, VAD pause chunks, flick/tap typing, backspace, undo, space, punctuation absorption, and cursor movements.

### Test Classes
- [`app/src/test/java/com/example/myasr/VoiceTouchInputArbitratorTest.kt`](app/src/test/java/com/example/myasr/VoiceTouchInputArbitratorTest.kt) (covers all 24 corner cases in ADR-012/ADR-013)
- [`app/src/test/java/com/example/myasr/FutoSwipeIntegrationTest.kt`](app/src/test/java/com/example/myasr/FutoSwipeIntegrationTest.kt) (covers swipe word commit, consecutive swipe inter-word spacing, single-tap entire-word deletion on backspace, alternative candidate replacement)

### How to Run
```bash
./gradlew testDebugUnitTest --tests "com.example.myasr.Voice*Test"
./gradlew testDebugUnitTest --tests "com.example.myasr.FutoSwipeIntegrationTest"
```

### Reference Documentation
- [`docs/voice_and_touch_interaction_corner_cases.md`](docs/voice_and_touch_interaction_corner_cases.md)

--------------------------------------------------------------------------------

## 6. Streaming Prefix Freeze & Latency Benchmarks

### Purpose
Evaluates partial commit strategies (Freeze) to eliminate visual flicker during real-time streaming speech recognition.

### Test Classes & Scripts
- **Test Class**: [`app/src/test/java/com/example/myasr/StreamingPrefixFreezeEvaluationTest.kt`](app/src/test/java/com/example/myasr/StreamingPrefixFreezeEvaluationTest.kt)
- **Latency Benchmark**: [`app/src/test/java/com/example/myasr/ImeLatencyAndAsyncTest.kt`](app/src/test/java/com/example/myasr/ImeLatencyAndAsyncTest.kt)

### How to Run
```bash
./gradlew testDebugUnitTest --tests "com.example.myasr.StreamingPrefixFreezeEvaluationTest"
./gradlew testDebugUnitTest --tests "com.example.myasr.ImeLatencyAndAsyncTest"
```

### Reference Documentation
- [`docs/streaming_prefix_freeze_solutions_and_evaluation.md`](docs/streaming_prefix_freeze_solutions_and_evaluation.md)

--------------------------------------------------------------------------------

## 7. English QWERTY Neural Swipe Typing Benchmark

### Purpose
Evaluates FUTO Keyboard's 1D-CNN/DFSMN spatial trajectory model and character-level ContextLM on real English swipe paths.

### Dataset & Scripts
- **Dataset**: `eval/futo_swipe/swipe_eval_1000.jsonl` (1,000 real touch trajectories with context)
- **Pipeline Implementation**: `tools/futo_swipe/futo_swipe_pipeline.py`
- **Evaluation Runner**: `eval/futo_swipe/run_futo_swipe_eval.py`
- **Results Summary**: `eval/futo_swipe/swipe_eval_results_1000.json`

### Benchmark Results
- **Spatial Baseline**: Top-1: 89.0%, Top-3: 92.4%, NN Latency: 3.08ms
- **With ContextLM**: Top-1: 90.3% (+1.3% net improvement), Top-3: 93.8%, NN Latency: 4.19ms

### How to Run
```bash
python3 eval/futo_swipe/run_futo_swipe_eval.py \
  --eval-file eval/futo_swipe/swipe_eval_1000.jsonl \
  --model-dir tools/models/futo_swipe \
  --limit 1000
```

### Reference Documentation
- [`docs/futo_swipe_phase1_evaluation_report.md`](docs/futo_swipe_phase1_evaluation_report.md)
- [`docs/futo_swipe_qwerty_design.md`](docs/futo_swipe_qwerty_design.md)

--------------------------------------------------------------------------------

## 🛠️ Quick Reference: Running the Entire Test Suite

To run all deterministic unit tests across all evaluation axes:
```bash
./gradlew testDebugUnitTest
```

To run both connected target deployment checks (ChromeOS ARCVM + Physical Pixel 10 Pro):
```bash
./deploy.sh
```
