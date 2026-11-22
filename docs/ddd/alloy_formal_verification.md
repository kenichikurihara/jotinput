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
- **`NeverPinsHighUtilityPredictionWhenNotTouching` / `PinsPredictionWhenUserTouchingAfterWindow`**: 非タッチ時は高効用予測候補（`TAP_SAFE_R4`）の Slot 1 昇格を許可し、タッチ中は 100ms ウィンドウ経過後の予測昇格を確実にピン留め阻止すること。
- **`ViewportTableIsTotal` / `MicroWaitBypassesPlaceholder` / `ActiveTouchDefersUpdate` / `Phase2PreservesViewportWhenNotTouching`**: ゼロ・ジッター候補UI（`ViewportAwareCandidateOrdering`）の 50ms マイクロ待機バイパス・タッチ中更新保留・可視領域凍結（画面外のみリランク）を網羅的に検証すること。
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
- **`RerankEnabledImpliesValidModel`**: AI リランクが ON のとき、モデルは必ず有効なモデル（Qwen3.5-0.8B または Qwen3.5-2B）が選択されており、モデル未選択（NONE）のまま放置されないこと。
- **`RerankDisabledImpliesEngineNone`**: AI リランクが OFF のとき、エンジンは必ず NONE に解決されること。
- **`BottomNavHiddenWhenImeActive`**: IME 入力中（`imeInputActive = True`）のときは、ボトムナビゲーションバーが必ず非表示（`showBottomNav = False`）となり、キーボード上の縦領域を最大化してタブの押し上げと無駄なホワイトスペースを完全に抑止すること。
- **`NeverPromptDownloadWhenInstalledOrDisabled` / `DownloadCtaOnlyWhenEnabledAndMissing`**: 導入済み・無効化済み・ダウンロード中の SLM モデルに対して重複ダウンロード確認やインライン取得 CTA を表示しないこと。


### 3.5 [`onboarding_flow.als`](app/src/test/resources/alloy/onboarding_flow.als)
オンボーディングフロー（[`OnboardingActivity`](app/src/main/java/com/example/myasr/OnboardingActivity.kt) / [`OnboardingDecisionModel`](app/src/main/java/com/example/myasr/context/ime/domain/OnboardingDecisionModel.kt)）の状態遷移とコーナーケースをモデル化し、以下を検証する：
- **`ExistingUserNeverForcedIntoOnboarding`**: 既存ユーザー（IME有効かつモデル/辞書あり）はアップデート時に強制オンボーディングされないこと。
- **`RevokedDrivePermissionNeverReportedConnected`**: Auto Backup 等で URI 文字列のみ残り SAF 権限が失効している場合、決して「連携済み」と誤判定されないこと。
- **`DownloadInProgressNeverBlocksCompletion`**: バックグラウンドダウンロード中・失敗時でも完了ステップへ到達できること（非ブロッキング性）。
- **`AllModesCanReachCompletion`**: 新規・再インストール・Google Drive 復元の全モードでデッドロックなく完了できること。

### 3.6 [`history_sync_settings.als`](app/src/test/resources/alloy/history_sync_settings.als)
設定タブの Google Drive 履歴同期と定期バックグラウンド同期（[`HistorySyncDecisionModel`](app/src/main/java/com/example/myasr/context/kanakanji/domain/HistorySyncDecisionModel.kt)）をモデル化し、以下を検証する：
- **`PeriodicJobOnlyWhenEnabledAndPermitted`**: 自動同期ON・URI設定済・SAF権限有効の3条件が揃ったときのみ定期ジョブがスケジュールされること。
- **`PermissionLostCancelsJobAndShowsWarning`**: 権限失効時は定期ジョブを停止し警告表示とすること。
- **`ChangingSyncUriResetsSnapshot`**: 同期先ファイル変更時は旧 Base Snapshot が必ずリセットされること。

### 3.7 [`english_auto_space_period.als`](app/src/test/resources/alloy/english_auto_space_period.als)
英語オートスペース・ダブルスペース自動ピリオド・Android `onUpdateSelection` 同期（[`VoiceTouchInputArbitrator`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt)）の状態遷移をモデル化し、以下を検証する：
- **`NoIllegalAutoPeriod` / `NoTripleSpaceDoublePeriod`**: バイパス時・文頭・非対象文字後・タイムアウト後の誤発動や連続ピリオド（`".. "`）を禁止すること。
- **`PunctuationNeverPrependsSpace` / `NoDoubleSpaceOnAutoSpace`**: 句読点直前のオートスペース挿入や二重スペース挿入を禁止すること。
- **`DoubleSpaceSurvivesImeEcho` / `CandidateAutoSpaceSurvivesImeEcho` / `ExternalMoveClearsAutoAssist`**: IME 自身の編集エコーでは状態を保持し、外部カーソル移動時のみクリアすること。

### 3.8 [`voice_correction_learning.als`](app/src/test/resources/alloy/voice_correction_learning.als)
日英音声修正学習（[`VoiceCorrectionDecisionModel`](app/src/main/java/com/example/myasr/context/speech/domain/VoiceCorrectionDecisionModel.kt) / [`VoiceCorrectionRepository`](app/src/main/java/com/example/myasr/context/speech/infrastructure/VoiceCorrectionRepository.kt)）の安全性・整合性をモデル化し、以下を検証する：
- **`NoSemanticRewritePollution`**: 編集ウィンドウ外・不正スパン・音韻距離外の単なる言い換え（意味的書き換え）が学習履歴を汚染しないこと。
- **`ReverseEditSafetyInvariant`**: ユーザーのリバート（`netCount <= 0`）後に即座に `BoostSuppressed` へ戻り、誤置換を繰り返さないこと。
- **`AmbiguousHomophoneGateSafety`**: 同音異義語・近音語において SLM 文脈整合性ゲートが棄却した場合は決して自動置換せず提案チップに留めること。
- **`CustomDictionaryIsolationInvariant`**: 学習履歴の追加・削除・クリアがいかなる場合も `custom_dictionary.json`（ユーザー辞書）を変更・消去しないこと。

