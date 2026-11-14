# PRD & System Design: Google Drive 履歴同期の設定画面統合 & 定期バックグラウンド同期

## Part 1: Product Requirements Document (PRD)

### 1. 背景と課題 (Executive Summary)
オンボーディングフロー（[`OnboardingActivity`](app/src/main/java/com/example/myasr/OnboardingActivity.kt)）において Google Drive からの履歴復元・新規連携が可能になったが、新しい 3タブ UI の [`MainActivity`](app/src/main/java/com/example/myasr/MainActivity.kt)（「ホーム」「設定」「データ」）には Google Drive 同期の管理カードが存在しないため、以下の課題が生じている：

1. **オンボーディング後の変更・同期手段の孤立**: 初回にスキップしたユーザーや、導入後に同期先ファイルを変更・即時同期・連携解除したいユーザーが、`MainActivity` の「設定」タブから直接操作できない。
2. **定期的なバックグラウンド同期の欠如**: 現状の [`UserHistorySyncManager`](app/src/main/java/com/example/myasr/context/kanakanji/application/UserHistorySyncManager.kt) は「辞書編集後 3秒デバウンス」と「IME ウィンドウ収納時の日和見同期（15分クールダウン）」のみに依存しており、長時間の継続利用・キーボード非収納・端末アイドル中などに **定期的にバックグラウンドで同期し続ける仕組み（OS `JobScheduler`）がない**。
3. **同期先変更時のスナップショット不整合リスク**: 既存の同期先とは別の Google Drive ファイルへ切り替えた際、旧ファイル用の `sync_base_snapshot.json` が残っていると、3-Way Delta マージが誤った削除差分を計算する恐れがある。

本 PRD では、**「設定タブからいつでも Google Drive 履歴の連携・変更・即時同期・解除ができ、かつ OS の定期ジョブによってバックグラウンドで継続的に同期され続ける」** アーキテクチャを定義する。

---

### 2. Jobs to be Done (JTBD)

| JTBD ID | ユーザー状況 (When) | 動機・やりたいこと (I want to) | 期待する成果 (So I can) |
| :--- | :--- | :--- | :--- |
| **JTBD-SYNC1** | **日常的にアプリの設定を開いたとき** | Google Drive との同期状態（最終同期日時・学習単語数・エラー有無）を一目で確認し、ワンタップで今すぐ同期したい | 自分の単語や変換学習が確実にクラウドへ保全されている安心感を得られる |
| **JTBD-SYNC2** | **同期先ファイルを変更したいとき、または初回にスキップしたあとで連携したくなったとき** | 設定画面からいつでも Google Drive 上の既存ファイル選択・新規ファイル作成・連携解除・権限再許可を行いたい | オンボーディングをやり直さなくても、いつでも自由にクラウド連携を管理できる |
| **JTBD-SYNC3** | **意識せずに複数端末や再インストールに備えたいとき** | キーボードを閉じたりアプリを開いたりしなくても、バックグラウンドで定期的に最新の学習履歴と辞書を自動同期し続けてほしい | 意識的な操作なしで、常に最新の変換環境が Google Drive に保たれる |
| **JTBD-SYNC4** | **ネットワーク不調や再インストールによる権限失効が起きたとき** | ローカルの辞書・履歴が壊れることなく安全にエラーや権限再許可の案内を受け取りたい | データを失うことなく、ワンタップで定期同期を復旧できる |

---

### 3. Critical User Journeys (CUJ)

#### CUJ-SYNC1: 設定タブからの新規連携・同期先変更・連携解除
1. ユーザーが [`MainActivity`](app/src/main/java/com/example/myasr/MainActivity.kt) の **「設定」** タブを開く。
2. **「Google Drive 履歴・辞書同期」カード** に現在の状態（未連携 / 連携済み / 権限の再許可が必要 / エラー）と学習統計（登録単語数・変換単語数・連接数）が表示される。
3. 未連携の場合、「既存ファイルを選択」または「新規作成」をタップし SAF ピッカーからファイルを選択すると、永続権限を取得して即座に初回マージ同期を実行し、定期バックグラウンド同期ジョブを自動登録する。
4. 連携済みの場合は、「同期先を変更」ボタンから別ファイルへ切り替え可能（切り替え時は旧 `sync_base_snapshot.json` を安全にリセットしてから新規同期）。「連携を解除」でローカルデータは保持したまま同期先 URI と定期ジョブを解除できる。

#### CUJ-SYNC2: 設定タブからの手動即時同期
1. 連携済み状態で「今すぐ同期（安全マージ）」をタップする。
2. ボタンとバッジが「同期中…」へ遷移し、[`UserHistorySyncManager.performSync`](app/src/main/java/com/example/myasr/context/kanakanji/application/UserHistorySyncManager.kt) が 3-Way Delta + Tombstone マージを実行する。
3. 完了後、`syncAllUserDictionaries()` によりホットワード・変換エンジンが即時更新され、最終同期日時・サマリーがカードに反映される。

