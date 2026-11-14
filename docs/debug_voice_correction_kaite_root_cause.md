# デバッグ報告書：「デザインドキュメントを描いて」→「書いて」の修正が次回音声入力に反映されない原因分析

- **対象事象**: 日本語音声入力で「でざいんどきゅめんとをかいて」と発話した際、「デザインドキュメントを描いて」が入力される。ユーザーが手動で「描いて」を「書いて」に修正したにもかかわらず、再度「でざいんどきゅめんとをかいて」と音声入力すると、再び「デザインドキュメントを描いて」が出力される。
- **調査範囲**:
  1. 修正検知フェーズ（`VoiceTouchInputArbitrator` → `VoiceInputMethodService` → `VoiceCorrectionDetector` → `VoiceCorrectionRepository`）
  2. 次回発話への適用フェーズ（`VoiceInputMethodService` → `HotwordsGenerator` / `ContextAwareProcessor` / `VoiceCorrectionScoring` / `LlmReranker`）

---

## 1. 結論（エグゼクティブ・サマリー）

本事象は単一のバグではなく、**「検知・記録フェーズ」と「次回発話の適用フェーズ」の双方に存在する合計 5 つの設計・実装上の欠陥**が連鎖して発生している。

| 優先度 | フェーズ | 根本原因（Root Cause） | 事象への影響 |
| :--- | :--- | :--- | :--- |
| **RC-1 (致命的)** | 次回適用 | **デフォルトのストリーミング音声入力パス（`formatVoiceText()`）が `VoiceCorrectionScoring` を一切呼んでいない** | 仮に学習が記録されていても、ストリーミング中は事後置換・文脈ゲート・提案チップ（S2/S3/S5）が**100% 無効化**される |
| **RC-2 (致命的)** | 検知・記録 | **`handleVoiceAnchorSettled` が `readingResolver` に `null` を渡しており、`fallbackJapaneseSurfaceDistance("描", "書")` が `1.0f` を返す** | 1文字漢字同士かつ文字重複のない「描」→「書」は音韻距離 `1.0f > 0.40f` となり、**`REJECT_SEMANTIC_REWRITE` として学習自体が棄却**される |
| **RC-3 (高)** | 検知・記録 | **ストリーミング凍結プレフィックス（`streamingCommittedPrefix`）により `VoiceCommitAnchor` が末尾断片しか保持しない** ＋ `onStartInputView` 等のリセットタイミング | 凍結済み領域を修正した場合や、確定前に別操作へ移った場合に差分抽出が欠落する |
| **RC-4 (高)** | 検知・記録・適用 | **送り仮名・活用語尾を無視した 1文字スパン抽出（`"描" -> "書"`）と `typedReadingHint`（`"かいて"`）の不一致** | 1文字漢字 `"書"` への Hotword ブーストが効かず、かつ S2 が動作した場合に「絵を描く」等まで「絵を書く」へ誤置換するリスクを生む |
| **RC-5 (中)** | 次回適用 | **`AMBIGUOUS_JP_HOMOPHONES` に「描く/書く（描いて/書いて）」等の同音訓読み動詞が含まれていない** ＋ S1 Hotwords の同音語識別限界 | 同音異義語であるにもかかわらず非曖昧語扱いとなり、SLM コヒーレンス・ゲート（S3）による文脈保護が効かない |

---

## 2. 発話〜修正〜再発話のコードレベル・トレース

### 2.1. ステップ①：初回音声入力「でざいんどきゅめんとをかいて」の確定

