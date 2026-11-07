# PRD & System Design: 自動辞書更新チェック・更新日可視化・更新通知システム (Dictionary Update Tracker & Notification)

## Part 1: Product Requirements Document (PRD)

### 1. 概要 (Overview)
JotInput が採用しているオンデマンド辞書アーキテクチャ（Mozc 基本辞書、Mozc 一般語彙、SudachiDict 新語、Wikipedia 固有名詞、地名・駅名、人名、絵文字・記号の計7種）において、**「ユーザーがいつ辞書を更新すべきか一目で分かり、ネット上の辞書が更新された際に自動検知・通知する仕組み」** を導入する。

具体的には以下の3点を提供する：
1. **ネット上の辞書更新日の自動取得**: バックグラウンド（非同期）で各辞書リポジトリ（GitHub / Gist）の最新コミット日時・更新日時を自動取得する。
2. **ユーザー更新日とネット更新日の並列可視化**: アプリ内の辞書一覧（Card 3「辞書・言語データ」）において、各辞書の **「端末の更新日（ローカル導入日）」** と **「ネット上の最新更新日（リモート公開日）」**、および **「最新 / 更新あり / 未取得」** のステータスを一目で比較できるようにする。
3. **更新通知（In-App バナー ＆ OS システム通知）**: 導入済み辞書よりもネット上の辞書の方が新しい場合、カード上部の通知バナー・バッジ強調表示および Android システム通知によって、更新が必要であることをユーザーへ自動的に知らせる。

---

### 2. 背景と課題 (Background & Problem Statement)
- **現状の課題**:
  - JotInput は APK サイズをコンパクト（$\le 110\text{ MB}$）に保つため、約400万語の拡張辞書群を端末ストレージへオンデマンドダウンロードする設計（Model & Lexicon Decoupling Contract）を採っている。
  - しかし現在の UI（Card 3）では、各辞書について `有効 (1,284,512 語)` という語数バッジと `再取得` ボタンしか表示されない。
  - そのため、ユーザーからは **「自分が最後にいつ辞書をダウンロードしたのか」「ネット上の辞書（SudachiDict や Wikipedia 等）に新しい語彙が追加されているのか」「いつ『再取得』ボタンを押すべきなのか」が全く分からない** という課題があった。
- **目指すユーザー体験 (Target UX)**:
  - アプリを開いた瞬間に、自動でネット上の最新辞書更新日がチェックされ、古い辞書があれば「🔔 N件の辞書に新しい更新があります」と案内される。
  - 個別の辞書一覧を開くと、すべての辞書について `端末更新: YYYY/MM/DD ｜ ネット最新: YYYY/MM/DD` が一目で比較でき、更新が必要な辞書だけを狙ってワンタップで更新（または一括更新）できる。

---

### 3. 機能要件 (Functional Requirements)

#### FR-1: 7種の全辞書に対する更新日トラッキング
以下の全7種の辞書データソースを対象とする：

| # | 辞書ID (`sourceId`) | 表示名 | リモート配信元 | 更新頻度目安 |
| :--- | :--- | :--- | :--- | :--- |
| 0 | `base_dictionary` | Mozc 基本・標準語彙 (コア辞書) | GitHub Gist (`kenichikurihara/39665c6e7ba6e63d6fe86c4c4b1bbd42`) | 随時 |
| 1 | `mozc_general` | Mozc 基本・一般語彙 | `google/mozc` (`src/data/dictionary_oss`) | 月1〜数回 |
| 2 | `sudachidict` | SudachiDict 現代語・複合語・新語 | `utuhiro78/mozcdic-ut-sudachidict` | 月1〜2回 |
| 3 | `jawiki` | Wikipedia 百科事典 | `utuhiro78/mozcdic-ut-jawiki` | 月1〜2回 |
| 4 | `place_names` | 地名・駅名・郵便番号 | `utuhiro78/mozcdic-ut-place-names` | 月1〜2回 |
| 5 | `personal_names` | 人名・姓名辞書 | `utuhiro78/mozcdic-ut-personal-names` | 月1〜2回 |
| 6 | `emoji_symbols` | 絵文字・記号・単漢字 | `google/mozc` (`src/data/emoji` 他) | 四半期〜随時 |

