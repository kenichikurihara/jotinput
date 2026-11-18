# PRD, CUJ & Technical Design: SLM (Qwen3.5 / Qwen2.5) モデルダウンロード UI とスムーズな導入提案

## 1. 背景と課題 (Background & Problem Statement)

JotInput はオンデバイス SLM (`llama.cpp` + GGUF) による文脈リランキング機能を備えており、[`RerankEngineType`](app/src/main/java/com/example/myasr/context/reranking/domain/RerankEngineType.kt) として以下の 4 モデルをサポートしている：
- **Qwen3.5-0.8B** (`qwen3.5-0.8b-instruct-q4_k_m.gguf`, 約 508 MB, Apache-2.0) — 次世代高速・推奨
- **Qwen3.5-2B** (`qwen3.5-2b-instruct-q4_k_m.gguf`, 約 1.19 GB, Apache-2.0) — 次世代最高精度
- **Qwen2.5-0.5B** (`qwen2.5-0.5b-instruct-q4_k_m.gguf`, 約 469 MB, Apache-2.0) — 軽量標準
- **Qwen2.5-1.5B** (`qwen2.5-1.5b-instruct-q4_k_m.gguf`, 約 1.04 GB, Apache-2.0) — 高精度

しかし現状では以下のギャップが存在する：
1. **アプリ内ダウンロード UI の欠如**: 音声認識モデル (ReazonSpeech / Parakeet) や拡張辞書には「データ」タブでのアプリ内 HTTPS ダウンロード UI がある一方、SLM (Qwen3.5 / Qwen2.5) にはアプリ内ダウンローダも「データ」タブの取得・削除 UI も存在せず、ADB (`tools/push_model_to_device.sh`) 経由でしか配置できない。
2. **モデル選択時の導入導線不足**: 「設定」タブで未導入のモデル（例: Qwen3.5-0.8B や Qwen3.5-2B）を選択しても「未導入」と表示されるだけで、その場からワンタップでダウンロードを開始する導線がない。
3. **オンボーディングでの提案欠如**: 初期セットアップ (`OnboardingActivity`) のデータ準備ステップで、音声モデルと辞書に加えて次世代 AI 変換モデル (Qwen3.5-0.8B) のバックグラウンド取得をスムーズに提案できていない。

---

## 2. Jobs to be Done (JTBD)

- **JTBD-1 (「データ」タブでのモデル一元管理)**:
  - **When** 端末ストレージの容量や精度要件に合わせて AI 変換モデルを導入・削除したいとき、
  - **I want to** 「データ」タブ上で Qwen3.5-0.8B / 2B（および Qwen2.5-0.5B / 1.5B）の導入状態・容量・ライセンスを確認し、バックグラウンドで取得・削除したい、
  - **So that** PC や ADB なしで完結にオンデバイス AI 変換を利用・管理できる。

- **JTBD-2 (設定でモデルを選んだ瞬間のスムーズなダウンロード)**:
  - **When** 「設定」タブでまだ端末にないモデル（例: Qwen3.5-0.8B や Qwen3.5-2B）を選んだとき、
  - **I want to** 「このモデルをダウンロードしますか？」と即座に提案され、1タップでバックグラウンド取得を開始し、完了後に自動でロードされてほしい、
  - **So that** 「データ」タブへ移動して同じモデルを探し直す手間なく、すぐに高精度な変換を使い始められる。

- **JTBD-3 (オンボーディングでの自然な AI モデル導入)**:
  - **When** 初回セットアップや再インストール後の環境復旧を行っているとき、
  - **I want to** 音声モデル・拡張辞書とあわせて推奨 AI 変換モデル（Qwen3.5-0.8B）のバックグラウンド取得を任意で選べるようにしたい、
  - **So that** セットアップ完了直後から AI 文脈変換の恩恵を受けられ、かつダウンロード待ちで操作をブロックされない。

---

## 3. Critical User Journeys (CUJ)

### CUJ-1: 「設定」タブで未導入の Qwen3.5 モデルを選択した際のダウンロード提案
1. ユーザーが「設定」タブを開く。
2. AI 変換エンジンのラジオボタンから `Qwen3.5-0.8B (508MB • 次世代高速)`（または `Qwen3.5-2B` 等）をタップする。
3. `RerankEngineSelectionDecisionTable` が選択エンジンを `LOCAL_SLM_3_5_0_8B` に更新する。
4. `SlmDownloadPromptDecisionTable` が `(isUserSelection=true, slmEnabled=true, modelInstalled=false, isDownloading=false)` を評価し、`shouldPromptDownload = true` を返す。
5. 確認ダイアログが表示される：
   - タイトル: `Qwen3.5-0.8B をダウンロードしますか？`
   - 本文: モデルサイズ (`約508MB`)、ライセンス (`Apache-2.0`)、バックグラウンド実行・自動有効化の説明。
   - CTA: `ダウンロード開始` / `あとで`
6. ユーザーが `ダウンロード開始` をタップすると `ProvisioningForegroundService` に `SLM_MODEL` タスクがエンキューされる。
7. ユーザーが `あとで` をタップした場合でも、「設定」タブの AI カード内にインラインの `Qwen3.5-0.8B をダウンロード (約508MB)` ボタンが表示され、いつでも開始できる。
8. ダウンロード完了後、`MainActivity` が自動で `LlmReranker.initializeIfAvailable(...)` を呼び出し、バッジが `稼働中 ✓` に遷移する。

