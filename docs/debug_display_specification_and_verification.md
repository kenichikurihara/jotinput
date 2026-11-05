# デバッグ表示機能 仕様書及び検証レポート (Debug Display Specification & Verification Report)

## 1. 概要 (Overview)

本機能は、JotInput の IME（音声およびフリック入力）において、リアルタイムの推論状態や内部処理状況を視覚的に確認するための汎用的な「デバッグ表示」オプションです。

最新仕様では、**本オプションが ON の際に、オンデバイス SLM（Qwen2.5-0.5B / AiCore 等）による候補再ランキング（SLMリランク）が実際に適用された場合、候補バーではなくデバッグバー（コンテクストバー）内のバッジ `tvDebugSlmBadge`（`[SLM]`, `[1.5B]`, `[n1]`, `[n2]` 等）に表示**されます。
従来の候補バー先頭へのダミーチップ挿入方式から、候補領域の幅を圧迫せず、入力・変換候補の視認性と操作性を妨げないデバッグバー統合型へと改善されました。

今後、他のデバッグ情報（Viterbi ビーム探索状態、ASR ストリーミング状態、音響モデル信頼度スコア等）を本オプションに集約・拡張できる汎用的な設計となっています。

---

## 2. 仕様 (Specifications)

### 2.1 設定項目
- **名称**: デバッグ表示
- **保存先**: `ime_preferences` (SharedPreferences)
- **キー名**: `debug_display` (`KeyboardHeightManager.KEY_DEBUG_DISPLAY`)
- **デフォルト値**: `false` (OFF)
- **UI配置**: `MainActivity` の「日本語入力キーボード設定」カード内（ストリーミング音声入力スイッチ直下）
- **説明文**: 「SLMリランク適用時にデバッグバーにバッジを表示し、推論文脈を表示」

### 2.2 表示仕様
1. **トリガー条件**:
   - `debug_display` が ON
   - かつ、文字入力時の変換候補生成時（または確定後の次句予測時）に、オンデバイス SLM（`LlmReranker` / `AiCoreReranker`）によるスコアリング・並び替えが**実際に実行・適用**された場合。
   - ※SLMモデル未ロード時、エラー時、単一候補時など SLM スコアリングがスキップされた場合は非表示（`View.GONE`）。
   - 新規文字入力開始、候補クリア、確定（コミット）、または選択移動時は即座に非表示（`View.GONE`）。
2. **バッジの外観と配置**:
   - 配置: デバッグコンテクストバー（`contextBarContainer`）内、`tvContextBadge`（例: `CTX: 12字`）の直後、`tvContextContent`（文脈テキスト）の直前。
   - テキスト: 稼働中エンジンの識別バッジ（例: `SLM`, `1.5B`, `n1`, `n2`）
   - スタイル: 角丸 4dp ピル形状背景（`@drawable/badge_background`）
   - 文字色: アクセントカラー（`@color/primary`、視認性の高いブルー）
3. **候補バーへの完全非干渉 (Zero Candidate Strip Interference)**:
   - 候補バー（`CandidateStripView`）には一切ダミーチップを挿入しないため、第1候補が常に最左端に位置し、タッチ操作やスワイプ、スペースキーによる変換確定の使い勝手を一切阻害しません。
4. **デバッグ表示 OFF 時の完全ゼロコスト化**:
   - `debug_display` が OFF の場合、`contextBarContainer` 全体が `View.GONE` となり、UI・メモリ・描画オーバーヘッドは完全にゼロになります。

---

## 3. 実装詳細 (Implementation Details)

### 3.1 `KeyboardHeightManager.kt`
- 設定キー `KEY_DEBUG_DISPLAY` を定義。
- `isDebugDisplayEnabled(context)` および `setDebugDisplayEnabled(context, enabled)` ヘルパーを提供。

### 3.2 `LlmReranker.kt`
- `rerankWithStatus(contextPrefix, candidates, weightAlpha): Pair<List<ConversionCandidate>, Boolean>` を新設。
  - SLM ネイティブ推論が成功し、実際にリランクが適用された場合にのみ第2要素として `true` を返却。
