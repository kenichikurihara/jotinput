# PRD & Design: フリック入力・未確定中のヘッダーアイコン非表示による候補表示数拡大

## 1. 背景と課題 (Background & Problem Statement)

現在の JotInput のキーボード上部ヘッダーバー（[`keyboard_view.xml`](../app/src/main/res/layout/keyboard_view.xml) の `keyboardHeader`、高さ `36dp`）には、以下の要素が横並びに配置されている：

1. **音声入力ボタンコンテナ** (`voiceToggleContainer` / `btnVoiceToggle` + `progressDecoding`): `66dp` + 右マージン `6dp` = **`72dp`**
2. **候補ストリップ** ([`CandidateStripView`](../app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt)): `layout_width="0dp"`, `layout_weight="1"`
3. **録音インジケータ** (`recordingIndicator`): `8dp` + 左右マージン `8dp`（録音中のみ表示）
4. **ユーザー辞書ショートカット** (`btnHeaderDict`, `📖`): `32dp` + 左マージン `2dp` = **`34dp`**
5. **クリップボード履歴ショートカット** (`btnHeaderClipboard`, `📋`): `32dp` + 左マージン `2dp` = **`34dp`**

一般的なスマートフォン（横幅 `360dp〜412dp`）では、これら 3 つのユーティリティアイコン群だけで合計 **`140dp`（ヘッダー全幅の約 35〜39%）** を常時占有している。そのため、フリック入力中や未確定文字列（変換中）がある状態でも、`CandidateStripView` の表示幅が狭く、スクロールなしで一度に見える候補数が **2〜3 個程度** に制限されていた。

フリック入力・文字入力の真っ最中や未確定文字がある状態では、ユーザーは「マイク」「辞書」「クリップボード」ではなく**変換候補の選択**に集中しているため、入力中の非入力アイコンを隠して候補表示領域を最大化することが、入力効率・視認性・SLM 候補露出の向上に直結する。

---

## 2. Jobs to be Done (JTBD) & Critical User Journeys (CUJ)

### JTBD
- **状況 (When)**: フリック入力でキーに触れているとき、または日本語・英語の未確定文字列（Composing Text）を入力・変換しているとき。
- **動機 (Want)**: マイク（`🎙 話す`）・辞書（`📖`）・クリップボード（`📋`）アイコンを自動的に隠し、候補バーを画面幅いっぱいに広げてほしい。
- **成果 (So that)**: 横スクロールしなくても一度に多くの変換候補を見渡して素早くタップでき、確定して待機状態に戻ればすぐに音声入力や辞書・クリップボードアイコンへ再アクセスできる。

### CUJ-1: 待機状態（Idle）
1. キーボードが開いた直後、または変換候補を確定して未確定文字が空（`composingText` 空、フリックタッチなし、録音・デコード中でない）の状態。
2. マイク（`🎙 話す`）・辞書（`📖`）・クリップボード（`📋`）の 3 アイコンがすべて表示される。
3. ユーザーはいつでもワンタップで音声入力開始、ユーザー辞書起動、クリップボード履歴表示を行える。

### CUJ-2: フリック／キー押下開始（Active Key Touch）
1. ユーザーが文字キー（`KANA_FLICK`, `MODIFIER_FLICK`, `PUNCTUATION_FLICK`, `QWERTY_CHAR`）に指を置く（`ACTION_DOWN`）。
2. `isKeyTouchActive = true` となり、音声が `IDLE` かつクリップボード履歴が閉じている場合、指を離す前（1文字目が `composingText` に入る前）から 3 アイコンを `View.GONE` にして `CandidateStripView` を全幅へ事前拡張する。これにより `ACTION_UP` 後の候補出現時にヘッダー幅のガタつき（レイアウトジャンプ）が起きない。
3. 指を離したときに文字が入力されれば未確定状態（CUJ-3）へそのまま引き継がれ、文字入力がキャンセルされて未確定文字が空のままなら即座にアイコンが再表示される。