#### FR-2: 辞書一覧 UI における更新日・ステータスのひと目表示
各辞書アイテム（`item_multi_dict.xml`）および Card 3 サマリーにおいて、以下の情報をひと目で判別可能にする：
- **日付比較行 (`tvDictDates`)**:
  - フォーマット: `端末更新: YYYY/MM/DD ｜ ネット最新: YYYY/MM/DD`
  - 未導入の場合: `端末更新: 未導入 ｜ ネット最新: YYYY/MM/DD`
  - オフライン等でリモート未取得の場合: `端末更新: YYYY/MM/DD ｜ ネット最新: 確認中... (または未確認)`
- **ステータスバッジ (`tvDictStatusBadge`) の色分け**:
  - **最新 (`UP_TO_DATE`)**: 緑バッジ（`@color/badge_success_bg` / `最新 (1,284,512 語)`）
  - **更新あり (`UPDATE_AVAILABLE`)**: オレンジ/警告バッジ（`@color/badge_warning_bg` または強調色 / `更新あり (1,284,512 語)`）
  - **未取得 (`NOT_INSTALLED`)**: グレーバッジ（`@color/badge_neutral_bg` / `未取得`）
- **アクションボタン (`btnDictAction`) のラベル最適化**:
  - 未取得時: `取得`
  - 更新あり時: `更新`（プライマリ強調スタイル）
  - 最新時: `再取得`

#### FR-3: 自動更新チェックと通知（In-App & Android システム通知）
1. **起動時・復帰時の自動バックグラウンドチェック**:
   - `MainActivity` 起動時（および前回のチェックから一定時間［デフォルト6時間］経過時、または手動更新チェック時）に非同期でリモート更新日を取得する。
2. **In-App 更新通知バナー（Card 3 内）**:
   - 導入済みの辞書の中に `ネット最新 > 端末更新` となる辞書が1件以上存在する場合、Card 3 内に目立つ更新通知バー（`🔔 N件の辞書に新しい更新があります`）を表示し、一括ボタンを `更新がある辞書を一括アップデート (N件)`（または全辞書一括更新）として強調する。
   - すべての導入済み辞書が最新の場合は、`✓ 導入済みの辞書はすべて最新です（最終確認: MM/DD HH:mm）` と安心感のあるステータスを表示する。
3. **Android OS システム通知 (`NotificationCompat`)**:
   - 新しいリモート更新日時を検知した際（同一のリモート更新日時につき1回のみ通知し、スパム通知を防止）、Android システム通知チャネル（`辞書アップデート通知`）へ通知を発行する。通知をタップすると `MainActivity` が開き、直ちに更新を行える。

---

