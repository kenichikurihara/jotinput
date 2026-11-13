# Tactical Design & Refactoring Plan

本ドキュメントは、[`README.md`](./README.md)・[`context_map.md`](./context_map.md)・[`bounded_context_canvases.md`](./bounded_context_canvases.md) で定義した戦略的設計を、実際の Kotlin / C++ JNI / XML / テストコードへ落とし込むための戦術的設計と段階的移行計画である。

---

## 1. Shared Kernel & Published Language (`com.example.myasr.shared`)

コンテキスト間の直接結合を断ち切るため、以下の最小限の共有モデルとイベントバスを導入する。

### 1.1 `shared.domain`
- **`InputLanguage`** (`enum class`): `JAPANESE`, `ENGLISH`, `OTHER`
- **`VocabularyPriority`** (`enum class`): `LOW`, `NORMAL`, `HIGH`
- **`VocabularyItem`** (`data class` — Published Language):
  - `id: String`, `word: String`, `reading: String`, `priority: VocabularyPriority`, `isEnabled: Boolean`, `language: InputLanguage`
  - `BC-5: Custom Vocabulary` の `DictionaryEntry` 内部表現を隠蔽し、下流コンテキストが消費する読み取り専用契約として公開する。
- **`CandidateSource`** (`enum class`): `KANA_KANJI`, `SLM_RERANKED`, `ENGLISH_SWIPE`, `ENGLISH_SPELL`, `VOICE_ASR`, `USER_DICTIONARY`, `NEXT_PHRASE`, `CLIPBOARD`
- **`ConversionCandidate`** (`data class` — Published Language):
  - `surface: String`, `reading: String`, `score: Float`, `source: CandidateSource`, `metadata: Map<String, String> = emptyMap()`
- **`Judgement<in I, out O>` / `JudgementResult<out T>` / `Specification<in T>`** (`shared/domain/Judgement.kt`):
  - ドメイン上の意思決定・判定ロジック（Policy / Specification / Decision）を Android UI / `InputMethodService` から分離し、理由付き判定結果と合成可能な仕様ルールを提供する基底契約。

### 1.2 `shared.event`
- **`DomainEvent`** (`interface`): `val occurredAtMillis: Long`
- **`DomainEventBus`** (`object` / テスト可能インスタンス):
  - `subscribe(eventClass, handler)` / `publish(event)`
  - スレッドセーフ（`CopyOnWriteArrayList` + `ConcurrentHashMap`）な軽量インメモリバス。
- **主要イベント**:
  - `UserDictionaryChangedEvent(val activeItems: List<VocabularyItem>, override val occurredAtMillis: Long)`
  - `TextCommittedEvent(val surface: String, val reading: String, val language: InputLanguage, override val occurredAtMillis: Long)`
  - `AssetProvisionedEvent(val assetCategory: String, val path: String, override val occurredAtMillis: Long)`

---

## 2. Anti-Corruption Layers (ACL) & Open Host Services (OHS)

### 2.1 `KanaKanjiVocabularySyncAdapter` (`context.kanakanji.application`)
- `UserDictionaryChangedEvent` を購読し、`List<VocabularyItem>` を `DictionaryEntry`（または `KanaKanjiEngine` の内部辞書エントリ）へ変換して `kanaKanjiEngine.syncUserDictionary(...)` を駆動する。
- `AsrApp` からの命令的呼び出しだけでなく、イベント駆動でも自動同期される二重安全構造とする。

### 2.2 `CandidateRerankingService` (`context.reranking.domain`)
- `BC-2: Neural Reranking` の Open Host Service インターフェース。
- `LlmReranker` (`context.reranking.infrastructure`) が実装し、`IME` や `KanaKanji` からはインターフェース経由でリランキングを要求できる。

---

## 3. 全ファイル移行マッピング（Complete File Mapping）

### 3.1 ルートパッケージ（Android Entrypoints & Composition Root — Manifest互換維持）
| クラス | 役割 | パッケージ |
|---|---|---|
| `AsrApp` | Composition Root（全コンテキスト初期化・EventBus配線） | `com.example.myasr` |
| `MainActivity` | 4-Card Launcher UI Entrypoint | `com.example.myasr` |
| `DictionaryActivity` | Custom Vocabulary UI Entrypoint | `com.example.myasr` |
| `VoiceInputMethodService` | Android `InputMethodService` Entrypoint | `com.example.myasr` |

### 3.2 Bounded Contexts パッケージ配置

