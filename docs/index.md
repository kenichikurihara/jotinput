# JotInput Technical Documentation

Welcome to the technical documentation for **JotInput**, a lightweight, fully offline on-device Japanese Automatic Speech Recognition (ASR) Android application.

---

## 📚 Table of Contents

1. [System Architecture](docs/architecture.md)
   - High-level pipeline and data flow
   - Audio recording subsystem ([`AudioRecorder`](app/src/main/java/com/example/myasr/AudioRecorder.kt))
   - ASR engine abstraction ([`AsrManager`](app/src/main/java/com/example/myasr/AsrManager.kt))
   - UI and interaction lifecycle ([`MainActivity`](app/src/main/java/com/example/myasr/MainActivity.kt))
   - Model download and storage fallback ([`ModelDownloader`](app/src/main/java/com/example/myasr/ModelDownloader.kt))

2. [ReazonSpeech Model & On-Device Optimization](docs/reazonspeech_model.md)
   - Background on ReazonSpeech corpus & model choices
   - Zipformer transducer architecture (`reazonspeech-k2-v2`)
   - Quantization strategy (int8 encoder, float32 decoder & joiner)
   - Memory footprint, CPU utilization, and RTF (Real-Time Factor)

3. [Setup, Build, and Deployment Guide](docs/setup_and_development.md)
   - Prerequisites (Java 21, Android SDK, Gradle)
   - Model asset preparation (`download_model.sh`)
   - Compiling the debug APK
   - Testing on ARCVM (ChromeOS Android container) and physical devices
   - Useful ADB commands & troubleshooting

4. [Custom Dictionary Solutions (Japanese ASR)](docs/dictionary_solutions.md)
   - Dual-key specification: `(Word, Reading)` / `(表記, よみ)`
   - Dual-channel reinforcement: In-beam acoustic biasing + phonetic homophone substitution
   - Automated Hiragana / Katakana expansion
   - User data model & UX design
   - Phased implementation roadmap

5. [Custom Dictionary Implementation Plan](docs/dictionary_implementation_plan.md)
   - Detailed component design & data flow
   - Repository, HotwordGenerator, and PostProcessor implementation steps
   - Android UI & verification guide

6. [Custom Dictionary Improvement & Evaluation Plan](docs/dictionary_improvement_plan.md)
   - Root cause analysis with ARCVM logcat post-mortem evidence
   - Tokenization space-separation fix (`cjkchar` format)
   - Quantitative evaluation plan (TWRR, CER, FPR, RTF) with 20-case test matrix
   - Review checkpoints before implementation

7. [1,000-Example Tricky Evaluation Report & Cross-Validation](docs/evaluation_report_1000.md)
   - Comprehensive benchmark on 1,000 tricky Japanese sentences
   - 5-fold stratified cross-validation for hyperparameter tuning ($\alpha$)
   - Before/after comparison: Baseline, Flawed Hotwords, Fixed Hotwords, and Hybrid Pipeline
   - Precision, Recall, Accuracy, F1, FPR, and RTF metrics
   - Detailed breakdown across 250 People Names, 250 Homophones, 150 Place Names, 200 Tech Terms, and 150 Negatives
   - Custom dictionary persistence guarantee across app updates

8. [Android Voice Keyboard (IME) Development Plan & Specification](docs/keyboard_development_plan.md)
   - Real Android Input Method Editor (`InputMethodService`) design
   - Minimalist keyboard UI: Centered toggle button (話す / 完了), `DEL`, `RETURN`, `、`, `。`, and `🌐`
   - Shared ASR model container ([`AsrApp`](app/src/main/java/com/example/myasr/AsrApp.kt)) for zero-overhead memory reuse
   - `InputConnection` event flow and lifecycle management
   - Permission fallback flow for background IME services

9. [Architectural Decisions & Technical Findings Record](docs/findings_and_decisions.md)
   - Comprehensive record of ADRs (ADR-001 through ADR-007)
   - Critical technical post-mortems (Sherpa-ONNX `cjkchar` whitespace bug, IME permission delegation, ARCVM hardware keyboard suppression)
   - 1,000-sentence cross-validation results and hyperparameter tuning findings
   - Dual-target deployment policies and Wi-Fi debugging characteristics
   - Viterbi lattice multi-bunsetsu search, local mock gap post-mortem, and colloquial verb contraction trap elimination (Findings 17–19)

10. [Context-Aware Recognition & Keyboard Enhancement Plan](docs/context_aware_recognition_plan.md)
    - Architecture for leveraging `InputConnection.getTextBeforeCursor(500, 0)`
    - Semantic homophone disambiguation and rare Kanji variant resolution
    - Smart Latin boundary spacing and clause punctuation
    - Dedicated 1,000-sentence context generation & evaluation plan

