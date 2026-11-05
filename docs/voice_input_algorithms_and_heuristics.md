# JotInput 音声入力における独自アルゴリズム・ヒューリスティクス技術仕様

## 1. 概要 (Overview)
本リポジトリ（JotInput）は、End-to-End ASR 音声認識モデル（ReazonSpeech Zipformer transducer / ONNX Runtime）による音響・言語推論結果をそのまま出力するだけにとどまらず、**モバイル端末・IME環境で実用的な日本語音声入力を実現するための多層的な独自アルゴリズムとヒューリスティクス**を備えています。

音声認識のデコード前（音響ストリーミング・VAD）、デコード中（ホットワードバイアス）、デコード後（文脈解決・句読点復元・漢数字正規化）、および入力確定時（IME調停キュー）の5つのレイヤーで高度な処理が組み込まれています。

```
+-----------------------------------------------------------------------------------+
| 1. Acoustic & VAD Layer                                                           |
|    - Dynamic Noise Floor Tracking (Sliding Window ~1.5s)                          |
|    - Typing Acoustic Suppression (Keystroke Vibration/Click Guard ~300ms)         |
|    - Micro-Pause & Continuous Pacing Chunking (<=200ms flush, <=2400ms pace)      |
+-----------------------------------------------------------------------------------+
                                          | Raw PCM Chunks
                                          v
+-----------------------------------------------------------------------------------+
| 2. Decoding & Acoustic Biasing Layer                                              |
|    - In-Beam Acoustic Biasing via Dynamic Hotwords (tokens.txt validated)         |
|    - Automatic Kana/Romaji/Kanji surface expansion with boost scores              |
+-----------------------------------------------------------------------------------+
                                          | Raw Transcribed Text
                                          v
+-----------------------------------------------------------------------------------+
| 3. Context & Semantic Post-Processing Layer                                       |
|    - Preceding Text Context Window (textBeforeCursor Analysis)                    |
|    - Contextual Homophone Disambiguation (e.g., 貴社/記者/帰社, 科学/化学)         |
|    - Person Name & Orthographic Variant Harmonization (e.g., 渡辺->渡邊, 斉藤->齋藤)|
|    - Arabic Numeral & Unit Normalization with Idiom Protection (e.g., 5キロ, 2026年)|
|    - Latency Budget (< 0.5ms pure string/regex execution)                         |
+-----------------------------------------------------------------------------------+
                                          | Context-Enriched Text
                                          v
+-----------------------------------------------------------------------------------+
| 4. Japanese Punctuation & Sentence Boundary Layer                                 |
|    - Spoken Punctuation Commands (まる/くてん->。, てん->、, かいぎょう->\n, etc.)  |
|    - Negative Preserved Vocabulary Guard (天気, 天然, 満点, 交差点, まるで, 弾丸)     |
|    - Sentence Boundary Detection & Progressive Committing                         |
|    - Auxiliary Verb & Copula Endings (です/ます/でした/ください -> 。)            |
|    - Conjunctive Starters & Subordinate Clauses (しかし/そして/ので/から -> 、)    |
+-----------------------------------------------------------------------------------+
                                          | Clean Japanese Text
                                          v
+-----------------------------------------------------------------------------------+
| 5. Hybrid IME Concurrency Arbitration Layer                                       |
|    - Dual-Thread / Concurrency Conflict Arbitration (Flick vs Speech)             |
|    - Voice Chunk FIFO Queue (pendingVoiceQueue) during Pre-edit State             |
|    - Zero Composing Overwrite (Preserves in-flight Kana composition)              |
+-----------------------------------------------------------------------------------+
                                          | Committed Text to InputConnection
                                          v
                                   [ Target Application ]
```

---

## 2. レイヤー別 詳細仕様

### レイヤー 1: 音響・ストリーミング制御（Acoustic & VAD レイヤー）
- **対象ソース**: `app/src/main/java/com/example/myasr/StreamingVadProcessor.kt`
- **課題**: 
  - モバイル実機（Pixel 10 Pro 等）やエミュレータ（ARCVM）では、環境騒音やマイクの自動ゲイン制御（AGC）により静穏時のベースライン音圧が常に変動する。
  - フリック入力やタップ操作中の打鍵振動・クリック音がマイクに混入し、誤って音声入力が開始されてしまう。