| Bounded Context | パッケージ | 配置クラス |
|---|---|---|
| **BC-1: Kana-Kanji** | `context.kanakanji.domain` | `KanaKanjiEngine`, `ConnectionMatrix`, `MmapDictionary`, `NextPhrasePredictor`, `JapaneseKanaUtils`, `JapaneseNumeralUtils` |
| | `context.kanakanji.application` | `UserHistorySyncManager`, `KanaKanjiVocabularySyncAdapter` |
| | `context.kanakanji.infrastructure` | `MmapDictionaryCompiler` |
| **BC-2: Neural Reranking** | `context.reranking.domain` | `RerankEngineType`, `ContextWindowTrimmer`, `CandidateRerankingService`, `CandidateRerankJudgement` |
| | `context.reranking.infrastructure` | `LlmReranker`, `PowerBenchmarkRunner` |
| **BC-3: Speech Recognition** | `context.speech.domain` | `StreamingVadProcessor`, `ProsodyPitchEstimator`, `JapanesePunctuationEngine`, `ContextAwareProcessor` |
| | `context.speech.application` | `HotwordsGenerator`, `DictionaryPostProcessor` |
| | `context.speech.infrastructure` | `AsrManager`, `AudioRecorder` |
| **BC-4: English Typing** | `context.english.domain` | `EnglishCapitalizationAnalyzer`, `EnglishDictionaryUtils`, `EnglishAssistJudgement` |
| | `context.english.infrastructure` | `FutoSwipeEngine`, `EnglishSpellEngine` |
| **BC-5: Custom Vocabulary** | `context.vocabulary.domain` | `DictionaryEntry` |
| | `context.vocabulary.infrastructure` | `DictionaryRepository`, `DictionaryDatabaseHelper` |
| | `context.vocabulary.presentation` | `DictionaryAdapter` |
| **BC-6: Provisioning** | `context.provisioning.domain` | `DictionaryUpdateTracker`, `ModelWarmUpManager` |
| | `context.provisioning.infrastructure` | `ModelDownloader`, `BaseDictionaryDownloader`, `MultiDictionaryManager`, `ConnectionDataManager`, `FutoSwipeDownloader`, `EnglishSpellDownloader` |
| **BC-7: IME Session & UI** | `context.ime.domain` | `VoiceTouchInputArbitrator`, `EditorBridge` (+ `InputConnectionEditorBridge`) |
| | `context.ime.presentation` | `FlickKeyboardView`, `FlickPopupWindow`, `FlickPopupContentView`, `FlickPopupGeometry`, `CandidateStripView`, `KeyboardHeightManager` |
| **BC-8: Clipboard** | `context.clipboard.domain` | `ClipboardSuggestionPolicy`, `ClipboardUtils` |
| | `context.clipboard.infrastructure` | `ClipboardHistoryRepository` |
| | `context.clipboard.presentation` | `ClipboardHistoryView` |

---

## 4. C++ JNI & XML リソース整合性更新

パッケージ移動に伴い、以下を必ず同期更新する：

1. **C++ JNI シンボル名**:
   - `app/src/main/cpp/llm_reranker_jni.cc`:
     `Java_com_example_myasr_LlmReranker_*` $\to$ `Java_com_example_myasr_context_reranking_infrastructure_LlmReranker_*`
   - `app/src/main/cpp/swipe/futo_swipe_jni.cc`:
     `Java_com_example_myasr_FutoSwipeEngine_*` $\to$ `Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_*`
   - `app/src/main/cpp/spelling/english_spell_jni.cc`:
     `Java_com_example_myasr_EnglishSpellEngine_*` $\to$ `Java_com_example_myasr_context_english_infrastructure_EnglishSpellEngine_*`
2. **XML カスタムビュー参照**:
   - `app/src/main/res/layout/keyboard_view.xml`:
     - `com.example.myasr.CandidateStripView` $\to$ `com.example.myasr.context.ime.presentation.CandidateStripView`
     - `com.example.myasr.FlickKeyboardView` $\to$ `com.example.myasr.context.ime.presentation.FlickKeyboardView`
     - `com.example.myasr.ClipboardHistoryView` $\to$ `com.example.myasr.context.clipboard.presentation.ClipboardHistoryView`
3. **Unit Tests (42 ファイル)**:
   - テストクラスが新パッケージのクラスを正しく `import` するよう更新し、`internal` 可視性の調整が必要な箇所（例: `NextPhrasePredictor` のテスト用コンストラクタ）は適切に公開またはテストパッケージを合わせる。

---

## 5. 実行・検証ステップ

1. `shared/domain` と `shared/event` の新規実装
2. Bounded Context パッケージへのファイル移動と `package` / `import` / 可視性（`internal` $\to$ `public` または同コンテキストテスト）の更新
3. `CandidateRerankingService`・`KanaKanjiVocabularySyncAdapter`・`DomainEventBus` の配線（`AsrApp`, `DictionaryRepository`, `LlmReranker`）
4. C++ JNI シンボル名と XML カスタムビュー参照の更新
5. 全42ユニットテストの `import` 更新および新規 DDD 境界テスト（`DomainEventBusTest`, `VocabularySyncAdapterTest`）の追加
6. 静的整合性検証スクリプトによる全参照チェック
7. `origin/main` への push および GitHub Actions CI/CD (`Android CI`) でのフルビルド・全ユニットテスト 100% パス確認
