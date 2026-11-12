---
name: myasr-debug
description: >-
  Interactive playbook, diagnostic recipes, and systematic procedures for debugging JotInput's
  Android IME, ASR engine, Mozc Viterbi Kana-Kanji conversion, on-device SLM (llama.cpp) reranking,
  English typo/spelling pipelines, and native NDK C++ models across ARCVM and physical phone targets.
---

# JotInput Systematic Debugging Playbook & Diagnostic Guide

This skill documents standard diagnostic workflows, command-line recipes, logcat filters, and root-cause analysis procedures for debugging issues across all subsystems in **JotInput** (Japanese On-device Text Input).

Use this skill whenever investigating:
- Unexpected candidate rankings (e.g. `"I wi"` $\to$ `"UI"`, `けっか` $\to$ `欠課`)
- IME layout rendering, touch/flick handling, or window inset geometry anomalies
- On-device SLM / LLM logit calculation or BPE tokenization mismatches
- Speech recognition (ASR / VAD) transcription stalls or latency spikes
- Dual-target deployment failures (ARCVM container vs. physical Pixel 10 Pro over Wi-Fi)
- Native C++ crashes, memory faults, or NDK ABI issues (`libmyasr_llm.so`, `libenglish_spell.so`)

--------------------------------------------------------------------------------

## 🧭 Core Debugging Principles (Non-Negotiable)

1. **Anti-Anecdotal Tuning Principle (AGENTS.md / GEMINI.md)**:
   - **Never apply ad-hoc cost patches, manual dictionary cost edits, or arbitrary `if (len <= 2)` heuristics** to fix isolated candidate anomalies.
   - Ground all solutions in probability theory:
     - **Observation Probability**: Continuous normalized spatial error $D_{\text{obs}}(X, W) = d(X, W) / \max(|X|, |W|)$.
     - **Prior / Transition Probability**: Corpus-derived Mozc CRF transition weights and SLM conditional log-likelihood $P(W \mid C)$.
2. **Dual-Target Verification Principle**:
   - Always verify behavior on **both** ARCVM (`arc:5555`) and the physical phone (`Pixel 10 Pro`).
   - ARCVM uses hardware key tunneling; physical phone exercises touch/flick insets and wireless adb.
3. **Mandatory Non-Regression Contract**:
   - Always run `./gradlew testDebugUnitTest` and ensure 100% pass (especially [`JapanesePunctuationEvaluationTest`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt), [`LmFirstJointEvaluationTest`](app/src/test/java/com/example/myasr/LmFirstJointEvaluationTest.kt), and [`EnglishTypoRerankTest`](app/src/test/java/com/example/myasr/EnglishTypoRerankTest.kt)).

--------------------------------------------------------------------------------

## 🔍 Diagnostic Recipes by Symptom

### 1. Candidate Ranking Anomalies

#### Symptom A: An unexpected word is promoted to #1 (e.g. `"I wi"` $\to$ `"UI"`)
- **Pipeline Stages to Trace**:
  1. **User Dictionary Match** ([`EnglishDictionaryUtils.suggestFromUserDictionary`](app/src/main/java/com/example/myasr/EnglishDictionaryUtils.kt)):
     - Did a fuzzy/typo search return an unrelated entry?
     - Check normalized observation error: $D_{\text{obs}}(X, W) = d_{\text{spatial}}(X, W) / \max(|X|, |W|)$.
     - If $D_{\text{obs}} > 0.38$, candidate must be rejected by relative cost normalization.
  2. **English Spell Engine Suggestions** ([`english_spell_engine.cc`](app/src/main/cpp/spelling/english_spell_engine.cc)):
     - Check whether prefix completions (`w.rfind(typed, 0) == 0`) or typo matches (`cost <= 3.6f && norm_cost <= 0.38f`) generated the candidate pool.
  3. **Contextual SLM Conditional Log-Likelihood** ([`LlmReranker.cc`](app/src/main/cpp/llm_reranker.cc)):
     - Check $-\log P(W \mid C)$ on target device using the on-device logit evaluation CLI or logcat.
     - Verify space-adaptive BPE tokenization: does context end with space? If not, did BPE evaluate the leading-space token (e.g. `' will'` = 686 vs `'will'` = 14387)?
  4. **Intra-Word Surprisal Clamping / User Boost Guard**:
     - Verify whether `isExactUser` was erroneously applied. User boost and NLL floor clamping are strictly reserved for exact matches (`candidate.obsPenalty == 0.0f`). Fuzzy user matches must compete on raw Bayesian likelihood.