- 既存の `rerank` は `rerankWithStatus(...).first` を呼ぶ後方互換ラッパーとして実装。

### 3.3 `KanaKanjiEngine.kt`
- `rerankCandidatesWithStatus(contextPrefix, baseCandidates): Pair<List<ConversionCandidate>, Boolean>` を新設。
  - 空文脈時やフォールバック時を含め、SLM が実稼働したかを高精度に判定。

### 3.4 `CandidateStripView.kt`
- `setCandidates(candidates: List<ConversionCandidate>, debugBadge: String? = null)`
  - `debugBadge` が指定された場合、候補列の先頭に非クリッカブルな `TextView`（`isClickable = false`）を挿入。
  - `currentCandidates` には純粋な `candidates` のみを保持。

### 3.5 `VoiceInputMethodService.kt`
- デバッグバー（`contextBarContainer`）内に `tvDebugSlmBadge` をバインド。
- `updateDebugSlmBadge(badge: String?)` ヘルパーにより、`isDebugDisplayEnabled` かつ SLM リランク適用時（`slmUsed == true`）に該当エンジンのバッジ名を表示。
- フリック入力の候補リランク非同期処理（`updateCandidates`）および確定後の次句予測処理（`showNextPhrasePredictions`）において、SLM リランク完了時に `updateDebugSlmBadge(engineType.badgeLabel)` を実行し、候補バー（`candidateStripView`）には純粋な候補リストのみを描画。
- 候補リセット時や空ステータス描画時（`showEmptyStatus`）には自動で `tvDebugSlmBadge` が非表示（`View.GONE`）に同期。

### 3.6 `MainActivity.kt` & `activity_main.xml`
- `switchDebugDisplay`（MaterialSwitch）を追加。
- リアルタイムで設定の切り替えが可能。説明文にデバッグバー表示への移行を明記。

---

## 4. 品質検証及び非リグレッション確認 (Verification Results)

### 4.1 ユニットテスト
- `./gradlew testDebugUnitTest` を実行。
  - 新規テスト `DebugDisplayTest.kt`:
    - `testDebugDisplayPrefKey` (PASS)
    - `testRerankCandidatesWithStatusFallbackWhenUninitialized` (PASS)
    - `testCandidateStripIntegrityWithDebugBadge` (PASS)
    - `testSpaceCommitDoesNotCommitDebugBadge` (PASS)
  - `KeyboardHeightManagerTest.kt`: `testDebugDisplayPrefKey` (PASS)
  - **最重要品質契約**: `JapanesePunctuationEvaluationTest.kt` (67件の音声句読点・境界判定テスト) 100% PASS
  - プロジェクト内全ユニットテスト **100% 合格 (BUILD SUCCESSFUL)** を確認。

### 4.2 デュアル実機デプロイ (`./deploy.sh`)
- **Pixel 10 Pro (Wi-Fi: `192.168.86.46:38423`)**: インストール・権限付与・起動 正常完了。
- **ARCVM (ChromeOS: `arc:5555`)**: インストール・権限付与・起動 正常完了。
- 両ターゲットの logcat で `MainActivity`, `AsrManager`, `LlmReranker` がクリーンに初期化されることを確認。

### 4.3 実機スクリーンショット・挙動検証 (Pixel 10 Pro)
1. **デバッグ表示 ON 時**:
   - ひらがな入力（例: `か`、`うみの`）時に、SLM リランク完了と同時に候補列の先頭に `[SLM]` チップが表示されることを確認。
   - `[SLM]` チップをタップしても入力・コミットされず、入力中の文字が保持されることを確認（タップ不可の実証）。
   - 隣接する第1候補等のタップにより、正常に対象の単語がコミットされることを確認。
2. **デバッグ表示 OFF 時**:
   - `switchDebugDisplay` を OFF に切り替えると、SLM リランクが適用されても `[SLM]` チップは一切表示されず、通常の候補表示となることを確認。
