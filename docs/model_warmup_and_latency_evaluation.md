# モデルのコールドスタート vs ウォームスタート レイテンシ評価および先行ウォームアップ設計仕様書

## 1. 概要 (Executive Summary)

本ドキュメントは、JotInputにおける主要3コンポーネント（**かな漢字変換エンジン**、**ASR音声認識エンジン**、**SLMリランカー**）のコールドスタート時とウォームスタート時におけるレイテンシを実測・定量比較し、そのレイテンシ差を解消するために実装した**「非同期・優先度付き先行ウォームアップパイプライン (ModelWarmUpManager)」**のアーキテクチャおよび設計仕様をまとめたものです。

---

## 2. レイテンシ評価データセットと実測ベンチマーク結果

### 2.1 実測値サマリー

実機環境（ARCVM / x86_64 / Android 13）およびJVM評価セットにて厳密に測定されたコールドスタートとウォームスタートの比較結果は以下の通りです。

| コンポーネント | コールドスタート時間 | ウォームスタート時間 (平均) | 高速化倍率 | コールドスタートのボトルネック要因 |
| :--- | :--- | :--- | :--- | :--- |
| **かな漢字変換エンジン**<br>*(KanaKanjiEngine)* | **235.86 ms** | **1.33 ms** | **177.9x** | 50,000行TSV辞書のパース、Trie木ノード構築、辞書同期 |
| **ASR音声認識**<br>*(ReazonSpeech Transducer)* | モデルロード: 2,693.85 ms<br>初回推論: 118.37 ms<br>**合計: 2,812.21 ms (~2.8秒)** | **148.94 ms**<br>*(2.0秒音声)* | **18.9x** | 3つのONNXモデル(encoder/decoder/joiner)メモリマップ、modified_beam_search初期化、推論グラフ初回構築 |
| **SLMリランカー**<br>*(Qwen2.5-0.5B GGUF)* | **11,749.18 ms (~11.7秒)** | **1.08 ms** | **10,905.5x** | llama.cppのコンテキストKVキャッシュ確保、スレッドプール生成、初回のフォワードパス(`llama_decode`)に伴うページイン |

### 2.2 詳細分析

1. **かな漢字変換エンジン**:
   - ユーザーがキーボードを開いて最初にキーをタップした際、コールド状態だと約236msの遅延が生じ、最初の文字入力に知覚可能な引っ掛かりが生じます。
   - ウォーム状態では1.3ms（約180倍高速）となり、毎秒60フレーム（16.6ms）の描画予算内に余裕で収まります。
2. **ASR音声認識エンジン**:
   - マイクボタンを押した後にオンデマンドでモデルをロードすると、**約2.8秒の待機時間**が発生し、ユーザー体験を損ないます。
   - 事前にロードおよび微小推論（無音ダミーパス）を行うことで、音声録音完了直後の認識処理は**148ms**で完了します。
3. **SLMリランカー (最大のボトルネック)**:
   - GGUFモデルの初回スコアリング処理(`nativeScoreCandidates` -> `llama_decode`)では、KVキャッシュのメモリ確保、モデルパラメータのページフォールト、バックエンドスレッド初期化によって**約11.7秒**を要します。
   - ウォームアップ完了後は、わずか**1.08ms (約1万倍高速)**で文脈スコアリングが完了します。
   - このため、**アプリ起動時・キーボード起動時の先行ウォームアップが極めて重要**であることが判明しました。

---

## 3. 先行ウォームアップパイプラインのアーキテクチャ設計

### 3.1 設計方針 (Non-Blocking & Fail-Safe)

1. **UIメインスレッドの100%非ブロック (0msメインスレッド停止)**:
   - 全てのモデルロードおよびウォームアップ推論は `Dispatchers.Default` 上のバックグラウンドコルーチンで非同期に実行します。
   - `startWarmUpAsync()` の呼び出し元スレッドのブロック時間は 0ms です。
