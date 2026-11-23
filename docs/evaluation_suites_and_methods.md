# JotInput 評価セット・評価方法 統合ガイド

本ドキュメントは、JotInput（オンデバイス日本語・英語入力 IME / 音声認識システム）において過去に構築・実施された**すべての評価データセット、評価スクリプト、JVM 単体テスト、C++ ベンチマーク、形式検証、および評価レポート**を体系的に整理したマスターインデックスです。

> **関連スキル**: エージェント向けのコマンド・分類要約は [`myasr-eval`](../.agent/skills/myasr-eval/SKILL.md)（※環境スキル参照）にも定義されていますが、本ドキュメントはリポジトリ内の全評価資産（音声・かな漢字・SLM・英語入力・電力/レイテンシ・形式検証）を網羅する**唯一の正規ドキュメント（Single Source of Truth）**です。

> 🧭 **トップレベルメトリック**: 以下の 7 ドメインの指標はすべて **L2 診断指標** です。プロダクトの意思決定は [`prd_top_level.md`](prd_top_level.md) で定義する North Star Metric **EIS（有効入力速度, 意図どおり文字/分）** と CUJ 別 $\mathrm{EIS}_c$、およびガードレール G1〜G9 で行います。新しい評価セットを追加するときは、どの CUJ バケットに入り、どの時間項（$T_{\text{wait}}, T_{\text{select}}, T_{\text{correct}}, T_{\text{produce}}$）を通じて EIS に影響するかを明記してください。

---

## 📊 全体マップ（クイックリファレンス）

