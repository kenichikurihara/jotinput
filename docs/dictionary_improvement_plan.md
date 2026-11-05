# Custom Dictionary Improvement & Evaluation Plan

This document details the root cause analysis of why registered dictionary words were not respected during recognition, outlines the technical improvement architecture, and establishes a rigorous **Evaluation Plan** to benchmark and guarantee dictionary adherence before implementing changes.

---

## 🔬 1. Root Cause Analysis (ARCVM Logcat Evidence)

When inspecting the Android system logs from ARCVM (`arc:5555`) during the user's test session at `14:32:09`, the native Sherpa-ONNX runtime logged the following critical warnings:

```
10-22 14:32:09.585 D AsrManager: Creating OfflineStream with dynamic hotwords (171 chars)
10-22 14:32:09.585 W sherpa-onnx: /home/runner/work/sherpa-onnx/sherpa-onnx/sherpa-onnx/csrc/utils.cc:EncodeBase:68
10-22 14:32:09.585 W sherpa-onnx: Cannot find ID for token ReazonSpeech at line: ReazonSpeech :4.5. (Hint: Check the tokens.txt see if ReazonSpeech in it)
10-22 14:32:09.585 W sherpa-onnx: /home/runner/work/sherpa-onnx/sherpa-onnx/sherpa-onnx/csrc/utils.cc:EncodeBase:68
10-22 14:32:09.585 W sherpa-onnx: Cannot find ID for token DX at line: DX :3.0. (Hint: Check the tokens.txt see if DX in it)
10-22 14:32:09.586 W sherpa-onnx: /home/runner/work/sherpa-onnx/sherpa-onnx/sherpa-onnx/csrc/offline-recognizer-transducer-impl.h:CreateStream:172
10-22 14:32:09.586 W sherpa-onnx: Encode hotwords failed, skipping, hotwords are : 'ReazonSpeech :4.5/れいぞんすぴーち :4.5/レイゾンスピーチ :4.5/中村 :3.0/なかむら :3.0/ナカムラ :3.0/貴社 :3.0/きしゃ :3.0/キシャ :3.0/DX :3.0/でぃーえっくす :3.0/ディーエックス :3.0/音声認識 :3.0/おんせいにんしき :3.0/オンセイニンシキ :3.0'
```

### The Two Compounding Breakdowns:

#### Breakdown A: Tokenization Format Mismatch (Total Hotword Drop)
1. **The Model's Vocabulary**: ReazonSpeech's `tokens.txt` is character-level (`cjkchar`). Individual characters (`R`, `e`, `a`, `z`, `o`, `n`, `中`, `村`, `な`, `か`, `む`, `ら`) exist as distinct token IDs. Single multi-character words (`ReazonSpeech`, `中村`, `DX`) **do not exist** as individual tokens.
2. **Sherpa-ONNX Expectation**: When parsing hotwords for a `cjkchar` model, Sherpa-ONNX splits each line by spaces. If given `中村 :3.0`, it searches for a single token named `"中村"`. It does **not** automatically segment the string into characters.
3. **All-or-Nothing Failure**: In Sherpa-ONNX, if **any single token** in the hotwords input fails to resolve (`Encode hotwords failed`), **the runtime drops the entire hotword graph**:
   ```
   Encode hotwords failed, skipping, hotwords are : ...
   ```
   **Consequence**: The acoustic beam search ran completely unassisted with **zero** hotwords in memory.

#### Breakdown B: Post-Processing Brittleness
- The post-processor currently relies on exact substring replacement of the raw Kana reading (e.g. `なかむら` $\rightarrow$ `中村`).
- If the un-assisted acoustic model transcribed the sound as a competing Kanji homophone (e.g. `記者` instead of `貴社`) or with slightly altered acoustic tokens, the exact Kana matcher never found `きしゃ` in the text and did not trigger.

---

## 🛠️ 2. The Improvement Plan

To guarantee strong dictionary adherence, we must fix both the **Acoustic In-Beam Channel** and the **Phonetic/Homophone Correction Channel**:

