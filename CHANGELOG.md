# Changelog

All notable changes to **JotInput** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0]

Initial public open-source release candidate of **JotInput** (`io.github.kenichikurihara.jotinput`), an offline on-device Japanese & English voice + flick/QWERTY Android keyboard (IME).

### Added
- **On-Device Japanese & English Speech Recognition (ASR)**:
  - Offline Japanese Zipformer transducer ASR (`reazonspeech-k2-v2` int8 via `sherpa-onnx`, `AsrManager`) with real-time `StreamingVadProcessor` sentence-boundary segmentation.
  - Spoken Japanese voice punctuation engine (`JapanesePunctuationEngine`) with vocabulary false-positive protection and micro-pause / sentence-boundary commit guardrails (`JapanesePunctuationEvaluationTest`).
  - Optional on-device English ASR using NVIDIA Parakeet TDT-CTC 110M via `EnglishAsrModelDownloader`.
- **Japanese Flick / QWERTY Keyboard & Mozc Kana-Kanji Engine**:
  - System-wide `VoiceInputMethodService` and `FlickKeyboardView` supporting Japanese flick, QWERTY, symbol, and numpad layouts plus in-strip clipboard paste and dictionary shortcuts.
  - Mozc-derived Viterbi Kana-Kanji conversion (`KanaKanjiEngine`) with bundled 2D grammatical connection cost matrix (`app/src/main/assets/mozc/connection_data.bin`, ~14.28 MB) and on-demand HTTPS downloaders (`MultiDictionaryManager`, `BaseDictionaryDownloader`) for Mozc base, SudachiDict, jawiki, place/personal names, and emoji lexicons.
- **English Neural Swipe Typing & Spatial Typo Correction**:
  - Native C++ ONNX Runtime swipe decoder (**Powered by FUTO Swipe**, `FutoSwipeEngine`, `FutoSwipeDownloader`) for continuous QWERTY gesture typing.
  - Spatial edit-distance / SymSpell English correction (`EnglishSpellEngine`, `EnglishSpellDownloader`) using `gl-keyboard-correction` frequency data.
- **Optional On-Device SLM Candidate Re-Ranking**:
  - `LlmReranker` (`llama.cpp`) with in-app HTTPS GGUF downloader (`SlmModelDownloader`) for optional Qwen3.5 GGUF models.
- **Unified Japanese & English Custom Dictionary**:
  - Unified persistent JSON store (`custom_dictionary.json`), guaranteed empty on fresh installs and preserved across app updates.
  - Synchronizes custom Japanese terms into ASR hotword biasing/phonetic replacement and custom English words into the native FUTO Swipe Trie and spatial typo candidates (`DictionaryActivity`).
- **Setup Hub, Architecture Guardrails & Release Pipeline**:
  - 3-tab `MainActivity` (Home / Settings / Data) with DMN-driven setup guidance and formally verified state transitions (`AlloyVerificationTest`), plus automated boundary tests (`DddArchitectureKonsistTest`).
  - Signed Release APK (~29 MB), R8 optimization (`proguard-rules.pro`), 16KB ELF page alignment check for `arm64-v8a`, `SHA256SUMS.txt` generation, and automated CI/CD workflows (`.github/workflows/android-ci.yml`, `.github/workflows/release.yml`).
  - OSS legal & governance files: Apache License 2.0 ([`LICENSE`](LICENSE)), upstream attributions ([`NOTICE`](NOTICE)), bilingual privacy policy ([`PRIVACY.md`](PRIVACY.md)), [`CONTRIBUTING.md`](CONTRIBUTING.md), and [`SECURITY.md`](SECURITY.md).

### Security
- Disabled Android cloud/ADB backup (`android:allowBackup="false"`) and set `DictionaryActivity` `android:exported="false"`.
- Restricted `REQUEST_INSTALL_PACKAGES` and the GitHub Actions debug-artifact self-updater (`!BuildConfig.DEBUG`) strictly to debug builds.
- Removed legacy debug keystore artifacts and sanitized repository history prior to public release.

### Known Limitations
- Supported ABIs are `arm64-v8a` and `x86_64` on Android 8.0+ (API 26+); 32-bit ABIs (`armeabi-v7a`, `x86`) are not supported.
- Maintainer hardware verification is limited to **Google Pixel 10 Pro** and **ChromeOS ARCVM**; other Android OEM devices are community-supported (Tier 2).
- Release builds do not auto-update in-app; download new signed APKs from GitHub Releases.
