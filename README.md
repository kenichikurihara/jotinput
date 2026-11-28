# JotInput - オンデバイス日本語・英語 音声＋フリック／QWERTY キーボード（Android）

> 🌐 **Language / 言語**: **日本語（このページ）** | [**English (`README.en.md`)**](README.en.md)

> **JotInput** = **J**apanese **O**n-device **T**ext **Input**

**ReazonSpeech**・**NVIDIA Parakeet**・**Sherpa-ONNX** を搭載し、Mozc ベースのかな漢字変換、ニューラルスワイプ入力（**Powered by FUTO Swipe**）、およびオプションのオンデバイス **Qwen3.5** SLM 候補リランキング（`llama.cpp`）を統合した、軽量かつ 100% オフライン・完全オンデバイス動作の Android 日本語＆英語音声＋フリック／QWERTY キーボード（IME）です。

- **ドキュメント**: [日本語インデックス (`docs/index.md`)](docs/index.md) · [English Index (`docs/index.en.md`)](docs/index.en.md)
- **ライセンス**: [Apache License 2.0](LICENSE) · [サードパーティ表記 (`NOTICE`)](NOTICE)
- **プライバシー・セキュリティ**: [プライバシーポリシー (`PRIVACY.md`)](PRIVACY.md) · [セキュリティポリシー (`SECURITY.md`)](SECURITY.md)
- **コントリビュート・リリース**: [コントリビュートガイド (`CONTRIBUTING.md`)](CONTRIBUTING.md) · [変更履歴 (`CHANGELOG.md`)](CHANGELOG.md)

---

## 📖 ドキュメント

詳細な技術ドキュメントは `docs/` フォルダにあります（[**日本語インデックス (`docs/index.md`)**](docs/index.md) · [**English Index (`docs/index.en.md`)**](docs/index.en.md)）：
- [トップレベル PRD & North Star Metric (EIS)](docs/prd_top_level.md): プロダクトビジョン、優先順位付き CUJ、最上位メトリック（有効入力速度: Effective Intended-text Speed）とリリースゲート。
- [システムアーキテクチャ](docs/architecture.md): 音声パイプライン、辞書サブシステム、キーボード（IME）、SLM リランキング、コンポーネント構成とデータフロー。
- [ユーザー辞書改善＆評価計画](docs/dictionary_improvement_plan.md): 根本原因ポストモーテム、空白トークナイズ修正、20ケース定量評価ベンチマーク。
- [ユーザー辞書実装計画](docs/dictionary_implementation_plan.md): リポジトリ、ホットワード、後処理、UI の段階的設計。
- [ユーザー辞書ソリューション](docs/dictionary_solutions.md): 日本語 ASR 向けのデュアルキー `(表記, よみ)` アーキテクチャ、文脈バイアス、同音異義語解決。
- [統一英語・日本語ユーザー辞書 PRD & 設計](docs/prd_and_design_unified_english_dictionary.md): 日英統一辞書 UI、英語の単一よみ入力、FUTO Swipe Trie 同期、空間タイポ候補補正。
- [ReazonSpeech モデル＆オンデバイス最適化](docs/reazonspeech_model.md): Zipformer Transducer アーキテクチャ、int8 量子化、レイテンシ／メモリ分析。
- [セットアップ・ビルド・デプロイガイド](docs/setup_and_development.md): 前提環境、ビルド手順、ChromeOS ARCVM / 実機へのインストール方法。

---

## 📱 対応デバイス（サポート階層）

JotInput は **Google Pixel 10 Pro** と **ChromeOS ARCVM** のみを実機保有する**単独メンテナ**によって開発されています：

| 階層 | 対象 | サポートレベル |
| :--- | :--- | :--- |
| **Tier 1（主要）** | **Google Pixel 10 Pro**（実機）＆ **ChromeOS ARCVM**（`x86_64`）、Android 8.0 以上（API 26+） | メンテナ検証済み。リグレッションはリリースをブロックします。 |
| **Tier 2（ベストエフォート）** | その他の `arm64-v8a` / `x86_64` 端末（標準／AOSP 互換の Android 8.0 以上） | 標準 Android API によるコミュニティサポート（PR にはログ・スクリーンショット・テストが必要。[`CONTRIBUTING.md`](CONTRIBUTING.md) 参照）。 |
| **Tier 3（非対応）** | 32bit ABI（`armeabi-v7a`, `x86`）、Android 8.0 未満、カスタム ROM ハック、OEM 独自の回避策 | サポート対象外。 |

---

## 🌟 主な機能

