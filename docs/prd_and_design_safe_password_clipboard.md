# PRD & Design Document: 安全なパスワード・機密クリップボードコピペ (Safe Password Clipboard)

## 1. 背景と課題 (Problem Statement)

Android 13 (API 33) 以降、Google パスワード マネージャーや各種パスワード管理アプリ（1Password、Bitwarden 等）は、パスワードをクリップボードへコピーする際に `ClipDescription.EXTRA_IS_SENSITIVE = true`（旧 API では `"android.content.extra.IS_SENSITIVE"`）を付与する。

従来の JotInput（[`ClipboardHistoryRepository`](app/src/main/java/com/example/myasr/context/clipboard/infrastructure/ClipboardHistoryRepository.kt)）では、`isSensitiveClip(currentDesc) == true` の場合に即座に `return@withLock false` として処理を打ち切っていたため、以下の問題が発生していた：

1. **候補ストリップに貼り付けチップが出ない**: パスワードをコピーしても [`CandidateStripView`](app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt) に貼り付け候補が表示されない。
2. **クリップボード履歴画面（`📋`）にも表示されない**: [`ClipboardHistoryView`](app/src/main/java/com/example/myasr/context/clipboard/presentation/ClipboardHistoryView.kt) を開いても、現在コピー中のパスワードが表示されない。
3. **結果としてパスワードのコピペができない体験**: ユーザーは「Google パスワード マネージャーからコピーしたパスワードを JotInput で貼り付けられない」と感じる。

一方で、パスワードを通常のクリップボード履歴と同様に扱うと、**ディスク永続化（`clipboard_history.json`）・平文表示（ショルダーサーフィン）・SLM/次文節予測への学習汚染**という重大なセキュリティリスクが生じる。

---

## 2. Jobs to be Done (JTBD)

- **JTBD-1 (ワンタップ安全貼り付け)**:
  - **状況**: ユーザーが Google パスワード マネージャー等からパスワードをコピーし、ログイン画面のパスワード欄を開いたとき、
  - **動機**: 複雑なパスワードを手入力したり、小さなテキスト欄を長押しして OS の貼り付けメニューを探したりせずに、キーボード上から即座に貼り付けたい。
  - **期待成果**: 候補ストリップにマスク表示されたチップ（`🔒 ••••••`）が表示され、1タップで安全に貼り付けられる。
- **JTBD-2 (クリップボードシートからの安全貼り付け)**:
  - **状況**: 入力開始後に数文字打ったあとや、候補ストリップからチップが消えたあとに、やはりコピー中のパスワードを貼り付けたいとき、
  - **動機**: 画面上の `📋` ボタンから、現在コピー中の機密クリップを呼び出して貼り付けたい。
  - **期待成果**: `📋` シートの先頭に「現在の機密クリップ（履歴には保存されません）」としてマスク表示（`••••••••`）され、タップで貼り付けられ、かつ履歴やディスクには一切残らない。
- **JTBD-3 (プライバシー・非学習・非永続化の保証)**:
  - **状況**: パスワードや機密情報をコピー・貼り付けしたあと、
  - **動機**: 端末のストレージ、他人の視線、予測変換や AI 学習履歴にパスワードが一切残らないことを確信したい。
  - **期待成果**: 平文はディスクに保存されず、UI にも平文は表示されず、SLM・次文節予測・音声確定追跡にも一切渡らない。

---

## 3. Critical User Journeys (CUJ)

### CUJ-1: Google パスワード マネージャーからのコピー＆ワンタップ貼り付け
1. ユーザーが Google パスワード マネージャーで「パスワードをコピー」をタップする（OS が `EXTRA_IS_SENSITIVE = true` を設定）。
2. ユーザーがブラウザまたはアプリのパスワード入力欄をタップし、JotInput が起動する（`onStartInputView`）。
3. `ClipboardHistoryRepository.setInputViewActive(true)` が `syncWithOsClipboard()` を即時実行し、機密クリップを検知する（平文はメモリ/ディスクの永続履歴 `items` には保存せず、ハッシュ ID とタイムスタンプのみ揮発メタデータ `volatileSensitiveClip` に保持）。
4. `CandidateStripView` にマスク済みチップ `🔒 ••••••` が表示される（平文パスワードは `ConversionCandidate` にすら保持しない）。
5. ユーザーが `🔒 ••••••` をタップすると、`VoiceInputMethodService` が OS `ClipboardManager` からオンデマンドでクリップを読み出して `editorBridge.commitText()` で貼り付ける。
6. 貼り付け後、次文節予測（`NextPhrasePredictor`）や `previousCommittedPhrase`、インフライトデコード追跡には**一切記録されない**。チップはストリップから即座に消える。

### CUJ-2: `📋` クリップボードシートからの貼り付けと削除
1. ユーザーが `📋` ボタンをタップして `ClipboardHistoryView` を開く。
2. 現在の OS クリップボードが機密クリップである場合、リスト先頭に専用カード「`🔒 現在の機密クリップ（履歴には保存されません）` / `••••••••（タップして貼り付け）`」が表示される。
3. カードをタップするとオンデマンドで貼り付けられ、シートが閉じる。
4. `✕` ボタン（または「すべて消去」）をタップすると揮発メタデータが即座に破棄され、同一クリップは再表示されない。