11. [Context-Aware Speech Recognition Evaluation Report](docs/context_aware_evaluation_report.md)
    - Comparative benchmark on 1,000 tricky Japanese sentences (Baseline vs. Hotwords vs. Hybrid vs. Context-Aware)
    - Overall recall increase to **84.5%** (+30.5% over Baseline, +3.9% over Hybrid)
    - People names jump to **76.0%** (+9.2% gain), homophones reach **96.0%**, tech terms reach **77.5%**
    - Zero false positives on negative controls (FPR: 0.0%) and zero latency overhead (0.014ms/utterance)

12. [Google Play Store Publishing Guide](docs/play_store_publishing_guide.md)
    - Comprehensive end-to-end publishing lifecycle and prerequisites
    - Personal vs. Organization accounts, D-U-N-S verification & mandatory 14-day closed testing
    - Technical requirements (App Bundle .aab, 64-bit native libs, Play App Signing, R8)
    - Policy compliance (Privacy policy, Data safety, Account deletion, Foreground services)
    - Store asset specifications, release track progression, and Fastlane / GitHub Actions automation

13. [Gboard-Inspired Flick-Only & English QWERTY Keyboard Plan](docs/qwerty_and_gboard_flick_plan.md)
    - Empirical analysis of Gboard's Flick-Only Japanese layout and English QWERTY layout on Pixel 10 Pro
    - Optional QWERTY top number row (`1–0`) toggle and live `SharedPreferences` synchronization
    - Directional modifier flicks (`゛`, `゜`, `大⇔小`), 5-direction punctuation key (`、。？！…`), Undo (`↶`), and cursor keys (`◀`, `▶`)
    - Unified circular flick popup and single-key QWERTY callout via zero-inset `PopupWindow`

14. [Mozc On-Device SLM/LLM Integration & Evaluation Plan](docs/mozc_ondevice_llm_integration_plan.md)
    - Resolution of Mozc local-minima / bunsetsu boundary limits (e.g., "ことなります" vs. "異なります")
    - Hybrid Viterbi N-best + On-device SLM conditional log-likelihood re-ranking architecture
    - Lightweight model selection (Qwen2.5-0.5B, Qwen2.5-1.5B, Llama-3.2-1B, Zenzai-style Transformers)
    - 10,000-sample balanced evaluation dataset design, data acquisition pipeline, and Win/Loss metrics

15. [Phase 1 PoC Evaluation Report (On-Device SLM Re-ranking)](docs/phase1_poc_evaluation_report.md)
    - Empirical verification of Qwen2.5-0.5B (GGUF INT4) on Intel Core i5 CPU
    - Demonstration of positional bias in multiple-choice prompts vs. mathematical superiority of token conditional log-likelihood
    - Decisive resolution of user's issue ("Mozcは私の直感と挙動が" + "ことなります" -> "異なります", ~40x probability over hiragana)
    - Quantitative results: +62.0% net Top-1 gain (31 Wins, 0 Losses) with sub-100ms latency

16. [Phase 2 Scaled Evaluation Report (1,000 Stratified Samples)](docs/phase2_scale_evaluation_report.md)
    - Automated pipeline generating a 10,000-sample balanced dataset from KWDLC, Tatoeba, and homophone contrast pairs
    - Large-scale statistical benchmark across 1,000 stratified samples (434 Wins, 0 Losses, Win/Loss ratio 434:1)
    - +43.40% net Top-1 gain across web articles, conversational dialogues, and homophone matrices
    - Sub-50ms single-query latency validation and readiness for Phase 3 C++ in-process engine integration

17. [Phase 3 C++ Engine Integration Report (Native In-Process Re-ranking)](docs/phase3_cpp_integration_report.md)
    - Direct C++ implementation of `LlmReranker` and `LlmRewriter` adhering to Mozc's `RewriterInterface`
    - Integration of `libllama` (`llama.cpp`) with fast mmap model loading (< 210 ms)
    - Combined cost formula scaling: $\text{Score} = \alpha \cdot (\text{Cost}/1000) + (1-\alpha) \cdot (-\text{LogProb})$
    - 100% pass rate (7/7) on end-to-end native test suite, promoting "異なります", "移動", "交渉", "記者", "貴社" in < 100 ms

18. [Phase 4 Plan: Production-Grade KV Cache Optimization, Multi-Token Disambiguation & Non-Blocking Architecture](docs/phase4_optimization_and_production_plan.md)
    - Hierarchical prefix KV cache engine with `llama_memory_seq_rm()` rollback for sub-20ms keystrokes
    - Two-stage adaptive disambiguation: fast-path (0 ms extra) vs. multi-token tie-break (16 ms for `決裁` vs `決済`)
    - Context prefix hashing (FNV-1a) and generation counters for asynchronous, non-blocking UI responsiveness
    - Production hardening against cursor jumps, buffer overflow, and multi-byte boundary errors

19. [Phase 4 Implementation Report: Production KV-Cache Reuse & Sub-Millisecond IME Re-ranking](docs/phase4_implementation_report.md)
    - Empirical keystroke latency reduction from ~70-100 ms down to **0.80 ms** (>85x speedup)
    - 64-bit FNV-1a context invalidation and `llama_memory_seq_rm()` speculative token rollback
    - 100% pass rate (4/4) on production test suite (motivating issue, 状況 vs 上京, live typing keystroke simulation, 交渉)