```
+-----------------------------------------------------------------------------------------+
| [Improvement 1] Token-Aware Hotwords Formatter                                          |
| - Inspects tokens.txt directly                                                          |
| - Splits CJK characters & Latin letters into valid space-separated tokens:             |
|   "R e a z o n S p e e c h :4.5"                                                        |
|   "中 村 :3.0"                                                                          |
|   "な か む ら :3.0"                                                                    |
| - Verifies and discards invalid tokens to prevent batch drop                            |
+-----------------------------------------------------------------------------------------+
                                             |
                                             v
+-----------------------------------------------------------------------------------------+
| [Improvement 2] Calibrated Beam Biasing                                                 |
| - Increases boosting score for High Priority (e.g., from 4.5 up to 6.0)                 |
| - Sets beam search parameters (maxActivePaths = 8) to maintain biased candidate paths   |
+-----------------------------------------------------------------------------------------+
                                             |
                                             v
+-----------------------------------------------------------------------------------------+
| [Improvement 3] Multi-Tier Homophone & Fuzzy Post-Processor                             |
| - Level 1: Exact Kana match ("なかむら" -> "中村")                                       |
| - Level 2: Competing Homophone swap (e.g., if reading is "きしゃ", map 記者 -> 貴社)    |
| - Level 3: Fuzzy phonetic matching (Levenshtein distance <= 1 mora for rare names)     |
+-----------------------------------------------------------------------------------------+
```

### Detailed Technical Fixes:

1. **Character-Level Space-Separated Hotwords**:
   Format every hotword rule by inserting spaces between individual tokens:
   - User Input: `Word: "中村"`, `Reading: "なかむら"`
   - Output to Sherpa-ONNX:
     ```text
     中 村 :3.5
     な か む ら :3.5
     ク リ ハ ラ :3.5
     R e a z o n S p e e c h :5.0
     れ い ぞ ん す ぴ ー ち :5.0
     ```
2. **Token Set Validation**:
   Load `tokens.txt` into an in-memory `Set<String>`. Before passing any token to the hotwords builder, verify that every character exists in `tokens.txt`. Skip unsupported symbols instead of failing the whole stream.
3. **Phonetic Homophone Lookup Table**:
   Build an internal mapping of frequent Japanese homophones. When a user registers `(貴社, きしゃ)`, automatically register competing homophones (`記者`, `汽車`, `帰社`) into the replacement pipeline.

---

## 📊 3. The Evaluation Plan (Rigorous Benchmarking)

Before and after applying fixes, we must quantitatively measure dictionary adherence, accuracy, and latency.

### A. Evaluation Metrics

1. **Target Word Recognition Rate (TWRR / Recall)**:
   $$\text{TWRR} = \frac{\text{Number of correctly transcribed target dictionary words}}{\text{Total spoken occurrences of target words}} \times 100\%$$
   - *Target*: $\ge 95\%$ for High Priority words; $\ge 85\%$ for Normal Priority words.
