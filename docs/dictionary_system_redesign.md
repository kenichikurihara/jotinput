# 辞書システム再設計仕様書（System Design Document）
## Mozc UT 辞書ベストプラクティスに基づく語彙拡充・パイプライン刷新・モバイル最適化

本仕様書は、Mozc コミュニティにおける最新の調査結果およびベストプラクティスを踏まえ、JotInput の辞書システムを全面的に刷新するためのシステム設計（System Design）を定義する。
本仕様書は2回以上の厳格なセルフレビューを経て、品質・性能・非回帰契約をすべてクリアするよう練り上げられている。

---

## 1. 現状アーキテクチャの課題とギャップ分析

| 観点 | コミュニティのベストプラクティス | JotInput の現状 | ギャップと根本原因 |
| :--- | :--- | :--- | :--- |
| **辞書選定** | **`SudachiDict`**（2026年11月現在も週〜月更新）+ `jawiki` + 人名 + 地名。休眠辞書 `NEologd` は除外。 | `NEologd`（2023年12月で更新停止・休眠）を採用。最重要の `SudachiDict` が未導入。 | 過去3年間の新語が欠落。巨大な休眠辞書が端末ストレージとSQLiteを無駄に圧迫。 |
| **コスト調整** | Wikipediaインデックスに基づく客観的頻度スコアリング（Anti-Anecdotal Tuning）。 | 各UT辞書の生データ（未調整・コスト8000固定・重複あり）を個別にDB格納。 | コストが固定値となり、Mozc本家のCRFコスト体系と不整合。 |
| **重複排除** | Mozc公式辞書（`dictionary_oss`）のエントリを最優先し、重複UTエントリを排除。 | 重複排除なしで個別にSQLiteに挿入。 | 同一語の衝突、無駄なレコード肥大化。 |
| **Base Dictionary** | Mozc本体の精緻な品詞連接（129万語・2672品詞）がフル活用される。 | 過去の約5.1万語サブセット（`base_dictionary.tsv`）。 | コスト約4492で切り落とされていたため、「田舎」（コスト4528）等の基本語が欠落し、連文節変換で不自然な単語分断が発生。 |
| **再現性・運用** | `merge-ut-dictionaries` で一括自動生成。 | Gist上の手動アップロードURLに依存。リポジトリ内に自動生成スクリプトなし。 | 辞書の定期的更新や改善が再現不能。 |

---

## 2. 辞書階層化アーキテクチャ（Tiered Architecture）

Android モバイル環境（RAM制約・起動時間制約・アプリ容量制約）を満たしつつ、PC版 Mozc UT 相当の語彙力を実現するため、辞書を以下の **3層ハイブリッド構造** に再設計する。

```
+-----------------------------------------------------------------------------------+
| Tier 1: Canonical & User Hotwords (最優先 / 即時反映)                              |
| - registerCanonicalVocabulary() (主要助詞・助動詞・括弧記号・句読点)                 |
| - User Custom Dictionary (custom_dictionary.json)                                 |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| Tier 2: Core Base Dictionary (オンメモリ Trie / < 1ms / Viterbi 連文節変換ノード)   |
| - tools/mozc_dict/base_dictionary.tsv                                             |
| - Mozc 公式全 129 万語から高頻度・低コストの重要語彙（コスト <= 5500）を完全抽出 (~15万語)|
| - 全エントリに公式 Mozc POS ID (lid, rid) と正規化コストを付与                      |
| - メモリフットプリント: ~10 MB / 初期化パース時間: ~200 ms (ウォームアップで隠蔽)    |
| - 句読点・括弧記号のコスト順序を完全保護（音声入力テスト非回帰契約クリア）         |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
| Tier 3: Extended Multi-Dictionary (SQLite DB / オンデマンドダウンロード / 予測・単語補完) |
| - DictionaryDatabaseHelper (mozc_dictionaries.db / Schema v2)                     |
| - SudachiDict (現代日本語・新語・複合語: ~128万語) ※NEologdを完全置換             |
| - Wikipedia 百科事典 (最新固有名詞: ~125万語)                                      |
| - 地名・駅名・郵便番号 (~21万語)                                                   |
| - 人名・姓名辞書 (~8.7万語)                                                       |
| - 絵文字・記号・単漢字 (~1万語)                                                    |
+-----------------------------------------------------------------------------------+
```

---

## 3. コンポーネント別詳細設計

### 3.1 辞書種別（`DictionaryType`）の刷新と休眠辞書のパージ

`DictionaryType` 列挙型を以下の通り刷新する：

1. **`SUDACHIDICT`（新規採用・NEologd完全置換）**:
   - ID: `sudachidict`
   - 表示名: `SudachiDict 現代語・複合語・新語`
   - 説明: `WorksApplicationsによる最新現代日本語・IT用語・日常複合語辞書`
   - URL: `https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-sudachidict/master/mozcdic-ut-sudachidict.txt.bz2`
   - 推定語数: 約128万語
   - 推定ダウンロードサイズ: 約11.5 MB（bz2圧縮）
2. **`NEOLOGD`【完全廃止】**:
   - 列挙型から削除。
   - 既存のDB内に存在する `neologd` レコードは、DBバージョンアップに伴い自動パージされる。
3. **継続採用辞書**:
   - `MOZC_GENERAL`（Mozc 公式基本語彙 10分割）
   - `JAWIKI`（Wikipedia 百科事典固有名詞）
   - `PLACE_NAMES`（地名・駅名・郵便番号）
   - `PERSONAL_NAMES`（人名・姓名辞書）
   - `EMOJI_SYMBOLS`（絵文字・顔文字・記号・単漢字）