### CUJ-3: OS クリップボード変更・有効期限切れによる自動消去
1. ユーザーが別の通常テキストをコピー、または OS が機密クリップをクリアすると、次の `syncWithOsClipboard()` で `volatileSensitiveClip` が即座に `null` へクリアされる。
2. 揮発 TTL（ストリップ: 60 秒、シート: 2 分）を超過した場合も自動的に非表示・破棄される。

---

## 4. Design Document (DD)

### 4.1 セキュリティ不変条件 (Security Invariants)
1. **Zero Disk Persistence**: `EXTRA_IS_SENSITIVE == true` のクリップは `clipboard_history.json` および `items` リストに**絶対に追加・保存しない**。
2. **Zero Plaintext in UI / Candidate Models**: `ConversionCandidate.surface`・`CandidateStripView`・`ClipboardHistoryView` には**マスク文字列（`🔒 ••••••` / `••••••••（タップして貼り付け）`）のみ**を渡し、平文パスワードを保持・描画しない。
3. **On-Demand Paste Verification**: 貼り付け実行時のみ `ClipboardHistoryRepository.consumeSensitiveClipTextForPaste(expectedId)` が OS `ClipboardManager.primaryClip` を読み出し、現在も機密クリップであり SHA-256 先頭ハッシュが `expectedId` と一致する場合のみ平文を返却する。
4. **Zero Learning & Telemetry Contamination**: 機密クリップの貼り付け時、およびパスワード入力欄（`TYPE_TEXT_VARIATION_PASSWORD` / `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` / `TYPE_TEXT_VARIATION_WEB_PASSWORD` / `TYPE_NUMBER_VARIATION_PASSWORD`）への貼り付け時は、`VoiceTouchInputArbitrator.previousCommittedPhrase` の更新、`recordDirectCommitForInFlightDecodes`、`NextPhrasePredictor.recordTransition`、`showNextPhrasePredictions` をすべて抑止する。
5. **Volatile Lifecycle**: OS クリップが非機密へ変化・消去された瞬間、または `SENSITIVE_VOLATILE_TTL_MS`（120 秒）経過時に揮発メタデータを即時破棄する。

### 4.2 ドメインモデル・DMN 決定表 (`BC-8: context.clipboard.domain`)
- [`ClipboardSuggestionJudgement`](app/src/main/java/com/example/myasr/context/clipboard/domain/ClipboardSuggestionJudgement.kt) に DMN `DecisionTable` **`SafeClipboardHandling`** (`HitPolicy.FIRST`) を追加する：
  - 入力: `SafeClipboardHandlingInput(isSensitiveClip, isPasswordInputField, isVolatileExpired, isDismissedFromStrip, isDismissedFromSheet)`
  - 出力: `SafeClipboardHandlingDecision(allowDiskPersistence, allowLearningAndPrediction, stripDisplayMode, sheetDisplayMode, reason)`
  - `SAFE_CLIP_R1`..`SAFE_CLIP_R4`: `isSensitiveClip == true` の全経路で `allowDiskPersistence = false`, `allowLearningAndPrediction = false`, 表示は `MASKED_SENSITIVE` または `HIDDEN` のみ。
  - `SAFE_CLIP_R5`: `!isSensitiveClip && isPasswordInputField` で `allowLearningAndPrediction = false`。
  - `SAFE_CLIP_DEFAULT`: 通常クリップかつ非パスワード欄のみ学習・永続化を許可。

### 4.3 Alloy 形式検証 (`app/src/test/resources/alloy/safe_password_clipboard.als`)
以下の不変条件を SAT4J で網羅検証する：
- `SafeClipboardTableIsTotal`: 全入力に対して一意のルールへ解決されること。
- `SensitiveClipNeverPersistedToDisk`: `isSensitiveClip = True` ならば常に `allowDiskPersistence = False`。
- `SensitiveClipNeverDisplayedPlaintext`: `isSensitiveClip = True` ならば `stripDisplayMode != PlainText` かつ `sheetDisplayMode != PlainText`。
- `SensitiveOrPasswordFieldNeverLearns`: `isSensitiveClip = True` または `isPasswordField = True` ならば `allowLearning = False`。

### 4.4 コンポーネント変更一覧
- `context/clipboard/domain/ClipboardUtils.kt`: マスク表示ヘルパ（`formatSensitiveCandidateChipPreview`, `formatSensitiveHistoryPreview`, `formatSensitiveBadge`）および `isPasswordInputType(inputType: Int)`。
- `context/clipboard/domain/ClipboardSuggestionJudgement.kt`: `SafeClipboardHandling` DMN 決定表。
- `context/clipboard/infrastructure/ClipboardHistoryRepository.kt`: `SensitiveClipMetadata`（平文非保持）、`volatileSensitiveClip` 管理、`getRecentSuggestionCandidateWithSensitivity`、`getActiveSensitiveClipForSheet`、`consumeSensitiveClipTextForPaste`、`dismissSensitiveFromStrip` / `dismissSensitiveFromSheet`。
- `context/clipboard/presentation/ClipboardHistoryView.kt`: 機密クリップ用マスクカードの描画とタップ／削除ハンドリング。
- `context/ime/presentation/CandidateStripView.kt`: `source == "clipboard_sensitive"` のマスク表示対応。
- `context/ime/domain/VoiceTouchInputArbitrator.kt`: `onClipboardPaste(..., isSensitiveOrPasswordContext: Boolean)` による非学習・`previousCommittedPhrase` クリア。
- `VoiceInputMethodService.kt`: 機密クリップのチップ表示、シートクリック、オンデマンド貼り付け、パスワード欄判定の統合。
