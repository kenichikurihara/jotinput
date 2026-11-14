# PRD & Formal Design: English Auto-Space, Double-Space Auto-Period & `onUpdateSelection` Synchronization

## Part 1: Product Requirements Document (PRD)

### 1. 概要 (Overview)
JotInput の英語 QWERTY / 記号（`QWERTY` / `QWERTY_SYMBOLS`）入力モードにおいて、以下の 2 つの入力支援機能を Android IME の非同期ライフサイクル（`InputMethodService.onUpdateSelection`）を含めて破綻なく動作させるための要件および形式仕様（Alloy）を定義する。

1. **英語自動スペース (English Auto-Space / Lazy Prepend)**:
   - スワイプ単語確定（`onCommitSwipeWord`）または英語候補チップ選択（`onEnglishCandidateSelected` / `futo_swipe` 代替候補選択）の直後に、次の単語（スワイプまたは英数字タップ）を入力した際、自動的に半角スペース `" "` を前置する。
   - 一方で、`.` `,` `!` `?` `'` などの句読点・記号タップ時はスペースを前置せず直結させる。
2. **Gboard 方式ダブルスペース自動ピリオド (Double-Space Auto-Period)**:
   - 英数字や閉じ括弧・引用符（`a-z`, `A-Z`, `0-9`, `)`, `]`, `}`, `"`, `'`, `%`）の直後にスペースキーを 1.0 秒（`1000ms`）以内に 2 回連続押下した際、`" "` を `". "` に自動変換する。

### 2. 背景・発見されたバグの根本原因 (Root Cause Analysis)
従来の実装（[`system_design_auto_space_period_and_jp_punctuation_suggestion.md`](docs/system_design_auto_space_period_and_jp_punctuation_suggestion.md)）では、単体テスト上は期待通りに動作していたにもかかわらず、実機・ARCVM 上の Android `EditText` と接続した際に**自動ピリオドおよび候補選択後の自動スペースが機能しない**不具合が発生していた。

**根本原因**:
1. **Android `onUpdateSelection` による状態破壊**:
   - 英語 QWERTY モードでは `composingText`（未確定文字列）を持たず、`editor.commitText(...)` で直接コミットする。
   - コミットの直後、ホストアプリの `EditText` は Binder IPC 経由で非同期に `InputMethodService.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, -1, -1)` を発火する。
   - [`VoiceTouchInputArbitrator.onExternalSelectionUpdated`](app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt) は `composingText.isEmpty() && streamingLastComposingText.isEmpty()` のとき、`System.currentTimeMillis() - lastImeComposingEditTimeMs > 80L` であれば無条件に `clearAutoAssistState()` を呼んでいた。
   - しかし `onSpacePressed` / `onCommitSwipeWord` / `onEnglishCandidateSelected` は `lastImeComposingEditTimeMs` を更新していなかったため、**1 回目のスペース押下直後や英語候補確定直後に `onUpdateSelection` が到着した瞬間に `lastAutoPeriodEligibleSpaceTimeMs = 0L` および `pendingAutoSpaceAfterWord = false` にリセットされていた**。
2. **時間窓（80ms）依存の脆弱性**:
   - Binder IPC の遅延が 80ms を超える環境（ARCVM や重い WebView/アプリ）では、単純なタイムスタンプ比較だけでは IME 自身のコミットによるエコーとユーザーのカーソルタップを区別できない。