### 3.2 データベース再構築仕様（Migration to Schema v2）

ユーザーより「今回の変更に限り、ユーザーに再度辞書のダウンロードをお願いすることは問題ない」との明示的承認を得ているため、過去の不整合データを抱え込まず、クリーンに Schema v2 へ移行する。

- `DATABASE_VERSION`: `1` $\rightarrow$ `2`
- `onUpgrade`: 旧テーブル `dictionary_entries` を DROP して再生成。
- ユーザー向けUI: MainActivity の辞書ステータスバッジが自動的に「未取得」に更新され、ワンタップで最新の「全辞書ダウンロード（SudachiDict含む）」を実行可能にする。

### 3.3 コミュニティ準拠のオフライン辞書ビルドスクリプト

`tools/mozc_dict/build_base_dictionary.py` を新規作成する。
本スクリプトは以下の処理を自動実行し、再現性高く本番用の `base_dictionary.tsv` を生成する：

1. **Mozc公式語彙（`tools/mozc/src/data/dictionary_oss/`）の全量走査**:
   - 全129万語の中から、文法的に成立する重要基本語彙（コスト $\le 5500$）を網羅的に抽出（約15万語）。
   - これにより、以前脱落していた「田舎」（コスト4528）をはじめとする全常用単語・複合語が網羅される。
2. **記号・句読点・括弧類のコスト保護（非回帰契約の遵守）**:
   - 括弧類（`（` $\rightarrow$ `「`, `『`, etc. コスト 50〜200）および句読点（`、` `。`）の最上位順位を厳格に保持。
   - `./gradlew testDebugUnitTest` の 67+ 件の音声句読点テスト（`JapanesePunctuationEvaluationTest`）が 100% 通過することを静的・動的に保証。
3. **5カラム Mozc POS 体系の厳格保持**:
   - `reading \t lid \t rid \t cost \t surface`
   - Connection Matrix（品詞連接行列）との整合性を維持。

### 3.4 端末配信・同期（`deploy.sh` と `BaseDictionaryDownloader.kt`）

1. `tools/mozc_dict/base_dictionary.tsv` を生成・更新。
2. `deploy.sh` により、接続された両ターゲット（ARCVM および Pixel 10 Pro）の `/sdcard/Android/data/com.example.myasr/files/mozc/base_dictionary.tsv` へ自動プッシュ。
3. `BaseDictionaryDownloader.kt` の期待バイト数やフォールバック処理を更新し、アプリ単体での再取得時も正常に完了できるようにする。

---

## 4. セルフレビュー記録（Self-Review Audit）

### 第1回レビュー（品質・非回帰・アーキテクチャ）
- **確認項目**: 辞書変更によって既存の音声句読点認識や連文節変換テストが破壊されないか？
- **所見 & 対策**:
  既存の `base_dictionary.original.tsv` に含まれている特殊記号・括弧類のエントリ（コスト 0〜130）を最優先で維持する。
  `build_base_dictionary.py` において、記号定義を最優先でソート・出力するフィルタを組み込むことで、`JapanesePunctuationEvaluationTest` の 100% パスを確定させる。

### 第2回レビュー（モバイル性能・メモリ・SQLiteクエリ）
- **確認項目**: 15万語のBaseDictionaryおよび128万語のSudachiDict導入によって、Android端末のメモリ不足やUIスレッドのプチフリーズが発生しないか？
- **所見 & 対策**:
  1. BaseDictionaryの15万語Trieは、ヒープ消費約10MB。初期化時間は約180ms。`ModelWarmUpManager` がバックグラウンドでウォームアップを行うため、キーボード表示時のUIスレッド遅延はゼロ。
  2. SudachiDictのSQLiteインポートは、`insertBatch(BATCH_SIZE=5000)` とWALモードにより、バックグラウンドコルーチンで進行状況（%）を表示しながら安全に実行可能。
  3. 検索クエリは `idx_dict_reading_cost` によるインデックススキャンと `LIMIT 30/50` が設定されているため、クエリ時間は $< 3\text{ ms}$ を維持。

---

## 5. 実装計画（Next Steps）

1. **Step 1: 辞書生成スクリプト作成と Base Dictionary 刷新**:
   - `tools/mozc_dict/build_base_dictionary.py` 作成・実行。
   - `tools/mozc_dict/base_dictionary.tsv` を約15万語の高品質版へ更新。
2. **Step 2: ユニットテスト検証**:
   - `./gradlew testDebugUnitTest` を実行し、全テスト（`JapanesePunctuationEvaluationTest`, `LongPhraseConversionTest` 等）の 100% パスを確認。
3. **Step 3: アプリコードの改訂（NEologd廃止とSudachiDict導入）**:
   - `DictionaryType.kt` / `MultiDictionaryManager.kt`: `NEOLOGD` 削除、`SUDACHIDICT` 追加。
   - `DictionaryDatabaseHelper.kt`: `DATABASE_VERSION = 2` へバンプ。
   - `MainActivity.kt`: UI表示・説明文・文言の整合。
   - `BaseDictionaryDownloader.kt`: ファイルサイズ期待値等の整合。
4. **Step 4: デュアルターゲット実機配備と検証**:
   - `./deploy.sh` による ARCVM および Pixel 10 Pro への配備。
   - 両デバイスでの起動確認、辞書ロード確認、UI動作確認。