### CUJ-3: 未確定中（Composing Text あり）
1. フリック入力（日本語かな漢字変換）、QWERTY 英語入力（スペル・単語候補）、または音声ストリーミング未確定テキスト（`isStreamingComposing == true`）が存在する状態。
2. 音声が `IDLE` の場合、マイク・辞書・クリップボードの 3 アイコンがすべて `View.GONE` になり、`CandidateStripView` が **`+140dp`** 拡張されてより多くの候補を表示する。
3. **音声録音／デコード中の安全不変条件**: もし音声入力が `RECORDING` または `DECODING` 中に未確定入力が重なった場合は、録音停止ボタン（`⏹ 停止`）やデコード中スピナーが操作不能にならないよう、`voiceToggleContainer` は必ず `View.VISIBLE` を維持し、辞書・クリップボードアイコンのみを `View.GONE` にする。

### CUJ-4: 候補確定・全削除（Commit / Clear）
1. ユーザーが候補をタップ、Enter で確定、または `⌫` で未確定文字をすべて削除し、`composingText` が空に戻る。
2. `syncKeyboardState()` が即座にヘッダー表示状態を更新し、3 アイコンが `View.VISIBLE` に復帰する。

### CUJ-5: クリップボード履歴パネル表示中
1. ユーザーが `📋` をタップして `ClipboardHistoryView` を開いている間は、パネルを閉じるトグル操作等を妨げないよう 3 アイコンとも `View.VISIBLE` を維持する。

---

## 3. 目標・非目標 (Goals & Non-Goals)

### 目標 (Goals)
- フリック入力（キー押下中）および未確定中（`isKeyboardComposing` / `isStreamingComposing`）にマイク・辞書・クリップボードアイコンを `View.GONE` とし、`CandidateStripView` の有効幅を最大 `+140dp` 拡大して候補の同時表示数を増やす。
- ヘッダー表示可否の意思決定ロジックを純粋ドメインの DMN 決定表（[`ImeHeaderDecisionModel`](../app/src/main/java/com/example/myasr/context/ime/domain/ImeHeaderDecisionModel.kt)）へ切り出し、Alloy 6（[`ime_header_visibility.als`](../app/src/test/resources/alloy/ime_header_visibility.als)）およびユニットテストで全状態遷移・安全不変条件を検証する。
- 設定項目（UI ノブ）は増やさず、デフォルト動作として組み込む（[`ui_design_and_simplicity_guidelines.md`](ui_design_and_simplicity_guidelines.md) 準拠）。

### 非目標 (Non-Goals)
- 2段候補パネル（展開ドロワー）の新設やキーボード高さの変更は行わない。
- 候補ピル自体のフォントサイズやパディング縮小は行わない（純粋に横幅拡大によって表示候補数を増やす）。

---

## 4. 詳細設計 (Detailed Design)

### 4.1 ドメイン層: `ImeHeaderDecisionModel` (BC-7: `context.ime.domain`)
- パッケージ: `com.example.myasr.context.ime.domain`（Android UI 非依存の純粋 Kotlin）
- 入力 `ImeHeaderVisibilityInput`:
  - `voiceState: VoiceState` (`IDLE`, `RECORDING`, `DECODING`)
  - `isKeyboardComposing: Boolean`
  - `isStreamingComposing: Boolean`
  - `isKeyTouchActive: Boolean`
  - `isClipboardHistoryShowing: Boolean`
- 出力 `ImeHeaderVisibilityDecision`:
  - `showVoiceToggle: Boolean`
  - `showDictionaryButton: Boolean`
  - `showClipboardButton: Boolean`