| ドメイン | 評価対象 | 主な評価データセット / テストクラス | 主な評価指標 | 実行コマンド / ツール | 詳細レポート |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1. 日本語 ASR** | 音響認識・カスタム辞書バイアス・文脈補正 | `eval/dataset.json` (1,000件)<br>`eval/dataset_with_context.json` (1,000件) | TWRR, Precision/Recall/F1, FPR, CER, RTF | `python3 eval/evaluate_1000.py`<br>`python3 eval/evaluate_context_aware.py` | [`evaluation_report_1000.md`](evaluation_report_1000.md)<br>[`context_aware_evaluation_report.md`](context_aware_evaluation_report.md) |
| **2. 音声入力 UX・句読点** | 音声句読点・保護語彙・フィラー除去・Streaming確定 | [`JapanesePunctuationEvaluationTest.kt`](../app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) (67+ケース)<br>[`StreamingPrefixFreezeEvaluationTest.kt`](../app/src/test/java/com/example/myasr/StreamingPrefixFreezeEvaluationTest.kt)<br>[`StreamingPrefixRevisionEvaluationTest.kt`](../app/src/test/java/com/example/myasr/StreamingPrefixRevisionEvaluationTest.kt) | 合格率 100%（必須非回帰）, FPR 0%, Edit Distance, Revision Rate, レイテンシ | `./gradlew testDebugUnitTest` | [`punctuation_and_voice_boundary_evaluation_criteria.md`](punctuation_and_voice_boundary_evaluation_criteria.md)<br>[`voice_input_evaluation_report.md`](voice_input_evaluation_report.md)<br>[`streaming_prefix_freeze_solutions_and_evaluation.md`](streaming_prefix_freeze_solutions_and_evaluation.md)<br>[`filler_removal_ideas_and_evaluation.md`](filler_removal_ideas_and_evaluation.md) |
| **3. 音声×タッチ調停・形式検証** | マルチモーダル競合調停・Alloy仕様検証・DDD境界 | [`VoiceInputArbitratorTest.kt`](../app/src/test/java/com/example/myasr/VoiceInputArbitratorTest.kt) (24ケース)<br>[`AlloyVerificationTest.kt`](../app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt)<br>[`DddArchitectureKonsistTest.kt`](../app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) | 不変条件 100% 充足, アーキテクチャ純粋性 | `./gradlew testDebugUnitTest` | [`voice_and_touch_interaction_corner_cases.md`](voice_and_touch_interaction_corner_cases.md)<br>[`ddd/alloy_formal_verification.md`](ddd/alloy_formal_verification.md) |
| **4. かな漢字変換・Mozc辞書** | 2D連接行列・N-Best多様性・長文変換・濁音曖昧検索・数字助数詞 | `tools/eval_nbest_diversity_dataset.jsonl` (350件)<br>[`ConnectionMatrixTest.kt`](../app/src/test/java/com/example/myasr/ConnectionMatrixTest.kt)<br>[`KanaKanjiEngineDiversityTest.kt`](../app/src/test/java/com/example/myasr/KanaKanjiEngineDiversityTest.kt)<br>[`LongPhraseConversionTest.kt`](../app/src/test/java/com/example/myasr/LongPhraseConversionTest.kt) | Top-1/Top-3, MRR, 語幹多様性, 接頭辞再現率, ノイズ排除率 | `python3 eval/evaluate_connection_matrix.py`<br>`python3 tools/eval_nbest_diversity.py`<br>`./gradlew testDebugUnitTest` | [`connection_matrix_evaluation_report.md`](connection_matrix_evaluation_report.md)<br>[`long_phrase_conversion_headroom_report.md`](long_phrase_conversion_headroom_report.md)<br>[`voicing_fuzzy_prefix_search_design_and_evaluation.md`](voicing_fuzzy_prefix_search_design_and_evaluation.md) |
| **5. SLM リランキング & LM-First** | 同音異義語・Context-Free・α交差検証・未消費スパン・文脈長比較 | `tools/eval_1000_cases.tsv` (1,000件)<br>`tools/eval_500_natural_empty.tsv` (500件)<br>`tools/eval_dataset_10000.jsonl` (10,000件)<br>`eval/lm_first_eval_dataset.json` (247件) | Top-1, Top-3, MRR, Win/Loss比, ΔNLL, ユーザー辞書100% | `python3 eval/tune_and_eval_lm_first.py`<br>`python3 eval/evaluate_slm_mandatory_rerank.py`<br>`tools/cv_optimize_alpha.cc` | [`slm_mandatory_rerank_evaluation_report.md`](slm_mandatory_rerank_evaluation_report.md)<br>[`slm_alpha_cross_validation_report.md`](slm_alpha_cross_validation_report.md)<br>[`context_length_and_segmentation_report.md`](context_length_and_segmentation_report.md)<br>[`rerank_nano2_kekka_sakujo_debug_and_ideas.md`](rerank_nano2_kekka_sakujo_debug_and_ideas.md) |
| **6. 英語入力 (音声 ASR / Swipe / 補完 / スペル)** | Parakeet ASR・FUTO Swipe・英語補完・タイポ訂正・SLMリランク | `eval/english_voice/dataset.json` (300件)<br>`eval/futo_swipe/swipe_eval_1000.jsonl` (1,000件)<br>`eval/english_completion/completion_eval_100.json` (100語)<br>[`EnglishTypoRerankTest.kt`](../app/src/test/java/com/example/myasr/EnglishTypoRerankTest.kt) | WER/CER, RTF, Top-1/Top-3/Top-5, NNレイテンシ(ms), 補完再現率 | `python3 eval/english_voice/evaluate_english_voice.py`<br>`python3 eval/futo_swipe/run_futo_swipe_eval.py`<br>`python3 eval/english_completion/evaluate_completion_golden.py` | [`english_asr_model_selection_and_design.md`](english_asr_model_selection_and_design.md)<br>[`futo_swipe_phase1_evaluation_report.md`](futo_swipe_phase1_evaluation_report.md)<br>[`prd_and_design_unified_english_dictionary.md`](prd_and_design_unified_english_dictionary.md) |
| **0. トップレベル EIS** | 全 CUJ 統合メトリック（文字/分） | 既存キャッシュ出力を CUJ 別に再解釈（`eval/eis/adapters.py`） | EIS, EIS_c, bootstrap 95% CI, アブレーション Δ | `python3 eval/eis/run_eis.py --report`<br>`python3 -m unittest discover -s eval/eis` | [`eis_baseline_report.md`](eis_baseline_report.md)<br>[`prd_top_level.md`](prd_top_level.md) |
| **7. レイテンシ・電力・実機計測** | ODPM消費電力・Cold/Warm起動・タイムアウト安全性 | [`ImeLatencyAndAsyncTest.kt`](../app/src/test/java/com/example/myasr/ImeLatencyAndAsyncTest.kt)<br>[`EngineColdVsWarmBenchmarkTest.kt`](../app/src/test/java/com/example/myasr/EngineColdVsWarmBenchmarkTest.kt) | mAh / mJ, CPU時間, KVキャッシュヒット率, ms | `python3 eval/evaluate_battery_power.py`<br>`tools/bench_ime_latency.cc` | [`slm_battery_and_power_consumption_report.md`](slm_battery_and_power_consumption_report.md)<br>[`ime_latency_and_offline_eval_plan.md`](ime_latency_and_offline_eval_plan.md)<br>[`model_warmup_and_latency_evaluation.md`](model_warmup_and_latency_evaluation.md) |

