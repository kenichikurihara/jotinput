# JotInput コードベース内ヒューリスティクス網羅リスト＆削減ロードマップ

## 1. 概要と目的 (Overview & Goal)

本ドキュメントは、JotInput コードベース全体（辞書コンパイル、Viterbi ラティス探索、ゼロクエリ予測、SLM リランキング、英語入力、音声認識後処理）に存在する**決め打ちのスコア・重み・しきい値・品詞 ID・長さ・語彙リストなどのヒューリスティクス**を網羅的に列挙し、今後の**データ駆動化・モデル統合による削減方針**を整理した技術インベントリです。

### 1.1 分類軸
本ドキュメントでは、各ヒューリスティクスを以下の 4 種類に分類して記載します。

- **[Score/Weight]**: 決め打ちのコスト、ペナルティ、ボーナス、混合重み ($\alpha, \lambda, \gamma$ 等)
- **[POS/Lexicon]**: 決め打ちの品詞 ID (Mozc `lid`/`rid`)、品詞範囲、固定語彙リスト、正規表現パターン
- **[Length/Limit]**: 決め打ちの文字数条件、ビーム幅、候補数上限、トークン予算
- **[Timing/Gate]**: 決め打ちのデバウンス時間、ウィンドウ時間、各種しきい値・ゲート条件

### 1.2 削減可否のトリアージ基準
- **Tier A0 (挙動不変リファクタ)**: 未使用定数の削除、同一ロジック・同一定数の重複定義の統一、マジックナンバーの名前付き定数化。出力が変わらないため評価不要（ユニットテストのみ）
- **Tier A (評価付き削除候補)**: データファイル化・コーパス統合・N-gram 委譲が容易なハードコード語彙リストやフォールバック。出力が変わるため評価ゲート（§8.0）必須
- **Tier B (統計・モデル置換候補)**: Viterbi やリランキング内の経験的ボーナス/ペナルティ（コーパス学習・交差検証・SLM 統合で置き換え可能）。**SLM 未導入環境での品質も維持すること**
- **Tier C (維持すべき仕様・安全制約)**: バイナリフォーマット制約（`u16` 上限等）、UI タップ安全性ガード、メモリ・レイテンシ予算、非回帰契約（音声句読点の誤変換防止リスト等）

### 1.3 改訂履歴
- **v1** (2026-11-20): 初版。
- **v2** (2026-11-20): レビュー反映。`isVerbStem` と `matrix == null` フォールバックを Tier A→B に変更、`Idea V1` の二重実装・規模の指摘、VAD 打鍵抑制の実効値修正、音声訂正・英語 Kotlin 側・IME タイミング・キャッシュ上限の抜け漏れ追加、Tier A0 / Phase 0 の新設、共通検証ゲート（SLM-off 評価を含む）の追加。
- **v3** (2026-11-20): Tier A0 / A / B 実装結果を反映（詳細: [`docs/dd_heuristics_reduction_tier_a_and_b.md`](docs/dd_heuristics_reduction_tier_a_and_b.md) §5）。
  - **削除・削減済み**: `MAX_COST_THRESHOLD` 削除、`isVerbStem` 末尾文字フォールバック削除、`matrix == null` ペナルティ (`+3000`) 削除、`NextPhrasePredictor` 非決定的時刻分岐 (`5..10時`) 削除、辞書ロード済み時の単形態素 67 語 `cost = 50` 上書き廃止、`properAfterParticlePen (+1200)` 削除、`DEFAULT_LENGTH_BONUS_PER_CHAR` を `2000 → 1000` に 50% 削減、`LlmReranker` `dictCostPenalty`/`dictCostNats` の二重非線形 `rareExcess^2` 削除、`Hybrid-6b` `thetaGate` の `+ 0.25f * deltaExt` 削除、`SentenceBoundaryDetector` の 7 段階確率を 3 クラスに集約。
  - **統一・集約済み**: `Idea V1` 二重実装を `computeLengthBonus` に統一、`MozcPosCatalog` 新設＋POS ID 集約、空間編集距離の Kotlin/C++ 同期テスト追加、`muSpan` / `AsrManager` / `VoiceInputMethodService` / `AudioRecorder` (`350ms`) / `VoiceCorrectionScoring` 定数集約。

---

## 2. Step 1: 辞書コンパイル・ロード・正規化

### 2.1 オフライン辞書ビルド (`tools/mozc_dict/`)

