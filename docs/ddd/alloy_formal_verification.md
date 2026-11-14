# Alloy 6 Formal Verification in JotInput

## 1. 導入の目的

JotInput は **DDD（境界付けられたコンテキストと `Judgement`）** と **DMN（決定表と Hit Policy）** によってドメインの意思決定を構造化した。さらに **Alloy 6（軽量形式手法 / SAT ベース有界モデル検査）** を導入し、実装や単体テストだけでは網羅しきれない**状態遷移の不変条件（Invariant）**や**決定表の網羅性・安全性**を数学的・網羅的に検証する。

---

## 2. 検証アーキテクチャ

- **Alloy エンジン**: `org.alloytools:org.alloytools.alloy.core:6.2.0`（`testImplementation`、Pure Java `SAT4J` ソルバを使用するためネイティブバイナリ不要・クロスプラットフォーム）
- **自動実行**: [`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) により `./gradlew testDebugUnitTest`（および GitHub Actions `Android CI`）のたびに全 `.als` モデルが自動検査される。
  - `check` アサーション: 反例が存在しないこと（`!solution.satisfiable()`）を検証。
  - `run` 到達可能性述語: モデルが過剰制約（vacuous）でないこと（`solution.satisfiable()`）を検証。

---

## 3. 形式仕様モデル一覧 (`app/src/test/resources/alloy/`)

### 3.1 [`voice_touch_arbitration.als`](app/src/test/resources/alloy/voice_touch_arbitration.als)
[`VoiceTouchInputArbitrator`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) のマルチモーダル状態遷移を `util/ordering[State]` でモデル化し、以下を検証する：
- **`SingleComposingOwnership`**: キーボード composing とストリーミング音声 composing が単一 `InputConnection` 上で同時に成立しないこと。
- **`UndoSafetyAfterVoice`**: 音声確定またはストリーミング音声入力中において `undoValid` が必ず `False` となり、Undo（`↶`）が音声テキストを破壊しないこと。
- **`PendingVoiceFlushedWhenIdle`**: キーボードセッション終了後（`OwnerNone`）に未フラッシュの `pendingVoice` が残留しないこと。
- **到達可能性（非空虚性）**: `canQueueVoiceAndFlush`, `canUndoSafelyAfterKeyboardCommit`。

### 3.2 [`dmn_decision_safety.als`](app/src/test/resources/alloy/dmn_decision_safety.als)
DMN 決定表（BC-2 / BC-8）の不変条件を検証する：
- **`TapSafeTableIsTotal`**: `TapSafeCandidateOrdering` があらゆる入力に対して一意のルールへ解決されること。
- **`NeverPinsWhenTop1UnchangedOrAbsent`**: 初期 Top-1 不在または同値時にピン留めしないこと。
- **`NeverPinsWithinWindowUnlessTouching`**: 100ms ウィンドウ内（非タッチ時）はピン留めしないこと。
- **`NeverPinsPartialOverFullSpan`**: 部分一致プレフィックスより完全スパン候補を優先すること。
- **`ClipboardNeverIntrudesDuringActiveInput`**: 入力中・音声アクティブ時にクリップボード提案が割り込まないこと。

### 3.3 [`custom_dictionary_ui.als`](app/src/test/resources/alloy/custom_dictionary_ui.als)
ユーザー辞書画面（[`DictionaryActivity`](app/src/main/java/com/example/myasr/DictionaryActivity.kt)）および [`DictionaryRepository`](app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt) の状態遷移をモデル化し、以下を検証する：
- **`ActiveAndTombstoneDisjoint`**: アクティブ単語と削除墓標（Tombstone）が同一キーで同時存在しないこと。
- **`UndoSlotConsistent` / `UndoRestoresDeletedAndClearsTombstone`**: 削除直後の Undo が確実に単語を復元し Tombstone を除去すること。
- **`VisibleSubsetOfActive` / `UnfilteredShowsAllActive` / `SearchFilterPreservesRepository`**: 検索フィルタがリポジトリ状態を変更せず、常に整合した部分集合のみを表示すること。

### 3.4 [`main_app_ui_state.als`](app/src/test/resources/alloy/main_app_ui_state.als)
メインアプリ画面（[`MainActivity`](app/src/main/java/com/example/myasr/MainActivity.kt) v2）の 3 タブ構成・DMN スマートガイド・プロビジョニング進捗状態をモデル化し、以下を検証する：
- **`ReadyImpliesAllPrerequisitesMet`**: ホームの Hero Action が `HeroReady` のとき、IME有効化・選択・日ASR・辞書導入・最新性がすべて揃っていること。
- **`TabSwitchPreservesState`**: タブ切替がプロビジョニングやセットアップ状態・AI設定を破壊しないこと。
- **`SlmSelectorHiddenWhenDisabled`**: AI エンジン OFF 時はエンジン選択 UI が必ず非表示であること。
- **`GlobalBannerVisibleDuringProvisioning`**: バックグラウンド処理中はタブを問わず進捗バナーが表示されること。

### 3.5 [`onboarding_flow.als`](app/src/test/resources/alloy/onboarding_flow.als)
オンボーディングフロー（[`OnboardingActivity`](app/src/main/java/com/example/myasr/OnboardingActivity.kt) / [`OnboardingDecisionModel`](app/src/main/java/com/example/myasr/context/ime/domain/OnboardingDecisionModel.kt)）の状態遷移とコーナーケースをモデル化し、以下を検証する：
- **`ExistingUserNeverForcedIntoOnboarding`**: 既存ユーザー（IME有効かつモデル/辞書あり）はアップデート時に強制オンボーディングされないこと。
- **`RevokedDrivePermissionNeverReportedConnected`**: Auto Backup 等で URI 文字列のみ残り SAF 権限が失効している場合、決して「連携済み」と誤判定されないこと。
- **`DownloadInProgressNeverBlocksCompletion`**: バックグラウンドダウンロード中・失敗時でも完了ステップへ到達できること（非ブロッキング性）。
- **`AllModesCanReachCompletion`**: 新規・再インストール・Google Drive 復元の全モードでデッドロックなく完了できること。


