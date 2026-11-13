# Alloy 6 Formal Verification in JotInput

## 1. 導入の目的

JotInput は **DDD（境界付けられたコンテキストと `Judgement`）** と **DMN（決定表と Hit Policy）** によってドメインの意思決定を構造化した。さらに **Alloy 6（軽量形式手法 / SAT ベース有界モデル検査）** を導入し、実装や単体テストだけでは網羅しきれない**状態遷移の不変条件（Invariant）**や**決定表の網羅性・安全性**を数学的・網羅的に検証する。

---

## 2. 検証アーキテクチャ

- **Alloy エンジン**: `org.alloytools:org.alloytools.alloy.core:6.2.0`（`testImplementation`、Pure Java `SAT4J` ソルバを使用するためネイティブバイナリ不要・クロスプラットフォーム）
- **自動実行**: [`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) により `./gradlew testDebugUnitTest`（および GitHub Actions `Android CI`）のたびに全 `.als` モデルが自動検査される。
  - `check` アサーション: 反例が存在しないこと（`!solution.satisfiable()`）を検証。
  - `run` 到達可能性述語: モデルが過剰制約（vacuous）でないこと（`solution.satisfiable()`）を検証。

---

## 3. 形式仕様モデル一覧 (`app/src/test/resources/alloy/`)

### 3.1 [`voice_touch_arbitration.als`](app/src/test/resources/alloy/voice_touch_arbitration.als)
[`VoiceTouchInputArbitrator`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) のマルチモーダル状態遷移を `util/ordering[State]` でモデル化し、以下を検証する：
- **`SingleComposingOwnership`**: キーボード composing とストリーミング音声 composing が単一 `InputConnection` 上で同時に成立しないこと。
- **`UndoSafetyAfterVoice`**: 音声確定またはストリーミング音声入力中において `undoValid` が必ず `False` となり、Undo（`↶`）が音声テキストを破壊しないこと。
- **`PendingVoiceFlushedWhenIdle`**: キーボードセッション終了後（`OwnerNone`）に未フラッシュの `pendingVoice` が残留しないこと。
- **到達可能性（非空虚性）**: `canQueueVoiceAndFlush`, `canUndoSafelyAfterKeyboardCommit`。

### 3.2 [`dmn_decision_safety.als`](app/src/test/resources/alloy/dmn_decision_safety.als)
DMN 決定表（BC-2 / BC-8）の不変条件を検証する：
- **`TapSafeTableIsTotal`**: `TapSafeCandidateOrdering` があらゆる入力に対して一意のルールへ解決されること。
- **`NeverPinsWhenTop1UnchangedOrAbsent`**: 初期 Top-1 不在または同値時にピン留めしないこと。
- **`NeverPinsWithinWindowUnlessTouching`**: 100ms ウィンドウ内（非タッチ時）はピン留めしないこと。
- **`NeverPinsPartialOverFullSpan`**: 部分一致プレフィックスより完全スパン候補を優先すること。
- **`ClipboardNeverIntrudesDuringActiveInput`**: 入力中・音声アクティブ時にクリップボード提案が割り込まないこと。
