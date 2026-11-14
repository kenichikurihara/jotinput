# PRD & Design: 英語QWERTYキーボード カーソル移動オプション（左右矢印キー / スペースキースワイプ）

## 1. 背景と課題 (Background & Problem Statement)
現在、JotInput の英語QWERTY配列（および英語記号配列 `?123`）では、最下段の右側に左右カーソル移動キー（`◀` / `▶`）を常時表示している。
- **利点**: 1タップ・長押しリピートで直感的にカーソル移動ができる。
- **課題**: 最下段に `◀` / `▶` キーが計 13%（記号配列では 12%）の幅を占有するため、スペースキーの幅が狭く（33% / 40%）、Gboard 等の標準的な英語キーボードで一般的な「広いスペースキー＋スペースキー左右スワイプによるカーソル移動」を好むユーザーにとって入力しづらい場合がある。

本機能では、ユーザーが設定から以下の2つのモードを選択できるようにする:
1. **左右の矢印キー (`◀` / `▶`) を表示**（従来の挙動・デフォルト）
2. **スペースキーを左右にスワイプしてカーソル移動**（`◀` / `▶` を非表示にし、その分の幅をスペースキーに統合）

---

## 2. 目標・非目標 (Goals & Non-Goals)
### 目標 (Goals)
- 英語キーボード（`QWERTY` および `QWERTY_SYMBOLS`）で、`◀` / `▶` 表示とスペースキー左右スワイプ移動をオプションで切り替えられるようにする。
- デフォルトは従来通り `ARROW_KEYS`（左右矢印キー表示）とし、既存ユーザーの操作感を壊さない。
- スペースキー左右スワイプモードでは、`◀` / `▶` キーを非表示にしてスペースキーの幅を拡張（QWERTY: 33% → 46%、QWERTY_SYMBOLS: 40% → 52%）し、タップによるスペース入力とスワイプによるカーソル移動が誤爆なく共存するようにする。
- モード切り替え後、キーボードを開き直した際（`onStartInputView` / `syncPreferences`）に即座に反映されること。

### 非目標 (Non-Goals)
- 日本語12キー（`KANA`）および数字テンキー（`NUMBER`）配列の `◀` / `▶` キーは対象外とする。
- スペースキーの長押しによるIME切り替えダイアログ等は本タスクの範囲外とする。

---

## 3. 機能要件 (Functional Requirements)

### FR-1: カーソル移動方式の設定オプション
- `KeyboardHeightManager` に設定キー `qwerty_cursor_control_mode` を追加する。
- 選択肢（`QwertyCursorControlMode` enum）:
  - `ARROW_KEYS` (`"arrow_keys"`): 左右矢印キー (`◀` / `▶`) を表示する（デフォルト）。
  - `SPACEBAR_SWIPE` (`"spacebar_swipe"`): 左右矢印キーを非表示にし、スペースキーの左右スワイプでカーソルを移動する。

### FR-2: 設定画面 UI (`MainActivity` 「設定」タブ)
- [`docs/ui_design_and_simplicity_guidelines.md`](ui_design_and_simplicity_guidelines.md) に従い、「設定」タブ（[`tab_main_settings.xml`](../app/src/main/res/layout/tab_main_settings.xml)）内の第1カード「キーボード」に配置する。
- `英語QWERTY配列に数字行 (1〜0) を表示` の下に区切り線と **「英語QWERTY カーソル移動」** セクションを追加し、`RadioGroup`（`rgQwertyCursorMode`）で2択を選択可能にする:
  - `左右の矢印キー (◀ ▶)` (`rbCursorArrowKeys`)
  - `スペースキー左右スワイプ` (`rbCursorSpaceSwipe`)
- 変更時は `SharedPreferences` に保存し、Toast で設定変更を通知する。

### FR-3: キーボードレイアウトの動的切り替え (`FlickKeyboardView`)
- **QWERTY モード最下段 (Row D)**:
  - `ARROW_KEYS`: `あa 13% | ?123 11% | , 8.5% | Space 33% | . 8.5% | ◀ 6.5% | ▶ 6.5% | ↵ 13%`
  - `SPACEBAR_SWIPE`: `あa 13% | ?123 11% | , 8.5% | Space 46% | . 8.5% | ↵ 13%`
  - スペースキーラベル: `ARROW_KEYS` 時は `"English"`、`SPACEBAR_SWIPE` 時はスワイプ可能であることが直感的に分かるよう `"‹ English ›"` とする。
- **QWERTY_SYMBOLS (`?123`) モード最下段 (Row 3)**:
  - `ARROW_KEYS`: `ABC 15% | , 10% | Space 40% | . 10% | ◀ 6% | ▶ 6% | ↵ 13%`
  - `SPACEBAR_SWIPE`: `ABC 15% | , 10% | Space 52% | . 10% | ↵ 13%`
  - スペースキーラベル: `ARROW_KEYS` 時は `"␣"`、`SPACEBAR_SWIPE` 時は `"‹ ␣ ›"` とする。