2. **実行優先順位付け (Typing Priority Ordering)**:
   ユーザーがキーボードを開いた直後に必要となるコンポーネント順に直列・並列にウォームアップを実施します。
   - **優先度 1: かな漢字変換エンジン (~550ms)**: 最初の1文字目のタップ・フリックに即座に応答可能にする。
   - **優先度 2: SLMリランカー (~2.8s)**: 変換候補提示や文脈予測を行う前にllama.cppのKVキャッシュをプライム。
   - **優先度 3: ASR音声認識 (~3.8s)**: ユーザーがマイクボタンを押すまでの間にSherpa-onnxの認識モデルを待機状態にする。
3. **冪等性 (Idempotent Execution)**:
   `AtomicBoolean` によるアトミック状態制御を行い、多重起動（App起動、IME起動、設定画面起動が連続した場合）でも無駄な重複処理やスレッド競合が発生しません。
4. **耐障害性 (Fail-Safe)**:
   特定モデル（SLMやASR）が未ダウンロードや未インストールの状態であっても、例外を捕捉して他のエンジンのウォームアップを継続します。

### 3.2 ライフサイクル連携

```mermaid
flowchart TD
    A["アプリプロセス起動 (AsrApp.onCreate)"] --> W["ModelWarmUpManager.startWarmUpAsync()"]
    B["IMEサービス生成 (VoiceInputMethodService.onCreate)"] --> W
    C["キーボード表示 (onStartInputView)"] --> W

    W --> BG["Dispatchers.Default (バックグラウンドコルーチン)"]
    
    subgraph NonBlockingBackgroundPipeline ["非同期・優先度付きウォームアップパイプライン"]
        BG --> S1["1. KanaKanjiEngine.warmUp()<br>・TSV辞書読込<br>・Trie木構築<br>・ダミークエリ ('きょう')"]
        S1 --> S2["2. LlmReranker.warmUp()<br>・GGUFロード<br>・ダミースコアリング (<s>)"]
        S2 --> S3["3. AsrManager.warmUp()<br>・ONNX Transducerロード<br>・0.1秒無音ダミー推論"]
    end

    S1 -.->|READY (~550ms)| UI1["フリック・ローマ字入力 即時応答"]
    S2 -.->|READY (~2.8s)| UI2["SLM文脈先読み・リランク 即時応答"]
    S3 -.->|READY (~3.8s)| UI3["音声認識マイク 即時録音・推論"]
```

---

## 4. 実装詳細

### 4.1 各コンポーネントのウォームアップメソッド

#### (1) `KanaKanjiEngine.warmUp()`
```kotlin
@Synchronized
fun warmUp() {
    initializeIfNeeded()
    try {
        getBaseCandidates("きょう")
        Log.i(TAG, "KanaKanjiEngine warm-up completed successfully.")
    } catch (e: Exception) {
        Log.w(TAG, "KanaKanjiEngine warm-up query failed: ${e.message}")
    }
}
```

#### (2) `LlmReranker.warmUp()`
```kotlin
fun warmUp(scope: CoroutineScope = CoroutineScope(Dispatchers.Default), onComplete: ((Boolean) -> Unit)? = null) {
    if (!nativeLibraryLoaded || !isModelInstalled()) {
        onComplete?.invoke(false)
        return
    }
    if (isWarmedUp && state == State.READY) {
        onComplete?.invoke(true)
        return
    }

    initializeIfAvailable(scope) { ready ->
        if (ready) {
            scope.launch(Dispatchers.Default) {
                try {
                    val dummyCands = listOf(
                        ConversionCandidate("今日", "きょう"),
                        ConversionCandidate("教", "きょう")
                    )
                    rerank("<s>", dummyCands)
                    isWarmedUp = true
                    Log.i(TAG, "LlmReranker warm-up scoring completed successfully.")
                    onComplete?.invoke(true)
                } catch (e: Exception) {
                    Log.w(TAG, "LlmReranker warm-up scoring failed: ${e.message}")
                    onComplete?.invoke(false)
                }
            }
        } else {
            onComplete?.invoke(false)
        }
    }
}
```