---

## 1. 日本語音声認識 (ASR) & カスタム辞書バイアス・文脈認識

ReazonSpeech Zipformer int8 モデルの音響デコード精度、Hotwords（in-beam バイアス）、後処理の表音置換、およびエディタ先行文脈 (`getTextBeforeCursor`) を用いた同音異義語解決を評価します。

### データセット
- **`eval/dataset.json`** (1,000件): 人名 250件、同音異義語 250件、地名 150件、技術用語 200件、負例コントロール（誤爆検証用）150件。対応する音声ファイルは `eval/audio/item_0001.wav` 〜 `item_1000.wav`。
- **`eval/dataset_with_context.json`** (1,000件): 上記 1,000件に先行文脈テキストを付与した文脈認識評価用データセット。

### 評価方法・指標
- **TWRR (Target Word Recognition Rate)**: 登録単語の正答再現率
- **Precision / Recall / F1 / FPR (False Positive Rate)**: 負例コントロールにおける誤置換ゼロ（FPR = 0.0%）の確認
- **CER (Character Error Rate)** / **RTF (Real-Time Factor)**

### 実行コマンド
```bash
# データセット・音声生成（必要時のみ）
python3 eval/build_dataset.py
python3 eval/generate_audio.py
python3 eval/generate_contexts.py

# 1,000件 ASR + カスタム辞書ベンチマーク
python3 eval/evaluate_1000.py \
  --model-dir tools/models/reazonspeech \
  --dataset eval/dataset.json \
  --output eval/evaluation_results_1000.json

# 文脈認識 (Context-Aware) 比較評価
python3 eval/evaluate_context_aware.py
```

### 関連ドキュメント
- [`docs/evaluation_report_1000.md`](evaluation_report_1000.md)
- [`docs/context_aware_evaluation_report.md`](context_aware_evaluation_report.md)
- [`docs/dictionary_improvement_plan.md`](dictionary_improvement_plan.md)

---

## 2. 音声句読点・発話境界・フィラー除去・ストリーミング確定

**最重要非回帰契約（`AGENTS.md` / `GEMINI.md`）**: ASR・VAD・句読点・IME に触れる変更時は、必ず `./gradlew testDebugUnitTest` で全件 100% パスすることが義務付けられています。

### テストスイート & データセット
1. **音声句読点・保護語彙テスト**: [`JapanesePunctuationEvaluationTest.kt`](../app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt) (13テストメソッド / 67+ケース)
   - 「てん」「まる」「から」「ます」「です」等のコマンド認識と、保護語彙（天気、天然、満点、交差点、重点、10点、まるで、一丸、日の丸、ですから、ますます等）の誤変換防止（FPR 0%）を検証。
2. **ストリーミング確定 & 改訂テスト**:
   - [`StreamingPrefixFreezeEvaluationTest.kt`](../app/src/test/java/com/example/myasr/StreamingPrefixFreezeEvaluationTest.kt) (8テストメソッド): フリッカー抑制・Edit Distance・Revision Rate 評価
   - [`StreamingPrefixRevisionEvaluationTest.kt`](../app/src/test/java/com/example/myasr/StreamingPrefixRevisionEvaluationTest.kt) (8テストメソッド): 後続文脈到着時の動的プレフィックス書き換え（「移動」→「異動願い」等）
   - [`StreamingVadProcessorTest.kt`](../app/src/test/java/com/example/myasr/StreamingVadProcessorTest.kt) / [`VoiceInputPauseAndStopRegressionTest.kt`](../app/src/test/java/com/example/myasr/VoiceInputPauseAndStopRegressionTest.kt) / [`VoiceUserDictionaryPriorityTest.kt`](../app/src/test/java/com/example/myasr/VoiceUserDictionaryPriorityTest.kt)
3. **フィラー除去・音声独自アルゴリズム評価仕様**:
   - 全5カテゴリ・120件のフィラー除去評価仕様（[`filler_removal_ideas_and_evaluation.md`](filler_removal_ideas_and_evaluation.md)）
   - 142+件の独自アルゴリズム総合ベンチマーク（[`voice_input_evaluation_methodology_and_criteria.md`](voice_input_evaluation_methodology_and_criteria.md), [`voice_input_evaluation_report.md`](voice_input_evaluation_report.md)）

