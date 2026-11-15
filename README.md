# JotInput - On-Device Japanese Automatic Speech Recognition & Flick/QWERTY Keyboard for Android

> **JotInput** = **J**apanese **O**n-device **T**ext **Input**

A lightweight, fully offline, on-device Android Japanese voice & flick keyboard (IME) powered by **ReazonSpeech** and **Sherpa-ONNX** (Next-gen Kaldi / ONNX Runtime), combined with a Mozc-based Kana-Kanji flick/QWERTY keyboard and optional on-device SLM candidate re-ranking.

---

## 📖 Documentation

Detailed documentation is available in the [`docs/`](docs/index.md) folder:
- [Top-Level PRD & North Star Metric (EIS)](docs/prd_top_level.md): Product vision, prioritized CUJs, and the single top-level metric (Effective Intended-text Speed) with release guardrails.
- [System Architecture](docs/architecture.md): Audio pipeline, dictionary subsystem, keyboard (IME), SLM re-ranking, component breakdown, and data flow.
- [Custom Dictionary Improvement & Evaluation Plan](docs/dictionary_improvement_plan.md): Root cause post-mortem, space tokenization fix, and 20-case quantitative evaluation benchmark.
- [Custom Dictionary Implementation Plan](docs/dictionary_implementation_plan.md): Step-by-step design of repository, hotwords, post-processing, and UI.
- [Custom Dictionary Solutions](docs/dictionary_solutions.md): Dual-key `(Word, Reading)` architecture, contextual biasing, and homophone resolution for Japanese ASR.
- [Unified English & Japanese Dictionary PRD & Design](docs/prd_and_design_unified_english_dictionary.md): Unified dictionary UI, single-field English entry, FUTO swipe trie synchronization, and spatial misspelling candidate correction.
- [ReazonSpeech Model & On-Device Optimization](docs/reazonspeech_model.md): Zipformer transducer architecture, int8 quantization, and latency/RAM analysis.
- [Setup, Build, and Deployment Guide](docs/setup_and_development.md): Prerequisites, build steps, and ChromeOS ARCVM / device installation.

---

## 🌟 Features

- **100% On-Device & Offline**: No cloud APIs, no external server calls, complete privacy.
- **Unified Custom Dictionary Subsystem (Japanese & English)**:
  - **Bilingual & Unified Storage**: Manage Japanese and English custom vocabulary in a unified JSON repository (`custom_dictionary.json`) with an empty-by-default persistence guarantee.
  - **Effortless English Registration**: Entering English in the reading field automatically collapses the word/surface field—English entries require only a single field while preserving casing in the surface and lowercasing the reading key.
  - **Neural Swipe Trie Synchronization**: Dynamically synchronizes user English words into the native C++ FUTO Swipe Trie in `< 0.5ms` with beam prefix protection and spatial-gated score boosting for Top-1 priority selection.
  - **Spatial Typo & Misspelling Correction**: Misspelled English words typed in QWERTY mode automatically surface user dictionary matches in the candidate strip with a `[USER]` badge based on 2-layer spatial edit distance and SymSpell integration.
  - **In-Beam Acoustic Biasing & Phonetic Substitution**: Generates hotwords for both Kanji surface and Kana readings (Hiragana/Katakana expansion) during Zipformer `modified_beam_search` decoding and replaces phonetic Kana matches in `< 0.5ms`.
  - **Full Dictionary Management UI**: Searchable and editable list with progressive disclosure to add, toggle, or delete terms directly within the app.
- **ReazonSpeech Zipformer Architecture**:
  - Based on `reazonspeech-k2-v2` trained on ~35,000 hours of Japanese audio.
  - Optimized with **int8 quantization** for mobile devices (Encoder: ~147 MB, Decoder: ~11 MB, Joiner: ~10 MB, Vocabulary: 40 KB). Total size is ~169 MB, which runs comfortably within standard mobile RAM limits.