- **100% オンデバイス＆テレメトリゼロ**: 音声・入力キーストローク・クリップボードプレビュー・ユーザー辞書は端末外へ一切送信されません。解析 SDK・広告 SDK・クラウド API は不使用です（[`PRIVACY.md`](PRIVACY.md)）。
- **日本語＆英語（オプション）音声認識**:
  - **日本語音声認識**: `sherpa-onnx` 経由の ReazonSpeech `reazonspeech-k2-v2` Zipformer int8（展開後約 169 MB）を搭載。ストリーミング VAD（`StreamingVadProcessor`）および保護語彙の誤爆防止を備えた日本語音声句読点復元（`JapanesePunctuationEngine`）に対応。
  - **英語音声認識（オプション）**: **NVIDIA Parakeet TDT-CTC 110M INT8**（`EnglishAsrModelDownloader`、展開後約 126 MB）をオンデマンドでダウンロード可能。
- **Mozc ベースかな漢字変換キーボード＆オンデマンド辞書**:
  - システム標準 IME（`VoiceInputMethodService`）として動作し、フリック／QWERTY／記号／数字レイアウト、ヘッダクリップボード貼り付け、辞書ショートカットを装備。
  - コンパクトな同梱 2D 連接コスト行列（`connection_data.bin`、約 14.28 MB）と、オンデマンド HTTPS ダウンロード可能な Mozc 基本辞書・SudachiDict・jawiki・地名／人名・絵文字辞書（`MultiDictionaryManager`, `BaseDictionaryDownloader`）を用いた Viterbi かな漢字変換（`KanaKanjiEngine`）。
- **ニューラルスワイプ入力＆英語スペル補正**:
  - **Powered by FUTO Swipe**: FUTO Swipe の空間 1D-CNN/DFSMN＋ContextLM モデルをクリーンルーム ONNX Runtime ネイティブ C++ で統合（`FutoSwipeEngine`, `FutoSwipeDownloader`）。自動単語間スペース挿入やワンタップでのスワイプ単語削除に対応。
  - **空間タイポ補正**: `EnglishSpellEngine`（`gl-keyboard-correction` / SymSpell, `EnglishSpellDownloader`）により英語のスペル修正候補を候補ストリップへ表示。
- **オンデバイス SLM 候補リランキング（オプション）**:
  - `LlmReranker`（`llama.cpp`）により、アプリ内から `SlmModelDownloader` でダウンロード（または `tools/push_model_to_device.sh` で配置）した量子化 **Qwen3.5-0.8B / 2B GGUF** モデルを利用可能。
- **日英統一ユーザー辞書（音声入力・スワイプ入力でも尊重）**:
  - 本プロジェクトの大切な動機の一つは、フリック／QWERTY タップ入力だけでなく、**日本語・英語の音声入力**や**英語のスワイプ入力**でもユーザー辞書を一貫して尊重することです。
  - 永続的な統一 JSON リポジトリ（`custom_dictionary.json`、初期状態は空でアプリ更新時も保持、`android:allowBackup="false"`）。
  - 英語の「よみ」を入力すると「表記」欄が自動で折りたたまれ、登録語は日本語 ASR のホットワード音響バイアス／音韻置換（`HotwordsGenerator`, `DictionaryPostProcessor`）、英語音声のユーザー辞書優先解決（`EnglishVoiceUserDictionaryResolver`）、ネイティブ C++ FUTO Swipe Trie（`FutoSwipeEngine`）、および空間タイポ／スペル候補（`EnglishSpellEngine`）へ自動同期されます。
- **コンパクトな APK ＆ モジュール式ダウンロード設計**:
  - 署名済み Release APK は **約 29 MB**（Debug APK は **約 36 MB**）、`arm64-v8a` ＆ `x86_64` 対応（`arm64-v8a` は **16KB ELF ページアラインメント**対応）。
  - APK に同梱されるのは `app/src/main/assets/mozc/connection_data.bin`（約 14.28 MB）のみ。重いニューラルモデルや大規模辞書は `MainActivity` の 3 タブ（`ホーム` / `設定` / `データ`）からアプリ内 HTTPS ダウンローダ経由で端末ストレージへ保存します。

---

## 🏗️ アーキテクチャ

```mermaid
flowchart LR
    Mic[マイク 16kHz PCM] --> Rec[AudioRecorder / StreamingVadProcessor]
    Rec --> Asr[AsrManager: sherpa-onnx Recognizer<br/>ReazonSpeech JA / Parakeet EN]
    Asr --> Post[DictionaryPostProcessor + JapanesePunctuationEngine]
    Post --> IME[VoiceInputMethodService]
    Keys[FlickKeyboardView] --> Kana[KanaKanjiEngine]
    Kana --> SLM[LlmReranker (Qwen3.5-0.8B / 2B GGUF)]
    SLM --> IME
    Kana --> IME
    IME --> App[対象アプリの入力欄]
    Main[MainActivity: ホーム / 設定 / データ] -.-> Asr
```

