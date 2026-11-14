# Bounded Context Canvases

DDD Crew の **Bounded Context Canvas** 形式に基づき、JotInput の全 8 コンテキストの責務・ユビキタス言語・ビジネスルール・境界契約を定義する。

---

## BC-1: Japanese Kana-Kanji Conversion（日本語かな漢字変換）

| 項目 | 定義 |
|---|---|
| **Name** | Japanese Kana-Kanji Conversion (`context.kanakanji`) |
| **Strategic Classification** | **Core Domain**（日本語 IME 品質の中核） |
| **Domain Roles** | Execution / Transformation Engine |
| **Purpose** | ひらがな読み列（およびフリック誤タップ・濁音曖昧性を含む入力）から、Mozc 2D接続コスト行列・mmap 辞書・ユーザー履歴を統合した Viterbi N-best 探索により自然で網羅的な日本語変換候補を生成する。 |

### Ubiquitous Language（ユビキタス言語）
- **Reading (読み)**: 正規化されたひらがな文字列（例: `きょうはいいてんき`）。
- **Lattice / Viterbi Path (ラティス / ビタビ経路)**: 辞書トークンと接続コスト行列 `ConnectionMatrix`（`lid`, `rid` 2次元テーブル）から構築される最小コスト形態素列。
- **Span-Aware Candidate (スパン認識候補)**: 入力読みの全区間（Full-Span）または接頭辞区間（Partial-Span）に対応する変換候補。
- **2D-MMR Interleaving**: 関連度と多様性（表層・読み長多様性）を両立させる候補配列アルゴリズム。
- **Next Phrase Prediction (次フレーズ予測)**: 確定済み文脈直後に続く後続文節の予測。

### Business Decisions & Domain Rules（ビジネスルール・不変条件）
1. **Anti-Anecdotal Tuning 原則**: 特定単語のコストを手動ハックせず、Mozc コーパス由来 CRF コストと接続行列（`connection_data.bin`）を正とする。
2. **All-Hiragana 除外ポリシー**: 変換候補ランキング・表示において、純ひらがな候補の過剰上位占有を抑制する（Finding 23）。
3. **活用語尾保存・潜在動詞語幹**: 活用形（「ことなります」「むかってる」等）の語幹分解と接続制約を厳格に保つ（ADR-022, ADR-023）。
4. **カスタム辞書絶縁**: ユーザー登録語彙は `VocabularySyncAdapter` 経由で優先スロットを確保しつつ、システム辞書の接続性を破壊しない。

### Inbound / Outbound Communication
- **Inbound**: `convert(reading, context)`, `predictNextPhrase(context)`, `recordSelection(surface, reading)`, `UserDictionaryChangedEvent`
- **Outbound**: `List<ConversionCandidate>`（`shared.domain`）, `UserHistory` 永続化ファイル

### Tactical Building Blocks
- **Domain Services**: `KanaKanjiEngine`, `NextPhrasePredictor`, `JapaneseKanaUtils`, `JapaneseNumeralUtils`
- **Value Objects / Entities**: `ConnectionMatrix`, `MmapDictionary`
- **Application / ACL**: `UserHistorySyncManager`, `KanaKanjiVocabularySyncAdapter`
- **Infrastructure**: `MmapDictionaryCompiler`

---

## BC-2: Neural Language Modeling & Reranking（オンデバイス言語モデル・リランキング）

| 項目 | 定義 |
|---|---|
| **Name** | Neural Language Modeling & Reranking (`context.reranking`) |
| **Strategic Classification** | **Core Domain**（文脈理解と意味的曖昧性解消の差別化要素） |
| **Domain Roles** | Analysis / Scoring Service |
| **Purpose** | オンデバイス SLM（llama.cpp / Qwen2.5-0.5B）を用い、カーソル前文脈に条件付けた対数尤度（Causal Token Conditional Log-Likelihood）で候補を再順位付けし、同音異義語・英単語スペルの精度を最大化する。 |

### Ubiquitous Language（ユビキタス言語）
- **Causal Conditional NLL**: `P(candidate | context)` に基づくトークン条件付き負の対数尤度。
- **KV Cache Prefix Rollback**: `llama_memory_seq_rm()` によるプレフィックス KV キャッシュのサブミリ秒再利用・巻き戻し。
- **Zero-Jitter Scoring**: ユーザー打鍵中に安全に中断（`nativeRequestAbortScoring`）し、確定候補欠落や Assertion 失敗を起こさない非同期スコアリング。
- **Rerank Engine Type**: `NONE`, `SLM_ONLY`, `LM_FIRST_JOINT` 等のリランキング戦略。