#### Symptom B: A common Japanese word loses to an obscure reading (e.g. `けっか` $\to$ `欠課` instead of `結果`)
- **Pipeline Stages to Trace**:
  1. **Mozc Bigram Connection Cost**:
     - Check left/right IDs (`lid`/`rid`) and bigram transition score from Mozc matrix (`connection_data.bin`).
  2. **Intra-Word Surprisal Clamping (ADR-018)**:
     - Check if SLM out-of-vocabulary penalty disproportionately penalized common kanji morphemes.
     - Verify $sysBestNll$ floor clamping in [`LlmReranker.kt`](app/src/main/java/com/example/myasr/LlmReranker.kt).

#### Quick On-Device Logit Inspection Recipe
To directly test on-device SLM log-likelihoods for any context and candidate set without recompiling:
```bash
# Push a quick standalone probe or run in-process unit test:
./gradlew testDebugUnitTest --tests "com.example.myasr.EnglishTypoRerankTest"
./gradlew testDebugUnitTest --tests "com.example.myasr.LmFirstJointEvaluationTest"
```

---

### 2. Live Android IME & UI Debugging

#### Symptom: UI does not update after deployment, or appears to show old layout
- **Root Cause (Finding 38)**: Android task reuse on `adb shell am start` without `am force-stop` brings the existing activity to front without re-inflating views.
- **Resolution**:
  ```bash
  DEV="<device_id>"  # e.g. arc:5555 or <device-ip>:32827
  adb -s "$DEV" shell am force-stop com.example.myasr
  adb -s "$DEV" shell am start -n com.example.myasr/.MainActivity
  ```
  *(Note: `./deploy.sh` automatically performs `force-stop` before `start`).*

#### Checking IME Window Focus and Display Geometry
```bash
# Check current window focus and top activity:
adb -s "$DEV" shell dumpsys window displays | grep -E "mCurrentFocus|mFocusedApp"

# Check IME window visibility and frame bounds:
adb -s "$DEV" shell dumpsys window windows | grep -A 10 "InputMethod"
```

#### Visual Verification via On-Device Screenshot Capture
```bash
# Capture screenshot from device and pull to local artifacts directory:
adb -s "$DEV" shell screencap -p /sdcard/screen.png
adb -s "$DEV" pull /sdcard/screen.png /tmp/device_screen.png
```

---

### 3. Logcat Filtering & Key Diagnostic Tags

Always filter logcat by subsystem tags for high signal-to-noise ratio:

```bash
# Core ASR, IME arbitrator, SLM and Dictionary diagnostics:
adb -s "$DEV" logcat -c  # Clear buffer
adb -s "$DEV" logcat -s AsrApp,VoiceInputMethod,LlmReranker,KanaKanjiEngine,EnglishSpellEngine,AsrManager

# Monitor SLM candidate re-ranking scores in real time:
adb -s "$DEV" logcat -s LlmReranker:D VoiceInputMethod:I | grep -E "English candidates|rerank|score"

# Monitor Speech-to-Text streaming audio & VAD transitions:
adb -s "$DEV" logcat -s AsrManager:D VoiceTouchArbitrator:D AudioRecorder:D
```

#### Key Log Messages to Look For:
- `LlmReranker: SLM model loaded successfully and ready for re-ranking.` $\implies$ Native llama.cpp GGUF model loaded.
- `LlmReranker: Context prefix '...' cached (tokens=N, hash=...)` $\implies$ Prefix KV-cache reused without full re-computation.
- `English candidates for 'wi' (context='I ', slm=true): [will, with, wish, win, wide]` $\implies$ Contextual SLM reranker executed successfully.
- `KanaKanjiEngine: KanaKanjiEngine warm-up completed successfully.` $\implies$ Mozc Viterbi & system dictionary memory mapped cleanly.

---