### CUJ-2: 「データ」タブでの SLM モデル一覧・取得・削除
1. ユーザーが「データ」タブを開く。
2. 「AI 変換言語モデル (SLM / Qwen)」カードに、推奨の **Qwen3.5-0.8B** と **Qwen3.5-2B** が表示され、「旧世代モデル (Qwen2.5) を表示 ▼」から **Qwen2.5-0.5B** / **Qwen2.5-1.5B** も展開できる（Progressive Disclosure）。
3. 各モデル行にステータスバッジ (`導入済` / `未取得` / `取得中...`)、サイズ・ライセンス (`Apache-2.0`)、進捗バー、`取得` / `再取得`、`削除` ボタンを配置する。
4. `削除` をタップすると、現在ロード中のモデルであれば `LlmReranker.release()` を行ってからファイルを削除し、「設定」タブのステータスバッジも即時同期する。

### CUJ-3: オンボーディング (`OnboardingActivity` Step 1: `DATA_PREP`) での提案
1. ユーザーがオンボーディング Step 1（音声モデル・辞書の準備）に到達する。
2. 従来の「日本語音声認識モデル」「推奨拡張辞書」の下に、**「AI 変換モデル（任意・推奨）」** セクションが表示される。
   - `OnboardingSlmProposalDecisionTable` により状態を評価。
   - モデル選択チップ／切替（`Qwen3.5-0.8B (508MB・高速)` / `Qwen3.5-2B (1.19GB・高精度)` / `今回は取得しない`）を用意し、デフォルトでは `Qwen3.5-0.8B` が提案される。
3. ユーザーが `バックグラウンドでまとめて取得` をタップすると、未導入の ASR・辞書に加えて、選択された Qwen3.5 モデルも `ProvisioningForegroundService` のキューに追加され、`RerankEngineType` と `slmRerankEnabled=true` が保存される。
4. ダウンロードは完全に非ブロッキングであり、ユーザーは完了を待たずに次のステップへ進める（`DownloadInProgressNeverBlocksCompletion` 不変条件を遵守）。

---

## 4. Technical Design (DD: 設計詳細)

### 4.1 ダウンロード元 URL と保存先仕様
[`RerankEngineType`](app/src/main/java/com/example/myasr/context/reranking/domain/RerankEngineType.kt) に `downloadUrl` / `estimatedSizeBytes` / `sizeLabel` を持たせる（純粋 Kotlin、Android 依存なし）：

| `RerankEngineType` | ローカルファイル名 | 配布元 (Hugging Face, Apache-2.0) | 実測サイズ |
|---|---|---|---|
| `LOCAL_SLM_3_5_0_8B` | `qwen3.5-0.8b-instruct-q4_k_m.gguf` | `https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf` | 532,517,120 B (~508MB) |
| `LOCAL_SLM_3_5_2B` | `qwen3.5-2b-instruct-q4_k_m.gguf` | `https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf` | 1,280,835,840 B (~1.19GB) |
| `LOCAL_SLM` | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | `https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf` | 491,400,032 B (~469MB) |
| `LOCAL_SLM_1_5B` | `qwen2.5-1.5b-instruct-q4_k_m.gguf` | `https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf` | 1,117,320,736 B (~1.04GB) |

- **保存先**: `context.getExternalFilesDir("models") ?: File(context.filesDir, "models")`（既存の [`LlmReranker.getModelFile`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt) および `tools/push_model_to_device.sh` と完全互換）。
- **原子性・安全性**: `<fileName>.tmp` へストリーミング書込し、リダイレクト（HTTP 301/302/303/307/308）追従、`coroutineContext.ensureActive()` によるキャンセル対応、サイズ検証（> 10MB）の後に `renameTo` で確定する。

### 4.2 Bounded Contexts & バックグラウンドサービス
- **`provisioning` コンテキスト**:
  - [`SlmModelDownloader`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/SlmModelDownloader.kt) を新設。
  - [`ProvisioningTaskKind`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ProvisioningForegroundService.kt) に `SLM_MODEL` を追加し、`dictId` に `RerankEngineType.id` を渡すことで既存のキュー・進捗通知・グローバルバナー基盤を再利用する。

### 4.3 DMN 決定表と Alloy 形式検証
- **DMN (`ime` コンテキスト)**:
  - `AiEngineStatusDecisionTable` (`MainAppUiDecisionModel`): `isDownloading` 入力と `showDownloadCta` 出力を追加し、未導入時に設定画面内インライン取得 CTA を制御する。
  - `SlmDownloadPromptDecisionTable` (`MainAppUiDecisionModel`): モデル選択時のダウンロード確認ダイアログ表示可否を宣言的に判定する。
  - `OnboardingSlmProposalDecisionTable` (`OnboardingDecisionModel`): オンボーディング Step 1 における SLM モデル提案状態（推奨・導入済・取得中・スキップ）を判定する。
- **Alloy 形式検証**:
  - [`main_app_ui_state.als`](app/src/test/resources/alloy/main_app_ui_state.als): `NeverPromptDownloadWhenInstalledOrDisabled` / `DownloadCtaOnlyWhenEnabledAndMissing` アサーションを追加検証。
  - [`onboarding_flow.als`](app/src/test/resources/alloy/onboarding_flow.als): SLM モデル選択・ダウンロード中においても `DownloadInProgressNeverBlocksCompletion` が成立することを検証。
