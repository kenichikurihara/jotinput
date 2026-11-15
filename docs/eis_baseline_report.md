# EIS-v1 ベースラインレポート（Phase 1）

> 自動生成: `python3 eval/eis/run_eis.py --report`。定義は [`prd_top_level.md`](prd_top_level.md)。
> 入力はリポジトリ内のキャッシュ済み評価出力のみ（モデル推論なし）。**絶対値は KLM 仮定定数（EIS-v1）に依存**し、
> 実測較正前（Phase 3）は **版内の相対比較・回帰検知** にのみ使うこと。

## 1. ヘッドライン（凍結 test 分割, bootstrap B=1000）

**EIS = 164.9 文字/分**（95% CI 137.1 – 187.9）

| CUJ | 重み | EIS_c (文字/分) | 95% CI | dev EIS_c | n (test) | 状態 |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| CUJ-1 | 0.40 | 204.4 | 192.4 – 217.2 | 212.7 | 123 | OK |
| CUJ-2 | 0.35 | 128.1 | 88.0 – 176.0 | 117.4 | 69 | OK |
| CUJ-3 | 0.15 | 139.0 | 133.7 – 144.5 | 138.0 | 177 | OK |
| CUJ-4 | 0.10 | 337.7 | 313.8 – 365.0 | 328.6 | 399 | OK |

dev 分割の全体 EIS = 159.9（参考。チューニングは dev のみで行う）。

## 2. アブレーション（EIS の感度確認）

全体 EIS の変化（test 分割, paired bootstrap 95% CI）。各機能の価値が **文字/分** という共通単位で比較できることを示す。

| 変更 | 全体 EIS | Δ | Δ 95% CI |
| :--- | :---: | :---: | :---: |
| 予測候補なし (CUJ-2: 変換のみ) | 147.3 | -17.52 | -35.04 … +1.87 |
| 予測 SLM OFF (CUJ-2: Mozc 単体予測) | 160.8 | -4.04 | -18.82 … +5.71 |
| ユーザー辞書なし (CUJ-3: 単語を登録しない) | 169.3 | +4.42 | +2.95 … +5.97 |
| Swipe ContextLM OFF (CUJ-4: 空間モデルのみ) | 164.8 | -0.08 | -0.20 … -0.01 |
| 音声に辞書バイアス適用 (CUJ-1: hybrid) | 164.6 | -0.25 | -0.88 … +0.30 |

### 2.1 辞書登録の効果は「登録語の再利用回数」の仮定に依存する

登録コスト（12 秒）を再利用回数 n で償却するモデル。`delta = 辞書なし − 辞書あり`（正なら辞書なしの方が速い＝登録が元を取れていない）。

| 再利用回数 n | EIS_3 辞書あり | EIS_3 辞書なし | 全体 EIS 辞書あり | 全体 EIS 辞書なし | delta |
| :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 91.6 | 162.9 | 151.0 | 169.3 | +18.31 |
| 3 | 139.0 | 162.9 | 164.9 | 169.3 | +4.42 |
| 5 | 155.0 | 162.9 | 168.0 | 169.3 | +1.33 |
| 7 | 163.1 | 162.9 | 169.3 | 169.3 | -0.03 |
| 10 | 169.7 | 162.9 | 170.4 | 169.3 | -1.07 |
| 20 | 178.2 | 162.9 | 171.6 | 169.3 | -2.29 |

損益分岐は表の delta の符号が反転する n 付近（1 回の使用あたりの訂正時間の節約量と、登録コストの比で決まる）。**この結果は『辞書機能は不要』という意味ではない**。
連絡先・社名など固有名詞は何度も使われるため、n の実測（端末内ローカル統計）が Phase 3 の重要項目になる。

## 3. ガードレール（本実行では未検証）

EIS 計算はガードレールを評価しない。リリース判定では下記をすべて合格させること（PRD §5.4）。

| ガードレール | 検証手段 | 本実行 |
| :--- | :--- | :---: |
| G1 完全オフライン | コードレビュー/マニフェスト | UNVERIFIED |
| G2 音声句読点の非回帰 | ./gradlew testDebugUnitTest (JapanesePunctuationEvaluationTest) | UNVERIFIED |
| G3 テキストを壊さない | VoiceInputArbitratorTest, AlloyVerificationTest | UNVERIFIED |
| G4 ユーザーデータ永続性 | 辞書永続性テスト | UNVERIFIED |
| G5 クラッシュ/ANR 0 | deploy.sh + logcat (両ターゲット) | UNVERIFIED |
| G6 CUJ-0 完走 | オンボーディング DMN/Alloy + 実機 | UNVERIFIED |
| G7 応答性上限 | ImeLatencyAndAsyncTest ほか | UNVERIFIED |
| G8 APK ≤ 110MB / 電力 | CI / ODPM | UNVERIFIED |
| G9 DDD 境界 | DddArchitectureKonsistTest | UNVERIFIED |

## 4. 既知の限界（解釈時に必ず読むこと）

- **CUJ-1**: Source: eval/dataset.json (homophones 250 + negative_control 150) x eval_checkpoint.json predictions.
- **CUJ-1**: Audio is synthetic TTS (eval/audio); real-speech gap is unmeasured (Phase 3).
- **CUJ-1**: Punctuation stripped before alignment; punctuation quality is covered by JapanesePunctuationEvaluationTest (guardrail G2), not by EIS-v1 Phase 1.
- **CUJ-1**: Filler words, streaming flicker and wait time beyond a constant tail latency are not yet modelled.
- **CUJ-2**: Source: eval/lm_first_nll_cache.json (247 conversion cases) + tools/eval/jotinput_prediction_evaluation_results.json (30 predictive prefix cases).
- **CUJ-2**: Predictive candidate input accelerates typing by allowing confirmation after 0, 1, or 2 keystrokes.
- **CUJ-2**: Conversion and predictive input are balanced 50:50 by character share (PRD §4.2).
- **CUJ-3**: Registration cost 12s amortised over 3 uses (v1 assumption; see reuse-count sensitivity in the report - the sign of the dictionary benefit depends on it).
- **CUJ-3**: Voice side: dictionary-registered (hybrid) predictions; counterfactual no_dictionary = baseline predictions.
- **CUJ-3**: Flick side: lm_first user_dict items (20); counterfactual removes user candidates.
- **CUJ-3**: Voice-correction learning (repeat-occurrence benefit) is NOT yet in EIS: only layer-level rates exist in eval/voice_correction_eval_results.json (Phase 2).
- **CUJ-4**: English VOICE: 300 benchmark samples evaluated on NVIDIA Parakeet TDT-CTC 110M (WER 10.26%, RTF 0.0281).
- **CUJ-4**: English TOUCH: 1,000 FUTO Swipe samples with spatial + ContextLM (Top-1 90.9%).
- **CUJ-4**: Voice and touch are balanced 50:50 by character share (PRD §4.2).
- 全 CUJ 共通: KLM 定数は仮定値（PRD §5.3）。重み $w_c$ は PO 承認済みの方針値。
- 現在の Phase 1 は **既存評価資産の再解釈** であり、新規計測ではない。Phase 2 で実エンジン（KanaKanjiEngine+リランカー）を回し、Phase 3 で実機遅延と定数を較正する。
