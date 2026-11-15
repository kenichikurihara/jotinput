# PRD & Design: DELキー長押し削除の多段階加速（バッチ削除対応）

## 1. 背景と課題 (Background & Problem Statement)

現在の JotInput では、キーボードの削除キー（`⌫` / `KeyType.DELETE`）を長押しした際、`FlickKeyboardView.repeatRunnable` が以下のように動作している：
- 初回遅延: `400ms`
- リピート間隔: 固定 `65ms`（約 `15.4回/秒`）
- 1回あたりの削除文字数: 固定 `1文字`

この構成には以下の課題があり、特に長文削除時や ARCVM / 重いアプリ（Chrome, Slack 等）上で「長押ししても削除が遅い」という体感につながっている：
1. **加速がない（上限 ~15.4文字/秒）**: 100文字を削除するのに約6.9秒、200文字では約13.4秒かかり、長文修正時のストレスが大きい。
2. **1文字ごとの IPC / UI 更新ボトルネック**: 1ティックごとに `editor?.getSelectedText(0)` および `editor?.deleteSurroundingText(1, 0)` の Binder IPC が走り、さらに英語モード（`ALPHABET`）では毎回 `updateEnglishTypoAndSpellingCandidates()` の辞書検索コルーチンが起動される。これにより、ターゲットアプリや ARCVM の描画・IPC レイテンシが 20〜50ms ある場合、タイマー間隔を短くしても実効速度が上がらない。
3. **指を離した瞬間の余分な1文字削除**: 長押しリピートが発火した後に `ACTION_UP` すると、`handleKeyRelease()` が再度 `onDeletePressed()` を呼ぶため、止めたい位置からさらに1文字余分に消えてしまう。

また、Gboard 等の「単語単位の削除」は日本語（形態素境界が曖昧）や日英混在文では予測しづらく、`getTextBeforeCursor()` の追加 IPC も必要になるため、**単語レベルの削除は行わず、シンプルな文字数バッチ増加＋間隔短縮による多段階加速**を採用する。

---

## 2. Jobs to be Done (JTBD) & Critical User Journeys (CUJ)

### JTBD
- **状況 (When)**: 音声入力の誤認識や長文の書き直しで、カーソル前の数十〜数百文字を素早く消したいとき。
- **動機 (Want)**: `⌫` キーを押し続けるだけで、最初は狙った位置で止められる速度から、徐々に一気に消せる速度へ自然に加速してほしい。
- **成果 (So that)**: 短押し・短い長押しでの微調整のしやすさを保ったまま、長文でも 1〜2 秒以内にストレスなく削除できる。

### CUJ-1: 短タップによる1文字削除（精密操作）
1. ユーザーが `⌫` キーをタップし、`350ms` 未満で指を離す（`ACTION_UP`）。
2. リピートは発火せず、`ACTION_UP` 時に1文字だけ削除される（スワイプ入力直後の場合は従来通りスワイプ単語削除）。

### CUJ-2: 短い長押し（微調整〜数語削除）
1. ユーザーが `⌫` キーを押し続ける。
2. `350ms` 経過時点でリピート1回目が発火（`50ms` 間隔・`1文字/tick` = 20文字/秒）。
3. 目的の位置で指を離す（`ACTION_UP`）。
4. すでにリピートで削除が行われているため、`ACTION_UP` での追加1文字削除は発生せず、見た通りの位置でピタッと止まる。

### CUJ-3: 長い長押し（長文・段落の高速削除）
1. ユーザーが `⌫` キーを `0.85s` 以上押し続ける。
2. 経過時間に応じて、リピート間隔短縮と**1ティックあたりのバッチ削除文字数（1 → 2 → 4 → 8文字）**が段階的に切り替わる：
   - リピート経過 `0〜500ms`（合計 `0.35〜0.85s`）: 間隔 `50ms`, バッチ `1文字`（20文字/秒）
   - リピート経過 `500〜1200ms`（合計 `0.85〜1.55s`）: 間隔 `40ms`, バッチ `2文字`（50文字/秒）
   - リピート経過 `1200〜2200ms`（合計 `1.55〜2.55s`）: 間隔 `35ms`, バッチ `4文字`（約114文字/秒）
   - リピート経過 `2200ms〜`（合計 `2.55s〜`）: 間隔 `30ms`, バッチ `8文字`（約266文字/秒）
3. `deleteSurroundingText(count, 0)` が1回の IPC で複数文字をまとめて削除するため、ARCVM や重いアプリでも実効速度がしっかり向上する。

### CUJ-4: 未確定文字列（Composing Text）境界での安全停止
1. 変換中（`composingText` あり）または音声ストリーミング確定前（`streamingLastComposingText` あり）の状態で長押し削除を行う。
2. バッチサイズ `count` が残りの未確定文字数以上であっても、そのティックでは未確定文字列を空にするところまでで停止し、同じティック内で確定済み本文まで巻き込んで削除しない（次のティックまで押し続けた場合のみ確定済み本文の削除へ進む）。

---

## 3. 目標・非目標 (Goals & Non-Goals)

### 目標 (Goals)
- 長押し削除の実効速度を、従来の最大 ~15文字/秒から段階的に **最大 ~266文字/秒（30ms × 8文字）** まで引き上げる。
- `deleteSurroundingText(count, 0)` によるバッチ削除で IPC 負荷を抑え、ARCVM および実機（Pixel 10 Pro）の双方で体感できる速度向上を実現する。
- 長押しリピート後の `ACTION_UP` で余分な1文字が消えないようにする。
- UI 設定項目は増やさず、最適なデフォルト定数として組み込む（[`docs/ui_design_and_simplicity_guidelines.md`](ui_design_and_simplicity_guidelines.md) 準拠）。

