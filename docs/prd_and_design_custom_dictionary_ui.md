# PRD & System Design: ユーザー辞書（単語登録）UI刷新

## Part 1: Product Requirements Document (PRD)

### 1. 概要 (Executive Summary)
JotInput のユーザー辞書画面（[`DictionaryActivity`](app/src/main/java/com/example/myasr/DictionaryActivity.kt)）は、音声認識（ASR）のホットワード強調とかな漢字変換（Mozc Viterbi）・英語スワイプ/スペル補完の両方に即時反映される重要な機能である。
しかし従来のUIは、画面最上部に巨大な「学習履歴 ＆ クラウド同期」カードが固定配置されており、本来の主目的である「単語をサッと登録・検索・編集する」体験が圧迫されていた。また、英単語登録時に「よみ」欄へ入力させる不自然な入力順序や、検索・Undo（削除取り消し）の欠如、内部スコア数値（`+3.0`, `+4.5`）の露出など、日常利用における摩擦（Friction）が大きかった。

本 PRD では、**Jobs to be Done (JTBD)** および **Critical User Journeys (CUJ)** に基づき、ユーザー辞書UIを **「Zero-Friction（摩擦ゼロ）＆ Focus-First」** な体験へ刷新するための要件と、状態遷移の整合性を保証する形式仕様（Alloy）を定義する。

---

### 2. Jobs to be Done (JTBD)

| JTBD ID | Job Statement (状況・動機・期待する成果) |
| :--- | :--- |
| **JTBD-1** | **入力中の即時語彙補強**: 音声入力やキーボード入力で人名・専門用語・英単語が変換できないとき、**入力の流れを極力止めずに数秒・最小タップ数で単語を登録し、即座に元の入力へ戻りたい**。 |
| **JTBD-2** | **辞書の検索と安全なメンテナンス**: 登録単語が増えてきたとき、**目的の単語を即座に絞り込んで優先度や有効/無効を調整でき、誤って削除してもワンタップで安全に復旧したい**。 |
| **JTBD-3** | **セカンダリ管理（クラウド同期・履歴）**: 複数端末間で辞書・学習頻度を共有したり学習履歴をリセットしたりしたいとき、**日常の単語登録作業を邪魔しない専用の操作場所から確実に実行したい**。 |

---

### 3. Critical User Journeys (CUJ)

#### CUJ-1: 日本語単語の最速登録（例:「中村 / なかむら」）
1. ユーザーは IME ヘッダーの `📖` ボタン（または設定画面の「単語登録」）から辞書画面を開く。
2. 画面最上部の **「単語（表記）」** 入力欄に「中村」を入力し、キーボードの「次へ (`actionNext`)」で **「よみ」** 欄へ移動して「なかむら」を入力する。
   - *スマート補助*: 「単語（表記）」がカタカナまたはひらがなのみで構成されている場合（例: `アテンション`）、「よみ」欄へひらがな読み（`あてんしょん`）を自動提案・補完する。
3. キーボードの「完了 (`actionDone`)」または **「辞書に追加」** ボタンを 1 タップして登録を完了する。
4. 登録された単語は即座にリスト最上部へ追加され、インメモリ辞書と ASR ホットワードへ即時反映される。

#### CUJ-2: 英単語・英語フレーズの 1 ステップ登録（例:「Antigravity」）
1. ユーザーは **「単語（表記）」** 入力欄に英単語（例: `Antigravity`）を入力する。
2. UI が英語入力を自動検知し、「よみ」入力欄（`layoutReading`）を **常時表示したまま** ヒントを `よみ (任意・ひらがな、例: くろーど)` へ切り替え、ガイドバッジに「よみ空欄で英語のみ登録可（かな入力で日本語音声・変換にも対応）」を表示する。
3. ユーザーは「よみ」を空欄のまま「追加」または「完了」を 1 タップするだけで英語専用エントリとして登録を完了できる。（※従来「よみ」欄に英単語を入力していたユーザーの習慣にも後方互換フォールバックで対応する）。

#### CUJ-2b: ASCII・英単語への日本語よみ付与による日英バイリンガル登録・編集（例:「Claude / くろーど」「API / えーぴーあい」「C++ / しーぷらぷら」）
1. ユーザーは **「単語（表記）」** 欄に `Claude` や `API` などの ASCII/英単語を入力し、**常時表示されている「よみ」欄** へひらがな・カタカナ（例: `くろーど`、`クロード`、`えーぴーあい`）を入力する。
2. ガイドバッジがリアルタイムに「日英両対応（日本語音声・変換 ＋ 英語入力）」へ切り替わり、保存すると `isJapaneseEntry = true` かつ `hasEnglishSurface = true` のバイリンガルエントリとして登録される。
   - **日本語音声入力**: `HotwordsGenerator` がひらがな・カタカナ双方のホットワードを生成し、`ContextAwareProcessor` が音声認識結果（`クロード` 等）を `Claude` へ決定論的に置換する。
   - **かな漢字変換・英語入力**: Mozc かな漢字変換（`くろーど` → `Claude`）と英語スワイプ・スペル補正の双方で即座に利用可能になる。
   - **誤入力防止**: `Claude` 等の英単語入力中に「よみ」欄へ非かな文字列（例: `reazon`）を入力した場合は、英語専用と誤解されて無言で破棄されることなく明確なバリデーションエラーを返す。
