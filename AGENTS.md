# AGENTS.md — JotInput (Japanese On-device Text Input) Agent Instructions

Always follow the policies and rules in [`GEMINI.md`](GEMINI.md) and review the architectural history and post-mortems in [`docs/findings_and_decisions.md`](docs/findings_and_decisions.md).

## 📱 Mandatory Dual-Target Deployment & Verification

Whenever any code, resource, or configuration change is made to the Android app:
1. **Deploy to BOTH connected targets**:
   - **ARCVM (ChromeOS Android container)**: e.g., `arc:5555`
   - **Physical Phone (Pixel 10 Pro via USB or Wi-Fi)**: e.g., `<device-serial>` or `<device-ip>:<port>`
2. Run [`./deploy.sh`](deploy.sh) and confirm in `adb -s <device> logcat` that `MainActivity` launches and the ReazonSpeech model (`AsrManager`) loads cleanly on **both** devices.
3. For any UI or IME window change, verify visual behavior (`screencap`) and window/inset geometry (`dumpsys window windows`) on the physical phone. Never silently skip a target.

## ☁️ Mandatory APK Distribution Contract (GitHub CI/CD)
Whenever any code, resource, or configuration change is pushed or tested:
- **Pull Request CI (`Build & Test`) & Rolling Remote HEAD Prerelease (`main-head`)**:
  - CI/CD ([`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)) runs `Build & Test` on **every** pull request targeting `main` (without path skips) and on `main` pushes, archiving `app-debug.apk` and verifying both Debug (persistent keystore) and Release (R8 + 16KB ELF) builds.
  - On every `main` push / workflow dispatch, `Publish Remote HEAD Prerelease` builds and publishes the rolling `main-head` prerelease (`jotinput-main-head-release.apk`, `jotinput-main-head-debug.apk`, `head-metadata.json`, `SHA256SUMS.txt`) for tokenless in-app updates without altering `/releases/latest`.
  - Official `v*` tag pushes run [`.github/workflows/release.yml`](.github/workflows/release.yml) to publish signed stable GitHub Releases.

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
  - [`./deploy.sh`](deploy.sh) must automatically check target device storage and provision absent model/lexicon files to app external storage so dual-target deployment and verification succeed seamlessly without bloating the APK.

## 🚫 Anti-Anecdotal Tuning Policy (No Ad-Hoc Cost Modifications)
- **Strict Prohibition on Anecdotal Cost Hacking**: Never manually alter word costs, insert arbitrary words into `registerCanonicalVocabulary()`, or add ad-hoc penalty entries based purely on isolated, anecdotal conversion observations (e.g. manually hacking `うみ` or `いなか` costs).
- **Corpus-Driven Principle**: Lexical costs and dictionary frequencies must be derived systematically from corpus statistics (e.g., Mozc OSS corpus-trained CRF weights or systematic dictionary rebuilds).
- **Language Model Over Hand Tuning**: Semantic disambiguation and contextual choice must be handled through principled language modeling (SLM re-ranking) and systematic dictionary generation, never through hand-coded unigram cost patches.

## 🎯 Top-Line Quality Contract: Voice Punctuation & Instant Sentence Boundary Non-Regression
- **Mandatory 100% Test Pass**: In any code change touching ASR, VAD, punctuation, or IME, always run `./gradlew testDebugUnitTest` and ensure [`JapanesePunctuationEvaluationTest`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) passes 100% (67+ unit tests).
- **No False-Positive Degradation**: Never weaken vocabulary protection rules for spoken punctuation commands (e.g. 天気, 天然, 満点, 交差点, 重点, 10点, まるで, 一丸, 日の丸, ですから, ますます).
- **Latency & Pause-Free Commit Budget**: Keep micro-pause flush threshold $\le 200\text{ms}$ and continuous sentence pacing $\le 2400\text{ms}$ so sentence boundaries commit without forcing users to pause.
- **Reference Spec**: Refer to [`docs/punctuation_and_voice_boundary_evaluation_criteria.md`](docs/punctuation_and_voice_boundary_evaluation_criteria.md) for full evaluation axes and criteria.

## ✨ UI Simplicity & Progressive Disclosure Contract (Mandatory)
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

## 🚀 Mandatory Pull Request & Pre-Merge CI/CD Verification Policy
- **Mandatory Topic Branch & Pull Request Workflow (No Direct Pushes to `main`)**:
  - Because `kenichikurihara/jotinput` is a public repository with Branch Protection on `main`, **never commit directly to `main` or push directly to `origin/main`** (also enforced by [`.githooks/pre-push`](.githooks/pre-push)).
  - For every completed feature, bug fix, asset update, or documentation change:
    1. Create a topic branch from `main` (e.g., `git checkout -b feat/<topic>` or `fix/<topic>`).
    2. Commit verified changes and push the topic branch to `origin` (`git push -u origin <branch>`).
    3. Open a Pull Request targeting `main` via `gh pr create --base main ...` following [`.github/pull_request_template.md`](.github/pull_request_template.md).
    4. **Pre-Merge CI/CD Gate**: Always wait for and verify that `Android CI` (`Build & Test`) passes 100% on the Pull Request **before merging** (`gh pr checks --watch` or `gh run watch <run_id>`). Never merge a PR while CI/CD is pending, failing, or skipped.
    5. Merge only after all required PR checks are green (`gh pr merge --admin --squash --delete-branch` when acting as the solo maintainer), then switch to `main` and `git pull --ff-only origin main`.
    6. Monitor the post-merge `Android CI` workflow run on `main` (including `Publish Remote HEAD Prerelease`) to 100% completion (`gh run watch <run_id>`).
- **CI/CD Failure Feedback & Local Test Enhancement (Prevent Remote Regression Escapes)**:
  - Whenever CI/CD fails (on a PR or on `main`), **always enhance or add local unit/integration tests (`./gradlew testDebugUnitTest`, Konsist, or evaluation suites) so that the regression is reliably reproduced and caught locally in future test runs**.
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

