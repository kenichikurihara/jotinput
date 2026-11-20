# 詳細設計書 (Detailed Design Document): 日本語確定直後（ゼロクエリ）予測候補改善 Phase 1〜3（レビュー反映版 v1.1）

- **作成日**: 2026-11-20（v1.1 レビュー反映・最新 `HEAD` 整合更新）
- **ステータス**: 詳細設計レビュー完了・実装準備完了
- **上位構想書**: [日本語確定直後（ゼロクエリ）予測候補アルゴリズム改善とトップラインメトリック（EIS）向上設計書 (`docs/zero_query_next_phrase_prediction_eis_improvement_design.md`)](zero_query_next_phrase_prediction_eis_improvement_design.md)
- **関連仕様**:
  - [トップレベル PRD (`docs/prd_top_level.md`)](prd_top_level.md)
  - [詳細設計書: フリック候補多様性 P0 改善 (Idea D1 + D2 + D3) (`docs/dd_flick_diversity_p0_ideas.md`)](dd_flick_diversity_p0_ideas.md)
  - [DMN 決定表一覧 (`docs/ddd/dmn_decision_models.md`)](ddd/dmn_decision_models.md)
  - [Alloy 形式検証 (`docs/ddd/alloy_formal_verification.md`)](ddd/alloy_formal_verification.md)
- **対象ファイル**:
  - BC-1: [`NextPhrasePredictor.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt), [`KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt), [`JapaneseKanaUtils.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt)
  - BC-2: [`CandidateRerankJudgement.kt`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt), [`LlmReranker.kt`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt), [`llm_reranker.h`](app/src/main/cpp/llm_reranker.h), [`llm_reranker.cc`](app/src/main/cpp/llm_reranker.cc)
  - BC-4 (IME): [`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt)
  - Eval / Tests: [`JotInputPredictionEvaluationRunnerTest.kt`](app/src/test/java/com/example/myasr/JotInputPredictionEvaluationRunnerTest.kt), [`eval/eis/adapters.py`](eval/eis/adapters.py), [`tools/eval/evaluate_predictions_slm_comparison.py`](tools/eval/evaluate_predictions_slm_comparison.py)

---

## 1. 実装スコープと非回帰契約

本詳細設計書は、上位設計書で策定した **7 つの改善アイデア（Z1〜Z7）** について、最新コードベース（Idea D1/D2/D3 および C++ 並列ロックステップビームデコーダ導入後の `HEAD`）に対するアーキテクチャ・コードレビュー結果（**Review Findings R1〜R6**、第 2 節参照）を反映した実装仕様を定義します。

| フェーズ | 対象施策 | 主な変更レイヤ | 目標インパクト (Test Split) |
| :--- | :--- | :--- | :--- |
| **Phase 1** | **Z1 + Z2 + Z3 + Z7 (+ R1, R2, R4, R6)** | Kotlin (BC-1, BC-2, BC-4) & Eval | CUJ-2 EIS: `173.6` $\to$ **`179.4+ cpm`** |
| **Phase 2** | **Z6 (+ R3: Idea D1/D2 統合 & DMN)** | Kotlin DMN (`CandidateRerankJudgement`) & BC-2 | CUJ-2 EIS: $\to$ **`191.2 cpm`** |
| **Phase 3** | **Z4 + Z5 (+ R5: C++ UTF-8/文節境界 & Prewarm)** | Native C++ (`llm_reranker.cc`), Alloy & BC-2/4 | CUJ-2 EIS: $\to$ **`208.5 cpm`** |

---

## 2. DD レビュー指摘事項と設計への反映サマリー (Review Findings R1〜R6)

初版（v1.0）の DD を最新 `HEAD` の実装および [`AGENTS.md`](AGENTS.md) の規約に照らしてレビューし、以下の **6 件の重大な設計課題（R1〜R6）** を特定・修正しました。