3. **既存エントリの編集**: 英語専用エントリ（例: `Antigravity`）を編集ボトムシートで開いた際も「よみ」欄が常時表示（初期値空欄）され、日本語よみを後から追記して日英バイリンガルへ昇格できる。日英バイリンガルエントリの単語・よみを編集する際も、既存のよみが消去されず保持される。
4. **LWW + Tombstone 整合性**: 編集によりエントリの識別キーが変化した場合（例: 英語専用 `("Claude", "claude")` → 日英バイリンガル `("Claude", "くろーど")`）、旧キーの重複・Tombstone 不整合を残さず安全に更新する。

#### CUJ-3: 登録済み単語のリアルタイム検索・インプレース編集・ON/OFF切替
1. ユーザーはリスト直上の **検索バー** にキーワード（表記、ひらがな読み、カタカナ読みのいずれでも可）を入力し、リアルタイムにリストを絞り込む。
2. 検索結果が 0 件の場合、入力した検索語をワンタップで登録フォームへ転記できる **「「xxx」を新しく登録」** ショートカットを提供する。
3. リスト上の単語カードをタップすると、スクロール位置を失うことなく **編集ボトムシート** が開き、表記・よみ・優先度（`標準` / `最優先`）を変更して保存できる。
4. リスト右側のスイッチで、削除せずに一時的な有効/無効をワンタップで切り替えられる。

#### CUJ-4: 安全な削除とワンタップ Undo（元に戻す）
1. ユーザーは単語カードの削除ボタンをタップする（確認ダイアログの煩わしさを排し即時削除）。
2. リストから即座に削除されると同時に、画面下部に **「「中村」を削除しました [元に戻す]」** Snackbar が表示される。
3. 「元に戻す」をタップすると、元の ID・優先度・有効状態のまま復元され、クラウド同期用の Tombstone（削除墓標）も矛盾なく取り消される。

#### CUJ-5: Google Drive 同期 ＆ 学習履歴管理（ボトムシート分離）
1. ユーザーは Top App Bar 右上の **同期アイコンボタン** をタップする。
2. **「学習履歴 ＆ クラウド同期」ボトムシート** が開き、学習統計バッジ（`単語 X / 連接 Y`）、定期自動同期スイッチ、ワンタップ同期実行、同期先ファイル変更、学習履歴クリアをすべて安全に実行できる。

---

### 4. UI 設計とガバナンス準拠 ([`ui_design_and_simplicity_guidelines.md`](docs/ui_design_and_simplicity_guidelines.md))

1. **情報階層の適正化**: 低頻度のクラウド同期・履歴クリアカードをメイン画面の最上部から Top App Bar のアクションボタン＋ボトムシートへ移動し、メイン画面の 100% を「登録・検索・一覧」に割り当てる。
2. **内部実装用語の排除**: 優先度選択から内部スコア値 `(+3.0)` / `(+4.5)` を撤去し、ユーザーにとって直感的な `標準` / `最優先` ラベル（Material Chip / セグメント選択）を採用する。
3. **滑らかなリスト体験**: 外側の巨大な `NestedScrollView` による `RecyclerView` 全展開を廃止し、登録フォーム＋検索ヘッダーの下で `RecyclerView` が独立して高速スクロールする構造にする。
4. **Empty State の提供**: 未登録時および検索 0 件時に適切なガイダンスと次アクションを提示する。

---

## Part 2: DMN 決定表および Alloy による形式仕様と不変条件 (DMN & Formal Specification)

### 1. モデル化の目的
UI の刷新に伴い導入する **「検索フィルタリング」「編集ボトムシート」「即時削除＋Snackbar Undo」**、および ASCII/英単語への日本語よみ付与・1ステップ英語登録を両立する **「よみ入力欄常時表示・日英バイリンガル登録 DMN ポリシー」**（[`DictionaryInputDecisionModel`](app/src/main/java/com/example/myasr/context/vocabulary/domain/DictionaryInputDecisionModel.kt)）が、既存の **LWW + Tombstone 同期アーキテクチャ**（[`DictionaryRepository`](app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt)）と矛盾しないことを数学的に保証する。

モデルファイル: [`custom_dictionary_ui.als`](app/src/test/resources/alloy/custom_dictionary_ui.als)