20. [On-Device SLM Re-ranking Android Integration & Live Verification Report](docs/ondevice_slm_android_integration_report.md)
    - Native Android NDK C++ JNI integration (`libmyasr_llm.so` linking `llama.cpp` statically)
    - End-to-end Kotlin IME pipeline integration (`LlmReranker.kt` + `KanaKanjiEngine.kt` + `VoiceInputMethodService.kt`)
    - Live ARCVM logcat verification: `ことなります` -> `異なります` (Top-1) and `じょうきょう` -> `状況` (Top-1)
    - Preservation of Custom Dictionary Contract and zero-cost fallback when model is absent

21. [IME Keystroke Latency Offline Evaluation & Performance Architecture](docs/ime_latency_and_offline_eval_plan.md)
    - Two-stage asynchronous candidate pipeline (instant synchronous fast-path $< 1\text{ ms}$ + async coroutine SLM re-ranking)
    - Stale keystroke coroutine job cancellation during rapid typing
    - Candidate pool pruning to top $K=8$ and multi-token evaluation bounds
    - Standalone native Linux keystroke benchmark harness ([`tools/bench_ime_latency.cc`](tools/bench_ime_latency.cc))
    - Elimination of UI thread freezing and ANRs on physical phone (Pixel 10 Pro)

22. [Long Phrase & Multi-Bunsetsu Conversion Headroom Report](docs/long_phrase_conversion_headroom_report.md)
    - Headroom analysis across 3,672 long Japanese phrases ($\ge 8$ kana)
    - Empirical quality metrics: Current 33.1% Top-1 Precision vs **92.4% SLM Potential** (+59.3% headroom)
    - Taxonomy of 4 failure modes (boundary segmentation, auxiliary verb corruption, honorific mis-splitting, token-0 collision)
    - Concrete mathematical proofs with $+19.5$ to $+33.6$ nats likelihood advantage using on-device Qwen2.5-0.5B

23. [Evaluation of 5 Improvement Ideas for Long Phrase Conversion](docs/long_phrase_improvement_ideas_and_evaluation.md)
    - Detailed breakdown of 5 proposals: Trie N-Best Viterbi, Full-Sequence SLM, Colloquial Transition Filters, Adaptive Chunking, and Speculative Autocomplete
    - Empirical comparative matrix on 500 continuous long phrases (1.0% Baseline $\to$ 20.2% Viterbi+Penalty $\to$ 92.4% Hybrid)
    - Implementation of In-Memory Viterbi Lattice Beam Search & Colloquial Transition Penalties in [`KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/KanaKanjiEngine.kt)
    - Sub-millisecond execution budget verification via [`LongPhraseConversionTest.kt`](app/src/test/java/com/example/myasr/LongPhraseConversionTest.kt)

24. [Context-Free SLM Candidate Re-ranking Evaluation Report](docs/context_free_slm_reranking_evaluation.md)
    - Quality and latency evaluation of triggering SLM re-ranking when preceding context is empty (`textBeforeCursor.isBlank()`)
    - Empirical comparison: Baseline Trie (86.7% Top-1, 0.0% Bizarre) vs. Context-Free SLM (30.0%–35.0% Top-1, 30.0%–35.0% Bizarre)
    - Discovery of unigram prior degeneration where standalone SLM prefers internet slang (`作業厨デスので`) over standard phrases
    - Latency analysis: 20–34 ms warm / 50–280 ms cold vs. 0.2 ms Trie fast-path
    - Architectural justification for gating SLM re-ranking exclusively on non-empty context ($|C| \ge 1$)

25. [Context-Free SLM Re-ranking Implementation & Settings](docs/findings_and_decisions.md#finding-21-context-free-slm-re-ranking-implementation--toggle-settings)
    - Scale evaluation finding: Context-Free SLM (26.10%) improves over Mozc Baseline (12.60%) by +13.50% on 1,000 cases
    - On-device implementation with BOS (`<s>`) KV caching in `libmyasr_llm.so`
    - In-app toggle switch (`switchSlmContextFree`) with persistence in `ime_preferences.xml`
    - Seamless live switching between context-only mode and full context-free mode

26. [SLM Alpha Optimization & 5-Fold Cross-Validation Report](docs/slm_alpha_cross_validation_report.md)
    - Systematic 5-fold cross-validation on 1,000 samples across $\alpha \in [0.00, 1.00]$
    - Proven convergence: All 5 folds independently select optimal $\alpha^* = 0.00$
    - Out-of-Fold (OOF) accuracy reaches 66.80% (Context-Aware) and 51.10% (Context-Free)
    - Complete sensitivity curve and production regularization recommendation ($\alpha \approx 0.05 \sim 0.10$)

27. [Exclusion of All-Hiragana Candidates from Ranking & Display](docs/findings_and_decisions.md#finding-23-exclusion-of-all-hiragana-candidates-from-conversion-ranking-and-display)
    - Complete elimination of all-hiragana candidates from candidate generation, SLM re-ranking, and UI candidate bar
    - Rationale: composing text is already rendered as raw Hiragana on screen; pressing Enter/Return directly commits it without conversion
    - Defense-in-depth across 4 layers: `JapaneseKanaUtils.isAllHiragana()`, `KanaKanjiEngine` candidate & lattice filtering, `LlmReranker` pre-computation filtering, and `CandidateStripView` view presentation
    - Verification: Unit test suite green (23 tasks); dual-target deployed and verified live on Pixel 10 Pro and ARCVM

28. [Zero-Query Next-Phrase Continuation Prediction Report & Ideas Evaluation](docs/next_phrase_prediction_ideas_and_evaluation.md)
    - Brainstorming and iterative evaluation across 7 architectural perspectives: User Bigram History, Canonical Collocations, Particle Continuations, On-Device SLM Generation, Contextual/Temporal Heuristics, Cloud LLM, and Multi-Tier Hybrid
    - Formal adoption and implementation of the **Tiered Hybrid Architecture** in [`NextPhrasePredictor.kt`](app/src/main/java/com/example/myasr/NextPhrasePredictor.kt)
    - Sub-millisecond execution ($< 20\text{ µs}$) with persistent learning, standard conversational anchors, and particle suggestions
    - Full IME integration in [`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) with unit test coverage