#### (3) `AsrManager.warmUp()`
```kotlin
@Synchronized
fun warmUp(hotwordsFile: File? = null): Boolean {
    if (!isModelAvailable()) return false
    val ok = initialize(hotwordsFile)
    if (!ok) return false
    return try {
        val dummyAudio = FloatArray(1600) // 0.1秒の無音音声 (16kHz)
        transcribe(dummyAudio, 16000)
        Log.i(TAG, "AsrManager warm-up inference completed successfully.")
        true
    } catch (e: Exception) {
        Log.w(TAG, "AsrManager warm-up inference failed: ${e.message}")
        false
    }
}
```

### 4.2 `ModelWarmUpManager` の統括

`app/src/main/java/com/example/myasr/ModelWarmUpManager.kt` が一元的にライフサイクルを制御し、以下をトリガーします:
- `AsrApp.onCreate()`
- `VoiceInputMethodService.onCreate()`
- `VoiceInputMethodService.onStartInputView()`

---

## 5. 実機ログによる検証 (Verification Log)

ARCVM実機(`arc:5555`)での起動時のlogcat出力:
```text
10-25 18:25:48.761  7231  7231 I AsrApp  : AsrApp initialized. Shared ASR, IME & SLM components ready.
10-25 18:25:48.762  7231  7260 I ModelWarmUpManager: Starting non-blocking background model warm-up pipeline...
10-25 18:25:49.288  7231  7260 I KanaKanjiEngine: Base dictionary loaded successfully.
10-25 18:25:49.337  7231  7260 I KanaKanjiEngine: KanaKanjiEngine warm-up completed successfully.
10-25 18:25:49.337  7231  7260 I ModelWarmUpManager: KanaKanjiEngine warmed up in 575ms
10-25 18:25:49.344  7231  7261 I LlmReranker: Loading SLM model from /storage/emulated/0/Android/data/com.example.myasr/files/models/qwen2.5-0.5b-instruct-q4_k_m.gguf...
10-25 18:25:49.350  7231  7260 I AsrManager: Initializing ReazonSpeech model from Android assets (modified_beam_search)...
10-25 18:25:50.265  7231  7261 I LlmReranker: SLM model loaded successfully and ready for re-ranking.
10-25 18:25:52.176  7231  7265 I LlmReranker: LlmReranker warm-up scoring completed successfully.
10-25 18:25:52.176  7231  7265 I ModelWarmUpManager: SLM Reranker warm-up completed in 2839ms (status=READY)
10-25 18:25:53.102  7231  7260 I AsrManager: ReazonSpeech model successfully loaded from assets with modified_beam_search
10-25 18:25:53.145  7231  7260 I AsrManager: AsrManager warm-up inference completed successfully.
10-25 18:25:53.145  7231  7260 I ModelWarmUpManager: AsrManager warmed up in 3801ms (status=READY)
10-25 18:25:53.145  7231  7260 I ModelWarmUpManager: Total background warm-up pipeline execution initiated in 4383ms
```

- **メインスレッド (PID 7231, TID 7231)**: `AsrApp.onCreate()` は 1ms 以内に復帰し、UI描画は完全に妨げられません。
- **ワーカースレッド (TID 7260, 7261, 7265)**:
  - 575ms: かな漢字変換エンジンが先行ウォームアップ完了
  - 2,839ms: SLMリランカーがウォームアップ完了 (KVキャッシュ初期化済)
  - 3,801ms: ASR音声認識エンジンがウォームアップ完了 (無音推論済)
- 全てのモデルが、ユーザーの入力・マイク押下前にウォーム状態（マイクロ秒〜ミリ秒オーダーでの即時応答）へと移行完了しました。
