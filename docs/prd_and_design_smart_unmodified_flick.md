# 詳細設計書：スマート無修飾フリック（小書き仮名・濁音・半濁音レス入力）アーキテクチャ

- **ドキュメントID**: `DD-FLICK-001`
- **対象コンポーネント**:
  - `JapaneseKanaUtils` (`app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt`)
  - `KanaKanjiEngine` (`app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt`)
  - `LlmReranker` (`app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt`)
- **関連ドキュメント**:
  - [「とうきよ」デバッグ報告 ＆ フリック向上アイデア集 (`docs/flick_toukiyo_debug_and_topline_ideas.md`)](flick_toukiyo_debug_and_topline_ideas.md)
  - [日本語フリック入力 (CUJ-2) ヘッドルーム分析 (`docs/flick_eis_headroom_analysis.md`)](flick_eis_headroom_analysis.md)
  - [LM-First Flick & QWERTY アーキテクチャ (`docs/lm_first_flick_and_qwerty_architecture.md`)](lm_first_flick_and_qwerty_architecture.md)

---

## 1. 背景と課題 (Problem Statement)

### 1.1 観測された事象とボトルネック
ユーザーが「東京」を入力しようとしてフリック入力で **「とうきよ」**（4文字：小書き `ょ` を修飾せずに大文字 `よ` のまま入力し、末尾の長音 `う` を打つ前）と入力した際：
1. 本来最優先されるべき **「東京」が候補ストリップに一切現れない**。
2. 日本郵便の個別事業所データ（`place_names`）に起因する長大固有名詞 **「東京貯金事務センター」が上位にランクインする**。

### 1.2 根本原因の構造的分解
1. **音韻修飾展開の片手落ち**:
   - `JapaneseKanaUtils.getVoicingExpansions` は清音 $\leftrightarrow$ 濁音・半濁音（`か` $\leftrightarrow$ `が`、`は` $\leftrightarrow$ `ば`/`ぱ`）のみを展開し、**小書き仮名（`よ` $\leftrightarrow$ `ょ`、`つ` $\leftrightarrow$ `っ`、`や` $\leftrightarrow$ `ゃ`、`ゆ` $\leftrightarrow$ `ゅ`、母音小書き）を完全に除外** していた。
2. **ファジープレフィックス検索の辞書パイプライン断絶**:
   - `KanaKanjiEngine.getBaseCandidates` のステップ 3.8（ファジー/濁音プレフィックス検索）において、インメモリ Trie と SQLite のみをクエリし、**主力の大規模 Mmap 辞書群（`system_dictionary.bin` / `mozc_general` / `sudachidict`）に対する `queryAllMmapPrefixTokens` の呼び出しが完全に欠落** していた。
3. **フリック打鍵コストの構造的肥大化**:
   - [CUJ-2 ヘッドルーム分析](flick_eis_headroom_analysis.md) が示すとおり、フリック入力時間の **82.7%** は打鍵時間が占めている。「小」「゛」キーを押す修飾操作（1文字あたり 2 アクション、打鍵数 1.3×）を強制することが、ユーザーの打ち直し（$C_{\text{none}} + \text{retype}$）と EIS 低下の主要因となっている。

---

## 2. Jobs to be Done (JTBD) & Critical User Journeys (CUJ)

### 2.1 Jobs to be Done
- **When**: スマートフォンで移動中や片手操作で高速に日本語フリック入力しているとき
- **I want to**: 「小」「゛」「゜」キーなどの修飾キーを意識的に押さずに、母音・子音の基底キー（清音・大文字）だけを連続フリック入力したい
- **So that**: 打ち直しのストレスをゼロにし、打鍵数と時間を大幅に削減して、意図通りの漢字・複合語（例：「東京」「会議」「ちょっと」「了解」）を最上位で即座に確定できる

### 2.2 Critical User Journeys (CUJ)
- **CUJ-A (小書き仮名の省略・先読み)**:
  - ユーザーが「とうきよ」（4打鍵）と入力 $\to$ 候補ストリップに **「東京」**（`とうきょう`、5文字）が上位（Slot 1〜2）に提示される $\to$ ユーザーは「小」キーも末尾「う」も打たずに 1 タップで確定。
- **CUJ-B (促音・長音・濁音の複合無修飾)**:
  - ユーザーが「ちよつと」（4打鍵）と入力 $\to$ 「ちょっと」および「一寸」が Top-1 に提示される。
  - ユーザーが「かいき」（3打鍵）と入力 $\to$ 文脈「明日の」のもとで「会議」（`かいぎ`）が Top-1 に提示される。