### 実行コマンド
```bash
./gradlew testDebugUnitTest --tests "com.example.myasr.JapanesePunctuationEvaluationTest"
./gradlew testDebugUnitTest --tests "com.example.myasr.StreamingPrefix*"
```

---

## 3. 音声×タッチ調停 & 形式検証 (Alloy / DMN / Konsist)

音声入力とフリック・QWERTY・スワイプ・削除・Undo・カーソル移動の競合、および DDD アーキテクチャ境界を検証します。

### テストスイート
- **マルチモーダル調停**: [`VoiceInputArbitratorTest.kt`](../app/src/test/java/com/example/myasr/VoiceInputArbitratorTest.kt)（7カテゴリ・24コーナーケースを網羅）、[`FutoSwipeIntegrationTest.kt`](../app/src/test/java/com/example/myasr/FutoSwipeIntegrationTest.kt)
- **Alloy 形式検証**: [`app/src/test/resources/alloy/voice_touch_arbitration.als`](../app/src/test/resources/alloy/voice_touch_arbitration.als), [`dmn_decision_safety.als`](../app/src/test/resources/alloy/dmn_decision_safety.als) を [`AlloyVerificationTest.kt`](../app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) で自動検査
- **DDD / DMN アーキテクチャ検証**: [`DddArchitectureKonsistTest.kt`](../app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt), [`DmnEngineTest.kt`](../app/src/test/java/com/example/myasr/shared/domain/dmn/DmnEngineTest.kt)

### 実行コマンド
```bash
./gradlew testDebugUnitTest --tests "com.example.myasr.VoiceInputArbitratorTest"
./gradlew testDebugUnitTest --tests "com.example.myasr.formal.AlloyVerificationTest"
./gradlew testDebugUnitTest --tests "com.example.myasr.ddd.DddArchitectureKonsistTest"
```

---

## 4. かな漢字変換・Mozc 2D連接行列・N-Best多様性・長文変換

SLM を使わないベースライン変換（Viterbi + 2D連接行列 + 辞書Trie/SQLite）の品質・多様性・曖昧検索を評価します。

### データセット & テスト
- **2D連接行列 ($2672 \times 2672$)**: `eval/evaluate_connection_matrix.py` / `eval/connection_matrix_eval_results.json` / [`ConnectionMatrixTest.kt`](../app/src/test/java/com/example/myasr/ConnectionMatrixTest.kt)
- **N-Best 多様性・接尾辞ノイズ排除**: `tools/eval_nbest_diversity_dataset.jsonl` (350件) / `tools/eval_nbest_diversity.py` / [`KanaKanjiEngineDiversityTest.kt`](../app/src/test/java/com/example/myasr/KanaKanjiEngineDiversityTest.kt)
- **長文・複数文節変換**: [`LongPhraseConversionTest.kt`](../app/src/test/java/com/example/myasr/LongPhraseConversionTest.kt) / `eval/evaluate_ieno_demasen_proposals.py`（「いえの」「でません」等の対策比較）
- **濁音・半濁音曖昧検索**: [`KanaKanjiEngineVoicingTest.kt`](../app/src/test/java/com/example/myasr/KanaKanjiEngineVoicingTest.kt)（20件マトリクス: `かいき`→`会議` 等）
- **アラビア数字+助数詞 / フリックタイポ**: [`JapaneseNumeralEvaluationTest.kt`](../app/src/test/java/com/example/myasr/JapaneseNumeralEvaluationTest.kt), [`FlickTypoAndMozcCorrectionEvaluationTest.kt`](../app/src/test/java/com/example/myasr/FlickTypoAndMozcCorrectionEvaluationTest.kt)

### 実行コマンド
```bash
python3 eval/evaluate_connection_matrix.py
python3 tools/eval_nbest_diversity.py
./gradlew testDebugUnitTest --tests "com.example.myasr.ConnectionMatrixTest" \
  --tests "com.example.myasr.KanaKanjiEngineDiversityTest" \
  --tests "com.example.myasr.LongPhraseConversionTest"
```

---

## 5. オンデバイス SLM リランキング & LM-First 統合スコアリング

