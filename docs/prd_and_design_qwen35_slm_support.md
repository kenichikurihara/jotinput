# PRD & Technical Design: Qwen3.5 SLM (0.8B & 2B) On-Device Reranking Support

## 1. Executive Summary & Background
JotInput uses on-device Small Language Models (SLMs) via `llama.cpp` to perform causal conditional log-likelihood re-ranking of Japanese Kana-Kanji conversion candidates and English typing candidates.
Previously, JotInput supported Qwen2.5-0.5B and Qwen2.5-1.5B.
This change introduces official support for the next-generation **Qwen3.5-0.8B** and **Qwen3.5-2B** foundation models.

## 2. License Compliance Verification
- **Model Family**: Alibaba Cloud Qwen3.5 (`Qwen/Qwen3.5-0.8B`, `Qwen/Qwen3.5-2B`, and quantized GGUF artifacts).
- **License Type**: **Apache License 2.0** (`Apache-2.0`).
- **Commercial Use**: **Allowed** without monthly active user (MAU) thresholds (unlike Meta Llama's 700M MAU limit) or restrictive patent/research covenants.
- **Modification & Redistribution**: **Allowed** for weights, quantized binaries (GGUF), derivatives, and runtime integrations.
- **Verdict**: **Full License Compliance Confirmed**. The model is suitable for JotInput's open distribution and on-device inference architecture.

## 3. Jobs to be Done (JTBD)
- **When** I am typing in Japanese (or English) on my Android device and configuring SLM candidate re-ranking in JotInput settings,
- **I want to** select next-generation Qwen3.5 models (0.8B for fast, highly responsive inference or 2B for state-of-the-art context-aware disambiguation),
- **So that** I get significantly improved conversion accuracy and nuance understanding with on-device latency that fits mobile typing constraints.

## 4. Critical User Journeys (CUJ)
- **CUJ-1: Selecting Qwen3.5-0.8B in Settings**
  1. User navigates to Settings tab (`tab_main_settings.xml`).
  2. Under AI Rerank Engine selection, user taps "Qwen3.5-0.8B (高速・次世代)".
  3. DMN decision table `RerankEngineSelectionDecisionTable` evaluates selection and sets `selectedEngine = LOCAL_SLM_3_5_0_8B`.
  4. Preferences update, `LlmReranker` asynchronously initializes `qwen3.5-0.8b-instruct-q4_k_m.gguf` if present.
  5. UI badge reflects "Qwen3.5 0.8B (Ready)" or "未検出 (deploy.sh で転送)".

- **CUJ-2: Selecting Qwen3.5-2B for Maximum Accuracy**
  1. User selects "Qwen3.5-2B (最高精度・次世代)".
  2. DMN decision table updates active engine to `LOCAL_SLM_3_5_2B`.
  3. Memory and file presence are checked, badge displays status accordingly.

- **CUJ-3: Model Provisioning Fallback**
  1. If model file is absent from device external storage, DMN `AiEngineStatusDecisionTable` outputs neutral/warning state and guide user to provision via `./deploy.sh` or `tools/push_model_to_device.sh`.
  2. Re-ranking safely falls back to dictionary Viterbi without crash or user-facing latency hiccups.

## 5. Architectural & Formal Specifications
- **Bounded Context**: `reranking` and `ime`
- **DMN Decision Tables**:
  - `AiEngineStatusDecisionTable`: Dynamically derives badge and status text from `RerankEngineType`.
  - `RerankEngineSelectionDecisionTable`: Evaluates engine selection preserving selected engine or falling back cleanly.
- **Alloy Formal Verification**: `main_app_ui_state.als` extended with `Engine3_5_0_8B` and `Engine3_5_2B` to guarantee complete state reachability and invariant safety.