### 4. Wireless ADB Connection Drops & Port Recovery

#### Symptom: Physical phone reports `Connection refused` or `No route to host`
- **Root Cause (Finding 39)**: Android Doze mode shuts down Wi-Fi listening sockets or dynamically reallocates wireless debugging ports when the screen goes dark.
- **Resolution Recipe**:
  1. **Wake up the phone screen**:
     ```bash
     adb -s <device-ip>:<port> shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
     ```
  2. **Scan active wireless debugging port on device IP**:
     [`deploy.sh`](deploy.sh) includes an automated fast port scanner across ports 30000–50000. You can run:
     ```bash
     ./deploy.sh
     ```
     Or manually find the open wireless port:
     ```bash
     python3 -c "
     import socket
     ip = '<device-ip>'
     for port in range(30000, 50000):
         s = socket.socket()
         s.settimeout(0.08)
         if s.connect_ex((ip, port)) == 0:
             print(f'Active ADB Port found: {port}')
             s.close()
             break
         s.close()
     "
     ```
  3. Reconnect:
     ```bash
     adb connect <device-ip>:<discovered_port>
     ```

---

### 5. Native C++ & NDK Debugging (`libmyasr_llm.so`, `libenglish_spell.so`)

#### Diagnosing SIGSEGV / Native Crashes
When native crashes occur, extract the tombstone or symbolized backtrace from logcat:
```bash
adb -s "$DEV" logcat -b crash -d
# Or filter logcat for DEBUG tombstones:
adb -s "$DEV" logcat -s DEBUG | head -n 50
```

#### llama.cpp JNI Native Crash (`SIGABRT` / `ggml_abort` / `seq_cp`) Diagnostic Recipe
- **Symptom**: App crashes instantly on typing or re-ranking with `Fatal signal 6 (SIGABRT)` in `libc.so` (`abort+205` $\to$ `ggml_abort` $\to$ `llama_kv_cache::seq_cp`).
- **Diagnosis**:
  ```bash
  # Check native crash backtrace in crash logcat buffer:
  adb -s "$DEV" logcat -b crash -d | head -n 40
  ```
- **Root Cause (Finding 42)**: `llama.cpp`'s `seq_cp()` contains an internal invariant assertion: `GGML_ASSERT(is_full && "seq_cp() is only supported for full KV buffers")`. Calling `seq_cp()` on a subrange ($p_1 < \text{get\_size}()$) intentionally aborts the process.
- **Resolution Principle**: Never use `llama_memory_seq_cp()` for speculative candidate branch evaluation in mobile IMEs. Keep all evaluations on sequence 0, evaluate multi-token candidates with admissible lower-bound pruning, and roll back cleanly via `llama_memory_seq_rm(mem, 0, start_pos, -1)`.

#### 16 KB Page Alignment Verification (Android 15+)
All native `.so` binaries must be aligned to 16 KB pages (`-Wl,-z,max-page-size=16384`):
```bash
# Verify ELF segment alignment:
readelf -l app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a/libmyasr_llm.so | grep -A 2 LOAD
```
Ensure `Align` field is `0x4000` (16,384 bytes).

#### Verifying Storage Assets Existence
Neural models and large dictionaries are loaded from `context.getExternalFilesDir(null)`:
```bash
adb -s "$DEV" shell ls -lh /storage/emulated/0/Android/data/com.example.myasr/files/models/
adb -s "$DEV" shell ls -lh /storage/emulated/0/Android/data/com.example.myasr/files/mozc/
```

---

## 📋 Comprehensive Pre-Push Checklist

Before committing or pushing any fix:
- [ ] `./gradlew testDebugUnitTest` passes 100% (zero failures across all 67+ punctuation and dictionary tests).
- [ ] Verified deployment to ARCVM container (`arc:5555`) via `./deploy.sh`.
- [ ] Verified deployment to Physical Phone (`Pixel 10 Pro`) via `./deploy.sh`.
- [ ] APK synced to Google Drive (`JotInput/app-debug.apk`) for phone sideloading.
- [ ] Architectural decisions or post-mortems documented in [`docs/findings_and_decisions.md`](docs/findings_and_decisions.md).
- [ ] Changes committed and pushed to `origin/main`.
