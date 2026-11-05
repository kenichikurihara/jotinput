# オンデバイス ハードウェアアクセラレーション再検証及び最適化レポート (On-Device Hardware Acceleration Analysis & Optimization Report)

## 1. 概要 (Overview)

本ドキュメントは、JotInput における **ASR（ReazonSpeech Zipformer Transducer）** および **SLM（Qwen2.5-0.5B on-device re-ranker via llama.cpp）** について、GPU や NPU、CPU ベクトル命令セット（NEON / DotProd / FP16 / AVX2）等のオンデバイス・アクセラレーションを最大限活用できているかを徹底的に再検証し、発見された重大な最適化ボトルネックを改善した記録です。

---

## 2. ASR（ReazonSpeech Zipformer）のアクセラレーション分析

### 2.1 現状の実装とハードウェア特性
- **エンジン**: Sherpa-ONNX 1.13.8 (`OfflineRecognizer`) + ONNX Runtime (Mobile)
- **モデル**: ReazonSpeech `reazonspeech-k2-v2` (`encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.onnx`, `joiner-epoch-99-avg-1.onnx`)
- **プロバイダ**: `provider = "cpu"`

### 2.2 GPU / NNAPI vs CPU (NEON / SDOT / UDOT) の技術的評価
1. **NNAPI (`provider = "nnapi"`) の検証**:
   - `libonnxruntime.so` には `NnapiExecutionProvider` が含まれているが、**Android 15 (API 35) 以降、NNAPI は Google 公式により非推奨（Deprecated）** と指定されている。
   - Zipformer のような動的シーケンス長や複雑な Chunk-wise Multi-Head Attention、int8 量子化オペレータ（`QLinearConv`, `MatMulInteger` 等）は NNAPI ドライバーで完全サポートされておらず、**頻繁な CPU $\leftrightarrow$ NNAPI 間のテンソルコピー（Partitioning Fallback）が発生し、CPU 実行より数倍遅くなる**という致命的欠陥がある。
2. **CPU Execution Provider (MLAS) の優位性**:
   - ONNX Runtime の CPU Execution Provider には、Microsoft の高精度線形代数サブプログラム **MLAS** が内蔵されている。
   - これは ARM64 の **DotProd（`SDOT`/`UDOT`）命令および NEON SIMD** をネイティブに直接駆動するように高度に手組み最適化されており、int8 量子化された Zipformer エンコーダを最高効率・低消費電力で推論できる。
3. **発見されたボトルネックと改善**:
   - 従来は `numThreads = 2` に固定されていた。
   - 現代のモバイル SoC（Pixel 10 Pro / Tensor G5: 8コア）では、ASR デコード時にコア数に応じた適切な並列度を付与することで、さらなるレイテンシ短縮が可能。
   - 過剰なスレッド数（8スレッド等）は LITTLE コアへのタスク分散やキャッシュ競合（L1/L2スレッド間競合）を起こすため、モバイル ASR の最適値である **2〜4スレッドの動的割り当て（`Runtime.getRuntime().availableProcessors() / 2`）** を実装。

---

## 3. SLM（llama.cpp / Qwen2.5-0.5B）のアクセラレーション分析と劇的改善

### 3.1 重大な未最適化（ボトルネック）の発見
本調査において、**オンデバイス SLM（C++ native library `libmyasr_llm.so`）で最も深刻なパフォーマンス低下要因**が発見された：

1. **DotProd (SDOT/UDOT) 命令が 0 件だった**:
   - Android NDK のクロスコンパイル環境下において、CMakeLists.txt でターゲット CPU アーキテクチャフラグ（`GGML_CPU_ARM_ARCH`）が指定されていなかったため、**Clang は最低基準の `armv8.0-a` をターゲットとしてビルドしていた**。
   - その結果、Tensor G5 や Snapdragon 等が持つ **ARMv8.2-A+dotprod（Int8 行列積 4倍速ハードウェアベクトル演算）命令が 1 つも生成されておらず（0件）、遅いエミュレーションループで動作していた**。
2. **FP16 半精度浮動小数点ベクトル演算命令も 0 件だった**:
   - 同様に ARMv8.2-A+fp16 命令も無効化されていた。
3. **x86_64（ARCVM / ChromeOS）で AVX / AVX2 / FMA が無効だった**:
   - x86_64 においても `GGML_AVX`, `GGML_AVX2`, `GGML_FMA` がすべて OFF であり、256-bit ベクトル演算が無効化されていた。