### 1.5 DMN 決定表 (`DictionaryInputDecisionModel`)
| 決定表名 | Hit Policy | 役割 |
|---|---|---|
| **`DictionaryReadingFormUiPolicy`** | `FIRST` | 単語・よみの入力状態から「よみ欄の常時表示 (`readingFieldVisible = true`)」「任意フラグ」「ガイドバッジ種別 (`NONE` / `ENGLISH_OPTIONAL_READING` / `BILINGUAL_READY` / `READING_KANA_REQUIRED`)」を決定する。 |
| **`DictionaryEntryRegistrationPolicy`** | `FIRST` | 単語・よみの組み合わせから登録結果（`REGISTER_BILINGUAL_ASCII_WITH_KANA` / `REGISTER_ENGLISH_ONLY_FROM_WORD` / `REGISTER_ENGLISH_ONLY_FROM_READING` / `REGISTER_JAPANESE_WITH_KANA` / 各種エラー）を決定する。 |

### 2. 検証する安全不変条件 (Safety Invariants)

| アサーション名 | 保証する制約 |
| :--- | :--- |
| **`ActiveAndTombstoneDisjoint`** | いかなる操作の後も、アクティブな単語集合（`activeEntries`）と削除墓標集合（`tombstones`）は互いに素である（同一キーが両方に同時存在して同期時にゾンビ復活・消失を起こさない）。 |
| **`UndoSlotConsistent`** | `undoSlot` に保持された削除直後の単語は、必ず `tombstones` に存在し、`activeEntries` には存在しない。 |
| **`UndoRestoresDeletedAndClearsTombstone`** | `DeleteWord` 直後に `UndoDelete` を実行すると、対象単語が確実に `activeEntries` に復元され、`tombstones` から除去され、`undoSlot` がクリアされる。 |
| **`VisibleSubsetOfActive`** | 検索フィルタの有無にかかわらず、画面に表示される単語集合（`visibleEntries`）は常に `activeEntries` の部分集合である。 |
| **`UnfilteredShowsAllActive`** | 検索クエリが空（`filterActive = False`）のとき、表示集合は常に `activeEntries` と完全に一致する。 |
| **`SearchFilterPreservesRepository`** | 検索フィルタの変更（`SearchFilter`）は、`activeEntries`、`tombstones`、`undoSlot` を一切変更しない（純粋なビュー投影である）。 |
| **`FormPolicyIsTotal`** | `DictionaryReadingFormUiPolicy` は全入力組み合わせに対して一意のガイド状態へ解決される。 |
| **`ReadingFieldAlwaysVisibleForAllInputs`** | いかなる単語・よみ入力（英単語含む）に対しても、「よみ」入力欄は常に表示状態（`True`）となる。 |
| **`RegistrationPolicyIsTotal`** | `DictionaryEntryRegistrationPolicy` は全入力組み合わせに対して一意の登録結果へ解決される。 |
| **`AsciiWordWithValidKanaAlwaysRegistersBilingual`** | ASCII/英単語に有効なひらがな/カタカナよみが入力された場合、常に日英バイリンガル登録（`RegBilingualAsciiWithKana`）となる。 |
| **`AsciiWordWithoutReadingStillAllowsOneStepEnglish`** | ASCII/英単語でよみ空欄の場合、引き続き 1 ステップ英語登録（`RegEnglishOnlyFromWord`）が可能である。 |
| **`AsciiWordWithInvalidNonKanaReadingRejected`** | ASCII/英単語に非かな文字列がよみとして入力された場合、無言で破棄せず `ErrReadingInvalidKana` として拒否される。 |

---

## Part 3: 実装・検証計画 (Implementation & Verification Plan)

1. **Alloy 形式仕様追加**: `app/src/test/resources/alloy/custom_dictionary_ui.als` を作成し、[`AlloyVerificationTest`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) に検証テストを追加する。
2. **UI レイアウト刷新**:
   - [`activity_dictionary.xml`](app/src/main/res/layout/activity_dictionary.xml): クイック登録カード、検索バー、独立スクロール `RecyclerView`、Empty State レイアウトへ刷新。
   - `bottom_sheet_dictionary_edit.xml` / `bottom_sheet_history_sync.xml`: 編集用・同期用のボトムシートレイアウトを追加。
   - [`item_dictionary_entry.xml`](app/src/main/res/layout/item_dictionary_entry.xml): タップで編集しやすい視覚的デザインと明瞭な読み・優先度バッジへ整理。
3. **ロジック・リポジトリ拡張**:
   - [`DictionaryRepository`](app/src/main/java/com/example/myasr/context/vocabulary/infrastructure/DictionaryRepository.kt): Undo 復元のために元の `DictionaryEntry` をそのまま安全に再登録し Tombstone を除去できること（既存の `add` が Tombstone を除去し重複キーを上書きする仕様を活用・保証、必要なら専用 `restore(entry)` を用意）。
   - 検索フィルタ・カタカナ→ひらがな読み自動提案・言語自動判定の純粋ロジックをテスト可能なヘルパーとして切り出し、単体テストを追加する。
4. **検証**:
   - `./gradlew testDebugUnitTest`（Alloy 検証・Konsist DDD 検証・音声句読点 67+ テスト・新規辞書 UI ロジックテスト）の 100% パスを GitHub Actions CI で確認する。