### 非目標 (Non-Goals)
- 日本語・英語の単語境界を解析する単語単位削除（Word-level deletion）は実装しない。
- `⌫` キーからの左スワイプによる一括削除ジェスチャーは本タスクの範囲外とする。
- `CURSOR_LEFT` / `CURSOR_RIGHT` の長押し挙動は本タスクでは変更しない（従来通り65ms）。

---

## 4. 技術設計 (Technical Design)

1. **`DeleteRepeatAccelerator`**（新規・純粋 Kotlin クラス）
   - パッケージ: `com.example.myasr.context.ime.presentation`
   - 責務: 初回長押し遅延（`350ms`）およびリピート開始からの経過時間 `elapsedRepeatMs` に基づき、次のリピートステップ `RepeatStep(delayMs: Long, deleteCount: Int)` を返す。
   - Android 依存なし（`android.*` インポートなし）とし、ユニットテストで各段階の境界値・単調非減少性を直接検証可能にする。

2. **`FlickKeyboardView`** ([`FlickKeyboardView.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/FlickKeyboardView.kt))
   - `OnKeyboardActionListener.onDeletePressed(count: Int = 1)` に `count` 引数を追加（デフォルト `1` で後方互換を維持）。
   - `deleteRepeatStartTimeMs` と `deleteRepeatCount` を保持し、`ACTION_DOWN` / `ACTION_CANCEL` / `dismissPopup()` でリセットする。
   - `repeatRunnable` の `KeyType.DELETE` 分岐で `DeleteRepeatAccelerator.stepForElapsedMs(elapsed)` を呼び出し、返された `deleteCount` で `listener?.onDeletePressed(step.deleteCount)`、次の遅延に `step.delayMs` を使用する。
   - 触覚フィードバック (`CLOCK_TICK`) は高速リピート時に過剰にならないよう最低 `50ms` 間隔に間引く。
   - `ACTION_UP` の `handleKeyRelease()` では、`key.type == KeyType.DELETE && deleteRepeatCount > 0` の場合に追加削除をスキップする。

3. **`VoiceTouchInputArbitrator`** ([`VoiceTouchInputArbitrator.kt`](../app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt))
   - `onDeletePressed(..., count: Int = 1)` に `count` 引数を追加（デフォルト `1`）。
   - `val deleteCount = count.coerceAtLeast(1)` として：
     - `composingText.isNotEmpty()`: 末尾から `minOf(deleteCount, composingText.length)` 文字を一括削除し、`setComposingText` を1回呼ぶ。
     - `streamingLastComposingText.isNotEmpty()`: `dropLast(minOf(deleteCount, streamingLastComposingText.length))` で一括短縮。
     - `lastCommittedWasSwipe`: 従来通りスワイプ単語全体を削除（単タップ時）。
     - 確定済みテキスト: 選択範囲があれば選択削除、なければ `editor?.deleteSurroundingText(deleteCount, 0)` と `recordImeDirectEditDelta(-deleteCount)` / `recordDeleteCharsForInFlightDecodes(deleteCount)` を1回で実行。

4. **`VoiceInputMethodService`** ([`VoiceInputMethodService.kt`](../app/src/main/java/com/example/myasr/VoiceInputMethodService.kt))
   - `onDeletePressed(count: Int)` を実装し、`arbitrator.onDeletePressed(..., count = count)` へ委譲する。
   - `count > 1`（高速バッチ削除中）のときは `DELETED_SURROUNDING_TEXT` 時の `updateEnglishTypoAndSpellingCandidates()` をスキップし、高速リピート中の辞書検索連打を防ぐ。

---

## 5. 検証計画 (Verification Plan)

1. **ユニットテスト (`./gradlew testDebugUnitTest`)**:
   - `DeleteRepeatAcceleratorTest`: 全4段階の経過時間しきい値（`0ms`, `499ms`, `500ms`, `1199ms`, `1200ms`, `2199ms`, `2200ms`, `5000ms`）で期待通りの `(delayMs, deleteCount)` が返ること、負の経過時間でも安全であることを検証。
   - `VoiceInputArbitratorTest`: `onDeletePressed(count = 4)` による `composingText` 境界停止（確定済みテキストを巻き込まないこと）、確定済みテキストのバッチ削除 `deleteSurroundingText(4, 0)` を検証。
   - `JapanesePunctuationEvaluationTest`、`FutoSwipeIntegrationTest`、`DddArchitectureKonsistTest` を含む全ユニットテスト 100% パスを確認。
2. **ARCVM (`emulator-5554`) 実機テスト**:
   - `./gradlew assembleDebug` でビルドし、ARCVM (`emulator-5554`) にインストール。
   - テキスト入力欄を持つアプリ（または `MainActivity` 内のカスタム辞書入力欄 / 検索欄 / テスト用エディタ）で長文を入力し、`⌫` キー座標への長押しタッチ（`input` / `sendevent` または UI 操作）で実際に削除速度が大幅に向上すること、およびクラッシュや余分な削除がないことを `logcat`・画面・文字数計測で確認する。
3. **リモート同期 & GitHub Actions CI**:
   - コミット＆プッシュ後、`Android CI` ワークフローの成功を確認する。
