# Project Guidelines: JotInput (Japanese On-device Text Input)

## 📱 Dual-Target Deployment Policy (Mandatory)

Whenever changes or fixes are made to the Android application, **always install and verify the app on BOTH target environments**:
1. **ARCVM (ChromeOS Android container)**: e.g. `arc:5555`
2. **Connected Phone (Pixel 10 Pro via USB or Wi-Fi)**: e.g. `<device-serial>` or `<device-ip>:<port>` (any active phone device listed in `adb devices`)

### Standard Deployment Workflow:
Run `./deploy.sh` or execute the following steps for all connected targets in `adb devices`:
```bash
./deploy.sh
```
Or manually:
1. Compile APK: `./gradlew assembleDebug`
2. For each device in `adb devices`:
   - `adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk`
   - `adb -s <device> shell pm grant io.github.kenichikurihara.jotinput android.permission.RECORD_AUDIO`
   - `adb -s <device> shell ime enable io.github.kenichikurihara.jotinput/com.example.myasr.VoiceInputMethodService`
   - `adb -s <device> shell am start -n io.github.kenichikurihara.jotinput/com.example.myasr.MainActivity`
3. Verify that the app launches and the ReazonSpeech model loads cleanly in logcat on both devices.
4. For any UI / IME change, verify visual rendering and `dumpsys window` metrics on the physical phone (and on ARCVM whenever applicable). Never silently skip a target; if a target is unreachable or a check is constrained by the environment (e.g., ChromeOS hardware keyboard bridge), explicitly report it to the user.

## ☁️ APK Distribution Policy (GitHub CI/CD)
Whenever changes are made and pushed:
- **GitHub Actions Artifact Storage**:
  - CI/CD (`.github/workflows/android-ci.yml`) automatically builds and archives the compiled debug APK (`app-debug.apk`) as a workflow artifact upon push to `main` or pull request.
  - Sideloading and verification can download the fresh artifact directly from GitHub Actions runs.

## 💾 Custom Dictionary Persistence Policy
- Keep dictionary empty by default on fresh installs.
- Never erase or overwrite `context.filesDir/custom_dictionary.json` across app updates or schema changes.

## 📦 Model & Lexicon Decoupling Policy (Mandatory Download-Only Architecture)
- **Strict Prohibition on Bundling Heavy Models or Large Word Dictionaries in APK**:
  - Never place neural model files (ONNX, GGUF, TFLite, etc.) or multi-million-word dictionary files (SudachiDict, Wikipedia, etc.) inside `app/src/main/assets/` or APK resources.
  - **Connection Matrix Bundling Permitted**: The Mozc 2D bigram grammatical connection cost matrix (`connection_data.bin`, ~14.28 MB) is permitted and required to be bundled in `app/src/main/assets/mozc/connection_data.bin` to guarantee 100% stable morphological transition scores out-of-the-box.
  - The APK must remain compact (containing compiled bytecode, layout/drawables, native `.so` libraries, and the ~14.28 MB connection matrix; target size $\le 110\text{ MB}$).
- **Storage-First & On-Demand Download Pattern for Models and Multi-Dictionaries**:
  - Neural models (ASR, SLM, VAD, etc.) and large lexical dictionaries (SudachiDict, Wikipedia, Place/Personal names) must be loaded from device storage (`context.getExternalFilesDir(null)` or `context.filesDir`).
  - Implement on-demand network downloaders over HTTPS with progress reporting and UI controls in `MainActivity` for large external lexicon assets.
- **Local Testing & Deployment Contract**:
  - Developer/test asset copies must reside under `tools/models/`, `tools/mozc_dict/`, or `app/src/test/resources/` (and large binaries must be excluded in `.gitignore`).
  - [`./deploy.sh`](deploy.sh) must automatically check target device storage and provision absent model/lexicon files to app external storage so dual-target deployment and verification succeed seamlessly without bloating the APK.

## 🚫 Anti-Anecdotal Tuning Policy (No Ad-Hoc Cost Modifications)
- **Strict Prohibition on Anecdotal Cost Hacking**: Never manually alter word costs, insert arbitrary words into `registerCanonicalVocabulary()`, or add ad-hoc penalty entries based purely on isolated, anecdotal conversion observations (e.g. manually hacking `うみ` or `いなか` costs).
- **Corpus-Driven Principle**: Lexical costs and dictionary frequencies must be derived systematically from corpus statistics (e.g., Mozc OSS corpus-trained CRF weights or systematic dictionary rebuilds).
- **Language Model Over Hand Tuning**: Semantic disambiguation and contextual choice must be handled through principled language modeling (SLM re-ranking) and systematic dictionary generation, never through hand-coded unigram cost patches.

## 🎯 Top-Line Non-Regression Policy: Voice Punctuation & Instant Sentence Boundary Input
- **Mandatory 100% Test Pass**: In any code change touching ASR, VAD, punctuation, or IME, always run `./gradlew testDebugUnitTest` and ensure [`JapanesePunctuationEvaluationTest`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) passes 100% (67+ unit tests).
- **No False-Positive Degradation**: Never weaken vocabulary protection rules for spoken punctuation commands (e.g. 天気, 天然, 満点, 交差点, 重点, 10点, まるで, 一丸, 日の丸, ですから, ますます).
- **Latency & Pause-Free Commit Budget**: Keep micro-pause flush threshold $\le 200\text{ms}$ and continuous sentence pacing $\le 2400\text{ms}$ so sentence boundaries commit without forcing users to pause.
- **Reference Spec**: Refer to [`docs/punctuation_and_voice_boundary_evaluation_criteria.md`](docs/punctuation_and_voice_boundary_evaluation_criteria.md) for full evaluation axes and criteria.