### Business Decisions & Domain Rules
1. **3秒タイムアウト予算 & Fail-Safe**: 推論がタイムアウト・中断された場合は、必ず元の Viterbi / Swipe 上位候補へフォールバックし、候補が空になることを防ぐ（ADR-013, Finding 28）。
2. **Context Window Trimming**: SLM のコンテキスト長上限を超えないよう `ContextWindowTrimmer` で文境界単位にトリミングする。
3. **Zero-Crash Native Guard**: `llama_memory_seq_cp` 等の部分範囲アサーション回避ルールを遵守する（Finding 42）。

### Inbound / Outbound Communication
- **Inbound**: `CandidateRerankingService.rerank(context, candidates, engineType, alpha)`, `warmContext(context)`, `abortScoring()`
- **Outbound**: `RerankResult`（スコア・確定フラグ・レイテンシ）, `RerankCompletedEvent`

### Tactical Building Blocks
- **Domain Service / OHS / Judgement**: `CandidateRerankingService`, `CandidateRerankJudgement`, `RerankEngineType`, `ContextWindowTrimmer`
- **Infrastructure (JNI)**: `LlmReranker` (`libmyasr_llm.so`), `PowerBenchmarkRunner`

---

## BC-3: Speech Recognition & Voice Input（音声認識・ストリーミング音声入力）

| 項目 | 定義 |
|---|---|
| **Name** | Speech Recognition & Voice Input (`context.speech`) |
| **Strategic Classification** | **Supporting Domain**（オンデバイス音声入力の基盤） |
| **Domain Roles** | Execution / Signal Processing Engine |
| **Purpose** | 16kHz PCM 音声の収録、VAD による無音区間・発話境界検出、ReazonSpeech (Sherpa-ONNX) デコード、音声句読点（「てん」「まる」）の文脈判別付与、コンテキスト認識後処理を行う。 |

### Ubiquitous Language（ユビキタス言語）
- **Audio Frame / Waveform**: 16kHz Mono 16-bit PCM から正規化された `[-1.0f, 1.0f]` Float 配列。
- **Dual-Cadence VAD**: 200ms 以下のマイクロポーズでの高速フラッシュと、2400ms 連続発話での文境界コミット（ADR-012）。
- **Spoken Punctuation Protection**: 「天気」「天然」「満点」「まるで」「ですから」等の一般語彙に対する句読点誤変換防止ルール。
- **In-Beam Hotwords (`cjkchar`)**: Sherpa-ONNX Modified Beam Search 用のスペース区切りホットワード列。

### Business Decisions & Domain Rules
1. **Top-Line Punctuation Non-Regression**: `JapanesePunctuationEvaluationTest`（67+ テスト）の誤爆防止・レイテンシ予算を厳守する。
2. **Pause-and-Stop リーク防止**: 一時停止・停止時のオーディオアキュムレータ残留や重複チャンク送出を禁止する（Finding 24）。
3. **Hotwords ACL**: `HotwordsGenerator` は日本語語彙を必ず `cjkchar` スペース区切り形式（例: `中 村`）で展開する（Finding 1）。

### Inbound / Outbound Communication
- **Inbound**: `startListening()`, `stopListening()`, `processAudioChunk()`, `UserDictionaryChangedEvent`
- **Outbound**: `VoiceUtteranceFinalizedEvent`, 確定テキスト（句読点・辞書置換適用済み）

### Tactical Building Blocks
- **Domain Services**: `StreamingVadProcessor`, `ProsodyPitchEstimator`, `JapanesePunctuationEngine`, `ContextAwareProcessor`
- **Application / ACL**: `HotwordsGenerator`, `DictionaryPostProcessor`
- **Infrastructure**: `AsrManager` (Sherpa-ONNX), `AudioRecorder`

---

## BC-4: English Typing & Swipe（英語入力・スワイプ・スペル修正）

| 項目 | 定義 |
|---|---|
| **Name** | English Typing & Swipe (`context.english`) |
| **Strategic Classification** | **Supporting Domain** |
| **Domain Roles** | Execution / Decoding Engine |
| **Purpose** | QWERTY 配列上のジェスチャー（スワイプ）軌跡を FUTO ニューラルモデルでデコードし、タイポに対しては SymSpell + 空間編集距離でスペル修正候補・大文字化候補を生成する。 |