### 3.9 [`slm_kv_cache_lifecycle.als`](app/src/test/resources/alloy/slm_kv_cache_lifecycle.als)
オンデバイス SLM（[`LlmReranker`](app/src/main/cpp/llm_reranker.cc)）の KV キャッシュ・Prefix-Tree ロールバック・Undo 短縮・RoPE 位置シフトの整合性をモデル化し、以下を検証する：
- **`TreeRollbackRestoresExactContextLength`**: Prefix-Tree Stage 2 一括デコードとロールバック後、常に `kvCacheLen = contextLen` に戻り投機ブランチトークンが残留しないこと。
- **`UndoPrefixTruncateNeverReturnsStaleLogits`**: Undo による文脈短縮時、`SnapshotRing` からの復元により古い文脈の Logits を返さないこと。
- **`RoPEShiftPreservesRelativePositions`**: 先頭トリミング時の RoPE 位置シフト（`seq_add`）後も位置インデックスの連続性が保たれること。

### 3.10 [`ime_header_visibility.als`](app/src/test/resources/alloy/ime_header_visibility.als)
IME ヘッダーバーのユーティリティアイコン表示裁定（[`ImeHeaderDecisionModel`](app/src/main/java/com/example/myasr/context/ime/domain/ImeHeaderDecisionModel.kt) / `ImeHeaderVisibilityDecisionTable`）をモデル化し、以下を検証する：
- **`HeaderTableIsTotal`**: 全入力組み合わせに対して一意のルールへ解決されること。
- **`VoiceToggleAlwaysVisibleWhenRecordingOrDecoding`**: 音声録音中・デコード中に停止ボタン／スピナーが隠れないこと。
- **`IconsHiddenDuringComposingOrFlickWhenVoiceIdle`**: 音声待機時かつクリップボード非表示時に、フリックタッチ中または未確定文字列存在時は 3 アイコン（マイク・辞書・クリップボード）がすべて非表示になること。
- **`AllIconsVisibleWhenFullyIdle` / `ClipboardOpenKeepsAllIconsVisible`**: 完全待機時およびクリップボード履歴パネル表示時は 3 アイコンすべてが表示されること。

### 3.11 [`safe_password_clipboard.als`](app/src/test/resources/alloy/safe_password_clipboard.als)
機密クリップボード（パスワードマネージャー等からの `EXTRA_IS_SENSITIVE` コピー）およびパスワード入力欄の安全裁定（[`ClipboardSuggestionJudgement`](app/src/main/java/com/example/myasr/context/clipboard/domain/ClipboardSuggestionJudgement.kt) / `SafeClipboardHandling`）をモデル化し、以下を検証する：
- **`SafeClipboardTableIsTotal`**: 全入力組み合わせに対して一意のルールへ解決されること。
- **`SensitiveClipNeverPersistedToDisk`**: 機密クリップはディスク永続化（`clipboard_history.json`）を一切許可しないこと。
- **`SensitiveClipNeverDisplayedPlaintext`**: 機密クリップは候補ストリップ・履歴シートのいずれにも平文表示しないこと。
- **`SensitiveOrPasswordFieldNeverLearns`**: 機密クリップまたはパスワード入力欄では次文節予測・学習履歴への記録を一切許可しないこと。
- **`FreshSensitiveClipIsMaskedAndAccessible`**: 有効期限内で未解除の機密クリップはマスク表示（`ModeMaskedSensitive`）で利用可能であること。

### 3.12 [`prediction_checkpoint_restoration.als`](app/src/test/resources/alloy/prediction_checkpoint_restoration.als)
予測候補選択後のバックスペース削除／カーソル復帰による予測一覧復元と履歴ロールバック（[`CandidateRerankJudgement`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt) `PredictionRestorationPolicy` および [`VoiceTouchInputArbitrator`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) `PredictionCheckpoint`）をモデル化し、以下を検証する：
- **`RestorationTableIsTotal`**: `PredictionRestorationPolicy` があらゆる入力に対して一意のルール・アクションへ解決されること。
- **`NeverRestoresInPasswordOrNonKanaOrComposing`**: 非かなモード・入力中・パスワード欄では常に `ActShowEmptyStatus` となること。
- **`CheckpointMatchAlwaysRestoresPredictions`**: かなモード・待機中・非パスワード欄で一致チェックポイントが存在すれば必ずチェックポイント復元を選択すること。
- **`BlankOrNonPredictableWithoutCheckpointShowsEmptyStatus`**: 一致チェックポイントがなく空白または非予測末尾の場合は空ステータスを表示すること。
- **`DeleteSelectedPredictionRestoresExactCandidatesAndRevertsHistory`**: 「確定 → 予測候補選択 → 削除復帰」の遷移後、直前の確定境界・表示候補スナップショット・学習履歴状態が厳密に復元されること。

---

## 4. 自動同期ルール
- `app/src/test/resources/alloy/` 配下の全 `.als` ファイルは [`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) により動的にスキャン・自動検証される。
- 新規 `.als` ファイルを追加した際は、本ドキュメントのセクション 3 にも概要を追記すること（[`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) が未記載の `.als` を検知する）。