- **独自アルゴリズム / ヒューリスティクス**:
  1. **動的ノイズフロア追従アルゴリズム**:
     - 直近24チャンク（約1.5秒間）の二乗平均平方根（RMS）振幅をスライディングウィンドウで保持し、環境雑音フロア（`noiseFloor`）を連続算出。無音判定・有音判定のしきい値を動的にシフトさせる。
  2. **タイピング音誤検出抑制ガード（Typing Acoustic Suppression）**:
     - ユーザーがキーボードをタッチ・フリックした瞬間に `notifyUserTyping(durationMs = 300L)` を発行。
     - 打鍵後300msの間は有音開始しきい値を引き上げ、打鍵クリック音によるASR発火を遮断。
  3. **マイクロポーズ & 連続発話チャンキング**:
     - 発話途中の息継ぎ（200ms以下の微小無音）ではセッションを切らず、文末や一定の無音（450ms）に達した段階で非同期に発話チャンクを切り出し。

---

### レイヤー 2: 認識デコード時の動的バイアス（Decoding / ASR 言語モデル層）
- **対象ソース**:
  - `app/src/main/java/com/example/myasr/AsrManager.kt`
  - `app/src/main/java/com/example/myasr/HotwordsGenerator.kt`
  - `app/src/main/java/com/example/myasr/DictionaryActivity.kt`
- **課題**:
  - 一般的なASRモデル（Zipformer Transducer）はドメイン固有の専門用語や人名、英語混じりの表記（例: `ReazonSpeech`, `幸太朗`）の事前言語モデル確率が低く、別の一般的同音異義語（`耕太郎`, `公治`）や平仮名に倒れやすい。
  - ビーム幅（`maxActivePaths`）が狭い場合（例: 4）、ホットワードFSTのスコアが累積する前にビーム枝刈り（Pruning）で消滅してしまう。
- **独自アルゴリズム / ヒューリスティクス**:
  1. **ビーム幅拡張（`maxActivePaths = 8`）と最適ブースト係数（`hotwordsScore = 3.5f`）**:
     - 探索ビーム幅を8に拡張することで、音響事前確率の差をホットワードFSTブーストが逆転するまでのパス生存を数学的に保証。
  2. **CJKトークン整合性検証（Token Set Validation）**:
     - ReazonSpeech の `tokens.txt`（40KB）をメモリにロードし、登録単語の各文字が語彙集合に含まれているか事前検証。未登録文字が含まれる不正ルールを自動除外。
  3. **表記・読み（平仮名・片仮名）マルチバイアス自動展開**:
     - 辞書エントリ `(単語: 表記, 読み: よみ)` に対し、表記（漢字等）と読み（平仮名・片仮名）の両方に等しく十分な音響ブースト重み（`score`）を適用。音響モデルが一般的同音漢字に偏る場合でも、正確な音響音素列（`コ ウ タ ロ ウ` 等）をビーム内に確実に引き寄せる。
  4. **辞書更新時の即時 Recognizer FST リロード**:
     - `DictionaryActivity` で単語の追加・編集・削除が行われた際、ディスク上の `hotwords.txt` を再生成した上で、バックグラウンドコルーチンから `AsrManager.reload(hotwordsFile)` を即座にトリガー。

---

### レイヤー 3: 前後文脈・確定的音韻置換・数字正規化（Context & Post-Processing レイヤー / Channel 2）
- **対象ソース**:
  - `app/src/main/java/com/example/myasr/ContextAwareProcessor.kt`
  - `app/src/main/java/com/example/myasr/JapaneseNumeralUtils.kt`
  - `app/src/main/java/com/example/myasr/DictionaryPostProcessor.kt`
- **課題**:
  - 日本語音声認識において、「きしゃ」「かがく」「かいとう」「いどう」などの同音異義語は音響情報だけでは原理的に区別できない。
  - ASRの音響・言語モデルが平仮名・片仮名（例: `コウタロウ`）を出力した場合、ユーザーが期待する正式表記（`幸太朗`）へ高信頼にマッピングする必要がある。
  - 単純な恣意的辞書置換（Mozc全候補の一括置換や根拠のない単語コストいじり）はプロジェクトの「Anti-Anecdotal Tuning Policy」に違反するため、言語モデル（ASR Hotwords & SLM）と確定的音韻置換の役割分担を厳格に保持する。
