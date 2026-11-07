# AGENTS.md — JotInput (Japanese On-device Text Input) Agent Instructions

Always follow the policies and rules in [`GEMINI.md`](file:///home/kenichikurihara/repos/myasr/GEMINI.md) and review the architectural history and post-mortems in [`docs/findings_and_decisions.md`](file:///home/kenichikurihara/repos/myasr/docs/findings_and_decisions.md).

## 📱 Mandatory Dual-Target Deployment & Verification

Whenever any code, resource, or configuration change is made to the Android app:
1. **Deploy to BOTH connected targets**:
   - **ARCVM (ChromeOS Android container)**: e.g., `arc:5555`
   - **Physical Phone (Pixel 10 Pro via USB or Wi-Fi)**: e.g., `57231FDCH00415` or `192.168.86.46:<port>`
2. Run [`./deploy.sh`](file:///home/kenichikurihara/repos/myasr/deploy.sh) and confirm in `adb -s <device> logcat` that `MainActivity` launches and the ReazonSpeech model (`AsrManager`) loads cleanly on **both** devices.
3. For any UI or IME window change, verify visual behavior (`screencap`) and window/inset geometry (`dumpsys window windows`) on the physical phone. Never silently skip a target.

## ☁️ Mandatory APK Sideload & Google Drive Sync Contract
Whenever any code, resource, or configuration change is made and the APK is built:
- **Google Drive Sync for Phone Sideloading**:
  - Ensure the compiled debug APK (`app/build/outputs/apk/debug/app-debug.apk`) is synced to Google Drive (e.g., `/mnt/chromeos/GoogleDrive/MyDrive/JotInput/app-debug.apk` or via Google Drive plugin/tool) so the user can immediately sideload and test on their physical phone.
  - [`./deploy.sh`](file:///home/kenichikurihara/repos/myasr/deploy.sh) automatically checks for ChromeOS Google Drive / shared storage mounts (`/mnt/chromeos/GoogleDrive/MyDrive` or `/mnt/chromeos/MyFiles`) and copies `app-debug.apk` directly into the `JotInput/` directory on every build.

## 💾 Custom Dictionary Persistence Contract
- Keep the custom dictionary empty by default on fresh installs.
- Never erase or overwrite `context.filesDir/custom_dictionary.json` across app updates or schema changes.

## 📦 Model & Lexicon Decoupling Contract (Mandatory Download-Only Architecture)
- **Strict Prohibition on Bundling Heavy Models or Large Word Dictionaries in APK**:
  - Never place neural model files (ONNX, GGUF, TFLite, etc.) or multi-million-word dictionary files (SudachiDict, Wikipedia, etc.) inside `app/src/main/assets/` or APK resources.
  - **Connection Matrix Bundling Permitted**: The Mozc 2D bigram grammatical connection cost matrix (`connection_data.bin`, ~14.28 MB) is permitted and required to be bundled in `app/src/main/assets/mozc/connection_data.bin` to guarantee 100% stable morphological transition scores out-of-the-box.
  - The APK must remain compact (containing compiled bytecode, layout/drawables, native `.so` libraries, and the ~14.28 MB connection matrix; target size $\le 110\text{ MB}$).
- **Storage-First & On-Demand Download Pattern for Models and Multi-Dictionaries**:
  - Neural models (ASR, SLM, VAD, etc.) and large lexical dictionaries (SudachiDict, Wikipedia, Place/Personal names) must be loaded from device storage (`context.getExternalFilesDir(null)` or `context.filesDir`).
  - Implement on-demand network downloaders over HTTPS with progress reporting and UI controls in `MainActivity` for large external lexicon assets.
- **Local Testing & Deployment Contract**:
  - Developer/test asset copies must reside under `tools/models/`, `tools/mozc_dict/`, or `app/src/test/resources/` (and large binaries must be excluded in `.gitignore`).
  - [`./deploy.sh`](file:///home/kenichikurihara/repos/myasr/deploy.sh) must automatically check target device storage and provision absent model/lexicon files to app external storage so dual-target deployment and verification succeed seamlessly without bloating the APK.

## 🚫 Anti-Anecdotal Tuning Policy (No Ad-Hoc Cost Modifications)
- **Strict Prohibition on Anecdotal Cost Hacking**: Never manually alter word costs, insert arbitrary words into `registerCanonicalVocabulary()`, or add ad-hoc penalty entries based purely on isolated, anecdotal conversion observations (e.g. manually hacking `うみ` or `いなか` costs).
- **Corpus-Driven Principle**: Lexical costs and dictionary frequencies must be derived systematically from corpus statistics (e.g., Mozc OSS corpus-trained CRF weights or systematic dictionary rebuilds).
- **Language Model Over Hand Tuning**: Semantic disambiguation and contextual choice must be handled through principled language modeling (SLM re-ranking) and systematic dictionary generation, never through hand-coded unigram cost patches.

## 🎯 Top-Line Quality Contract: Voice Punctuation & Instant Sentence Boundary Non-Regression
- **Mandatory 100% Test Pass**: In any code change touching ASR, VAD, punctuation, or IME, always run `./gradlew testDebugUnitTest` and ensure [`JapanesePunctuationEvaluationTest`](file:///home/kenichikurihara/repos/myasr/app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) passes 100% (67+ unit tests).
- **No False-Positive Degradation**: Never weaken vocabulary protection rules for spoken punctuation commands (e.g. 天気, 天然, 満点, 交差点, 重点, 10点, まるで, 一丸, 日の丸, ですから, ますます).
- **Latency & Pause-Free Commit Budget**: Keep micro-pause flush threshold $\le 200\text{ms}$ and continuous sentence pacing $\le 2400\text{ms}$ so sentence boundaries commit without forcing users to pause.
- **Reference Spec**: Refer to [`docs/punctuation_and_voice_boundary_evaluation_criteria.md`](file:///home/kenichikurihara/repos/myasr/docs/punctuation_and_voice_boundary_evaluation_criteria.md) for full evaluation axes and criteria.

## ✨ UI Simplicity & Progressive Disclosure Contract (Mandatory)
- **4-Card Top-Level Cap (`MainActivity`)**: Keep [`activity_main.xml`](file:///home/kenichikurihara/repos/myasr/app/src/main/res/layout/activity_main.xml) strictly structured into at most **4 top-level cards**:
  1. **Card 1 (Hero: Setup, Status & Quick Test)**: Unified status badges, IME enable/switch buttons, Custom Dictionary button, and immediate test field (`etKeyboardTest`).
  2. **Card 2 (Core Everyday Settings)**: At most 4–5 everyday user preferences (Keyboard Height, QWERTY Number Row, Streaming Voice Input, AI Rerank Engine selector). Hide child sub-selectors (`View.GONE`) when a parent feature toggle is OFF.
  3. **Card 3 (Unified Dictionary & Model Data Management)**: One-tap recommended download for dictionaries and connection matrix, with individual dictionary/asset rows placed inside the collapsible `layoutDictDetailsContainer` (collapsed by default).
  4. **Card 4 (Collapsible Advanced & Developer Tools)**: Place all hyperparameter tuning knobs ($\alpha$ slider, Context-Free toggle, Mandatory toggle, Debug badges) inside `layoutAdvancedContent` (collapsed by default).
- **Optimal Defaults Over UI Knobs**: When a parameter is proven optimal via cross-validation or corpus evaluation, apply it as the default constant in code rather than adding new visible controls to the primary UI.
- **Zero Dead UI Resources**: Immediately delete obsolete layouts or classes when refactoring UI flows. Refer to [`docs/ui_design_and_simplicity_guidelines.md`](file:///home/kenichikurihara/repos/myasr/docs/ui_design_and_simplicity_guidelines.md) before adding any UI element.

## 🚀 Remote Repository Synchronization Policy (Mandatory)
- **Continuous Remote Git Push**:
  - Whenever any new feature, bug fix, asset addition, or documentation change is completed and verified, **always commit and push changes to the remote GitHub repository (`origin/main`)**.
  - Never leave completed feature increments or tested fixes unpushed on the local branch.