### Ubiquitous Language（ユビキタス言語）
- **Swipe Trajectory**: キー座標 `(x, y, t)` の時系列サンプル。
- **Spatial Edit Distance**: Damerau-Levenshtein 距離と物理 QWERTY キー座標のユークリッド距離の融合スコア。
- **Base Trie Snapshot**: 0.5ms 未満でリセットしユーザー辞書を再同期できる C++ 語彙トライのスナップショット。
- **Capitalization Pattern**: 文頭・固有名詞・全大文字（ACRONYM）の大文字化パターン。

### Business Decisions & Domain Rules
1. **Spatial-Gated User Boost**: スワイプデコードでは空間尤度が閾値（`max_base - 6.5f`）を満たす場合のみユーザー辞書ブーストを許可し、誤認識を防止する。
2. **Symmetric English Sync**: `DictionaryRepository` の英語エントリ変更時、FUTO Swipe Trie と SymSpell の双方へ同期する。
3. **Corpus-Derived Algorithmic Truecasing & Exact-Surface Dedup**: 大文字化候補はハードコード語彙リストを排し、コーパス統計 $P_{\text{lex}}(s \mid w)$・2-state 文字バイグラム LM（OOV アクロニム）・Punkt 型文境界確率 $P(\text{BOS} \mid \text{ctx})$ のベイズ混合で順位付けし、表層の正確一致で重複排除することで `US`/`us` や `May`/`may` を候補バーに共存させる。

### Inbound / Outbound Communication
- **Inbound**: `decodeSwipe(points)`, `suggestTypo(word)`, `analyzeCapitalization(word, context)`, `generateCaseVariants(word, bosProb, policy)`, `UserDictionaryChangedEvent`
- **Outbound**: `List<ConversionCandidate>`

### Tactical Building Blocks
- **Domain Services / Judgement / Policy**: `EnglishCapitalizationAnalyzer`, `EnglishCaseModel`, `SentenceBoundaryDetector`, `BayesianCaseRanker`, `InputFieldCapPolicy`, `EnglishDictionaryUtils`, `EnglishAssistJudgement`
- **Infrastructure (JNI)**: `FutoSwipeEngine` (`libfuto_swipe.so`), `EnglishSpellEngine` (`libenglish_spell.so`)

---

## BC-5: Custom Vocabulary（カスタム辞書・ユーザー語彙）

| 項目 | 定義 |
|---|---|
| **Name** | Custom Vocabulary (`context.vocabulary`) |
| **Strategic Classification** | **Supporting Domain**（全入力モダリティへの単一真実源 / Single Source of Truth） |
| **Domain Roles** | Master Data / Source of Truth |
| **Purpose** | 日本語（表記・よみ）と英語（単語・大文字表記）を統合したユーザー語彙の登録・編集・優先度管理・永続化を担い、変更を全下流コンテキストへ配信する。 |

### Ubiquitous Language（ユビキタス言語）
- **DictionaryEntry (Aggregate Root)**: `id`, `word`, `reading`, `priority (LOW/NORMAL/HIGH)`, `isEnabled`, `language`。
- **Unified Schema**: 英語単語は `reading` に小文字正規化形、`word` に元の表記（例: `Antigravity`）を保持する統一スキーマ。

### Business Decisions & Domain Rules
1. **Empty-by-Default 契約**: 初回インストール時は空配列 `[]` とし、ベースライン認識を汚染しない（ADR-005）。
2. **Persistence Contract**: `filesDir/custom_dictionary.json` をアプリアップデート時に絶対に上書き・初期化しない。
3. **変更伝播**: 保存・削除・トグル操作ごとに `UserDictionaryChangedEvent` を発行し、ASR・かな漢字・Swipe・Spell を即時同期する。

### Inbound / Outbound Communication
- **Inbound**: `addOrUpdate(entry)`, `delete(id)`, `toggle(id)`, `getActive()`
- **Outbound**: `UserDictionaryChangedEvent(activeItems)`

### Tactical Building Blocks
- **Aggregate Root / Entity**: `DictionaryEntry`
- **Repository / Infrastructure**: `DictionaryRepository`, `DictionaryDatabaseHelper`
- **Presentation**: `DictionaryAdapter`（+ `DictionaryActivity`）

---

## BC-6: Asset & Model Provisioning（モデル・辞書アセット配信管理）

| 項目 | 定義 |
|---|---|
| **Name** | Asset & Model Provisioning (`context.provisioning`) |
| **Strategic Classification** | **Generic Subdomain** |
| **Domain Roles** | Infrastructure / Lifecycle Orchestration |
| **Purpose** | Download-Only アーキテクチャに基づき、ReazonSpeech ONNX・Mozc 各種外部辞書・接続行列・FUTO Swipe・SymSpell・SLM モデルの HTTPS ダウンロード、展開、更新追跡、および起動時ウォームアップ順序制御を担う。 |