| 対象ファイル | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| [build_base_dictionary.py](tools/mozc_dict/build_base_dictionary.py#L20) | `[Score/Weight]` | `MAX_COST_THRESHOLD = 5500`（定義のみで現在未使用） | **Tier A0** (挙動不変で削除可) |
| [build_base_dictionary.py](tools/mozc_dict/build_base_dictionary.py#L40-L43) | `[POS/Lexicon]` | 保護記号の決め打ち読みリスト (`"（"`, `"("`, `"！"`, `"。"`, `"、"` 等) および `cost <= 200` かつ非数字の保護条件 | **Tier B** (辞書メタデータ化) |
| [build_base_dictionary.py](tools/mozc_dict/build_base_dictionary.py#L81-L85) | `[POS/Lexicon]` | ノイズトークン除外の決め打ち (`lid == "0" && rid == "0" && surface.isdigit()`、および `reading == "せん" && surface == "1000"`) | **Tier B** (上流コーパスフィルタ化) |
| [build_base_dictionary.py](tools/mozc_dict/build_base_dictionary.py#L94-L123) | `[POS/Lexicon]` `[Score/Weight]` | `essential_conjugations`: 29個の活用語・助動詞（「出」「ません」「行けた」等）に対し、Mozc 品詞 ID (`705, 659, 240, 1906, 1851, 1975, 2430, 701, 694, 142, 348`) と固定コスト (`0, 43, 50, 66, 100` 等) をハードコード | **Tier A** (Mozc OSS 展開ルールへ統合) |
| [build_base_dictionary.py](tools/mozc_dict/build_base_dictionary.py#L148-L153)<br>[build_system_dictionary_bin.py](tools/mozc_dict/build_system_dictionary_bin.py#L118-L123) | `[Score/Weight]` `[POS/Lexicon]` | `reading_correction.tsv` 統合時、コーパスに表記があれば `base_cost + 3000`（上限 `32767`）、無ければ品詞 `lid=1851, rid=1851`（名詞,一般）、`corr_cost = 7500` を決め打ち | **Tier B** (誤読確率の統計推定へ) |

### 2.2 オンデバイス辞書コンパイラ・ダウンロード (`MultiDictionaryManager`, `MmapDictionary`)

| 対象ファイル | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| [MultiDictionaryManager.kt](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/MultiDictionaryManager.kt#L349-L361) | `[POS/Lexicon]` `[Score/Weight]` | `parseStandardMozcLine`: 列欠損時のデフォルト品詞 `lid=1851, rid=1851`、デフォルトコスト `8000`（5列形式）または `6000`（2列形式） | **Tier C** (不正行フォールバック) |
| [MultiDictionaryManager.kt](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/MultiDictionaryManager.kt#L372-L420) | `[POS/Lexicon]` `[Score/Weight]` | `parseCustomSpecialLine`: 特殊辞書の固定コスト・固定品詞 ID<br>- `emoji_data.tsv`: `cost=5500, lid=rid=2632`<br>- `emoticon.tsv`: `cost=4500, lid=rid=2632`<br>- `single_kanji.tsv`: `cost = min(7500 + idx * 120, 14000), lid=rid=1851`<br>- `symbol.tsv`: `cost=4000, lid=rid=2632` | **Tier B** (絵文字/記号の頻度統計化) |
| [MmapDictionaryCompiler.kt](app/src/main/java/com/example/myasr/context/kanakanji/infrastructure/MmapDictionaryCompiler.kt#L113-L115) | `[Length/Limit]` | MYASRDIC v1.0 フォーマット制約: `cost` を `Short` (`-32768..32767`) に丸め、1読みあたりトークン数を `65535` に制限 | **Tier C** (バイナリ仕様) |
| [MmapDictionary.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/MmapDictionary.kt#L104) | `[Length/Limit]` | `getExactTokens`: デフォルト上限 `maxTokens = 128` | **Tier C** (メモリ/レイテンシ保護) |
| [MmapDictionary.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/MmapDictionary.kt#L190-L263) | `[Length/Limit]` `[Score/Weight]` | `getPrefixTokens(maxCount = 40)`: スキャン上限 `maxOf(maxCount * 5, 60)`、1読みあたり最大 `3` トークン取得、ソート時の延長ペナルティ `tok.cost + maxOf(0, r.length - prefixLen) * 800` | **Tier B** (`800` を LM/ユーティリティへ委譲) |
| [KanaKanjiEngine.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L440-L560) | `[POS/Lexicon]` `[Score/Weight]` | `registerCanonicalVocabulary()`: 助詞・助動詞・活用語幹（約75語）を固定 `cost = 50` と決め打ち POS ID で強制登録。括弧類 (`openingBrackets` / `closingBrackets` / `kakkoPairs`) も `50 + idx * 5` でハードコード登録 | **Tier A** (システム辞書統合で段階的削除可能) |
| [KanaKanjiEngine.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L588-L594) | `[Score/Weight]` `[POS/Lexicon]` | `syncUserDictionary`: ユーザー辞書の優先度別固定コスト (`HIGH=0, NORMAL=10, LOW=50`) と固定品詞 `lid=1851, rid=1851` | **Tier B** (品詞選択 UI または自動推定) |
| [KanaKanjiEngine.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt#L1692-L1695) | `[Score/Weight]` | `queryAllMmapExactTokens`: `emoji_symbols` 由来の CJK 単漢字 (`\u4E00..\u9FFF`) に最低コスト `maxOf(tok.cost, 7500)` を課す | **Tier B** (コンパイル時のコスト設定へ統一) |
| [ConnectionMatrix.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/ConnectionMatrix.kt#L25-L27) | `[Score/Weight]` `[Length/Limit]` | `DEFAULT_POS_SIZE = 2672`, 範囲外遷移ペナルティ `INVALID_COST = 30000` | **Tier C** (Mozc 行列仕様) |

---

## 3. Step 2: 仮名漢字変換 (Trie 展開・Viterbi ラティス探索・前方予測)

すべて [KanaKanjiEngine.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) および [JapaneseKanaUtils.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt) に集中しています。

### 3.1 品詞 ID (POS) 範囲・品詞判定の決め打ち

| 箇所 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| `KanaKanjiEngine.kt` (L1659-1682, L2353-2369) | `[POS/Lexicon]` | 品詞カテゴリのハードコード ID 範囲:<br>- `isSuffix`: `2032, 1941, 1951, 1975, 1941..2034`<br>- `isFunctionalWord`: `29..433`<br>- `isAuxiliaryVerb`: `29..267`<br>- `isPredicatePos`: `434..1840, 2196..2590`<br>- `isProperNoun`: `1917..1940`<br>- `isSahenNoun`: `1841, 2035..2150`<br>- `isContentWord`: `1841..2195, 434..1840, 2196..2590, 12..28, 2659..2671` | **Tier B** (`id.def` からのメタデータ自動生成へ置換) |
| `KanaKanjiEngine.kt` (L2358-2363) | `[POS/Lexicon]` | `isVerbStem`: まず `isProperNoun` / `isPredicatePos` の POS 判定を行い、**判定できなかった場合のみ**、表記が2文字以上かつ末尾が `っ/い/め/り/き/ぎ/し/ち` なら動詞語幹とみなす形態素フォールバック。呼び出し元は動的テイル合成 (L2478, `Idea B3`) | **Tier B**（フォールバック除去は B3 出力を変え得るため、POS 自動生成化後に評価付きで削除） |
| `KanaKanjiEngine.kt` (L1736-1800) | `[Length/Limit]` `[POS/Lexicon]` | `selectPosStratifiedSubwords(maxTotal=24 / 20)`: 品詞クラスごとのビーム枠決め打ち（機能語 `2`/POSペア、用言 `2`/POSペア、固有名詞 計`4`/`2`、一般名詞 計`8`/`3`、記号 `2`） | **Tier B** (スコア差ベースの動的枠配分へ) |

### 3.2 Viterbi ラティス探索・スコア計算・2D 多様性

| 箇所 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| `KanaKanjiEngine.kt` (L973-978) | `[Length/Limit]` | Viterbi 起動最小文字数 `normalized.length >= 3`、ビーム幅 `beamWidth = 12`（各位置での prune 上限 `beamWidth * 2 = 24`） | **Tier C / B** (計算量制御として幅のみ維持) |
| `KanaKanjiEngine.kt` (L796-814, L1850-1873) | `[Score/Weight]` `[POS/Lexicon]` | **長さボーナス＆境界ガード (`Idea V1`)**:<br>- `boundaryChars = "とはがをにでものかねよ"`<br>- `conf = ((8500 - cost) / 4000f).coerceIn(0.15f, 1.0f)`<br>- `isRiskyOverSpan`: 固有名詞かつ `cost > 4500` または `cost > 5200` で `effectiveConf = min(conf, 0.25f)`<br>- `baseBonus = (spanLen - 1) * 2000 * effectiveConf`（user/canonical は無条件 `(spanLen - 1) * 2000`）<br>- `predicateBunsetsuBonus = 2000` (用言後の助動詞)<br>⚠️ **規模**: 1文字あたり最大 `2000` は Mozc 単語コスト（典型 3000〜8000）に匹敵し、ランキングへの影響が最も大きい補正。<br>⚠️ **重複と不一致**: 単文節用 `singleViterbiCost`（L796-814）とラティス内（L1857-1894）に**同じロジックが二重実装**されており、前者は先頭助詞境界判定が無く EOS 遷移コストを加算する点で挙動が異なる | **Tier B** (最有力削減対象: CRF/SLM スコアに統合すべきアドホック補正)。二重実装の統一は **Tier A0** |
| `KanaKanjiEngine.kt` (L1874) | `[Score/Weight]` | ユーザー辞書ボーナス `userBonus = 50000` | **Tier B** (SLM 側の `gammaUser` に一本化可能) |
| `KanaKanjiEngine.kt` (L1877-1885) | `[Score/Weight]` `[POS/Lexicon]` | 接続行列不在時のフォールバックペナルティ: 単一カタカナまたは古語「之」(`lid == 385`) に `+3000` | **Tier B**（`connection_data.bin` は APK 同梱だが、ロード失敗時や行列未ロードのユニットテストでは `connectionMatrix == null` が実際に発生する。null 分岐は他にも L811, L1075, L2150, L2236, L2447 等に存在し、「行列必須」への契約変更とテストフィクスチャ移行を伴う） |
| `KanaKanjiEngine.kt` (L810, L1888-1894) | `[Score/Weight]` | **希少語ヒンジペナルティ (`Idea V2`)**:<br>- `rarityDelta`: `maxWordCost > 6200` の超過分の `1/2` を加算<br>- `properAfterParticlePen`: 機能語直後の `cost > 5000` な固有名詞に `+1200` | **Tier B** (辞書コスト分布・SLM NLL へ委譲) |
| `KanaKanjiEngine.kt` (L1897-1910) | `[Score/Weight]` | **並列協調対称ボーナス (`Idea V2`)**: 「カタカナ＋と/や＋カタカナ」に `+1200`、「漢字＋と/や＋漢字」に `+600` | **Tier B** (SLM が文脈で自然に評価可能) |
| `KanaKanjiEngine.kt` (L1971) | `[Score/Weight]` | 未登録1文字ひらがなフォールバック遷移コスト `+18000` | **Tier C** (未知語フォールバック) |
| `KanaKanjiEngine.kt` (L989) | `[Score/Weight]` | 濁音展開あり時の Viterbi 全文節昇格許可マージン `vCost <= bestStep2Cost + 2500` | **Tier B** |
| `KanaKanjiEngine.kt` (L2042-2054) | `[Score/Weight]` `[Length/Limit]` | **2D 多様性ペナルティ (`Idea V3`)**:<br>- 第1文節重複 `count1 * 3000`, 最終内容語重複 `countLast * 2500`<br>- 外れ値パス (`cost - bestFullCost > 3500` または `maxWordCost > 6500`) はカウント除外<br>- 新規ペアかつ高品質 (`<= 3000`, `maxWordCost <= 6000`) なら `min(pen1, pen2)` に緩和 | **Tier B** (SLM 側の 2D-MMR / 効用最大化へ一本化可能) |
| `KanaKanjiEngine.kt` (L2075-2078) | `[Score/Weight]` | **SLM への辞書コスト伝播 (`Idea R1`)**: `avgWordCost + max(0, maxWordCost - 4500)/2 + avgExcessTrans` (`transitionCost - 1500` の超過平均) を `[0, 15000]` にクリップ | **Tier B** (生の対数尤度/Viterbi 総コストの線形射影へ簡素化) |
| `KanaKanjiEngine.kt` (L103, L275, L1114-1116, L2338-2342) | `[Length/Limit]` | クエリ結果キャッシュ `queryCache` 上限 `300` 件、学習履歴上限 `MAX_HISTORY_WORDS = 10_000`、Multi-Horizon 合成結果の上限 `beamWidth + 12`（Full）/ `beamWidth + 18`（Prefix 込み） | **Tier C**（メモリ・レイテンシ予算）。結果上限は Multi-Horizon 統一（Phase 2-5）時に再検討 |

### 3.3 Multi-Horizon Prefix 抽出・曖昧一致・動的テイル合成

| 箇所 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| `KanaKanjiEngine.kt` (L2099-2344) | `[Length/Limit]` `[Score/Weight]` | **Multi-Horizon Prefix (`n >= 4`)**:<br>- `minPrefixLen = if (n >= 6) 3 else 2`<br>- Backward DP 初期値 `100000`, 分岐カウント閾値 `<= 2500`, エントロピー温度 `1500.0f` (`diff <= 6000.0f`)<br>- `branchBonus` 階段関数 (`>=12: 2400`, `>=6: 1800`, `>=3: 1200`, `>=1: 600`)<br>- Pass 1: `cost > 6000` 除外, 順位ペナルティ `acceptedRank * 200`, 上限 `8`<br>- Pass 2: `maxWordCost > 6500` 除外, 複合語ペナルティ `1500 * (words.size - 1)`<br>- インターリーブ枠: Full 2 -> Prefix 4 -> Full 2 -> Prefix 8 | **Tier B** (SLM の部分一致効用計算と重複するロジックが多い) |
| `KanaKanjiEngine.kt` (L890-965, L1374-1418)<br>[JapaneseKanaUtils.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt#L245-L347) | `[Score/Weight]` `[Length/Limit]` | **濁音・小書き仮名・フリック誤タップ展開**:<br>- `maxVoicingDistance = 2`, 濁音ペナルティ `1200 * vDist` (`obsPenalty = 1.2f * vDist`), 探索パス上限 `28`<br>- `FLICK_TYPO_EXPANSION_MAP`: 中心キーフォールバック `2200`, 隣接キー `3200`<br>- フリック訂正候補上限 `take(8)` / ひらがな訂正 `take(3)` (`dictCost = 4000`)<br>- 拗音/小書き仮名後の 1モーラ先読み (`"う"`, `"ん"`) | **Tier B** (タップ座標確率モデルへの置換余地あり。先読みは入力モデルで代替可) |
| `KanaKanjiEngine.kt` (L289-316, L2377-2540) | `[POS/Lexicon]` `[Length/Limit]` `[Score/Weight]` | **動的複文節テイル合成 (`Idea B3`)**:<br>- 対象読み長 `2..8` 文字<br>- `DYNAMIC_SAHEN_TAILS` (8種), `DYNAMIC_VERB_TAILS` (6種), `DYNAMIC_NOUN_TAILS` (6種) のハードコード<br>- 接尾語接続検証の固定 LID (`です: 172`, `中: 2003`, `します: 1825`)<br>- `priority = 3.5f` (定型) / `3.3f` (動的)、各語幹 `2` 件、合計 `take(8)` | **Tier A / B** (SLM 次フレーズ生成またはコーパス N-gram へ置換) |
| `KanaKanjiEngine.kt` (L1124-1211) | `[Length/Limit]` `[Score/Weight]` | **短読み予測インターリーブ (`normalized.length in 2..4`)**: `priority >= 3.0f` 判定、スコア `dictCost + obsPenalty * 1500 + extLen * 600`、Slot 1..4 固定交互配置 | **Tier B** (SLM 側 EIS 効用・多様性ロジックと重複) |

---

## 4. Step 3: ゼロクエリ（確定直後）次フレーズ予測

| 対象ファイル | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| [NextPhrasePredictor.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt#L56-L449) | `[POS/Lexicon]` | - `CANONICAL_STEM_READINGS` (約55語) & `CANONICAL_COLLOCATIONS` (約55語×2〜8フレーズの固定用例辞書)<br>- `COMMON_PARTICLES` (13助詞)<br>- `SENTENCE_ENDING_PREDICATES` (34語尾), `QUESTION_ENDINGS` (6語尾), `PERIOD_SUPPRESSING_SUFFIXES` | **Tier A / B** (固定用例は軽量 N-gram / SLM 生成へ段階的移行) |
| [NextPhrasePredictor.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt#L741-L752) | `[Timing/Gate]` `[POS/Lexicon]` | 文頭挨拶ヒューリスティクス: `5..10時` に「おはようございます」、常時「お疲れ様です」「お世話になっております」等 6 種を固定挿入 | **Tier A** (履歴学習 + SLM に委譲可能) |
| [NextPhrasePredictor.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt#L589-L597, L785) | `[Score/Weight]` | 統一コスト式 `(3500 + connCost).coerceIn(0, 15000)`、行列欠損時フォールバック `5000` / 助詞ソート時 `2500` | **Tier B** |
| [NextPhrasePredictor.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt#L43-L45) | `[Length/Limit]` | RAM 5MB 上限用キャッシュキャップ: `MAX_PREFIX_KEYS = 4000`, `MAX_CONTINUATIONS_PER_KEY = 16`, `MAX_TOTAL_BIGRAM_PAIRS = 32000` | **Tier C** (メモリ保護制約) |
| [CandidateRerankJudgement.kt](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt#L480-L516) | `[Length/Limit]` | `ZeroQueryHorizonPolicy`: Top-1 が複合チャンク判定 (`surface.length >= 4 || reading.length >= 5`) のとき Slot 2 に同語幹短候補 (`length >= 2`) または助詞を強制配置 | **Tier B** (EIS 効用・Submodular 多様性に統合可能) |

---

## 5. Step 4: SLM リランキング (Kotlin 側プール・効用計算 + C++ `llama.cpp`)

### 5.1 コンテキストウィンドウ切り詰め ([ContextWindowTrimmer.kt](app/src/main/java/com/example/myasr/context/reranking/domain/ContextWindowTrimmer.kt))

| 箇所 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| L25-28, L58-71 | `[Length/Limit]` `[Score/Weight]` | トークン予算: `DEFAULT_TARGET_TOKENS = 24`, `MIN = 10`, `MAX = 36`, `STICKY_HIGH_WATER_MARK = 56`。BPE トークン数推定密度: ASCII `4.2 chars/tok`, CJK `1.3 chars/tok`, フォールバック `2.5` | **Tier B / C** (実トークナイザ長取得または現状維持) |
| L33-41, L165-207 | `[POS/Lexicon]` | 3階層境界ルール、英語略語リスト (`mr.`, `e.g.` 等19種)、接語リスト (`'ll`, `'ve` 等7種) | **Tier C** (安全な境界切り詰めロジック) |

### 5.2 候補プール構築・Bayesian スコア・EIS 効用・MMR 多様性 ([LlmReranker.kt](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt))

| 箇所 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| L1063-1100, L1233-1270 | `[Length/Limit]` | **`topPool` 構築 (上限 16 候補)**:<br>- 履歴 `>= 2`: 最大 `4`<br>- Full 候補: 最大 `8`<br>- `fuzzyShort` (`obsPenalty > 0 && dictCost <= 6000`): 保護枠 `2`<br>- Single Prefix `4` + Diversified PhraseChunk `4` (計 `8` 枠) | **Tier B** (Stage 1 logit スコアで全候補から直接絞る設計にすれば簡素化可能) |
| L76-85<br>[KeyboardHeightManager.kt](app/src/main/java/com/example/myasr/context/ime/presentation/KeyboardHeightManager.kt#L21-L25) | `[Score/Weight]` | **主要ハイパーパラメータ & `ScoringProfile`**:<br>- `DEFAULT_SLM_WEIGHT_ALPHA = 0.20f`, `LAMBDA_OBS = 0.50f`, `GAMMA_USER = 3.00f`<br>- `JA_FLICK`: `(keyCostSec=0.40, riskWeight=0.25, extGainWeight=1.00, slot1MarginSec=0.15)`<br>- `JA_ZERO_QUERY`: `(0.40, 0.30, 1.00, 0.00)`<br>- `EN_QWERTY`: `(0.30, 0.45, 0.85, 0.25)` | **Tier B** (少数の最適化パラメータへ集約) |
| L222-224, L256, L324-356 | `[Score/Weight]` | **スパン長・キメラ・分岐ペナルティ**:<br>- `muSpan = 2.20f` (JP) / `1.40f` (EN)（**L222 と L1295 に二重定義**）, `kappaCommit = 1.50f`, `lambdaExt = 1.20f`<br>- キメラ判定: `thetaCoherent = 4.0f`, ペナルティ `+10.0f`<br>- 分岐ボーナス: `min(1.20f, 0.55f * ln(max(1, branching)))`<br>- Zero-query 長さ正規化: `rawNll / tokens^0.85f` | **Tier B**（`muSpan` の重複統一は **Tier A0**） |
| L288-295, L390-396 | `[Timing/Gate]` `[Score/Weight]` | **Dead-End Reading Gate (`Idea 7`)**: `n in 2..5` かつ `dictCost <= 5500` の自然語が無い場合、`obsDiscount` に `0.35f` 倍を適用。`obsDiscount = 0.20f + 0.80f * ((deltaNll - 1.5f) / 3.0f)` | **Tier B** |
| L360-386 | `[Score/Weight]` `[Timing/Gate]` | **Hybrid-6b 履歴 × SLM 融合**:<br>- Coherence Gate: `thetaGate = 4.6f + 0.25f * deltaExt`, 温度 `0.6f`<br>- 減衰時定数: 短期 `1.0h`, 長期 `336.0h` (14日)<br>- Soft-Clamping `eta` 上限: Bigram `0.90f`, Unigram `0.70f` (`(count-1)/5`)<br>- ブースト係数: `bigramBoost = 6.0f`, `shortTerm = 2.2f`, `longTerm = 1.8f * min(ln(1+c), 3.0f)`, `c==1` 減衰 `0.30f` | **Tier B** (パラメータ数が非常に多く、簡素な対数線形融合へ整理可能) |
| L398-404, L407-413 | `[Score/Weight]` | **辞書コスト融合・ヒステリシス**:<br>- `dictCostPenalty = (c / 3000f) + 0.45f * rareExcess^2` (`rareExcess = max(0, (c - 6000)/2000f)`)<br>- `rankCost = min(4, stratumRank)`<br>- `hysteresisDelta = -1.20f` (Full) / `-0.35f` (Fast) | **Tier B** (Viterbi 側 `dictCost` 変換と二重に非線形補正している) |
| L417-491 | `[Score/Weight]` | **Softmax 事後確率 & EIS 効用**:<br>- 温度 `2.0f` (`logPostScores / 2.0f`)<br>- `gExt` 長さ減衰: `deltaExt > 4` で `4 + sqrt(deltaExt - 4)`、希少語減衰 (`dictCost > 5000`)<br>- 誤確定損失 `lErr = 1.50f + 0.20f * surface.length + keyCostSec * m`<br>- スロット別リスク減衰 `riskWeight * 0.65f^min(rankPos, 4)` | **Tier B** |
| L512-691 | `[Score/Weight]` `[Length/Limit]` | **2D-MMR & Submodular 多様性**:<br>- `divPenalty = (1 - min(0.5, alpha)) * (count1 * 2.00f + countLast * 1.60f)`<br>- Det-Sub ペナルティ: `suffixBranchingFactor < 1.80f` で `+2.50f`<br>- `computeIntentOverlapKappa`: 同一語幹・同ホライズン `0.80f`, 異ホライズン `0.35f`, 同最終内容語 `0.50f`<br>- インターリーブ枠: `isHighBranch` (`>= 1.80f`) で Full `2`/Partial `4`、それ以外 `3`/`3` | **Tier B** |
| L55, L1002-1027 | `[POS/Lexicon]` | 潜在助詞補完: `CASE_PARTICLES = ["に", "を", "が"]` を用言候補へ挿入して NLL 最小値を採用 | **Tier B** (助詞バリエーションの拡張または SLM 直接評価) |

### 5.3 UI 実行判定・デバウンス ([CandidateRerankJudgement.kt](app/src/main/java/com/example/myasr/context/reranking/domain/CandidateRerankJudgement.kt))

| 定数 / 規則 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| L21-30 | `[Timing/Gate]` | `RAPID_TYPING_IKI_THRESHOLD_MS = 140L`, `RAPID_TYPING_MAX_READING_LEN = 2`, `RAPID_TYPING_DEBOUNCE_MS = 40L`, `NORMAL_DEBOUNCE_MS = 4L`, `TAP_SAFE_WINDOW_MS = 100L`, `MICRO_WAIT_HOLD_MS = 50L`, `RAPID_TYPING_HOLD_MS = 85L`, `DEFAULT_VISIBLE_SLOT_FALLBACK = 3` | **Tier C** (UX レイテンシ・タップ安全性ガード) |
| L218-226 | `[Length/Limit]` `[Score/Weight]` | `TAP_SAFE_R4`: `reading.length in 2..4` かつ (`priority >= 3.5f || historyCount >= 2 || history_bigram || context_collocation`) のみ100ms超過後の Top-1 昇格を許可 | **Tier B** |

### 5.4 ネイティブ C++ エンジン (`llm_reranker.h` / `llm_reranker.cc`)

| 箇所 | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| [llm_reranker.h](app/src/main/cpp/llm_reranker.h#L21-L30) | `[Length/Limit]` `[Score/Weight]` | `n_ctx = 256`, `n_seq_max = 16`, `n_threads = 4`, `n_threads_batch = 6`, `decisive_top1_gap_nats = 3.5f`, `stage2_prune_margin_nats = 4.5f`, `max_tree_batch_tokens = 32` | **Tier C** (メモリ・推論速度予算) |
| [llm_reranker.cc](app/src/main/cpp/llm_reranker.cc#L819) | `[Score/Weight]` | FastScore 未完了トークン近似コスト: `t0_nll + (n_cand_tokens - 1) * 0.8f` | **Tier B** |
| [llm_reranker.cc](app/src/main/cpp/llm_reranker.cc#L951-L976) | `[Score/Weight]` `[Length/Limit]` | Stage 2 枝刈り推定長: `bounded_len = min(4, tokens.size())`, `est_best_total = lb + 2.2f * (bounded_len - 1)`。Prefix-Tree 展開上限 `max_toks = min(8, tokens.size())` | **Tier B / C** |
| [llm_reranker.cc](app/src/main/cpp/llm_reranker.cc#L122-L134, L1164-L1177) | `[POS/Lexicon]` `[Length/Limit]` | `PredictNextPhrases`: `IsBunsetsuBoundary` 固定語尾リスト (18種)、初期トークン上位 `16`、ビーム上限 `min(max_candidates, 6)` | **Tier B** (EOS/句読点のみで停止するかモデル学習へ) |
| [llm_reranker.cc](app/src/main/cpp/llm_reranker.cc#L272) | `[Timing/Gate]` | Big.LITTLE 判定しきい値 `max_freq > min_freq * 1.15` | **Tier C** (ハードウェア最適化) |

---

## 6. Step 5: 英語入力 (Swipe / Spelling / Truecasing)

| 対象ファイル | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| [futo_swipe_engine.cc](app/src/main/cpp/swipe/futo_swipe_engine.cc#L138-L140, L542-L546) | `[Score/Weight]` `[Timing/Gate]` | **Swipe デコード**:<br>- 静止クランプ `kMaxStationaryDtMs = 65.0ms`, `kStationaryDistThr = 0.03`<br>- CTC Prefix Search: `gamma_prune=0.1902`, `beta_prune=1.2727`, `gamma=0.1126/0.5949`, `beta=2.2138/0.7271`, `alpha=0.6387`<br>- ユーザー辞書サブツリーボーナス `0.75f` | **Tier B / C** (一部は最適化済みパラメータ) |
| [futo_swipe_engine.cc](app/src/main/cpp/swipe/futo_swipe_engine.cc#L621-L685, L843) | `[Score/Weight]` | **Swipe リスコア補正**:<br>- 始点/終点キー距離ペナルティ `-2.0 * d0_sq - 3.5 * d1_sq`<br>- アポストロフィ共有ノード `-2.5f`, Zipf 重み `0.004f` / `0.0015f`, Unigram バイアス `0.25f`<br>- ContextLM 保護: `vocab_rank < 6000`, `best_common_tot > -0.55f && tot_gap > 1.7f && cs < 1.0f` で `-0.80f * (tot_gap - 1.7f)`<br>- ユーザー語ブースト (`5.5 / 3.8 / 2.0`, ゲート `max_base - 6.5f`), hashed_bias 下限 `-4.2f` | **Tier B** (SLM リランキングと統合余地あり) |
| [english_spell_engine.cc](app/src/main/cpp/spelling/english_spell_engine.cc#L302-L321, L431-L440, L645-L668) | `[Score/Weight]` `[Length/Limit]` | **SymSpell & 空間編集距離**:<br>- ユーザー語擬似頻度 (`50M / 30M / 15M`)、Prefix 登録長 `2..10`・リスト上限 `12/14`<br>- `EditAndSpatialCost`: 削除/挿入 `1.5 / 1.2`, 置換 `0.8 + 0.3 * min(kd, 2.0)`, 転置 `0.6`<br>- Prefix スコア `log_freq - 0.45 * len_diff + 2.0 * cov_ratio` | **Tier B** |
| [BayesianCaseRanker.kt](app/src/main/java/com/example/myasr/context/english/domain/BayesianCaseRanker.kt#L41-L43, L91-L147)<br>[EnglishCaseModel.kt](app/src/main/java/com/example/myasr/context/english/domain/EnglishCaseModel.kt#L29) | `[Score/Weight]` | `LAMBDA_OBS = 5.0f`, `LAMBDA_FREQ = 0.15f`, `MIN_VARIANT_POSTERIOR = 0.04f`, `OOV_ACRO_THRESHOLD_LLR = 0.28f`、各種フォールバック事前確率 (`0.95, 0.85, 0.70, 0.15, 0.08, 0.05`) | **Tier B** |
| [VoiceInputMethodService.kt](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1993-L1998) | `[Length/Limit]` | 英語候補の SLM リランキング入力上限 `take(8)`、表示上限 `take(5)` | **Tier C** |
| [EnglishDictionaryUtils.kt](app/src/main/java/com/example/myasr/context/english/domain/EnglishDictionaryUtils.kt#L93-L186) | `[Score/Weight]` `[Length/Limit]` | **Kotlin 側の英語辞書照合**:<br>- 空間編集距離 削除/挿入 `1.5 / 1.2`, 置換 `0.8 + 0.3 * min(kd, 2.0)`, 転置 `0.6` — **C++ `english_spell_engine.cc` の `EditAndSpatialCost` と同一定数の二重定義**<br>- ユーザー語の優先度別擬似スコア `HIGH=22.0 / NORMAL=19.5 / LOW=17.5`<br>- Prefix コスト `0.4 + 0.1 * lenDiff`、スコア係数 `2.8`<br>- 受理閾値 `normCost <= 0.38 && cost <= 3.6`、ファジー Prefix `<= 0.30 && <= 1.2`（加算 `+0.8`） | **Tier A0**（二重定義の統一）/ **Tier B**（擬似スコア・閾値の統計化） |
| [SentenceBoundaryDetector.kt](app/src/main/java/com/example/myasr/context/english/domain/SentenceBoundaryDetector.kt#L44-L95) | `[Score/Weight]` | 文頭大文字化のための文境界確率の固定値: 改行 `1.0`, `!?。` `0.98`, 既定 `0.97`, `0.95`, `0.25`, `0.10`, `0.05`（略語・番号付きリスト等の分岐別） | **Tier B** (英語コーパスからの最尤推定) |

---

## 7. Step 6: 音声認識・VAD・句読点・後処理

音声認識レイヤーのアルゴリズム詳細は [voice_input_algorithms_and_heuristics.md](docs/voice_input_algorithms_and_heuristics.md) を参照してください。主な決め打ちは以下の通りです。

| 対象ファイル | 種別 | 内容・決め打ちの値 | 削減難易度 / Tier |
| :--- | :--- | :--- | :--- |
| [StreamingVadProcessor.kt](app/src/main/java/com/example/myasr/context/speech/domain/StreamingVadProcessor.kt#L20-L87) | `[Timing/Gate]` | ノイズフロア窓 (24チャンク / ~1.5s)、マイクロポーズフラッシュ `200ms`、無音確定 `450ms`、連続発話ペーシング `<= 2400ms`。打鍵抑制ガード `notifyUserTyping` の既定値は `300ms` だが、**`VoiceInputMethodService` の全呼び出し（約13箇所）が `350L`（1箇所 `500L`）を明示指定しており、実効値は 350/500ms**（既定値は事実上未使用） | **Tier C**（レイテンシ契約）。打鍵抑制値の単一定数化は **Tier A0** |
| [JapanesePunctuationEngine.kt](app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt#L56-L200) | `[POS/Lexicon]` | `PRESERVED_WORDS`（天気・天然・満点・まるで等の誤変換防止リスト）、`SAFE_PARTICLE_ENDINGS`、音声句読点コマンド置換ルール | **Tier C** (トップライン品質契約で保護必須) |
| [JapaneseNumeralUtils.kt](app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseNumeralUtils.kt#L19-L54) | `[POS/Lexicon]` `[Length/Limit]` | `PRESERVED_IDIOMS` (~60語)、`UNIT_WORDS` / `KANA_UNITS` (~50単位)、`parseKanaToNumber` の最大 `9999` 制限 | **Tier C** (熟語誤変換ゼロ保証) |
| [AsrManager.kt](app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt#L132-L168) | `[Score/Weight]` `[Length/Limit]` | ビーム幅 `maxActivePaths = 8`, ホットワードブースト `hotwordsScore = 3.5f`（L132-134 と L166-168 の 2 箇所に重複記述） | **Tier C** (評価済み最適値)。重複記述の定数化は **Tier A0** |
| [VoiceCorrectionScoring.kt](app/src/main/java/com/example/myasr/context/speech/domain/VoiceCorrectionScoring.kt#L88-L181) | `[Score/Weight]` `[Timing/Gate]` | 音声訂正学習のスコア: 頻度項 `1.5 * ln(1 + net) * recencyDecay`、文脈一致ボーナス `0.8 * tokenMatches`、文脈内に訂正後表記が既出なら `+2.0`、頻度ブースト上限 `3.0`、SLM コヒーレンスゲート `gate >= 0.35`、SLM 非使用時 `ctxScore >= 2.2` | **Tier B** (訂正ログからのロジスティック回帰等で推定) |
| [VoiceCorrectionDetector.kt](app/src/main/java/com/example/myasr/context/speech/domain/VoiceCorrectionDetector.kt#L26-L27) | `[Timing/Gate]` `[Length/Limit]` `[Score/Weight]` | 訂正検出窓 `EDIT_WINDOW_MS = 15s`、最大スパン `MAX_SPAN_LENGTH = 36`、仮名距離（小書き/濁音同一視 `0.3`、共有文字 `0.2`、活用・漢字化修正 `0.05`） | **Tier B / C** (窓・スパンは C、距離重みは B) |
| [ProsodyPitchEstimator.kt](app/src/main/java/com/example/myasr/context/speech/domain/ProsodyPitchEstimator.kt#L21-L89) | `[Timing/Gate]` `[Score/Weight]` | 文末ピッチ推定: 最小音声長 `200ms`、フレーム長 `50ms`、F0 探索範囲 `75..420Hz`、比較区間 `末尾180ms / 70ms`、有声判定 `自己相関 >= 0.45` | **Tier C** (音響的に標準的な値) |
| [ContextAwareProcessor.kt](app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt#L275) | `[POS/Lexicon]` | 敬称の固定リスト（`さん/様/くん/君/氏/ちゃん`） | **Tier B** (Mozc 接尾辞 POS 判定へ置換可能) |
| [VoiceInputMethodService.kt](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) | `[Timing/Gate]` `[Length/Limit]` | SLM リランキングのタイムアウト `withTimeout(2500L)`（L1267, L1374, L1762）、ASR 推論タイムアウト `3000L`（L1556, L2305）、UI 遅延 `delay(120L / 150L / 250L)`、訂正置換時の後続テキスト長上限 `tail.length <= 32`（L697）、英語候補表示の最小語長 `2` | **Tier C**（レイテンシ予算）。定数の集約は **Tier A0** |


---

## 8. ヒューリスティクス削減ロードマップ (Reduction Action Plan)

コードベース全体の複雑性を下げ、コーパスおよび言語モデル (Mozc CRF + Qwen3.5 SLM) 主導のクリーンな設計へ移行するため、以下の 4 フェーズでの削減を推奨します。

> [!IMPORTANT]
> **SLM 非存在時の品質保証が前提条件**: 本プロジェクトは Model & Lexicon Decoupling Contract により **SLM はオンデマンドダウンロード（未導入状態があり得る）** です。したがって「SLM が既に評価しているから Viterbi 側の補正は不要」という理由だけで削除してはなりません。Phase 1 以降のすべての削除は **SLM-off（Viterbi 単体）と SLM-on の両条件で** 非回帰を確認します。

### 8.0 共通検証ゲート (全フェーズ必須)

| ゲート | コマンド / 評価セット | 合格基準 |
| :--- | :--- | :--- |
| G1: ユニット / アーキテクチャ | `./gradlew testDebugUnitTest`（`JapanesePunctuationEvaluationTest`, `DddArchitectureKonsistTest`, `AlloyVerificationTest` を含む） | 100% パス |
| G2: 仮名漢字 SLM-off | `myasr-eval` スキルのフリック変換評価（100件ヘッドルームセット）を SLM 未ロードで実行 | Top-1 / Top-3 が削除前ベースライン比 **-0.5pt 以内**（暫定値。初回ベースライン計測時に評価セットの分散から確定） |
| G3: 仮名漢字 SLM-on | 同上を SLM ロード状態で実行 + EIS（期待打鍵削減）評価 | Top-1 / EIS が **非劣化**（統計的に有意な低下なし） |
| G4: ゼロクエリ予測 | `myasr-eval` の次フレーズ予測評価 | Hit@3 が非劣化 |
| G5: 英語入力 | Swipe / SymSpell / Truecasing 評価 | Top-1 精度が非劣化 |
| G6: 実機 | `./deploy.sh` による ARCVM + Pixel のデュアルターゲット確認 | 両端末で `AsrManager` ロード・変換動作正常 |

各削除 PR では「削除対象」「影響予想 (大/中/小)」「通過したゲートと数値差分」を PR 説明に記載すること。

### Phase 0: 挙動不変のリファクタリング (Tier A0 — 評価不要・即時実施可)
挙動を一切変えずに重複・死蔵を解消し、以降のフェーズの差分を読みやすくする。ゲート: **G1 のみ**（出力バイト一致をユニットテストで確認）。
1. `build_base_dictionary.py` の未使用 `MAX_COST_THRESHOLD` 削除。
2. **Idea V1 の重複定義統一**: `singleViterbiCost`（L796-814）とラティス内（L1857-1894）の長さボーナス・希少語ペナルティを単一の純粋関数に抽出（現状の差異＝先頭助詞判定と EOS コストは引数で明示し、挙動を保存）。
3. **空間編集距離の二重定義統一**: `EnglishDictionaryUtils.kt` と `english_spell_engine.cc` の `1.5/1.2/0.8+0.3·kd/0.6` を単一定義（共有定数表または JNI 呼び出し）へ。
4. **`muSpan` 二重定義統一**: `LlmReranker.kt` L222 / L1295 を共通定数化。
5. **打鍵抑制時間の単一化**: `VoiceInputMethodService` の `notifyUserTyping(350L)` ×約13箇所と `StreamingVadProcessor` 既定値 `300L` の乖離を、名前付き定数 1 つに統合（実効値 350ms を保存）。
6. Tier C に分類した上限・タイムアウト類（`queryCache=300`, SLM `withTimeout(2500L)` 等）を名前付き定数として集約。

### Phase 1: ハードコード語彙・フォールバックの評価付き削除 (Tier A)
ゲート: **G1 + G2 + G3 (+ G4)**。
1. **canonical 語彙のコーパス一本化**: `KanaKanjiEngine.registerCanonicalVocabulary()` と `build_base_dictionary.py` の `essential_conjugations` を、Mozc OSS コーパス生成パイプラインに一本化（Anti-Anecdotal Tuning Policy に整合）。削除後に同語彙がシステム辞書側でコーパス由来コストを持つことを辞書ビルドテストで保証する。
2. **固定用例・挨拶の委譲**: `NextPhrasePredictor.CANONICAL_COLLOCATIONS` および時間帯挨拶ヒューリスティクスを、ユーザー履歴 + 軽量 N-gram へ委譲（SLM-off 時も G4 を満たすこと）。

### Phase 2: 変換・リランキング数理モデルの簡素化 (Tier B)
ゲート: **G1〜G5 全て**。1 項目ずつ独立した PR で A/B 評価する。
1. **POS ID ハードコードの自動生成化**: `KanaKanjiEngine.kt` に散在する `1841..2195`, `434..1840` 等の POS ID マジックナンバー群を、Mozc `id.def` からビルド時に生成する列挙型/ビットマスクへ置換。完了後に `isVerbStem` の末尾文字フォールバックの要否を評価（POS 判定のみで B3 テイル合成の出力が変わらないことを確認してから削除）。
2. **`matrix == null` フォールバックの整理**: 「接続行列ロード失敗時はエンジン非活性」という前提へ契約を変更し、行列未ロードのユニットテストをテスト用行列フィクスチャへ移行したうえで、単一カタカナ・古語「之」`+3000` 分岐および null 分岐群（L811, L1075, L1877, L2150, L2236, L2447 等）を削除。
3. **Viterbi 内のアドホック補正の削減（最優先: 長さボーナス）**:
   - `Idea V1` の `(spanLen-1) * 2000` は Mozc 単語コスト（典型 3000〜8000）に匹敵する規模で、全体のランキングを最も強く歪めている。まず**係数のスイープ（0 / 500 / 1000 / 2000）を G2 で計測**し、Mozc コストのみで成立する範囲を特定する。
   - `Idea V2`（希少語ヒンジ・助詞後固有名詞・並列協調ボーナス）、`Idea V3`（Viterbi 側 2D 多様性ペナルティ）は SLM-on では後段と重複するが、**SLM-off での G2 劣化が許容範囲内の場合のみ削除**する。劣化する場合は「SLM-off 専用フォールバック」として明示的にフラグ分離する。
4. **SLM リランキングのパラメータ統合**: `LlmReranker.scoreAndRankPool` の 15 個以上の経験定数（特に Hybrid-6b の `thetaGate`, `shortTerm`, `longTerm`, `c1Damp`, `eta` 上限、および Viterbi 側 `Idea R1` と重なる `dictCostPenalty` の二重非線形ペナルティ）を、交差検証で同定可能な最小限のパラメータセット（例: 3〜4 個）へ縮約する。
5. **Multi-Horizon Prefix / 短読みインターリーブの統一**: `KanaKanjiEngine` 側の `branchBonus`・`interleavePredictiveCandidates` と `LlmReranker` 側の EIS 効用・Strata 配置で二重になっている順位制御を、SLM 未ロード時のフォールバックのみに限定する。
6. **英語側の統計化**: `EnglishDictionaryUtils` の優先度擬似スコア（`22.0/19.5/17.5`）・閾値群、`SentenceBoundaryDetector` の固定確率を、英語評価コーパスからの最尤推定値へ置換。

### Phase 3: 維持すべき不変条件 (Tier C)
- バイナリフォーマット制約 (`MYASRDIC v1.0` の `u16` 上限・`ConnectionMatrix` サイズ)
- 音声句読点・漢数字の非回帰保護リスト (`PRESERVED_WORDS`, `PRESERVED_IDIOMS`) — Top-Line Quality Contract により削減対象外
- UI 安全ガード (`TAP_SAFE_WINDOW_MS`, `RAPID_TYPING_DEBOUNCE_MS`, VAD レイテンシ予算 `≤ 200ms` / `≤ 2400ms`)
- メモリ・レイテンシ予算（キャッシュ上限、`n_ctx`, SLM / ASR タイムアウト）
