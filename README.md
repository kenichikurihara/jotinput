# JotInput - On-Device Japanese & English Voice + Flick/QWERTY Keyboard for Android

> **JotInput** = **J**apanese **O**n-device **T**ext **Input**

A lightweight, 100% offline, on-device Android Japanese & English voice + flick/QWERTY keyboard (IME) powered by **ReazonSpeech**, **NVIDIA Parakeet**, and **Sherpa-ONNX**, combined with a Mozc-based Kana-Kanji keyboard, neural swipe typing (**Powered by FUTO Swipe**), and optional on-device **Qwen3.5** SLM candidate re-ranking (`llama.cpp`).

- **License**: [Apache License 2.0](LICENSE) · [Third-Party Notices (`NOTICE`)](NOTICE)
- **Privacy & Security**: [Privacy Policy (`PRIVACY.md`)](PRIVACY.md) · [Security Policy (`SECURITY.md`)](SECURITY.md)
- **Contributing & Releases**: [Contributing Guide (`CONTRIBUTING.md`)](CONTRIBUTING.md) · [Changelog (`CHANGELOG.md`)](CHANGELOG.md)

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

## 📱 Supported Devices (Support Tiers)

JotInput is developed by a **solo maintainer** who only owns **Google Pixel 10 Pro** and **ChromeOS ARCVM** hardware:

| Tier | Scope | Support Level |
| :--- | :--- | :--- |
| **Tier 1 (Primary)** | **Google Pixel 10 Pro** (physical device) & **ChromeOS ARCVM** (`x86_64`), Android 8.0+ (API 26+) | Maintainer-verified. Regressions block releases. |
| **Tier 2 (Best-Effort)** | Other `arm64-v8a` / `x86_64` devices running stock/AOSP-compatible Android 8.0+ | Community-supported via standard Android APIs (requires logs/screenshots/tests in PRs; see [`CONTRIBUTING.md`](CONTRIBUTING.md)). |
| **Tier 3 (Unsupported)** | 32-bit ABIs (`armeabi-v7a`, `x86`), Android `< 8.0`, custom-ROM hacks, or OEM proprietary workarounds | Not supported. |

---

## 🌟 Features

- **100% On-Device & Zero Telemetry**: Audio, typed keystrokes, clipboard previews, and custom dictionaries never leave your device. No analytics, ad SDKs, or cloud APIs ([`PRIVACY.md`](PRIVACY.md)).
- **Japanese & Optional English Speech Recognition**:
  - **Japanese ASR**: ReazonSpeech `reazonspeech-k2-v2` Zipformer int8 via `sherpa-onnx` (~169 MB extracted model files) with streaming VAD (`StreamingVadProcessor`) and spoken Japanese punctuation (`JapanesePunctuationEngine`) with vocabulary false-positive protection.
  - **English ASR (Optional)**: On-demand download of **NVIDIA Parakeet TDT-CTC 110M INT8** (`EnglishAsrModelDownloader`, ~126 MB extracted).
- **Mozc-Based Kana-Kanji Keyboard & On-Demand Lexicons**:
  - System-wide `VoiceInputMethodService` with flick / QWERTY / symbol / numpad layouts, header clipboard paste, and dictionary shortcut.
  - Viterbi Kana-Kanji conversion (`KanaKanjiEngine`) using a compact bundled 2D bigram connection matrix (`connection_data.bin`, ~14.28 MB) plus on-demand HTTPS downloads for Mozc base, SudachiDict, jawiki, place/personal name, and emoji dictionaries (`MultiDictionaryManager`, `BaseDictionaryDownloader`).
- **Neural Swipe Typing & English Typo Correction**:
  - **Powered by FUTO Swipe**: Cleanroom ONNX Runtime native C++ integration of FUTO Swipe spatial 1D-CNN/DFSMN + ContextLM models (`FutoSwipeEngine`, `FutoSwipeDownloader`) for fast QWERTY gesture typing with automatic inter-word spacing and single-tap gesture-word deletion.
  - **Spatial Typo Correction**: `EnglishSpellEngine` (`gl-keyboard-correction` / SymSpell, `EnglishSpellDownloader`) surfaces English spelling corrections in the candidate strip.
- **Optional On-Device SLM Candidate Re-Ranking**:
  - `LlmReranker` (`llama.cpp`) supports quantized **Qwen3.5-0.8B / 2B GGUF** models downloaded directly in-app via `SlmModelDownloader` (or pushed with `tools/push_model_to_device.sh`).
- **Unified Japanese & English Custom Dictionary**:
  - Persistent unified JSON repository (`custom_dictionary.json`, empty by default and preserved across upgrades; `android:allowBackup="false"`).
  - Entering an English reading automatically collapses the surface field; custom entries synchronize into ASR hotword biasing/phonetic replacement and the native C++ FUTO Swipe Trie + spatial typo candidates.