### Ubiquitous Language（ユビキタス言語）
- **Asset Storage Mode**: `externalFilesDir` をプライマリ、APK `assets/` をレガシーフォールバックとする解決規約。
- **Multi-Dictionary Type**: 一般・Wikipedia・人名・地名・Sudachi 等の分割アセット種別。
- **Warm-Up Sequence**: KanaKanji $\to$ SLM $\to$ ASR の非同期ウォームアップ状態遷移。

### Business Decisions & Domain Rules
1. **Download-Only Policy**: 大規模バイナリを APK に同梱せず（`connection_data.bin` のみ許可）、オンデマンドダウンロードで APK サイズ $\le 110\text{MB}$ を維持する。
2. **Non-Blocking Warm-Up**: アプリ起動時・IME 起動時に UI スレッドをブロックせず非同期でウォームアップする。

### Inbound / Outbound Communication
- **Inbound**: `downloadAsset(type)`, `checkUpdates()`, `startWarmUpAsync()`
- **Outbound**: `AssetProvisionedEvent`, 進捗コールバック

### Tactical Building Blocks
- **Domain / Orchestration**: `DictionaryUpdateTracker`, `ModelWarmUpManager`
- **Infrastructure**: `ModelDownloader`, `BaseDictionaryDownloader`, `MultiDictionaryManager`, `ConnectionDataManager`, `FutoSwipeDownloader`, `EnglishSpellDownloader`

---

## BC-7: IME Session & Keyboard UI（IMEセッション・キーボード入力調停）

| 項目 | 定義 |
|---|---|
| **Name** | IME Session & Keyboard UI (`context.ime`) |
| **Strategic Classification** | **Core Orchestration / Presentation** |
| **Domain Roles** | Gateway / Orchestrator / UI |
| **Purpose** | Android `InputMethodService` のセッション管理、音声入力とタッチ入力の競合調停、日本語フリック・英語 QWERTY キーボード描画、候補ストリップ表示、およびセットアップ画面を提供する。 |

### Ubiquitous Language（ユビキタス言語）
- **EditorBridge**: Android `InputConnection` を抽象化し、テスト可能にしたテキスト操作境界。
- **Voice-Touch Arbitration**: 音声認識のインフライトデコード中に DEL / Enter / Space が押下された際の確定・キャンセル調停ルール。
- **Keyboard Height Preset**: ユーザー設定に基づくキーボード高さプリセット。

### Business Decisions & Domain Rules
1. **4-Card Top-Level UI 契約**: `MainActivity` は必ず 4 カード構成（Status/Test, Core Settings, Data Management, Collapsible Advanced）を維持する。
2. **IME Window Inset 非クリッピング**: フリックポップアップがトップ行でクリップされないようインウィンドウオーバーレイで描画する（Finding 7）。

### Inbound / Outbound Communication
- **Inbound**: Android IME イベント (`onKey`, `onStartInputView`), `VoiceUtteranceFinalizedEvent`, `RerankCompletedEvent`
- **Outbound**: `EditorBridge.commitText()`, `TextCommittedEvent`

### Tactical Building Blocks
- **Domain Services**: `VoiceTouchInputArbitrator`, `EditorBridge`, `InputConnectionEditorBridge`
- **Presentation**: `FlickKeyboardView`, `FlickPopupWindow`, `FlickPopupContentView`, `FlickPopupGeometry`, `CandidateStripView`, `KeyboardHeightManager`（+ `VoiceInputMethodService`, `MainActivity`）

---

## BC-8: Clipboard History（クリップボード履歴）

| 項目 | 定義 |
|---|---|
| **Name** | Clipboard History (`context.clipboard`) |
| **Strategic Classification** | **Generic Subdomain** |
| **Domain Roles** | Utility / Persistence & Presentation |
| **Purpose** | システムクリップボードの変更履歴を安全に保持・重複排除し、入力欄の種別や経過時間に応じたサジェスト表示と貼り付け UI を提供する。 |

### Ubiquitous Language（ユビキタス言語）
- **Clipboard Suggestion Policy**: パスワード欄除外・直近コピー優先・有効期限を判定するドメインポリシー。

### Tactical Building Blocks
- **Domain**: `ClipboardSuggestionPolicy`, `ClipboardUtils`
- **Infrastructure**: `ClipboardHistoryRepository`
- **Presentation**: `ClipboardHistoryView`