2. **False Positive Rate (FPR / Hallucination)**:
   $$\text{FPR} = \frac{\text{Number of incorrectly injected dictionary words}}{\text{Total audio files NOT containing target words}} \times 100\%$$
   - *Target*: $< 2\%$ (ensuring hotword scores don't cause random hallucination).
3. **Character Error Rate (CER)**:
   $$\text{CER} = \frac{S + D + I}{N} \times 100\%$$
   - Evaluated across full Japanese test sentences to ensure overall sentence quality is preserved.
4. **Real-Time Factor (RTF) & Latency**:
   $$\text{RTF} = \frac{\text{Decode Time (ms)}}{\text{Audio Duration (ms)}}$$
   - *Target*: $\text{RTF} \le 0.40$ on mobile CPU.

---

### B. Test Dataset Matrix (20 Test Cases)

We will construct an evaluation suite with 20 distinct Japanese audio scenarios (16kHz mono WAV):

| ID | Test Category | Target Word | Reading | Test Spoken Sentence | Expected Output |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **T01** | Proper Name | `中村` | `なかむら` | 担当の中村さんに連絡しました | `担当の中村さんに連絡しました` |
| **T02** | Proper Name | `尾崎` | `おざき` | 尾崎先生の研究室です | `尾崎先生の研究室です` |
| **T03** | Tech Brand | `ReazonSpeech` | `れいぞんすぴーち` | ReazonSpeechの精度をテストします | `ReazonSpeechの精度をテストします` |
| **T04** | Tech Brand | `GitHub` | `ぎっとはぶ` | GitHubにコードをコミットしました | `GitHubにコードをコミットしました` |
| **T05** | Acronym | `DX` | `でぃーえっくす` | 全社的なDXを推進します | `全社的なDXを推進します` |
| **T06** | Acronym | `ASR` | `えーえすあーる` | オンデバイスASRのアプリです | `オンデバイスASRのアプリです` |
| **T07** | Homophone | `貴社` | `きしゃ` | 明日貴社に訪問いたします | `明日貴社に訪問いたします` (not 記者) |
| **T08** | Homophone | `異動` | `いどう` | 部署の異動が決まりました | `部署の異動が決まりました` (not 移動) |
| **T09** | Homophone | `校正` | `こうせい` | 原稿の校正をお願いします | `原稿の校正をお願いします` (not 構成) |
| **T10** | Orthography | `打ち合わせ` | `うちあわせ` | 3時から打ち合わせです | `3時から打ち合わせです` (not 打合せ) |
| **T11** | Orthography | `サーバー` | `さーばー` | クラウドサーバーを構築する | `クラウドサーバーを構築する` (not サーバ) |
| **T12** | Compound | `音声認識` | `おんせいにんしき` | 高度な音声認識モデル | `高度な音声認識モデル` |
| **T13** | Compound | `マイナンバー` | `まいなんばー` | マイナンバーカードの提示 | `マイナンバーカードの提示` |
| **T14** | Rare Name | `草薙` | `くさなぎ` | 草薙駅で待ち合わせ | `草薙駅で待ち合わせ` |
| **T15** | Rare Tech | `Zipformer` | `じっぷふぉーまー` | Zipformerアーキテクチャ | `Zipformerアーキテクチャ` |
| **T16** | Negative (No Target) | *(None)* | *(None)* | 今日の天気は晴れです | `今日の天気は晴れです` (No dictionary hallucination) |
| **T17** | Negative (No Target) | *(None)* | *(None)* | 新幹線で東京に向かいます | `新幹線で東京に向かいます` (No `貴社` injection) |
| **T18** | Negative (No Target) | *(None)* | *(None)* | 自動車の運転に注意する | `自動車の運転に注意する` |
| **T19** | Edge Case (Multiple) | `中村` + `DX` | `なかむら` + `でぃーえっくす` | 中村さんとDXについて話す | `中村さんとDXについて話す` |
| **T20** | Edge Case (Multiple) | `ReazonSpeech` + `貴社` | `れいぞんすぴーち` + `きしゃ` | 貴社のReazonSpeech導入事例 | `貴社のReazonSpeech導入事例` |

---

### C. Automated Evaluation Harness Implementation

We will implement an automated evaluation script (`evaluate_dictionary.py`, a planned script that is not present in the repository; see [`eval/`](eval) for the evaluation scripts that exist) that:
1. Feeds the 20 test audio clips through the Sherpa-ONNX offline engine on Linux / Android.
2. Compares four configurations:
   - **Baseline (Configuration A)**: Default greedy search without dictionary.
   - **Current Flawed Hotwords (Configuration B)**: Unseparated hotwords (verifying the `Encode hotwords failed` reproduction).
   - **Fixed Hotwords (Configuration C)**: Space-separated tokenized hotwords.
   - **Full Hybrid Pipeline (Configuration D)**: Fixed Hotwords + Homophone Post-Processor.
3. Automatically computes **TWRR**, **CER**, **FPR**, and **RTF** across all 20 test cases and outputs a Markdown comparison table.

---

## 📋 4. Review & Sign-Off Checkpoints Before Implementation

Before beginning code changes, please review the following questions:

> [!IMPORTANT]
> ### Key Review Questions:
> 1. **Default Starter Dictionary**: Should terms like `ReazonSpeech`, `中村`, `貴社`, `DX` remain pre-loaded as examples, or should the dictionary start completely empty for a fresh install?
> 2. **Boosting Score Level**: Is a boosting score of $+4.0 \sim +5.0$ acceptable for High Priority, or should we expose a custom numeric slider in the UI?
> 3. **Homophone Aggressiveness**: When a user registers `(貴社, きしゃ)`, should the app automatically replace competing words (`記者`, `汽車`) everywhere, or only when the acoustic reading matches `きしゃ`?
> 4. **Evaluation Audio**: Should we generate the 20 benchmark test WAV files using standard Japanese Text-to-Speech (e.g. Kokoro / Piper / Google TTS) for reproducible automated evaluation?

Once you review and approve this plan, we will implement the test harness, evaluate the baseline, and deploy the fixes.