- **CUJ-C (完全一致の非破壊性・非退行)**:
  - ユーザーが清音意図で「きよ」（器用・清等）や「たいき」（大気・待機等）と打った場合、完全一致語が不当に排除されず、文脈と言語モデル確率に基づいて自然に共存する。

---

## 3. 数理モデル・音韻修飾距離の定式化

### 3.1 ベイズ事後確率の定式化
入力された仮名文字列を $X = (x_1, x_2, \dots, x_m)$、候補単語を $W$、その正統な読み仮名を $R = (r_1, r_2, \dots, r_n)$ とする。
候補 $W$ の事後スコア $\mathcal{S}(W \mid X, C)$ は、言語モデル対数尤度と観測ペナルティの和として定義される：

$$\mathcal{S}(W \mid X, C) = -\log P_{\text{SLM}}(W \mid C) + \alpha \cdot \text{Cost}_{\text{dict}}(W) + \lambda_{\text{obs}} \cdot \mathcal{D}_{\text{obs}}(X, R) + \lambda_{\text{ext}} \cdot \Delta_{\text{ext}}(X, R)$$

ここで：
- $\Delta_{\text{ext}}(X, R) = \max(0, |R| - |X|)$：未入力プレフィックスの延長ペナルティ
- $\mathcal{D}_{\text{obs}}(X, R)$：観測音韻修飾ペナルティ（後述）

### 3.2 統合音韻修飾距離 (Unified Phonetic Modifier Distance)
入力文字 $x_i$ と候補文字 $r_i$ の音韻関係を以下の 3 レベルに分類し、修飾距離 $d_{\text{mod}}(x_i, r_i)$ を定義する：

$$d_{\text{mod}}(x_i, r_i) = \begin{cases} 0 & (x_i = r_i \text{：完全一致}) \\ 1 & (r_i \in \text{Mod}(x_i) \text{：濁音化・半濁音化・小書き化}) \\ \infty & (\text{その他}) \end{cases}$$

展開集合 $\text{Mod}(x)$ は以下の音韻同値類を網羅する：
1. **拗音・小書き仮名**:
   - `'よ' \leftrightarrow 'ょ'`, `'や' \leftrightarrow 'ゃ'`, `'ゆ' \leftrightarrow 'ゅ'`
   - `'つ' \leftrightarrow 'っ'`
   - `'わ' \leftrightarrow 'ゎ'`
2. **小書き母音（外来語・感嘆表現）**:
   - `'あ' \leftrightarrow 'ぁ'`, `'い' \leftrightarrow 'ぃ'`, `'う' \leftrightarrow 'ぅ' / 'ゔ'`, `'え' \leftrightarrow 'ぇ'`, `'お' \leftrightarrow 'ぉ'`
3. **濁音・半濁音**:
   - カ行 $\leftrightarrow$ ガ行、サ行 $\leftrightarrow$ ザ行、タ行 $\leftrightarrow$ ダ行（`つ` は `づ` / `っ` の双方へ展開）
   - ハ行 $\leftrightarrow$ バ行 / パ行

文字列全体の修飾距離は累積和で表される：

$$D_{\text{mod}}(X, R_{1..m}) = \sum_{i=1}^{m} d_{\text{mod}}(x_i, r_i)$$

探索木の探索予算として $D_{\text{mod}} \le 2$ を上限とし、$O(1)$ の Trie 分割で計算量を厳密に抑制する。

### 3.3 観測ペナルティ $\mathcal{D}_{\text{obs}}$ と SLM の協調
- 辞書コストペナルティ: $\text{cost}_{\text{penalty}} = D_{\text{mod}} \times 1200$
- 観測負対数尤度ペナルティ: $\text{obsPenalty} = 1.2 \times D_{\text{mod}}$
- **効果**:
  - `とうきよ` に対する `とうきょう`（`東京`）は $D_{\text{mod}} = 1$（`よ→ょ`）であるため $\text{obsPenalty} = 1.2$、$\Delta_{\text{ext}} = 1$。
  - `東京` の Mozc コストは極めて低く（高頻度語）、SLM の事前対数尤度 $-\log P(\text{東京})$ も非常に小さいため、ペナルティを容易に相殺して最上位にランクインする。
  - 一方、「東京貯金事務センター」は $\Delta_{\text{ext}} = 10$ であり、$\lambda_{\text{ext}} \cdot 10$ の延長ペナルティによって自然に下位へ抑制される。

---

## 4. コンポーネント詳細設計

