# Contributing to JotInput

Thank you for your interest in **JotInput** (*Japanese On-device Text Input*)!
Please read this document carefully before opening an Issue or Pull Request (PR).

> **日本語サマリー (Summary in Japanese)**: 下部に日本語での要約を記載しています。まずは [日本語サマリー](#日本語サマリー--summary-in-japanese) もご確認ください。

---

## 1. Maintainer Capacity & Contribution Model (Model A: Issue-First / Curated PRs)

JotInput is developed and maintained by a **solo maintainer** in spare time.

- **No SLA / No Merge Guarantee**: Issue triage and PR reviews are best-effort. Even well-written PRs may take significant time to review or may be declined if they fall outside the maintainer's capacity or product direction.
- **Issue-First Rule**:
  - **PRs welcome without prior approval** (keep them small and focused):
    - Reproducible crash/ANR or bug fixes with minimal scope and unit/Robolectric tests
    - Unit test additions or `Android CI` / build-script fixes
    - Documentation clarifications, typo fixes, or translation improvements
  - **Prior Issue discussion REQUIRED before coding a PR**:
    - New user-facing features or IME keyboard behaviors
    - UI/UX or layout modifications (which require PRD / JTBD / CUJ updates)
    - New ASR / SLM / swipe / dictionary downloaders or format changes
    - Architectural refactors, DDD boundary changes, or new third-party dependencies
    - Device-specific workarounds for hardware the maintainer does not own
  - **Closure Policy**: Unsolicited large feature PRs, unagreed UI redesigns, sweeping refactors, or unverified/low-effort AI-generated PRs ("AI slop") may be closed without detailed line-by-line review.
- **Forking Is Welcome**: JotInput is licensed under the [Apache License 2.0](LICENSE). If you want features, layouts, or OEM-specific tweaks beyond what a solo maintainer can support, you are warmly encouraged to fork the repository and build your own variant while complying with [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

---

## 2. Device Support Tiers & Multi-Device PR Policy

Because the maintainer only owns **Google Pixel 10 Pro** hardware and **ChromeOS ARCVM**, device support is organized into three tiers:

| Tier | Scope | Support Level |
| :--- | :--- | :--- |
| **Tier 1 (Primary)** | **Google Pixel 10 Pro** (physical device) & **ChromeOS ARCVM** (`x86_64`), Android 8.0+ (API 26+) | **Maintainer-verified**. Regressions on Tier 1 block releases. |
| **Tier 2 (Best-Effort)** | Other `arm64-v8a` / `x86_64` devices running stock/AOSP-compatible Android 8.0+ | **Community-supported**. Maintainer cannot reproduce OEM-only issues locally. Fixed via standard Android APIs and community PRs with full evidence. |
| **Tier 3 (Unsupported)** | 32-bit ABIs (`armeabi-v7a`, `x86`), Android `< 8.0` (API `< 26`), custom-ROM hacks, or fragile OEM-proprietary workarounds | **Unsupported**. PRs targeting Tier 3 or adding risky device-specific hacks will not be accepted. |

### Mandatory Requirements for Multi-Device / IME / UI PRs

If your PR addresses behavior on a device the maintainer cannot test physically, **all** of the following are required in the PR description:

1. **Standard Android APIs Only**: Use standard public Android `InputMethodService`, `WindowInsets`, `AudioRecord`, and NDK APIs. Do **not** add hardcoded device-model switches, fragile reflection on OEM internals, or changes that risk regressing Tier 1 devices.
2. **Environment Details**: Exact device model, Android OS version, ABI (`arm64-v8a` / `x86_64`), and target host app (if IME-specific).
3. **Diagnostic Evidence**:
   - Filtered `adb logcat` before and after the fix
   - Window/insets geometry (`adb shell dumpsys window windows`) for IME sizing, navigation bar overlap, or keyboard inset issues
   - Before/after screenshots or screen recordings for any UI or IME visual change
4. **Automated Test Coverage**: Include unit or Robolectric tests reproducing the underlying state/logic bug so CI protects against regressions automatically.

---

## 3. Architectural Contracts & Quality Gates

All PRs targeting code must pass GitHub Actions **`Android CI`** and comply with the project's core engineering contracts:

1. **Automated Test Suite (`./gradlew testDebugUnitTest`)**:
   - Must pass 100%, including:
     - `JapanesePunctuationEvaluationTest` (voice punctuation & false-positive vocabulary protection; latency and pause-free commit budgets per [`docs/punctuation_and_voice_boundary_evaluation_criteria.md`](docs/punctuation_and_voice_boundary_evaluation_criteria.md))
     - `DddArchitectureKonsistTest` (Bounded Context & Shared Kernel boundaries)
     - `AlloyVerificationTest` (Alloy 6 formal state-transition invariants)
2. **Release Build & 16KB Page Alignment**:
   - `assembleDebug`, `assembleRelease` (R8 + `lintVitalRelease`), and 16KB ELF `LOAD` segment alignment (`0x4000`) for `arm64-v8a` native `.so` libraries must succeed cleanly in CI.
3. **Model & Lexicon Decoupling (No Heavy Assets in APK)**:
   - Never place neural model files (`.onnx`, `.gguf`, `.tflite`, etc.) or large word dictionaries inside `app/src/main/assets/` or APK resources.
   - Only the Mozc 2D bigram connection matrix (`app/src/main/assets/mozc/connection_data.bin`) is bundled in the APK. Large models and multi-dictionaries must load from device storage via on-demand HTTPS downloaders.
4. **Anti-Anecdotal Tuning Policy**:
   - Do **not** manually hack individual word costs, hardcode isolated words into `registerCanonicalVocabulary()`, or add ad-hoc penalties for isolated conversion complaints. Conversion accuracy improvements must be systematic, corpus-driven, or handled via language modeling.
5. **Custom Dictionary Persistence Guarantee**:
   - `context.filesDir/custom_dictionary.json` must remain **empty by default** on fresh installs and must **never** be erased or overwritten during upgrades or schema migrations.
6. **UI Simplicity & Progressive Disclosure**:
   - Any UI change requires a PRD (`docs/prd_and_design_*.md`) defining **Jobs to be Done (JTBD)** and **Critical User Journeys (CUJ)** beforehand, keeping `MainActivity`'s 3-tab structure (Home / Settings / Data) clean and advanced knobs collapsed by default (`docs/ui_design_and_simplicity_guidelines.md`).
   - UI decision rules and state transitions should be expressed cleanly with DMN (`DecisionTable` / `DecisionModel`) and verified with Alloy `.als` specs in `app/src/test/resources/alloy/`.
7. **DDD / DMN / Alloy Sync**:
   - Changes touching Bounded Contexts (`docs/ddd/context_map.md`), `DecisionTable`/`Judgement` (`docs/ddd/dmn_decision_models.md`), or Alloy `.als` specs (`docs/ddd/alloy_formal_verification.md`) must update the corresponding documentation and unit tests in the same PR. Do not add Android UI (`android.view.*`, `android.widget.*`), `infrastructure`, or `presentation` dependencies into `domain` or `shared` packages.

---

## 4. AI-Assisted Contributions

Using AI coding tools is permitted, **provided that**:

- You explicitly disclose AI usage in the Pull Request template.
- You have personally read, understood, and verified every line of your diff.
- You ran `./gradlew testDebugUnitTest` and verified actual build/device behavior yourself — never fabricate test results, benchmarks, or device logs.
- You checked project architecture rules (`DddArchitectureKonsistTest`, `AlloyVerificationTest`, `README`/`docs` consistency).

PRs that appear to be unreviewed AI output violating project policies will be closed immediately.

---

## 5. Privacy, Security & Hygiene

- **Never commit secrets or PII**: Never include keystores (`.jks`, `.keystore`), `keystore.properties`, personal emails/names, LAN IPs, device serials, or local absolute paths (e.g., `/home/...`, `/Users/...`).
- **On-device privacy**: JotInput must never send user keystrokes, voice audio, clipboard contents, or custom dictionary words to external servers ([`PRIVACY.md`](PRIVACY.md)).
- **Security bugs**: Do **not** open a public Issue or PR for vulnerabilities; follow [`SECURITY.md`](SECURITY.md).

---

## 6. Submitting a Pull Request

1. Fork the repository and create a topic branch from `main`.
2. Ensure local tests pass:
   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew assembleDebug
   ```
   *(Note: evaluation runner tests may touch `tools/eval/jotinput_prediction_evaluation_results*.json`; revert unrelated working-tree changes to those files before committing if you did not intend to update benchmark baselines.)*
3. Keep your PR focused on a single concern (no bundled formatting sweeps or unrelated refactors).
4. Fill out every applicable section of [`.github/pull_request_template.md`](.github/pull_request_template.md).

---

## 日本語サマリー / Summary in Japanese

**JotInput** は個人メンテナーが空き時間で開発・運用しているため、PR レビューやサポート範囲に明確な制約を設けています（**Model A: Issue-First / Curated PRs**）。

- **事前相談必須（Issue-First）**: バグ修正・テスト追加・ドキュメント改善の小規模 PR は歓迎ですが、**新機能・UI 変更・大規模リファクタ・新モデル追加・他端末向け対応** は、コーディング前に必ず Issue で方針を相談してください。事前の合意がない大型 PR や、未検証の AI 生成 PR（いわゆる AI スロップ）は詳細なレビューなしでクローズする場合があります。
- **サポート端末 Tier**:
  - **Tier 1（一次検証対象）**: Pixel 10 Pro 実機・ChromeOS ARCVM（Android 8.0+ / `arm64-v8a`, `x86_64`）
  - **Tier 2（ベストエフォート）**: 標準 Android API で動作するその他 Android 8.0+ 端末（メンテナーは実機を所有していないため、PR 時は端末情報・`logcat`・`dumpsys window windows`・前後スクリーンショット・単体テストの添付が必須です）
  - **Tier 3（非対応）**: 32-bit ABI・Android 8.0 未満・カスタム ROM や機種固有のハック
- **設計・品質ルール**:
  - `Android CI`（`./gradlew testDebugUnitTest`, `assembleDebug`, R8 Release ビルド, 16KB ページアラインメント）全通過が必須です。
  - APK への巨大モデル/辞書同梱禁止（`connection_data.bin` のみ許可）、場当たり的な単語コスト調整禁止、`custom_dictionary.json` の初期空状態と更新時保持、UI 変更時の PRD/DMN/Alloy 同期、`DddArchitectureKonsistTest` 準拠が求められます。
- **AI ツールの利用**: 使用有無を PR テンプレートで明記し、必ず自身の責任で全行とローカルテスト結果を確認してください。
- **Fork 歓迎**: Apache-2.0 ライセンスの範囲内で、自由なフォーク・独自カスタマイズを歓迎します（[`LICENSE`](LICENSE)・[`NOTICE`](NOTICE) 準拠）。
