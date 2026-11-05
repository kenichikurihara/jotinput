# PRD & DD: フリックミス補正ひらがな候補の表示と整流化

## 1. PRD (Product Requirements Document)

### 1.1 背景と課題
- **背景**:
  JotInputのかな漢字変換（`KanaKanjiEngine`）および候補バー（`CandidateStripView`）では、「変換キー（確定キー）を押せば未変換のひらがな（composing text）がそのまま入力されるため、候補バーに同じひらがな候補を出すのは冗長である」という方針に基づき、ひらがなのみ（`JapaneseKanaUtils.isAllHiragana(cand.surface)`）の候補を一律非表示としていた。
- **課題**:
  1. ユーザーがフリック入力を誤った場合（例: 「さ」と打つつもりが「し」になった、あ段に戻って「さ」のままになった、隣接キーを誤タップした）、エンジン内部のフリック補正探索（`flickPaths`）によって正しい読みや語彙（例: 「わたし」）が検出されているにもかかわらず、その表記（`surface`）がひらがなである単語（「わたし」「これ」「ありがとう」等）やひらがなそのものが `isAllHiragana` チェックによって一律除外されてしまう。
  2. その結果、漢字表記（例: 「私」）は候補バーに出るが、平仮名表記（例: 「わたし」）が出ず、フリックをやり直すか手動で修正しなければならない。
  3. また、フリックミス補正パス（`fPath.reading`）そのもの（例: 入力が「わたさ」のとき「わたし」）がひらがな候補としてユーザーに提示されると、手動の再入力なしにワンタップでフリック誤りを訂正でき、IMEの利便性が大幅に向上する。

### 1.2 要求仕様 (Requirements)
1. **未変換入力と一致するひらがなのみ除外**:
   - 入力文字列（正規化後の `normalized`）と完全一致するひらがな候補は、従来通り冗長なため除外する。
2. **ミスフリック・補正によるひらがな候補の許容**:
   - フリックミス補正（`flickPaths`）や濁音・清音補正（`voicedPaths`）など、入力読みと異なる表記・読みを持つひらがな候補（`cand.surface != normalized`）は、候補バーに表示する。
3. **フリックミス補正読み自体のひらがな候補提示**:
   - `flickPaths` において有効な単語が存在する補正後の読み（例: 入力「わたさ」に対して辞書にマッチした「わたし」）について、その平仮名そのもの（`surface = fPath.reading`）も候補として提示する。
4. **SLM / AICore リランキングとの整合性**:
   - `LlmReranker` や `AiCoreReranker` でのフィルタリング条件も同様に、入力読みと完全一致するひらがなのみを除外し、補正された異なるひらがな候補はリランキング対象として維持する。
5. **回帰防止**:
   - 単文節・長文節の既存変換テスト、および `JapanesePunctuationEvaluationTest` などの既存テスト（100%パス必須）に悪影響を与えないこと。

---

## 2. DD (Design Document)

### 2.1 候補フィルタリング条件の変更
`KanaKanjiEngine.kt` における `addCandidate` 判定ロジック：

**Before:**
```kotlin
fun addCandidate(cand: ConversionCandidate) {
    if (cand.surface.isNotBlank() &&
        !JapaneseKanaUtils.isAllHiragana(cand.surface) &&
        seenSurfaces.add(cand.surface)) {
        result.add(cand)
    }
}
```

**After:**
```kotlin
fun addCandidate(cand: ConversionCandidate) {
    // Exclude all-hiragana candidate ONLY IF it is identical to raw composing text,
    // because enter/commit will input it directly.
    // Hiragana candidates derived from flick typo correction or voicing correction
    // (cand.surface != normalized) are valuable and must be shown.
    val isRedundantHiragana = JapaneseKanaUtils.isAllHiragana(cand.surface) && cand.surface == normalized
    if (cand.surface.isNotBlank() &&
        !isRedundantHiragana &&
        seenSurfaces.add(cand.surface)) {
        result.add(cand)
    }
}
```

### 2.2 フリック誤り補正（`flickPaths`）における平仮名候補の明示的追加
`flickPaths`（`typoCostPenalty > 0`）の各マッチについて：
1. 辞書エントリ（`w.surface`）を追加する。
2. もし `fPath.reading != normalized` であり、その読みが辞書単語として存在している場合、補正後の平仮名（`fPath.reading`）自体も候補（`surface = fPath.reading`）として追加する。
   - コスト: 基本ペナルティ（`fPath.typoCostPenalty`）を考慮して順位付け。

### 2.3 `CandidateStripView` & `LlmReranker` / `AiCoreReranker` の整合
- `CandidateStripView.kt`:
  余計な二重フィルタで補正ひらがなを落とさないよう、`isRedundantHiragana = isAllHiragana(it.surface) && it.surface == it.reading` 等の条件に調整、またはエンジン側で担保されているため表示を許容。
- `LlmReranker.kt` / `AiCoreReranker.kt`:
  同様に、未入力ママの同一ひらがなのみ除外。

### 2.4 テスト方針
1. `FlickTypoHiraganaTest`:
   - 「わたさ」と入力した際、変換候補に「私」だけでなく平仮名の「わたし」が含まれていることを検証。
   - 「あさ」と入力した際、入力通りの「あさ」は候補バーに出ず、「朝」等が出ることを検証。
2. 既存の `LongPhraseConversionTest`, `QwertyAndFlickUtilsTest`, `JapanesePunctuationEvaluationTest` を実行し、全件グリーンであることを確認。