Qwen2.5-0.5B / 1.5B (GGUF INT4, `llama.cpp`) を用いた条件付き対数尤度リランキング、ハイパーパラメータ ($\alpha, \lambda_{\text{obs}}, \gamma_{\text{user}}$) 最適化、および文脈長トレードオフを評価します。

### データセット一覧
| ファイル | 件数 | 用途・構成 |
| :--- | :---: | :--- |
| `tools/eval_dataset_pilot.jsonl` | 50件 | Phase 1 PoC（手動確認用パイロットセット） |
| `tools/eval_dataset_10000.jsonl` | 10,000件 | Phase 2 大規模コーパス（KWDLC + Tatoeba + 同音異義語対） |
| `tools/eval_1000_cases.tsv` | 1,000件 | 先行文脈ありの同音異義語・文節変換ベンチマーク |
| `tools/eval_500_natural_empty.tsv` | 500件 | 先行文脈なし (`""`) の Context-Free 安定性ベンチマーク |
| `eval/lm_first_eval_dataset.json` | 247件 | LM-First 統合スコアリング（6カテゴリ: ユーザー辞書20, 長さバイアス診断12, タイポ15, 会話100 等） |

### 評価スクリプト & C++ ハーネス
- **Phase 1 / 2 評価**: `tools/eval_benchmark.py` / `tools/eval_benchmark_scale.py`
- **Mandatory Rerank & 0.5B vs 1.5B 比較**: `eval/evaluate_slm_mandatory_rerank.py` / `eval/evaluate_qwen1_5b.py`
- **LM-First チューニング & 評価**: `eval/tune_and_eval_lm_first.py`（NLLキャッシュ: `eval/lm_first_nll_cache.json`） / [`LmFirstJointEvaluationTest.kt`](../app/src/test/java/com/example/myasr/LmFirstJointEvaluationTest.kt)
- **未消費スパン評価・多様性**: [`SpanAwareBayesianRerankingEvaluationTest.kt`](../app/src/test/java/com/example/myasr/SpanAwareBayesianRerankingEvaluationTest.kt)
- **文脈トークン長 & 境界分割トレードオフ**: `eval/evaluate_context_length_tradeoff.py`（予算 0〜128 トークン × 3分割戦略）
- **精度トレードオフ型 10倍以上高速化評価 (EIS-v1 Zero-Leakage)**: `eval/evaluate_slm_10x_speedup_profiles.py` + `tools/bench_slm_10x_profiles.cc` → [`eval/slm_10x_speedup_eval_results.json`](eval/slm_10x_speedup_eval_results.json) / レポート: [`slm_approximate_10x_speedup_evaluation_report.md`](docs/slm_approximate_10x_speedup_evaluation_report.md)（11プロファイル比較・選定結果 `P1c_a2_token0_c12_d2` でセッション結合 13.1x / KV-Hit 97.8x / Warm NextPhrase 611.3x）
- **C++ ネイティブ高速ベンチマーク**:
  - `tools/slm_scorer_cli.cc`: 単体スコア検証 CLI
  - `tools/eval_context_free_slm.cc` / `tools/bench_1000_context_free.cc`: Context-Free 500/1,000件評価
  - `tools/cv_optimize_alpha.cc`: 5分割交差検証による $\alpha$ 最適化

### 実行コマンド
```bash
# LM-First パラメータチューニング & 評価 (247件)
python3 eval/tune_and_eval_lm_first.py
./gradlew testDebugUnitTest --tests "com.example.myasr.LmFirstJointEvaluationTest"

# Mandatory SLM Rerank / Qwen 1.5B 比較
python3 eval/evaluate_slm_mandatory_rerank.py
python3 eval/evaluate_qwen1_5b.py

# 文脈長トレードオフ評価
python3 eval/evaluate_context_length_tradeoff.py
```

---

## 6. 英語入力（音声 ASR・FUTO Swipe・英語補完・スペル訂正・SLMリランク）

