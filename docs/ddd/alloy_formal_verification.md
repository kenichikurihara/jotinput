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
- **`SlmGateTableIsTotal` / `SlmGateNeverInvokesWhenUnreadyOrSingle` / `SlmGateAlwaysSkipsUncontestedUserDictWithoutTypo` / `SlmGateNeverSkipsExplicitKanjiHomophone` / `SlmGateNeverSkipsCompetingUserOrTypo`**: Idea A1 動的 SLM 起動ゲート（`SlmInvocationGate`）の網羅性・単一/未準備スキップ・無競合ユーザー辞書Top1保護・明示的漢字同音異義語およびタイポ/競合ユーザー辞書の不変保証を検証すること。
- **`ClipboardNeverIntrudesDuringActiveInput`**: 入力中・音声アクティブ時にクリップボード提案が割り込まないこと。

### 3.3 [`custom_dictionary_ui.als`](app/src/test/resources/alloy/custom_dictionary_ui.als)
ユーザー辞書画面（[`DictionaryActivity`](app/src/main/java/com/example/myasr/DictionaryActivity.kt)）、DMN 入力・UI 決定モデル（[`DictionaryInputDecisionModel`](app/src/main/java/com/example/myasr/context/vocabulary/domain/DictionaryInputDecisionModel.kt)）、および [`DictionaryRepository`](app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt) の状態遷移をモデル化し、以下を検証する：
- **`ActiveAndTombstoneDisjoint`**: アクティブ単語と削除墓標（Tombstone）が同一キーで同時存在しないこと。
- **`UndoSlotConsistent` / `UndoRestoresDeletedAndClearsTombstone`**: 削除直後の Undo が確実に単語を復元し Tombstone を除去すること。
- **`VisibleSubsetOfActive` / `UnfilteredShowsAllActive` / `SearchFilterPreservesRepository`**: 検索フィルタがリポジトリ状態を変更せず、常に整合した部分集合のみを表示すること。
- **`FormPolicyIsTotal` / `ReadingFieldAlwaysVisibleForAllInputs`**: `DictionaryReadingFormUiPolicy` が全入力に対して一意のガイド状態へ解決され、ASCII/英単語入力時を含め常に「よみ」入力欄が表示され続けること。
- **`RegistrationPolicyIsTotal` / `AsciiWordWithValidKanaAlwaysRegistersBilingual` / `AsciiWordWithoutReadingStillAllowsOneStepEnglish` / `AsciiWordWithInvalidNonKanaReadingRejected`**: `DictionaryEntryRegistrationPolicy` が全入力に対して一意に解決され、ASCII単語＋かな読みで日英バイリンガル登録、ASCII単語＋空欄で英語1ステップ登録、ASCII単語＋非かな読みでエラー拒否されること。

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
- **`token0FastPathSkip` / `zeroDecodeNextPhrase` (`canRunToken0AndZeroDecodeNextPhrase`)**: `NEVER_TOKEN0_ONLY` による Stage 2 バイパスおよび 0-decode `NextPhrase` が KV キャッシュ長・位置連続性・Logits 整合性を一切破壊せず実行到達可能であること。

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

### 3.13 [`english_prefix_completion.als`](app/src/test/resources/alloy/english_prefix_completion.als)
英語プレフィックス補完の DMN 決定表（[`EnglishAssistJudgement`](app/src/main/java/com/example/myasr/context/english/domain/EnglishAssistJudgement.kt) `EnglishPrefixCompletionPolicy`）およびランキング不変条件（[`BayesianCaseRanker`](app/src/main/java/com/example/myasr/context/english/domain/BayesianCaseRanker.kt)）をモデル化し、以下を検証する：
- **`PrefixPolicyTableIsTotal`**: 全入力組み合わせに対して一意のルール・戦略へ解決されること。
- **`KnownExactShortWordProtectedAtRank1`**: 既知単語（`an`, `tail` 等）入力時は拡張候補（`and`, `Tailscale`）より完全一致単語が Rank 1 に保護されること。
- **`DeadEndOovPrefixPromotesCompletionToRank1`**: 未完成 OOV プレフィックス（`tailsc` 等）で辞書補完候補が存在するときは、OOV 生断片や短縮タイポよりプレフィックス補完候補が Rank 1 へ昇格すること。
- **`RawEscapeHatchAlwaysPresent`**: 生入力文字列が常にエスケープハッチとして候補プールへ保持されること。

### 3.14 [`english_ota_overlay_lifecycle.als`](app/src/test/resources/alloy/english_ota_overlay_lifecycle.als)
英語拡張辞書の OTA オーバーレイ適用・リロード・削除ライフサイクルおよび DMN 決定表（[`EnglishAssistJudgement`](app/src/main/java/com/example/myasr/context/english/domain/EnglishAssistJudgement.kt) `EnglishOtaOverlayAdmissionPolicy`）をモデル化し、以下を検証する：
- **`OtaAdmissionTableIsTotal`**: 全入力組み合わせに対して一意のルール・アクションへ解決されること。
- **`CoreShortLowerWordNeverHijackedByOta`**: 長さ 4 文字以下のコア基本語（`tail`, `the`, `an` 等）が OTA 語彙で決して上書きされないこと。
- **`InvalidOrOverCapacityNeverAdmitted`**: 不正フォーマット・不正スコア・容量上限超過エントリが決して受理されないこと。
- **`UserCustomDictionaryIsolatedFromOtaOps`**: OTA 導入・更新・削除が `custom_dictionary.json`（ユーザー辞書）から完全に隔離されていること。
- **`OtaDeleteRestoresBundledLexicon`**: OTA 削除後に即座に内蔵辞書のみのクリーン状態へ復元されること。