29. [Voice Punctuation Restoration & Continuous Sentence Boundary Input](docs/voice_punctuation_and_sentence_boundary_input.md)
    - Implementation of [`JapanesePunctuationEngine.kt`](app/src/main/java/com/example/myasr/JapanesePunctuationEngine.kt) for spoken commands (`まる`, `てん`, `かいぎょう`) and automatic sentence-ending periods (`です`, `ます`).
    - Vocabulary protection masking (e.g., 天気, 天然, 満点, 交差点, 重点, まるで, 一丸, 日の丸).
    - Micro-pause flushing (200ms) and continuous pacing flush (2400ms) for pause-free boundary commits.

30. [Punctuation & Voice Boundary Evaluation Criteria & Benchmark Suite](docs/punctuation_and_voice_boundary_evaluation_criteria.md)
    - Comprehensive benchmark specifications and criteria across 7 categories and 67 unit test cases ([`JapanesePunctuationEvaluationTest.kt`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt)).

31. [Streaming Voice Input Architecture, Continuous In-place Input & Dynamic Prefix Revision](docs/streaming_voice_input_architecture_and_deliberation.md)
    - In-depth architectural self-deliberation on real-time continuous streaming vs. legacy pause-flush input.
    - Continuous as-you-speak in-place editor rendering via active live composing spans without waiting for sentence boundaries (periods).
    - Dynamic prefix revision: automatically rewriting earlier words (prefixes) as right-context words arrive (e.g. "移動" $\to$ "異動願い", "です。" $\to$ "ですから、").
    - Horizon-based dynamic sliding window: keeps active revision horizon in `setComposingText()` while progressively freezing older stabilized sentences into `commitText()`.
    - Strict mutual exclusion: automatically disabling pause-based chunking (`microPauseDurationMs`, `silenceDurationMs`) when streaming mode is enabled.
    - Comprehensive evaluation benchmark design covering continuous in-place input, prefix revision, multi-sentence streaming, and regression guarantees.

32. [Mandatory SLM Reranking with Bounded Timeout: Design & Latency Evaluation](docs/slm_mandatory_rerank_design_and_latency.md)
    - Elimination of Fast-Path candidate flickering and Space-commit bypass of SLM via mandatory bounded timeout reranking (Default: ON).
    - Strict anti-anecdotal tuning policy prohibiting ad-hoc unigram cost tweaking.
    - Analysis of Mozc community corpus CRF optimization ($POS \times POS$ connection matrix + negative log-likelihoods).
    - Comprehensive mobile latency evaluation: 100ms fail-safe budget selection vs 22-38ms typical SLM inference time.

33. [Mozc 2D Connection Cost Matrix (`connection_single_column.txt`) Evaluation Report](docs/connection_matrix_evaluation_report.md)
    - Comparative empirical evaluation with vs without Mozc's 2D connection cost matrix ($2672 \times 2672$, 7.14M transitions).
    - Mathematical transition mechanics: BOS $\to$ bound noun penalty ($+5,841$) eliminating `生みのまわりに` in favor of `海の周りに`.
    - 5-column POS dictionary schema enrichment (`base_dictionary.tsv` (generated under `tools/mozc_dict/`, downloaded on demand; not bundled in the APK)) derived systematically from Mozc OSS CRF corpus.
    - Quantified gains: Top-1 (+2.0%), Top-3 (+2.0%), MRR (+0.024) on conversational suite, and +1.47% pass rate (+9 sentences) on official Mozc OSS benchmark.
    - Zero-overhead virtual memory mapping with only $+0.074\text{ms}$ latency impact.