### データセット & テスト
- **英語音声 ASR (300件)**: `eval/english_voice/dataset.json`（5カテゴリ: 会話100, 業務技術50, 質問命令50, 句読点大文字50, 数字日付50） / `eval/english_voice/audio/*.wav`（ローカル Piper TTS 16kHz 合成） / `eval/english_voice/evaluate_english_voice.py` / 結果: `eval/english_voice/english_voice_eval_results.json`（NVIDIA Parakeet TDT-CTC 110M: 平均 WER 10.26%, RTF 0.0281〔~35.6倍速〕）
- **QWERTY ニューラルスワイプ (1,000件)**: `eval/futo_swipe/swipe_eval_1000.jsonl`（※大容量のためローカル/ダウンロード配置） / `eval/futo_swipe/run_futo_swipe_eval.py` / 結果: `eval/futo_swipe/swipe_eval_results_1000.json`（Spatial単体 Top-1: 89.0% → ContextLM併用: 90.3%）
- **英語プレフィックス補完 (100語)**: `eval/english_completion/completion_eval_100.json` / `eval/english_completion/target_100_words.json` / `eval/english_completion/evaluate_completion_golden.py`
- **スペル・タイポ訂正 & SLMリランク**:
  - `tools/models/english_spelling/evaluate_spelling.py`
  - [`EnglishTypoRerankTest.kt`](../app/src/test/java/com/example/myasr/EnglishTypoRerankTest.kt)（相対観測コスト $D_{\text{obs}} \le 0.38$ と短語保護）
  - [`UnifiedEnglishDictionaryTest.kt`](../app/src/test/java/com/example/myasr/UnifiedEnglishDictionaryTest.kt), [`EnglishSpellEngineTest.kt`](../app/src/test/java/com/example/myasr/EnglishSpellEngineTest.kt), [`EnglishCapitalizationAnalyzerTest.kt`](../app/src/test/java/com/example/myasr/EnglishCapitalizationAnalyzerTest.kt)

### 実行コマンド
```bash
# 英語音声合成 & Parakeet ASR ベンチマーク (300件)
python3 eval/english_voice/build_dataset.py
python3 eval/english_voice/generate_audio.py
python3 eval/english_voice/evaluate_english_voice.py

# 英語スワイプ & 補完 & スペル
python3 eval/futo_swipe/run_futo_swipe_eval.py --eval-file eval/futo_swipe/swipe_eval_1000.jsonl --model-dir tools/models/futo_swipe --limit 1000
python3 eval/english_completion/evaluate_completion_golden.py
./gradlew testDebugUnitTest --tests "*English*" --tests "com.example.myasr.FutoSwipeIntegrationTest"
```

---

## 7. レイテンシ・ウォームアップ・消費電力（実機 & オフライン）

### 計測スイート
- **実機バッテリー・ODPM 電力計測 (Pixel 10 Pro)**: `eval/evaluate_battery_power.py` → `eval/battery_power_eval_results.json`（Rerank OFF vs Qwen2.5-0.5B vs 1.5B の mAh / CPU時間 / 周波数滞在率を比較）
- **オフライン C++ キーストロークレイテンシ**: `tools/bench_ime_latency.cc`
- **JVM レイテンシ・非同期・ウォームアップテスト**:
  - [`ImeLatencyAndAsyncTest.kt`](../app/src/test/java/com/example/myasr/ImeLatencyAndAsyncTest.kt)
  - [`EngineColdVsWarmBenchmarkTest.kt`](../app/src/test/java/com/example/myasr/EngineColdVsWarmBenchmarkTest.kt)
  - [`ModelWarmUpManagerTest.kt`](../app/src/test/java/com/example/myasr/ModelWarmUpManagerTest.kt)
  - [`ModelTimeoutAndFailSafeTest.kt`](../app/src/test/java/com/example/myasr/ModelTimeoutAndFailSafeTest.kt)
  - [`ZeroJitterRerankingTest.kt`](../app/src/test/java/com/example/myasr/ZeroJitterRerankingTest.kt)

---

## 🛠️ 運用・非回帰チェックリスト

1. **全 JVM 単体テストの実行（毎回の変更で必須）**:
   ```bash
   ./gradlew testDebugUnitTest
   ```
   ※ 特に `JapanesePunctuationEvaluationTest`（音声句読点非回帰）と `DddArchitectureKonsistTest`（DDD境界）は 100% パスが必須です。
2. **新規評価セット・ベンチマーク追加時のルール**:
   - 大容量モデル・大規模辞書バイナリは `app/src/main/assets/` には置かず、`tools/models/` または `eval/` 配下に配置し `.gitignore` を確認する（APK サイズ $\le 110\text{ MB}$ 契約）。
   - 新たな評価セットやスクリプトを追加した場合は、本ドキュメントの該当セクションと全体マップに追記する。