### 3.15 [`github_app_update_lifecycle.als`](app/src/test/resources/alloy/github_app_update_lifecycle.als)
Debug 限定 GitHub Actions ビルド更新チェック・バックグラウンドダウンロード・インストール導線（[`AppUpdateDecisionModel`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateDecisionModel.kt)）をモデル化し、以下を検証する：
- **`ReleaseBuildNeverShowsCard`**: 非 Debug ビルドでは更新カードが常に非表示であること。
- **`NoTokenFailSafeNeverOffersDownload`**: GitHub トークン未設定時は（一致済ローカル APK 不在時）ダウンロードやインストールを提示せず Fail-Safe すること。
- **`ErrorStateNeverOffersDownloadOrInstall`**: 認証・ネットワーク・ダウンロード失敗状態において誤ってダウンロードやインストールを提示しないこと。
- **`DownloadOnlyWhenTokenAndUpdateAvailable`**: ダウンロード CTA は Debug かつトークン有効かつ更新検知時のみ提示されること。
- **`InstallOnlyWhenMatchingApkExistsAndDebug`**: インストール CTA は Debug かつ整合 APK 保持時のみ提示されること。
- **`UpToDateNeverOffersDownloadOrInstall`**: 最新判定時は再確認のみを提示すること。
- **`ActiveDownloadAlwaysShowsProgressAndCancel`**: バックグラウンド DL/待機中は進捗バーとキャンセル CTA を必ず表示すること。

### 3.16 [`swipe_extended_lexicon_and_cache.als`](app/src/test/resources/alloy/swipe_extended_lexicon_and_cache.als)
スワイプ大規模語彙拡張（Idea A Compact CSR Dual-Trie + Idea C Adaptive LRU Cache）の DMN 決定表（[`SwipeVocabularyJudgement`](app/src/main/java/com/example/myasr/context/english/domain/SwipeVocabularyJudgement.kt)）および状態遷移不変条件をモデル化し、以下を検証する：
- **`Pass2TriggerTableIsTotal`**: `SwipeExtendedPass2TriggerPolicy` が全入力組み合わせに対して一意のルール・アクションへ解決されること。
- **`ConfidentBaseNeverTriggersPass2` / `MissingLexiconNeverTriggersPass2`**: Base 32k 高確信一致時および拡張辞書不在時に Pass 2 CSR 探索が決して発火しないこと。
- **`AdaptiveAdmissionTableIsTotal`**: `SwipeAdaptiveCacheAdmissionPolicy` が全入力に対して一意に解決されること。
- **`SensitiveOrPasswordNeverCached`**: パスワード欄・機密クリップからの確定語が適応キャッシュにも保留バッファにも絶対に入らないこと。
- **`SingleUnverifiedRawTypoNeverEntersActiveCache`**: 未検証スペース確定の単発入力が即座にアクティブキャッシュに入らず 1-shot ガードに留保されること。
- **`CustomDictAndBaseVocabIsolatedFromAdaptiveCache`**: `custom_dictionary.json` および Base 32k 語彙が適応キャッシュから厳格に隔離・維持されること。

### 3.17 [`english_voice_user_dict.als`](app/src/test/resources/alloy/english_voice_user_dict.als)
英語音声入力（Parakeet TDT-CTC 110M）におけるユーザー辞書優先適用ポリシー（[`EnglishUserDictDecisionModel`](app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt) `EnglishVoiceUserDictResolutionPolicy`）およびストレージ隔離不変条件をモデル化し、以下を検証する：
- **`ResolutionTableIsTotal`**: `EnglishVoiceUserDictResolutionPolicy` があらゆる入力組み合わせに対して一意のアクションへ解決されること。
- **`DisabledOrAlreadyExactNeverMutates`**: 無効化された辞書項目・一致なし・すでに表層完全一致の語に対して一切の置換・チップ提案を行わないこと。
- **`ShortTargetNeverFuzzyReplaces`**: 3文字以下の短縮語に対して非完全一致（音韻類似・部分列一致）による自動置換を禁止し誤爆を防止すること。
- **`RevertedNonExactNeverAutoReplaces`**: ユーザーによりリバートされたペアに対して非完全一致での再自動置換を禁止し、1タップ提案チップへ降格すること。
- **`CommonWordCollisionNeverBlindlyAutoReplaces`**: 一般英単語と衝突する候補（`Go`, `Mosque`, `consists` 等）が先行文脈・SLM文脈整合性ゲートの確認なしに盲目的に自動置換されないこと。
- **`CustomDictionaryNeverMutatedByVoiceResolution`**: 音声解決・リバート記録・チップ承認の遷移が `custom_dictionary.json`（ユーザー辞書）を一切変更・消去しないこと。

---

## 4. 自動同期ルール
- `app/src/test/resources/alloy/` 配下の全 `.als` ファイルは [`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) により動的にスキャン・自動検証される。
- 新規 `.als` ファイルを追加した際は、本ドキュメントのセクション 3 にも概要を追記すること（[`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) が未記載の `.als` を検知する）。


