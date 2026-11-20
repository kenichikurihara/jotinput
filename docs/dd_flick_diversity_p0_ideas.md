# 詳細設計書 (Detailed Design Document): フリック候補多様性 P0 改善 (Idea D1 + D2 + D3)

- **作成日**: 2026-11-19
- **ステータス**: 詳細設計完了・実装対象（P0 Priority）
- **上位構想書**: [フリック入力における候補多様性（Diversity）再設計とトップラインメトリック（EIS）改善提案書 (`docs/flick_diversity_topline_eis_improvement_ideas.md`)](docs/flick_diversity_topline_eis_improvement_ideas.md)
- **関連仕様**:
  - [効用スコア（Utility Score）導入による予測変換最適化 (`docs/utility_score_algorithm_design.md`)](docs/utility_score_algorithm_design.md)
  - [未消費スパンの言語モデル評価と多様性保証 (`docs/lm_unconsumed_span_evaluation_and_diversity_design.md`)](docs/lm_unconsumed_span_evaluation_and_diversity_design.md)
- **対象ファイル**:
  - [`KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt)
  - [`LlmReranker.kt`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt)

---

## 1. 実装スコープと設計原則

本詳細設計書（DD）は、トップラインメトリック（**EIS**）のさらなる向上に向けて特定された P0 の 3 つの多様性改善アイデア（**Idea D1, Idea D2, Idea D3**）を、既存パイプラインへ安全かつゼロ・レイテンシ増で統合するための実装仕様を定義します。

- **Anti-Anecdotal Tuning Policy 遵守**: 特定単語へのアドホックなコスト改変は一切行わず、語幹同値類・ホライズン粒度・劣モジュラ集合効用に基づく純粋アルゴリズムで実装します。
- **非回帰契約**:
  - `JapanesePunctuationEvaluationTest`（67+ テスト）100% パス
  - `SpanAwareBayesianRerankingEvaluationTest` / `DynamicMultiBunsetsuTailRolloutTest` / `EisRegressionTest` / `KanaKanjiEngineDiversityTest` 100% パス
  - `DddArchitectureKonsistTest` / `AlloyVerificationTest` 100% パス（公開 DDD 境界・DMN・Alloy 状態遷移の不変維持）

---

## 2. Idea D3 詳細設計：B3 動的複文節ロールアウトの語幹ラウンドロビン多様化

### 2.1 現状の問題 (`KanaKanjiEngine.kt:2063-2180`)
`synthesizeDynamicMultiBunsetsuChunks` は、Loop 1（`CANONICAL_COLLOCATIONS`）および Loop 2（一般辞書の `topStems` 最大 8 語幹）の双方において、**1 語幹につき最大 2 件を連続生成する深さ優先（Depth-First）ループ** になっています。
その結果、例えば `こう` や `かい` のような同音異義語幹が多いプレフィックスでは、上位 2 語幹のテイル違い（例：`確認します`, `確認しました`, `拡大します`, `拡大しました`）だけで 4 枠が埋まり、`LlmReranker` の `phraseChunkPrefix.take(4)` に **3 番手以降の語幹（例：`改善します`, `開始します`, `解決します`）が一切渡りません**。

### 2.2 アルゴリズム仕様

#### (A) 引数拡張と直前文脈の統語ロール判定
`synthesizeDynamicMultiBunsetsuChunks` に `contextPrefix: String = ""` を追加し、`getBaseCandidates` から伝播します。
`contextPrefix.trimEnd()` の末尾文字から、直前文脈の統語選好 `ContextTailRole` を軽量判定します：
- `CASE_PARTICLE_OBJECT`（末尾が `を`, `に`, `で`, `へ`, `から`, `と`）:
  - 格助詞の直後は動作・完了述語が自然であるため、サ変名詞テイルは `DYNAMIC_SAHEN_TAILS` の動詞化テイル（`します`, `しました`, `しています`, `いたします`, ...）の順を優先。
- `CLAUSE_START_OR_TOPIC`（空文脈、または末尾が `、`, `。`, `は`, `が`, `も` 等）:
  - 節頭・主題の後は状態・理由説明や依頼表現（`中ですので`, `予定です`, `します`, `お願いします`, ...）も第 1 テイル候補として均等に浮かび上がるよう、Pass 1（第 1 テイル）と Pass 2（第 2 テイル）でバリエーションを確保。

#### (B) 幅優先（Breadth-First）ラウンドロビン生成
Loop 1（Canonical）および Loop 2（Dynamic）において、各語幹から生成可能な有効テイルリスト `perStemCandidates: List<List<ConversionCandidate>>`（各語幹最大 2 件）をまず収集し、**ラウンドロビン（Pass 1 → Pass 2）** で平坦化します：

```kotlin
// Pass 1: 各語幹から第1テイルを1件ずつ収集 (語幹多様性を最大化)
for (stemList in perStemCandidates) {
    if (stemList.isNotEmpty() && seenSurfaces.add(stemList[0].surface)) {
        out.add(stemList[0])
    }
}
// Pass 2: 余剰枠に各語幹の第2テイルを1件ずつ収集
for (stemList in perStemCandidates) {
    if (stemList.size > 1 && seenSurfaces.add(stemList[1].surface)) {
        out.add(stemList[1])
    }
}
```

- **効果**: `results.take(8)` および `LlmReranker` の `phraseChunkPrefix.take(4)` に、**同一語幹の連続を排して最大 4〜5 種類の異なる語幹** が確実に供給されます。
- **計算量**: 生成上限（最大 8 件）および `matrix.getTransitionCost` 呼び出し回数は従来と同一であり、レイテンシ増加は **$0.00\text{ms}$** です。

---

## 3. Idea D1 詳細設計：劣モジュラ集合効用最大化 (`LlmReranker.scoreAndRankPool`)

### 3.1 候補の 4 次元多様性プロファイル抽出
`LlmReranker.scoreAndRankPool` 内で、評価プール `topPool` の全候補 `i` に対し、UI や他コンテキストに依存しない純粋関数で多様性プロファイル `DiversityProfile` を構築します。

1. **長さホライズン区分 `HorizonBucket`**:
   ```kotlin
   enum class HorizonBucket {
       PARTIAL,    // deltaUnconsumed > 0
       EXACT,      // deltaUnconsumed == 0 && deltaExt == 0 && !cand.isPrediction
       SHORT_EXT,  // deltaUnconsumed == 0 && !PARTIAL && !EXACT && deltaExt <= 2 && cand.source != "phrase_chunk"
       CHUNK_EXT   // deltaUnconsumed == 0 && (deltaExt >= 3 || cand.source == "phrase_chunk")
   }
   ```
2. **先頭語幹の正規化抽出 `extractCanonicalStem(cand, topPool, isEnglishPool)`**:
   - 優先 1: `cand.firstBunsetsuSurface` が非空ならそれを使用。
   - 優先 2（日本語 Prefix Stem Canonicalization）: `topPool` 内に「`cand.surface.startsWith(other.surface)` かつ `other.surface.length in 2 until cand.surface.length` かつ `!JapaneseKanaUtils.isAllHiragana(other.surface)`」を満たす最短のベース語幹 `other.surface` が存在する場合、その `other.surface` を正規化語幹とする（例：`確認` がプールにあれば、`確認しました` と `確認させていただきます` はどちらも `stem = "確認"` に正規化される）。
   - 優先 3（形態素ヒューリスティックフォールバック）: 先頭の連続する漢字群（`[\u4E00-\u9FFF々]+`）またはカタカナ群（`[\u30A0-\u30FFー]+`）を抽出。末尾が1文字接尾辞（`中`, `的`, `化`, `後`, `前`）で語幹が3文字以上ならその手前までを語幹とみなす。ひらがな開始の場合は先頭から助詞・活用語尾前まで（または `surface` 全体）を返す。
   - 英語プールの場合: 小文字化した単語本体（アポストロフィ除去、先頭4文字以上の共通語幹または完全語）。

### 3.2 意図重複カーネル $\kappa(i, j)$ と貪欲選択アルゴリズム
`extIndices` のランキング（Step 9）を、独立ソートから **劣モジュラ限界効用貪欲選択（Submodular Marginal Expected EIS Utility Selection）** に置き換えます。

1. **重複カーネル $\kappa(i, j) \in [0, 1)$**:
   $$\kappa(i, j) = \begin{cases}
   0.80 & \text{if } \text{stem}(i) = \text{stem}(j) \land \text{horizon}(i) = \text{horizon}(j) \\
   0.35 & \text{if } \text{stem}(i) = \text{stem}(j) \land \text{horizon}(i) \ne \text{horizon}(j) \\
   0.50 & \text{if } \text{lastContent}(i) = \text{lastContent}(j) \ne \emptyset \\
   0.00 & \text{otherwise}
   \end{cases}$$

2. **初期残余被覆率の条件付け（`rankedFull` / `exactUserIndices` との連携）**:
   すでに `exactUserIndices` や上位 `rankedFull.take(2)` で選ばれた候補 $j$ がある場合、`extIndices` の各候補 $i$ の初期残余倍率 `residualMultiplier[i]` に $(1 - \kappa(i, j))$ を事前に乗算します。これにより、`EXACT` で既に上位に出た語幹と同じ語幹の `SHORT_EXT` 重複を抑えつつ、異ホライズンの `CHUNK_EXT`（$\kappa = 0.35$）や別語幹（$\kappa = 0.0$）を優先します。

3. **貪欲ループ（ステップ $r = 0, 1, \dots$）**:
   - スロット依存リスク重み：
     $$\gamma_{\text{risk}}(r) = \text{riskWeight} \times 0.65^{\min(r, 4)}$$
   - 各未選出候補 $i \in \text{remainingExt}$ の限界効用：
     $$\Delta \mathcal{U}(i \mid S) = \bigl(P_i \cdot \text{residualMultiplier}[i]\bigr) \cdot (G_{\text{base}, i} + G_{\text{ext}, i}) - (1 - P_i) \cdot \gamma_{\text{risk}}(r) \cdot L_{\text{err}, i}$$
   - 最大限界効用を持つ候補 $b = \arg\max_i \Delta \mathcal{U}(i \mid S)$（差が `0.03f` 以内のときは `baseScores[i]` 昇順でタイブレーク）を選出し、`rankedExtIndices` に追加。
   - 残りの全候補 $j \in \text{remainingExt}$ について `residualMultiplier[j] *= (1.0f - kappa(j, b))` を更新。

4. **Slot 1 安全性不変条件**:
   - Slot 1 への昇格判定（`candidateToPromote`）は、これまで通り独立効用 `utilities[i]` と `slot1MarginSec` を使用するため、不確実な候補が Slot 1 へ誤昇格するリスクは一切増加しません。

---

## 4. Idea D2 詳細設計：ホライズン直交性（Dual-Horizon Pairing）

### 4.1 発動条件とペアリング規則
Step 10（Adaptive Stratified Interleaving Schedule）において、以下の条件が満たされた場合に **アンカー短単語（Anchor Stem）** を可視スロット（Slot 2〜3）へペアリング配置します：

1. **長文チャンクの検出**:
   - 先頭（Slot 1、または Slot 1 が `exactUser` で Slot 2）に配置された候補 $W_{\text{top}}$ が `HorizonBucket.CHUNK_EXT`（すなわち `deltaExt >= 3` または `source == "phrase_chunk"`）であり、かつ $n \ge 1$（0文字予測ではない通常のフリック入力途中）である。
2. **アンカー短単語の探索**:
   - $W_{\text{top}}$ と同じ先頭語幹（`stem(u) == stem(W_top)`）を持ち、かつ `u.surface.length < W_top.surface.length`、`horizon(u) in {EXACT, SHORT_EXT}`（`deltaUnconsumed == 0` かつ `deltaExt <= 2`）を満たす候補 $u$ を `rankedExt` および `rankedFull` から探索する（複数ある場合は事後確率 $P_u$ または効用が最大のものを選択）。
3. **配置位置**:
   - もし探索されたアンカー短単語 $u_{\text{anchor}}$ がまだ `result` に追加されていなければ、$W_{\text{top}}$ の直後（Slot 2 または Slot 3 の可視領域）に 1 件だけ優先配置する（`seenSurfaces` で重複防止）。
   - **効果**: `かく`（2文字）で Slot 1 に `確認させていただきます` が出た際、直近の可視枠にアンカー `確認` が確実に並ぶため、後続表現が異なる場合でも即座に `確認` を確定して次の文節へ進めます。

---

## 5. 単体テスト検証計画

新規テストスイート [`FlickDiversityToplineEvaluationTest.kt`](app/src/test/java/com/example/myasr/FlickDiversityToplineEvaluationTest.kt) を追加し、以下を検証します：

1. **Idea D1 検証**: 同語幹の複数チャンク（`作業中ですので`, `作業中です`, `作業を進めます`）と別語幹の有力予測（`先ほど送付した` 等）が混在するプールにおいて、独立効用ソートでは同語幹が Top-3 を独占する状況で、劣モジュラ集合効用ランキングが Top-3 に異語幹候補を浮上させ、Unique Stem Coverage @ 3（USC@3）を向上させること。
2. **Idea D2 検証**: Slot 1 に長文チャンク（`確認させていただきます`）が昇格した際、同一語幹のアンカー短単語（`確認`）が可視スロット（Slot 2〜3）に確実にペアリング配置されること。
3. **Idea D3 検証**: `KanaKanjiEngine.getBaseCandidates("こう")` や `getBaseCandidates("かい")` 等において、B3 動的複文節ロールアウトが 1〜2 語幹に偏らず、3 種類以上の異なる語幹の `phrase_chunk` 候補を生成すること。
4. **既存テスト非回帰**: `SpanAwareBayesianRerankingEvaluationTest`, `DynamicMultiBunsetsuTailRolloutTest`, `EisRegressionTest`, `KanaKanjiEngineDiversityTest`, `JapanesePunctuationEvaluationTest`, `DddArchitectureKonsistTest`, `AlloyVerificationTest` の全パス。

## レビュー指摘への修正 (R1–R5)

| ID | 修正 |
|---|---|
| R1 | Slot 1 昇格候補は独立 utility で選定。submodular greedy は Slot 1 確定後にそれを条件として実行 (`runExtGreedy(conditionOnIdx)`) |
| R2 | `synthesizeDynamicMultiBunsetsuChunks` が `firstBunsetsuSurface = stem`、動的 chunk は `dictCost = stemWord.cost` を設定 (`extractCanonicalStem` の推測を排除) |
| R3 | `diversifyByFirstStem` を `interleavePredictiveCandidates` のソート後と `LlmReranker` の `phraseChunkPrefix.take(4)` 前に適用し、stem ラウンドロビンを維持 |
| R4 | `getBaseCandidates` の cacheKey に `isClauseStartOrTopicContext` (B3 テール順序の条件) を反映 |
| R5 | `remainingExt` から既出 surface (ペアリングされたアンカー等) を除外 |
