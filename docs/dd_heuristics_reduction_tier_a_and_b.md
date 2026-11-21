# 詳細設計書 (Detailed Design Document): ヒューリスティクス削減計画 (Tier A0 / A / B)

- **作成日**: 2026-11-20（v2: セルフ・コード照合レビュー反映）
- **ステータス**: 詳細設計完了・実装対象
- **上位文書**: [コードベース内ヒューリスティクス網羅リスト＆削減ロードマップ (`docs/heuristics_inventory_and_reduction_plan.md`)](docs/heuristics_inventory_and_reduction_plan.md)
- **関連ポリシー**:
  - `AGENTS.md` / `GEMINI.md`（Anti-Anecdotal Tuning Policy、Top-Line Quality Contract、Model & Lexicon Decoupling Contract、DDD/Konsist 境界契約）

---

## 0. 初版 DD レビューで検出した設計欠陥と修正点

初版 DD を実コード・テストスイート・リポジトリ構成と照合した結果、以下の 6 点の設計上の問題が見つかったため、本 v2 で修正しています。

1. **【A-1 重大】辞書未ダウンロード時のコールドスタート安全網の喪失**:
   - `KanaKanjiEngine.initializeIfNeeded()` ([`KanaKanjiEngine.kt:320-366`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L320-L366)) は、Model & Lexicon Decoupling Contract により APK に `base_dictionary.tsv` も `.bin` も同梱していないため、**初回起動・辞書未ダウンロード状態では `registerCanonicalVocabulary()` のみが唯一のインメモリ語彙**になります。また `DebugDisplayTest` (L25) や `UnifiedEnglishDictionaryTest` (L187) は辞書ファイルなしで `KanaKanjiEngine()` を生成しています。
   - さらに `registerCanonicalVocabulary()` の 78 語のうち 11 語（`でした`, `たかった`, `なかった`, `である`, `だった`, `だろう`, `ている`, `てい`, `てお`, `している`, `しています`）は複形態素チャンクであり、単形態素辞書である `base_dictionary.tsv` には存在しません。さらに `source == "canonical"` は [`KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) の 6 箇所（L798, L1760, L1854, L2137, L2235 等）で無条件保護・コスト免除の特別扱いを受けています。
   - **修正**: `registerCanonicalVocabulary()` を無条件削除するのではなく、**(a) 辞書ロード済み時は `source == "canonical"` のコスト 50 上書き＆特別免除（6 箇所）を廃止してシステム辞書のコーパス由来コストに委譲**し、**(b) ` !baseLoaded`（辞書未ダウンロード時・空エンジンのテスト時）の最小フォールバック語彙としてのみ登録**する設計に改めます。複形態素 11 語は Viterbi の 2 形態素連結で自然に生成されることをテストで確認してから除外します。
2. **【A-2 重大】`CANONICAL_COLLOCATIONS` の複数コンテキスト参照とテスト契約**:
   - `NextPhrasePredictor.CANONICAL_STEM_READINGS` / `CANONICAL_COLLOCATIONS` は `NextPhrasePredictor.kt` だけでなく、[`KanaKanjiEngine.kt:2391-2475`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L2391-L2475)（動的複文節テイル合成 Loop 1）、[`KanaKanjiEngine.kt:752`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L752)（`canonical_projected` 優先度 4.5f）、さらに [`NextPhrasePredictorTest.kt:82,155`](app/src/test/java/com/example/myasr/NextPhrasePredictorTest.kt#L82)、[`DynamicMultiBunsetsuTailRolloutTest.kt:157`](app/src/test/java/com/example/myasr/DynamicMultiBunsetsuTailRolloutTest.kt#L157)、[`Phase3SlmAndContextualPredictionTest.kt:63`](app/src/test/java/com/example/myasr/Phase3SlmAndContextualPredictionTest.kt#L63) から直接参照・アサートされています。
   - また文頭挨拶を単純削除すると `NextPhrasePredictorTest.testSentenceStartAndPunctuationHeuristics`（空文脈で `お疲れ様です`、句点後に `また、` / `つきましては、` を要求）が即座に失敗します。
   - **修正**: 時間帯依存（`Calendar.getInstance()` による 5..10 時分岐）を撤廃し、文頭・句点後候補とコロケーションは **BOS (文頭品詞 ID=0) / 直前語からの統計接続テーブル（TSV/JSON リソースまたは `KanaKanjiEngine` の `DYNAMIC_*_TAILS` との統合テーブル）** へ移行し、既存テスト契約を満たす設計にします。
3. **【G2 評価ゲート】`evaluate_flick_headroom_100.py` は `KanaKanjiEngine` を呼ばない**:
   - `eval/evaluate_flick_headroom_100.py` は KLM 数式シミュレータであり、実際の `KanaKanjiEngine` を実行しません。
   - **修正**: G2（SLM-off 仮名漢字評価）の実行ハーネスを、実際に JVM 上で `KanaKanjiEngine` を駆動する [`FlickDiversityToplineEvaluationTest`](app/src/test/java/com/example/myasr/FlickDiversityToplineEvaluationTest.kt)、[`JotInputPredictionEvaluationRunnerTest`](app/src/test/java/com/example/myasr/JotInputPredictionEvaluationRunnerTest.kt)、[`LongPhraseConversionTest`](app/src/test/java/com/example/myasr/LongPhraseConversionTest.kt)、[`EisRegressionTest`](app/src/test/java/com/example/myasr/EisRegressionTest.kt)、[`KanaKanjiEngineDiversityTest`](app/src/test/java/com/example/myasr/KanaKanjiEngineDiversityTest.kt)、[`DynamicMultiBunsetsuTailRolloutTest`](app/src/test/java/com/example/myasr/DynamicMultiBunsetsuTailRolloutTest.kt) に修正します。
4. **【B-1】リポジトリ内に `tools/mozc/src/data/dictionary_oss/id.def` が存在しない**:
   - `tools/mozc/` は git 管理外であり、現在のワークスペースには存在しません。
   - **修正**: `id.def` の POS ID 範囲知識（現在 `KanaKanjiEngine.kt` と `tools/eval_nbest_diversity.py` に散在）を単一の宣言的定義ファイル `tools/mozc_dict/mozc_pos_ranges.json`（または `MozcPosCatalog.kt` オブジェクト）に集約し、Kotlin と Python の双方から単一ソースを参照する設計に変更します（外部クローン不要）。
5. **【A0-3】`EditAndSpatialCost` には個別 JNI エントリが無く、ホスト JVM 単体テストではネイティブ `.so` をロードしない**:
   - **修正**: 新規 JNI 関数は追加せず、共有定数仕様を `EnglishDictionaryUtils.SpatialEditCostParams` と C++ `constexpr` 定数へそれぞれ整理し、定数値の一致を Konsist / ソース検証テストで保証する軽量な設計にします。
6. **【B-5】`ConversionCandidate` は Published Language 相当の共有モデル**:
   - フィールド追加は影響範囲が広いため、既存フィールド（`dictCost`, `priority`）の意味付け整理で対応し、公開データクラスのシグネチャ変更を最小化します。

---

## 1. 実装スコープと基本原則

### 1.1 設計原則
1. **SLM-off 品質および辞書未ダウンロード時フォールバックの維持**: SLM 未導入環境（Viterbi 単体）および初回起動時（辞書未ダウンロード）の安全網を壊さないことを絶対条件とします。
2. **1 PR = 1 サブタスク（独立した A/B 計測）**: Phase 0 → Phase 1 → Phase 2 の各サブタスク（`A0-*`, `A-*`, `B-*`）を独立したコミット/PR として実装・計測します。
3. **Anti-Anecdotal Tuning Policy 遵守**: 個別単語の手書きコスト補填は一切行いません。
4. **DDD / Konsist 境界の維持**: [`DddArchitectureKonsistTest`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) の全ルールを 100% 維持します。

### 1.2 共通検証ゲート（各サブタスクの完了条件）

| ゲート | 実行コマンド / 実際の評価ハーネス | 合格基準 |
| :--- | :--- | :--- |
| **G1: 全 JVM ユニット / DDD / Alloy** | `./gradlew testDebugUnitTest` | 100% パス（`JapanesePunctuationEvaluationTest`, `DddArchitectureKonsistTest`, `AlloyVerificationTest` 含む） |
| **G2: 仮名漢字・Viterbi (SLM-off)** | `./gradlew testDebugUnitTest --tests "*.EisRegressionTest" --tests "*.FlickDiversityToplineEvaluationTest" --tests "*.JotInputPredictionEvaluationRunnerTest" --tests "*.LongPhraseConversionTest" --tests "*.KanaKanjiEngineDiversityTest" --tests "*.DynamicMultiBunsetsuTailRolloutTest" --tests "*.FlickTypoAndMozcCorrectionEvaluationTest"` | 全テスト 100% パス、かつ `JotInputPredictionEvaluationRunnerTest` / `EisRegressionTest` の Top-1・Hit Rate・EIS（CUJ-2 `>= 110 chars/min`, 削減率 `>= 35%`）がベースライン比 **-0.5pt 以内** |
| **G3: 仮名漢字・SLM リランク (SLM-on)** | `./gradlew testDebugUnitTest --tests "*.LmFirstJointEvaluationTest" --tests "*.SpanAwareBayesianRerankingEvaluationTest" --tests "*.HistorySlmRerankEvaluationTest"` + `python3 eval/tune_and_eval_lm_first.py` | 247件ベンチマーク Top-1 (`90.28%`), Top-3 (`99.19%`), MRR (`0.9460`) が非劣化 |
| **G4: ゼロクエリ予測** | `./gradlew testDebugUnitTest --tests "*.NextPhrasePredictorTest" --tests "*.ReferencePredictionEvaluationTest" --tests "*.Phase3SlmAndContextualPredictionTest"` | 全テスト 100% パス、Zero-query EIS `>= 250 chars/min` |
| **G5: 英語入力 / 音声訂正** | `./gradlew testDebugUnitTest --tests "*English*" --tests "*Voice*"` + `python3 eval/evaluate_voice_correction_learning.py` | 全テスト 100% パス |
| **G6: 実機検証** | `./deploy.sh` | ARCVM + Pixel の両端末で `AsrManager` ロード・変換動作正常 |

---

## 2. Phase 0 (Tier A0): 挙動不変リファクタリング詳細設計

出力を 1 ビットも変えずに重複定義と死蔵定数を解消します。**必須ゲート: G1**。

### A0-1: 未使用定数の削除
- **対象**: [`tools/mozc_dict/build_base_dictionary.py:20`](tools/mozc_dict/build_base_dictionary.py#L20) の `MAX_COST_THRESHOLD = 5500`
- **変更**: 未使用定義行を削除。

### A0-2: `Idea V1` 二重実装の単一純粋関数への統一
- **現状**: [`KanaKanjiEngine.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) 内の [`singleViterbiCost`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L796-L814) と [ラティス探索内ループ](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L1857-L1894) に同一定数（`boundaryChars = "とはがをにでものかねよ"`, `8500`, `4000f`, `0.15f..1.0f`, `4500`, `5200`, `0.25f`, `2000`, 希少語閾値 `6200`）が重複実装されています。
- **変更仕様**:
  ```kotlin
  internal const val BOUNDARY_PARTICLE_CHARS = "とはがをにでものかねよ"
  internal const val DEFAULT_LENGTH_BONUS_PER_CHAR = 2000
  internal const val RARITY_HINGE_THRESHOLD = 6200

  internal fun computeLengthBonus(
      subReading: String,
      rawCost: Int,
      lid: Int,
      isUserOrCanonical: Boolean,
      checkLeadingParticle: Boolean,
      prevIsFunctional: Boolean,
      bonusPerChar: Int = DEFAULT_LENGTH_BONUS_PER_CHAR
  ): Int
  ```
  を抽出し、`singleViterbiCost`（`checkLeadingParticle = false`）とラティス探索（`checkLeadingParticle = i > 0`）の双方から呼び出します。挙動は 100% 不変です。