### 4. 非機能要件・UIガバナンス遵守 (Non-Functional Requirements & Governance)
- **UI Simplicity Contract（4カード上限ルール）の厳守**:
  - [`docs/ui_design_and_simplicity_guidelines.md`](file:///home/kenichikurihara/repos/myasr/docs/ui_design_and_simplicity_guidelines.md) に従い、新たなトップレベル `MaterialCardView` は追加しない。すべての UI 要素は既存の **Card 3 (`cardMozcDict`: 辞書・言語データ)** の内部および `item_multi_dict.xml` 内に美しく統合する。
- **レートリミット耐性と低通信量**:
  - 辞書本体（数MB〜十数MB）をダウンロードせずに更新日時だけを取得するため、数KB以下の軽量メタデータ通信（GitHub Commits Atom Feed XML / Gist API）を使用する。
- **レガシー導入済み環境のフォールバック**:
  - 本機能導入前にすでに辞書をダウンロード済みの端末では、SharedPreferences に導入日時が記録されていない。その場合は辞書ファイル（`base_dictionary.tsv` または `mozc_dictionaries.db`）のファイルシステム最終更新日時（`File.lastModified()`）を初期の「端末更新日」として自動フォールバックし、「不明」表示を回避する。

---

### 5. PRD におけるオープンクエスチョンとベスト解 (Open Questions & Best Solutions)

#### Q1: `raw.githubusercontent.com` の HTTP `HEAD` リクエストには `Last-Modified` ヘッダが含まれないが、ネット上の辞書更新日をどうやって正確かつレート制限なしに取得するか？
> **【ベスト解】**
> **GitHub のコミット Atom フィード (`https://github.com/{owner}/{repo}/commits/{branch}/{path}.atom`) を第一手段として使用し、Gist には GitHub Gist API (`https://api.github.com/gists/{id}`) を使用するハイブリッド方式を採用する。**
> - **理由**:
>   1. `raw.githubusercontent.com` は CDN キャッシュの都合上 `Last-Modified` ヘッダを返さない（`ETag` のみ）。
>   2. 一方、GitHub REST API (`api.github.com/repos/.../commits`) は未認証 IP あたり **60リクエスト/時** の厳しいレート制限がある（7種の辞書を毎回チェックするとすぐに上限に達するリスクがある）。
>   3. これに対し、GitHub の **Atom フィード (`/commits/main.atom` 等)** は API レート制限（60回/時）の対象外でありながら、先頭 `<updated>2026-11-03T19:29:10Z</updated>` タグに **正確な最新コミット日時（ISO-8601）** と `<id>`（コミット SHA）を含んでいる。また、万一 Atom フィードが失敗した場合のフォールバックとして REST API も併用できる。

#### Q2: ユーザーが「今日」辞書を更新した場合、ネット上の辞書の公開日（例: 2026/11/03）と端末の更新日（例: 2026/11/08）はどう比較・表示されるべきか？
> **【ベスト解】**
> **画面には実際の「端末更新日（例: 2026/11/08）」と「ネット最新日（例: 2026/11/03）」の両方を事実通り表示し、判定ロジックは `remoteUpdatedAtMillis > localUpdatedAtMillis` のときのみ「更新あり」と判定する。**
> - さらに、ダウンロード完了時点において `localUpdatedAtMillis = max(System.currentTimeMillis(), remoteUpdatedAtMillis)`（またはダウンロード時のリモートコミット日時を記録）とすることで、時計のわずかなズレがあってもダウンロード直後は確実に **「最新 (`UP_TO_DATE`)」** 状態となることを保証する。

#### Q3: 一括アップデートボタンを押したとき、すでに「最新」の辞書まで毎回再ダウンロードすべきか？
> **【ベスト解】**
> **更新が必要な辞書（`UPDATE_AVAILABLE`）または未導入の辞書（`NOT_INSTALLED`）が存在する場合は、それら（差分のある辞書のみ）を優先的にスマート一括更新し、すべて最新の状態でユーザーが明示的に押した場合のみ全辞書を再取得する。**
> - **理由**: 約400万語すべてを毎回再パース・SQLite 挿入すると数分かかるが、更新があった辞書（例: SudachiDict 1種のみ）だけを更新すれば数秒〜十数秒で完了し、通信量・バッテリー・待ち時間を劇的に削減できるため。

---

## Part 2: Design Document (DD / システム設計)

### 1. アーキテクチャ全体像 (Architecture Overview)

```mermaid
flowchart TD
    subgraph UI Layer ["MainActivity (Card 3: 辞書・言語データ)"]
        Header["Card 3 Header & Update Summary Banner\n(tvDictUpdateBanner / btnCheckDictUpdates)"]
        BatchBtn["Smart Action Button (btnDownloadAllDicts)\n'更新がある辞書を一括更新 (N件)' / '推奨拡張辞書を一括ダウンロード'"]
        ItemList["Individual Dictionary List (layoutDictItemsContainer)\nitem_multi_dict.xml × 7 (Base + 6 Extended)"]
    end

    subgraph Domain Layer ["DictionaryUpdateTracker"]
        Tracker["DictionaryUpdateTracker\n- checkUpdatesIfNeeded(force)\n- getDictStatusInfo(sourceId)\n- markInstalled(sourceId)"]
        Notifier["DictionaryUpdateNotifier\n- postSystemNotificationIfNeeded()"]
    end

    subgraph Storage & Network ["Persistence & Upstream Endpoints"]
        Prefs["SharedPreferences ('dict_update_prefs')\n- local_updated_ms_{id}\n- remote_updated_ms_{id}\n- last_notified_remote_ms_{id}\n- last_check_timestamp_ms"]
        GitHubAtom["GitHub Commits Atom Feed (XML)\n- google/mozc (.../dictionary_oss.atom, .../emoji.atom)\n- utuhiro78/mozcdic-ut-* (commits/main.atom)"]
        GistApi["GitHub Gist API (JSON)\n- api.github.com/gists/39665c..."]
    end

    UI Layer --> Tracker
    Tracker --> Prefs
    Tracker --> GitHubAtom
    Tracker --> GistApi
    Tracker --> Notifier
```

---

### 2. データモデルと状態定義 (Data Models & State Machine)

#### 2.1 `DictUpdateState` 列挙型
各辞書の更新状態を以下の4状態で表現する：

```kotlin
enum class DictUpdateState {
    NOT_INSTALLED,            // 未取得 (ローカル語数 == 0)
    UP_TO_DATE,               // 最新 (導入済 && remoteUpdatedAt > 0 && localUpdatedAt >= remoteUpdatedAt)
    UPDATE_AVAILABLE,         // 更新あり (導入済 && remoteUpdatedAt > localUpdatedAt)
    INSTALLED_REMOTE_UNKNOWN  // 導入済・リモート日付未確認 (オフライン初回起動時など)
}
```

#### 2.2 `DictionaryStatusInfo` データクラス
UI 描画に必要なすべての情報を集約したイミュータブルモデル：

```kotlin
data class DictionaryStatusInfo(
    val sourceId: String,
    val displayName: String,
    val isInstalled: Boolean,
    val wordCount: Int,
    val localUpdatedAtMillis: Long,   // 0L if not installed
    val remoteUpdatedAtMillis: Long,  // 0L if not fetched yet
    val state: DictUpdateState
) {
    fun formatLocalDate(): String // "2026/11/08" or "未導入"
    fun formatRemoteDate(): String // "2026/11/03" or "未確認"
}
```

---

### 3. リモート更新日取得プロトコル詳細 (Remote Update Fetching Protocol)

各辞書の `sourceId` とリモート更新日取得エンドポイントのマッピング：

| `sourceId` | 取得方式 | エンドポイント URL | 抽出ルール |
| :--- | :--- | :--- | :--- |
| `base_dictionary` | Gist JSON API | `https://api.github.com/gists/39665c6e7ba6e63d6fe86c4c4b1bbd42` | JSON の `"updated_at"` フィールド (`ISO-8601`) をパース |
| `mozc_general` | Atom Feed XML | `https://github.com/google/mozc/commits/master/src/data/dictionary_oss.atom` | `<feed>` または先頭 `<entry>` 内の `<updated>` タグ (`ISO-8601`) を抽出 |
| `sudachidict` | Atom Feed XML | `https://github.com/utuhiro78/mozcdic-ut-sudachidict/commits/main.atom` | 同上 |
| `jawiki` | Atom Feed XML | `https://github.com/utuhiro78/mozcdic-ut-jawiki/commits/main.atom` | 同上 |
| `place_names` | Atom Feed XML | `https://github.com/utuhiro78/mozcdic-ut-place-names/commits/main.atom` | 同上 |
| `personal_names` | Atom Feed XML | `https://github.com/utuhiro78/mozcdic-ut-personal-names/commits/main.atom` | 同上 |
| `emoji_symbols` | Atom Feed XML | `https://github.com/google/mozc/commits/master/src/data/emoji.atom` | 同上 |

#### 3.1 パーサー設計 (`parseIso8601ToMillis`)
- GitHub の Atom フィードおよび REST API はすべて UTC の ISO-8601 形式（例: `2026-11-03T19:29:10Z` または `2026-10-20T07:38:20-07:00`）を返す。
- `java.time.Instant` / `java.time.OffsetDateTime`（または `SimpleDateFormat` フォールバック）を用いてエポックミリ秒（`Long`）へ変換する。
- 表示時は日本のタイムゾーン（または端末のデフォルトロケール `yyyy/MM/dd`）にフォーマットして表示する。

#### 3.2 キャッシュとスロットリング戦略
- **自動チェック間隔 (TTL)**: `6時間` (`6 * 3600 * 1000L`)。
  - `MainActivity.onResume()` / `onCreate()` 時に、前回のチェックから6時間以上経過している場合、またはまだ一度もリモート更新日を取得できていない辞書がある場合に、バックグラウンド (`Dispatchers.IO`) で並列取得（`coroutineScope { async { ... } }`）を実行する。
- **手動チェックボタン**:
  - Card 3 ヘッダーまたは折りたたみセクション付近に「更新確認 ↻」アクションを用意し、ユーザーが任意のタイミングで即座に最新状態を再確認できるようにする。

---

### 4. ローカル更新日の永続化とフォールバック設計 (Local Timestamp Persistence)

1. **ダウンロード完了時の記録**:
   - `BaseDictionaryDownloader.download()` 成功時: `DictionaryUpdateTracker.markDictionaryUpdated("base_dictionary")` を呼び出す。
   - `MultiDictionaryManager.downloadDictionary(type)` 成功時: `DictionaryUpdateTracker.markDictionaryUpdated(type.id)` を呼び出す。
   - `markDictionaryUpdated(sourceId)` は `SharedPreferences` の `local_updated_ms_${sourceId}` に `System.currentTimeMillis()` を保存する。
2. **既存インストール済み環境の自動マイグレーション（フォールバック）**:
   - `getLocalUpdatedAtMillis(sourceId)` 呼び出し時、対象辞書が導入済み（`wordCount > 0` または `isBaseDictionaryDownloaded() == true`）であるにもかかわらず `local_updated_ms_${sourceId}` が未記録（`0L`）の場合：
     - `base_dictionary`: `KanaKanjiEngine.getInstance(context).getBaseDictionaryFile().lastModified()` を取得して初期値として保存・返却する。
     - 拡張辞書 (`DictionaryType`): `context.getDatabasePath(DictionaryDatabaseHelper.DATABASE_NAME).lastModified()` を取得して初期値として保存・返却する。
   - これにより、アップデート前から利用しているユーザー環境でも即座に正確なローカル導入日が表示される。

---

### 5. UI 設計詳細 (UI Layout & Visual Design)

#### 5.1 Card 3 サマリー＆通知バナー (`activity_main.xml`)
Card 3（`cardMozcDict`）内に以下の要素を配置する（トップレベルカード数は4枚のまま維持）：
- **更新ステータス行 (`layoutDictUpdateBanner`)**:
  - リモート更新チェック結果に応じて動的に表示を切り替える：
    - **更新あり時**: 背景 `@drawable/badge_background` (tint: アンバー/警告系またはプライマリ薄色)、テキスト `🔔 N件の辞書に新しい更新があります（最新公開: YYYY/MM/DD）` ＋ 右端に `更新確認 ↻`
    - **すべて最新時**: `✓ 導入済みの辞書はすべて最新です` ＋ 右端に `更新確認 ↻`
    - **確認中**: `ネット上の辞書更新日を確認中...`

#### 5.2 個別辞書リスト行 (`item_multi_dict.xml`)
各辞書アイテムのレイアウトを以下のように拡張し、ユーザーの更新日とネット上の更新日が一目で対比できるようにする：

```
+-------------------------------------------------------------------+
| SudachiDict 現代語・複合語・新語              [更新あり (1,284,512語)] |
| WorksApplicationsによる最新現代日本語・IT用語・日常複合語辞書          |
| 📅 端末更新: 2026/10/10  ｜  🌐 ネット最新: 2026/10/16 (要更新)       |
| 約128万語 (約11.5 MB)                                     [ 更新 ] |
+-------------------------------------------------------------------+
```

- **最新の場合の表示例**:
  - バッジ: `[最新 (1,284,512語)]`（グリーン）
  - 日付行: `📅 端末更新: 2026/11/08  ｜  🌐 ネット最新: 2026/10/16`（セカンダリテキスト色）
  - ボタン: `[ 再取得 ]`（アウトラインボタン）
- **更新ありの場合の表示例**:
  - バッジ: `[更新あり (1,250,000語)]`（オレンジ/レッド強調）
  - 日付行: `📅 端末更新: 2026/10/25  ｜  🌐 ネット最新: 2026/11/03`（強調テキスト色）
  - ボタン: `[ 更新 ]`（プライマリ強調）
- **未導入の場合の表示例**:
  - バッジ: `[未取得]`（ニュートラルグレー）
  - 日付行: `📅 端末更新: 未導入  ｜  🌐 ネット最新: 2026/11/03`
  - ボタン: `[ 取得 ]`

---

### 6. OS システム通知設計 (`DictionaryUpdateNotifier`)

- **通知チャネル**:
  - Channel ID: `dictionary_update_channel`
  - Channel Name: `辞書アップデート通知`
  - Importance: `NotificationManager.IMPORTANCE_DEFAULT`
- **重複通知抑止ロジック**:
  - 更新ありと判定された辞書群の中で最大の `remoteUpdatedAtMillis`（または対象辞書ごとの `remoteUpdatedAtMillis`）を `last_notified_remote_ms` と比較。
  - すでに通知済みのリモート更新日時であれば再通知しない（ユーザーがアプリを開くたびに通知音が鳴るのを防ぐ）。
  - 新しいリモート更新が検出されたときのみ1回通知を発行し、タップ時に `MainActivity` を起動する `PendingIntent` を付与する。
  - Android 13 (API 33) 以降では `Manifest.permission.POST_NOTIFICATIONS` の付与状態を確認し、未付与時は例外を投げずに In-App バナー通知のみで安全に動作させる。

---

### 7. テスト計画と検証基準 (Verification & Test Plan)

1. **単体テスト (`DictionaryUpdateTrackerTest.kt`)**:
   - GitHub Atom Feed XML および Gist JSON からの ISO-8601 タイムスタンプ抽出テスト。
   - `localUpdatedAt` と `remoteUpdatedAt` の比較による `DictUpdateState` (`NOT_INSTALLED`, `UP_TO_DATE`, `UPDATE_AVAILABLE`, `INSTALLED_REMOTE_UNKNOWN`) の状態遷移テスト。
   - レガシー導入済み辞書に対するファイル最終更新日時フォールバックテスト。
   - 重複システム通知の抑止ロジックテスト。
2. **回帰テスト (`JapanesePunctuationEvaluationTest.kt` 他)**:
   - `./gradlew testDebugUnitTest` を実行し、全ユニットテストが 100% パスすることを確認。
3. **実機デュアルターゲット検証 (ARCVM & Pixel 10 Pro)**:
   - `./deploy.sh` により両ターゲットへデプロイ＆Google Drive へ APK 同期。
   - 実機上で GitHub から全7辞書のネット更新日が自動取得され、Card 3 の辞書一覧に「端末更新日」と「ネット最新日」が並んで表示されることを `screencap` で視覚的に確認。