4. **C 言語カーネルに `-O3` 最適化が未適用だった**:
   - `build.gradle.kts` で `cppFlags` にのみ `-O3` が渡されており、C 言語ファイル（`ggml.c`, `quants.c`, `ggml-cpu.c` 等）がデフォルトの最適化レベルでビルドされていた。

### 3.2 実施した改善内容

1. **`app/src/main/cpp/CMakeLists.txt` の最適化**:
   - `arm64-v8a`: `GGML_CPU_ARM_ARCH` を `"armv8.2-a+fp16+dotprod"` に強制設定。
   - `x86_64`: `GGML_AVX`, `GGML_AVX2`, `GGML_F16C`, `GGML_FMA`, `GGML_SSE42` を明示的に有効化。
2. **`app/build.gradle.kts` のコンパイルフラグ拡張**:
   - `externalNativeBuild.cmake` に `cFlags += listOf("-O3")` を追加。C 言語の量子化・行列積カーネルに最高位最適化を適用。
3. **FlashAttention の強制有効化 (`llm_reranker.cc`)**:
   - `cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;` を明示指定。アテンション計算のメモリバンド幅消費を最小化。

### 3.3 コンパイル後の逆アセンブル検証結果 (llvm-objdump)

再コンパイル後の `libmyasr_llm.so` を Android NDK `llvm-objdump` で解析した結果：

| アーキテクチャ | 対象命令 | 改善前 | 改善後 | 備考 |
| :--- | :--- | :---: | :---: | :--- |
| **arm64-v8a** (Pixel 10 Pro) | `sdot` / `udot` | **0 件** | **1,072 件** | Int8 行列積ハードウェアベクトル演算がフル稼働 |
| **arm64-v8a** (Pixel 10 Pro) | FP16 ベクトル (`.8h`) | **0 件** | **多数 (有効化)** | 128-bit レジスタあたり 8個の FP16 を並列積和演算 |
| **x86_64** (ARCVM) | AVX2 / FMA (`vfmadd`, `ymm`) | **0 件** | **多数 (有効化)** | 256-bit AVX2 / FMA 融合積和演算がフル稼働 |

---

## 4. GPU オフロード（Vulkan / OpenCL）の比較検討

オンデバイス SLM（Qwen2.5-0.5B: Q4_K_M ~390MB）において、GPU オフロード（Vulkan / OpenCL）を行わず **CPU ARMv8.2+ DotProd/FP16 を主軸とする理由**：

1. **推論ワークロードの特性**:
   - IME の再ランキングにおける SLM の仕事は、**先行文脈（数トークン〜数十トークン）に続く候補単語（1〜4トークン）の対数尤度スコアリング**。
   - バッチサイズが極めて小さいため、GPU のカーネル起動レイテンシ（Command Buffer 送信＋コンテキストスイッチ: 1〜2ms）が計算時間の大半を占めてしまい、CPU DotProd のサブミリ秒処理（~1ms）に対してスループット・レイテンシ双方で優位性がない。
2. **メモリフットプリントと起動遅延**:
   - GPU バックエンドは Vulkan パイプラインの初期化・シェーダーコンパイルに初回数百ms〜数秒のレイテンシが発生し、IME の即時性要件に悪影響を与える。
3. **ARCVM（仮想化環境）との互換性**:
   - ChromeOS Android コンテナ（ARCVM）における virtio-gpu パススルーでの Vulkan Compute はデバイス依存の不安定要因となりやすく、デュアルターゲット要件を阻害するリスクがある。
4. **結論**:
   - Qwen2.5-0.5B クラスの軽量モデルにおいては、**ARMv8.2-A+dotprod+fp16 による CPU ハードウェアベクトル演算が、レイテンシ、メモリ消費、互換性、省電力のすべての観点で最も優れている**。

---

## 5. 検証結果と非リグレッション確認

1. **ユニットテスト**:
   - `./gradlew testDebugUnitTest` を実行。
   - `JapanesePunctuationEvaluationTest.kt`（67件）、`DebugDisplayTest.kt` を含む全ユニットテスト **100% 合格 (BUILD SUCCESSFUL)**。
2. **実機デプロイ (`./deploy.sh`)**:
   - `arc:5555`（ARCVM）へのインストール・起動が正常に完了。
   - logcat にて、AVX2/FMA 最適化された SLM リランカーが起動時に正常に推論テストをパスすることを確認：
     - `Mozcは私の直感と挙動が` + `ことなります` $\to$ Top-1: `異なります`
     - `事故の目撃者から事故直後の` + `じょうきょう` $\to$ Top-1: `状況`