### 4.1 `JapaneseKanaUtils.kt` の改修設計
- `HIRAGANA_VOICING_EXPANSION_MAP` を **`HIRAGANA_MODIFIER_EXPANSION_MAP`** に拡張。
- 従来の `getVoicingExpansions(char: Char): List<Char>` のシグネチャを完全維持しつつ、小書き仮名の双方向マッピングを追加。
- 既存の濁音・半濁音に加え、以下を網羅：
  - `'よ'` $\to$ `['よ', 'ょ']`, `'ょ'` $\to$ `['ょ', 'よ']`
  - `'や'` $\to$ `['や', 'ゃ']`, `'ゃ'` $\to$ `['ゃ', 'や']`
  - `'ゆ'` $\to$ `['ゆ', 'ゅ']`, `'ゅ'` $\to$ `['ゅ', 'ゆ']`
  - `'つ'` $\to$ `['つ', 'づ', 'っ']`, `'っ'` $\to$ `['っ', 'つ']`, `'づ'` $\to$ `['づ', 'つ']`
  - `'あ'` $\to$ `['あ', 'ぁ']`, `'い'` $\to$ `['い', 'ぃ']`, `'う'` $\to$ `['う', 'ゔ', 'ぅ']`, `'え'` $\to$ `['え', 'ぇ']`, `'お'` $\to$ `['お', 'ぉ']`
  - `'わ'` $\to$ `['わ', 'ゎ']`

### 4.2 `KanaKanjiEngine.kt` の改修設計
1. **ステップ 3.8（ファジー/音韻修飾プレフィックス検索）に `MmapDictionary` 連携を追加**:
   ```kotlin
   // 3.8. Voiced, Semi-voiced & Small-Kana Predictive Prefix Matches
   if (voicedPaths.isNotEmpty()) {
       ...
       for ((reading, entry, vDist) in voicedPrefixWords) {
           addCandidate(ConversionCandidate(surface = entry.surface, reading = reading, isPrediction = true, source = entry.source, obsPenalty = 1.2f * vDist))
       }
       // Query Mmap & SQLite prefix matches for top modified readings (up to 2 readings)
       for (vPath in voicedPaths.take(2)) {
           val mmapVoicedPrefix = queryAllMmapPrefixTokens(vPath.reading, maxCount = 10)
           for (cand in mmapVoicedPrefix) {
               addCandidate(ConversionCandidate(surface = cand.surface, reading = cand.reading, isPrediction = true, source = cand.source, obsPenalty = 1.2f * vPath.voicingDistance))
           }
           try {
               val dbVoicedPrefix = dbHelper?.getPrefixMatches(vPath.reading, limit = 15) ?: emptyList()
               for (cand in dbVoicedPrefix) {
                   addCandidate(ConversionCandidate(surface = cand.surface, reading = cand.reading, isPrediction = true, source = cand.source, obsPenalty = 1.2f * vPath.voicingDistance))
               }
           } catch (e: Exception) {
               Log.w(TAG, "DB voiced prefix query failed: ${e.message}")
           }
       }
   }
   ```
2. **`obsPenalty` の伝播**:
   - `voicedPrefixWords`（インメモリ Trie 由来）の `addCandidate` 呼び出しに `obsPenalty = 1.2f * vDist` を確実に付与し、SLM リランカーでの公平な Bayesian 競合を保証する。

---

## 5. DDD / アーキテクチャ境界と Konsist 整合性

- `JapaneseKanaUtils` および `KanaKanjiEngine` は純粋なドメイン層（`context.kanakanji.domain`）に位置し、Android UI（`android.view.*`）やインフラ層への不正依存は一切導入しない。
- `DddArchitectureKonsistTest` のルールに完全準拠し、Shared Kernel / Domain の純粋性を担保する。

---

## 6. 非退行検証計画

1. **`FlickTypoAndMozcCorrectionEvaluationTest`**:
   - `testFlickUnmodifiedToukiyoSuggestsTokyo()`: 「とうきよ」から「東京」が候補ストリップに存在し、上位にランクインすることを検証。
   - `testFlickUnmodifiedChiyotsutoSuggestsChotto()`: 「ちよつと」から「ちょっと」が出現することを検証。
   - `testFlickUnmodifiedKaikiSuggestsKaigi()`: 「かいき」から「会議」が出現することを検証。
2. **全スイート実行**:
   - `./gradlew testDebugUnitTest` を実行し、既存の全単体テスト（特に `JapanesePunctuationEvaluationTest` 67+ 件、`LmFirstJointEvaluationTest`）が 100% 合格することを確認。
