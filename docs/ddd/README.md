# JotInput Domain-Driven Design (DDD) Architecture

## 1. 導入の目的と背景

JotInput は、完全オンデバイスで動作する日本語音声認識（ReazonSpeech）、Mozc ベースのかな漢字変換（Viterbi + 接続コスト行列 + mmap 辞書）、オンデバイス SLM リランキング（llama.cpp / Qwen2.5-0.5B）、英語スワイプ・スペル修正（FUTO Swipe / SymSpell）、カスタム辞書、クリップボード履歴を統合したマルチモーダル Android IME である。

従来の実装では、50を超えるクラスが単一パッケージ `com.example.myasr` 直下にフラットに配置されており、以下の課題が顕在化していた：

1. **境界の曖昧さ**: 音声認識・かな漢字変換・英語入力・SLMリランキング・アセットダウンロード・UI制御が同一名前空間に混在し、クラス間の暗黙的依存が増大していた。
2. **変更影響の局所化困難**: `AsrApp` や `VoiceInputMethodService` が全サブシステムの具体クラスに直接依存しており、コンテキスト間の契約（Contract）が不明確だった。
3. **ユビキタス言語の分断**: 同じ「候補」「辞書」「コンテキスト」という語が、ASR・Viterbi・SLM・Swipe・カスタム辞書で異なる意味で使われていた。

これらを解決するため、**戦略的DDD（Strategic DDD: コンテキストマップ ＋ Bounded Context Canvas）** および **戦術的DDD（Tactical DDD: 値オブジェクト・集約・ドメインサービス・リポジトリ・腐敗防止層・ドメインイベント）** を導入する。

---

## 2. ドキュメント構成

| ドキュメント | 内容 |
|---|---|
| [`README.md`](./README.md) | 本ドキュメント。DDD採用の目的・設計原則・パッケージ規約・プロジェクトポリシーとの整合性 |
| [`context_map.md`](./context_map.md) | **コンテキストマップ**：8つの Bounded Context の関係性、統合パターン（ACL / OHS / Published Language / Shared Kernel）、イベントフロー |
| [`bounded_context_canvases.md`](./bounded_context_canvases.md) | **Bounded Context Canvas**：全8コンテキストの Purpose、ユビキタス言語、ビジネスルール、入出力、戦術的モデル |
| [`tactical_design_and_refactoring_plan.md`](./tactical_design_and_refactoring_plan.md) | **戦術的設計と移行計画**：Shared Kernel、Domain Event Bus、ACL、全クラス/JNI/XML/テストの移行マッピング |

---

## 3. パッケージ構成規約（Package Structure Convention）

Android のコンポーネント解決互換性（`AndroidManifest.xml` や `deploy.sh` の `com.example.myasr/.MainActivity`・`com.example.myasr/.VoiceInputMethodService`）を完全に維持しつつ、アプリケーションルートを Composition Root / Entrypoint として残し、各 Bounded Context を明確なサブパッケージへ分離する。

