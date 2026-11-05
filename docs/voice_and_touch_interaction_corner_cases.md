# 音声入力×タッチ（フリック／キー）併用時のコーナーケース網羅分析・自然挙動定義・アーキテクチャ設計書
## (Multimodal Voice & Touch Input Interaction: Exhaustive Corner-Case Taxonomy, Natural UX Specification & Arbitrator Architecture)

> **作成日**: 2026-11-01  
> **対象コンポーネント**:
> - [`VoiceTouchInputArbitrator.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt)
> - [`VoiceInputMethodService.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/VoiceInputMethodService.kt)
> - [`AudioRecorder.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/AudioRecorder.kt)
> - [`StreamingVadProcessor.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/StreamingVadProcessor.kt)
> - [`FlickKeyboardView.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/FlickKeyboardView.kt)
> - [`VoiceInputArbitratorTest.kt`](file:///home/kenichikurihara/repos/myasr/app/src/test/java/com/example/myasr/VoiceInputArbitratorTest.kt)

---

## 1. 背景と本質的課題 (Background & Root Challenges)

音声入力（ストリーミング随時入力モード／VAD ポーズ区切りモード）を有効にしたまま、ユーザーがキーボードの**確定ボタン（`確定` / `↵`）**、**変換・空白ボタン（`変換` / `␣`）**、**フリック文字入力**、**バックスペース（`⌫`）**、**濁点・小書き修飾キー（`゛゜`）**、**やり直し（`↶`）**、**カーソル移動（`◀` / `▶`）**、**クリップボード貼り付け（`📋`）** などを併用するマルチモーダル環境では、以下の **4 つの構造的競合（Structural Conflicts）** が発生する。

1. **単一 Composing 領域の競合 (`Single-Composing Span Contention`)**:
   - Android の `InputConnection` はエディタ内に **ただ 1 つの未確定下線領域（Composing Span）** しか保持できない。
   - ストリーミング音声入力が `setComposingText(streamingLastComposingText, 1)` を使っている最中に、キーボード側が `setComposingText(composingText, 1)` や `commitText(text, 1)` を呼ぶと、**進行中の音声テキストが瞬時に上書き消去**され、さらに 250ms 後のストリーミング周期で **キーボード入力が音声テキストに再び上書き消去される（相互破壊ループ）**。
2. **音声バッファ残留による確定後テキストの再出現 (`Post-Confirm Audio Resurrection`)**:
   - ストリーミング中に画面の下線付き音声テキストを「確定」や「フリック入力」で確定させても、`AudioRecorder` 内の PCM バッファ（`recordedPcmData`）や実行中の非同期デコード世代（`streamingGenerationId`）がリセットされないと、250ms 後に同じ音声が再認識されて二重に出力される。
3. **VAD 沈黙待ち・非同期 ASR 推論中の時系列逆転 (`Chronological Inversion Race`)**:
   - ユーザーが「明日の会議は」と発話した直後（VAD の 200ms〜450ms 沈黙判定が終わる前、または Sherpa-ONNX のバックグラウンド推論 100〜250ms が完了する前）に素早くキーボードで「10時」とフリック・数字入力すると、キーボード入力が先にエディタにコミットされ、後から完了した音声認識結果が末尾に付いて **`「10時明日の会議は」` と語順が逆転**する。
4. **音声コミット後のキーボード Undo (`↶`) 状態不整合 (`Stale Undo Corruption`)**:
   - キーボードで「かいぎ」$\to$「会議」と変換確定した直後に音声で「を始めます」と発話してコミットされた際、前回のキーボード Undo 状態（`lastCommittedSurface = "会議"` [2文字], `lastCommittedReading = "かいぎ"`）が残っていると、`↶` を押した瞬間に直前の音声テキスト「ます」（2文字）が消去されて「かいぎ」に置き換わり、**`「会議を始めかいぎ」` というデータ破損**を引き起こす。

---

## 2. マルチモーダル入力の3大基本原則 (Core Design Principles)

すべてのコーナーケースにおいて、ユーザーにとって最も自然で驚きがない（Principle of Least Surprise）挙動を実現するため、以下の **3 大原則** を定義する。

### 原則 1: 画面上の下線テキストに対する即時確定・先行確定の原則 (Visual What-You-See-Is-What-You-Confirm)
- それがキーボード由来（`composingText`）であれ、ストリーミング音声由来（`streamingLastComposingText`）であれ、あるいは発話直後の未フラッシュ音声であれ、**未確定の入力が存在する時は右下の Enter キーを青色の `確定` ピルとして表示**する。
- その状態で `確定` を押したときは、**改行やメッセージ送信（`KEYCODE_ENTER`）を決して発火させず、現在の音声・未確定テキストをその場で確定（Freeze & Reset）** し、マイクは継続待機状態に保つ。
- ストリーミング音声の下線表示中にフリック入力・数字入力・スペース・ペースト等を行った場合は、**表示中の音声テキストを即座に自動確定（Auto-Commit）した上で、その直後にキー入力を続ける**。

### 原則 2: 発話開始・入力開始順序に基づく厳密な時系列順序保証 (Strict Chronological Total Ordering)
- キーボードの未確定入力セッションに単調増加するセッション ID $S \in \{1, 2, \dots\}$（未入力時は $S = 0$）を付与する。
- 任意の音声チャンクおよびキー入力は、以下の全順序関係（Total Order）に従ってエディタ上に配置される：
  $$\text{ComposingSession}(S) \prec \text{SpeechCapturedDuring}(S) \prec \text{CommitsAfterSpeech}(S) \prec \text{ComposingSession}(S + 1)$$
- すなわち：
  - **発話が先・フリックが後（Pre-Typing Speech）**: ユーザーが発話し、そのポーズ判定や ASR 推論が終わる前にフリック入力を始めた場合、タッチした瞬間に発話チャンクを切り出し（タップ音の混入も遮断）、ASR 完了時に `beginBatchEdit()` 内で **現在入力中のフリック文字列（およびその間に確定された文字）の手前に遡及挿入**する。さらに、先行文脈が追加されたことでフリック中の漢字変換候補（SLM リランキング）も即座に再評価する。
  - **フリックが先・発話が後（Post-Typing Speech）**: ユーザーがフリック入力で未確定文字列（Session $S$）を表示したまま発話した場合、その音声は `pendingVoiceQueue` に保持して変換作業を邪魔せず、Session $S$ が確定された瞬間に直後へ結合出力する。

### 原則 3: 編集操作の直感性と破壊防止の原則 (Non-Destructive Direct Manipulation)
- ストリーミング音声の下線表示中に `⌫`（バックスペース）を押した場合は、**先行する確定済み文を誤削除せず、その音声テキスト自身の末尾 1 文字を削除して確定テキスト化**し、そのまま連続タップ／長押しで 1 文字ずつ自然に巻き戻せるようにする。
- 音声によるテキストコミットが 1 文字でも発生した時点で、過去のキーボード変換に対する `↶`（やり直し）状態を即座に無効化し、音声テキストの誤消去を 100% 防止する。

---

## 3. 全 24 コーナーケースの網羅的列挙と自然挙動の定義 (Exhaustive 24 Corner-Case Taxonomy)

### カテゴリ A: 確定ボタン（`確定` / `↵`）および 変換・空白ボタン（`変換` / `␣`）

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case A1** | **【ストリーミング ON】** 音声認識中の下線テキスト（例: <u>今日は天気が</u>）が表示されている時に **`確定`（Enter）** を押す | キーボードの `composingText` が空のためボタンが `↵` のままになっており、押すと確定ではなく **改行やチャット送信（`KEYCODE_ENTER`）が発火**し、さらに 250ms 後に同じ音声が再挿入された。 | 音声下線表示中はボタンを **`確定`** と表示。押下時は改行を送らず **<u>今日は天気が</u> を即座に確定コミット**し、音声バッファと世代 ID をリセットして次の発話を待機する。 |
| **Case A2** | **【ストリーミング OFF (VAD)】** 発話直後（200ms〜450ms の無音判定タイマー満了前、または ASR デコード実行中）に **`確定`（Enter）** を押す | まだ文字が画面に出ていないため idle と判定され、**先に改行や送信が発火**し、300ms 後に改行の後ろへ音声テキストが出力された。 | 発話中・未フラッシュ音声あり・デコード実行中は Enter キーを **`確定`** として動作させ、押下時は **溜まっている音声を即時フラッシュ＆デコードして確定出力**する（改行は送らない）。 |
| **Case A3** | **【両方】** 音声録音中にキーボードでかな入力中（例: <u>かいぎ</u>）に **`確定`（Enter）** を押す | 発話しながら `確定` を押すと、未フラッシュの音声にタップ音が混ざるか、順序が前後した。 | 未フラッシュ音声があればフラッシュし、<u>かいぎ</u> をひらがな確定した後、待機中の後続音声（`pendingVoiceQueue`）を直後にフラッシュする。 |
| **Case A4** | **【ストリーミング ON】** 音声の下線テキスト（例: <u>こんにちは</u>）が表示されている時に **`␣`（空白 / Space）** を押す | `commitText("　", 1)` が下線テキスト <u>こんにちは</u> を **空白 1 文字で上書き消去**し、250ms 後に再び <u>こんにちは</u> に上書きされた。 | まず **<u>こんにちは</u> を即座に確定コミット＆音声バッファリセット**し、その直後に空白（`　` または ` `）を挿入して `「こんにちは　」` とする。 |
| **Case A5** | **【両方】** 音声録音中にキーボードでかな入力中（例: <u>かいぎ</u>）に **`変換`（Space）** を押す | 変換確定後に保留音声がフラッシュされるが、発話中だった場合のフラッシュが遅延した。 | <u>かいぎ</u> を最上位候補（SLM リランク含む）で変換確定し、保留音声があれば直後にフラッシュする。 |

---

### カテゴリ B: フリック入力・直接文字入力（`onTextChar`）の割り込み

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case B1** | **【ストリーミング ON】** 音声の下線テキスト（例: <u>今日は</u>）が表示されている最中に、かなキーをフリック（例: `て`）する | `setComposingText("て", 1)` が <u>今日は</u> を消去し、250ms 後にストリーミングループが <u>て</u> を消去して <u>今日は</u> に戻す **相互上書き合戦** が発生した。 | フリックした瞬間に **<u>今日は</u> を自動確定＆音声バッファリセット**し、続けて `今日は`<u>`て`</u> として新しいキーボード Composing を開始する。 |
| **Case B2** | **【ストリーミング ON】** 音声の下線テキスト（例: <u>会議は</u>）が表示されている最中に、QWERTY / 数字 / 記号キー（例: `1`, `0`）を入力する | `commitText("1", 1)` が <u>会議は</u> を上書き消去し、直後にストリーミングループが `1` を消去した。 | キー入力の瞬間に **<u>会議は</u> を自動確定＆音声バッファリセット**し、その直後に `10` を確定入力して `「会議は10」` とする。 |
| **Case B3** | **【ストリーミング ON】** キーボードでかな入力・変換中（例: <u>てんき</u>）に、250ms 周期のストリーミング ASR ループが発火する | ストリーミングループが `setComposingText` と `CandidateStripView.showEmptyStatus("音声入力中: ...")` を呼び、**入力中の <u>てんき</u> と変換候補バーを破壊**した。 | `arbitrator.isKeyboardComposing()` が `true` の間はストリーミングループのポーリング・UI 更新を一時停止し、ユーザーのキーボード変換と候補バーを 100% 保護する。 |
| **Case B4** | **【ストリーミング OFF (VAD) / ストリーミング初回周期前】** 「明日の会議は」と発話し、ポーズ判定や ASR 完了を待たずに即座に「じゅうじ」とフリック（または「10」と直接入力）する | 指のタップ音が音声チャンクに混入し、さらに後から終わった ASR 結果が末尾に回って **`「10時明日の会議は」` と語順が逆転**した。 | 最初のキー操作時に未フラッシュ音声を即時切り出し（`flushPreTypingSpeechIfNeeded`）、ASR 完了時に `beginBatchEdit()` で **入力中・確定済みの後続文字の手前に `「明日の会議は」` を挿入**する。さらに先行文脈を得て <u>じゅうじ</u> の変換候補も再更新する。 |
| **Case B5** | **【両方】** 先にキーボードで <u>明日の会議は</u> とフリックし、未確定のまま「10時からです」と音声発話する | すでに Composing がある状態で音声が来ると、発話タイミングとの前後関係が曖昧だった。 | Session $S$ の開始後にキャプチャされた音声（`capturedSessionId == activeComposingSessionId`）として `pendingVoiceQueue` に保持し、<u>明日の会議は</u> が確定された直後に `「10時からです」` を出力する。 |
| **Case B6** | **【高速交互入力】** ① <u>あした</u> とフリック（Session 1）$\to$ ②「10時から」と発話 $\to$ ③ ASR 完了前に「明日」を確定し、即座に ④ <u>よろしく</u> とフリック開始（Session 2） | ②の音声が④の後ろに回るか、④の Composing を破壊した。 | `capturedSessionId (1) < activeComposingSessionId (2)` により、②の音声が Session 1 と Session 2 の間であることを厳密に判定し、**`明日10時から`<u>`よろしく`</u>** の順序で寸分の狂いなく挿入する。 |

---

### カテゴリ C: バックスペース削除（`⌫`）および 修飾キー（`゛゜ / 大⇔小`）

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case C1** | **【ストリーミング ON】** 既存文 `「お疲れ様です。」` の後に音声下線 <u>今日は天気が</u> が出ている状態で **`⌫`（Delete）** を押す | Android の `deleteSurroundingText(1, 0)` は Composing 領域を除外するため、**前の文末の `「。」` が消えてしまい**、さらに 250ms 後に <u>今日は天気が</u> が再描画された。 | 音声下線テキストの末尾 1 文字（`が`）を削った **`「今日は天気」` を確定コミット＆音声バッファリセット**する。続けて `⌫` を押せば `気` $\to$ `天` と自然に削除できる。 |
| **Case C2** | **【ストリーミング OFF (VAD)】** 言い淀みや誤発話の直後（まだ画面に文字が出る前）に **`⌫`（Delete）** を押して既存文字を消そうとする | `⌫` で文字を消した 300ms 後に、直前の言い淀み音声がデコード完了して勝手に挿入された。 | Composing が空の状態で `⌫` が押されたら、**未フラッシュの途中音声（`discardCurrentSpeech()`）および保留キューを破棄**し、不要な音声が後から湧き出るのを防ぐ。 |
| **Case C3** | **【ストリーミング ON】** 音声下線テキスト（例: <u>今日はてんきか</u>）の表示中に **`゛゜ / 大⇔小`** キーを操作する | `composingText` が空のため何も起きなかった。 | 音声下線テキストの末尾文字（`か`）に濁点等を適用して **`「今日はてんきが」` に変換した上で即時確定＆音声バッファリセット**する。 |

---

### カテゴリ D: 変換候補タップ（`CandidateStripView`）および やり直し（`↶` Undo）

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case D1** | **【両方】** キーボードで「かいぎ」$\to$「会議」と確定した後、音声で「を始めます」と発話・入力され、その後に **`↶`（Undo）** を押す | 古い `lastCommittedSurface = "会議"`（2文字）が残っていたため、音声テキストの末尾「ます」が消されて **`「会議を始めかいぎ」` に文字化け**した。 | 音声によるテキストコミット（通常認識・ストリーミング凍結・保留フラッシュ）が発生した瞬間に **`clearUndoState()` で Undo 状態を完全クリア**し、`↶` ボタンを無効化する。 |
| **Case D2** | **【両方】** 音声録音待機中、キーボードで「かいぎ」$\to$「会議」と変換した直後（まだ何も発話していない時）に **`↶`（Undo）** を押す | タップ音の抑制（`notifyUserTyping`）が呼ばれず、新しいセッション ID も更新されなかった。 | タップ音を抑制し、「会議」を取り消して新しい `activeComposingSessionId` で <u>かいぎ</u> の変換状態に安全に戻す。 |
| **Case D3** | **【両方】** 音声録音中、候補バーに表示されている **次フレーズ予測候補（Prediction Chip）** をタップする | ストリーミング下線テキストが同時に始まっていた場合に下線テキストが予測候補で上書き消去された。 | ストリーミング下線テキストや未フラッシュ音声があれば先に確定・フラッシュしてから予測候補を追記コミットする。 |
| **Case D4** | **【両方】** 発話またはキー確定の直後に次フレーズ予測候補が表示されたが、450ms 後に VAD の `onSpeechStateChanged(false)` が発火する | `!isSpeaking && composingText.isEmpty()` の条件だけで `showEmptyStatus("話すか、キー入力してください…")` が呼ばれ、**表示されたばかりの予測候補チップが一瞬で消去**された。 | 候補バーに有効な予測候補が表示されている間（`!candidateStripView.getCandidates().isNullOrEmpty()`）は、無音遷移イベントで候補バーを上書き消去しない。 |

---

### カテゴリ E: カーソル移動（`◀` / `▶`）、モード切替（`あa` / `?123`）、クリップボード貼り付け（`📋`）

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case E1** | **【両方】** ストリーミング下線表示中または発話直後に **カーソル左・右（`◀` / `▶`）** を押す | `streamingLastComposingText` が確定されず、カーソル移動後の位置に 250ms 後のストリーミング更新が重なってテキストが分裂・重複した。 | カーソル移動前に **ストリーミング下線テキストおよびキーボード Composing をすべて確定＆音声バッファリセット**し、遡及挿入トラッキングもクリアしてから安全にカーソルを移動する。 |
| **Case E2** | **【両方】** ストリーミング下線表示中または発話直後に **キーボードモード切替（`あa`, `?123`, `1234`, `ABC`）** を押す | `streamingLastComposingText` が未確定のまま放置され、ステータス表示も IDLE 用の文言に戻ってしまった。 | モード切替時にすべての Composing を確定＆音声バッファリセットし、録音中であれば録音中ステータスを維持する。 |
| **Case E3** | **【両方】** ストリーミング下線テキスト（例: <u>URLは</u>）の表示中に **ヘッダーのクリップボード貼り付け（`📋`）** を押す | `commitText(clipText, 1)` が <u>URLは</u> を消去してペーストし、250ms 後にストリーミングループがペースト文字列を消去して <u>URLは</u> に戻した。 | ペースト前に **<u>URLは</u> を確定コミット＆音声バッファリセット**し、その直後にクリップボード文字列を連結ペースト（`「URLはhttps://...」`）する。 |

---

### カテゴリ F: マイク開始・停止ボタン（`🎙 話す` / `⏹ 停止`）とキーボード入力の競合

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case F1** | **【両方】** 音声録音中にキーボードでかな入力中（例: <u>かいぎ</u> で候補選択中）に、マイクを切ろうとして **`⏹ 停止`** を押す | ① ストリーミング ON 時: `conn.finishComposingText()` が無条件に呼ばれ、エディタ上の <u>かいぎ</u> が勝手に確定される一方、内部の `composingText` には残って二重入力になった。<br>② ストリーミング OFF 時: 無音だと候補バーが `「音声が検出されませんでした」` で上書きされ、**変換候補が消滅**した。<br>③ `flushPendingVoice()` が無条件に呼ばれ、<u>かいぎ</u> を上書き消去した。 | `arbitrator.isKeyboardComposing()` が `true` の状態で `⏹ 停止` を押した時は、**キーボードの <u>かいぎ</u> と変換候補バーを一切破壊せずそのまま維持**し、停止前の先行音声のみを手前に挿入する。 |
| **Case F2** | **【両方】** `⏹ 停止` を押して最終デコード中（`State.DECODING` の 100〜250ms 間）に、すぐ次の文字をフリック入力し始める | 停止時の音声チャンク完了時に `composingText` が非空になっているため、`pendingVoiceQueue` に回されて **後から打った文字の後ろに停止前の音声が出力**された。 | `audioRecorder.stop()` 時点で即座に `onSpeechChunkCapturedForDecode()` トークンを発行するため、`State.DECODING` 中に打ち始めた文字の **手前（本来の時系列位置）** に停止前の音声が正確に挿入される。 |
| **Case F3** | **【ストリーミング ON】** 短い単語（200〜300ms）を発話して、最初の 250ms ストリーミング周期が完了する前に即座に **`⏹ 停止`** を押す | `streamingJob.cancel()` で初回デコードがキャンセルされ、`streamingLastComposingText` が空のため **発話全体が無視・消失**した。 | `hadUnflushedBeforeStop && !hadStreamingComposing` を検知し、停止時に残存サンプルに対する最終 ASR パスを実行して確実にテキスト化する。 |

---

### カテゴリ G: エディタ側の外部カーソル移動・選択変更（`onUpdateSelection`）

| ケース ID | 発生状況（音声モード × ユーザー操作） | 改善前の問題挙動 | 定義された最も自然な挙動（改善後） |
|---|---|---|---|
| **Case G1** | **【両方】** ストリーミング下線表示中またはキーボード Composing 中に、ユーザーが **アプリのテキスト欄を直接指でタップして別位置へカーソルを移動**する | `onUpdateSelection` が未実装だったため、古い位置に下線テキストが残ったまま、新しいカーソル位置に同じ未確定テキストが再度流し込まれて **文章が二重に複製**された。 | `onUpdateSelection` で Composing 範囲外へのカーソル移動を検知し、即座に `finishComposingText()` と `resetStreamingUtterance()` を実行して、新カーソル位置からクリーンに入力を再開する。 |

---

## 4. アーキテクチャと状態遷移設計 (Architecture & State Machine Design)

```mermaid
flowchart TD
    subgraph Sources ["入力イベント源 (Multimodal Event Sources)"]
        VoiceStream["ストリーミング ASR (250ms 周期)"]
        VoiceVad["VAD チャンク ASR (非同期完了)"]
        TouchKey["タッチキー操作 (Flick / QWERTY / 確定 / 変換 / ⌫ / ゛゜ / ↶ / 📋 / ◀▶)"]
        ExtCursor["外部カーソル移動 (onUpdateSelection)"]
    end

    subgraph Arbitrator ["VoiceTouchInputArbitrator (単一状態調停エンジン)"]
        GenGuard{"世代 ID & Composing ガード<br/>genId == streamingGenId<br/>&& !isKeyboardComposing"}
        SessionTracker["ComposingSession(S) & InFlightDecode トラッカー<br/>Session(S) < Speech(S) < CommitsAfter(S) < Session(S+1)"]
        StreamCommit["commitActiveStreamingComposing()<br/>+ AudioRecorder.resetStreamingUtterance()"]
        PreFlush["flushPreTypingSpeechIfNeeded()<br/>(キー入力開始前に未フラッシュ音声を切り出し)"]
        BatchPrepend["beginBatchEdit() 遡及プリペンド挿入<br/>(先行音声を後続キー入力の手前に配置)"]
        UndoGuard["Undo 安全管理<br/>(音声コミット時に lastCommitted* を即時無効化)"]
    end

    subgraph Output ["Android InputConnection & Keyboard UI"]
        Editor["Host App Editor (InputConnection)"]
        KeyUI["FlickKeyboardView (確定 vs ↵ / 変換 vs ␣ / ↶)"]
        CandUI["CandidateStripView (変換候補 / 予測候補 / 音声ステータス)"]
    end

    VoiceStream --> GenGuard
    GenGuard -->|有効| Editor
    GenGuard -->|有効| KeyUI

    TouchKey --> StreamCommit
    StreamCommit --> PreFlush
    PreFlush --> SessionTracker

    VoiceVad --> SessionTracker
    SessionTracker -->|Speech(S) < CurrentSession| BatchPrepend
    SessionTracker -->|Speech(S) == CurrentSession| PendingQueue["pendingVoiceQueue (確定時フラッシュ)"]
    BatchPrepend --> Editor
    BatchPrepend --> CandUI

    ExtCursor --> StreamCommit
    VoiceStream & VoiceVad --> UndoGuard
    UndoGuard --> KeyUI
```

### 主要クラスの責務と連携
1. **[`VoiceTouchInputArbitrator`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt)**:
   - Android の `InputConnection` を抽象化した [`EditorBridge`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt#L9-L18) インターフェースを介して、キーボード `composingText`、ストリーミング `streamingLastComposingText`、非同期デコード記述子 `InFlightDecode`、保留キュー `pendingVoiceQueue`、および `Undo` 状態を一元管理する。
2. **[`AudioRecorder`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/AudioRecorder.kt) & [`StreamingVadProcessor`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/StreamingVadProcessor.kt)**:
   - `hasUnflushedSpeech()`: 現在発話中（`State.SPEECH`）で未フラッシュの音声サンプルがあるかを返す。
   - `flushPendingSpeechChunk()`: キー入力開始時や `確定` 押下時に、無音タイマーを待たずにその場までの音声チャンクを即座に切り出す。
   - `resetStreamingUtterance()`: ストリーミング中の確定操作時に、`recordedPcmData` と VAD 状態をリセットし、確定済み音声の再デコードを遮断する。 또한、ストリーミング無音待機中に `recordedPcmData` が 0.5 秒のプリロールを超えて肥大化しないよう自動トリミングを行う。
   - `discardCurrentSpeech()`: バックスペース押下時に言い淀み音声を破棄する。
3. **[`FlickKeyboardView`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/FlickKeyboardView.kt)**:
   - `setComposingState(isComposing, canUndo, hasVoiceComposing)` により、キーボード未確定時だけでなく **音声下線表示中・発話中・デコード中も Enter ピルを `確定` に切り替え**、1 タップでの音声確定を可能にする。

---

## 5. 単体テストによる検証マトリクス (`VoiceInputArbitratorTest`)

[`VoiceInputArbitratorTest.kt`](file:///home/kenichikurihara/repos/myasr/app/src/test/java/com/example/myasr/VoiceInputArbitratorTest.kt) において、Android `InputConnection` の正確な Composing / SurroundingText セマンティクスを再現した `RealisticMockEditorBridge` を用い、全カテゴリのコーナーケースを自動検証している。

- `testCaseA1_ConfirmEnterDuringStreamingVoiceComposingCommitsWithoutNewlineAndResetsStream`: ストリーミング中の `確定` 押下による即時確定・改行抑止・古い世代の遅延結果無視を検証。
- `testCaseA2_ConfirmEnterInVadModeFlushesUnflushedSpeechInsteadOfSendingPrematureNewline`: VAD 発話直後の `確定` 押下による即時フラッシュ＆確定を検証。
- `testCaseA4_SpacePressedDuringStreamingVoiceComposingCommitsVoiceThenAppendsSpace`: ストリーミング中の空白キー押下で音声テキストが消えず確定後に空白が付くことを検証。
- `testCaseB1_FlickKanaDuringStreamingVoiceAutoCommitsVoiceAndSuspendsStreamingOverwrites`: ストリーミング中のフリック割り込みで音声が自動確定され、かな入力中はストリーミング上書きがブロックされることを検証。
- `testCaseB2_DirectCharInQwertyOrNumpadDuringStreamingCommitsVoiceFirst`: ストリーミング中の数字・QWERTY 直接入力で音声が自動確定された後に文字が続くことを検証。
- `testCaseB4_PreTypingSpeechChronologicalPreservationWhenFlickingBeforeAsrCompletes`: 発話直後にフリック入力した際、後から完了した音声がフリック中テキストの手前に遡及挿入されることを検証。
- `testCaseB4b_PreTypingSpeechChronologicalPreservationWhenUserAlreadyCommittedTypedWordBeforeAsrCompletes`: 発話直後に数字・漢字を確定しさらに次の単語をフリックしている最中に先行音声の ASR が完了した場合でも、正確な時系列位置（最初の確定文字の手前）に挿入されることを検証。
- `testCaseB5_PostTypingSpeechDuringActiveComposingQueuesAndFlushesAfterCommit`: フリック未確定中に発話した音声が `pendingVoiceQueue` に保持され、確定直後に結合されることを検証。
- `testRapidThreeTurnAlternation_Flick1_Speak2_Commit1_Flick3_BeforeSpeak2FinishesDecoding`: フリック① $\to$ 発話② $\to$ ①確定 $\to$ フリック③ の超高速交互入力で `① + ② + <u>③</u>` の順序が完全に保たれることを検証。
- `testCaseC1_BackspaceDuringStreamingVoiceDeletesLastCharOfStreamingTextNotPrecedingSentence`: ストリーミング中の `⌫` が先行文ではなく音声下線テキストの末尾文字を削除し確定化することを検証。
- `testCaseC3_ModifierKeyDuringStreamingVoiceModifiesLastCharAndCommits`: ストリーミング中の `゛゜` キーが末尾文字を濁点化して確定することを検証。
- `testCaseD1_VoiceCommitInvalidatesStaleKeyboardUndoStatePreventingCorruption`: 音声コミット後に `↶`（Undo）が無効化され、音声テキストが誤消去されないことを検証。
- `testCaseD2_UndoWorksNormallyWhenNoVoiceCommittedAfterKeyboardCommit`: 音声が挟まらない通常のキーボード変換直後は `↶` が正常動作することを検証。
- `testCaseE3_ClipboardPasteDuringStreamingVoiceCommitsVoiceFirstThenPastes`: ストリーミング中のクリップボード貼り付けで音声が先に確定されてからペーストされることを検証。
- `testCaseG1_ExternalCursorTapFinalizesComposingAndPreventsDuplication`: エディタタップによる外部カーソル移動（`onUpdateSelection`）で Composing が確定・リセットされ二重出力が起きないことを検証。
- `testAudioRecorderFlushPendingSpeechChunkAndResetStreamingUtterance`: `AudioRecorder.flushPendingSpeechChunk()` による即時切り出しと `stop()` 時の非重複保証を検証。