34. [Mandatory SLM Re-ranking Performance & Disambiguation Evaluation Report](docs/slm_mandatory_rerank_evaluation_report.md)
    - Comprehensive empirical comparison of Mandatory SLM Re-ranking vs Mozc Connection Matrix baseline.
    - Context-Aware Homophone Disambiguation (50 items): Top-1 accuracy leaps from **42.0% $\to$ 80.0% (+38.0% absolute gain)**; MRR increases from **0.677 $\to$ 0.858 (+0.181)**.
    - Mathematical limitation of 2D connection matrix: identical-POS homophones (`移動` [1841] vs `異動` [1841], `回答` [1841] vs `解答` [1841]) have 0 delta cost under bigram grammar alone.
    - Discovery and fix for multi-token speculative scoring length penalty bias via per-token length normalization.
    - Alpha hyperparameter sensitivity analysis across $\alpha \in [0.00, 1.00]$ and mobile latency evaluation ($\le 108\text{ms}$ vs $500\text{ms}$ timeout).

35. [Debug Display Specification & Multi-Target Verification Report](docs/debug_display_specification_and_verification.md)
    - Specification and verification report for the "デバッグ表示" (Debug Display) toggle.
    - Non-clickable dummy `[SLM]` chip rendering in [`CandidateStripView`](app/src/main/java/com/example/myasr/CandidateStripView.kt) when on-device SLM re-ranking is applied.
    - Zero-regression candidate list isolation and dual-target verification on Pixel 10 Pro and ARCVM.

36. [On-Device Hardware Acceleration Analysis & Optimization Report](docs/ondevice_hardware_acceleration_analysis_and_optimization.md)
    - Comprehensive audit of on-device hardware acceleration (GPU/NPU/CPU SIMD) for both ASR (Sherpa-ONNX/Zipformer) and SLM (llama.cpp/Qwen2.5-0.5B).
    - Identification of missing DotProd (`sdot`/`udot`) and FP16 vector instructions in arm64 native builds due to unconfigured NDK arch flags.
    - Explicit enablement of `armv8.2-a+fp16+dotprod` and `-O3` yielding 1,072 SIMD DotProd instructions, plus AVX2/FMA for x86_64 and dynamic thread scaling (2-4 cores) for ASR.
    - Deep dive on why NNAPI (deprecated in Android 15) and mobile GPU offload (dispatch latency > compute time for short sequence log-likelihoods) are suboptimal compared to optimized CPU SIMD execution.

37. [Qwen2.5-1.5B Model Selection & Quantitative Evaluation Report](docs/qwen_1_5b_model_selection_and_evaluation_report.md)
    - Specification and UI design for selecting the next larger SLM (`Qwen2.5-1.5B`, 1.1GB) alongside `Qwen2.5-0.5B` (469MB).
    - Empirical evaluation across the 100-item evaluation benchmark (Suite 1: Context-Aware Homophones; Suite 2: Collocations).
    - Quantitative proof of +8.0% boost in Homophone Top-1 accuracy (80.0% $\rightarrow$ 88.0%) and Top-3 accuracy (90.0% $\rightarrow$ 94.0%).
    - Seamless dynamic model swapping, dual-target verification on Pixel 10 Pro and ARCVM, and latency trade-off analysis.

38. [Predictive Prefix Matching Search Logic](docs/predictive_prefix_matching_logic.md)
    - Comprehensive investigation into predictive candidate retrieval (e.g. `「ぱ」` $\rightarrow$ `「パン」`).
    - Dual pipeline analysis: SQLite multi-dictionary B-Tree range query and In-Memory Trie depth-first prefix expansion (`collectPrefixWords`).

39. [Voicing & Semi-Voicing Fuzzy Prefix Search Design & Evaluation](docs/voicing_fuzzy_prefix_search_design_and_evaluation.md)
    - In-depth design discussions covering Branching Trie Search, O(1) phonotactic pruning, asymmetric voicing mapping, and BFS-based predictive expansion.
    - Resolves missing candidate issues for unvoiced inputs (e.g. `「かいき」` $\rightarrow$ `「会議」`, `「すーは」` $\rightarrow$ `「スーパー」`).
    - Quantitative benchmark across 20-item evaluation matrix: 100% pass rate, sub-0.2ms latency ($0.188\text{ms}$), and 100% non-regression on voice punctuation.

40. [Mozc UT 辞書ベストプラクティス調査・辞書選定報告](docs/mozc_ut_dictionary_best_practices.md)
    - MozcコミュニティにおけるUT辞書（`merge-ut-dictionaries`）の更新活発度ダブルチェック結果（2026年11月最新更新実績）。
    - 採用辞書（`SudachiDict`, `jawiki`, `personal-names`, `place-names`）と不採用辞書（休眠状態の `NEologd`、古典辞書）の明確な根拠。
    - コミュニティの標準マージ・重複排除およびWikipediaインデックスによる客観的コスト調整アルゴリズム。

