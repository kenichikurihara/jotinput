# JotInput 技術ドキュメント

> 🌐 **Language / 言語**: **日本語（このページ）** | [**English Version (`docs/index.en.md`)**](index.en.md)

**JotInput**（**J**apanese **O**n-device **T**ext **Input**）の公式技術ドキュメントです。JotInput は、ReazonSpeech・NVIDIA Parakeet・Sherpa-ONNX・Mozc ベースかな漢字変換・FUTO Swipe ニューラルスワイプ入力・オンデバイス SLM 候補リランキング（`llama.cpp`）を統合した、軽量かつ 100% オフライン・完全オンデバイス動作の Android 日本語＆英語音声＋フリック／QWERTY キーボード（IME）です。

---

## 💡 このプロジェクトの大切な動機：音声入力やスワイプ入力でもユーザー辞書を尊重する

### 背景と課題
一般的な IME やクラウド音声入力・スワイプキーボードでは、ユーザーが登録した単語（人名・社名・固有名詞・技術用語・略語など）は通常のフリック／タップ入力でしか参照されず、**音声入力やスワイプ入力ではユーザー辞書が無視されて誤認識・誤変換が繰り返される**という課題があります。

### JotInput の方針
このプロジェクトの最も大切な動機の一つは、**単一の永続ユーザー辞書（`custom_dictionary.json`）を、通常のフリック・タップ入力だけでなく、音声入力（日本語・英語）やスワイプ入力（FUTO Swipe）でも一貫して尊重すること**です。100% オンデバイス・プライバシー保護を維持したまま、「一度登録した自分の語彙が、どの入力方式を使ってもしっかり正しく出る」体験を中核に設計しています。