#### CUJ-SYNC3: 定期バックグラウンド同期の継続と頻度設定
1. 同期が有効（`auto_sync_enabled = true` かつ有効な SAF URI あり）なとき、Android の `JobScheduler`（[`HistorySyncJobService`](app/src/main/java/com/example/myasr/context/kanakanji/infrastructure/HistorySyncJobService.kt)）が指定間隔（**15分 / 1時間 / 6時間**、デフォルト **1時間**）でネットワーク接続時にバックグラウンド実行される。
2. ユーザーは設定タブ内の同期カードで「自動・定期同期」スイッチの ON/OFF と同期間隔をいつでも変更できる。
3. `setPersisted(true)`（`RECEIVE_BOOT_COMPLETED`）および [`AsrApp.onCreate`](app/src/main/java/com/example/myasr/AsrApp.kt) での整合性確認により、端末再起動やアプリ更新後も定期同期が継続する。

#### CUJ-SYNC4: 権限失効・エラー時の安全な復旧
1. 再インストール直後等で `sync_file_uri` が残っているが SAF 権限がない場合、設定カードに「権限の再許可が必要」バッジと「ファイルを選択して再連携」ボタンが表示され、定期ジョブは無駄な失敗ループを防ぐため自動停止される。
2. ユーザーが再選択して権限が復旧すると、即座に同期と定期ジョブの再登録が行われる。

---

### 4. コーナーケース対策 (Corner Cases & Safeguards)

| # | コーナーケース | 対策 |
| :--- | :--- | :--- |
| **CC-S1** | **複数トリガーの同時発火（定期 JobScheduler × IME 収納 × 辞書編集デバウンス × 手動同期）** | `UserHistorySyncManager` 内の `syncMutex` により排他制御し、競合時は安全にスキップまたは直列化する。 |
| **CC-S2** | **バックグラウンド定期ジョブでの権限失効（`SecurityException`）** | `HistorySyncJobService` 実行前に `hasValidSyncPermission()` を検証し、失効時はジョブを再スケジュールせず安全に停止（`jobFinished(params, false)`）して UI 側で `PERMISSION_LOST` を表示する。 |
| **CC-S3** | **同期間隔の頻繁な変更** | すでに同じ間隔で `JobScheduler` に登録済みの場合は再登録をスキップし、間隔変更・ON/OFF 切替・URI 変更時のみ `JobScheduler.schedule` / `cancel` を呼ぶ。 |
| **CC-S4** | **同期先ファイルの変更** | `setSyncUri(newUri)` が既存の URI と異なる URI を設定する際、`sync_base_snapshot.json` を削除して旧ファイルとの誤った 3-Way 削除差分を防ぐ。 |
| **CC-S5** | **OS バックグラウンド制限・ネットワーク未接続** | `JobInfo.Builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)` を指定し、オフライン時は OS が接続復帰まで待機するためバッテリー浪費がない。 |
| **CC-S6** | **`MainActivity` と `DictionaryActivity` 間の同期状態整合** | 両画面ともシングルトン `UserHistorySyncManager` と同一 `SharedPreferences` を参照し、`onResume()` で必ず UI を最新化する。 |

---

## Part 2: DMN 決定表設計 (`HistorySyncDecisionModel`)

[`HistorySyncDecisionModel`](app/src/main/java/com/example/myasr/context/kanakanji/domain/HistorySyncDecisionModel.kt) に以下の 2 つの純粋 DMN `DecisionTable` を定義する：

1. **`SyncScheduleDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `autoSyncEnabled`, `hasStoredUri`, `hasValidPermission`, `intervalMinutes`
   - 出力: `ScheduleAction` (`SCHEDULE_PERIODIC`, `CANCEL_DISABLED`, `CANCEL_NO_URI`, `CANCEL_PERMISSION_LOST`), `effectiveIntervalMinutes`
2. **`SettingsSyncCardDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `isSyncing`, `hasStoredUri`, `hasValidPermission`, `autoSyncEnabled`, `lastSyncTimestamp`, `lastSyncError`
   - 出力: `SyncCardState` (`SYNCING`, `ERROR`, `PERMISSION_LOST`, `CONNECTED`, `NOT_CONNECTED`), `showAutoSyncControls`, `badgeText`, `primaryButtonLabel`, `showChangeFileButton`, `showUnlinkButton`, `tone`

---

## Part 3: Alloy による形式仕様 (`history_sync_settings.als`)

[`app/src/test/resources/alloy/history_sync_settings.als`](app/src/test/resources/alloy/history_sync_settings.als) により以下を SAT4J で検証する：
- **`PeriodicJobOnlyWhenEnabledAndPermitted`**: `jobScheduled = True` ならば必ず `autoSyncEnabled = True`、`hasStoredUri = True`、`hasValidPermission = True` がすべて成立していること。
- **`PermissionLostCancelsJobAndShowsWarning`**: `hasStoredUri = True` かつ `hasValidPermission = False` のときは必ず定期ジョブが停止され、UI が `CardPermissionLost` となること。
- **`ChangingSyncUriResetsSnapshot`**: 同期先ファイル変更時は古い Base Snapshot が必ずクリアされること。
- **`Reachability`**: 設定画面から連携・同期・変更・定期実行・解除のすべてへ到達可能であること。