- **Compact APK & Modular Download-Only Architecture**:
  - Signed Release APK is **~29 MB** (Debug APK is **~36 MB**), compiled for `arm64-v8a` & `x86_64` with **16KB ELF page alignment** (`arm64-v8a`).
  - Only `app/src/main/assets/mozc/connection_data.bin` (~14.28 MB) is bundled in the APK; heavy neural models and large multi-dictionaries load from device storage via in-app HTTPS downloaders across `MainActivity`'s 3 tabs (`Home` / `Settings` / `Data`).

---

## 🏗️ Architecture

```mermaid
flowchart LR
    Mic[Microphone 16kHz PCM] --> Rec[AudioRecorder / StreamingVadProcessor]
    Rec --> Asr[AsrManager: sherpa-onnx Recognizer<br/>ReazonSpeech JA / Parakeet EN]
    Asr --> Post[DictionaryPostProcessor + JapanesePunctuationEngine]
    Post --> IME[VoiceInputMethodService]
    Keys[FlickKeyboardView] --> Kana[KanaKanjiEngine]
    Kana --> SLM[LlmReranker (Qwen3.5-0.8B / 2B GGUF)]
    SLM --> IME
    Kana --> IME
    IME --> App[Target app text field]
    Main[MainActivity: Home / Settings / Data] -.-> Asr
```

See [System Architecture](docs/architecture.md) for details.

---

## 📥 Install v0.1.0 Release APK (End Users)

> Official package ID: `io.github.kenichikurihara.jotinput` (Android 8.0+ / API 26+, `arm64-v8a` or `x86_64`).

1. Download `app-release.apk` and `SHA256SUMS.txt` from [GitHub Releases](https://github.com/kenichikurihara/jotinput/releases).
2. *(Recommended)* Verify the download integrity and APK signature before installing:
   ```bash
   sha256sum -c SHA256SUMS.txt
   apksigner verify --print-certs app-release.apk
   ```
3. Transfer `app-release.apk` to your Android device and allow **Install unknown apps** when prompted to complete installation (or install via USB: `adb install -r app-release.apk`).
4. Open **JotInput**, grant microphone permission, and follow the **Home** / **Data** tabs to download the speech model and dictionaries over HTTPS.
5. Enable **JotInput** in Android Language & Keyboard settings and switch the active input method to **JotInput**.

> **Note**: Release builds update manually from GitHub Releases (do not upgrade between Debug and Release builds without uninstalling, as they use different signing certificates).

---

## 🛠️ Build & Test from Source (Developers)

### Prerequisites
- Android device or emulator running **Android 8.0 (API 26)** or higher (`arm64-v8a` or `x86_64`; 32-bit ABIs are not supported).
- Android SDK (`platform-tools`) and NDK 26.3 (for native `llama.cpp` / C++ engines).

### Build, Unit Tests & Provisioning
```bash
# Run unit, punctuation, Konsist DDD, and Alloy 6 formal verification tests
./gradlew testDebugUnitTest

# Build Debug APK (app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# Optional: pre-fetch models into tools/models/ and deploy Debug build to connected devices
./download_model.sh
./deploy.sh
```

> Release builds (`./gradlew assembleRelease`) produce an unsigned APK unless release signing variables/properties are configured locally (see [`SECURITY.md`](SECURITY.md) and [`app/build.gradle.kts`](app/build.gradle.kts)).

---

## 📱 How to Use

1. Launch **JotInput** and grant microphone permission when prompted.
2. Download the ReazonSpeech model and Mozc dictionaries from the **Home** / **Data** tabs (optional English ASR, FUTO Swipe, English Spell, and Qwen3.5 GGUF SLM models are also available on demand).
3. Tap **音声キーボードを有効にする** to enable the keyboard, then switch the active IME to **JotInput**.
4. Tap any text field (such as the in-app **キーボードテスト** box) and use the keyboard:
   - Tap **🎙 話す** in the header, speak, and tap **完了** (or use streaming mode for hands-free sentence-boundary commits with voice punctuation).
   - Type with the flick/QWERTY keyboard or swipe on the English QWERTY layout and select candidates from the header strip.
5. Register custom Japanese or English words via the **Dict** shortcut or **Data** tab.

---

## 📄 License, Privacy & Contributing

- Licensed under the [**Apache License 2.0**](LICENSE).
- Third-party software, model weights, and lexicon attributions (including **"Powered by FUTO Swipe"**) are documented in [**`NOTICE`**](NOTICE).
- Privacy details are documented in [**`PRIVACY.md`**](PRIVACY.md); vulnerability reporting is in [**`SECURITY.md`**](SECURITY.md).
- Before opening a PR, please read [**`CONTRIBUTING.md`**](CONTRIBUTING.md) for solo-maintainer expectations, Issue-First rules, and multi-device evidence requirements.
