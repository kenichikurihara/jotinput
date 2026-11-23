# DMN (Decision Model and Notation) in JotInput

## 1. 導入の目的

JotInput では、DDD の `Judgement<in I, out O>` 契約によってドメイン意思決定ロジックを UI / Android Framework (`VoiceInputMethodService`) から分離した。さらに **DMN (Decision Model and Notation)** を導入することで、以下の課題を一歩進めて解決する：

1. **宣言的な決定表（Decision Table）**: 手続き的な `when` / `if-else` の羅列を、ルール ID・説明・優先度・Hit Policy を持つ構造化された決定表へ昇華させる。
2. **Hit Policy による意図の明文化**:
   - `UNIQUE (U)`: 相互排他なルール集合（重複マッチは即座に例外で検知）
   - `FIRST (F)`: 上から順に評価するガード節 / フォールバック連鎖
   - `PRIORITY (P)`: 複数条件が同時に成立した際の明示的優先度解決（例: `SLM > USER > CAPS > SPELL`）
   - `ANY (A)`: 複数ルールが同一結論に合意することを保証
   - `COLLECT (C)` / `RULE_ORDER (R)`: 該当する全ルールの出力収集
3. **完全な監査可能性（Auditability / Traceability）**:
   - `evaluateWithTrace(input)` により、`decisionName`、`hitPolicy`、`matchedRuleIds`、`selectedRuleId`、`usedDefaultFallback`、`result` を取得でき、デバッグおよび単体テストの検証性を高める。
4. **Judgement とのシームレスな統合**:
   - `DecisionTable<I, O>` は `Judgement<I, O>` を実装しているため、既存の DDD `Judgement` 利用箇所を破壊せず透過的に利用できる。

---

## 2. コアエンジン (`shared/domain/dmn/DmnEngine.kt`)

- `HitPolicy`: `UNIQUE`, `FIRST`, `PRIORITY`, `ANY`, `COLLECT`, `RULE_ORDER`
- `DecisionRule<I, O>`: `id`, `description`, `priority`, `condition`, `output`
- `DecisionTable<I, O> : Judgement<I, O>`: `evaluate(input)`, `evaluateWithTrace(input)`, `evaluateAll(input)`, `describeTable()`
- `DmnFeel`: `any`, `eq`, `neq`, `oneOf`, `inRange`, `lt`, `lte`, `gt`, `gte` などの FEEL 風述語ヘルパ
- `decisionTable(...)`: 型安全な Kotlin DSL
- `composeWith(...)`: 複数の `DecisionTable` を連結し、全段の `DecisionTrace` を保持する DRG（Decision Requirements Graph）合成

---

## 3. 導入済み DMN 決定表一覧

