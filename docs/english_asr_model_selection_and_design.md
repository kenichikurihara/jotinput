# English On-Device ASR Model Selection & System Design

This document details the model selection, license & performance evaluation, system architecture, and integration plan for adding an **English on-device speech recognition (ASR)** engine to JotInput alongside the existing Japanese ReazonSpeech engine.

---

## 1. Executive Summary

- **Selected Model**: **NVIDIA Parakeet TDT-CTC 110M** (`sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8`)
- **License**: **CC-BY-4.0** (NVIDIA NeMo weights) + **Apache-2.0** (`sherpa-onnx` export/runtime) — permissive for commercial and open-source distribution with attribution.
- **Footprint**: **~126 MB** total (`model.int8.onnx` = 126 MB, `tokens.txt` = 9.8 KB; 2 files only).
- **On-Device Speed**: **RTF ~0.066** on 2 CPU threads (~15x faster than real-time) via FastConformer-CTC non-autoregressive inference.
- **Accuracy & Output Quality**: **2.40% WER** on LibriSpeech `test-clean` (trained on 36,000 hours of English speech), with **native Punctuation and Capitalization (PnC)** and zero autoregressive silence hallucinations.

---

## 2. Candidate Model Comparison

We evaluated open-source English ASR models compatible with JotInput's existing `sherpa-onnx` (v1.13.8) runtime on Android (`arm64-v8a` / `x86_64`, CPU execution provider):

| Model | Architecture | License | Disk Size (int8) | LibriSpeech `test-clean` WER | CPU RTF (2 threads) | Punctuation & Case (PnC) | Hallucination Risk | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`parakeet_tdt_ctc_110m-en-36000-int8`** | **FastConformer-CTC (110M)** | **CC-BY-4.0** | **~126 MB** (2 files) | **2.40%** | **~0.066** | **Yes (Native)** | **None (CTC)** | **Selected** |
| `parakeet-tdt-0.6b-v2-int8` | FastConformer-TDT (600M) | CC-BY-4.0 | ~661 MB (4 files) | ~1.69% | ~0.25–0.45 | Yes (Native) | None (TDT) | Too heavy for mobile IME RAM/disk |
| `moonshine-base-en-quantized` | Transformer Enc-Dec (61.5M) | MIT | ~141 MB (3 files) | ~3.23% | ~0.12–0.20 | Yes (Native) | Moderate (autoregressive) | Slower, higher WER, autoregressive |
| `moonshine-tiny-en-int8` | Transformer Enc-Dec (27.1M) | MIT | ~124 MB (4 files) | ~4.50% | ~0.08–0.15 | Yes (Native) | Moderate (autoregressive) | Higher WER, 4-file graph overhead |
| `zipformer-en-2023-06-26` | Zipformer Transducer | Apache-2.0 | ~70–100 MB | ~2.5% (LS only) | ~0.08 | **No (ALL CAPS)** | None | No punctuation/casing; narrow domain |
| `whisper-tiny.en` / `base.en` | Whisper Enc-Dec (39M/74M) | MIT | ~110–190 MB | ~4.3–5.0% | ~0.35–0.80 | Yes | High (30s padding, loops) | Too slow; 30s fixed padding; loops |

### Why Parakeet TDT-CTC 110M Wins for an Android IME

1. **Non-Autoregressive Single-Pass CTC**:
   - Unlike Whisper or Moonshine, FastConformer-CTC computes emissions in a single forward pass with 8x temporal downsampling.
   - Latency scales linearly with actual audio length (no 30-second zero-padding, no token-by-token autoregressive decoder loop, no KV-cache RAM allocation).
   - Cannot enter infinite repetition loops or hallucinate phantom phrases on silence/background noise.
2. **IME-Ready Native Punctuation & Capitalization (PnC)**:
   - Trained on 36,000 hours of diverse English speech with punctuation and capitalization targets (`Well, I don't wish to see it any more, observed Phoebe.`), eliminating the need for a separate English punctuation restoration model.
3. **Balanced Mobile Footprint**:
   - At **126 MB** (smaller than the 169 MB Japanese ReazonSpeech model), it coexists comfortably in memory alongside `KanaKanjiEngine` and `ReazonSpeech` on modern Android devices.
4. **Permissive Commercial-Friendly License**:
   - `nvidia/parakeet-tdt_ctc-110m` is licensed under **CC-BY-4.0**, allowing commercial and open-source distribution with proper attribution in JotInput's OSS Licenses dialog.

---

## 3. Architecture & Domain Design

### 3.1 Bounded Context Placement (DDD Compliance)