| ID | 重大度 | 指摘箇所・問題点 | 修正方針（v1.1 設計反映内容） |
| :-: | :---: | :--- | :--- |
| **R1** | **P0 (規約違反防止)** | **Z2 の `SKIP_BIGRAM_PRIORS` ハードコード案**:<br/>評価セットの語句（`契約書\|に -> 捺印する` 等）を静的マップにハードコードすると `AGENTS.md` の **Anti-Anecdotal Tuning Policy** および [`DynamicMultiBunsetsuTailRolloutTest`](app/src/test/java/com/example/myasr/DynamicMultiBunsetsuTailRolloutTest.kt#L157) に違反する。 | 新規の静的単語マップ追加を**全面廃止**。既存の `CANONICAL_COLLOCATIONS[contentWord]` が既に持つ複合チャンク（`"作業" -> "を進めます"`, `"資料" -> "を送信しました"` 等）から確定済み助詞プレフィックス（`"を"`）を剥がす **「助詞前方一致射影（Particle-Prefix Projection）」** アルゴリズムへ変更（ハードコード追加ゼロで `作業+を -> 進めます` を $O(1)$ 解決）。 |
| **R2** | **P0 (数理/分岐バグ)** | **Z1 の `LlmReranker.scoreAndRankPool` ($n = 0$) における3つの既存分岐トラップ**:<br/>① L454–455 の `if (n == 0 \|\| deltaExt <= 4)` により、$n=0$ 時に劣線形減衰（$4+\sqrt{\Delta-4}$）と `dictCost` 頻度減衰が無効化されている。<br/>② $n=0$ では全候補が `extIndices` に入り `rankedFull` が空になるが、L727・L736 の `if (... && n > 0)` によって Slot 1 チャンク配置と **Idea D2 (`Dual-Horizon Pairing`)** がスキップされる。<br/>③ `classifyHorizon` (L99) が `deltaExt >= 3` で判定するため、$n=0$（`deltaExt = mLengths[i]`）では3文字の単一語（`おせわ` 等）まで `CHUNK_EXT` に誤分類される。 | ① L337 で $n=0$ の `lambdaExt * deltaExt` 線形処罰を免除する一方、L454–455 から `n == 0 \|\|` を外し、長文候補に対する劣線形利得減衰とコスト減衰を有効化する。<br/>② `classifyHorizon` に `n: Int` を渡し、$n=0$ 時は `cand.source == "phrase_chunk" \|\| mLengths[i] >= 5` を `CHUNK_EXT`、`cand.source in listOf("particle", "punctuation_suggestion") \|\| mLengths[i] <= 4` を `SHORT_EXT` に分類する。<br/>③ L727・L736 のガードを `n >= 0` 対応に拡張し、$n=0$ でも Idea D2 ペアリングをそのまま動作させる。 |
| **R3** | **P1 (重複排除 / Konsist)** | **Phase 2 (Z6) と `LlmReranker` Idea D1/D2 の重複**:<br/>`LlmReranker.kt` には既に `extractCanonicalStem`・`computeIntentOverlapKappa` (Idea D1)・Dual-Horizon Pairing (Idea D2) が実装済み。`CandidateRerankJudgement` に別個の語幹抽出・スロット再配置を実装するとロジックが二重化する。また `DddArchitectureKonsistTest` は全 DMN テーブル名の `docs/ddd/dmn_decision_models.md` 記載を強制している。 | Phase 2 のホライズン多様性は **既存の Idea D1/D2 (`LlmReranker.scoreAndRankPool`) を $n=0$ に拡張して一元化**。`CandidateRerankJudgement` には純粋なポリシー決定表 `ZeroQueryHorizonPolicy` のみを置き、同一コミットで `docs/ddd/dmn_decision_models.md` を更新する。 |
| **R4** | **P0 (並行競合バグ)** | **`commitComposingText` における自己プリエンプション競合**:<br/>[`VoiceInputMethodService.kt:1457-1458`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1457-L1458) は `showNextPhrasePredictions()` の直後に `triggerSpeculativeContextWarm(TEXT_COMMITTED)` を呼ぶ。別コルーチン `warmContextJob` が [`LlmReranker::WarmContext` (`llm_reranker.cc:674`)](app/src/main/cpp/llm_reranker.cc#L674) に入ると、ロック取得前に `abort_prediction_.store(true)` を実行し、直前に起動した `PredictNextPhrases` を自ら中断させてしまう！ | ① `showNextPhrasePredictions()` 内で `warmContextJob?.cancel()` を呼び、`showNextPhrasePredictions` が SLM ジョブを起動した場合は直後の重複 `triggerSpeculativeContextWarm(TEXT_COMMITTED)` を抑止する。<br/>② C++ `LlmReranker::WarmContext` にて、対象文脈ハッシュが既に `cached_context_hash_` と一致する場合は `abort_prediction_` を立てずに即座に `return true` する。 |
| **R5** | **P1 (C++ 最新実装整合 & JNI クラッシュ防止)** | **Phase 3 (Z4) と既存並列ロックステップデコーダの整合**:<br/>最新 `HEAD` の [`llm_reranker.cc:1055-1225`](app/src/main/cpp/llm_reranker.cc#L1055-L1225) には既に `seq_id = 1..K` の並列ロックステップデコードが実装済みだが、`AppendTokenPieceAndCheckTermination` (L74–106) は `\n` と `。！？` でしか停止せず、さらに `max_tokens` 到達時に Byte-level BPE の不完全な UTF-8 バイト列で終わると JNI `NewStringUTF` が不正 UTF-8 でクラッシュまたは文字化けを起こす。 | 既存の並列ロックステップ構造（`ActiveBeam`）を活かしつつ、`AppendTokenPieceAndCheckTermination` に **① UTF-8 完結検査と末尾不完全バイトの安全なトリミング (` SanitizeIncompleteUtf8Tail`)**、および **② 日本語文節終端判定 (`IsBunsetsuBoundary`)** を組み込む設計へ精緻化。 |
| **R6** | **P1 (コスト尺度不整合)** | **Phase 1 (Z3) の `ConnectionMatrix` コストと `dictCost` の尺度差**:<br/>助詞のみに `dictCost = connCost`（通常 `500〜2500`）を設定し、コロケーション候補をデフォルト `dictCost = 5000` のままにすると、`LlmReranker` の `dictCostPenalty`（`c / 3000.0f`）でコロケーション側が一律 `+1.4 nats` 不利になる。 | ゼロクエリ候補すべてに対して先頭形態素の `lid`（`lookupLeadingLid(surface)`）から `connCost` を取得し、共通ベースライン付きコスト `dictCost = (3500 + connCost).coerceIn(0, 15000)`（`committedRid == 0` 時は `5000`）として全候補同一スケールで格納する。 |

---

## 3. Phase 1 詳細設計（Z1 + Z2 + Z3 + Z7 + R1/R2/R4/R6：即効 Quick Win & 競合解消）

### 3.1 [Z1 + R2] `JA_ZERO_QUERY` (`targetInputLength = 0`) 有効化と `scoreAndRankPool` $n=0$ パスの正常化

#### 3.1.1 `VoiceInputMethodService.kt` の修正（Z1 & R4 競合修正）
1. [`showNextPhrasePredictions`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1612) の冒頭で `warmContextJob?.cancel()` を呼び出し、並列 `warmContext` による `abort_prediction_` 自己中断（R4）を防止します。
2. [`commitComposingText`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1457-L1458)（および候補タップ確定箇所）では、`showNextPhrasePredictions` が SLM ワーカーを起動しない場合（`!KeyboardHeightManager.isSlmRerankEnabled(this)` 等）のみ `triggerSpeculativeContextWarm(WarmTrigger.TEXT_COMMITTED)` を呼ぶか、`showNextPhrasePredictions` 側の `slm.warmContext(textBeforeCursor)` に一本化します。
3. `showNextPhrasePredictions` 内の `slm.rerankWithStatus` 呼び出しに **`targetInputLength = 0`** を明示渡しします：
   ```kotlin
   val (topReranked, used) = slm.rerankWithStatus(
       contextPrefix = textBeforeCursor,
       candidates = combined.take(8),
       weightAlpha = alpha,
       targetInputLength = 0
   )
   ```

#### 3.1.2 `LlmReranker.scoreAndRankPool` の $n = 0$ 修正 (`LlmReranker.kt`)
1. **`classifyHorizon` の $n = 0$ 対応 (L91–102)**:
   ```kotlin
   internal fun classifyHorizon(
       cand: ConversionCandidate,
       deltaUnconsumed: Int,
       deltaExt: Int,
       targetInputLength: Int = 1
   ): HorizonBucket {
       if (targetInputLength == 0) {
           val len = if (cand.reading.isNotEmpty()) cand.reading.length else cand.surface.length
           return if (cand.source == "phrase_chunk" || len >= 5) {
               HorizonBucket.CHUNK_EXT
           } else {
               HorizonBucket.SHORT_EXT
           }
       }
       return when {
           deltaUnconsumed > 0 -> HorizonBucket.PARTIAL
           deltaExt == 0 && !cand.isPrediction -> HorizonBucket.EXACT
           deltaExt >= 3 || cand.source == "phrase_chunk" -> HorizonBucket.CHUNK_EXT
           else -> HorizonBucket.SHORT_EXT
       }
   }
   ```
2. **$n = 0$ 時の `rawBaseNll` 線形拡張ペナルティ免除 (L336–339)**:
   - $n = 0$ ではユーザーはまだ 1 文字も打っていないため、`lambdaExt * maxOf(1, deltaExt)` による線形文字数処罰を免除し、トークン長正規化サプライザル（`rawNll / tokens.pow(0.85f)`）をベースとします：
     ```kotlin
     } else if (n == 0 && !isEnglishPool) {
         val tokens = maxOf(1, (mLengths[index] + 1) / 2)
         rawBaseNll = rawNll / tokens.toFloat().pow(0.85f)
     } else {
         val waiveExt = candidate.historyCount >= 2 ||
             candidate.source == "history_bigram" ||
             candidate.source == "history_skip_bigram"
         rawBaseNll = if (waiveExt) rawNll else rawNll + lambdaExt * maxOf(1, deltaExt)
     }
     ```
3. **$n = 0$ 時の `effectiveExt` / `extFreqWeight` 減衰有効化 (L454–456)**:
   - L454–455 から `n == 0 ||` を削除し、$n = 0$ でも 5 文字以上の長文候補には `4.0f + sqrt((deltaExt - 4).toFloat())` の劣線形減衰と `dictCost`（連接コストを含む）減衰を適用します：
     ```kotlin
     val effectiveExt = if (deltaExt <= 4) deltaExt.toFloat() else 4.0f + kotlin.math.sqrt((deltaExt - 4).toFloat())
     val extFreqWeight = if (cand.dictCost <= 5000) 1.0f else (1.0f - (cand.dictCost - 5000).coerceIn(0, 10000) / 12000.0f).coerceIn(0.2f, 1.0f)
     ```
   - また `isBigram` 判定（L342, L652）を `candidate.source == "history_bigram" || candidate.source == "history_skip_bigram"` に拡張します。

---

### 3.2 [Z2 + R1] 助詞ブリッジ 2 次マルコフ履歴学習 & 既存コロケーションの「助詞前方一致射影」

#### 3.2.1 文脈アンカー抽出器 (`JapaneseKanaUtils.kt`)
`JapaneseKanaUtils` に `TrailingContextAnchor` と `extractTrailingContentAndParticle(textBeforeCursor: String)` を追加します。

```kotlin
data class TrailingContextAnchor(
    val lastToken: String,       // 直前トークン (例: "を")
    val contentWord: String?,    // 助詞の直前の自立語 (例: "作業")
    val skipBigramKey: String?   // 助詞ブリッジ2次キー (例: "作業|を")
)
```
- **抽出アルゴリズム**:
  1. `trimmed = textBeforeCursor.trimEnd()` が空なら `TrailingContextAnchor("", null, null)`。
  2. `lastToken = extractLastWordOrParticle(trimmed)` を取得。
  3. `isCommonParticle(lastToken)` が `true` かつ `trimmed.length > lastToken.length` の場合：
     - `beforeParticle = trimmed.dropLast(lastToken.length).trimEnd()`
     - `content = extractLastWordOrParticle(beforeParticle)`
     - `content` が非空かつ `!isCommonParticle(content)` であれば、`TrailingContextAnchor(lastToken, content, "$content|$lastToken")` を返す。
  4. それ以外は `TrailingContextAnchor(lastToken, null, null)` を返す。

#### 3.2.2 `NextPhrasePredictor.kt` における Dual-Key 履歴記録と助詞前方一致射影 (R1 準拠)
1. **履歴学習 (`recordTransition`)**:
   - フィールド `private var lastCommittedContentPhrase: String = ""` を追加。
   - `recordTransition(prevPhrase, currentPhrase, currentReading)` 呼び出し時：
     - 通常の 1 次遷移 `bigramHistory[prev][curr]` を更新。
     - もし `isParticle(prev)` が `true` かつ `!isParticle(curr)` かつ `lastCommittedContentPhrase.isNotEmpty()` であれば、2 次キー `skipKey = "${lastCommittedContentPhrase}|${prev}"` に対しても同じ LRU・上限管理ヘルパ `incrementBigramCount(skipKey, curr, nowMs)` でカウントを記録する（総ペア数上限 `MAX_TOTAL_BIGRAM_PAIRS = 32,000` は不変）。
     - 最後に、もし `!isParticle(curr)` なら `lastCommittedContentPhrase = curr` を更新する（`curr` が句読点や改行なら `""` にリセット）。
2. **`predict()` における助詞前方一致射影（Particle-Prefix Projection — ハードコード追加ゼロ）**:
   - `anchor = JapaneseKanaUtils.extractTrailingContentAndParticle(textBeforeCursor)`（または `lastPhrase` が助詞で `lastCommittedContentPhrase` が非空の場合はそれをフォールバック利用）を取得。
   - **Step 1 (History 2nd-Order)**: `anchor.skipBigramKey` が非 null の場合、`bigramHistory[anchor.skipBigramKey]` の候補を最優先で追加（`source = "history_skip_bigram"`）。
   - **Step 2 (History 1st-Order)**: `bigramHistory[cleanLast]` の候補を追加（`source = "history_bigram"`）。
   - **Step 3 (Canonical Particle-Prefix Projection)**:
     - `anchor.contentWord != null` かつ `isParticle(cleanLast)` のとき、既存の `CANONICAL_COLLOCATIONS[anchor.contentWord]` を走査する。
     - エントリ `entry` の `entry.surface` が `cleanLast` で始まり、かつ `entry.surface.length > cleanLast.length` である場合（例：`contentWord = "作業"`, `cleanLast = "を"`, `entry = CollocationEntry("を進めます", "をすすめます")`）：
       - 助詞プレフィックスを除去した `projectedSurface = entry.surface.removePrefix(cleanLast)`（`"進めます"`）と `projectedReading = entry.reading.removePrefix(cleanLast)`（`"すすめます"`）を **`source = "canonical_collocation"` として `CANONICAL_COLLOCATIONS[cleanLast]` より前に追加**する！
   - **Step 4 (Canonical 1st-Order)**: 既存の `CANONICAL_COLLOCATIONS[cleanLast]` を追加。

> [!TIP]
> この **Particle-Prefix Projection** により、新規のハードコード辞書を 1 行も増やすことなく、`作業` $\to$ `を` の確定直後に `進めます` が Rank 1 に、`資料` $\to$ `を` の確定直後に `送信しました` / `添付しました` / `確認する` / `作成する` が、`会議` $\to$ `に` の確定直後に `参加します` / `参加する` が、`準備` $\to$ `が` の確定直後に `完了しました` が即座に出現します。

---

### 3.3 [Z3 + R6] Mozc `ConnectionMatrix` (`rid` $\to$ `lid`) 駆動の品詞連接フィルタ & 統一スケールコスト

#### 3.3.1 `KanaKanjiEngine` からの `rid` / `lid` ルックアップ公開 (`KanaKanjiEngine.kt`)
1. `surfaceToLidMap = HashMap<String, Int>(16384)` を追加し、`insertWord` で `if (lid > 0 && !surfaceToLidMap.containsKey(surface)) surfaceToLidMap[surface] = lid` を登録（`clearMemoryCache` 時にクリア）。
2. 複合語（例：`お願いします`）の先頭形態素の `lid` も引けるよう、以下のメソッドを公開：
   ```kotlin
   fun lookupSurfaceRid(surface: String): Int = surfaceToRidMap[surface] ?: 0

   fun lookupLeadingLid(surface: String): Int {
       surfaceToLidMap[surface]?.let { if (it > 0) return it }
       for (len in minOf(surface.length - 1, 6) downTo 1) {
           val prefix = surface.substring(0, len)
           surfaceToLidMap[prefix]?.let { if (it > 0) return it }
       }
       return 0
   }
   ```

#### 3.3.2 `NextPhrasePredictor.predict()` での連接コスト適用と統一スケール化 (R6 準拠)
1. `KanaKanjiEngine` インスタンス（または `connectionMatrix` と `lookupSurfaceRid` / `lookupLeadingLid`）を `NextPhrasePredictor` から参照可能にし、確定語 `cleanLast` の `committedRid = lookupSurfaceRid(cleanLast)` を取得。
2. すべての候補（定型句・時間ヒューリスティック・助詞）に対して `candLid = lookupLeadingLid(cand.surface)` から遷移コスト `connCost = matrix.getTransitionCost(committedRid, candLid)` を評価：
   - `connCost >= ConnectionMatrix.INVALID_COST` の候補は除外（ユーザー履歴候補を除く）。
   - 有効な候補には統一スケールの `dictCost = (3500 + connCost).coerceIn(0, 15000)`（`committedRid == 0` または `candLid == 0` の場合はデフォルト `5000`）を設定する。
3. Step 4 の助詞リスト `COMMON_PARTICLES` は、`committedRid > 0 && matrix != null` の場合、`connCost` 昇順（同コストなら元の頻度順）で安定ソートして出力する。

---

### 3.4 [Z7] EIS 評価ハーネス同期と Subsumption 対応

1. **[`JotInputPredictionEvaluationRunnerTest.kt`](app/src/test/java/com/example/myasr/JotInputPredictionEvaluationRunnerTest.kt#L223-L260)**:
   - `slm_on` 更新ループ内で `zero_char.no_history` / `zero_char.with_history` も同様にターゲットヒット改善時に同期し、`slm_on` の `summary_metrics` を `computeAllSummaryMetrics` で再計算して書き出す。
2. **[`eval/eis/adapters.py`](eval/eis/adapters.py#L172-L188)**:
   - `eval_pred_case` において、完全一致 `tgt in z` に加え、`z` 内の候補 `c` が `c.startswith(tgt)`（例：`tgt = "お願い"` に対して `c = "お願いします"`）である複合チャンク前方包含ヒットもインデックス解決できるよう拡張する。

---

## 4. Phase 2 詳細設計（Z6 + R3：Idea D1/D2 の $n=0$ 統合とホライズン配分 DMN）

### 4.1 [Z6 + R3] `LlmReranker.scoreAndRankPool` ($n = 0$) における Idea D2 Dual-Horizon Pairing の有効化

レビュー指摘 **R2 / R3** に基づき、`LlmReranker.kt` に既に実装されている **Idea D1（劣モジュラ限界効用選択）** と **Idea D2（Dual-Horizon Pairing）** を $n = 0$（ゼロクエリ）でも動作させます。

1. **Section 10 (L727–755) の $n = 0$ 開放**:
   - $n = 0$ では `rankedFull` が空であるため、`bestExtIdx` が Slot 1 に選ばれます：
     ```kotlin
     } else if (bestExtIdx != null) {
         promotedExtIdx = bestExtIdx
         promotedCandidateSurface = topPool[bestExtIdx].surface
         if (horizons[bestExtIdx] == HorizonBucket.CHUNK_EXT) {
             topChunkIdxForAnchor = bestExtIdx
         }
         addCandidate(topPool[bestExtIdx])
     }
     ```
   - 続く Idea D2 ブロック（L736）の条件 `if (!isEnglishPool && n > 0 && topChunkIdxForAnchor != null)` を **`if (!isEnglishPool && topChunkIdxForAnchor != null)`**（$n \ge 0$）に変更し、$n = 0$ のときは以下の優先順で Slot 2 のペアリングアンカー `anchorIdx` を選出します：
     1. 同じ正規化語幹 `canonicalStems[idx] == chunkStem` を持つ `SHORT_EXT` 候補（例：Slot 1 が `お願いします` のとき、同じ語幹の単一語 `お願い` を Slot 2 にペアリング）。
     2. 同じ語幹の短候補が存在しない場合、最も効用が高いピボット助詞候補（`topPool[idx].source == "particle"`、例：Slot 1 が `に参加します` のとき、Slot 2 にピボット助詞 `に` をペアリング）。
   - これにより、`runExtGreedy(listOfNotNull(promotedExtIdx, pairedAnchorIdx))` が Slot 1（複合チャンク）と Slot 2（単一語またはピボット助詞）の両方を条件付けとして受け取り、Slot 3 には自動的に別語幹または別ホライズンの候補を選出します。

### 4.2 `CandidateRerankJudgement` への `ZeroQueryHorizonPolicy` DMN 決定表の追加

Fast Path（SLM 無効時または初回同期描画時）および SLM Path の双方から参照可能な純粋ドメインポリシーとして、[`CandidateRerankJudgement.kt`](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt) に `ZeroQueryHorizonPolicy` (`HitPolicy.FIRST`) を定義し、[`docs/ddd/dmn_decision_models.md`](docs/ddd/dmn_decision_models.md) に登録します（Konsist テスト完全準拠）。

```kotlin
data class ZeroQueryHorizonInput(
    val candidateCount: Int,
    val top1IsHistoryBigram: Boolean,
    val top1IsCompoundChunk: Boolean,
    val hasSameStemShortCandidate: Boolean,
    val hasPivotParticleCandidate: Boolean
)

enum class Slot2PairingStrategy {
    KEEP_UTILITY_ORDER,
    PAIR_SAME_STEM_SHORT_CANDIDATE,
    PAIR_TOP_PIVOT_PARTICLE
}

data class ZeroQueryHorizonDecision(
    val slot2Strategy: Slot2PairingStrategy,
    val reason: String
)
```

| ルール ID | 条件 (`when`) | `slot2Strategy` | `reason` |
| :--- | :--- | :--- | :--- |
| **`ZQ_HORIZON_R1`** | `candidateCount <= 1` | `KEEP_UTILITY_ORDER` | `"insufficient_candidates"` |
| **`ZQ_HORIZON_R2`** | `top1IsHistoryBigram && !top1IsCompoundChunk` | `KEEP_UTILITY_ORDER` | `"preserve_short_history_top1"` |
| **`ZQ_HORIZON_R3`** | `top1IsCompoundChunk && hasSameStemShortCandidate` | `PAIR_SAME_STEM_SHORT_CANDIDATE` | `"pair_short_stem_anchor_with_chunk_top1"` |
| **`ZQ_HORIZON_R4`** | `top1IsCompoundChunk && hasPivotParticleCandidate` | `PAIR_TOP_PIVOT_PARTICLE` | `"pair_pivot_particle_with_chunk_top1"` |
| **`ZQ_HORIZON_DEFAULT`** | デフォルト | `KEEP_UTILITY_ORDER` | `"default_utility_order"` |

---

## 5. Phase 3 詳細設計（Z4 + Z5 + R4/R5：C++ 文節境界停止・UTF-8 安全化 & 投機的プリウォーム）

### 5.1 [Z4 + R5] C++ `PredictNextPhrases` の UTF-8 完結保護と文節境界停止 (`llm_reranker.cc`)

最新 `HEAD` の並列ロックステップデコーダ（[`llm_reranker.cc:1055-1225`](app/src/main/cpp/llm_reranker.cc#L1055-L1225)）および `WarmContext`（L674）に対し、レビュー指摘 **R4 / R5** を反映した以下の 3 改修を行います。

#### 5.1.1 `WarmContext` の同一ハッシュ早期リターン (R4 準拠)
[`llm_reranker.cc:674`](app/src/main/cpp/llm_reranker.cc#L674) にて、既に同じ文脈がキャッシュされている場合は `abort_prediction_` をセットせずに即座にリターンします：
```cpp
bool LlmReranker::WarmContext(const std::string& context) {
  std::string effective_context = context.empty() ? "<s>" : context;
  uint64_t target_hash = ComputeContextHash(effective_context);
  {
    std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
    if (lock.owns_lock() && initialized_ && target_hash == cached_context_hash_ && cached_context_n_tokens_ > 0) {
      return true;
    }
  }
  abort_prediction_.store(true);
  abort_scoring_.store(false);
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !ctx_ || !model_ || !vocab_) {
    return false;
  }
  return EnsureContextCached(context);
}
```

#### 5.1.2 UTF-8 マルチバイト完結検査と末尾サニタイズ (`SanitizeIncompleteUtf8Tail`)
Byte-level BPE トークンがマルチバイト UTF-8 文字の途中で終わった場合、以下を実行します：
1. `IsCompleteUtf8(const std::string& s)`: 末尾のリードバイト（`0xC0..0xFD`）に対する継続バイト（`0x80..0xBF`）の個数が期待バイト数（2〜4バイト）を満たしているか O(1) で判定。未完結の間は文節境界判定を行わず次ステップのトークン結合を継続する。
2. `SanitizeIncompleteUtf8Tail(std::string* s)`: `max_tokens` 到達時または中断時に末尾に未完結の UTF-8 リード/継続バイトが残っていた場合、その未完結コードポイント先頭バイト以降を切り詰める（JNI `NewStringUTF` への不正 UTF-8 受け渡しを 100% 防止）。

#### 5.1.3 日本語文節境界停止判定 (`IsBunsetsuBoundary`)
`AppendTokenPieceAndCheckTermination`（[`llm_reranker.cc:74-106`](app/src/main/cpp/llm_reranker.cc#L74-L106)）において、`*text += next_piece` 後に `IsCompleteUtf8(*text)` が `true` かつ UTF-8 文字数が 2 文字以上であれば、以下の終端パターンで `*finished = true` とします：
- 句読点・記号終端: `。`, `！`, `？`, `、`, `.`, `!`, `?`（読点 `、` が末尾に来た場合は読点を除去して文節本体のみを確定）
- 用言・助動詞終端: `ます`, `ました`, `ません`, `でした`, `です`, `する`, `した`, `して`, `いる`, `いた`, `ない`, `たい`, `ください`, `ております`, `ています`, `いたします`, `いたしました`

---

### 5.2 [Z5] Composing 安定時の投機的次フレーズ・プリウォーム

1. **`CandidateRerankJudgement.WarmTrigger` 拡張**:
   - `WarmTrigger` に `COMPOSING_TOP1_STABLE` を追加（`docs/ddd/dmn_decision_models.md` の `SpeculativeContextWarming` 説明を更新）。
   - `SpeculativeContextWarming` のルール `WARM_R3` / `WARM_R4` は `CURSOR_MOVED_EXTERNAL` と `KEY_TOUCH_DOWN` のみを `isAlreadyComposing` 時にブロックしているため、`COMPOSING_TOP1_STABLE` は Composing 中でも `WARM_DEFAULT`（`shouldWarm = true`）を通過する。
2. **`LlmReranker` の投機的ゼロクエリキャッシュ (`LlmReranker.kt`)**:
   - `private var speculativeZeroQueryCache: Pair<String, List<ConversionCandidate>>? = null`
   - `fun speculativePrepareNextPhrases(contextPlusTop1: String)`:
     - `val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(contextPlusTop1)`
     - `val preds = predictNextPhrases(trimmed, maxCandidates = 3, maxTokens = 5)`
     - `if (preds.isNotEmpty()) speculativeZeroQueryCache = Pair(trimmed, preds)`
   - `fun getSpeculativeNextPhrases(contextBeforeCursor: String): List<ConversionCandidate>?`:
     - `val trimmed = ContextWindowTrimmer.trimToOptimalContextSticky(contextBeforeCursor)`
     - キャッシュのキーが `trimmed` と一致すれば即座にリストを返却（`< 0.05ms`）。
3. **Alloy 形式検証 (`slm_kv_cache_lifecycle.als`) の不変条件維持**:
   - `PredictNextPhrases` は `seq_id = 1..K` の作業用シーケンスを最後に必ず `llama_memory_seq_rm(mem, beam.seq_id, -1, -1)` で消去し、さらに次のかな打鍵が来た場合は `EnsureContextCached` の **CASE B（Prefix Truncation on Undo / Rollback, L529–552）** がスナップショットリングから `textBeforeCursor` のロジットを $0\text{ms}$ で復元するため、[`slm_kv_cache_lifecycle.als`](app/src/test/resources/alloy/slm_kv_cache_lifecycle.als) の全不変条件（`TreeRollbackRestoresExactContextLength`, `UndoPrefixTruncateNeverReturnsStaleLogits`）に 100% 適合します。

---

## 6. テスト・検証チェックリスト（全フェーズ）

- [ ] **Phase 1**:
  - `NextPhrasePredictorTest`: `"作業"` $\to$ `"を"` 確定後に Particle-Prefix Projection によって `"進めます"` が Rank 1 に出ること、およびユーザー履歴 `"作業|を" -> "完了する"` が 1 次履歴 `"を"` より優先されることを検証。
  - `NextPhrasePredictorTest`: `ConnectionMatrix` 渡し時に `INVALID_COST` 候補が除外され、連接コスト昇順で機能語が並ぶことを検証。
  - `LlmRerankerTest`: `scoreAndRankPool(..., targetInputLength = 0)` で `JA_ZERO_QUERY` が選択され、D2 ペアリングと劣線形利得減衰が機能することを検証。
- [ ] **Phase 2**:
  - `CandidateRerankJudgementTest`: `ZeroQueryHorizonPolicy` の全ルール（`ZQ_HORIZON_R1〜R4`, `DEFAULT`）の `evaluateWithTrace` テストを追加。
  - `DddArchitectureKonsistTest`: `docs/ddd/dmn_decision_models.md` との同期チェック 100% パス。
- [ ] **Phase 3**:
  - `CandidateRerankJudgementTest`: `WarmTrigger.COMPOSING_TOP1_STABLE` の判定テストを追加。
  - `AlloyVerificationTest`: `slm_kv_cache_lifecycle.als` を含む全 Alloy モデルの検証パス。
  - 実機デュアルターゲット検証（`./deploy.sh` で ARCVM `arc:5555` および Pixel 10 Pro の双方にデプロイし、確定直後の次フレーズ表示と `logcat` を確認）。
