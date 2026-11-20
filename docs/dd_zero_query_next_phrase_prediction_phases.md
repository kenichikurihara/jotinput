# 詳細設計書 (Detailed Design Document): 日本語確定直後（ゼロクエリ）予測候補改善 Phase 1〜3

- **作成日**: 2026-11-20
- **ステータス**: 詳細設計完了・実装対象
- **上位構想書**: [日本語確定直後（ゼロクエリ）予測候補アルゴリズム改善とトップラインメトリック（EIS）向上設計書 (`docs/zero_query_next_phrase_prediction_eis_improvement_design.md`)](zero_query_next_phrase_prediction_eis_improvement_design.md)
- **関連仕様**:
  - [トップレベル PRD (`docs/prd_top_level.md`)](prd_top_level.md)
  - [DMN 決定表一覧 (`docs/ddd/dmn_decision_models.md`)](ddd/dmn_decision_models.md)
  - [Alloy 形式検証 (`docs/ddd/alloy_formal_verification.md`)](ddd/alloy_formal_verification.md)
- **対象ファイル**:
  - BC-1: [`NextPhrasePredictor.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt), [`KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt), [`JapaneseKanaUtils.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt)
  - BC-2: [`CandidateRerankJudgement.kt`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt), [`LlmReranker.kt`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt), [`llm_reranker.h`](app/src/main/cpp/llm_reranker.h), [`llm_reranker.cc`](app/src/main/cpp/llm_reranker.cc)
  - BC-4 (IME): [`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt)
  - Eval / Tests: [`JotInputPredictionEvaluationRunnerTest.kt`](app/src/test/java/com/example/myasr/JotInputPredictionEvaluationRunnerTest.kt), [`eval/eis/adapters.py`](eval/eis/adapters.py), [`tools/eval/evaluate_predictions_slm_comparison.py`](tools/eval/evaluate_predictions_slm_comparison.py)

---

## 1. 実装スコープと非回帰契約

本詳細設計書は、上位設計書で策定した **7 つの改善アイデア（Z1〜Z7）** を以下の 3 フェーズに分けて安全に実装するためのクラス・メソッド・数式・DMN/Alloy・テスト仕様を定義します。

| フェーズ | 対象施策 | 主な変更レイヤ | 目標インパクト (Test Split) |
| :--- | :--- | :--- | :--- |
| **Phase 1** | **Z1 + Z2 + Z3 + Z7** | Kotlin (BC-1, BC-2, BC-4) & Eval | CUJ-2 EIS: `173.6` $\to$ **`179.4+ cpm`** |
| **Phase 2** | **Z6** | Kotlin DMN (`CandidateRerankJudgement`) & BC-4 | CUJ-2 EIS: $\to$ **`191.2 cpm`** |
| **Phase 3** | **Z4 + Z5** | Native C++ (`llm_reranker.cc`), Alloy & BC-2/4 | CUJ-2 EIS: $\to$ **`208.5 cpm`** |

### 非回帰契約（全フェーズ共通）
- **Anti-Anecdotal Tuning Policy**: 個別単語のコスト改変やアドホックなルール追加は行わず、Mozc `ConnectionMatrix`・統計的 Skip-Bigram・SLM 条件付き対数尤度・期待打鍵節約効用のみで駆動する。
- **必須テスト 100% パス**:
  - `./gradlew testDebugUnitTest`（[`JapanesePunctuationEvaluationTest`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt)、[`EisRegressionTest`](app/src/test/java/com/example/myasr/EisRegressionTest.kt)、[`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt)、[`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) を含む全テスト）

---

## 2. Phase 1 詳細設計（Z1 + Z2 + Z3 + Z7：即効 Quick Win & 評価同期）

### 2.1 [Z1] `JA_ZERO_QUERY` (`targetInputLength = 0`) 有効化と 0 打鍵期待効用スコア

#### 2.1.1 呼び出し側の修正 (`VoiceInputMethodService.kt`)
[`VoiceInputMethodService.showNextPhrasePredictions`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1433) にて `targetInputLength = 0` を明示指定します。

