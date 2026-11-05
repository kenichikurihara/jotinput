# IME Keystroke Latency Offline Evaluation & Performance Architecture

## 1. Executive Summary & Observed Problem

### 1.1 Observed Issue on Physical Phone (Pixel 10 Pro: `57231FDCH00415`)
During live interaction with the physical phone, typing on the soft keyboard resulted in severe UI freezes:
```text
WindowManager: ANR in Window{b3f72a1 u0 InputMethod}. Reason: Input dispatching timed out (Waited 5001ms for MotionEvent).
Choreographer: Skipped 275 frames! The application may be doing too much work on its main thread.
HWUI: Davey! duration=4603ms
HWUI: Davey! duration=1224ms; Skipped 73 frames!
InputDispatcher: b3f72a1 InputMethod spent 4593ms processing MotionEvent
```

### 1.2 Root Cause Decomposition
The slowness is caused by three compounding bottlenecks executing synchronously on the **Android Main UI Thread**:

1. **Synchronous SLM Candidate Scoring on Main Thread**:
   In `VoiceInputMethodService.kt`, `onKeyClicked` calls `updateCandidates()` synchronously on the UI thread, which calls:
   $$\text{onKeyClicked} \longrightarrow \text{KanaKanjiEngine.getCandidates()} \longrightarrow \text{LlmReranker.rerank()} \longrightarrow \text{nativeScoreCandidates()}$$
   Even for a 0.5B parameter quantized model, running llama context prefill and multi-token decoding on the mobile CPU takes 100–1200 ms. Doing this on the UI thread starves Choreographer and Android's `InputDispatcher`, causing immediate dropped frames and ANRs.

2. **Unbounded Candidate Pool Size ($K=50$)**:
   Up to 50 candidates are extracted from the in-memory Trie and SQLite, and all 50 strings are passed across JNI into `nativeScoreCandidates`. Tokenizing and evaluating up to 50 candidates on CPU causes unnecessary tokenization overhead and multi-token evaluation passes, when the candidate strip only renders 4–8 items horizontally.

3. **Synchronous Dictionary Ingestion on First Touch**:
   On first keystroke, `initializeIfNeeded()` was running on the main thread, parsing TSVs and decompressing GZIP files into Kotlin Trie memory.

---

## 2. Latency Budget & Target Metrics

| Component | Current State | Target Budget | Thread Context |
| :--- | :--- | :--- | :--- |
| **Touch Event Handling & Composing Region** | Blocked | $< 2\text{ ms}$ | **Android Main Thread** |
| **Instant Candidate Strip Display** | Blocked | $< 5\text{ ms}$ | **Android Main Thread** (In-Memory Trie) |
| **SLM Re-ranking Inference** | Synchronous ($1224\text{ ms}$) | $< 25\text{ ms}$ | **Worker Coroutine (`Dispatchers.Default`)** |
| **Stale Keystroke Cancellation** | None (Queued) | $< 1\text{ ms}$ | **Coroutine `Job.cancel()`** |
| **Frame Rate under Continuous Typing** | 0–15 fps (Davey spikes) | **60–120 fps** (0 dropped frames) | **SurfaceFlinger / HWUI** |

---

## 3. Offline Reproduction & Measurement Plan (Linux/Crostini Environment)

To iterate rapidly and benchmark without relying on a tethered phone, we establish a **two-layer offline test suite** directly in the workstation environment:

### Layer 1: Native C++ Keystroke Benchmark Harness (`tools/bench_ime_latency.cc`)
A standalone native Linux binary utilizing `./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf` to benchmark:
1. **Typing Simulation**:
   Simulates rapid typing sequences (inter-keystroke intervals: 50ms, 100ms, 200ms) for real-world sentences (e.g. `こ` $\to$ `こと` $\to$ `ことな` $\to$ `ことなり` $\to$ `ことなります`).
