## Summary
<!-- Briefly describe what changed and why -->

## DDD / DMN / Judgement / Alloy Sync Checklist
- [ ] **UI Changes (PRD / JTBD / CUJ / DMN / Alloy)**: For any UI change, a PRD (`docs/prd_and_design_*.md`) is created/updated with explicit JTBD & CUJ, and DMN/Alloy are used where applicable for decision rules and state transitions.
- [ ] **DDD Boundaries**: Any new/changed Bounded Context or contract is reflected in `docs/ddd/context_map.md` and `docs/ddd/README.md`, and passes `DddArchitectureKonsistTest`.
- [ ] **DMN & Judgement**: Any new/changed `DecisionTable`, `DecisionModel`, or `Judgement` is covered by unit tests and documented in `docs/ddd/dmn_decision_models.md`.
- [ ] **Alloy 6 Formal Specs**: Any state-transition or decision-safety change has its `.als` model updated under `app/src/test/resources/alloy/`, documented in `docs/ddd/alloy_formal_verification.md`, and verified by `AlloyVerificationTest`.
- [ ] **Tests & CI**: `./gradlew testDebugUnitTest` passes 100% locally / in GitHub Actions `Android CI`.
