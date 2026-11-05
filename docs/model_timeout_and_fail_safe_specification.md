# 各種モデルのタイムアウト (3秒) & フェイルセーフ仕様書
(Model Execution 3-Second Timeout, Fail-Safe Fallbacks, and Error Messaging Specification)

## 1. 概要 (Overview)
JotInputは端末内（On-Device）で以下の複数モデルが協調動作します：
1. **ASR音声認識モデル**: Sherpa-ONNX + ReazonSpeech Zipformer Transducer
2. **SLM次候補・コンテキストリランカー**: llama.cpp Native Qwen2.5-0.5B
3. **かな漢字変換エンジン**: Mozc辞書 Trie Viterbi探索
4. **バックグラウンド事前ウォームアップパイプライン**: `ModelWarmUpManager`

モバイル環境では、端末のCPUサーマルスロットリング、メモリ逼迫（Low Memory）、長大な文脈処理などの要因により、モデル推論が想定外に遅延するリスクが存在します。
本改修では、すべてのモデル処理に**3秒のタイムアウト予算（`withTimeout(3000L)`）**を設定し、タイムアウト時には安全にフォールバックしてアプリがフリーズしない**フェイルセーフ動作**を実装しました。また、音声入力においてユーザーが混乱しないよう、タイムアウトや各種エラー発生時の**明確なエラーメッセージ表示**を整備しました。

---

## 2. タイムアウトおよびフェイルセーフ仕様 (Fail-Safe Architecture)

### 2.1 ASR音声認識 (Streaming / Final Decode)
- **タイムアウト設定**: `withTimeout(3000L)`（3秒超過で `TimeoutCancellationException` を捕捉）
- **フェイルセーフ動作**:
  1. ハングしたデコードジョブを直ちに中断・キャンセル。
  2. デコード進行度表示（`progressDecoding`）を即座に非表示（`GONE`）へ遷移。
  3. IME / 録音状態を安全に `State.IDLE` へ復旧。
  4. 遅延した不完全音声チャンクを安全に破棄し、後続の音声バッファ汚染を防止。
- **エラーメッセージ表示**:
  - IME（キーボード）: `Toast` および候補ストリップ上ステータス表示（`candidateStripView?.showEmptyStatus(...)`）に「**音声認識がタイムアウトしました (3秒超過)**」を表示。
  - メイン画面（MainActivity）: 結果テキスト欄に「**Error: 音声認識がタイムアウトしました (3秒超過)**」を表示し、再試行ボタンを活性化。

### 2.2 SLM候補リランキング & 次フレーズ予測 (LlmReranker)
- **タイムアウト設定**: `withTimeout(3000L)`
- **フェイルセーフ動作**:
  1. 変換候補リランキング時: SLM推論が3秒を超過した場合は推論を破棄し、すでに画面上に表示されている**基本辞書候補（`baseCandidates`）をそのまま維持**。
  2. 次フレーズ予測時: SLM推論が3秒を超過した場合は推論を破棄し、ミリ秒未満で算出済みの**高速予測候補（`fastPredictions`: 履歴Bigram・助詞継続）をそのまま維持**。
  3. キーストロークやフリック入力のタイピングUIを **0ミリ秒たりともブロックしない**（完全非同期フェイルセーフ）。

### 2.3 かな漢字変換エンジン (KanaKanjiEngine)
- **タイムアウト設定**: `withTimeout(3000L)`
- **フェイルセーフ動作**:
  - 万が一Viterbi探索や辞書検索がタイムアウトした場合、入力読みの生テキスト（ひらがな・カタカナ・英数字候補）を即時フォールバック候補として生成し、入力中の未確定文字列が消失したりキーボードが固まることを完全に防止。

### 2.4 モデルウォームアップマネージャー (ModelWarmUpManager)
- **タイムアウト設定**: 各コンポーネント（KanaKanjiEngine / SLM / ASR）のウォームアップ実行に個別に `withTimeout(3000L)` を設定。
- **フェイルセーフ動作**:
  - いずれかのモデルが3秒以内にウォームアップを完了しなかった場合、該当ステータスを `Status.FAILED` として記録し、パイプライン全体をハングさせることなく後続の処理を継続。

---

## 3. エラーシナリオとユーザー通知の調査・整備一覧 (Error Scenarios Survey)

調査の結果、ユーザー向けエラーメッセージが必要となる以下のケースを特定し、メッセージを親切かつ具体的に刷新しました：

| エラーシナリオ | 発生契機 | 旧動作 | 新仕様・エラーメッセージ |
|---|---|---|---|
| **モデル推論タイムアウト** | ASRデコードが3秒を超過 | 無反応 / 固まる | Toast & 候補バー表示: `「音声認識がタイムアウトしました (3秒超過)」` |
| **マイク初期化失敗** | 他アプリ（通話や録音）がマイクを使用中 | 短い汎用エラー | `「マイクの初期化に失敗しました。他のアプリがマイクを使用中の可能性があります。」` |
| **マイク権限未付与** | 音声ボタン押下時に権限がない | 設定画面遷移 | `「音声入力にはマイクの権限が必要です」` + 設定誘導 |
| **モデル未初期化** | ASRモデルファイル未展開・ロード中 | 英語/技術的メッセージ | `「音声認識モデルの初期化に失敗しました。アプリを再起動してください。」` |
| **音声無検出** | 発話が極小 / 雑音のみで認識文字数0 | 単に無音・無反応 | 候補バーに親切な案内: `「音声が認識されませんでした。もう一度お話しください。」` |
| **辞書登録バリデーション** | 単語やよみが空 / よみが非かな | 入力枠エラー表示 | 該当レイアウトへフォーカス移動し理由を明示 |

---

## 4. テスト検証 (`ModelTimeoutAndFailSafeTest.kt`)
新設したテストスイート [`ModelTimeoutAndFailSafeTest.kt`](app/src/test/java/com/example/myasr/ModelTimeoutAndFailSafeTest.kt) により以下を検証しています：
- `testAsrTranscriptionTimeoutTriggersFailSafeAndErrorMessage`: 3秒超過時にフェイルセーフとなりエラーメッセージが設定されること。
- `testAsrTranscriptionFastPathSucceedsWithinTimeout`: 通常の推論（~150ms）がタイムアウトせず即時正常完了すること。
- `testSlmRerankTimeoutFailsSafeRetainingBaseCandidates`: SLM遅延時に基本候補が脱落せず維持されること。
- `testSlmNextPhrasePredictionTimeoutFailsSafeRetainingFastPredictions`: 次フレーズ予測遅延時に高速予測が維持されること。
- `testKanaKanjiConversionTimeoutFailsSafeWithRawReading`: 変換遅延時に入力読みフォールバックが生成されること。
- `testAudioRecordInitFailureMessage`: マイク初期化失敗時のエラーメッセージが明確であること。