41. [辞書システム再設計仕様書 (System Design Document)](docs/dictionary_system_redesign.md)
    - 3層ハイブリッド辞書アーキテクチャ（Canonical / Base Dictionary 13.1万語 / SQLite Multi-Dictionary Schema v2）。
    - 2回以上の厳格なセルフレビューと品質・非回帰契約の検証記録。
    - `build_base_dictionary.py` による自動ビルドパイプラインと実機配備結果。

42. [ユーザー入力履歴の活用アーキテクチャ仕様書](docs/user_input_history_architecture.md)
    - 確定単語頻度履歴（Unigram Selection History）、フレーズ遷移バイグラム（Bigram History）、エディタ先行文脈（Preceding Cursor Context）の3層構造。
    - 各履歴の記録・永続化（`ime_history.json` / `next_phrase_cache.json`）と高速サジェスト・リランキングへの適用メカニズム。

43. [音声入力独自アルゴリズム・ヒューリスティクス技術仕様書](docs/voice_input_algorithms_and_heuristics.md)
    - 音響VAD、デコードHotwords、文脈後処理、句読点復元、IME調停キューの5層独自アーキテクチャ。

44. [音声入力独自アルゴリズム評価基準・評価セット仕様書](docs/voice_input_evaluation_methodology_and_criteria.md)
    - 独自機能ごとのKPI、Positive再現率・Negative特異度基準、評価データセットの設計方針。

45. [音声入力独自アルゴリズム評価結果レポート](docs/voice_input_evaluation_report.md)
    - 142+件のテストケースに対する全機能ベンチマーク結果（合格率100%）。

46. [ストリーミング音声入力における確信度ベース・プレフィックス確定技術仕様・評価手法および比較レポート](docs/streaming_prefix_freeze_solutions_and_evaluation.md)
    - 長文ストリーミング入力時のフリッカー抑制と確信度プレフィックス確定（Freeze / Partial Commit）に関する3つのアイデア、評価メトリクス、評価セット構築法、シミュレーション比較評価および推奨アーキテクチャ。

47. [音声入力におけるフィラー（「あー」「うー」「えーと」等）自動除去：3つのアイデア提案・評価指標・評価セットおよび比較検証レポート](docs/filler_removal_ideas_and_evaluation.md)
    - 「あー」「うー」「えーと」「あのー」等のフィラー除去に関する3つのアーキテクチャ案（境界制約＆保護語彙＋LCP保留、Mozc品詞・2D連接コスト識別、音響プロソディ＆オンデバイスSLM尤度ゲート）、6つの評価メトリクス、全5カテゴリ・120件の評価セット仕様、および2段階カスケード推奨方式の比較検証レポート。

48. [オンデバイスSLM再ランキング：バッテリー消費・電力ベンチマークおよび最適化レポート](docs/slm_battery_and_power_consumption_report.md)
    - Pixel 10 Pro (Tensor G5) 実機ODPMサブシステムおよびCPU時間によるバッテリー消費の実測評価（リランクOFF vs Qwen2.5-0.5B / 1.5B）、`cached_context_logits_` によるKVキャッシュヒット7.0倍高速化・省電力化（-84%）、およびモバイルIME業界基準との比較。

49. [UI設計原則とシンプルさ維持ガイドライン (UI Design & Simplicity Guidelines)](docs/ui_design_and_simplicity_guidelines.md)
    - 機能・UIの精査と4階層段階的開示（Progressive Disclosure）アーキテクチャへの統合仕様、および今後機能を追加する際にUIの煩雑化を防ぐための5大原則（4カード上限、最適デフォルト優先、アセット一元管理、IMEミニマリズム、不要UI即時撤去）。

50. [音声入力×タッチ（フリック／キー）併用時のコーナーケース網羅分析・自然挙動定義・アーキテクチャ設計書](docs/voice_and_touch_interaction_corner_cases.md)
    - 音声入力（ストリーミング随時入力／VADポーズ区切り）中に確定ボタン・変換・フリック入力・バックスペース・濁点修飾・Undo・カーソル移動・ペースト・外部カーソル移動を行った際の全7カテゴリ・24コーナーケースの網羅的列挙、自然なユーザー体験の定義、全順序時系列保証モデル（$\text{Session}(S) \prec \text{Speech}(S) \prec \text{CommitsAfter}(S) \prec \text{Session}(S+1)$）、および [`VoiceTouchInputArbitrator.kt`](app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt) の実装・単体テスト仕様。

