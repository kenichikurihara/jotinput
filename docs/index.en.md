# JotInput Technical Documentation

> 🌐 **Language**: [**日本語 (`docs/index.md`)**](index.md) | **English (this page)**

Welcome to the official technical documentation for **JotInput** (**J**apanese **O**n-device **T**ext **Input**), a lightweight, 100% offline, on-device Android Japanese & English voice + flick/QWERTY keyboard (IME) powered by ReazonSpeech, NVIDIA Parakeet, Sherpa-ONNX, Mozc-based Kana-Kanji conversion, neural swipe typing (FUTO Swipe), and optional on-device SLM candidate re-ranking (`llama.cpp`).

---

## 💡 Core Project Motivation: Respecting Your User Dictionary Across Voice & Swipe Input

### Background & Problem
On most keyboards, cloud voice input tools, and swipe IMEs, custom user-dictionary words (personal names, company names, domain terms, and abbreviations) only work during standard tap or flick typing—**voice recognition and swipe typing completely ignore the user dictionary**, causing the same misrecognitions and wrong conversions over and over.

### JotInput's Principle
One of the core motivations behind JotInput is **consistently respecting a single persistent custom user dictionary (`custom_dictionary.json`) not only in flick and tap typing, but also across Voice Input (Japanese & English) and Swipe Input (FUTO Swipe)**. While keeping everything 100% on-device and private, JotInput is built around one core promise: *teach your own vocabulary once, and have it recognized accurately no matter which input modality you use*.

