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
| **BC-2: Neural Reranking** | `CandidateRerankJudgement` | `NextPhraseSlmRerank` | `FIRST` | 次文節予測の SLM リランキング適用可否 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishAutoAssistBypass` | `FIRST` | QWERTY モード・対象フィールドによる英語アシストバイパス判定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishWordCandidateEligibility` | `FIRST` | 英語単語候補生成の適格性判定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishGecClauseEligibility` | `FIRST` | GEC（文法/文脈修正）対象節の抽出と適格性判定 |
| **BC-4: English Typing** | `EnglishAssistJudgement` | `EnglishCandidateBadge` | `PRIORITY` | 候補バッジ選択（`SLM`(100) > `USER`(80) > `CAPS`(60) > `SPELL`） |
| **BC-8: Clipboard** | `ClipboardSuggestionJudgement` | `ClipboardSuggestionEligibility` | `FIRST` | アイドル時のクリップボード提案チップ表示可否 |

---

## 4. アーキテクチャ適合度関数（Konsist）

[`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) により以下が CI で自動検証される：
- `com.example.myasr.shared.domain.dmn..` は `infrastructure`・`presentation`・`android.*` に依存しない純粋 Kotlin であること。
- `*DecisionTable` / `*DecisionModel` クラスは `..domain..` パッケージに配置されること。