| Bounded Context | クラス / オブジェクト | DecisionTable 名 | Hit Policy | 役割 |
|---|---|---|---|---|
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `CandidateRerankExecution` | `FIRST` | SLM リランキング実行可否・デバウンス時間 (40ms / 4ms) |
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `TapSafeCandidateOrdering` | `FIRST` | Policy ⑤ タップ安全ウィンドウ保護（100ms / タッチ中ピン留め） |
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `ViewportAwareCandidateOrdering` | `FIRST` | ゼロ・ジッター候補UI（50ms待機・AI予約枠・可視領域凍結・画面外リランク） |
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `NextPhraseSlmRerank` | `FIRST` | 次文節予測の SLM リランキング適用可否 |
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `ZeroQueryHorizonPolicy` | `FIRST` | 確定直後ゼロクエリ候補における複合チャンクと短語幹/ピボット助詞の Slot 2 ペアリング方針 |
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `SpeculativeContextWarming` | `FIRST` | 文節確定・起動・カーソル移動・キー押下・Composing安定時の投機的 KV ウォーミング可否 |
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `PredictionRestorationPolicy` | `FIRST` | バックスペース削除・カーソル復帰時のチェックポイント予測候補復元／再計算／空ステータス裁定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishAutoAssistBypass` | `FIRST` | QWERTY モード・対象フィールドによる英語アシストバイパス判定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishWordCandidateEligibility` | `FIRST` | 英語単語候補生成の適格性判定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishGecClauseEligibility` | `FIRST` | GEC（文法/文脈修正）対象節の抽出と適格性判定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishCandidateBadge` | `PRIORITY` | 候補バッジ選択（`SLM`(100) > `USER`(80) > `CAPS`(60) > `SPELL`） |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishPrefixCompletionPolicy` | `FIRST` | 英語プレフィックス補完と既知短語保護（`NONE` / `PROTECT_EXACT_WORD` / `PROMOTE_PREFIX_COMPLETION` / `RAW_AND_TYPO_ONLY`） |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishOtaOverlayAdmissionPolicy` | `FIRST` | OTA 拡張英語語彙の安全受入・コア短語（4文字以下）保護・容量上限制御（`REJECT_*` / `ADMIT_NEW_WORD` / `UPGRADE_EXISTING_FREQ` / `KEEP_BUNDLED`） |
| **BC-8: Clipboard** | `ClipboardSuggestionJudgement` | `ClipboardSuggestionEligibility` | `FIRST` | アイドル時のクリップボード提案チップ表示可否 |
| **BC-8: Clipboard** | `ClipboardSuggestionJudgement` | `SafeClipboardHandling` | `FIRST` | 機密クリップ（`EXTRA_IS_SENSITIVE`）・パスワード欄での非永続化・マスク表示・非学習裁定 |
| **BC-7: IME Session & UI** | `MainAppUiDecisionModel` | `HomeHeroActionDecisionTable` | `FIRST` | ホーム画面のスマートガイド（次の唯一アクション / 準備完了）決定 |
| **BC-7: IME Session & UI** | `MainAppUiDecisionModel` | `ProvisioningBannerDecisionTable` | `FIRST` | タブ横断グローバル進捗バナーの表示・進捗状態決定 |
| **BC-7: IME Session & UI** | `MainAppUiDecisionModel` | `AiEngineStatusDecisionTable` | `FIRST` | AI 変換エンジンのセレクタ表示・ステータスバッジ・ダウンロードCTA表示決定 |
| **BC-7: IME Session & UI** | `MainAppUiDecisionModel` | `SlmDownloadPromptDecisionTable` | `FIRST` | 未導入 SLM モデル選択時のダウンロード確認ダイアログ提案可否決定 |
| **BC-7: IME Session & UI** | `MainAppUiDecisionModel` | `RerankEngineSelectionDecisionTable` | `FIRST` | リランクON/OFF切替時のモデル選定・Qwen3.5-0.8Bデフォルト選定・整合性不変条件保護 |
| **BC-7: IME Session & UI** | `MainAppUiDecisionModel` | `BottomNavVisibilityDecisionTable` | `FIRST` | IME 表示時のボトムナビゲーション非表示（キーボード連動・無駄なスクロール/ホワイトスペース排除）決定 |
| **BC-7: IME Session & UI** | `ImeHeaderDecisionModel` | `ImeHeaderVisibilityDecisionTable` | `FIRST` | フリック入力（キー押下中）・未確定中におけるマイク・辞書・クリップボードアイコン非表示と候補表示幅最大化決定 |
| **BC-7: IME Session & UI** | `OnboardingDecisionModel` | `OnboardingLaunchDecisionTable` | `FIRST` | 起動時オンボーディング要否・再インストール/既存移行判定 |
| **BC-7: IME Session & UI** | `OnboardingDecisionModel` | `DriveRestoreStatusDecisionTable` | `FIRST` | Google Drive 履歴復元・SAF権限失効検知と表示状態決定 |
| **BC-7: IME Session & UI** | `OnboardingDecisionModel` | `OnboardingStepGuidanceDecisionTable` | `FIRST` | 各ステップのCTA・非ブロッキング進行・スキップ制御決定 |
| **BC-7: IME Session & UI** | `OnboardingDecisionModel` | `OnboardingSlmProposalDecisionTable` | `FIRST` | オンボーディング Step 1 における Qwen3.5 モデル取得提案状態決定 |
| **BC-1: Kana-Kanji & Sync** | `HistorySyncDecisionModel` | `SyncScheduleDecisionTable` | `FIRST` | 定期バックグラウンド同期ジョブの登録・停止方針決定 |
| **BC-1: Kana-Kanji & Sync** | `HistorySyncDecisionModel` | `SettingsSyncCardDecisionTable` | `FIRST` | 設定タブ Google Drive 同期カードのUI状態・操作可否決定 |
| **BC-3: Speech Recognition** | `VoiceCorrectionDecisionModel` | `VoiceCorrectionEligibilityDecisionTable` | `FIRST` | 音声確定後編集の修正検知妥当性・意味的書き換え除外判定 |
| **BC-3: Speech Recognition** | `VoiceCorrectionDecisionModel` | `VoiceCorrectionBoostPolicyDecisionTable` | `FIRST` | 学習済み音声修正の3段階昇格・SLM文脈整合性ゲート・自己修復抑制決定 |

---

## 4. アーキテクチャ適合度関数（Konsist）

[`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) により以下が CI で自動検証される：
- `com.example.myasr.shared.domain.dmn..` は `infrastructure`・`presentation`・`android.*` に依存しない純粋 Kotlin であること。
- `*DecisionTable` / `*DecisionModel` クラスは `..domain..` パッケージに配置されること。