2. **Micro-Benchmarking Metrics**:
   - $T_{\text{prefill}}$: Context prefix KV cache prefill time (cold start).
   - $T_{\text{cached}}$: Steady-state KV cache hit latency with Token-0 fast-path.
   - $T_{\text{token0}}$: Token-0 evaluation time across candidate pool sizes $K \in \{4, 8, 16, 32, 50\}$.
   - $T_{\text{multi}}$: Multi-token disambiguation latency.
   - Cache hit ratio across continuous typing.
3. **Cancellation Benchmark**:
   Simulates user typing the next key while inference for the previous key is running, measuring clean cancellation latency.

### Layer 2: JVM Architecture & Coroutine Unit Tests (`app/src/test/java/com/example/myasr/...`)
- Runs via `./gradlew testDebugUnitTest` on Linux.
- Simulates rapid sequential calls to `updateCandidates()`:
  - Asserts that UI-thread time is $< 2\text{ ms}$.
  - Asserts that when 5 keystrokes are fired within 100ms, the first 4 background jobs are cancelled immediately, and only the 5th completes.

---

## 4. Architecture Implementation Plan

### 4.1 Two-Stage Candidate Pipeline
```
[User Taps Key 'こ']
       │
       ▼
(Main Thread) ─────────────────────────────────────────────────────────┐
 1. Update composingText = "こ"                                        │
 2. InputConnection.setComposingText("こ", 1)                          │
 3. baseCandidates = Trie.lookup("こ")   [< 1ms, in-memory]            │
 4. candidateStripView.setCandidates(baseCandidates) [INSTANT 120 FPS] │
 5. previousJob?.cancel()                                              │
 6. launch coroutine on Dispatchers.Default ─────────────────────┐     │
                                                                 │     │
                                                                 ▼     ▼
(Dispatchers.Default Worker Thread)                    UI Thread Remains Free!
 1. Check if reading qualifies for SLM (length >= 2 or homophones exist)
 2. Select top K=8 candidates from baseCandidates
 3. slmScores = nativeScoreCandidates(contextPrefix, top8Surfaces)
 4. Rerank top 8 candidates
 5. If isActive && composingText == currentComposingText:
       withContext(Dispatchers.Main) {
           candidateStripView.updateCandidatesSmoothly(reranked)
       }
```

### 4.2 Candidate Pool Pruning & Adaptive SLM Triggering
1. **Cap Candidate Pool to $K=8$**:
   Since the keyboard strip shows at most 4–8 items at once, only pass the top 8 candidates to `nativeScoreCandidates`.
2. **Skip Trivial Single-Character Kana**:
   When `reading.length == 1`, candidates are dominated by trivial hiragana/katakana mappings. Skip SLM scoring unless an exact user dictionary match is present.
3. **KV Cache Early Exit**:
   In `llm_reranker.cc`, only trigger Stage 2 (multi-token decode) when top-2 candidates share the same first token AND their log-likelihood gap is below threshold. Cap multi-token evaluation to max 2 candidates and max 2 tokens.

### 4.3 Asynchronous Dictionary Preloading
- Move `KanaKanjiEngine.initializeIfNeeded()` to `AsrApp.onCreate()` and `VoiceInputMethodService.onCreate()` inside `CoroutineScope(Dispatchers.IO)`.
- The main thread never loads files or decodes GZIPs.

---

## 5. Iteration & Verification Checklist

1. [ ] Build and run `tools/bench_ime_latency.cc` locally on Linux.
   - Measure latency for $K=8$ vs $K=50$.
   - Confirm steady-state latency $< 2\text{ ms}$ with cache hits.
2. [ ] Implement JVM Unit Test verifying coroutine cancellation and $< 2\text{ ms}$ main-thread return.
3. [ ] Implement Two-Stage Pipeline in `VoiceInputMethodService.kt` and `KanaKanjiEngine.kt`.
4. [ ] Deploy to Pixel 10 Pro (`57231FDCH00415`) and ARCVM (`arc:5555`) via `./deploy.sh`.
5. [ ] Measure logcat on Pixel 10 Pro:
   - Confirm 0 ANRs.
   - Confirm 0 Choreographer skipped frames during rapid typing.
   - Confirm `HWUI: Davey!` eliminated.