- **Voice Keyboard (IME) & Setup Screen**:
  - `VoiceInputMethodService` is a system-wide keyboard: the header **🎙 話す / 完了** button records 16,000 Hz mono 16-bit PCM and commits the decoded text at the cursor (optionally streaming at sentence boundaries with voice punctuation commands).
  - Gboard-style flick / QWERTY / symbols / numpad keyboard with Kana-Kanji conversion (Mozc-derived dictionaries), next-phrase prediction, clipboard paste and dictionary shortcut in the header.
  - **Neural Swipe Typing on English QWERTY**: Cleanroom ONNX Runtime native C++ integration of FUTO Keyboard's spatial 1D-CNN/DFSMN and ContextLM model, providing continuous gesture typing at ~4.2ms latency with 90.3% Top-1 accuracy, automatic inter-word space management on consecutive swipes, and single-tap entire-word deletion on backspace.
  - `MainActivity` is a setup & settings hub: IME enable/select, quick-test field, keyboard height, streaming voice input, SLM re-rank engine selection, dictionary/model downloads and advanced tuning.
- **Lightweight APK & On-Demand Model/Dictionary Download Architecture**:
  - The APK is optimized for minimal size (~86 MB vs ~261 MB previously, target ≤ 90 MB) with zero models or dictionaries bundled in assets.
  - All models and dictionaries are loaded from device storage. The ReazonSpeech ASR model, the Mozc dictionaries / Connection Matrix, and FUTO Swipe typing models (~12 MB) are downloaded on demand with progress tracking in the app UI and automated provisioning via `deploy.sh`. The optional Qwen2.5 GGUF SLM currently has no in-app downloader and is pushed with `tools/push_model_to_device.sh` (in-app download is still to be implemented per the project policy).

---

## 🏗️ Architecture

```mermaid
flowchart LR
    Mic[Microphone 16kHz PCM] --> Rec[AudioRecorder / StreamingVadProcessor]
    Rec --> Asr[AsrManager: sherpa-onnx OfflineRecognizer<br/>ReazonSpeech Zipformer]
    Asr --> Post[DictionaryPostProcessor + JapanesePunctuationEngine]
    Post --> IME[VoiceInputMethodService]
    Keys[FlickKeyboardView] --> Kana[KanaKanjiEngine]
    Kana --> SLM[LlmReranker (Qwen2.5-0.5B / 1.5B)]
    SLM --> IME
    Kana --> IME
    IME --> App[Target app text field]
    Main[MainActivity: setup, settings, downloads] -.-> Asr
```

See [System Architecture](docs/architecture.md) for details.

---

## 🚀 Getting Started

### Prerequisites
- Android device or emulator running **Android 8.0 (API 26)** or higher with an `arm64-v8a` or `x86_64` processor (32-bit ABIs are not supported).
- Android SDK installed (`android-sdk` with `platform-tools`) and NDK 26.3 for the native re-ranker.

### 1. Model Setup (Download-Only; nothing is bundled in the APK)

#### Option A: Developer provisioning
```bash
./download_model.sh   # fetches ReazonSpeech into tools/models/reazonspeech/
./deploy.sh           # builds, installs, and pushes missing model files to connected devices
```

#### Option B: Download in-app
Install the APK without models. When opened, the app displays a setup card with a **"Download ReazonSpeech Model (~160MB)"** button with a live progress indicator. Dictionaries are downloaded from the dictionary card; the optional Qwen SLM is pushed with `tools/push_model_to_device.sh`.

---

### 2. Build the App

```bash
./gradlew assembleDebug
```

The APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

### 3. Install on Android Device

Connect your phone (USB debugging) and/or ChromeOS ARCVM, then run:

```bash
./deploy.sh
```

or manually:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio** and click **Run**.

---

## 📱 How to Use

1. Launch **JotInput** and grant microphone permission when prompted.
2. Download the model/dictionaries if prompted.
3. Tap **音声キーボードを有効にする** to enable the keyboard, then **入力方法をJotInputに切り替える** to select it.
4. Tap any text field (e.g. the built-in **キーボードテスト** field) and use the keyboard:
   - Tap **🎙 話す** in the header, speak Japanese, then tap **完了**; the transcription is inserted at the cursor.
   - Or type with the flick/QWERTY keyboard and pick conversion candidates from the header strip.
5. Register custom words via the **Dict** shortcut or the **Custom Dictionary** button.