```text
com.example.myasr
├── AsrApp.kt                          # Composition Root (Application Container & Event Wiring)
├── MainActivity.kt                    # Android Launcher Entrypoint (4-Card UI)
├── DictionaryActivity.kt              # Android Activity Entrypoint (Custom Vocabulary UI)
├── VoiceInputMethodService.kt         # Android InputMethodService Entrypoint
│
├── shared/                            # Shared Kernel & Published Language
│   ├── domain/                        #   値オブジェクト (InputLanguage, VocabularyItem, ConversionCandidate 等)
│   └── event/                         #   軽量インメモリ DomainEvent & DomainEventBus
│
└── context/                           # Bounded Contexts
    ├── kanakanji/                     # BC-1: Japanese Kana-Kanji Conversion [Core]
    │   ├── domain/                    #   KanaKanjiEngine, ConnectionMatrix, MmapDictionary, NextPhrasePredictor, Utils
    │   ├── application/               #   UserHistorySyncManager, VocabularySyncAdapter (ACL)
    │   └── infrastructure/            #   MmapDictionaryCompiler
    │
    ├── reranking/                     # BC-2: Neural Language Modeling & Reranking [Core]
    │   ├── domain/                    #   RerankEngineType, ContextWindowTrimmer, CandidateRerankingService
    │   └── infrastructure/            #   LlmReranker (JNI -> libmyasr_llm.so), PowerBenchmarkRunner
    │
    ├── speech/                        # BC-3: Speech Recognition & Voice Input [Supporting]
    │   ├── domain/                    #   StreamingVadProcessor, ProsodyPitchEstimator, JapanesePunctuationEngine, ContextAwareProcessor
    │   ├── application/               #   HotwordsGenerator (ACL), DictionaryPostProcessor (ACL)
    │   └── infrastructure/            #   AsrManager (Sherpa-ONNX), AudioRecorder
    │
    ├── english/                       # BC-4: English Typing & Swipe [Supporting]
    │   ├── domain/                    #   EnglishCapitalizationAnalyzer, EnglishDictionaryUtils
    │   └── infrastructure/            #   FutoSwipeEngine (JNI), EnglishSpellEngine (JNI)
    │
    ├── vocabulary/                    # BC-5: Custom Vocabulary [Supporting]
    │   ├── domain/                    #   DictionaryEntry, VocabularyChangedEvent
    │   ├── infrastructure/            #   DictionaryRepository, DictionaryDatabaseHelper
    │   └── presentation/              #   DictionaryAdapter
    │
    ├── provisioning/                  # BC-6: Asset & Model Provisioning [Generic]
    │   ├── domain/                    #   DictionaryUpdateTracker, ModelWarmUpManager
    │   └── infrastructure/            #   ModelDownloader, BaseDictionaryDownloader, MultiDictionaryManager,
    │                                  #   ConnectionDataManager, FutoSwipeDownloader, EnglishSpellDownloader
    │
    ├── ime/                           # BC-7: IME Session & Keyboard UI [Core Orchestration]
    │   ├── domain/                    #   VoiceTouchInputArbitrator, EditorBridge
    │   └── presentation/              #   FlickKeyboardView, FlickPopup*, CandidateStripView, KeyboardHeightManager
    │
    └── clipboard/                     # BC-8: Clipboard History [Generic]
        ├── domain/                    #   ClipboardSuggestionPolicy, ClipboardUtils
        ├── infrastructure/            #   ClipboardHistoryRepository
        └── presentation/              #   ClipboardHistoryView
```

---

## 4. 依存ルール（Dependency Rules）

1. **コンテキスト間の直接内部参照の禁止**:
   - あるコンテキストの `infrastructure` 実装詳細に、別コンテキストから直接依存しない。
   - コンテキスト間のデータ受け渡しは `shared.domain`（Published Language / Shared Kernel）または `shared.event`（Domain Event）、あるいは対象コンテキストの公開インターフェース（OHS / Domain Service）を経由する。
2. **腐敗防止層（Anti-Corruption Layer: ACL）の徹底**:
   - `vocabulary` コンテキストの `VocabularyItem` を、`kanakanji`（Mozc コスト体系）、`speech`（Sherpa-ONNX `cjkchar` スペース区切り形式）、`english`（C++ Trie / SymSpell 正規化形式）へ変換する処理は、各下流コンテキスト側の ACL 責務とする。
3. **ドメイン層の純粋性**:
   - `domain` パッケージ内の値オブジェクト・ドメインサービス・ポリシーは、可能な限り Android Framework UI (`View`, `Activity`) に依存せず、単体テスト（JVM Unit Test）で高速に検証可能であること。
4. **既存プロジェクト契約の絶対遵守**:
   - **Download-Only Architecture**: 大規模モデル・辞書を APK に同梱しない（`connection_data.bin` のみ例外）。
   - **Top-Line Voice Punctuation Contract**: `JapanesePunctuationEvaluationTest`（67+ テスト）を含む全ユニットテスト 100% パスを維持する。
   - **Anti-Anecdotal Tuning**: アドホックなコスト改変を行わない。
   - **4-Card UI Simplicity**: `MainActivity` の 4 カード構成を変更しない。
   - **Custom Dictionary Persistence**: `custom_dictionary.json` の空初期値とアップデート時非破壊を維持する。