## ✨ UI Simplicity & Progressive Disclosure Policy (Mandatory)
- **Mandatory PRD, JTBD & CUJ for UI Changes**:
  - Whenever making any UI-related changes (including layouts, views, navigation, dialogs, user settings, or IME surface interactions), **always author/update a PRD (`docs/prd_and_design_*.md`) beforehand**.
  - Clearly define the **Jobs to be Done (JTBD)** (user context, triggers, motivations, and expected outcomes) and **Critical User Journeys (CUJ)** (step-by-step user interaction flows and edge cases).
- **Proactive DMN & Alloy Modeling for UI Decisions & State Transitions**:
  - Whenever the UI involves decision logic, action arbitration (e.g. smart setup guides, dynamic CTA selection, feature enabling/disabling), or multi-state transitions, **proactively leverage DMN (`DecisionTable` / `DecisionModel`) and Alloy formal verification (`.als`)**.
  - Keep decision rules declarative and testable outside Android UI (`android.view.*`), and formally verify transition safety and reachability invariants in `app/src/test/resources/alloy/*.als`.
- **v2 3-Tab Information Architecture & Progressive Disclosure**:
  - Maintain the 3-tab structure for `MainActivity` (Home / Settings / Data) as defined in [`docs/ui_design_and_simplicity_guidelines.md`](docs/ui_design_and_simplicity_guidelines.md).
  - Use DMN smart guides (presenting only the single highest-priority next action) to minimize cognitive load.
  - Collapse developer/advanced tuning knobs by default (`View.GONE` / collapsible containers).
- **Optimal Defaults Over UI Knobs**: When a parameter is proven optimal via cross-validation or corpus evaluation, apply it as the default constant in code rather than adding new visible controls to the primary UI.
- **Zero Dead UI Resources**: Immediately delete obsolete layouts or classes when refactoring UI flows. Refer to [`docs/ui_design_and_simplicity_guidelines.md`](docs/ui_design_and_simplicity_guidelines.md) before adding any UI element.

## 🚀 Remote Repository Synchronization & CI/CD Verification Policy (Mandatory)
- **Continuous Remote Git Push**:
  - Whenever any new feature, bug fix, asset addition, or documentation change is completed and verified, **always commit and push changes to the remote GitHub repository (`origin/main`)**.
  - Never leave completed feature increments or tested fixes unpushed on the local branch.
- **Mandatory CI/CD Completion & Success Verification**:
  - After pushing commits to remote, **always monitor and verify that GitHub Actions CI/CD (`Android CI`) completes with success (`gh run watch <run_id>` or `gh run list` / `gh run view`)**.
  - Never finish or conclude a task while CI/CD is failing or unresolved. If CI/CD fails, immediately investigate the failure logs, fix the root cause, and re-push until CI/CD is all green.
- **CI/CD Failure Feedback & Local Test Enhancement (Prevent Remote Regression Escapes)**:
  - Whenever CI/CD fails, **always enhance or add local unit/integration tests (`./gradlew testDebugUnitTest`, Konsist, or evaluation suites) so that the regression is reliably reproduced and caught locally in future test runs**.
  - Never patch a CI failure superficially or push trial-and-error fixes without establishing corresponding local test coverage to detect the failure beforehand.

## 🏛️ DDD / DSL Boundary Review & Architecture Enforcement (Mandatory)
- **Pre-Commit Review for DDD / DSL Boundary Changes**:
  - Whenever a change modifies **DDD / DSL boundaries** — including `shared/domain/`, `shared/event/`, Bounded Context public contracts (OHS, ACL, Published Language DTOs), `Judgement` / `Specification` contracts, cross-context dependencies, or `docs/ddd/` — **always perform an explicit architectural review before committing**:
    1. Verify alignment with [`docs/ddd/context_map.md`](docs/ddd/context_map.md) and [`docs/ddd/README.md`](docs/ddd/README.md) dependency rules.
    2. Confirm no `domain` or `shared` package introduces dependencies on `infrastructure`, `presentation`, or Android UI (`android.view.*`, `android.widget.*`).
    3. Update `docs/ddd/` and unit tests in the same change when boundary contracts evolve.
- **Automated Konsist Enforcement & Doc-Code Sync**:
  - [`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) enforces Shared Kernel purity, `Judgement` placement/purity, domain UI-independence, repository placement, Bounded Context allowlist alignment (`docs/ddd/context_map.md`), DMN `DecisionTable` documentation sync (`docs/ddd/dmn_decision_models.md`), `Judgement`/`DecisionModel` test coverage, and Alloy `.als` documentation sync (`docs/ddd/alloy_formal_verification.md`) on every `./gradlew testDebugUnitTest` run. Never weaken or bypass these rules without explicit review.
- **Spec-First & Alloy Synchronization**:
  - All formal Alloy models must reside in `app/src/test/resources/alloy/*.als` so [`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) automatically discovers and verifies them in CI.
  - When modifying state transitions, UI mode arbitration, or DMN/Judgement decision rules, update the corresponding `.als` spec, DMN `DecisionTable`, and `docs/ddd/` in the same commit.