### How Each Input Modality Respects Your Custom Dictionary
- **Japanese Voice Input (ReazonSpeech)**: Combines in-beam acoustic hotword biasing ([`HotwordsGenerator.kt`](../app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt)), post-decoding phonetic homophone substitution ([`DictionaryPostProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/application/DictionaryPostProcessor.kt)), context-aware variant resolution ([`ContextAwareProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt)), and correction learning—achieving **84.5%** recall with **0.0%** false positive rate (FPR) across 1,000 tricky Japanese sentences ([`dictionary_solutions.md`](dictionary_solutions.md) / [`context_aware_evaluation_report.md`](context_aware_evaluation_report.md) / [`voice_correction_learning_design_and_evaluation.md`](voice_correction_learning_design_and_evaluation.md)).
- **English Voice Input (NVIDIA Parakeet TDT-CTC 110M)**: Applies pure-domain English phonetics (Metaphone/G2P), spoken symbol normalization, aligned subsequence matching, DMN arbitration ([`EnglishUserDictDecisionModel.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt)), and forced Stage-2 SLM context gating ([`EnglishVoiceUserDictionaryResolver.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt)), boosting Exact TWRR from 22.06% to **92.65%** with **0.0%** FPR ([`Finding 61 & ADR-026`](findings_and_decisions.md#finding-61--adr-026-english-voice-user-dictionary-prioritization--forced-stage-2-slm-windowing)).
- **English Swipe Input (FUTO Swipe)**: Dynamically merges enabled custom entries into the native C++ CTC Beam Search Trie with priority boosting ([`FutoSwipeEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt) / [`futo_swipe_engine.cc`](../app/src/main/cpp/swipe/futo_swipe_engine.cc)), allowing out-of-vocabulary (OOV) and mixed-case proper nouns (e.g., `JotInput`, `ReazonSpeech`, `Nakamura`) to decode directly as Top-1 from swipe gestures ([`prd_and_design_unified_english_dictionary.md`](prd_and_design_unified_english_dictionary.md) / [`futo_swipe_qwerty_design.md`](futo_swipe_qwerty_design.md)).
- **Japanese Flick (Kana-Kanji) & English QWERTY Tap (Spelling & Completion)**: Automatically synchronizes the same user dictionary into [`KanaKanjiEngine.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) and [`EnglishSpellEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt) (SymSpell + spatial typo correction and prefix completion).
- **Single Persistent Dictionary Across Japanese & English**: Manage both languages in one unified screen ([`DictionaryActivity.kt`](../app/src/main/java/com/example/myasr/DictionaryActivity.kt)). English entries require only a single reading field, and bilingual entries with a kana reading plus English surface (e.g., `れいぞんすぴーち` → `ReazonSpeech`) automatically work across Japanese voice/flick and English voice/swipe/spelling without duplicate registration. `custom_dictionary.json` is strictly preserved across app upgrades ([`prd_and_design_custom_dictionary_ui.md`](prd_and_design_custom_dictionary_ui.md)).

---

## 📚 Table of Contents

> 🧭 **[Top-Level PRD — Product Vision, Prioritized CUJs & North Star Metric (EIS)](prd_top_level.md)** — Top-level PRD governing all feature PRDs and evaluation suites, defining the unified **EIS (Effective Intended-text Speed, intended CPM)** metric and release guardrails
>   - 📊 [EIS Baseline Measurement Report (`docs/eis_baseline_report.md`)](eis_baseline_report.md)
>   - 🚀 [Japanese Flick Input (CUJ-2) Headroom Analysis & EIS Acceleration Roadmap (`docs/flick_eis_headroom_analysis.md`)](flick_eis_headroom_analysis.md)
>   - 🌌 [Flick Input (CUJ-2/3) Precision Headroom Anatomy & Moonshot Concepts (`docs/eis_radical_headroom_and_moonshot_concepts.md`)](eis_radical_headroom_and_moonshot_concepts.md)
>   - 🧪 [100-Case Japanese Flick Input Headroom Evaluation Report (`docs/flick_input_100_headroom_evaluation_report.md`)](flick_input_100_headroom_evaluation_report.md)
>   - 📐 [Utility Score Predictive Conversion Optimization Design & Evaluation Plan (`docs/utility_score_algorithm_design.md`)](utility_score_algorithm_design.md)
>   - 🌈 [Flick Candidate Diversity Redesign & Top-Line EIS Improvement Proposals (`docs/flick_diversity_topline_eis_improvement_ideas.md`)](flick_diversity_topline_eis_improvement_ideas.md)
>   - 🛠️ [Detailed Design (DD): Flick Candidate Diversity P0 Improvements (Idea D1 + D2 + D3) (`docs/dd_flick_diversity_p0_ideas.md`)](dd_flick_diversity_p0_ideas.md)
>   - ⚡ [Zero-Query Next-Phrase Prediction Algorithm & Top-Line EIS Design (`docs/zero_query_next_phrase_prediction_eis_improvement_design.md`)](zero_query_next_phrase_prediction_eis_improvement_design.md)
>     - 📋 [Detailed Design (DD): Zero-Query Next-Phrase Prediction Phases 1–3 (`docs/dd_zero_query_next_phrase_prediction_phases.md`)](dd_zero_query_next_phrase_prediction_phases.md)
>
> 🎯 **[Master Evaluation Index (`docs/evaluation_suites_and_methods.md`)](evaluation_suites_and_methods.md)** — Master map of all evaluation datasets, evaluation scripts, JVM unit tests, C++ benchmarks, and formal verification suites across the repository

0. [Domain-Driven Design (DDD) Architecture](ddd/README.md)
   - [Context Map & Integration Patterns](ddd/context_map.md)
   - [Bounded Context Canvases (8 Contexts)](ddd/bounded_context_canvases.md)
   - [Tactical Design & Refactoring Plan](ddd/tactical_design_and_refactoring_plan.md)

1. [System Architecture](architecture.md)
   - High-level pipeline and data flow
   - Audio recording subsystem ([`AudioRecorder`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AudioRecorder.kt))
   - ASR engine abstraction ([`AsrManager`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt))
   - UI and interaction lifecycle ([`MainActivity`](../app/src/main/java/com/example/myasr/MainActivity.kt))
   - Model download and storage fallback ([`ModelDownloader`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ModelDownloader.kt))

2. [ReazonSpeech Model & On-Device Optimization](reazonspeech_model.md)
   - Background on ReazonSpeech corpus & model choices
   - Zipformer transducer architecture (`reazonspeech-k2-v2`)
   - Quantization strategy (int8 encoder, float32 decoder & joiner)
   - Memory footprint, CPU utilization, and RTF (Real-Time Factor)
   - [English On-Device ASR Model Selection & System Design](english_asr_model_selection_and_design.md) — NVIDIA Parakeet TDT-CTC 110M (`CC-BY-4.0`, ~126 MB int8, RTF ~0.066, native PnC)

3. [Setup, Build, and Deployment Guide](setup_and_development.md)
   - Prerequisites (Java 21, Android SDK, Gradle)
   - Model asset preparation (`download_model.sh`)
   - Compiling the debug APK
   - Testing on ARCVM (ChromeOS Android container) and physical devices
   - Useful ADB commands & troubleshooting

4. [Custom Dictionary Solutions (Japanese ASR)](dictionary_solutions.md)
   - Dual-key specification: `(Word, Reading)` / `(表記, よみ)`
   - Dual-channel reinforcement: In-beam acoustic biasing + phonetic homophone substitution
   - Automated Hiragana / Katakana expansion
   - User data model & UX design
   - Phased implementation roadmap

5. [Custom Dictionary Implementation Plan](dictionary_implementation_plan.md)
   - Detailed component design & data flow
   - Repository, HotwordGenerator, and PostProcessor implementation steps
   - Android UI & verification guide

6. [Custom Dictionary Improvement & Evaluation Plan](dictionary_improvement_plan.md)
   - Root cause analysis with ARCVM logcat post-mortem evidence
   - Tokenization space-separation fix (`cjkchar` format)
   - Quantitative evaluation plan (TWRR, CER, FPR, RTF) with 20-case test matrix
   - Review checkpoints before implementation

7. [1,000-Example Tricky Evaluation Report & Cross-Validation](evaluation_report_1000.md)
   - Comprehensive benchmark on 1,000 tricky Japanese sentences
   - 5-fold stratified cross-validation for hyperparameter tuning ($\alpha$)
   - Before/after comparison: Baseline, Flawed Hotwords, Fixed Hotwords, and Hybrid Pipeline
   - Precision, Recall, Accuracy, F1, FPR, and RTF metrics
   - Detailed breakdown across 250 People Names, 250 Homophones, 150 Place Names, 200 Tech Terms, and 150 Negatives
   - Custom dictionary persistence guarantee across app updates

8. [Android Voice Keyboard (IME) Development Plan & Specification](keyboard_development_plan.md)
   - Real Android Input Method Editor (`InputMethodService`) design
   - Minimalist keyboard UI: Centered toggle button (話す / 完了), `DEL`, `RETURN`, `、`, `。`, and `🌐`
   - Shared ASR model container ([`AsrApp`](../app/src/main/java/com/example/myasr/AsrApp.kt)) for zero-overhead memory reuse
   - `InputConnection` event flow and lifecycle management
   - Permission fallback flow for background IME services

9. [Architectural Decisions & Technical Findings Record](findings_and_decisions.md)
   - Comprehensive record of ADRs (ADR-001 through ADR-026)
   - Critical technical post-mortems (Sherpa-ONNX `cjkchar` whitespace bug, IME permission delegation, ARCVM hardware keyboard suppression)
   - 1,000-sentence cross-validation results and hyperparameter tuning findings
   - Dual-target deployment policies and Wi-Fi debugging characteristics
   - Viterbi lattice multi-bunsetsu search, local mock gap post-mortem, and colloquial verb contraction trap elimination (Findings 17–19)

10. [Context-Aware Recognition & Keyboard Enhancement Plan](context_aware_recognition_plan.md)
    - Architecture for leveraging `InputConnection.getTextBeforeCursor(500, 0)`
    - Semantic homophone disambiguation and rare Kanji variant resolution
    - Smart Latin boundary spacing and clause punctuation
    - Dedicated 1,000-sentence context generation & evaluation plan

11. [Context-Aware Speech Recognition Evaluation Report](context_aware_evaluation_report.md)
    - Comparative benchmark on 1,000 tricky Japanese sentences (Baseline vs. Hotwords vs. Hybrid vs. Context-Aware)
    - Overall recall increase to **84.5%** (+30.5% over Baseline, +3.9% over Hybrid)
    - People names jump to **76.0%** (+9.2% gain), homophones reach **96.0%**, tech terms reach **77.5%**
    - Zero false positives on negative controls (FPR: 0.0%) and zero latency overhead (0.014ms/utterance)

12. [Google Play Store Publishing Guide](play_store_publishing_guide.md)
    - Comprehensive end-to-end publishing lifecycle and prerequisites
    - Personal vs. Organization accounts, D-U-N-S verification & mandatory 14-day closed testing
    - Technical requirements (App Bundle `.aab`, 64-bit native libs, Play App Signing, R8)
    - Policy compliance (Privacy policy, Data safety, Account deletion, Foreground services)
    - Store asset specifications, release track progression, and Fastlane / GitHub Actions automation

13. [Gboard-Inspired Flick-Only & English QWERTY Keyboard Plan](qwerty_and_gboard_flick_plan.md)
    - Empirical analysis of Gboard's Flick-Only Japanese layout and English QWERTY layout on Pixel 10 Pro
    - Optional QWERTY top number row (`1–0`) toggle and live `SharedPreferences` synchronization
    - Directional modifier flicks (`゛`, `゜`, `大⇔小`), 5-direction punctuation key (`、。？！…`), Undo (`↶`), and cursor keys (`◀`, `▶`)
    - Unified circular flick popup and single-key QWERTY callout via zero-inset `PopupWindow`

14. [Mozc On-Device SLM/LLM Integration & Evaluation Plan](mozc_ondevice_llm_integration_plan.md)
    - Resolution of Mozc local-minima / bunsetsu boundary limits (e.g., "ことなります" vs. "異なります")
    - Hybrid Viterbi N-best + On-device SLM conditional log-likelihood re-ranking architecture
    - Lightweight model selection (Qwen2.5-0.5B, Qwen2.5-1.5B, Llama-3.2-1B, Zenzai-style Transformers)
    - 10,000-sample balanced evaluation dataset design, data acquisition pipeline, and Win/Loss metrics

15. [Phase 1 PoC Evaluation Report (On-Device SLM Re-ranking)](phase1_poc_evaluation_report.md)
    - Empirical verification of Qwen2.5-0.5B (GGUF INT4) on Intel Core i5 CPU
    - Demonstration of positional bias in multiple-choice prompts vs. mathematical superiority of token conditional log-likelihood
    - Decisive resolution of motivating issue ("Mozcは私の直感と挙動が" + "ことなります" -> "異なります", ~40x probability over hiragana)
    - Quantitative results: +62.0% net Top-1 gain (31 Wins, 0 Losses) with sub-100ms latency

16. [Phase 2 Scaled Evaluation Report (1,000 Stratified Samples)](phase2_scale_evaluation_report.md)
    - Automated pipeline generating a 10,000-sample balanced dataset from KWDLC, Tatoeba, and homophone contrast pairs
    - Large-scale statistical benchmark across 1,000 stratified samples (434 Wins, 0 Losses, Win/Loss ratio 434:1)
    - +43.40% net Top-1 gain across web articles, conversational dialogues, and homophone matrices
    - Sub-50ms single-query latency validation and readiness for Phase 3 C++ in-process engine integration

17. [Phase 3 C++ Engine Integration Report (Native In-Process Re-ranking)](phase3_cpp_integration_report.md)
    - Direct C++ implementation of `LlmReranker` and `LlmRewriter` adhering to Mozc's `RewriterInterface`
    - Integration of `libllama` (`llama.cpp`) with fast mmap model loading (< 210 ms)
    - Combined cost formula scaling: $\text{Score} = \alpha \cdot (\text{Cost}/1000) + (1-\alpha) \cdot (-\text{LogProb})$
    - 100% pass rate (7/7) on end-to-end native test suite, promoting "異なります", "移動", "交渉", "記者", "貴社" in < 100 ms

18. [Phase 4 Plan: Production-Grade KV Cache Optimization, Multi-Token Disambiguation & Non-Blocking Architecture](phase4_optimization_and_production_plan.md)
    - Hierarchical prefix KV cache engine with `llama_memory_seq_rm()` rollback for sub-20ms keystrokes
    - Two-stage adaptive disambiguation: fast-path (0 ms extra) vs. multi-token tie-break (16 ms for `決裁` vs `決済`)
    - Context prefix hashing (FNV-1a) and generation counters for asynchronous, non-blocking UI responsiveness
    - Production hardening against cursor jumps, buffer overflow, and multi-byte boundary errors

19. [Phase 4 Implementation Report: Production KV-Cache Reuse & Sub-Millisecond IME Re-ranking](phase4_implementation_report.md)
    - Empirical keystroke latency reduction from ~70–100 ms down to **0.80 ms** (>85x speedup)
    - 64-bit FNV-1a context invalidation and `llama_memory_seq_rm()` speculative token rollback
    - 100% pass rate (4/4) on production test suite (motivating issue, 状況 vs 上京, live typing keystroke simulation, 交渉)

20. [On-Device SLM Re-ranking Android Integration & Live Verification Report](ondevice_slm_android_integration_report.md)
    - Native Android NDK C++ JNI integration (`libmyasr_llm.so` linking `llama.cpp` statically)
    - End-to-end Kotlin IME pipeline integration (`LlmReranker.kt` + `KanaKanjiEngine.kt` + `VoiceInputMethodService.kt`)
    - Live ARCVM logcat verification: `ことなります` -> `異なります` (Top-1) and `じょうきょう` -> `状況` (Top-1)
    - Preservation of Custom Dictionary Contract and zero-cost fallback when model is absent

21. [IME Keystroke Latency Offline Evaluation & Performance Architecture](ime_latency_and_offline_eval_plan.md)
    - Two-stage asynchronous candidate pipeline (instant synchronous fast-path $< 1\text{ ms}$ + async coroutine SLM re-ranking)
    - Stale keystroke coroutine job cancellation during rapid typing
    - Candidate pool pruning to top $K=8$ and multi-token evaluation bounds
    - Standalone native Linux keystroke benchmark harness ([`tools/bench_ime_latency.cc`](../tools/bench_ime_latency.cc))
    - Elimination of UI thread freezing and ANRs on physical phone (Pixel 10 Pro)

22. [Long Phrase & Multi-Bunsetsu Conversion Headroom Report](long_phrase_conversion_headroom_report.md)
    - Headroom analysis across 3,672 long Japanese phrases ($\ge 8$ kana)
    - Empirical quality metrics: Current 33.1% Top-1 Precision vs **92.4% SLM Potential** (+59.3% headroom)
    - Taxonomy of 4 failure modes (boundary segmentation, auxiliary verb corruption, honorific mis-splitting, token-0 collision)
    - Concrete mathematical proofs with $+19.5$ to $+33.6$ nats likelihood advantage using on-device Qwen2.5-0.5B

23. [Evaluation of 5 Improvement Ideas for Long Phrase Conversion](long_phrase_improvement_ideas_and_evaluation.md)
    - Detailed breakdown of 5 proposals: Trie N-Best Viterbi, Full-Sequence SLM, Colloquial Transition Filters, Adaptive Chunking, and Speculative Autocomplete
    - Empirical comparative matrix on 500 continuous long phrases (1.0% Baseline $\to$ 20.2% Viterbi+Penalty $\to$ 92.4% Hybrid)
    - Implementation of In-Memory Viterbi Lattice Beam Search & Colloquial Transition Penalties in [`KanaKanjiEngine.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt)
    - Sub-millisecond execution budget verification via [`LongPhraseConversionTest.kt`](../app/src/test/java/com/example/myasr/LongPhraseConversionTest.kt)

24. [Context-Free SLM Candidate Re-ranking Evaluation Report](context_free_slm_reranking_evaluation.md)
    - Quality and latency evaluation of triggering SLM re-ranking when preceding context is empty (`textBeforeCursor.isBlank()`)
    - Empirical comparison: Baseline Trie (86.7% Top-1, 0.0% Bizarre) vs. Context-Free SLM (30.0%–35.0% Top-1, 30.0%–35.0% Bizarre)
    - Discovery of unigram prior degeneration where standalone SLM prefers internet slang (`作業厨デスので`) over standard phrases
    - Latency analysis: 20–34 ms warm / 50–280 ms cold vs. 0.2 ms Trie fast-path
    - Architectural justification for gating SLM re-ranking exclusively on non-empty context ($|C| \ge 1$)

25. [Context-Free SLM Re-ranking Implementation & Settings](findings_and_decisions.md#finding-21-context-free-slm-re-ranking-implementation--toggle-settings)
    - Scale evaluation finding: Context-Free SLM (26.10%) improves over Mozc Baseline (12.60%) by +13.50% on 1,000 cases
    - On-device implementation with BOS (`<s>`) KV caching in `libmyasr_llm.so`
    - In-app toggle switch (`switchSlmContextFree`) with persistence in `ime_preferences.xml`
    - Seamless live switching between context-only mode and full context-free mode

26. [SLM Alpha Optimization & 5-Fold Cross-Validation Report](slm_alpha_cross_validation_report.md)
    - Systematic 5-fold cross-validation on 1,000 samples across $\alpha \in [0.00, 1.00]$
    - Proven convergence: All 5 folds independently select optimal $\alpha^* = 0.00$
    - Out-of-Fold (OOF) accuracy reaches 66.80% (Context-Aware) and 51.10% (Context-Free)
    - Complete sensitivity curve and production regularization recommendation ($\alpha \approx 0.05 \sim 0.10$)

27. [Exclusion of All-Hiragana Candidates from Ranking & Display](findings_and_decisions.md#finding-23-exclusion-of-all-hiragana-candidates-from-conversion-ranking-and-display)
    - Complete elimination of all-hiragana candidates from candidate generation, SLM re-ranking, and UI candidate bar
    - Rationale: composing text is already rendered as raw Hiragana on screen; pressing Enter/Return directly commits it without conversion
    - Defense-in-depth across 4 layers: `JapaneseKanaUtils.isAllHiragana()`, `KanaKanjiEngine` candidate & lattice filtering, `LlmReranker` pre-computation filtering, and `CandidateStripView` view presentation
    - Verification: Unit test suite green (23 tasks); dual-target deployed and verified live on Pixel 10 Pro and ARCVM

28. [Zero-Query Next-Phrase Continuation Prediction Report & Ideas Evaluation](next_phrase_prediction_ideas_and_evaluation.md)
    - Brainstorming and iterative evaluation across 7 architectural perspectives: User Bigram History, Canonical Collocations, Particle Continuations, On-Device SLM Generation, Contextual/Temporal Heuristics, Cloud LLM, and Multi-Tier Hybrid
    - Formal adoption and implementation of the **Tiered Hybrid Architecture** in [`NextPhrasePredictor.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt)
    - Sub-millisecond execution ($< 20\text{ µs}$) with persistent learning, standard conversational anchors, and particle suggestions
    - Full IME integration in [`VoiceInputMethodService.kt`](../app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) with unit test coverage

29. [Voice Punctuation Restoration & Continuous Sentence Boundary Input](voice_punctuation_and_sentence_boundary_input.md)
    - Implementation of [`JapanesePunctuationEngine.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt) for spoken commands (`まる`, `てん`, `かいぎょう`) and automatic sentence-ending periods (`です`, `ます`).
    - Vocabulary protection masking (e.g., 天気, 天然, 満点, 交差点, 重点, まるで, 一丸, 日の丸).
    - Micro-pause flushing (200ms) and continuous pacing flush (2400ms) for pause-free boundary commits.

30. [Punctuation & Voice Boundary Evaluation Criteria & Benchmark Suite](punctuation_and_voice_boundary_evaluation_criteria.md)
    - Comprehensive benchmark specifications and criteria across 7 categories and 67 unit test cases ([`JapanesePunctuationEvaluationTest.kt`](../app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt)).

31. [Streaming Voice Input Architecture, Continuous In-place Input & Dynamic Prefix Revision](streaming_voice_input_architecture_and_deliberation.md)
    - In-depth architectural self-deliberation on real-time continuous streaming vs. legacy pause-flush input.
    - Continuous as-you-speak in-place editor rendering via active live composing spans without waiting for sentence boundaries (periods).
    - Dynamic prefix revision: automatically rewriting earlier words (prefixes) as right-context words arrive (e.g. "移動" $\to$ "異動願い", "です。" $\to$ "ですから、").
    - Horizon-based dynamic sliding window: keeps active revision horizon in `setComposingText()` while progressively freezing older stabilized sentences into `commitText()`.
    - Strict mutual exclusion: automatically disabling pause-based chunking (`microPauseDurationMs`, `silenceDurationMs`) when streaming mode is enabled.
    - Comprehensive evaluation benchmark design covering continuous in-place input, prefix revision, multi-sentence streaming, and regression guarantees.

32. [Mandatory SLM Reranking with Bounded Timeout: Design & Latency Evaluation](slm_mandatory_rerank_design_and_latency.md)
    - Elimination of Fast-Path candidate flickering and Space-commit bypass of SLM via mandatory bounded timeout reranking (Default: ON).
    - Strict anti-anecdotal tuning policy prohibiting ad-hoc unigram cost tweaking.
    - Analysis of Mozc community corpus CRF optimization ($POS \times POS$ connection matrix + negative log-likelihoods).
    - Comprehensive mobile latency evaluation: 100ms fail-safe budget selection vs 22–38ms typical SLM inference time.

33. [Mozc 2D Connection Cost Matrix (`connection_single_column.txt`) Evaluation Report](connection_matrix_evaluation_report.md)
    - Comparative empirical evaluation with vs without Mozc's 2D connection cost matrix ($2672 \times 2672$, 7.14M transitions).
    - Mathematical transition mechanics: BOS $\to$ bound noun penalty ($+5,841$) eliminating `生みのまわりに` in favor of `海の周りに`.
    - 5-column POS dictionary schema enrichment (`base_dictionary.tsv`, generated under `tools/mozc_dict/` and downloaded on demand; not bundled in the APK) derived systematically from Mozc OSS CRF corpus.
    - Quantified gains: Top-1 (+2.0%), Top-3 (+2.0%), MRR (+0.024) on conversational suite, and +1.47% pass rate (+9 sentences) on official Mozc OSS benchmark.
    - Zero-overhead virtual memory mapping with only $+0.074\text{ms}$ latency impact.

34. [Mandatory SLM Re-ranking Performance & Disambiguation Evaluation Report](slm_mandatory_rerank_evaluation_report.md)
    - Comprehensive empirical comparison of Mandatory SLM Re-ranking vs Mozc Connection Matrix baseline.
    - Context-Aware Homophone Disambiguation (50 items): Top-1 accuracy leaps from **42.0% $\to$ 80.0% (+38.0% absolute gain)**; MRR increases from **0.677 $\to$ 0.858 (+0.181)**.
    - Mathematical limitation of 2D connection matrix: identical-POS homophones (`移動` [1841] vs `異動` [1841], `回答` [1841] vs `解答` [1841]) have 0 delta cost under bigram grammar alone.
    - Discovery and fix for multi-token speculative scoring length penalty bias via per-token length normalization.
    - Alpha hyperparameter sensitivity analysis across $\alpha \in [0.00, 1.00]$ and mobile latency evaluation ($\le 108\text{ms}$ vs $500\text{ms}$ timeout).

35. [Debug Display Specification & Multi-Target Verification Report](debug_display_specification_and_verification.md)
    - Specification and verification report for the "デバッグ表示" (Debug Display) toggle.
    - Non-clickable dummy `[SLM]` chip rendering in [`CandidateStripView`](../app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt) when on-device SLM re-ranking is applied.
    - Zero-regression candidate list isolation and dual-target verification on Pixel 10 Pro and ARCVM.

36. [On-Device Hardware Acceleration Analysis & Optimization Report](ondevice_hardware_acceleration_analysis_and_optimization.md)
    - Comprehensive audit of on-device hardware acceleration (GPU/NPU/CPU SIMD) for both ASR (Sherpa-ONNX/Zipformer) and SLM (llama.cpp/Qwen2.5-0.5B).
    - Identification of missing DotProd (`sdot`/`udot`) and FP16 vector instructions in arm64 native builds due to unconfigured NDK arch flags.
    - Explicit enablement of `armv8.2-a+fp16+dotprod` and `-O3` yielding 1,072 SIMD DotProd instructions, plus AVX2/FMA for x86_64 and dynamic thread scaling (2–4 cores) for ASR.
    - Deep dive on why NNAPI (deprecated in Android 15) and mobile GPU offload (dispatch latency > compute time for short sequence log-likelihoods) are suboptimal compared to optimized CPU SIMD execution.

37. [Qwen2.5-1.5B Model Selection & Quantitative Evaluation Report](qwen_1_5b_model_selection_and_evaluation_report.md)
    - Specification and UI design for selecting the next larger SLM (`Qwen2.5-1.5B`, 1.1GB) alongside `Qwen2.5-0.5B` (469MB).
    - Empirical evaluation across the 100-item evaluation benchmark (Suite 1: Context-Aware Homophones; Suite 2: Collocations).
    - Quantitative proof of +8.0% boost in Homophone Top-1 accuracy (80.0% $\rightarrow$ 88.0%) and Top-3 accuracy (90.0% $\rightarrow$ 94.0%).
    - Seamless dynamic model swapping, dual-target verification on Pixel 10 Pro and ARCVM, and latency trade-off analysis.

38. [Predictive Prefix Matching Search Logic](predictive_prefix_matching_logic.md)
    - Comprehensive investigation into predictive candidate retrieval (e.g. `「ぱ」` $\rightarrow$ `「パン」`).
    - Dual pipeline analysis: SQLite multi-dictionary B-Tree range query and In-Memory Trie depth-first prefix expansion (`collectPrefixWords`).

39. [Voicing & Semi-Voicing Fuzzy Prefix Search Design & Evaluation](voicing_fuzzy_prefix_search_design_and_evaluation.md)
    - In-depth design discussions covering Branching Trie Search, O(1) phonotactic pruning, asymmetric voicing mapping, and BFS-based predictive expansion.
    - Resolves missing candidate issues for unvoiced inputs (e.g. `「かいき」` $\rightarrow$ `「会議」`, `「すーは」` $\rightarrow$ `「スーパー」`).
    - Quantitative benchmark across 20-item evaluation matrix: 100% pass rate, sub-0.2ms latency ($0.188\text{ms}$), and 100% non-regression on voice punctuation.

40. [Mozc UT Dictionary Best-Practices Survey & Lexicon Selection Report](mozc_ut_dictionary_best_practices.md)
    - Double-checked update activity of Mozc community UT dictionaries (`merge-ut-dictionaries`, active through Nov 2026).
    - Clear selection rationale for adopted dictionaries (`SudachiDict`, `jawiki`, `personal-names`, `place-names`) vs. excluded dictionaries (dormant `NEologd`, classical lexicons).
    - Community-standard merge/deduplication pipeline and objective Wikipedia index cost calibration algorithm.

41. [Dictionary System Redesign Specification (System Design Document)](dictionary_system_redesign.md)
    - 3-tier hybrid dictionary architecture (Canonical / Base Dictionary 131K entries / SQLite Multi-Dictionary Schema v2).
    - Multi-pass strict self-review and quality/non-regression verification log.
    - Automated `build_base_dictionary.py` build pipeline and physical device deployment verification.

42. [User Input History Utilization Architecture Specification](user_input_history_architecture.md)
    - 3-tier history architecture: Unigram Selection History, Bigram Phrase Transition History, and Preceding Cursor Context.
    - Persistence mechanisms (`ime_history.json` / `next_phrase_cache.json`) and integration with fast suggestion and candidate re-ranking.

43. [Voice Input Proprietary Algorithms & Heuristics Technical Specification](voice_input_algorithms_and_heuristics.md)
    - 5-layer architecture covering acoustic VAD, decode-time Hotwords, context post-processing, punctuation restoration, and IME arbitration queueing.

44. [Voice Input Proprietary Algorithms Evaluation Methodology & Criteria](voice_input_evaluation_methodology_and_criteria.md)
    - Per-feature KPIs, positive recall and negative specificity thresholds, and evaluation dataset design principles.

45. [Voice Input Proprietary Algorithms Evaluation Report](voice_input_evaluation_report.md)
    - Comprehensive feature benchmark results across 142+ test cases (100% pass rate).

46. [Confidence-Based Prefix Freeze in Streaming Voice Input: Technical Spec, Evaluation & Comparison](streaming_prefix_freeze_solutions_and_evaluation.md)
    - Three architectural proposals for flicker suppression and confidence-based prefix freezing (Freeze / Partial Commit) during long streaming voice input, evaluation metrics, benchmark construction, simulation comparison, and recommended architecture.

47. [Automatic Voice Filler Removal (`あー`, `うー`, `えーと`, etc.): 3 Proposals, Metrics, Dataset & Comparative Evaluation](filler_removal_ideas_and_evaluation.md)
    - Three architectural designs for removing spoken fillers (`あー`, `うー`, `えーと`, `あのー`, etc.): boundary-constrained protected vocabulary + LCP hold, Mozc POS & 2D connection-cost classification, and acoustic prosody + on-device SLM likelihood gating; 6 evaluation metrics; 120-item 5-category benchmark; and comparative results recommending a 2-stage cascade.

48. [On-Device SLM Re-ranking: Battery & Power Consumption Benchmark and Optimization Report](slm_battery_and_power_consumption_report.md)
    - Real-device ODPM subsystem and CPU-time power measurements on Pixel 10 Pro (Tensor G5) comparing Rerank OFF vs. Qwen2.5-0.5B / 1.5B, 7.0x KV-cache hit speedup & power savings (-84%) via `cached_context_logits_`, and comparison against mobile IME industry baselines.

49. [UI Design Principles & Simplicity Guidelines](ui_design_and_simplicity_guidelines.md)
    - Feature/UI audit and 4-layer Progressive Disclosure integration spec, plus 5 core rules preventing UI bloat (4-card cap, optimal defaults first, unified asset management, IME minimalism, immediate dead-UI removal).

50. [Voice + Touch (Flick/Key) Co-occurrence Corner Cases, Natural Behavior & Architecture Design](voice_and_touch_interaction_corner_cases.md)
    - Exhaustive taxonomy of 7 categories / 24 corner cases when users tap confirm, convert, flick, backspace, voice-modify, undo, move cursor, or paste during streaming or VAD-chunked voice input; total-order causal timeline invariant ($\text{Session}(S) \prec \text{Speech}(S) \prec \text{CommitsAfter}(S) \prec \text{Session}(S+1)$); and [`VoiceTouchInputArbitrator.kt`](../app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) design & unit test spec.

51. [Root-Cause Analysis, 3 Solutions & Quantitative Benchmark for `けっか` (`結果`) / `さくじょ` (`削除`) Rerank Drop](rerank_nano2_kekka_sakujo_debug_and_ideas.md)
    - Anatomy of 5 compounding root causes when Gemini Nano 2 (`AiCoreReranker` → `LlmReranker` fallback + $\alpha=0.00$ + Context-Free `<s>`) drops `結果` from Rank 1 and `削除` to Rank 8: Stage-1 token-0 myopia, Stage-2 Top-2 cutoff, per-token length normalization subword bias, and unfiltered chimera/rare-word pool contamination; 3 algorithmic solutions evaluated across 4 benchmark suites (1,612 cases total).

52. [FUTO Keyboard Swipe Typing Integration Design Document](futo_swipe_qwerty_design.md)
    - Design for integrating FUTO Keyboard's spatial 1D-CNN/DFSMN acoustic model and ContextLM into the English QWERTY layout: cleanroom ONNX Runtime C++ engine, flat-array Trie CTC beam search, ARM NEON / x86_64 AVX2 SIMD acceleration, and voice-touch arbitration.

53. [FUTO Keyboard Swipe Typing Phase 1 Evaluation Benchmark Report](futo_swipe_phase1_evaluation_report.md)
    - Quantitative benchmark on 1,000 real contextual swipe samples comparing spatial-only decoding (Top-1: 89.0%, 3.08 ms) vs. ContextLM fusion (Top-1: 90.3%, 4.19 ms), proving practical on-device latency and accuracy.

54. [Unified English & Japanese Custom Dictionary PRD & System Design](prd_and_design_unified_english_dictionary.md)
    - Unified custom dictionary management for Japanese and English, single-field English input (auto-hiding the surface field), dynamic FUTO Swipe C++ Trie merging & priority boosting, and QWERTY spatial typo / SymSpell misspelling candidate integration.

55. [Mathematical Anatomy & Algorithmic Solutions for Partial-Match Rank Inversion in LM-First Reranking (`さいしょの` → `最初` > `最初の`)](lm_first_partial_prefix_ranking_analysis_and_solutions.md)
    - Analysis of autoregressive NLL monotonicity causing partial-span candidates (`viterbi_prefix`, $m < n$) to outrank full-span candidates ($m = n$), distinguishing BPE token-count normalization (Finding 37) from mora-span alignment, and 5 algorithmic fixes (unconsumed reading completion, dominance gating, horizon-stratified reranking, backward LSE marginalization, expected resolved-mora utility).

56. [ASR-Agnostic Automatic Punctuation Insertion Architecture & Proposals](asr_agnostic_punctuation_insertion_ideas.md)
    - Four universal signals independent of specific ASR architectures (CTC/Transducer vs. Seq2Seq/Whisper): surface normalization, Mozc morphology & 2D connection matrix, VAD pause length & terminal $F_0$ pitch, and on-device SLM logits; 6 core ideas (retrospective inter-chunk boundary rewrite, comma density budget model); and a 5-stage hybrid cascade design.

57. [LM Evaluation of Unconsumed Spans & Diversity-Preserving Reranking Design](lm_unconsumed_span_evaluation_and_diversity_design.md)
    - Root-cause analysis explaining why static span penalties (ADR-020) failed to fix `さいしょの` (unscored unconsumed spans + `prioritizeByHistory` promoting partial matches to Rank 0 + 2-phase Tap-Safe pinning + diversity-destroying flat sorting), virtual full-sentence completion via backward Viterbi best suffix ($\widetilde{W}(U) = U \oplus V^*_{m:n}$), backward lattice LSE entropy bonus ($-\tau_{\text{branch}} \ln M_{\text{eff}}$), and 2D MMR submodular diversity selection generalizing [`prd_and_design_viterbi_nbest_diversity.md`](prd_and_design_viterbi_nbest_diversity.md) and [`prd_and_design_multi_length_prefix_diversity.md`](prd_and_design_multi_length_prefix_diversity.md) to LM posterior space.

58. [Master Evaluation Index (`docs/evaluation_suites_and_methods.md`)](evaluation_suites_and_methods.md)
    - Consolidated index of evaluation datasets, runners, C++ benchmarks, JVM unit tests, execution commands, and operating contracts across all 7 domains (Japanese ASR, Voice UX/Punctuation, Voice×Touch Arbitration/Alloy, Kana-Kanji/Connection Matrix/N-Best Diversity, SLM Reranking/LM-First, English Swipe/Completion/Spelling, and On-Device ODPM Power/Latency).

59. [Custom Dictionary UI Redesign PRD & Alloy Spec](prd_and_design_custom_dictionary_ui.md)
    - JTBD- and 5-CUJ-driven Zero-Friction entry form (surface-first, 1-step English auto-detection, automatic Katakana reading suggestion), live search filtering, bottom-sheet editing separated from cloud sync, instant delete + Snackbar Undo, and formal state verification via [`custom_dictionary_ui.als`](../app/src/test/resources/alloy/custom_dictionary_ui.als).

60. [Main App UI Redesign PRD & Formal Design (MainActivity v2 — 3-Tab + DMN Smart Guide)](prd_and_design_main_app_ui.md)
    - Replaces the legacy vertical 4-card cap with a JTBD/CUJ-driven 3-tab IA (Home / Settings / Data), [`MainAppUiDecisionModel`](../app/src/main/java/com/example/myasr/context/ime/domain/MainAppUiDecisionModel.kt) (3 DMN decision tables), and formal transition verification via [`main_app_ui_state.als`](../app/src/test/resources/alloy/main_app_ui_state.als).

61. [Onboarding Flow PRD & Formal Design (First Install, Reinstall & Google Drive History Restore)](prd_and_design_onboarding_flow.md)
    - Non-blocking onboarding PRD covering fresh install, reinstall (detecting Android Auto Backup permission revocation), and Google Drive history restore; 4 JTBDs, 4 CUJs, 12 corner-case guards, [`OnboardingDecisionModel`](../app/src/main/java/com/example/myasr/context/ime/domain/OnboardingDecisionModel.kt) (3 DMN decision tables), and [`onboarding_flow.als`](../app/src/test/resources/alloy/onboarding_flow.als).

62. [Google Drive History Sync Settings Integration & Periodic Background Sync PRD & Formal Design](prd_and_design_google_drive_sync_settings.md)
    - PRD for persistent Drive linking, folder switching, manual sync, disconnect, and `JobScheduler` periodic background sync from the Settings tab; 4 JTBDs, 4 CUJs, [`HistorySyncDecisionModel`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/HistorySyncDecisionModel.kt) (2 DMN decision tables), and [`history_sync_settings.als`](../app/src/test/resources/alloy/history_sync_settings.als).

63. [English QWERTY Truecasing & Capitalization Candidates PRD, CUJ, Algorithmic Design & Evaluation](prd_and_system_design_qwerty_capitalization_and_spell_candidates.md)
    - Corpus-derived $P_{\text{lex}}(s \mid w)$ eliminating static heuristics, 2-state character bigram LM for OOV acronym detection, Punkt-style probabilistic `SentenceBoundaryDetector`, and `BayesianCaseRanker` unifying tap, typo, and FUTO Swipe across CUJ-1–7 (60-case evaluation: Top-1 93.3%, Top-3 98.3%, avg 0.108 ms).

64. [Voice Correction Detection, Personalization & Score Boosting Design and Evaluation](voice_correction_learning_design_and_evaluation.md)
    - Five correction-detection mechanisms (action traces, Myers Diff snapshots, phonetic/G2P plausibility gates, morpheme boundary expansion, streaming hooks) and five score-boosting mechanisms (dynamic Hotwords promotion, confusion-pair×context BM25 rescoring, SLM coherence gating, English Truecase/Spell adaptation, 3-stage promotion + self-healing revert) for Japanese (ReazonSpeech) and English (Parakeet TDT-CTC 110M), with DDD/DMN/Alloy specs and 3-tier benchmark design.

65. [Debug Report: Root-Cause Analysis for Voice Correction Failing to Propagate (`デザインドキュメントを描いて` → `書いて`)](debug_voice_correction_kaite_root_cause.md)
    - Execution trace and fix plan for 5 compounding root causes blocking `描いて` → `書いて` learning: RC-1 (`VoiceCorrectionScoring` uncalled on streaming path), RC-2 (`readingResolver == null` triggering `fallbackJapaneseSurfaceDistance("描","書") == 1.0f` rejection), RC-3 (streaming freeze splitting `VoiceCommitAnchor`), RC-4 (1-char span extraction without okurigana), and RC-5 (unprotected homophone verb SLM gate).

66. [On-Device LLM/SLM Latency Headroom Analysis & Optimization Ideas](slm_latency_headroom_and_optimization_ideas.md)
    - Anatomy of 5 major latency bottlenecks in `LlmReranker.kt` / `llm_reranker.cc` (`llama.cpp` backend)—serial per-candidate Stage-2 `llama_decode` wasting DRAM bandwidth, redundant latent particle decodes, `ContextWindowTrimmer` prefix shifts causing LCP=0 cache flushes, 152K-vocab LM head projection, and Little-core barrier straggler delays—plus 10 optimization proposals (Prefix-Tree Packed Single-Pass Decoding, RoPE Position-Shift Ring KV Cache, Prior-Aware Admissible Lower Bound, Sparse LM Head, In-Flight Speculative Context Warming) targeting 3x–10x speedups ($25\sim 105\text{ms} \to 1\sim 12\text{ms}$).

67. [Codebase Heuristics Inventory & Reduction Plan](heuristics_inventory_and_reduction_plan.md)
    - Comprehensive inventory of hardcoded scores, POS IDs, length caps, and thresholds across dictionary compilation, Viterbi lattice search, zero-query prediction, SLM reranking, English input, and ASR post-processing, organized into a Tier A–C reduction roadmap.

68. [Detailed Design: Heuristics Reduction Plan (Tier A0 / A / B)](dd_heuristics_reduction_tier_a_and_b.md)
    - Implementation specs and verification gates G1–G6 for Tier A0 (behavior-preserving refactoring), Tier A (corpus consolidation & prediction delegation), and Tier B (automated POS table generation, mandatory connection matrix, dual-axis SLM-off/on pruning of Viterbi heuristics `V1/V2/V3`, SLM parameter reduction, and statistical English/voice-correction modeling).

69. [PRD, JTBD, CUJ & System Design: English Dictionary Strengthening & Prefix Completion (`tailsc` → `Tailscale`)](prd_and_design_english_dictionary_strengthening.md)
    - Multi-corpus vocabulary enrichment (NLTK seed, Debian English descriptions, multi-LLM tokenizer consensus, Mozc OSS, Aspell, FUTO vocab), pure-domain `EnglishCaseModel.suggestPrefixAndTypo()` combining binary search + frequency-descending typo buckets + upper-bound pruning, DMN `EnglishPrefixCompletionPolicy`, and formal verification via [`english_prefix_completion.als`](../app/src/test/resources/alloy/english_prefix_completion.als).

70. [PRD, JTBD, CUJ & System Design: Periodic Vocabulary Ingestion Pipeline](prd_and_design_periodic_vocabulary_ingestion.md)
    - Zero-hardcoding 5-tier (Tier 1–5) public corpus ingestion hierarchy, cross-lingual Japanese-English neologism reflow (Mozc-UT `jawiki` / `sudachidict`), 6-stage statistical admission guard preventing pollution and regression, monthly GitHub Actions auto-PR & diff-audit pipeline, and 3-phase roadmap.

71. [English Voice Input (Parakeet) User-Dictionary Prioritization & Forced Stage-2 SLM Windowing (Finding 61 & ADR-026)](findings_and_decisions.md#finding-61--adr-026-english-voice-user-dictionary-prioritization--forced-stage-2-slm-windowing)
    - Zero-hardcoded-table, fully on-device user-dictionary prioritization pipeline for NVIDIA Parakeet TDT-CTC 110M English voice input.
    - Pure-domain [`EnglishVoiceUserDictionaryResolver.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt) and DMN policy [`EnglishUserDictDecisionModel.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt) integrating Metaphone/G2P phonetics, spoken symbol normalization (`dot`/`slash`/`plus`/`sharp`), aligned subsequence matching, and bilingual kana-reading entries.
    - Forced Stage-2 SLM diverging pair windowing (`LlmReranker.extractDivergingPairWindow()`) and Alloy formal verification ([`english_voice_user_dict.als`](../app/src/test/resources/alloy/english_voice_user_dict.als)).
    - Real-model benchmark (Parakeet TDT-CTC 110M + Qwen2.5-0.5B): Exact TWRR leaps from 22.06% $\to$ **92.65%** with **0.0%** FPR across 42 adversarial negatives and 300 baseline English utterances.

---

## 📂 Source Code Layout

| Path | Purpose |
| :--- | :--- |
| [`app/src/main/java/com/example/myasr/MainActivity.kt`](../app/src/main/java/com/example/myasr/MainActivity.kt) | Application entry point, v2 3-Tab (Home / Settings / Data) + DMN Smart Guide UI controller, and IME setup launcher |
| [`app/src/main/java/com/example/myasr/VoiceInputMethodService.kt`](../app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) | System-wide Android Input Method Editor (IME) service with context capture |
| [`app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt`](../app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) | Multimodal state arbitrator coordinating live streaming voice, VAD chunks, touch, and continuous swipe gesture input |
| [`app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt) | IME header candidate strip view rendering conversion, voice-correction, and prediction chips |
| [`app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt) | High-level Kotlin wrapper for on-device English QWERTY neural swipe typing with dynamic custom dictionary Trie sync |
| [`app/src/main/java/com/example/myasr/context/provisioning/infrastructure/FutoSwipeDownloader.kt`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/FutoSwipeDownloader.kt) | On-demand downloader and storage provisioner for FUTO Swipe ONNX models and vocabulary |
| [`app/src/main/cpp/swipe/futo_swipe_engine.cc`](../app/src/main/cpp/swipe/futo_swipe_engine.cc) | Native C++ CTC Beam Search Trie decoder (with custom dictionary dynamic merging) and ContextLM reranking engine |
| [`app/src/main/cpp/swipe/futo_swipe_jni.cc`](../app/src/main/cpp/swipe/futo_swipe_jni.cc) | JNI bridge connecting Kotlin `FutoSwipeEngine` to native ONNX Runtime C++ engine |
| [`app/src/main/java/com/example/myasr/context/english/domain/EnglishDictionaryUtils.kt`](../app/src/main/java/com/example/myasr/context/english/domain/EnglishDictionaryUtils.kt) | English word validation, normalization, QWERTY physical key spatial edit distance, and typo candidate generator |
| [`app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt) | SymSpell native/fallback English spelling correction engine with user dictionary synchronization |
| [`app/src/main/cpp/spelling/english_spell_engine.cc`](../app/src/main/cpp/spelling/english_spell_engine.cc) | Native C++ SymSpell dictionary and spell candidate generator |
| [`app/src/main/cpp/spelling/english_spell_jni.cc`](../app/src/main/cpp/spelling/english_spell_jni.cc) | JNI bridge connecting Kotlin `EnglishSpellEngine` to native C++ SymSpell |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt) | Zero-query next-phrase continuation prediction engine (Bigrams, Collocations, Particles) |
| [`app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt`](../app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt) | High-performance on-device SLM re-ranking singleton controller |
| [`app/src/main/cpp/llm_reranker.cc`](../app/src/main/cpp/llm_reranker.cc) | Native C++ KV cache retention, rollback & causal token log-likelihood engine |
| [`app/src/main/cpp/llm_reranker_jni.cc`](../app/src/main/cpp/llm_reranker_jni.cc) | JNI bridge connecting Kotlin `LlmReranker` to native C++ engine |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) | Japanese Kana-Kanji conversion engine driven by Mozc CRF parameters, bigram connection matrix, SLM re-ranking, and custom dictionary |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/ConnectionMatrix.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/ConnectionMatrix.kt) | Memory-mapped 2D Bigram Connection Cost Matrix ($2672 \times 2672$) zero-copy evaluator |
| [`app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ConnectionDataManager.kt`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ConnectionDataManager.kt) | Mozc `connection_single_column.txt` on-demand stream downloader and binary compiler |
| [`app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt) | Context-aware variant resolution, homophone disambiguation & spacing engine for Japanese ASR |
| [`app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt) | Pure-domain user-dictionary prioritization resolver for English voice input (Parakeet) |
| [`app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt) | DMN decision model arbitrating user-dictionary auto-apply, chip suggestion, and common-word collision guards in English ASR |
| [`app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt) | Spoken punctuation conversion, vocabulary protection masking, and automatic sentence-ending period engine |
| [`app/src/main/java/com/example/myasr/AsrApp.kt`](../app/src/main/java/com/example/myasr/AsrApp.kt) | Singleton Application container sharing `AsrManager`, dictionary & `LlmReranker` |
| [`app/src/main/java/com/example/myasr/context/speech/infrastructure/AudioRecorder.kt`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AudioRecorder.kt) | 16 kHz mono 16-bit PCM microphone capture engine |
| [`app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt) | Sherpa-ONNX wrapper for ReazonSpeech / NVIDIA Parakeet offline decoding |
| [`app/src/main/java/com/example/myasr/DictionaryActivity.kt`](../app/src/main/java/com/example/myasr/DictionaryActivity.kt) | Unified Japanese & English custom dictionary management screen (zero-friction in-place add/edit/search) |
| [`app/src/main/java/com/example/myasr/context/vocabulary/presentation/DictionaryAdapter.kt`](../app/src/main/java/com/example/myasr/context/vocabulary/presentation/DictionaryAdapter.kt) | `RecyclerView` adapter for dictionary entries |
| [`app/src/main/java/com/example/myasr/context/vocabulary/domain/DictionaryEntry.kt`](../app/src/main/java/com/example/myasr/context/vocabulary/domain/DictionaryEntry.kt) | Data model for `(Word, Reading, Priority)` |
| [`app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt`](../app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt) | Local JSON persistence (`custom_dictionary.json`) for unified Japanese & English custom dictionary entries |
| [`app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt`](../app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt) | Multi-variant hotword rule generator (Kanji, Hiragana, Katakana) |
| [`app/src/main/java/com/example/myasr/context/speech/application/DictionaryPostProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/application/DictionaryPostProcessor.kt) | Post-decoding phonetic substitution & surface normalizer |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt) | Kana conversion (Hiragana $\leftrightarrow$ Katakana) and validation |
| [`app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ModelDownloader.kt`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ModelDownloader.kt) | On-demand HTTPS model downloader with progress feedback |
| [`app/src/main/res/layout/activity_main.xml`](../app/src/main/res/layout/activity_main.xml) | v2 3-Tab + progressive disclosure layout for `MainActivity` |
| [`app/src/main/res/layout/keyboard_view.xml`](../app/src/main/res/layout/keyboard_view.xml) | Keyboard IME bottom layout (Voice Toggle, Candidate Strip, Flick/QWERTY surface) |
| [`app/src/main/res/xml/method.xml`](../app/src/main/res/xml/method.xml) | Input Method Editor metadata definition |
