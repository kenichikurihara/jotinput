# EIS-v1 ベースラインレポート（Phase 1）

> 自動生成: `python3 eval/eis/run_eis.py --report`。定義は [`prd_top_level.md`](prd_top_level.md)。
> 入力はリポジトリ内のキャッシュ済み評価出力のみ（モデル推論なし）。**絶対値は KLM 仮定定数（EIS-v1）に依存**し、
> 実測較正前（Phase 3）は **版内の相対比較・回帰検知** にのみ使うこと。

## 1. ヘッドライン（凍結 test 分割, bootstrap B=1000）

**EIS = 140.6 文字/分**（95% CI 134.3 – 145.9）　※ CUJ-4 は英語音声が NO_DATA のため **暫定値**

| CUJ | 重み | EIS_c (文字/分) | 95% CI | dev EIS_c | n (test) | 状態 |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| CUJ-1 | 0.40 | 204.4 | 192.4 – 217.4 | 212.7 | 123 | OK |
| CUJ-2 | 0.35 | 92.8 | 85.9 – 99.3 | 90.6 | 63 | OK |
| CUJ-3 | 0.15 | 139.0 | 134.0 – 144.4 | 138.0 | 177 | OK |
| CUJ-4 | 0.10 | 326.1 | 294.6 – 360.6 | 297.1 | 310 | PARTIAL |

dev 分割の全体 EIS = 139.5（参考。チューニングは dev のみで行う）。

## 2. アブレーション（EIS の感度確認）

全体 EIS の変化（test 分割, paired bootstrap 95% CI）。各機能の価値が **文字/分** という共通単位で比較できることを示す。

| 変更 | 全体 EIS | Δ | Δ 95% CI |
| :--- | :---: | :---: | :---: |
| ユーザー辞書なし (CUJ-3: 単語を登録しない) | 143.8 | +3.20 | +2.50 … +3.88 |
| Swipe ContextLM OFF (CUJ-4: 空間モデルのみ) | 140.5 | -0.12 | -0.28 … -0.01 |
| 音声に辞書バイアス適用 (CUJ-1: hybrid) | 140.4 | -0.18 | -0.65 … +0.20 |

### 2.1 辞書登録の効果は「登録語の再利用回数」の仮定に依存する

登録コスト（12 秒）を再利用回数 n で償却するモデル。`delta = 辞書なし − 辞書あり`（正なら辞書なしの方が速い＝登録が元を取れていない）。

| 再利用回数 n | EIS_3 辞書あり | EIS_3 辞書なし | 全体 EIS 辞書あり | 全体 EIS 辞書なし | delta |
| :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | 91.6 | 162.9 | 130.3 | 143.8 | +13.42 |
| 3 | 139.0 | 162.9 | 140.6 | 143.8 | +3.20 |
| 5 | 155.0 | 162.9 | 142.8 | 143.8 | +0.96 |
| 7 | 163.1 | 162.9 | 143.8 | 143.8 | -0.02 |
| 10 | 169.7 | 162.9 | 144.5 | 143.8 | -0.77 |
| 20 | 178.2 | 162.9 | 145.4 | 143.8 | -1.65 |

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
- **CUJ-2**: Source: eval/lm_first_nll_cache.json (cached SLM NLLs), ranked with lambda_obs/gamma_user from best_config but alpha=0 (leak guard).
- **CUJ-2**: LEAK: mozc_cost in this dataset is assigned by candidate index (target first), so a Mozc-only baseline and alpha>0 are invalid here; the 'SLM OFF' ablation is therefore NOT reported. Phase 2 must use the real engine.
- **CUJ-2**: Candidate lists are small (2-4 per item), so rank-1 rates are optimistic vs live Viterbi N-best; long-phrase headroom (docs/long_phrase_conversion_headroom_report.md: 33.1% Top-1) is NOT captured. Phase 2 must run the real KanaKanjiEngine on tools/eval_1000_cases.tsv.
- **CUJ-3**: Registration cost 12s amortised over 3 uses (v1 assumption; see reuse-count sensitivity in the report - the sign of the dictionary benefit depends on it).
- **CUJ-3**: Voice side: dictionary-registered (hybrid) predictions; counterfactual no_dictionary = baseline predictions.
- **CUJ-3**: Flick side: lm_first user_dict items (20); counterfactual removes user candidates.
- **CUJ-3**: Voice-correction learning (repeat-occurrence benefit) is NOT yet in EIS: only layer-level rates exist in eval/voice_correction_eval_results.json (Phase 2).
- **CUJ-4**: English VOICE (Parakeet) side is NO_DATA (Phase 1.5); EIS_4 currently = touch (swipe) only.
- **CUJ-4**: Swipe items are pseudo-items reconstructed from aggregate Top-1/3/5 counts (raw per-sample outputs are not in the repo), so bootstrap CIs reflect sampling of the 1,000 words but ranks are not paired to real samples.
- **CUJ-4**: Tap-typing, spell-correction and capitalisation suites are not yet included.
- 全 CUJ 共通: KLM 定数は仮定値（PRD §5.3）。重み $w_c$ は PO 承認済みの方針値。
- 現在の Phase 1 は **既存評価資産の再解釈** であり、新規計測ではない。Phase 2 で実エンジン（KanaKanjiEngine+リランカー）を回し、Phase 3 で実機遅延と定数を較正する。