3. **`futo_swipe` 代替候補タップ時の不整合**:
   - [`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) で `futo_swipe` の代替候補をタップした際、`onCandidateSelected` のスワイプ置換分岐で `pendingAutoSpaceAfterWord` や `lastAutoPeriodEligibleSpaceTimeMs` が更新されず、さらに日本語向けの `showNextPhrasePredictions` が呼ばれていた。

### 3. 要件と挙動マトリクス (Requirements & Behavior Matrix)

#### 3.1 バイパス条件 (`bypassAutoAssist`)
以下のいずれかに該当する場合、自動スペース・自動ピリオドともに**無効（発動しない）**：
- `KeyboardMode` が `QWERTY` および `QWERTY_SYMBOLS` 以外（`KANA`, `NUMBER`）。
- ユーザー設定で無効化されている（`autoSpaceEnabled == false` / `autoPeriodEnabled == false`）。
- `EditorInfo` がパスワード（`TYPE_TEXT_VARIATION_PASSWORD`, `VISIBLE_PASSWORD`, `WEB_PASSWORD`, `NUMBER_VARIATION_PASSWORD`）、メールアドレス（`TYPE_TEXT_VARIATION_EMAIL_ADDRESS`）、URI（`TYPE_TEXT_VARIATION_URI`）、または `TYPE_TEXT_FLAG_NO_SUGGESTIONS`。
- `textBeforeCursor` の直前非英字文字が `@`, `/`, `#`, `$`, `_`（メンション・URLパス・識別子）。

#### 3.2 英語自動スペース (Lazy Prepend)
| 直前操作 / コンテキスト | 次の操作 | 出力 (`!bypass`) | 備考 |
| :--- | :--- | :--- | :--- |
| 空 / 空白（` `, `\n`, `\t`） | スワイプ `"world"` | `"world"` | スペースなし |
| 英数字（タップ語・スワイプ語・音声語） | スワイプ `"world"` | `" world"` | 前置スペース |
| 文末/区切り記号（`.`, `,`, `!`, `?`, `;`, `:`, `)`, `]`, `}`, `%`, `"`, `'`） | スワイプ `"world"` | `" world"` | 例: `"hello. world"` |
| 開始/識別子記号（`(`, `[`, `{`, `/`, `@`, `#`, `$`, `_`, `-`） | スワイプ `"world"` | `"world"` | 例: `"@world"`, `"(world"` |
| スワイプ確定直後 / 英語候補選択直後 (`pendingAutoSpaceAfterWord == true`) | 英数字タップ (`w`, `2`) | `" w"`, `" 2"` | `onUpdateSelection` エコー後も維持 |
| スワイプ確定直後 / 英語候補選択直後 (`pendingAutoSpaceAfterWord == true`) | 句読点・記号タップ (`.`, `,`, `!`, `?`, `'`) | `"."`, `","` | スペースなしで直結 |
| 通常タップ入力中 (`pendingAutoSpaceAfterWord == false`) | `.` タップ → `c` タップ | `".c"` | `"google.com"`, `"3.14"` を破壊しない |

#### 3.3 英語ダブルスペース自動ピリオド (Gboard-style)
| 条件 | 1 回目 Space | `onUpdateSelection` エコー | 2 回目 Space (≤ 1000ms) | 3 回目 Space |
| :--- | :--- | :--- | :--- | :--- |
| 英数字 / `)` `]` `}` `"` `'` `%` の直後 (`!bypass`) | `" "` を挿入、`autoPeriodWindow = true` | 状態維持 (`autoPeriodWindow == true`) | 直前の `" "` を `". "` に置換、`autoPeriodWindow = false` | `" "` のみ挿入 (`".. "` にしない) |
| 空 / 空白 / 記号（`.`, `,`, `!`, `?`, `-`, `/` 等）の直後 | `" "` を挿入、`autoPeriodWindow = false` | 状態維持 (`autoPeriodWindow == false`) | `" "` のみ挿入 | `" "` のみ挿入 |
| 2 回目 Space が > 1000ms 後 | `" "` を挿入、`autoPeriodWindow = true` | 状態維持 | `" "` のみ挿入（新しいウィンドウ開始） | 条件満たせば `". "` |
| 1 回目 Space 後にユーザーが別位置をタップ (`ExternalCursorMove`) | `" "` を挿入、`autoPeriodWindow = true` | （別位置タップにより `autoPeriodWindow = false`） | `" "` のみ挿入 | — |

---

## Part 2: Alloy による形式仕様 (Formal Specification)

複雑な非同期状態遷移（IME 編集と `onUpdateSelection` の競合）の正当性を保証するため、Alloy モデルを [`app/src/test/resources/alloy/english_auto_space_period.als`](app/src/test/resources/alloy/english_auto_space_period.als) に定義した。

### 状態と IME カーソルデルタ追跡
IME が `commitText(s)` または `deleteSurroundingText(n, 0)` を実行する際、期待されるカーソル移動量 `pendingImeSelectionDelta += (committedLen - deletedLen)` を記録する。
`onExternalSelectionUpdated(oldSelStart, oldSelEnd, newSelStart, newSelEnd, ...)` が呼ばれた際：
1. `newSelStart == newSelEnd` かつ `pendingImeSelectionDelta != 0` かつ `(newSelStart - oldSelStart) == pendingImeSelectionDelta` の場合は **IME 自身の編集エコー（`ImeSelectionEcho`）** と判定し、`pendingImeSelectionDelta` を 0 に消費して `pendingAutoSpaceAfterWord` および `lastAutoPeriodEligibleSpaceTimeMs` を**保持**する。
2. それ以外のカーソル移動（`ExternalCursorMove`）の場合は、ユーザーまたはホストアプリによる外部カーソル移動と判定し、`clearAutoAssistState()` を実行して両状態をクリアする。

### 検証アサーション (Alloy `assert`)
- **`NoIllegalAutoPeriod`**: `bypass == True`、文頭、空白・非対象記号の直後、またはタイムアウト後に自動ピリオドが絶対に発動しないこと。
- **`NoTripleSpaceDoublePeriod`**: 自動ピリオド発動直後は `autoPeriodWindow = False` となり、3 回目のスペースで `".. "` にならないこと。
- **`PunctuationNeverPrependsSpace`**: 句読点タップ（`TypeDirectPunct`）では絶対にスペースが前置されないこと。
- **`NoDoubleSpaceOnAutoSpace`**: 既にカーソル前が空白・空文字の場合、または `bypass == True` の場合は絶対にスペースが前置されないこと。
- **`DoubleSpaceSurvivesImeEcho`**: 1 回目のスペース直後に `ImeSelectionEcho` が到着しても `autoPeriodWindow` が保持されること。
- **`CandidateAutoSpaceSurvivesImeEcho`**: 英語候補選択直後に `ImeSelectionEcho` が到着しても `pendingAutoSpace` が保持されること。
- **`ExternalMoveClearsAutoAssist`**: 外部カーソル移動（`ExternalCursorMove`）で確実に両状態がクリアされること。

---

## Part 3: 実装・テスト計画 (Implementation & Verification Plan)

1. **[`VoiceTouchInputArbitrator.kt`](app/src/main/java/com/example/myasr/VoiceTouchInputArbitrator.kt)**:
   - `pendingImeSelectionDelta: Int` を導入し、全ての直接編集（`onTypeDirectChar`, `onCommitSwipeWord`, `onEnglishCandidateSelected`, `onSpacePressed`, `onDeletePressed`, `onCandidateSelected`）で期待カーソル差分を記録。
   - `onExternalSelectionUpdated` で `ImeSelectionEcho` と `ExternalCursorMove` を正確に判別し、エコー時に `autoAssist` 状態を破壊しないよう修正。
   - `onCandidateSelected` のスワイプ代替候補置換分岐でも `pendingAutoSpaceAfterWord`・`lastAutoPeriodEligibleSpaceTimeMs`・`pendingImeSelectionDelta` を正しく更新。
2. **[`VoiceInputMethodService.kt`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt)**:
   - `futo_swipe` 候補選択時に日本語の `showNextPhrasePredictions` を呼ばず、英語アイドル表示に戻す。
3. **ユニットテスト & エンドツーエンド（IMEライフサイクル統合）テスト**:
   - [`FutoSwipeIntegrationTest.kt`](app/src/test/java/com/example/myasr/FutoSwipeIntegrationTest.kt) に、Android `EditText` の非同期 `onUpdateSelection` エコーを毎回忠実に再現する `SimulatingEditorBridge` を用いた E2E シナリオテストを追加（ダブルスペース自動ピリオド、スワイプ・候補選択後の自動スペース、外部カーソル移動でのキャンセル、80ms 超え遅延エコー、トリプルスペース、`futo_swipe` 代替候補選択後の自動スペース等）。
   - 全ユニットテスト（`JapanesePunctuationEvaluationTest` 67+ テスト含む）の 100% パスを確認し、ARCVM 等へのデプロイ・動作確認を実施する。