1. デフォルト設定では [`KeyboardHeightManager.isStreamingVoiceInputEnabled`](app/src/main/java/com/example/myasr/context/ime/presentation/KeyboardHeightManager.kt#L194-L197) が `true` のため、[`VoiceInputMethodService.startStreamingLoop()`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1629-L1720) が動作する。
2. ReazonSpeech ASR が `"デザインドキュメントを描いて"` を出力し、N-step LCP 安定判定（[`onStreamingStepResult`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt#L920-L958)）により、前半（例: `"デザインドキュメント"`）が `streamingCommittedPrefix` として先に `editor.commitText` される。
3. 発話終了時、[`finishListeningAndDecode()`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1724-L1760) にて `hadStreamingComposing == true` となり、[`commitActiveStreamingComposing`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt#L266-L294) が**残余サフィックス（例: `"を描いて"`）のみ**を引数に [`recordVoiceAnchor(editor, finalized)`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt#L103-L119) を呼ぶ。
   - **ここで既に RC-3 が発生**: `VoiceCommitAnchor.committedText` は文全体ではなく `"を描いて"`（または最後の確定チャンクのみ）になり、もし `"デザインドキュメントを描"` まで凍結済みだった場合は `"いて"` しかアンカーに残らない。

### 2.2. ステップ②：ユーザーによる「描いて」→「書いて」の手動修正

ユーザーがバックスペース等で「描いて」を消し、フリック入力で「かいて」→「書いて」へ確定したとする。

1. `onDeletePressed()` により `markVoiceAnchorEdited()` が呼ばれ、`commitKeyboardSurface(editor, "かいて", "書いて")` により [`settleActiveVoiceAnchorIfNeeded(editor)`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt#L130-L149) が発火する。
2. [`VoiceInputMethodService.handleVoiceAnchorSettled`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L286-L341) が `VoiceCorrectionDetector.detect(...)` を呼び出す。
   - **RC-2（最重要：検知棄却）**:
     `handleVoiceAnchorSettled` は `readingResolver` を渡していない（`null`）。
     ```kotlin
     val detected = VoiceCorrectionDetector.detect(
         beforeText = anchor.committedText,   // 例: "を描いて"
         afterText = editedText,              // 例: "を書いて"
         language = lang,
         contextBefore = anchor.contextBefore,
         elapsedSinceVoiceCommitMs = elapsedMs,
         typedReadingHint = anchor.typedReadingHint // "かいて"
     )
     ```
   - `computeDiffSpan("を描いて", "を書いて")` は共通接頭辞 `"を"` と共通接尾辞 `"いて"` を削るため、**`uOrig = "描"`, `uCorr = "書"`** が抽出される。
   - `expandSpanBoundary` では `描`・`書` が漢字（`cls=1`）、前後の `を`・`い` がひらがな（`cls=0`）であるため拡張されず、`uOrig = "描"`, `uCorr = "書"` のまま確定する（**RC-4**）。
   - 次に `VoiceCorrectionDetector.detect`（[92–114行目](app/src/main/java/com/example/myasr/context/speech/domain/VoiceCorrectionDetector.kt#L92-L114)）で音韻距離を計算する：
     - `resolvedReadingCorr` = `"かいて"`（`typedReadingHint` 由来）
     - `resolvedReadingOrig` = `""`（**`readingResolver == null` のため空文字**）
     - `if (resolvedReadingOrig.isNotEmpty() && resolvedReadingCorr.isNotEmpty())` が **`false`** になるため、[`fallbackJapaneseSurfaceDistance("描", "書")`](app/src/main/java/com/example/myasr/context/speech/domain/VoiceCorrectionDetector.kt#L298-L307) へフォールバックする！
     - `fallbackJapaneseSurfaceDistance("描", "書")` 内では：
       ```kotlin
       val shared = uOrig.count { it in uCorr } // "描" と "書" の共通文字数 = 0
       ```
       `shared == 0` のため `weightedKanaDistance("描", "書")` が実行され、異なる漢字 1 文字同士の距離 **`1.0f`** が返る！
     - `VoiceCorrectionEligibilityDecisionTable` は `phoneticDistance (1.0f) > 0.40f` により **`REJECT_SEMANTIC_REWRITE`** を返し、**`detect(...)` は `null` を返して学習を完全に棄却する**。

> [!IMPORTANT]
> **単体テストで見落とされた理由**: [`VoiceCorrectionDetectorTest`](app/src/test/java/com/example/myasr/context/speech/domain/VoiceCorrectionDetectorTest.kt) ではテスト側でモックの `readingResolver` を明示的に渡していたが、本番の [`VoiceInputMethodService.handleVoiceAnchorSettled`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L301-L308) では `readingResolver` が渡されていなかった。

### 2.3. ステップ③：再度「でざいんどきゅめんとをかいて」と音声入力した際

仮に何らかの形で `VoiceCorrectionRepository` にエントリが保存されたとしても、次回発話で以下の 2 つの壁に阻まれる。

1. **RC-1（致命的：ストリーミング出力に事後スコア補正が適用されない）**:
   - [`startStreamingLoop()`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1676) および [`finishListeningAndDecode()` のストリーミング確定部](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L1741) は、どちらも [`formatVoiceText(...)`](app/src/main/java/com/example/myasr/VoiceInputMethodService.kt#L496-L516) を呼んでいる。
   - `formatVoiceText` の実装は以下の通りであり、**`processWithCorrectionsAndPunctuation`（S2: `VoiceCorrectionScoring.applyCorrections` ＋ S3: SLM 文脈ゲート ＋ S5: 提案チップ）を一切呼んでいない**:
     ```kotlin
     return app.contextAwareProcessor.process(text, ctx, activeEntries)
     ```
   - `processWithCorrectionsAndPunctuation` が配線されているのは、`decodeSpeechChunkAsync()`（非ストリーミングチャンク）と `finishListeningAndDecode()` 内の `if (shouldDecodeFinalSamples)`（非ストリーミング、または極短発話のみ）の 2 箇所だけである。したがって、**通常のストリーミング音声入力では S2/S3/S5 が一度も実行されない**。

2. **RC-4 & RC-5（仮に保存・適用された場合の Hotwords 無効化と誤置換リスク）**:
   - S1（[`HotwordsGenerator.buildHotwordRules`](app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt#L134-L163)）に渡されるエントリが `correctedSurface = "書"`, `normalizedReading = "かいて"` の状態だと：
     - `"書 :3.4"`（1文字漢字単体）と `"か い て :3.4"`（`"描いて"` と `"書いて"` で音韻が完全同一のひらがな列）が生成されるだけで、`"書 い て"`（送り仮名付き）がブーストされない。
     - そもそも ReazonSpeech Transducer の `modified_beam_search` における Hotwords は未知語・固有名詞向けであり、「描いて」と「書いて」のような高頻度同音動詞の逆転には不向き（設計ドキュメント Section 8 でも S2+S3 が必須と結論づけられている）。
   - また、[`VoiceCorrectionScoring.AMBIGUOUS_JP_HOMOPHONES`](app/src/main/java/com/example/myasr/context/speech/domain/VoiceCorrectionScoring.kt#L12-L24) に「描く/書く」等の一般同音語が含まれていないため、仮に `"描" -> "書"` が登録されて S2 が有効化されると、1-Shot から `"絵を描く"` まで無条件で `"絵を書く"` に置換されてしまう危険がある。

---

## 3. 根本原因の整理と修正方針（次フェーズへの提言）

### 3.1. 修正すべき 5 点

1. **[RC-1 対策] `formatVoiceText`（ストリーミング確定パス）への `processWithCorrectionsAndPunctuation` 統合**:
   - ストリーミング確定時（`commitActiveStreamingComposing` の `formatFn` および `startStreamingLoop` 内のプレビュー整形）で `VoiceCorrectionScoring.applyCorrections` を適用し、確定直後に `suggestedChips` があれば候補バーへ `🔄` チップを表示する。
2. **[RC-2 対策] `KanaKanjiEngine` による日本語読み逆引き（`readingResolver`）の実装・接続**:
   - `handleVoiceAnchorSettled` から `VoiceCorrectionDetector.detect` へ、辞書／形態素／`KanaKanjiEngine` ベースの `readingResolver`（例: `"描"` → `"かく/えがく"`、`"描いて"` → `"かいて"`）を渡す。
   - さらに、`typedReadingHint` が非空（ユーザー自身が `"かいて"` と打ち直して確定した）場合は、たとえ `uOrig` の読みが逆引きできなくても「文字長が近く（1〜4文字）ユーザーが明確に読みを入力して漢字を書き換えた」ケースを救済する音韻検証パスを設ける。
3. **[RC-3 対策] ストリーミング発話全体の `VoiceCommitAnchor` 保持**:
   - ストリーミング開始〜発話確定までの全体テキスト（`streamingCommittedPrefix + finalized`）を `VoiceCommitAnchor.committedText` として記録し、発話の前半・後半のどちらを修正しても確実に差分を捉えられるようにする。
4. **[RC-4 対策] 送り仮名・ひらがな語尾を含む形態素単位のスパン拡張**:
   - `uOrig = "描"`, `uCorr = "書"` のように**漢字 1〜2 文字の直後に共通のひらがな送り仮名（`"いて"`, `"く"`, `"った"` 等）が続く場合**、`expandSpanBoundary` で後続のひらがな活用語尾（最大 3 文字程度、句読点・助詞の手前まで）をペアに含めて **`"描いて" -> "書いて"`** として抽出する。これにより S1（`"書 い て"`）、S2（`"デザインドキュメントを"` 文脈付き `"描いて" -> "書いて"`）、S3（SLM 文脈判定 `"デザインドキュメントを描いて"` vs `"デザインドキュメントを書いて"`）すべての精度が劇的に向上する。
5. **[RC-5 対策] 訓読み同音動詞・一般漢字置換の SLM コヒーレンス・ゲート保護**:
   - 1文字漢字置換や訓読み同音動詞ペア（`"描いて"/"書いて"`, `"聞いて"/"聴いて"`, `"会う"/"合う"` 等）や高頻度一般語に対しては、常に S3（SLM 文脈コヒーレンス・ゲート）を適用し、`"絵を描く"` での誤爆（$\text{FPR}_{\text{adv}}$）を `0%` に抑えつつ `"デザインドキュメントを書いて"` のみを通す。

---

## 4. 検証計画（修正実装時の確認事項）

1. **再現テストの追加**:
   - `VoiceCorrectionDetectorTest`: `"デザインドキュメントを描いて"` → `"デザインドキュメントを書いて"`（`readingResolver` なし／あり双方、および部分削除 `"描"` → `"書"`）で **`("描いて", "書いて", "かいて")`** が抽出されることを検証。
   - `VoiceCorrectionScoringTest`: 学習後に `"でざいんどきゅめんとをかいて"`（ASR 出力 `"デザインドキュメントを描いて"`）が `"デザインドキュメントを書いて"` に補正され、かつ対照文 `"絵を描いて"` は補正されない（またはゲートオフ＋チップ提案になる）ことを検証。
2. **ストリーミング経路の単体テスト**:
   - `VoiceTouchInputArbitrator` にて `streamingCommittedPrefix` ＋ `streamingLastComposingText` 確定後の `VoiceCommitAnchor` が発話全体を保持し、修正後に `onVoiceAnchorSettled` が発火することを検証。