詳細は [システムアーキテクチャ](docs/architecture.md) を参照してください。

---

## 📥 v0.1.0 リリース APK のインストール（一般ユーザー向け）

> 公式パッケージ ID: `io.github.kenichikurihara.jotinput`（Android 8.0 以上 / API 26+、`arm64-v8a` または `x86_64`）。

1. [GitHub Releases](https://github.com/kenichikurihara/jotinput/releases) から `jotinput-0.1.0-universal-release.apk` と `SHA256SUMS.txt` をダウンロードします。
2. *（推奨）* インストール前にダウンロードの整合性と APK 署名を検証します：
   ```bash
   sha256sum -c SHA256SUMS.txt
   apksigner verify --print-certs jotinput-0.1.0-universal-release.apk
   ```
3. `jotinput-0.1.0-universal-release.apk` を Android 端末に転送し、プロンプトに従って **不明なアプリのインストール** を許可してインストールします（USB 経由の場合：`adb install -r jotinput-0.1.0-universal-release.apk`）。
4. **JotInput** を開き、マイク権限を許可して **ホーム** / **データ** タブから音声モデルと辞書を HTTPS 経由でダウンロードします。
5. Android の「言語と入力 / キーボード設定」で **JotInput** を有効にし、使用する入力方法を **JotInput** に切り替えます。

> **補足**: Release ビルド・Debug ビルドともに、**ホーム** タブにトークン不要のアプリ内アップデータを搭載しています。**リリース版**（Release）と **リモートヘッド**（Remote HEAD）チャンネルを切り替えられ、SHA-256 と署名鍵の互換性チェックも行われます（Debug 署名と Release 署名の間で切り替える場合は、一度アンインストールしてから再インストールしてください）。

---

## 🛠️ ソースからのビルド＆テスト（開発者向け）

### 前提条件
- **Android 8.0 (API 26)** 以上が動作する Android 実機またはエミュレータ（`arm64-v8a` または `x86_64`。32bit ABI は非対応）。
- Android SDK（`platform-tools`）および NDK 26.3（ネイティブ `llama.cpp` / C++ エンジン用）。

### ビルド・単体テスト・端末配備
```bash
# 単体テスト・句読点テスト・Konsist DDD 検査・Alloy 6 形式検証を実行
./gradlew testDebugUnitTest

# Debug APK をビルド (app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# 任意: モデルを tools/models/ へ事前取得し、接続端末へ Debug ビルドを配備
./download_model.sh
./deploy.sh
```

> Release ビルド（`./gradlew assembleRelease`）は、ローカルでリリース署名用の環境変数またはプロパティが設定されていない限り未署名 APK を生成します（[`SECURITY.md`](SECURITY.md) および [`app/build.gradle.kts`](app/build.gradle.kts) 参照）。

---

## 📱 使い方

1. **JotInput** を起動し、求められたらマイク権限を許可します。
2. **ホーム** / **データ** タブから ReazonSpeech モデルと Mozc 辞書をダウンロードします（英語 ASR・FUTO Swipe・英語スペル補正・Qwen3.5 GGUF SLM モデルも必要に応じて追加できます）。
3. **音声キーボードを有効にする** をタップしてキーボードを有効化し、使用する IME を **JotInput** に切り替えます。
4. 任意のテキスト入力欄（アプリ内の **キーボードテスト** 欄など）をタップしてキーボードを使います：
   - ヘッダの **🎙 話す** をタップして発話し、**完了** をタップします（ストリーミングモードでは、音声句読点とあわせてハンズフリーで文境界ごとに確定されます）。
   - フリック／QWERTY キーボードでの入力や、英語 QWERTY レイアウトでのスワイプ入力を行い、ヘッダの候補ストリップから候補を選択します。
5. **Dict** ショートカットまたは **データ** タブから日本語・英語のユーザー単語を登録します。

---

## 📄 ライセンス・プライバシー・コントリビュート

- [**Apache License 2.0**](LICENSE) の下でライセンスされています。
- サードパーティソフトウェア、モデル重み、辞書帰属表記（**"Powered by FUTO Swipe"** を含む）は [**`NOTICE`**](NOTICE) に記載しています。
- プライバシーの詳細は [**`PRIVACY.md`**](PRIVACY.md)、脆弱性報告手順は [**`SECURITY.md`**](SECURITY.md) を参照してください。
- PR を作成する前に、単独メンテナ体制・Issue-First ルール・マルチデバイス検証証跡の要件について [**`CONTRIBUTING.md`**](CONTRIBUTING.md) をご確認ください。