### A0-3: 英語空間編集距離の定数集約と同期テスト
- **現状**: [`EnglishDictionaryUtils.kt:93-112`](app/src/main/java/com/example/myasr/context/english/domain/EnglishDictionaryUtils.kt#L93-L112) と [`english_spell_engine.cc:645-668`](app/src/main/cpp/spelling/english_spell_engine.cc#L645-L668) に編集距離パラメータが直書きされています。
- **変更仕様**:
  - `EnglishDictionaryUtils` 内に名前付き定数（`BOUNDARY_GAP_COST = 1.5f`, `INDEL_COST = 1.2f`, `SUB_BASE_COST = 0.8f`, `SUB_DIST_SLOPE = 0.3f`, `SUB_MAX_DIST = 2.0f`, `TRANSPOSITION_COST = 0.6f`, `MAX_NORM_TYPO_COST = 0.38f`）を定義。
  - `english_spell_engine.cc` 側も同名の `constexpr` 定数へ整理。
  - `UnifiedEnglishDictionaryTest` に C++ ソース内の `constexpr` 値と Kotlin 定数値が一致することを検証するテストを追加し、今後のチューニング時の片側修正漏れを防ぎます。

### A0-4: `LlmReranker.muSpan` 重複定義の統一
- **対象**: [`LlmReranker.kt`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt) L222 と L1295。
- **変更**: `companion object` に `private const val DEFAULT_MU_SPAN_JA = 2.20f` / `DEFAULT_MU_SPAN_EN = 1.40f` を定義し統一。

### A0-5: VAD 打鍵抑制・ASR 重複・IME タイムアウト定数の集約
- **変更**:
  1. `StreamingVadProcessor.DEFAULT_TYPING_SUPPRESS_MS = 350L` を定義し、`notifyUserTyping(durationMs: Long = DEFAULT_TYPING_SUPPRESS_MS)` のデフォルト値を実効値 `350L` に更新。`VoiceInputMethodService.kt` の `notifyUserTyping(350L)` 13 箇所は引数省略（デフォルト使用）に簡素化し、`500L` の 1 箇所のみ `TYPING_SUPPRESS_LONG_MS = 500L` を渡す。
  2. `AsrManager.kt` の `DEFAULT_MAX_ACTIVE_PATHS = 8`, `DEFAULT_HOTWORDS_SCORE = 3.5f` を `companion object` 定数に統一。
  3. `VoiceInputMethodService.kt` の `SLM_RERANK_TIMEOUT_MS = 2500L`, `ASR_TRANSCRIBE_TIMEOUT_MS = 3000L`, `CORRECTION_MAX_TAIL_CHARS = 32` を `companion object` 定数に集約。

---

## 3. Phase 1 (Tier A): ハードコード語彙・フォールバックの評価付き整理

**必須ゲート: G1 + G2 + G3 + G4**。

### A-1: `registerCanonicalVocabulary()` の「コーパス優先化」と `source == "canonical"` 特別免除の廃止
- **背景（レビュー指摘反映）**:
  - `registerCanonicalVocabulary()` は現在、システム辞書 (`base_dictionary.tsv` / `MmapDictionary`) がロード済みであっても**常に `cost = 50, source = "canonical"` でインメモリ Trie に強制挿入**され、さらに Viterbi 探索で `source == "canonical"` は **「長さボーナスの信頼度減衰なし（無条件 +2000）」「希少語ペナルティ 0」「単語コスト合計 (`wordCostSum`) への加算 0」「ビーム枝刈り無条件保護」** という強力な特別扱い（L798, L1760, L1854, L1888, L1915, L2137, L2235）を受けています。
  - これにより、システム辞書が持つ本来のコーパス統計コストが機能語・一部動詞で無視されています。
- **詳細設計**:
  1. **`base_dictionary.tsv` カバレッジ確認**:
     - 単形態素の canonical 語（助詞・助動詞・可能動詞連用形 67 語）はすべて `base_dictionary.tsv` に既に収録されています。
     - 残る 11 語（`でした`, `たかった`, `なかった`, `である`, `だった`, `だろう`, `ている`, `てい`, `てお`, `している`, `しています`）は複形態素であり、Viterbi ラティス上で構成形態素（例: `て(348)` + `いる(1084)`）の連結として自然に生成されます。
  2. **辞書ロード済み時の重複登録の廃止**:
     - `initializeIfNeeded()` および `initializeWithStream()` において、`baseLoaded == true`（Mmap 辞書または `base_dictionary.tsv` がロードされた場合）は、78 語の `highPriority` リストを Trie に重複登録しない。
     - `baseLoaded == false`（初回起動で辞書未ダウンロード時、または辞書なし単体テスト時）のみ、最小サバイバル語彙として登録する。
     - 対括弧補完（`openingBrackets`, `closingBrackets`, `kakkoPairs`）は `registerStructuralBrackets()` として分離し、`source = "bracket"` として機能維持する。
  3. **`source == "canonical"` 特別免除の縮小**:
     - Viterbi 内の `isUserOrCanon` のうち、ユーザー辞書保護 (`source == "user"`) と canonical を分離し、システム辞書由来の機能語がすべて Mozc 本来の Unigram コスト＋接続コストで公平に評価されるようにする。
- **検証**: **G1 + G2 + G3**（特に `LongPhraseConversionTest`, `KanaKanjiEngineVoicingTest`, `DebugDisplayTest` が通ることを確認）。

### A-2: `NextPhrasePredictor` 時間帯分岐の撤廃とコロケーション定義の整理
- **詳細設計**:
  1. **非決定的な時刻依存 (`Calendar.getInstance()`) の撤廃**:
     - [`NextPhrasePredictor.kt:742-745`](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt#L742-L745) の `HOUR_OF_DAY in 5..10` による非決定的な分岐を削除する（テスト実行時刻によって候補順位が変わる副作用も解消）。
     - 文頭 (`isSentenceStart`) のデフォルト定型句リストは、ユーザー履歴が空のときのフォールバックシード（`DEFAULT_SENTENCE_START_SEEDS`）として整理し、ユーザー確定履歴 (`historyMap`) が蓄積され次第、履歴スコアが優先される構造にする（これにより `NextPhrasePredictorTest.testSentenceStartAndPunctuationHeuristics` の契約も維持）。
  2. **`CANONICAL_COLLOCATIONS` と `DYNAMIC_*_TAILS` の重複整理**:
     - 現在、`NextPhrasePredictor.CANONICAL_COLLOCATIONS` と `KanaKanjiEngine` の `DYNAMIC_SAHEN_TAILS` / `DYNAMIC_VERB_TAILS` / `DYNAMIC_NOUN_TAILS` の双方が「サ変名詞/動詞＋助詞＋述語テイル」を別々に管理しています。
     - `KanaKanjiEngine.synthesizeDynamicMultiBunsetsuChunks` の Loop 1（ハードコード語幹のみの優先ループ）と Loop 2（任意のサ変名詞・動詞に対する汎用 POS 接続ループ）の比率を見直し、汎用 POS 接続ループ（Loop 2）でカバーできる定型サ変語幹（`確認します`, `連絡します` 等）を段階的に Loop 2 へ統合する。
- **検証**: **G1 + G4**（`NextPhrasePredictorTest`, `DynamicMultiBunsetsuTailRolloutTest`, `Phase3SlmAndContextualPredictionTest`, `ReferencePredictionEvaluationTest` 100% パス）。

---

## 4. Phase 2 (Tier B): 統計・モデル置換詳細設計

**必須ゲート: G1〜G5 全て**。

### B-1: `MozcPosCatalog` への POS ID 集約 & `isVerbStem` 末尾文字フォールバックの削減
- **背景（レビュー指摘反映）**: リポジトリ内に `tools/mozc/` は存在しないため、外部ファイルに依存せず現在の Mozc OSS `id.def` 準拠の ID 範囲を単一のカタログクラスへ集約します。
- **詳細設計**:
  1. [`app/src/main/java/com/example/myasr/context/kanakanji/domain/MozcPosCatalog.kt`](app/src/main/java/com/example/myasr/context/kanakanji/domain/MozcPosCatalog.kt) を新規作成し、`KanaKanjiEngine.kt` 内に散在する全 POS ID マジックナンバー（`12..28`, `29..267`, `29..433`, `385`, `434..1840`, `1841..2195`, `1917..1940`, `1941..2034`, `2035..2150`, `2196..2590`, `2632`, `2659..2671` 等）および固定アンカー ID（`POS_AUX_DESU = 172`, `POS_AUX_MASU = 240`, `POS_NOUN_GENERAL = 1851`, `POS_SAHEN_SURU_MASU = 1825`, `POS_SUFFIX_CHUU = 2003`, `POS_SYMBOL = 2632`）を `IntArray(2672)` のビットマスクテーブル＋名前付き定数に集約する。
  2. `KanaKanjiEngine.kt` と `MultiDictionaryManager.kt` の全 POS ID リテラルを `MozcPosCatalog` 参照に置換する（この段階では挙動 100% 不変）。
  3. 次に [`KanaKanjiEngine.isVerbStem`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L2358-L2363) の末尾文字ヒューリスティクス（`surface.endsWith("っ" / "い" / "め" / ...)`）を削除し、`MozcPosCatalog.isPredicatePos(lid) || MozcPosCatalog.isPredicatePos(rid)` のみで **G1 + G2**（特に `DynamicMultiBunsetsuTailRolloutTest`）を検証する。
     - もし `base_dictionary.tsv` 内で `lid=0, rid=0`（POS 未付与）の動詞語幹エントリが原因でテストが落ちる場合は、`base_dictionary.tsv` 側の POS 欠損エントリを修正することで解決し、コード上の文字末尾ヒューリスティクスを完全撤廃する。

### B-2: `connectionMatrix == null` 専用ヒューリスティクスの撤廃
- **詳細設計**:
  1. `KanaKanjiEngine` で `connectionMatrix` が未設定のときにも `ConnectionMatrix` を自動ロードできるよう、JVM テスト環境用のフォールバックローダ（`app/src/main/assets/mozc/connection_data.bin` が存在すればロード）またはテストヘルパーを整備する。
  2. [`KanaKanjiEngine.kt:1881-1885`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L1881-L1885) の `matrix == null` 時専用ペナルティ（単一カタカナ・古語「之」`lid == 385` に `+3000`）を削除し、`matrix?.getTransitionCost(path.lastRid, lid) ?: 0` に統一する。
- **検証**: **G1 + G2**（`ConnectionMatrixTest` 等の意図的な null テストが壊れないことを確認）。

### B-3: Viterbi 内アドホック補正 (`Idea V1 / V2 / V3`) の SLM-off / SLM-on 二軸評価と縮約
- **詳細設計**:
  1. **Step B-3a: 長さボーナス係数 (`DEFAULT_LENGTH_BONUS_PER_CHAR = 2000`) の感度分析**:
     - `bonusPerChar` を $\{0, 500, 1000, 1500, 2000\}$ で変化させ、**G2（SLM-off JVM 変換評価スイート）** と **G3（SLM-on 247件ベンチマーク）** の通過率を計測する。
     - 同時に、`predicateBunsetsuBonus = 2000`（用言＋助動詞ボーナス）を `0` にした場合の差分も計測し、不要であれば削除、必要であれば `bonusPerChar` に統合する。
  2. **Step B-3b: `Idea V2`（並列協調対称ボーナス `coordBonus`・助詞後固有名詞ペナルティ `properAfterParticlePen`）の削除評価**:
     - `coordBonus`（カタカナ＋と/や＋カタカナに `+1200`、漢字に `+600`）および `pendingCoordKind` 状態変数をラティス状態 `ViterbiPath` から削除し、**G1 + G2 + G3** を実行する。非劣化であれば完全削除する（`ViterbiPath` のフィールド削減により GC 負荷も軽減）。
     - `properAfterParticlePen` (`+1200`) も同様にアブレーション計測を行い、ConnectionMatrix の助詞→固有名詞遷移コストと `rarityDelta` で代替可能なら削除する。
  3. **Step B-3c: `Idea V3`（Viterbi 2D 多様性ペナルティ）と SLM 側 2D-MMR の役割分担**:
     - SLM リランキング有効時は `LlmReranker` 側で 2D-MMR / Submodular 多様性制御が行われるため、Viterbi 側の `Idea V3` と重複している。アブレーションで G2 / G3 への寄与を計測し、縮約または統合する。

### B-4: `LlmReranker` パラメータ縮約 & 二重コスト補正の整理
- **現状**:
  1. Viterbi 側 `Idea R1`（[`KanaKanjiEngine.kt:2075-2078`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L2075-L2078): `avgWordCost + max(0, maxWordCost - 4500)/2 + avgExcessTrans`）で既に希少語超過ペナルティが加算された `dictCost` に対し、`LlmReranker` 側（[`LlmReranker.kt:398-401`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt#L398-L401)）がさらに `0.45f * rareExcess^2` (`rareExcess = max(0, (c - 6000)/2000f)`) を二重に課している。
  2. Hybrid-6b 履歴融合（[`LlmReranker.kt:360-386`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt#L360-L386)）に 10 個以上の経験定数が存在する。
- **詳細設計**:
  1. `LlmReranker` 側の `dictCostPenalty` と Viterbi 側 `Idea R1` の希少語ヒンジの重複を整理し、`LmFirstJointEvaluationTest` (247 件) と `SpanAwareBayesianRerankingEvaluationTest` が 100% パスする単一の変換式に統合する。
  2. Hybrid-6b の短期/長期 2 系統減衰 (`shortDecay` 1h + `longDecay` 336h) と Soft-Clamping を、`HistorySlmRerankEvaluationTest` 上でアブレーション検証し、単一減衰または最小パラメータセットへ縮約する。

### B-5: 英語入力・音声訂正の統計化
- **詳細設計**:
  1. **文境界判定 (`SentenceBoundaryDetector.kt`)**: 7 段階の細かいハードコード確率 (`1.0, 0.98, 0.97, 0.95, 0.25, 0.10, 0.05`) を、`BayesianCaseRanker` の事前確率として意味を持つ 3 クラス（`DEFINITE_BOUNDARY = 0.98f`, `AMBIGUOUS_ABBREV = 0.20f`, `NON_BOUNDARY = 0.05f`）に集約し、`EnglishCapitalizationAnalyzerTest` / `EnglishTruecasingEvaluationTest` で非回帰を確認する。
  2. **音声訂正スコアリング (`VoiceCorrectionScoring.kt`)**: 重み定数を名前付きモデルパラメータ構造体 `VoiceCorrectionWeights` に集約し、`eval/evaluate_voice_correction_learning.py` で交差検証最適化する。

---

## 5. 実行順序と進捗管理

以下の順序で 1 ステップずつ実装・テスト（G1〜G5）を実行します。

1. **Step 1 (Phase 0 一括)**: `A0-1`〜`A0-5`（挙動不変リファクタリング）→ `./gradlew testDebugUnitTest` で 100% パス確認
2. **Step 2 (B-1)**: `MozcPosCatalog.kt` 抽出＋`isVerbStem` 末尾文字ヒューリスティクス削除 → G1/G2 検証
3. **Step 3 (B-2)**: `connectionMatrix == null` 時専用ペナルティ（`+3000`）の削除 → G1/G2 検証
4. **Step 4 (A-1)**: `registerCanonicalVocabulary()` の辞書ロード済み時スキップ＆`source == "canonical"` 特別免除整理 → G1/G2/G3 検証
5. **Step 5 (A-2)**: `NextPhrasePredictor` の非決定的時刻分岐 (`5..10時`) 削除と文頭シード整理 → G1/G4 検証
6. **Step 6 (B-3)**: Viterbi `coordBonus` 等のアブレーション削除と長さボーナス検証 → G1/G2/G3 検証
7. **Step 7 (B-4 / B-5)**: `LlmReranker` 二重コスト整理 & `SentenceBoundaryDetector` 確率クラス集約 → G1/G3/G5 検証