### 入力方式ごとのユーザー辞書尊重メカニズム
- **日本語音声入力（ReazonSpeech）**: デコード時のホットワード音響バイアス（[`HotwordsGenerator.kt`](../app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt)）＋デコード後の音韻・同音異義語置換（[`DictionaryPostProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/application/DictionaryPostProcessor.kt)）＋文脈対応解決（[`ContextAwareProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt)）＋ユーザー修正学習を組み合わせています。1,000文評価で再現率 **84.5%**・誤爆率（FPR）**0.0%** を達成しています（[`dictionary_solutions.md`](dictionary_solutions.md) / [`context_aware_evaluation_report.md`](context_aware_evaluation_report.md) / [`voice_correction_learning_design_and_evaluation.md`](voice_correction_learning_design_and_evaluation.md)）。
- **英語音声入力（NVIDIA Parakeet TDT-CTC 110M）**: 英語音韻（Metaphone/G2P）・読み上げ記号正規化・整列部分列マッチ・DMN ポリシー（[`EnglishUserDictDecisionModel.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt)）および強制 Stage-2 SLM 文脈ゲート（[`EnglishVoiceUserDictionaryResolver.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt)）により、Exact TWRR を 22.06% → **92.65%**（FPR **0.0%**）へ向上させています（[`Finding 61 & ADR-026`](findings_and_decisions.md#finding-61--adr-026-english-voice-user-dictionary-prioritization--forced-stage-2-slm-windowing)）。
- **英語スワイプ入力（FUTO Swipe）**: ネイティブ C++ の CTC Beam Search Trie に有効な登録語を動的マージ＆優先度ブーストしています（[`FutoSwipeEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt) / [`futo_swipe_engine.cc`](../app/src/main/cpp/swipe/futo_swipe_engine.cc)）。既定語彙に存在しない未知語（OOV）や大文字混じりの固有名詞（例: `JotInput`, `ReazonSpeech`, `Nakamura`）もスワイプ軌跡から直接 Top-1 出力できます（[`prd_and_design_unified_english_dictionary.md`](prd_and_design_unified_english_dictionary.md) / [`futo_swipe_qwerty_design.md`](futo_swipe_qwerty_design.md)）。
- **日本語フリック（かな漢字変換）＆英語 QWERTY タップ（スペル補正・補完）**: 同じユーザー辞書が [`KanaKanjiEngine.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) と [`EnglishSpellEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt)（SymSpell＋空間タイポ補正・前方一致補完）へ自動同期されます。
- **単一の永続辞書・日英シームレス共有**: [`DictionaryActivity.kt`](../app/src/main/java/com/example/myasr/DictionaryActivity.kt) の1画面で日英両方を管理できます。英単語は「よみ」1項目のみで登録でき、かな読み＋英語表記（例: `れいぞんすぴーち` → `ReazonSpeech`）を登録すれば日本語音声／フリックと英語音声／スワイプ／スペル補正のすべてに自動適用されます（二重登録不要）。また `custom_dictionary.json` はアプリ更新時も上書き・消去されません（[`prd_and_design_custom_dictionary_ui.md`](prd_and_design_custom_dictionary_ui.md)）。

---

## 📚 目次

> 🧭 **[トップレベル PRD — プロダクトビジョン・最重要 CUJ・North Star Metric (EIS)](prd_top_level.md)** — 全機能 PRD・評価セットが従属する最上位 PRD。統一トップレベルメトリック **EIS（有効入力速度, 意図どおり文字/分）** とリリースゲート（ガードレール）を定義
>   - 📊 [EIS ベースライン計測レポート (`docs/eis_baseline_report.md`)](eis_baseline_report.md)
>   - 🚀 [日本語フリック入力 (CUJ-2) ヘッドルーム分析と EIS 高速化ロードマップ (`docs/flick_eis_headroom_analysis.md`)](flick_eis_headroom_analysis.md)
>   - 🌌 [フリック入力 (CUJ-2/3) ヘッドルーム精密解剖と抜本的・画期的コンセプト構想書 (`docs/eis_radical_headroom_and_moonshot_concepts.md`)](eis_radical_headroom_and_moonshot_concepts.md)
>   - 🧪 [日本語フリック入力 100件ヘッドルーム評価レポート (`docs/flick_input_100_headroom_evaluation_report.md`)](flick_input_100_headroom_evaluation_report.md)
>   - 📐 [効用スコア（Utility Score）導入による予測変換最適化 デザインドキュメント＆総合評価プラン (`docs/utility_score_algorithm_design.md`)](utility_score_algorithm_design.md)
>   - 🌈 [フリック入力における候補多様性（Diversity）再設計とトップラインメトリック（EIS）改善提案書 (`docs/flick_diversity_topline_eis_improvement_ideas.md`)](flick_diversity_topline_eis_improvement_ideas.md)
>   - 🛠️ [詳細設計書 (DD): フリック候補多様性 P0 改善 (Idea D1 + D2 + D3) (`docs/dd_flick_diversity_p0_ideas.md`)](dd_flick_diversity_p0_ideas.md)
>   - ⚡ [日本語確定直後（ゼロクエリ）予測候補アルゴリズム改善とトップラインメトリック（EIS）向上設計書 (`docs/zero_query_next_phrase_prediction_eis_improvement_design.md`)](zero_query_next_phrase_prediction_eis_improvement_design.md)
>     - 📋 [詳細設計書 (DD): 日本語確定直後（ゼロクエリ）予測候補改善 Phase 1〜3 (`docs/dd_zero_query_next_phrase_prediction_phases.md`)](dd_zero_query_next_phrase_prediction_phases.md)
>
> 🎯 **[評価セット・評価方法 統合ガイド (Master Evaluation Index)](evaluation_suites_and_methods.md)** — リポジトリ内の全評価データセット・評価スクリプト・JVM単体テスト・C++ベンチマーク・形式検証の全体マップ

0. [ドメイン駆動設計（DDD）アーキテクチャ](ddd/README.md)
   - [コンテキストマップ＆統合パターン](ddd/context_map.md)
   - [境界づけられたコンテキストキャンバス（全8コンテキスト）](ddd/bounded_context_canvases.md)
   - [戦術的設計＆リファクタリング計画](ddd/tactical_design_and_refactoring_plan.md)

1. [システムアーキテクチャ](architecture.md)
   - 全体パイプラインとデータフロー
   - 音声録音サブシステム（[`AudioRecorder`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AudioRecorder.kt)）
   - ASR エンジン抽象化層（[`AsrManager`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt)）
   - UI とインタラクション・ライフサイクル（[`MainActivity`](../app/src/main/java/com/example/myasr/MainActivity.kt)）
   - モデルダウンロードとストレージフォールバック（[`ModelDownloader`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ModelDownloader.kt)）

2. [ReazonSpeech モデル＆オンデバイス最適化](reazonspeech_model.md)
   - ReazonSpeech コーパスとモデル選定の背景
   - Zipformer Transducer アーキテクチャ（`reazonspeech-k2-v2`）
   - 量子化戦略（int8 Encoder・float32 Decoder & Joiner）
   - メモリ使用量・CPU 負荷・RTF（Real-Time Factor）
   - [英語オンデバイス ASR モデル選定＆システム設計](english_asr_model_selection_and_design.md) — NVIDIA Parakeet TDT-CTC 110M（`CC-BY-4.0`、int8 約 126 MB、RTF 約 0.066、ネイティブ句読点・大文字化対応）

3. [セットアップ・ビルド・デプロイガイド](setup_and_development.md)
   - 前提条件（Java 21・Android SDK・Gradle）
   - モデルアセットの準備（`download_model.sh`）
   - デバッグ APK のビルド手順
   - ARCVM（ChromeOS Android コンテナ）および実機でのテスト方法
   - 便利な ADB コマンド＆トラブルシューティング

4. [ユーザー辞書ソリューション（日本語 ASR）](dictionary_solutions.md)
   - デュアルキー仕様：`(表記, よみ)` / `(Word, Reading)`
   - 2チャネル強化学習：ビーム探索内の音響バイアス＋音韻同音異義語置換
   - ひらがな・カタカナ自動展開
   - ユーザーデータモデルと UX 設計
   - 段階的実装ロードマップ

5. [ユーザー辞書実装計画](dictionary_implementation_plan.md)
   - コンポーネント詳細設計とデータフロー
   - Repository・HotwordGenerator・PostProcessor の実装手順
   - Android UI と動作検証ガイド

6. [ユーザー辞書改善＆評価計画](dictionary_improvement_plan.md)
   - ARCVM logcat ポストモーテムに基づく根本原因分析
   - トークナイズ空白区切り修正（`cjkchar` フォーマット）
   - 20ケース評価マトリクスによる定量評価計画（TWRR・CER・FPR・RTF）
   - 実装前レビューチェックポイント

7. [1,000文・難読評価レポート＆交差検証](evaluation_report_1000.md)
   - 日本語難読文 1,000件に対する包括的ベンチマーク
   - ハイパーパラメータ（$\alpha$）チューニングのための5分割層化交差検証
   - 導入前後比較：Baseline・欠陥 Hotwords・修正 Hotwords・ハイブリッドパイプライン
   - Precision・Recall・Accuracy・F1・FPR・RTF メトリクス
   - 人名 250件・同音異義語 250件・地名 150件・技術用語 200件・ネガティブ対照 150件の詳細内訳
   - アプリ更新を跨いだユーザー辞書永続性保証

8. [Android 音声キーボード（IME）開発計画＆仕様書](keyboard_development_plan.md)
   - Android システム標準 IME（`InputMethodService`）設計
   - ミニマルなキーボード UI：中央トグルボタン（話す / 完了）、`DEL`、`RETURN`、`、`、`。`、`🌐`
   - メモリオーバーヘッドゼロの ASR モデル共有コンテナ（[`AsrApp`](../app/src/main/java/com/example/myasr/AsrApp.kt)）
   - `InputConnection` イベントフローとライフサイクル管理
   - バックグラウンド IME サービス向けのマイク権限フォールバックフロー

9. [アーキテクチャ決定記録（ADR）＆技術的知見集](findings_and_decisions.md)
   - ADR（ADR-001〜ADR-026）の包括的記録
   - 重要技術ポストモーテム（Sherpa-ONNX `cjkchar` 空白バグ、IME 権限委譲、ARCVM ハードウェアキーボード抑制など）
   - 1,000文交差検証結果とハイパーパラメータ調整の知見
   - デュアルターゲット配備ポリシーと Wi-Fi デバッグ特性
   - Viterbi ラティス複数文節探索・ローカルモック乖離ポストモーテム・口語動詞縮約トラップ解消（Finding 17〜19）

10. [文脈対応音声認識＆キーボード拡張計画](context_aware_recognition_plan.md)
    - `InputConnection.getTextBeforeCursor(500, 0)` を活用した文脈参照アーキテクチャ
    - 意味的同音異義語曖昧性解消および異体字・難読漢字解決
    - 英単語境界の自動スペース挿入と節区切り句読点処理
    - 1,000文の文脈付き評価データセット生成＆評価計画

11. [文脈対応音声認識 評価レポート](context_aware_evaluation_report.md)
    - 日本語難読文 1,000件に対する比較ベンチマーク（Baseline vs. Hotwords vs. Hybrid vs. 文脈対応）
    - 全体再現率（Recall）が **84.5%** へ向上（Baseline 比 +30.5%、Hybrid 比 +3.9%）
    - 人名が **76.0%**（+9.2% 改善）、同音異義語が **96.0%**、技術用語が **77.5%** へ到達
    - ネガティブ対照での誤爆ゼロ（FPR: 0.0%）かつ追加遅延ゼロ（0.014 ms/発話）

12. [Google Play ストア公開ガイド](play_store_publishing_guide.md)
    - 公開までのエンドツーエンド・ライフサイクルと前提条件
    - 個人アカウント vs 組織アカウント、D-U-N-S 確認、必須の14日間クローズドテスト
    - 技術要件（App Bundle `.aab`、64bit ネイティブライブラリ、Play App Signing、R8）
    - ポリシー準拠（プライバシーポリシー、データセーフティ、アカウント削除、フォアグラウンドサービス）
    - ストアアセット仕様、リリース段階配信、Fastlane / GitHub Actions 自動化

13. [Gboard 風フリック専用＆英語 QWERTY キーボード設計計画](qwerty_and_gboard_flick_plan.md)
    - Pixel 10 Pro 上の Gboard フリック専用日本語レイアウトおよび英語 QWERTY レイアウトの実測分析
    - オプションの QWERTY 最上段数字行（`1–0`）トグルと `SharedPreferences` ライブ同期
    - 方向指定修飾フリック（`゛`、`゜`、`大⇔小`）、5方向句読点キー（`、。？！…`）、元に戻す（`↶`）、カーソル移動キー（`◀`、`▶`）
    - ゼロインセット `PopupWindow` による統一円形フリックポップアップと単一キー QWERTY コールアウト

14. [Mozc オンデバイス SLM/LLM 統合＆評価計画](mozc_ondevice_llm_integration_plan.md)
    - Mozc の局所解・文節境界制約（例: 「ことなります」vs「異なります」）の解消
    - ハイブリッド Viterbi N-best＋オンデバイス SLM 条件付き対数尤度リランキング・アーキテクチャ
    - 軽量モデル選定（Qwen2.5-0.5B、Qwen2.5-1.5B、Llama-3.2-1B、Zenzai 系 Transformer）
    - 10,000サンプル均衡評価データセット設計・データ取得パイプライン・Win/Loss 評価指標

15. [Phase 1 PoC 評価レポート（オンデバイス SLM リランキング）](phase1_poc_evaluation_report.md)
    - Intel Core i5 CPU 上での Qwen2.5-0.5B（GGUF INT4）の実証検証
    - 多肢選択プロンプトの位置バイアスと、トークン条件付き対数尤度スコアの数学的優位性の立証
    - 発端課題（「Mozcは私の直感と挙動が」＋「ことなります」→「異なります」、ひらがな比約40倍の確率）の決定的解決
    - 定量結果：100ms 未満の遅延で純 Top-1 改善 **+62.0%**（31勝0敗）

16. [Phase 2 大規模評価レポート（1,000層化サンプル）](phase2_scale_evaluation_report.md)
    - KWDLC・Tatoeba・同音異義語対照ペアから 10,000サンプル均衡データセットを自動構築するパイプライン
    - 1,000層化サンプルでの大規模統計ベンチマーク（434勝0敗、Win/Loss 比 434:1）
    - Web 記事・対話文・同音異義語マトリクス全体で純 Top-1 改善 **+43.40%**
    - 50ms 未満の単一クエリ遅延を検証し、Phase 3 C++ インプロセス統合への移行基準を達成

17. [Phase 3 C++ エンジン統合レポート（ネイティブ・インプロセス再ランキング）](phase3_cpp_integration_report.md)
    - Mozc の `RewriterInterface` に準拠した `LlmReranker` / `LlmRewriter` の C++ 直接実装
    - 高速 mmap モデル読み込み（< 210 ms）を備えた `libllama`（`llama.cpp`）の統合
    - 結合コストスケーリング式：$\text{Score} = \alpha \cdot (\text{Cost}/1000) + (1-\alpha) \cdot (-\text{LogProb})$
    - ネイティブ E2E テストスイート合格率 100%（7/7）、「異なります」「移動」「交渉」「記者」「貴社」を 100 ms 未満で上位昇格

18. [Phase 4 計画：本番品質 KV キャッシュ最適化・複数トークン曖昧性解消・非ブロッキング設計](phase4_optimization_and_production_plan.md)
    - `llama_memory_seq_rm()` ロールバックを用いた階層的プレフィックス KV キャッシュによる 20ms 未満のキー入力応答
    - 2段階適応型曖昧性解消：高速パス（追加 0 ms）vs 複数トークン決着（「決裁」vs「決済」で 16 ms）
    - 非同期・非ブロッキング UI 応答性のための文脈プレフィックスハッシュ（FNV-1a）と世代カウンタ
    - カーソル飛び・バッファオーバーフロー・マルチバイト境界エラーに対する本番堅牢化

19. [Phase 4 実装レポート：本番 KV キャッシュ再利用＆サブミリ秒 IME リランキング](phase4_implementation_report.md)
    - 実測キー入力遅延を約 70–100 ms から **0.80 ms** へ短縮（85倍超の高速化）
    - 64bit FNV-1a 文脈無効化判定と `llama_memory_seq_rm()` による投機的トークンロールバック
    - 本番テストスイート合格率 100%（4/4：発端ケース、「状況」vs「上京」、ライブタイピング模擬、「交渉」）

20. [オンデバイス SLM リランキング Android 統合＆実機検証レポート](ondevice_slm_android_integration_report.md)
    - Android NDK C++ JNI ネイティブ統合（`llama.cpp` を静的リンクした `libmyasr_llm.so`）
    - Kotlin IME パイプライン E2E 統合（`LlmReranker.kt` ＋ `KanaKanjiEngine.kt` ＋ `VoiceInputMethodService.kt`）
    - ARCVM 実機 logcat 検証：`ことなります` → `異なります`（Top-1）、`じょうきょう` → `状況`（Top-1）
    - ユーザー辞書契約の厳守とモデル未配置時のゼロコスト・フォールバック

21. [IME キー入力レイテンシのオフライン評価＆性能設計](ime_latency_and_offline_eval_plan.md)
    - 2段階非同期候補パイプライン（1ms 未満の即時同期高速パス＋非同期コルーチン SLM リランキング）
    - 高速タイピング中の古いキー入力コルーチンジョブのキャンセル制御
    - 候補プールの上位 $K=8$ への枝刈りと複数トークン評価上限
    - スタンドアロン・ネイティブ Linux キー入力ベンチマークハーネス（[`tools/bench_ime_latency.cc`](../tools/bench_ime_latency.cc)）
    - 実機（Pixel 10 Pro）における UI スレッドフリーズ・ANR の完全解消

22. [長文節・複数文節変換 ヘッドルーム分析レポート](long_phrase_conversion_headroom_report.md)
    - 長文日本語フレーズ（8かな以上）3,672件に対するヘッドルーム分析
    - 実測品質：現行 Top-1 精度 33.1% に対し **SLM 潜在精度 92.4%**（+59.3% の改善余地）
    - 4つの失敗モード分類（文節境界誤り・助動詞破損・敬語誤分割・先頭トークン衝突）
    - オンデバイス Qwen2.5-0.5B による $+19.5 \sim +33.6$ nats 対数尤度優位性の数学的証明

23. [長文節変換に対する5つの改善アイデア評価レポート](long_phrase_improvement_ideas_and_evaluation.md)
    - 5提案の詳細評価：Trie N-Best Viterbi、全系列 SLM、口語遷移フィルタ、適応的チャンク分割、投機的オートコンプリート
    - 連続長文フレーズ 500件での比較評価マトリクス（1.0% Baseline $\to$ 20.2% Viterbi＋ペナルティ $\to$ 92.4% ハイブリッド）
    - [`KanaKanjiEngine.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) へのインメモリ Viterbi ラティス・ビーム探索＆口語遷移ペナルティ実装
    - [`LongPhraseConversionTest.kt`](../app/src/test/java/com/example/myasr/LongPhraseConversionTest.kt) によるサブミリ秒実行予算検証

24. [文脈なし（Context-Free）SLM 候補リランキング評価レポート](context_free_slm_reranking_evaluation.md)
    - 先行文脈が空（`textBeforeCursor.isBlank()`）の場合に SLM リランキングを発火させた際の品質・遅延評価
    - 実測比較：Baseline Trie（Top-1 86.7%、異常出力 0.0%）vs 文脈なし SLM（Top-1 30.0%–35.0%、異常出力 30.0%–35.0%）
    - 単体 SLM が一般語よりネットスラング（`作業厨デスので` 等）を好むユニグラム事前分布退化の発見
    - レイテンシ比較：Warm 20–34 ms / Cold 50–280 ms vs Trie 高速パス 0.2 ms
    - 非空文脈（$|C| \ge 1$）限定ゲーティングのアーキテクチャ的根拠

25. [文脈なし SLM リランキング実装＆設定トグル](findings_and_decisions.md#finding-21-context-free-slm-re-ranking-implementation--toggle-settings)
    - 大規模評価知見：1,000ケースにおいて文脈なし SLM（26.10%）が Mozc Baseline（12.60%）を +13.50% 上回る結果を確認
    - `libmyasr_llm.so` での BOS（`<s>`）KV キャッシュによるオンデバイス実装
    - アプリ内トグルスイッチ（`switchSlmContextFree`）および `ime_preferences.xml` 永続化
    - 文脈あり限定モードと常時（文脈なし含む）モードのシームレスなライブ切替

26. [SLM 重み $\alpha$ 最適化＆5分割交差検証レポート](slm_alpha_cross_validation_report.md)
    - 1,000サンプルに対する $\alpha \in [0.00, 1.00]$ の系統的5分割交差検証
    - 収束の実証：全5分割で独立して最適値 $\alpha^* = 0.00$ を選択
    - Out-of-Fold (OOF) 精度は 文脈ありで **66.80%**、文脈なしで **51.10%** に到達
    - 感度曲線全体と本番運用推奨正則化値（$\alpha \approx 0.05 \sim 0.10$）の分析

27. [全ひらがな候補のランキング・表示からの完全除外](findings_and_decisions.md#finding-23-exclusion-of-all-hiragana-candidates-from-conversion-ranking-and-display)
    - 候補生成・SLM リランキング・UI 候補バーからの全ひらがな候補の完全排除
    - 設計理由：未確定文字列（Composing text）自体が画面上に生のひらがなとして描画されており、Return/Enter キーで変換せずそのまま確定できるため
    - 4層多層防御：`JapaneseKanaUtils.isAllHiragana()`、`KanaKanjiEngine` 候補＆ラティスフィルタ、`LlmReranker` 事前計算フィルタ、`CandidateStripView` 表示制御
    - 検証：単体テスト全23タスク合格、Pixel 10 Pro および ARCVM へのデュアルターゲット配備・実機確認済み

28. [ゼロクエリ次フレーズ継続予測レポート＆アイデア評価](next_phrase_prediction_ideas_and_evaluation.md)
    - 7つの設計観点（ユーザーバイグラム履歴、定型連語、助詞継続、オンデバイス SLM 生成、文脈・時間ヒューリスティクス、クラウド LLM、多層ハイブリッド）の網羅的検討・評価
    - [`NextPhrasePredictor.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt) における**階層型ハイブリッドアーキテクチャ**の正式採用と実装
    - 永続学習・定型会話アンカー・助詞サジェストを備えたサブミリ秒実行（$< 20\text{ µs}$）
    - [`VoiceInputMethodService.kt`](../app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) への統合と単体テスト網羅

29. [音声句読点復元＆途切れのない文境界自動入力](voice_punctuation_and_sentence_boundary_input.md)
    - 音声コマンド（「まる」「てん」「かいぎょう」）および文末自動句点（「です」「ます」）を扱う [`JapanesePunctuationEngine.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt) の実装
    - 語彙誤爆防止マスキング（例：天気、天然、満点、交差点、重点、まるで、一丸、日の丸）
    - マイクロポーズ確定（200ms）および連続発話ペーシング確定（2400ms）による、意図的な休止不要の文境界確定

30. [句読点＆音声境界 評価基準・ベンチマークスイート](punctuation_and_voice_boundary_evaluation_criteria.md)
    - 7カテゴリ・67件の単体テストケース（[`JapanesePunctuationEvaluationTest.kt`](../app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt)）に基づく包括的評価基準と仕様

31. [ストリーミング音声入力アーキテクチャ、連続インプレース入力＆動的プレフィックス書き換え](streaming_voice_input_architecture_and_deliberation.md)
    - リアルタイム連続ストリーミングと従来のポーズ確定方式を比較した深い設計検討
    - 文末（句点）を待たずにアクティブな未確定スパンでエディタ上へ逐次描画する連続インプレース入力
    - 動的プレフィックス書き換え：後続文脈の到着に応じて先行語（プレフィックス）を自動修正（例：「移動」$\to$「異動願い」、「です。」$\to$「ですから、」）
    - ホライズン型動的スライディングウィンドウ：`setComposingText()` 内に修正可能ホライズンを保ちつつ、安定した過去の文を順次 `commitText()` で凍結確定
    - 厳格な排他制御：ストリーミングモード有効時はポーズ区切り分割（`microPauseDurationMs`、`silenceDurationMs`）を自動無効化
    - 連続インプレース入力・プレフィックス書き換え・複数文ストリーミング・非回帰保証を網羅するベンチマーク設計

32. [上限タイムアウト付き強制 SLM リランキング：設計＆レイテンシ評価](slm_mandatory_rerank_design_and_latency.md)
    - 上限タイムアウト付き強制リランキング（デフォルト ON）による、Fast-Path 候補ちらつきおよび Space 確定時の SLM バイパスの解消
    - アドホックな単一語彙コスト調整を禁止する厳格な反逸話的チューニングポリシー
    - Mozc コミュニティコーパス CRF 最適化（$POS \times POS$ 連接行列＋負の対数尤度）の分析
    - モバイル実測レイテンシ評価：通常 SLM 推論時間 22–38 ms に対する 100 ms フェイルセーフ予算の妥当性検証

33. [Mozc 2D 連接コスト行列（`connection_single_column.txt`）評価レポート](connection_matrix_evaluation_report.md)
    - Mozc 2D 連接コスト行列（$2672 \times 2672$、714万遷移）の有無による定量比較評価
    - 数学的遷移メカニズム：文頭 $\to$ 付属名詞ペナルティ（$+5,841$）により「生みのまわりに」を排除し「海の周りに」を選好
    - Mozc OSS CRF コーパスから系統的に導出した 5列品詞（POS）辞書スキーマ強化（`base_dictionary.tsv` は `tools/mozc_dict/` 下で生成しオンデマンドダウンロード、APK 非同梱）
    - 定量成果：会話スイートで Top-1 (+2.0%)、Top-3 (+2.0%)、MRR (+0.024) 向上、公式 Mozc OSS ベンチマークで合格率 +1.47%（+9文）向上
    - ゼロオーバーヘッド仮想メモリマップにより追加遅延はわずか $+0.074\text{ms}$

34. [強制 SLM リランキング 性能＆曖昧性解消 評価レポート](slm_mandatory_rerank_evaluation_report.md)
    - 強制 SLM リランキング vs Mozc 連接コスト行列ベースラインの包括的比較評価
    - 文脈付き同音異義語曖昧性解消（50問）：Top-1 精度が **42.0% $\to$ 80.0%（+38.0% 絶対改善）**、MRR が **0.677 $\to$ 0.858（+0.181）** へ飛躍
    - 2D 連接行列の数学的限界：同一品詞の同音異義語（`移動` [1841] vs `異動` [1841]、`回答` [1841] vs `解答` [1841]）ではバイグラム文法のみではコスト差が 0 になることを実証
    - トークン長正規化による複数トークン投機的スコアリング長ペナルティバイアスの発見と修正
    - $\alpha \in [0.00, 1.00]$ 感度分析とモバイル実測レイテンシ評価（$500\text{ms}$ タイムアウトに対し $\le 108\text{ms}$）

35. [デバッグ表示仕様＆マルチターゲット検証レポート](debug_display_specification_and_verification.md)
    - 「デバッグ表示」トグルスイッチの仕様および動作検証レポート
    - オンデバイス SLM リランキング適用時に [`CandidateStripView`](../app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt) へ非クリック可能な `[SLM]` ダミーチップを表示
    - 候補リスト非干渉の保証と、Pixel 10 Pro および ARCVM でのデュアルターゲット検証

36. [オンデバイス・ハードウェアアクセラレーション分析＆最適化レポート](ondevice_hardware_acceleration_analysis_and_optimization.md)
    - ASR（Sherpa-ONNX / Zipformer）および SLM（llama.cpp / Qwen2.5-0.5B）のオンデバイス HW アクセラレーション（GPU / NPU / CPU SIMD）の全面監査
    - arm64 ネイティブビルドで NDK アーキテクチャフラグ未設定のため DotProd（`sdot`/`udot`）および FP16 ベクトル命令が欠落していた問題の特定
    - `armv8.2-a+fp16+dotprod` と `-O3` の明示的有効化（1,072個の SIMD DotProd 命令生成）、x86_64 向け AVX2/FMA 有効化、および ASR 動的スレッドスケーリング（2–4コア）
    - 短系列対数尤度計算において NNAPI（Android 15 で非推奨）やモバイル GPU オフロード（ディスパッチ遅延＞計算時間）より最適化 CPU SIMD の方が高速である理由の詳細分析

37. [Qwen2.5-1.5B モデル選定＆定量評価レポート](qwen_1_5b_model_selection_and_evaluation_report.md)
    - `Qwen2.5-0.5B`（469MB）と並べて選択可能な上位 SLM（`Qwen2.5-1.5B`、1.1GB）の仕様と UI 設計
    - 100件評価ベンチマーク（Suite 1: 文脈付き同音異義語、Suite 2: コロケーション）での定量評価
    - 同音異義語 Top-1 精度の +8.0% 向上（80.0% $\rightarrow$ 88.0%）および Top-3 精度の向上（90.0% $\rightarrow$ 94.0%）を実証
    - シームレスな動的モデル切替、Pixel 10 Pro / ARCVM 実機検証、レイテンシ・トレードオフ分析

38. [予測前方一致検索ロジック解説](predictive_prefix_matching_logic.md)
    - 予測候補検索（例：「ぱ」$\rightarrow$「パン」）の包括的調査ドキュメント
    - デュアルパイプライン解析：SQLite マルチ辞書 B-Tree 範囲クエリとインメモリ Trie 深さ優先前方一致展開（`collectPrefixWords`）

39. [濁音・半濁音ファジー前方一致検索 設計＆評価レポート](voicing_fuzzy_prefix_search_design_and_evaluation.md)
    - 分岐 Trie 探索、O(1) 音素配列枝刈り、非対称清濁マッピング、BFS 予測展開の詳細設計議論
    - 清音入力での候補欠落問題（例：「かいき」$\rightarrow$「会議」、「すーは」$\rightarrow$「スーパー」）の解消
    - 20ケース評価マトリクスで合格率 100%、サブ 0.2ms レイテンシ（$0.188\text{ms}$）、音声句読点非回帰 100% を達成

40. [Mozc UT 辞書ベストプラクティス調査・辞書選定報告](mozc_ut_dictionary_best_practices.md)
    - Mozc コミュニティにおける UT 辞書（`merge-ut-dictionaries`）の更新活発度ダブルチェック結果（2026年11月最新更新実績）
    - 採用辞書（`SudachiDict`, `jawiki`, `personal-names`, `place-names`）と不採用辞書（休眠状態の `NEologd`、古典辞書）の明確な根拠
    - コミュニティの標準マージ・重複排除および Wikipedia インデックスによる客観的コスト調整アルゴリズム

41. [辞書システム再設計仕様書 (System Design Document)](dictionary_system_redesign.md)
    - 3層ハイブリッド辞書アーキテクチャ（Canonical / Base Dictionary 13.1万語 / SQLite Multi-Dictionary Schema v2）
    - 2回以上の厳格なセルフレビューと品質・非回帰契約の検証記録
    - `build_base_dictionary.py` による自動ビルドパイプラインと実機配備結果

42. [ユーザー入力履歴の活用アーキテクチャ仕様書](user_input_history_architecture.md)
    - 確定単語頻度履歴（Unigram Selection History）、フレーズ遷移バイグラム（Bigram History）、エディタ先行文脈（Preceding Cursor Context）の3層構造
    - 各履歴の記録・永続化（`ime_history.json` / `next_phrase_cache.json`）と高速サジェスト・リランキングへの適用メカニズム

43. [音声入力独自アルゴリズム・ヒューリスティクス技術仕様書](voice_input_algorithms_and_heuristics.md)
    - 音響 VAD、デコード Hotwords、文脈後処理、句読点復元、IME 調停キューの5層独自アーキテクチャ

44. [音声入力独自アルゴリズム評価基準・評価セット仕様書](voice_input_evaluation_methodology_and_criteria.md)
    - 独自機能ごとの KPI、Positive 再現率・Negative 特異度基準、評価データセットの設計方針

45. [音声入力独自アルゴリズム評価結果レポート](voice_input_evaluation_report.md)
    - 142+件のテストケースに対する全機能ベンチマーク結果（合格率 100%）

46. [ストリーミング音声入力における確信度ベース・プレフィックス確定技術仕様・評価手法および比較レポート](streaming_prefix_freeze_solutions_and_evaluation.md)
    - 長文ストリーミング入力時のフリッカー抑制と確信度プレフィックス確定（Freeze / Partial Commit）に関する3つのアイデア、評価メトリクス、評価セット構築法、シミュレーション比較評価および推奨アーキテクチャ

47. [音声入力におけるフィラー（「あー」「うー」「えーと」等）自動除去：3つのアイデア提案・評価指標・評価セットおよび比較検証レポート](filler_removal_ideas_and_evaluation.md)
    - 「あー」「うー」「えーと」「あのー」等のフィラー除去に関する3つのアーキテクチャ案（境界制約＆保護語彙＋LCP 保留、Mozc 品詞・2D 連接コスト識別、音響プロソディ＆オンデバイス SLM 尤度ゲート）、6つの評価メトリクス、全5カテゴリ・120件の評価セット仕様、および2段階カスケード推奨方式の比較検証レポート

48. [オンデバイス SLM 再ランキング：バッテリー消費・電力ベンチマークおよび最適化レポート](slm_battery_and_power_consumption_report.md)
    - Pixel 10 Pro (Tensor G5) 実機 ODPM サブシステムおよび CPU 時間によるバッテリー消費の実測評価（リランク OFF vs Qwen2.5-0.5B / 1.5B）、`cached_context_logits_` による KV キャッシュヒット 7.0倍高速化・省電力化（-84%）、およびモバイル IME 業界基準との比較

49. [UI 設計原則とシンプルさ維持ガイドライン (UI Design & Simplicity Guidelines)](ui_design_and_simplicity_guidelines.md)
    - 機能・UI の精査と4階層段階的開示（Progressive Disclosure）アーキテクチャへの統合仕様、および今後機能を追加する際に UI の煩雑化を防ぐための5大原則（4カード上限、最適デフォルト優先、アセット一元管理、IME ミニマリズム、不要 UI 即時撤去）

50. [音声入力×タッチ（フリック／キー）併用時のコーナーケース網羅分析・自然挙動定義・アーキテクチャ設計書](voice_and_touch_interaction_corner_cases.md)
    - 音声入力（ストリーミング随時入力／VAD ポーズ区切り）中に確定ボタン・変換・フリック入力・バックスペース・濁点修飾・Undo・カーソル移動・ペースト・外部カーソル移動を行った際の全7カテゴリ・24コーナーケースの網羅的列挙、自然なユーザー体験の定義、全順序時系列保証モデル（$\text{Session}(S) \prec \text{Speech}(S) \prec \text{CommitsAfter}(S) \prec \text{Session}(S+1)$）、および [`VoiceTouchInputArbitrator.kt`](../app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) の実装・単体テスト仕様

51. [「けっか（結果）」「さくじょ（削除）」リランク順位低下バグの根本原因分析・3つの解決策提案および既存評価セットでの定量比較レポート](rerank_nano2_kekka_sakujo_debug_and_ideas.md)
    - Gemini Nano 2 選択時（`AiCoreReranker` → `LlmReranker` フォールバック + $\alpha=0.00$ + Context-Free `<s>`）に「けっか」で「結果」が1位から転落し、「さくじょ」で「削除」が8位（最下位）に転落する5つの複合的根本原因（Stage-1 先頭トークン近視眼・Stage-2 Top-2 打ち切り・トークン数除算によるサブワード細分化バイアス・キメラ候補/超稀少語の無差別プール混入）の解剖、3つの解決策アイデア（完全系列結合対数尤度・Mozc コスト較正事前分布＋文脈適応ゲーティング・候補プール構造フィルタリング）、および既存4評価セット（計 1,612 ケース）での実証評価レポート

52. [FUTO Keyboard スワイプ入力モデル統合設計書 (FUTO Swipe Typing Design Document)](futo_swipe_qwerty_design.md)
    - FUTO Keyboard の空間 1D-CNN/DFSMN 音響モデルおよび ContextLM を英語 QWERTY レイアウトに統合するための設計書。完全クリーンルーム ONNX Runtime C++ エンジン、フラット配列 Trie による CTC ビームサーチ、ARM NEON / x86_64 AVX2 SIMD ハードウェア高速化仕様、および音声・タッチ調停アーキテクチャの定義

53. [FUTO Keyboard スワイプ入力モデル Phase 1 評価ベンチマークレポート](futo_swipe_phase1_evaluation_report.md)
    - 文脈付き実スワイプデータ 1,000サンプルにおける空間モデル単体（Top-1: 89.0%, 3.08ms）と文脈 LM 連動（Top-1: 90.3%, 4.19ms）の定量ベンチマーク評価、およびオンデバイス実用性の実証レポート

54. [統一英語・日本語ユーザー辞書 PRD & システム設計仕様書](prd_and_design_unified_english_dictionary.md)
    - 日本語と英語の統合ユーザー辞書管理、英語入力時の単一よみフィールド UI（表記自動非表示）、FUTO Swipe C++ Trie 動的マージ＆優先ブースト、および QWERTY 空間タイポ・SymSpell ミススペル訂正候補の完全仕様

55. [LM-First リランキングにおける部分一致候補の順位逆転現象（「さいしょの」→「最初」＞「最初の」）の数学的解剖とアルゴリズム改善設計書](lm_first_partial_prefix_ranking_analysis_and_solutions.md)
    - 自己回帰言語モデルの連鎖律による部分候補（`viterbi_prefix`, $m < n$）と全文候補（$m = n$）の NLL 単調性・未消費読みスパン欠落の解剖、BPE トークン長正規化（Finding 37）と読みモーラ長スパン整合の理論的区別、および5つのアルゴリズム改善案（未消費読み生成補完・包含優越ゲート・ホライズン層別リランキング・後方 LSE 周辺化統合・期待解決モーラ効用）の設計仕様

56. [ASR モデル非依存の自動句読点挿入アーキテクチャ・アイデア提案書](asr_agnostic_punctuation_insertion_ideas.md)
    - CTC/Transducer 系（句読点なし）や Seq2Seq/Whisper 系（記号揺れ・句点欠落）など任意の ASR モデルに依存せず利用できる4大普遍シグナル（表層正規化、Mozc 形態素・2D 連接行列、VAD ポーズ長＆終端ピッチ $F_0$、オンデバイス SLM ロジット）の整理、6つのコアアイデア（チャンク間遡及境界リライト、読点密度予算モデル等）、および5段階ハイブリッド・カスケード設計仕様

57. [未消費スパンの言語モデル評価と言語モデル下での多様性確保に関する設計書 (LM Evaluation of Unconsumed Spans & Diversity-Preserving Reranking)](lm_unconsumed_span_evaluation_and_diversity_design.md)
    - 静的スパンペナルティ（ADR-020）でも「さいしょの」が解決しなかった根本原因（未消費スパンの LM 未評価＋`prioritizeByHistory` による部分候補の Rank 0 昇格＋2フェーズ Tap-Safe ピン留め＋フラットソートによる多様性破壊）の解剖、後方 Viterbi 最良接尾辞を用いた仮想全文補完 LM 評価（$\widetilde{W}(U) = U \oplus V^*_{m:n}$）、後方ラティス LSE 周辺化エントロピーボーナス（$-\tau_{\text{branch}} \ln M_{\text{eff}}$）、および非 LM 時の多様性議論（[`prd_and_design_viterbi_nbest_diversity.md`](prd_and_design_viterbi_nbest_diversity.md) / [`prd_and_design_multi_length_prefix_diversity.md`](prd_and_design_multi_length_prefix_diversity.md)）を LM 事後確率空間へ一般化した2次元 MMR・劣モジュラ多様性選択＋後続エントロピー適応型層化スロットインターリーブの設計仕様

58. [評価セット・評価方法 統合ガイド (Master Evaluation Index)](evaluation_suites_and_methods.md)
    - リポジトリ内の全7ドメイン（日本語 ASR・音声入力 UX/句読点・音声×タッチ調停/Alloy 形式検証・かな漢字変換/Mozc 連接行列/N-Best 多様性・SLM リランキング/LM-First・英語 Swipe/補完/スペル・実機 ODPM 電力/レイテンシ）の評価データセット、スクリプト、C++ ベンチマーク、JVM 単体テスト、実行コマンド、および運用ルールを統合整理したマスターインデックス

59. [ユーザー辞書（単語登録）UI 刷新 PRD & 形式設計 (Custom Dictionary UI PRD & Alloy Spec)](prd_and_design_custom_dictionary_ui.md)
    - JTBD と 5つの CUJ に基づく Zero-Friction 登録フォーム（表記先行・英語1ステップ自動判定・カタカナ読み自動提案）、リアルタイム検索フィルタ、ボトムシート編集・クラウド同期分離、即時削除＋Snackbar Undo、および [`custom_dictionary_ui.als`](../app/src/test/resources/alloy/custom_dictionary_ui.als) による状態整合性検証

60. [メインアプリ UI 刷新 PRD & 形式設計 (MainActivity v2 — 3-Tab + DMN Smart Guide)](prd_and_design_main_app_ui.md)
    - 従来の4カード縦長制約を解消し、JTBD・CUJ から再設計した3タブ構成（ホーム / 設定 / データ）、[`MainAppUiDecisionModel`](../app/src/main/java/com/example/myasr/context/ime/domain/MainAppUiDecisionModel.kt)（DMN 決定表3種）、および [`main_app_ui_state.als`](../app/src/test/resources/alloy/main_app_ui_state.als) による状態遷移形式検証

61. [オンボーディングフロー PRD & 形式設計 (Onboarding Flow — 初回・再インストール・Google Drive 履歴復元対応)](prd_and_design_onboarding_flow.md)
    - 初回インストール・再インストール（Android Auto Backup 権限失効検知）・Google Drive 履歴復元に対応する非ブロッキング型オンボーディングの PRD、4つの JTBD、4つの CUJ、12項目のコーナーケース対策、[`OnboardingDecisionModel`](../app/src/main/java/com/example/myasr/context/ime/domain/OnboardingDecisionModel.kt)（DMN 決定表3種）、および [`onboarding_flow.als`](../app/src/test/resources/alloy/onboarding_flow.als) による形式検証仕様

62. [Google Drive 履歴同期の設定画面統合 & 定期バックグラウンド同期 PRD & 形式設計](prd_and_design_google_drive_sync_settings.md)
    - 設定タブからの常時連携・同期先変更・即時同期・解除と、`JobScheduler` による定期バックグラウンド同期継続のための PRD、4つの JTBD、4つの CUJ、コーナーケース対策、[`HistorySyncDecisionModel`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/HistorySyncDecisionModel.kt)（DMN 決定表2種）、および [`history_sync_settings.als`](../app/src/test/resources/alloy/history_sync_settings.als) による形式検証仕様

63. [英語 QWERTY Truecasing & 大文字化候補 PRD・CUJ・アルゴリズミック設計・定量評価レポート](prd_and_system_design_qwerty_capitalization_and_spell_candidates.md)
    - 静的ヒューリスティクスを排したコーパス由来 $P_{\text{lex}}(s \mid w)$、OOV アクロニム検出用 2-state 文字バイグラム言語モデル、Punkt 型確率的文境界検出器（`SentenceBoundaryDetector`）、およびベイズ混合ケースランキング（`BayesianCaseRanker`）によるタップ・タイポ・FUTO Swipe 統合設計、CUJ-1〜7、および 60 ケース定量評価結果（Top-1 93.3%, Top-3 98.3%, 平均 0.108 ms）

64. [音声入力結果の修正検知・学習およびスコアブースト設計・評価仕様書 (Voice Correction Detection, Personalization & Score Boosting Design and Evaluation)](voice_correction_learning_design_and_evaluation.md)
    - 日本語（ReazonSpeech Transducer）および英語（Parakeet TDT-CTC 110M）の音声入力結果に対するユーザーの修正検知5案（操作トレース、Myers Diff スナップショット差分、音韻・G2P 妥当性ゲート、形態素・単語境界拡張、ストリーミング介入フック）、スコア引き上げ5案（動的 Hotwords 昇格、誤認識ペア×文脈 BM25 リスコア、SLM 文脈コヒーレンス・ゲート融合、英語 Truecase/Spell 横断適応、3段階昇格＋逆修正自己修復）、DDD/DMN/Alloy 形式設計、および3層評価ベンチマーク仕様

65. [デバッグ報告書：「デザインドキュメントを描いて」→「書いて」の修正が次回音声入力に反映されない原因分析](debug_voice_correction_kaite_root_cause.md)
    - 音声修正学習が「描いて」→「書いて」で機能しない5つの根本原因（RC-1: ストリーミング経路で `VoiceCorrectionScoring` 未呼出、RC-2: `readingResolver == null` による `fallbackJapaneseSurfaceDistance("描","書") == 1.0f` 棄却、RC-3: ストリーミング凍結による `VoiceCommitAnchor` 分断、RC-4: 送り仮名を含まない1文字スパン抽出、RC-5: 同音動詞の SLM ゲート未保護）のトレースと修正方針

66. [オンデバイス LLM/SLM レイテンシ・ヘッドルーム分析と高速化アイデア設計書 (SLM Latency Headroom Analysis & Optimization Ideas)](slm_latency_headroom_and_optimization_ideas.md)
    - `LlmReranker.kt` / `llm_reranker.cc` (`llama.cpp` バックエンド) における5大レイテンシボトルネック（Stage 2 の候補ごと直列 `llama_decode` による DRAM 帯域浪費、潜在格助詞バリアント重複デコード、`ContextWindowTrimmer` 先頭シフトによる LCP=0 キャッシュ全破棄、15.2万語彙 LM Head 射影、Little コア混入によるバリア同期ストラグラー遅延）の解剖と、レイテンシを $1/3 \sim 1/10$（平均 $25\sim 105\text{ms} \to 1\sim 12\text{ms}$）へ短縮する10個の改善アイデア（Prefix-Tree Packed Single-Pass Decoding、RoPE Position-Shift Ring KV Cache、Prior-Aware Admissible Lower Bound、Sparse LM Head、In-Flight Speculative Context Warming 等）の設計仕様

67. [コードベース内ヒューリスティクス網羅リスト＆削減ロードマップ (Heuristics Inventory & Reduction Plan)](heuristics_inventory_and_reduction_plan.md)
    - 辞書コンパイル・Viterbi ラティス探索・ゼロクエリ予測・SLM リランキング・英語入力・音声認識後処理の各ステップに存在する決め打ちスコア、品詞 ID、長さ制限、しきい値を網羅的に列挙し、Tier A〜C の削減ロードマップを定義

68. [詳細設計書: ヒューリスティクス削減計画 (Tier A0 / A / B) (`dd_heuristics_reduction_tier_a_and_b.md`)](dd_heuristics_reduction_tier_a_and_b.md)
    - Tier A0（挙動不変リファクタ）、Tier A（コーパス一本化・予測委譲）、Tier B（POS 自動生成、行列必須化、Viterbi `V1/V2/V3` の SLM-off/on 二軸評価削減、SLM パラメータ縮約、英語・音声訂正の統計化）の詳細実装仕様と検証ゲート G1〜G6

69. [PRD, JTBD, CUJ & System Design: 英語辞書強化＆プレフィックス補完（`tailsc` → `Tailscale`）](prd_and_design_english_dictionary_strengthening.md)
    - 多段コーパス統合（NLTK シード・Debian 英語説明文・マルチ LLM トークナイザ合意・Mozc OSS・Aspell・FUTO vocab）による語彙拡充、純粋ドメイン `EnglishCaseModel.suggestPrefixAndTypo()` の二分探索＋頻度降順タイポバケット＋上界枝刈り、DMN `EnglishPrefixCompletionPolicy`、および [`english_prefix_completion.als`](../app/src/test/resources/alloy/english_prefix_completion.als) による形式検証仕様

70. [PRD, JTBD, CUJ & System Design: 定期的・体系的な新語取り込み計画（Periodic Vocabulary Ingestion Pipeline）](prd_and_design_periodic_vocabulary_ingestion.md)
    - 個別単語ハードコードを排した5層（Tier 1〜5）公開コーパス統合階層、日英クロスリンガル新語還流（Mozc-UT `jawiki` / `sudachidict` 連携）、汚染・退行を防ぐ6段階の統計アドミッション・ガード、月次 GitHub Actions 自動取り込み PR＆差分監査パイプライン、および段階的ロードマップ（Phase 1〜3）

71. [英語音声入力（Parakeet）でのユーザー辞書最優先化＆強制 Stage-2 SLM ウィンドウイング（Finding 61 & ADR-026）](findings_and_decisions.md#finding-61--adr-026-english-voice-user-dictionary-prioritization--forced-stage-2-slm-windowing)
    - NVIDIA Parakeet TDT-CTC 110M 英語音声入力における完全オンデバイス・ユーザー辞書最優先化パイプライン（単語テーブルのハードコード一切なし）
    - 英語音韻（Metaphone/G2P）・読み上げ記号正規化（`dot`/`slash`/`plus`/`sharp` 等）・整列部分列マッチ・バイリンガルかな読みエントリ対応を統合した [`EnglishVoiceUserDictionaryResolver.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt) と DMN ポリシー [`EnglishUserDictDecisionModel.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt)
    - 強制 Stage-2 SLM ペアウィンドウ評価（`LlmReranker.extractDivergingPairWindow()`）および [`english_voice_user_dict.als`](../app/src/test/resources/alloy/english_voice_user_dict.als) による形式検証
    - 実機音声モデル（Parakeet TDT-CTC 110M + Qwen2.5-0.5B）定量評価：Exact TWRR 22.06% $\to$ **92.65%**、FPR **0.0%**（42件対立テスト＆300件ベースライン文）

---

## 📂 ソースコード構成

| パス | 役割 |
| :--- | :--- |
| [`app/src/main/java/com/example/myasr/MainActivity.kt`](../app/src/main/java/com/example/myasr/MainActivity.kt) | アプリケーションエントリーポイント、v2 3タブ（ホーム / 設定 / データ）＋ DMN スマートガイド UI コントローラ、IME 設定起動 |
| [`app/src/main/java/com/example/myasr/VoiceInputMethodService.kt`](../app/src/main/java/com/example/myasr/VoiceInputMethodService.kt) | システム全体で利用可能な Android Input Method Editor (IME) サービスおよび周辺テキスト文脈取得 |
| [`app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt`](../app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) | ライブストリーミング音声・VAD チャンク・タッチ入力・スワイプジェスチャを統合調停するマルチモーダル状態調停器 |
| [`app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt`](../app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt) | 変換・音声補正・予測候補を表示する IME ヘッダ候補ストリップビュー |
| [`app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt) | オンデバイス英語 QWERTY ニューラルスワイプ入力（ユーザー辞書 Trie 統合対応）の Kotlin ラッパー |
| [`app/src/main/java/com/example/myasr/context/provisioning/infrastructure/FutoSwipeDownloader.kt`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/FutoSwipeDownloader.kt) | FUTO Swipe ONNX モデルおよび語彙ファイルのオンデマンドダウンローダ＆ストレージ配備 |
| [`app/src/main/cpp/swipe/futo_swipe_engine.cc`](../app/src/main/cpp/swipe/futo_swipe_engine.cc) | ネイティブ C++ CTC ビームサーチ Trie デコーダ（カスタム辞書動的マージ対応）＆ ContextLM リランキングエンジン |
| [`app/src/main/cpp/swipe/futo_swipe_jni.cc`](../app/src/main/cpp/swipe/futo_swipe_jni.cc) | Kotlin `FutoSwipeEngine` とネイティブ ONNX Runtime C++ エンジンを結ぶ JNI ブリッジ |
| [`app/src/main/java/com/example/myasr/context/english/domain/EnglishDictionaryUtils.kt`](../app/src/main/java/com/example/myasr/context/english/domain/EnglishDictionaryUtils.kt) | 英単語判定・正規化・QWERTY 物理キー空間編集距離計算・タイポ候補生成器 |
| [`app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt`](../app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt) | ユーザー辞書同期機能を備えた SymSpell ネイティブ／フォールバック英語スペル補正エンジン |
| [`app/src/main/cpp/spelling/english_spell_engine.cc`](../app/src/main/cpp/spelling/english_spell_engine.cc) | ネイティブ C++ SymSpell 辞書＆スペル候補生成器 |
| [`app/src/main/cpp/spelling/english_spell_jni.cc`](../app/src/main/cpp/spelling/english_spell_jni.cc) | Kotlin `EnglishSpellEngine` とネイティブ C++ SymSpell を結ぶ JNI ブリッジ |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/NextPhrasePredictor.kt) | ゼロクエリ次フレーズ継続予測エンジン（バイグラム・定型連語・助詞サジェスト） |
| [`app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt`](../app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt) | 高速オンデバイス SLM リランキング制御シングルトン |
| [`app/src/main/cpp/llm_reranker.cc`](../app/src/main/cpp/llm_reranker.cc) | ネイティブ C++ KV キャッシュ保持・ロールバック＆自己回帰トークン対数尤度計算エンジン |
| [`app/src/main/cpp/llm_reranker_jni.cc`](../app/src/main/cpp/llm_reranker_jni.cc) | Kotlin `LlmReranker` とネイティブ C++ エンジンを結ぶ JNI ブリッジ |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) | Mozc CRF パラメータ・2D 連接コスト行列・SLM リランキング・ユーザー辞書統合を駆動するかな漢字変換エンジン |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/ConnectionMatrix.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/ConnectionMatrix.kt) | メモリマップド 2D バイグラム連接コスト行列（$2672 \times 2672$）ゼロコピー評価器 |
| [`app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ConnectionDataManager.kt`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ConnectionDataManager.kt) | Mozc `connection_single_column.txt` オンデマンドストリームダウンローダ＆バイナリコンパイラ |
| [`app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt) | 日本語音声入力の文脈対応表記解決・同音異義語曖昧性解消＆スペース挿入エンジン |
| [`app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoiceUserDictionaryResolver.kt) | 英語音声入力（Parakeet）向けの純粋ドメイン・ユーザー辞書優先解決器（音韻・読み上げ記号・SLM ゲート） |
| [`app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/EnglishUserDictDecisionModel.kt) | 英語音声入力でのユーザー辞書適用・衝突保護を調停する DMN 意思決定モデル |
| [`app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt`](../app/src/main/java/com/example/myasr/context/speech/domain/JapanesePunctuationEngine.kt) | 音声句読点コマンド変換・語彙保護マスキング・文末自動句点エンジン |
| [`app/src/main/java/com/example/myasr/AsrApp.kt`](../app/src/main/java/com/example/myasr/AsrApp.kt) | `AsrManager`・ユーザー辞書・`LlmReranker` をプロセス内で共有する Application シングルトンコンテナ |
| [`app/src/main/java/com/example/myasr/context/speech/infrastructure/AudioRecorder.kt`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AudioRecorder.kt) | 16 kHz モノラル 16-bit PCM マイク録音エンジン |
| [`app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt`](../app/src/main/java/com/example/myasr/context/speech/infrastructure/AsrManager.kt) | ReazonSpeech / NVIDIA Parakeet 向け Sherpa-ONNX 推論ラッパー |
| [`app/src/main/java/com/example/myasr/DictionaryActivity.kt`](../app/src/main/java/com/example/myasr/DictionaryActivity.kt) | 日英共通カスタム辞書管理画面（Zero-Friction インプレース追加・編集・検索） |
| [`app/src/main/java/com/example/myasr/context/vocabulary/presentation/DictionaryAdapter.kt`](../app/src/main/java/com/example/myasr/context/vocabulary/presentation/DictionaryAdapter.kt) | ユーザー辞書エントリ一覧表示用 `RecyclerView` アダプタ |
| [`app/src/main/java/com/example/myasr/context/vocabulary/domain/DictionaryEntry.kt`](../app/src/main/java/com/example/myasr/context/vocabulary/domain/DictionaryEntry.kt) | ユーザー辞書データモデル `(表記, よみ, 優先度)` |
| [`app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt`](../app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt) | `custom_dictionary.json` ローカル JSON 永続化リポジトリ |
| [`app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt`](../app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt) | 多表記ホットワードルール生成器（漢字・ひらがな・カタカナ展開） |
| [`app/src/main/java/com/example/myasr/context/speech/application/DictionaryPostProcessor.kt`](../app/src/main/java/com/example/myasr/context/speech/application/DictionaryPostProcessor.kt) | デコード後の音韻置換＆表層正規化ポストプロセッサ |
| [`app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt`](../app/src/main/java/com/example/myasr/context/kanakanji/domain/JapaneseKanaUtils.kt) | かな相互変換（ひらがな $\leftrightarrow$ カタカナ）および文字種バリデーション |
| [`app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ModelDownloader.kt`](../app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ModelDownloader.kt) | 進捗通知付きオンデマンドモデル HTTPS ダウンローダ |
| [`app/src/main/res/layout/activity_main.xml`](../app/src/main/res/layout/activity_main.xml) | `MainActivity` 向け v2 3タブ＋段階的開示レイアウト |
| [`app/src/main/res/layout/keyboard_view.xml`](../app/src/main/res/layout/keyboard_view.xml) | キーボード IME 下部レイアウト（音声トグル・候補ストリップ・フリック／QWERTY 盤面） |
| [`app/src/main/res/xml/method.xml`](../app/src/main/res/xml/method.xml) | Android Input Method Editor メタデータ定義 |