### FR-4: スペースキー左右スワイプのジェスチャー仕様
- **有効条件**:
  - `currentMode` が `QWERTY` または `QWERTY_SYMBOLS`
  - `qwertyCursorControlMode == SPACEBAR_SWIPE`
  - `ACTION_DOWN` 時にヒットしたキーが `KeyType.SPACE`
- **スワイプ開始判定（誤爆防止）**:
  - タップ開始点 `(touchStartX, touchStartY)` からの水平移動距離 `|dx| >= activationThresholdPx`（`14dp`）かつ `|dx| >= |dy|` になった時点でスワイプモード (`isActive = true`) に入る。
  - スワイプ開始時に即座に1ステップ（左なら `onCursorLeft()`、右なら `onCursorRight()`）を発火し、基準位置を更新する。
- **連続カーソル移動**:
  - スワイプモード中は、指がスペースキー領域から多少上下に外れても `ACTION_UP` / `ACTION_CANCEL` まで追従を継続する。
  - 基準位置からの水平移動が `stepThresholdPx`（`12dp`）に達するごとに1文字分移動（`onCursorLeft()` または `onCursorRight()`）し、`HapticFeedbackConstants.CLOCK_TICK` で触覚フィードバックを返す。
- **タップ時のスペース入力との排他**:
  - スワイプモードに入らずに `ACTION_UP` した場合は、通常のスペースキー押下（`listener?.onSpacePressed()`）を実行する。
  - 一度でもスワイプモードに入った場合は、`ACTION_UP` 時にスペース文字を入力せず、ジェスチャー状態をリセットして終了する。

---

## 4. 技術設計 (Technical Design)

1. **`KeyboardHeightManager`** ([`KeyboardHeightManager.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/KeyboardHeightManager.kt))
   - `QwertyCursorControlMode` enum と `getQwertyCursorControlMode(context)` / `setQwertyCursorControlMode(context, mode)` を追加する。
2. **`SpacebarCursorSwipeTracker`** ([`SpacebarCursorSwipeTracker.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/SpacebarCursorSwipeTracker.kt))
   - タッチ座標の増分からスワイプ開始判定・左右ステップ数（`-N` / `+N`）を純粋 Kotlin で計算するステートフルヘルパーを切り出し、Android 依存なしでユニットテスト可能にする。
3. **`FlickKeyboardView`** ([`FlickKeyboardView.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/FlickKeyboardView.kt))
   - `qwertyCursorControlMode` プロパティを追加し、`syncPreferences()` で `KeyboardHeightManager` から同期する。
   - `layoutGboardQwertyKeys()` と `layoutGboardSymbolsKeys()` の最下段をモードに応じて分岐する。
   - `onTouchEvent()` の `ACTION_DOWN` / `ACTION_MOVE` / `ACTION_UP` / `ACTION_CANCEL` に `SpacebarCursorSwipeTracker` を統合する。
4. **`MainActivity` & `tab_main_settings.xml`**
   - [`tab_main_settings.xml`](../app/src/main/res/layout/tab_main_settings.xml) のキーボードカードに `rgQwertyCursorMode` (`rbCursorArrowKeys`, `rbCursorSpaceSwipe`) を追加。
   - [`MainActivity.kt`](../app/src/main/java/com/example/myasr/MainActivity.kt) で初期化・変更リスナー・`updateKeyboardHeightUI()` での状態復元を実装。

---

## 5. 検証計画 (Verification Plan)
1. **ユニットテスト (`./gradlew testDebugUnitTest`)**:
   - `SpacebarCursorSwipeTrackerTest`: しきい値未満のタップでは発火しないこと、縦方向優先のドラッグでは発火しないこと、左右スワイプで正しいステップ数と方向（左・右・切り返し）が返ること、リセット動作を検証。
   - `JapanesePunctuationEvaluationTest` および `DddArchitectureKonsistTest` を含む全ユニットテストの 100% パスを確認。
2. **実機・ARCVM デュアルデプロイ (`./deploy.sh`)**:
   - ARCVM (`arc:5555`) および実機 (Pixel 10 Pro) の両方にビルド・インストールし、`MainActivity` 起動と `AsrManager` ロードを `logcat` で確認。
   - 実機で設定画面のラジオボタン切り替え、および英語キーボードでの「矢印表示モード」「スペーススワイプモード」双方のレイアウト表示（`screencap`）と挙動を確認。
3. **リモート同期 & GitHub Actions CI**:
   - コミット＆プッシュ後、`Android CI` ワークフローの成功を確認する。
