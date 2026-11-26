## Related Issue
- Related / agreed Issue: #<!-- Issue number (required for features, UI changes, large refactors, or Tier 2 device workarounds per CONTRIBUTING.md) -->

## Summary & Scope
<!-- Briefly describe what changed, why, and what is intentionally out of scope -->

## Device Testing & Evidence (Required for IME / UI / Audio / Device-Specific Changes)
- **Tested on**: <!-- e.g., Pixel 10 Pro (Android XX, arm64-v8a) / ChromeOS ARCVM / Other device model + Android OS + ABI -->
- **Host app(s) tested**: <!-- e.g., MainActivity, Chrome, Gmail -->
- **Visual / Diagnostic evidence**: <!-- Attach before/after screenshots or video for UI/IME changes, and paste relevant `logcat` / `dumpsys window windows` notes if applicable -->
- [ ] Confirmed this change uses standard Android APIs only and avoids hardcoded OEM/device hacks that could regress Tier 1 devices.

## AI Assistance Disclosure
- [ ] No AI coding tools were used, **OR**
- [ ] AI coding tools were used; I personally reviewed every line, ran all verification commands locally, and did not fabricate any test output or device evidence.

## Quality, Privacy & Architecture Checklist
- [ ] **Tests & CI**: `./gradlew testDebugUnitTest` (including `JapanesePunctuationEvaluationTest`, `DddArchitectureKonsistTest`, `AlloyVerificationTest`) and `./gradlew assembleDebug` pass locally. (If CI/CD previously failed, local tests were added/improved so the regression is caught locally.)
- [ ] **UI Changes (PRD / JTBD / CUJ / DMN / Alloy)**: For any UI/IME layout change, a PRD (`docs/prd_and_design_*.md`) is created/updated with explicit JTBD & CUJ, preserving the 3-tab structure (`MainActivity`) and progressive disclosure.
- [ ] **DDD Boundaries**: Any new/changed Bounded Context or contract is reflected in `docs/ddd/context_map.md` and `docs/ddd/README.md`, with no UI/infra/presentation leaks into `domain`/`shared`.
- [ ] **DMN & Judgement**: Any new/changed `DecisionTable`, `DecisionModel`, or `Judgement` is covered by unit tests and documented in `docs/ddd/dmn_decision_models.md`.
- [ ] **Alloy 6 Formal Specs**: Any state-transition or decision-safety change has its `.als` model updated under `app/src/test/resources/alloy/`, documented in `docs/ddd/alloy_formal_verification.md`, and verified by `AlloyVerificationTest`.
- [ ] **Core Project Policies**: No heavy neural models/lexicons bundled in APK assets (only `connection_data.bin`), no anecdotal word-cost tuning, `custom_dictionary.json` remains empty by default and preserved across updates, and no secrets/PII are included.