- **独自アルゴリズム / ヒューリスティクス**:
  1. **Channel 2 確定的音韻置換（Deterministic Phonetic Substitution / ADR-004）**:
     - ASR デコード結果にユーザー登録語の読み（平仮名・片仮名：`こうたろう`, `コウタロウ`）が含まれる場合、トークン退避プレースホルダ（`\uE002_idx_\uE003`）を用いてユーザー登録表記（`幸太朗`）へ不可逆・最優先でマッピング。
  2. **カーソル直前文脈（`textBeforeCursor`）に基づく同音異義語判定**:
     - カーソル前方のテキストからドメインキーワード群を走査。
     - 例: 「見積」「請求」「取引」等の語彙が存在する場合は「きしゃ」→「貴社」を選択。「新聞」「記事」「取材」等の語彙が存在する場合は「記者」を選択。
  3. **文脈統一による異体字・人名ハーモナイゼーション**:
     - 直前の入力履歴や文書内にすでに旧字体・異体字（「渡邊」「齋藤」「幸太朗」等）が使用されている場合、ASRが一般的な新字体を出力しても自動で直前の異体字に統一。
  4. **慣用句・四字熟語保護付きアラビア数字正規化**:
     - 単位・助数詞（50種以上: kg, cm, 年, 月, 日, 時間, 円, ％, 個, 本, 階等）が後続する漢数字を検出してアラビア数字に変換。
     - 事前に50種以上の保護慣用句（「一期一会」「百発百中」「十人十色」「一番」「五分五分」など）をプレースホルダ（`\uE000_idx_\uE001`）へ退避（マスキング）させ、誤変換を数学的に防止した上で置換し、復元する。

---

### レイヤー 4: 日本語句読点復元・音声記号コマンド（Punctuation レイヤー）
- **対象ソース**: `app/src/main/java/com/example/myasr/JapanesePunctuationEngine.kt`
- **課題**:
  - ユーザーが音声で「まる」「てん」「かいぎょう」と指示して記号を入力したいニーズと、通常の発話（「天気」「天然」「満点」「交差点」「まるで」「ですから」）が混在し、安易な置換を行うと激しい誤変換（False-Positive）が発生する。
- **独自アルゴリズム / ヒューリスティクス**:
  1. **ネガティブ保護語彙リスト（PRESERVED_WORDS）フィルタリング**:
     - 「てん」「まる」を部分文字列として含む50以上の語彙（漢字表記およびカナ表記の両方）を定義し、文脈判定により誤置換を完全に遮断。
  2. **文末終止形・丁寧語に基づく自動句点（。）挿入**:
     - 「です」「ます」「でした」「ました」「ません」「ください」「お願いします」「ありがとう」等の終止形述語・助動詞を検知し、文末に自然に「。」を補完。
  3. **接続語・従属節に基づく自動読点（、）挿入**:
     - 文頭の接続表現（「しかし」「そして」「また」「したがって」「ところで」等）や、従属節の接続助詞（「ので」「から」「けど」「たら」「なら」等）の境界に自動で「、」を付与。

---

### レイヤー 5: IME ハイブリッド競合調停（Concurrency Arbitration レイヤー）
- **対象ソース**: `app/src/main/java/com/example/myasr/VoiceInputMethodService.kt`
- **課題**:
  - ユーザーがフリックキーボードで文字をタイピングしている最中（未確定文字列: Composing Text が存在）に、バックグラウンドの音声認識チャンクが届いた場合、直接コミットすると入力途中の文字が破壊または強制確定されてしまう。
- **独自アルゴリズム / ヒューリスティクス**:
  1. **非同期アービトレータ・キュー（`pendingVoiceQueue`）**:
     - 状態を監視し、`composingText` が空でない場合は音声認識結果をキューに退避。
     - ユーザーがフリック候補を選択または確定したイベント（`onCommitComposing`）をフックし、キュー内の音声を安全にカーソル後方へフラッシュ（追記コミット）する。