51. [「けっか（結果）」「さくじょ（削除）」リランク順位低下バグの根本原因分析・3つの解決策提案および既存評価セットでの定量比較レポート](docs/rerank_nano2_kekka_sakujo_debug_and_ideas.md)
    - Gemini Nano 2 選択時（`AiCoreReranker` → `LlmReranker` フォールバック + $\alpha=0.00$ + Context-Free `<s>`）に「けっか」で「結果」が1位から転落し、「さくじょ」で「削除」が8位（最下位）に転落する5つの複合的根本原因（Stage-1 先頭トークン近視眼・Stage-2 Top-2 打ち切り・トークン数除算によるサブワード細分化バイアス・キメラ候補/超稀少語の無差別プール混入）の解剖、3つの解決策アイデア（完全系列結合対数尤度・Mozcコスト較正事前分布＋文脈適応ゲーティング・候補プール構造フィルタリング）、および既存4評価セット（計 1,612 ケース）での実証評価レポート。

52. [FUTO Keyboard スワイプ入力モデル統合設計書 (FUTO Swipe Typing Design Document)](docs/futo_swipe_qwerty_design.md)
    - FUTO Keyboardの空間1D-CNN/DFSMN音響モデルおよびContextLMを英語QWERTYレイアウトに統合するための設計書。完全クリーンルームONNX Runtime C++エンジン、フラット配列TrieによるCTCビームサーチ、ARM NEON / x86_64 AVX2 SIMDハードウェア高速化仕様、および音声・タッチ調停アーキテクチャの定義。

53. [FUTO Keyboard スワイプ入力モデル Phase 1 評価ベンチマークレポート](docs/futo_swipe_phase1_evaluation_report.md)
    - 文脈付き実スワイプデータ1,000サンプルにおける空間モデル単体（Top-1: 89.0%, 3.08ms）と文脈LM連動（Top-1: 90.3%, 4.19ms）の定量ベンチマーク評価、およびオンデバイス実用性の実証レポート。

54. [統一英語・日本語ユーザー辞書 PRD & システム設計仕様書](docs/prd_and_design_unified_english_dictionary.md)
    - 日本語と英語の統合ユーザー辞書管理、英語入力時の単一よみフィールドUI（表記自動非表示）、FUTO Swipe C++ Trie動的マージ＆優先ブースト、およびQWERTY空間タイポ・SymSpellミススペル訂正候補の完全仕様。

55. [LM-First リランキングにおける部分一致候補の順位逆転現象（「さいしょの」→「最初」＞「最初の」）の数学的解剖とアルゴリズム改善設計書](docs/lm_first_partial_prefix_ranking_analysis_and_solutions.md)
    - 自己回帰言語モデルの連鎖律による部分候補（`viterbi_prefix`, $m < n$）と全文候補（$m = n$）の NLL 単調性・未消費読みスパン欠落の解剖、BPE トークン長正規化（Finding 37）と読みモーラ長スパン整合の理論的区別、および5つのアルゴリズム改善案（未消費読み生成補完・包含優越ゲート・ホライズン層別リランキング・後方 LSE 周辺化統合・期待解決モーラ効用）の設計仕様。

---

## 📂 Source Code Layout