- **`context/speech/domain/` (Pure Kotlin, zero Android UI/infra dependencies)**:
  - `AsrLanguage.kt`: Enum defining `JAPANESE` and `ENGLISH`, plus pure routing helper `AsrLanguagePolicy.resolveLanguage(isEnglishKeyboardMode: Boolean, isEnglishModelAvailable: Boolean): AsrLanguage`.
  - `EnglishVoicePostProcessor.kt`: Pure domain post-processor for English ASR transcriptions:
    - Trims leading/trailing whitespace.
    - Preserves Parakeet's native capitalization and punctuation.
    - Smart boundary spacing: prepends a single ASCII space (`" "`) when `textBeforeCursor` ends with an alphanumeric character or sentence/clause punctuation (`.`, `,`, `!`, `?`, `:`, `;`, `)`, `]`, `"`, `'`) without trailing whitespace.
    - Word-boundary-aware streaming prefix freeze (`extractWordAwareFreezePrefix`): computes longest common prefix (LCP) across recent streaming steps, protects a trailing active horizon, and snaps freeze boundaries to whitespace so partial words are never frozen mid-word.
- **`context/speech/infrastructure/`**:
  - `AsrManager.kt`: Manages both Japanese (`OfflineTransducerModelConfig` for ReazonSpeech) and English (`OfflineNemoEncDecCtcModelConfig` for Parakeet TDT-CTC 110M) `OfflineRecognizer` instances independently.
    - Storage directory for English model: `context.getExternalFilesDir(null)/parakeet-en-110m/` containing `model.int8.onnx` and `tokens.txt`.
    - Supports `isModelAvailable(language)`, `initialize(language, hotwordsFile)`, `warmUp(language, hotwordsFile)`, `transcribe(samples, sampleRate, hotwords, language)`, and `release(language?)`.
    - English recognizer config:
      - `OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(model = ...), tokens = ..., numThreads = getOptimalThreadCount(), provider = "cpu")`
      - `OfflineRecognizerConfig(modelConfig = ..., decodingMethod = "greedy_search")`
- **`context/provisioning/infrastructure/`**:
  - `EnglishAsrModelDownloader.kt`: Downloads `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2` from the official `k2-fsa/sherpa-onnx` GitHub release over HTTPS, streams through `BZip2CompressorInputStream` + `TarArchiveInputStream` (via `commons-compress`), and atomically writes `model.int8.onnx` and `tokens.txt` into `parakeet-en-110m/`.
- **`context/provisioning/domain/`**:
  - `ModelWarmUpManager.kt`: Warms up the English ASR model alongside Japanese ASR during background initialization when installed.

### 3.2 Language Routing in `VoiceInputMethodService`

```
Current Keyboard Mode (FlickKeyboardView.currentMode)
  │
  ├── QWERTY / QWERTY_SYMBOLS / ALPHABET
  │     ├── English ASR installed? ── YES ──> AsrLanguage.ENGLISH (Parakeet 110M)
  │     │                                      └── EnglishVoicePostProcessor
  │     └── English ASR missing?   ── NO  ──> Fallback: AsrLanguage.JAPANESE (ReazonSpeech)
  │                                            └── ContextAwareProcessor (Japanese)
  │
  └── KANA / NUMBER
        └── AsrLanguage.JAPANESE (ReazonSpeech)
              └── ContextAwareProcessor (Japanese)
```

---

## 4. Storage-First & UI Simplicity Compliance

- **APK Size Contract**: Zero ONNX files are bundled in `app/src/main/assets/`. The English model (~126 MB) is strictly download-on-demand to external app storage (`parakeet-en-110m/`).
- **4-Card Top-Level Cap (`MainActivity`)**:
  - No new top-level card is added to `activity_main.xml`.
  - A compact `cardEnglishAsr` subsection is placed inside the collapsible **Card 3 (`layoutDictDetailsContainer`)**, collapsed by default, alongside `cardEnglishSpelling`.
  - Includes progress bar, status label, Download/Re-download button, and Delete button.
  - **OSS Attribution**: `MainActivity.showOssLicensesDialog()` includes NVIDIA Parakeet TDT-CTC 110M (`CC-BY-4.0`) attribution.

---

## 5. Verification Plan

1. **Unit Tests**:
   - `EnglishVoicePostProcessorTest`: Tests whitespace normalization, smart leading space insertion after words/punctuation, empty/blank handling, and word-boundary-aware streaming prefix freeze.
   - `AsrLanguagePolicyTest`: Tests routing between `JAPANESE` and `ENGLISH` based on keyboard mode and model availability.
   - `DddArchitectureKonsistTest`: Ensures all new `domain` classes remain pure (no `infrastructure`, `presentation`, or Android UI imports).
   - `JapanesePunctuationEvaluationTest`: Ensures 100% pass (zero regression on Japanese voice punctuation).
2. **Build & CI**:
   - Verify `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass cleanly on GitHub Actions CI (`Android CI`).