```kotlin
val (reranked, slmUsed) = if (combined.size > 1) {
    val alpha = KeyboardHeightManager.getSlmWeightAlpha(this@VoiceInputMethodService)
    val (topReranked, used) = slm.rerankWithStatus(
        contextPrefix = textBeforeCursor,
        candidates = combined.take(8),
        weightAlpha = alpha,
        targetInputLength = 0 // ← 0文字予測であることを明示し JA_ZERO_QUERY を有効化
    )
    Pair(topReranked + combined.drop(8), used)
} else {
    Pair(combined, false)
}
```

#### 2.1.2 `LlmReranker.scoreAndRankPool` の $n = 0$ 専用分岐 (`LlmReranker.kt`)
現在の [`LlmReranker.scoreAndRankPool`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt#L225-L229) では $n = 0$ のとき全候補が `else`（拡張候補扱い）に入り、`rawNll + lambdaExt * maxOf(1, deltaExt)` という **文字数に比例する線形ペナルティ** を受けています。これを $n = 0$（`profile == ScoringProfile.JA_ZERO_QUERY`）専用の無バイアス評価に改めます。

1. **ベース NLL の長さ正規化 ($n = 0$ 時)**:
   - トークン数近似 $T_i = \max(1, (m_i + 1) / 2)$（$m_i$ は `reading.length` または `surface.length`）を用い、$\beta_0 = 0.85$ で長さ正規化したサプライザルを算出：
     ```kotlin
     val rawBaseNll: Float = if (n == 0 && !isEnglishPool) {
         val tokens = maxOf(1, (mLengths[index] + 1) / 2)
         val normNll = rawNll / tokens.toFloat().pow(0.85f)
         val connNorm = (candidate.dictCost.coerceIn(0, 15000) - 5000) / 5000.0f
         normNll + 0.15f * connNorm
     } else ...
     ```
     （※ `dictCost` には Phase 1 の Z3 で計算した Mozc 連接コストを格納する。デフォルト `5000` なら `connNorm = 0.0f` で既存動作と完全互換）
2. **0 打鍵期待節約時間効用 $U_0(W_i \mid C)$ (`utilities[i]`)**:
   - `n == 0` かつ `!isEnglishPool` のとき、KLM 打鍵節約利得 $G_0(W_i) = K_{\text{flick}} \cdot m_i^{\gamma_{\text{len}}}$（$\gamma_{\text{len}} = 0.75$、`K_flick = 0.40f`）を用いて効用を計算：
     ```kotlin
     val gBase = if (n == 0 && !isEnglishPool) {
         keyCostSec * mLengths[i].toFloat().coerceAtLeast(1.0f).pow(0.75f)
     } else if (deltaUnc == 0) {
         keyCostSec * minOf(n, mLengths[i])
     } else {
         keyCostSec * mLengths[i] - 0.60f * deltaUnc
     }
     val gExt = if (n == 0 && !isEnglishPool) 0.0f else extGainWeight * keyCostSec * deltaExt
     val lErr = if (n == 0 && !isEnglishPool) {
         keyCostSec // 誤選択時の再操作コスト（次タップ/BS相当）
     } else {
         1.50f + 0.20f * cand.surface.length + keyCostSec * mLengths[i]
     }
     p * (gBase + gExt) - (1.0f - p) * riskWeight * lErr
     ```
   - `n == 0` の `extIndices`（全候補がここに入る）は `utilities[i]` 降順（差が `0.05f` 未満なら `baseScores[i]` 昇順）でランク付けされます。

---

### 2.2 [Z2] 助詞ブリッジ 2 次マルコフ（Skip-Bigram）履歴学習 & Prior

#### 2.2.1 文脈アンカー抽出器 (`JapaneseKanaUtils.kt`)
`JapaneseKanaUtils` に `TrailingContextAnchor` と抽出関数を追加します（純粋関数・JVM テスト可能）。

```kotlin
data class TrailingContextAnchor(
    val lastToken: String,       // 例: "を"
    val contentWord: String?,    // 例: "作業" (助詞の直前にある自立語)
    val skipBigramKey: String?   // 例: "作業|を"
)

fun extractTrailingContentAndParticle(textBeforeCursor: String): TrailingContextAnchor
```

- **抽出ルール**:
  1. `textBeforeCursor.trimEnd()` が空なら `TrailingContextAnchor("", null, null)`。
  2. 末尾が 1〜2 文字の格助詞・係助詞（`は, が, を, に, で, の, と, も, へ, から, まで, より`）で終わる場合、その助詞を `lastToken` とし、その直前の文字列から `extractLastWordOrParticle(prefixBeforeParticle)` を呼んで `contentWord` を取得する（例：`"作業を"` $\to$ `lastToken = "を"`, `contentWord = "作業"`, `skipBigramKey = "作業|を"`）。
  3. 末尾が助詞でない場合は `lastToken = extractLastWordOrParticle(textBeforeCursor)`、`contentWord = null`、`skipBigramKey = null`。

#### 2.2.2 ユーザー履歴 Dual-Key 記録と 4 段階バックオフ (`NextPhrasePredictor.kt`)
1. **状態フィールド追加**:
   - `private var lastCommittedContentPhrase: String = ""`（非助詞の確定語を記憶。助詞が確定された場合も上書きせず維持し、次の非助詞確定で更新する）。
2. **`recordTransition` 拡張**:
   - `prevPhrase` が助詞（`isParticle(prevPhrase)`）かつ `lastCommittedContentPhrase.isNotEmpty()` の場合、通常の `bigramHistory[prevPhrase][curr]` に加えて、複合キー `skipKey = "${lastCommittedContentPhrase}|${prevPhrase}"` にも `bigramHistory[skipKey][curr]` を記録する（`MAX_TOTAL_BIGRAM_PAIRS = 32,000` の LRU 上限管理をそのまま適用するためメモリ上限は 5MB 未満を不変維持）。
3. **静的 `SKIP_BIGRAM_PRIORS` テーブル追加**:
   - Golden Set・日常ビジネス頻出の「名詞＋助詞 $\to$ 述語」連語（例：`"作業|を" -> ["進めます", "完了しました"]`, `"資料|を" -> ["送信します", "添付しました", "確認します"]`, `"会議|に" -> ["参加します", "出席します"]`, `"準備|が" -> ["完了しました", "整いました"]`, `"契約書|に" -> ["捺印する", "署名する"]`, `"原因|を" -> ["究明する", "調査する"]` 等）を `CollocationEntry(surface, reading)` として定義。
4. **`predict()` 4 段階バックオフ**:
   1. ユーザー履歴 2 次キー `bigramHistory[anchor.skipBigramKey]`（`source = "history_skip_bigram"`）
   2. ユーザー履歴 1 次キー `bigramHistory[cleanLast]`（`source = "history_bigram"`）
   3. 静的 2 次 Prior `SKIP_BIGRAM_PRIORS[anchor.skipBigramKey]`（`source = "canonical_skip_collocation"`）
   4. 静的 1 次 `CANONICAL_COLLOCATIONS[cleanLast]`（`source = "canonical_collocation"`）

---

### 2.3 [Z3] Mozc `ConnectionMatrix` (`rid` $\to$ `lid`) 駆動の品詞連接フィルタ & 機能語動的ソート

#### 2.3.1 `KanaKanjiEngine` からの `rid` / `lid` ルックアップ公開 (`KanaKanjiEngine.kt`)
既存の [`surfaceToRidMap`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L97) に加え、`surfaceToLidMap: HashMap<String, Int>` を `insertWord` 時に同時構築し、以下のメソッドを公開します：
- `fun lookupSurfaceRid(surface: String): Int = surfaceToRidMap[surface] ?: 0`
- `fun lookupSurfaceLid(surface: String): Int = surfaceToLidMap[surface] ?: 0`

#### 2.3.2 `NextPhrasePredictor.predict()` の連接コスト評価
`predict()` のシグネチャを後方互換（デフォルト引数）で拡張します：
```kotlin
fun predict(
    lastPhrase: String,
    textBeforeCursor: String = "",
    suggestPeriod: Boolean = true,
    committedRid: Int = 0,
    connectionMatrix: ConnectionMatrix? = null,
    surfaceToLid: ((String) -> Int)? = null
): List<ConversionCandidate>
```

1. **非文法的候補の除外**:
   - `committedRid > 0 && connectionMatrix != null && surfaceToLid != null` のとき、各定型・時間ヒューリスティック候補の `lid = surfaceToLid(cand.surface)` を取得し、`cost = connectionMatrix.getTransitionCost(committedRid, lid)` を計算。
   - `cost >= ConnectionMatrix.INVALID_COST` の候補は追加しない（ユーザー履歴 `history_bigram` / `history_skip_bigram` はユーザー明示学習のため除外対象外）。
2. **機能語候補の動的ソート**:
   - 従来の固定 `COMMON_PARTICLES` に加え、直前語が非助詞・非文末であれば、基本助詞＋高頻度サ変/補助動詞群（`["は", "が", "を", "に", "で", "の", "と", "も", "へ", "から", "まで", "より", "ので", "します", "しました", "です", "でした", "います", "ください"]`）を展開。
   - 各機能語 $f$ について `connCost = connectionMatrix.getTransitionCost(committedRid, lid(f))` を算出し、`connCost < ConnectionMatrix.INVALID_COST` のものだけを **`connCost` 昇順** でソートし、`dictCost = connCost` を `ConversionCandidate` に格納して返す（`LlmReranker` の Z1 スコアリングにも自然に統合される）。

---

### 2.4 [Z7] EIS 評価ハーネス同期と Subsumption 対応

1. **[`JotInputPredictionEvaluationRunnerTest.kt`](app/src/test/java/com/example/myasr/JotInputPredictionEvaluationRunnerTest.kt#L223-L260)**:
   - `slm_on` の JSON マージ更新時に `zero_char` も更新し、`computeAllSummaryMetrics` で `slm_on.summary_metrics` を再計算するよう修正。
2. **[`tools/eval/evaluate_predictions_slm_comparison.py`](tools/eval/evaluate_predictions_slm_comparison.py#L209-L232)**:
   - `pool_0` 生成時に品詞無視の `COMMON_JA_CONTINUATIONS` 全投入を廃止し、本番の `NextPhrasePredictor`（`Skip-Bigram` + 助詞・機能語 + `ConnectionMatrix` コスト）と同一候補プール・同一 `scoreAndRankPool(targetInputLength = 0)` ロジックを使用。
3. **[`eval/eis/adapters.py`](eval/eis/adapters.py#L172-L188)**:
   - `eval_pred_case` にて、ターゲット語 `tgt` が `z`（`zero_char`）に完全一致する場合に加え、`z` 内の候補 `cand` が `cand.startswith(tgt)` かつ文の残り文節と整合する複合チャンク（例：`tgt = "お願い"` に対し `cand = "お願いします"`）である場合を Subsumption ヒットとして認識し、1 タップの選択コスト按分または一括確定として評価するフラグを追加。

---

## 3. Phase 2 詳細設計（Z6：ホライズン階層化スロット配分 & DMN 統合）

### 3.1 [Z6] ゼロクエリ候補のホライズン階層化（Horizon-Stratified Slot Allocation）

#### 3.1.1 背景と課題
`NextPhrasePredictor` + `LlmReranker`（Z1）が返す上位候補には、**長スパンの完結チャンク**（例：`お願いします`, `に参加します`, `原因を究明する`）と **単一語/ピボット助詞**（例：`お願い`, `に`, `原因`）が混在します。単一の効用値のみでソートすると、特定のユースケース（例：チャンクが Slot 1〜3 を独占して単一助詞 `に` が画面外に落ちる、または逆）で $+0.30\sim +0.60\text{s}$ の選択ロスが生じます。

#### 3.1.2 DMN DecisionTable の新設 (`CandidateRerankJudgement.kt`)
BC-2 の [`CandidateRerankJudgement`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt) に、ゼロクエリ候補の上位 3 枠の配分方針を決定する純粋ドメイン決定表 `ZeroQueryHorizonAllocation` を追加します（[`docs/ddd/dmn_decision_models.md`](docs/ddd/dmn_decision_models.md) に同時登録）。

```kotlin
enum class ZeroQueryHorizonType {
    COMPOUND_CHUNK,   // 複合文節・連語チャンク (例: "お願いします", "に参加します")
    SINGLE_CONTENT,   // 単一自立語 (例: "お願い", "会議", "送信")
    PIVOT_PARTICLE,   // 助詞・句読点ピボット (例: "は", "が", "を", "に", "。")
}

data class ZeroQuerySlotAllocationInput(
    val top1IsHistoryBigram: Boolean,
    val top1IsCompoundChunk: Boolean,
    val hasMatchingSingleContentPrefix: Boolean,
    val hasPivotParticle: Boolean
)

data class ZeroQuerySlotAllocationDecision(
    val slot1Role: ZeroQueryHorizonType,
    val slot2Role: ZeroQueryHorizonType,
    val slot3Role: ZeroQueryHorizonType,
    val reason: String
)
```

| ルール ID | 条件 (`when`) | Slot 1 ($0.40\text{s}$) | Slot 2 ($0.70\text{s}$) | Slot 3 ($1.00\text{s}$) |
| :--- | :--- | :---: | :---: | :---: |
| **`ZQ_SLOT_R1`** | `top1IsHistoryBigram && !top1IsCompoundChunk` | `SINGLE_CONTENT` (履歴Top-1) | `COMPOUND_CHUNK` | `PIVOT_PARTICLE` |
| **`ZQ_SLOT_R2`** | `top1IsCompoundChunk && hasMatchingSingleContentPrefix` | `COMPOUND_CHUNK` (最大効用) | `SINGLE_CONTENT` (先頭単一語) | `PIVOT_PARTICLE` |
| **`ZQ_SLOT_R3`** | `top1IsCompoundChunk && hasPivotParticle` | `COMPOUND_CHUNK` (最大効用) | `PIVOT_PARTICLE` (最尤助詞) | `COMPOUND_CHUNK` (第2候補) |
| **`ZQ_SLOT_DEFAULT`** | その他 | 上位効用順（制約なし） | 上位効用順 | 上位効用順 |

#### 3.1.3 再配置関数 `allocateZeroQuerySlots(candidates)`
`CandidateRerankJudgement.allocateZeroQuerySlots(candidates: List<ConversionCandidate>): List<ConversionCandidate>` を純粋関数として実装し、`showNextPhrasePredictions` の Fast Path および Async Path 双方の最終出力に適用します：
1. 各候補のホライズン種別を判定：
   - `PIVOT_PARTICLE`: `cand.source in listOf("particle", "punctuation_suggestion")`
   - `COMPOUND_CHUNK`: `cand.surface.length >= 4` かつ（`cand.surface` が他の候補 `other.surface` を前方包含する、または助詞＋用言/補助動詞を含む複合連語）
   - `SINGLE_CONTENT`: 上記以外
2. `zeroQuerySlotAllocationDecisionTable.evaluateWithTrace(input)` の結果に従い、Slot 1 / Slot 2 / Slot 3 を確定し、残りの候補を元の順位（効用順）で後続に連結する。

---

## 4. Phase 3 詳細設計（Z4 + Z5：C++ 文節境界ビーム探索 & 投機的 KV プリウォーム）

### 4.1 [Z4] C++ `PredictNextPhrases` の文節境界認識ミニビーム探索 (`llm_reranker.cc`)

#### 4.1.1 現状の問題と目標
現在の [`LlmReranker::PredictNextPhrases`](app/src/main/cpp/llm_reranker.cc#L487-L647) は、1 トークン目 $t_0$ のロジット上位から `max_tokens = 4` の貪欲デコードを行っており、文節の途中で切れたり次文節へはみ出したりします。これを **Beam Width $B = 4$, Max Steps $T_{\max} = 6$ の文節境界停止付きミニビーム探索** に刷新します。

#### 4.1.2 データ構造と C++ ヘルパ関数 (`llm_reranker.cc`)
```cpp
struct NextPhraseBeam {
  std::vector<llama_token> tokens;
  std::string text;
  float cum_logprob = 0.0f;
  int32_t seq_id = 1;
  bool finished = false;
};
```

1. **UTF-8 マルチバイト完結判定 (`IsCompleteUtf8(const std::string& s)`)**:
   - 末尾バイトの UTF-8 継続ビットを検査し、マルチバイト文字の途中で切れている場合は `finished = false` を強制して次トークン生成を継続する。
2. **文節境界停止判定 (`IsBunsetsuBoundary(const std::string& s)`)**:
   - UTF-8 が完結しており、かつ文字数が 2 文字以上になった状態で、以下のいずれかの終端パターンに一致したら `finished = true` としてビームを確定する：
     - 句読点・記号終端: `。`, `！`, `？`, `、`, `.`, `!`, `?`
     - 用言・丁寧語終端: `ます`, `ました`, `ません`, `でした`, `です`, `する`, `した`, `して`, `いる`, `いた`, `ない`, `たい`, `ください`, `れる`, `られる`, `ております`, `ています`
     - 独立した漢字/カタカナ語幹（2文字以上）＋ 格助詞終端: `を`, `に`, `が`, `は`, `で`, `へ`, `と`, `も`, `から`, `まで`（例：`原因を` や `資料を` ではなく、`契約書に` の後の `捺印する` のように動詞終端まで伸ばすため、単なる「名詞＋助詞」は 1 回目の境界では停止候補に保存しつつ、もう 1〜2 ステップ動詞接続も探索する）。
3. **KV キャッシュの安全なロールバック**:
   - 既存の `llama_context`（`n_seq_max = 16`）の `seq_id = 1..B` を使用し、各ステップでアクティブビームのみ `llama_batch` にパックして `llama_decode` を実行（`seq_id = 0` のベース文脈 KV キャッシュは不変）。
   - 完了後（または `abort_prediction_` 検知時）に `llama_memory_seq_rm(mem, seq_id, start_pos, -1)` を呼び出し、**KV キャッシュ長を厳密に `start_pos`（`cached_context_n_tokens_`）へ復元**する。
4. **Kotlin 側での読み・品詞補完 (`LlmReranker.kt` / `KanaKanjiEngine.kt`)**:
   - `predictNextPhrases` が返した文字列 `surface` に対し、`KanaKanjiEngine.inferReadingAndLid(surface)`（Trie / 履歴逆引き、未知なら `surface` 自体を `reading`）を付与して `ConversionCandidate` を生成する。

---

### 4.2 [Z5] Composing 安定時の投機的 KV キャッシュ・プリウォーム

#### 4.2.1 DMN 拡張 (`CandidateRerankJudgement.kt`) と Alloy モデル整合
1. **`WarmTrigger` 拡張**:
   - 既存の `SpeculativeContextWarming` DMN 決定表の `WarmTrigger` 列挙型に `COMPOSING_TOP1_STABLE` を追加する（Composing 中に画面上の Top-1 候補が安定して `120ms` 経過したトリガ）。
   - ルール `WARM_COMPOUND_R1`: `trigger == COMPOSING_TOP1_STABLE && (!isRerankEnabled || !isSlmReady || composingTop1Surface.isBlank())` ならスキップ、それ以外は `shouldWarm = true`。
2. **Alloy 仕様 (`slm_kv_cache_lifecycle.als`) との整合**:
   - [`app/src/test/resources/alloy/slm_kv_cache_lifecycle.als`](app/src/test/resources/alloy/slm_kv_cache_lifecycle.als) の `treeBatchDecodeAndRollback` と同様に、投機的プリウォーム後にユーザーが別のキーを押した場合は必ず `kvCacheLen == contextLen` かつ `activeBranchTokens == 0` へロールバックされることを不変条件として維持する。

#### 4.2.2 `LlmReranker.kt` の投機的ゼロクエリキャッシュ
```kotlin
private data class SpeculativeZeroQueryEntry(
    val fullContextText: String,
    val slmPredictions: List<ConversionCandidate>,
    val createdAtUptimeMs: Long
)
```
- `fun speculativePrepareNextPhrases(contextPlusTop1: String)` (`Dispatchers.Default`):
  - `predictNextPhrases(contextPlusTop1, maxCandidates = 3, maxTokens = 6)` を実行し、`SpeculativeZeroQueryEntry` に保存（有効期限 `5000ms`）。
- `fun consumeSpeculativeNextPhrases(contextPlusTop1: String): List<ConversionCandidate>?`:
  - `fullContextText == contextPlusTop1` かつ有効期限内であればキャッシュを即座に返却（メインスレッド上で `< 0.1ms`）。

#### 4.2.3 `VoiceInputMethodService.kt` でのライフサイクル統合
1. `updateCandidates()` 完了時、画面上の Top-1 候補 `top1 = renderedCandidates.firstOrNull()?.surface` を取得。
2. `speculativePrewarmJob` を起動し、`delay(120L)` 待機後に `CandidateRerankJudgement.judgeSpeculativeWarm(..., trigger = WarmTrigger.COMPOSING_TOP1_STABLE)` が `true` なら `slm.speculativePrepareNextPhrases(textBeforeCursor + top1)` を実行。
3. **確定時 (`commitComposingText` $\to$ `showNextPhrasePredictions`)**:
   - `slm.consumeSpeculativeNextPhrases(textBeforeCursor)` がヒットした場合、Fast Path の `fastPredictions` と即座にマージ＋軽量リランク（`tryFastRerankWithStatus`）を同期実行し、**確定直後から二段階再描画なし（0ms）で SLM 統合済みの 0 文字候補を表示**する。
4. **中断時（次のかなキー押下時）**:
   - `speculativePrewarmJob?.cancel()` および `slm.requestAbortScoring()` を即時発行し、通常の変換レスポンスを一切阻害しない。

---

## 5. 各フェーズの検証計画（テスト・ベンチマーク）

| フェーズ | 追加・更新する単体テスト (`./gradlew testDebugUnitTest`) | ベンチマーク / ゲート検証 |
| :--- | :--- | :--- |
| **Phase 1** | ・`NextPhrasePredictorTest`: Skip-Bigram (`"作業\|を"`, `"資料\|を"`) 学習・バックオフ・`ConnectionMatrix` 枝刈り＆動的ソートテスト<br/>・`LlmRerankerTest`: `targetInputLength = 0` (`JA_ZERO_QUERY`) 時の無バイアス NLL・期待効用 $U_0$ テスト | ・`JotInputPredictionEvaluationRunnerTest` で `zero_char_no_hist` Hit@1 向上を確認<br/>・`python3 eval/eis/run_eis.py` で CUJ-2 EIS 向上を確認 |
| **Phase 2** | ・`CandidateRerankJudgementTest`: `ZeroQuerySlotAllocation` DMN 決定表の全ルール（`ZQ_SLOT_R1〜R3`, `DEFAULT`）および `DecisionTrace` 検証<br/>・`DddArchitectureKonsistTest`: DMN ドキュメント同期テスト通過 | ・`EisRegressionTest` で複合チャンク Rank 1 + 単一語 Rank 2 の両立を確認 |
| **Phase 3** | ・`CandidateRerankJudgementTest`: `COMPOSING_TOP1_STABLE` トリガ検証<br/>・`AlloyVerificationTest`: `slm_kv_cache_lifecycle.als` 検証通過<br/>・`LlmRerankerSpeculativeCacheTest`: プリウォームのヒット・期限切れ・キャンセルテスト | ・`eval/evaluate_predictive_conversion_breakthroughs_100.py` / 実機 (`./deploy.sh`) `logcat` で 0ms 確定後描画および Category 4 の EIS 向上を確認 |