| Path | Purpose |
| :--- | :--- |
| [`app/src/main/java/com/example/myasr/MainActivity.kt`](app/src/main/java/com/example/myasr/MainActivity.kt) | Application entry point, 4-card progressive disclosure UI controller, and IME setup launcher |
| [`app/src/main/java/com/example/myasr/VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) | System-wide Android Input Method Editor (IME) service with context capture |
| [`app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt`](app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt) | Multimodal state arbitrator coordinating live streaming voice, VAD chunks, touch, and continuous swipe gesture input |
| [`app/src/main/java/com/example/myasr/FutoSwipeEngine.kt`](app/src/main/java/com/example/myasr/FutoSwipeEngine.kt) | High-level Kotlin wrapper for on-device English QWERTY neural swipe typing |
| [`app/src/main/java/com/example/myasr/FutoSwipeDownloader.kt`](app/src/main/java/com/example/myasr/FutoSwipeDownloader.kt) | On-demand downloader and storage provisioner for FUTO Swipe ONNX models and vocabulary |
| [`app/src/main/cpp/swipe/futo_swipe_engine.cc`](app/src/main/cpp/swipe/futo_swipe_engine.cc) | Native C++ CTC Beam Search Trie decoder and ContextLM reranking engine |
| [`app/src/main/cpp/swipe/futo_swipe_jni.cc`](app/src/main/cpp/swipe/futo_swipe_jni.cc) | JNI bridge connecting Kotlin `FutoSwipeEngine` to native ONNX Runtime C++ engine |
| [`app/src/main/java/com/example/myasr/EnglishDictionaryUtils.kt`](app/src/main/java/com/example/myasr/EnglishDictionaryUtils.kt) | English word validation, normalization, QWERTY physical key spatial edit distance, and typo candidate generator |
| [`app/src/main/java/com/example/myasr/EnglishSpellEngine.kt`](app/src/main/java/com/example/myasr/EnglishSpellEngine.kt) | SymSpell native/fallback English spelling correction engine with user dictionary synchronization |
| [`app/src/main/cpp/spelling/english_spell_engine.cc`](app/src/main/cpp/spelling/english_spell_engine.cc) | Native C++ SymSpell dictionary and spell candidate generator |
| [`app/src/main/cpp/spelling/english_spell_jni.cc`](app/src/main/cpp/spelling/english_spell_jni.cc) | JNI bridge connecting Kotlin `EnglishSpellEngine` to native C++ SymSpell |
| [`app/src/main/java/com/example/myasr/NextPhrasePredictor.kt`](app/src/main/java/com/example/myasr/NextPhrasePredictor.kt) | Zero-query next-phrase continuation prediction engine (Bigrams, Collocations, Particles) |
| [`app/src/main/java/com/example/myasr/LlmReranker.kt`](app/src/main/java/com/example/myasr/LlmReranker.kt) | High-performance on-device SLM re-ranking singleton controller |
| [`app/src/main/cpp/llm_reranker.cc`](app/src/main/cpp/llm_reranker.cc) | Native C++ KV cache retention, rollback & causal token log-likelihood engine |
| [`app/src/main/cpp/llm_reranker_jni.cc`](app/src/main/cpp/llm_reranker_jni.cc) | JNI bridge connecting Kotlin `LlmReranker` to native C++ engine |
| [`app/src/main/java/com/example/myasr/KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/KanaKanjiEngine.kt) | Japanese Kana-Kanji conversion engine driven by Mozc CRF parameters, bigram connection matrix, and SLM re-ranking |
| [`app/src/main/java/com/example/myasr/ConnectionMatrix.kt`](app/src/main/java/com/example/myasr/ConnectionMatrix.kt) | Memory-mapped 2D Bigram Connection Cost Matrix ($2672 \times 2672$) zero-copy evaluator |
| [`app/src/main/java/com/example/myasr/ConnectionDataManager.kt`](app/src/main/java/com/example/myasr/ConnectionDataManager.kt) | Mozc `connection_single_column.txt` on-demand stream downloader and binary compiler |
| [`app/src/main/java/com/example/myasr/ContextAwareProcessor.kt`](app/src/main/java/com/example/myasr/ContextAwareProcessor.kt) | Context-aware variant resolution, homophone disambiguation & spacing engine |
| [`app/src/main/java/com/example/myasr/AsrApp.kt`](app/src/main/java/com/example/myasr/AsrApp.kt) | Singleton Application container sharing `AsrManager`, dictionary & `LlmReranker` |
| [`app/src/main/java/com/example/myasr/AudioRecorder.kt`](app/src/main/java/com/example/myasr/AudioRecorder.kt) | 16 kHz mono 16-bit PCM microphone capture engine |
| [`app/src/main/java/com/example/myasr/AsrManager.kt`](app/src/main/java/com/example/myasr/AsrManager.kt) | Sherpa-ONNX wrapper for Zipformer offline decoding |
| [`app/src/main/java/com/example/myasr/DictionaryActivity.kt`](app/src/main/java/com/example/myasr/DictionaryActivity.kt) | Custom dictionary management screen (in-place add/edit form) |
| [`app/src/main/java/com/example/myasr/DictionaryAdapter.kt`](app/src/main/java/com/example/myasr/DictionaryAdapter.kt) | RecyclerView adapter for dictionary entries |
| [`app/src/main/java/com/example/myasr/DictionaryEntry.kt`](app/src/main/java/com/example/myasr/DictionaryEntry.kt) | Data model for (Word, Reading, Priority) |
| [`app/src/main/java/com/example/myasr/DictionaryRepository.kt`](app/src/main/java/com/example/myasr/DictionaryRepository.kt) | Local JSON persistence for custom dictionary entries |
| [`app/src/main/java/com/example/myasr/HotwordsGenerator.kt`](app/src/main/java/com/example/myasr/HotwordsGenerator.kt) | Multi-variant hotword rule generator (Kanji, Hiragana, Katakana) |
| [`app/src/main/java/com/example/myasr/DictionaryPostProcessor.kt`](app/src/main/java/com/example/myasr/DictionaryPostProcessor.kt) | Post-decoding phonetic substitution & surface normalizer |
| [`app/src/main/java/com/example/myasr/JapaneseKanaUtils.kt`](app/src/main/java/com/example/myasr/JapaneseKanaUtils.kt) | Kana conversion (Hiragana $\leftrightarrow$ Katakana) and validation |
| [`app/src/main/java/com/example/myasr/ModelDownloader.kt`](app/src/main/java/com/example/myasr/ModelDownloader.kt) | In-app fallback downloader with progress feedback |
| [`app/src/main/res/layout/activity_main.xml`](app/src/main/res/layout/activity_main.xml) | Clean 4-card progressive disclosure layout for `MainActivity` |
| [`app/src/main/res/layout/keyboard_view.xml`](app/src/main/res/layout/keyboard_view.xml) | Keyboard IME bottom layout (Voice Toggle, Candidate Strip, Flick/QWERTY surface) |
| [`app/src/main/res/xml/method.xml`](app/src/main/res/xml/method.xml) | Input Method Editor metadata definition |