- DMN 決定表 `ImeHeaderVisibilityDecisionTable` (`HitPolicy.FIRST`):
  1. `HEADER_R1`: `isClipboardHistoryShowing == true` → `(true, true, true)`
  2. `HEADER_R2`: `voiceState != IDLE && (isKeyboardComposing || isStreamingComposing || isKeyTouchActive)` → `(true, false, false)`
  3. `HEADER_R3`: `voiceState != IDLE` → `(true, true, true)`
  4. `HEADER_R4`: `isKeyboardComposing || isStreamingComposing || isKeyTouchActive` → `(false, false, false)`
  5. `HEADER_R5`: `true`（デフォルト待機状態）→ `(true, true, true)`

### 4.2 プレゼンテーション層 & レイアウト
1. [`keyboard_view.xml`](../app/src/main/res/layout/keyboard_view.xml):
   - `btnVoiceToggle` と `progressDecoding` を囲む `FrameLayout` に `android:id="@+id/voiceToggleContainer"` を付与し、`GONE` 時に `72dp` の領域ごと解放されるようにする。
2. [`FlickKeyboardView.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/FlickKeyboardView.kt):
   - `OnKeyboardActionListener` に `fun onKeyTouchUpOrCancel() {}` を追加（デフォルト実装付き）。
   - `onKeyTouchDown()` を発火した文字／フリックキー押下を追跡（`isCharKeyTouchNotified`）し、`ACTION_UP`（`handleKeyRelease` 実行後）・`ACTION_CANCEL`・`dismissPopup()` で `onKeyTouchUpOrCancel()` を通知する。
   - `handleKeyRelease()` の後に `onKeyTouchUpOrCancel()` を呼ぶことで、文字入力確定（`composingText` 更新）の 1 フレーム前にアイコンが一瞬再表示されるチラつきを防止する。
3. [`VoiceInputMethodService.kt`](../app/src/main/java/com/example/myasr/VoiceInputMethodService.kt):
   - `voiceToggleContainer` 参照を保持し、`isKeyTouchActive` 状態と `updateHeaderVisibility()` メソッドを追加。
   - `syncKeyboardState()`、`updateUiState()`、`showClipboardHistoryView()`、`hideClipboardHistoryView()`、`onKeyTouchDown()`、`onKeyTouchUpOrCancel()`、`onFinishInputView()` から `updateHeaderVisibility()` を呼び出し、DMN 決定結果に応じて `voiceToggleContainer` / `btnHeaderDict` / `btnHeaderClipboard` の `visibility`（`View.VISIBLE` / `View.GONE`）を切り替える。

### 4.3 形式検証 (Alloy 6)
- [`ime_header_visibility.als`](../app/src/test/resources/alloy/ime_header_visibility.als) にて以下を網羅検証：
  - `HeaderTableIsTotal`: 全入力パターンで一意のルールに到達すること。
  - `VoiceToggleAlwaysVisibleWhenRecordingOrDecoding`: `RECORDING` / `DECODING` 中に停止ボタン／スピナーが隠れないこと。
  - `IconsHiddenDuringComposingOrFlickWhenVoiceIdle`: 音声待機かつクリップボード非表示時に、フリックタッチ中または未確定中であれば 3 アイコンがすべて隠れること。
  - `AllIconsVisibleWhenFullyIdle`: 完全待機時は 3 アイコンすべて表示されること。
  - `ClipboardOpenKeepsAllIconsVisible`: クリップボードパネル表示中は 3 アイコンすべて表示されること。

---

## 5. 検証計画 (Verification Plan)
1. **ユニットテスト**: `ImeHeaderDecisionModelTest` で DMN ルール `HEADER_R1〜R5` 全分岐・トレース・境界ケースを検証。
2. **形式検証 & アーキテクチャ適合度**: `AlloyVerificationTest`（`ime_header_visibility.als`）および `DddArchitectureKonsistTest` 全パス。
3. **回帰テスト & CI**: `./gradlew testDebugUnitTest` および `./gradlew assembleDebug`（GitHub Actions `Android CI`）の成功確認。
