# PRD & System Design: トークン不要アプリ内更新（リリース版 / リモートヘッド選択対応）

## Part 1: Product Requirements Document (PRD)

### 1. 概要 (Executive Summary)
`kenichikurihara/jotinput` がパブリックリポジトリとして公開され、署名済み Release APK (`v*` タグの GitHub Releases) および `main` ブランチの CI ビルドが公開配布可能となった。従来のアプリ内アップデーターはプライベートリポジトリ前提で Fine-Grained PAT (`GitHubTokenStore`) と `BuildConfig.DEBUG` 限定ガードに依存していたため、一般ユーザー向けの Release ビルドでは更新確認ができず、Debug ビルドでもトークン設定が必要だった。

本機能は、**Debug ビルド・Release ビルドの両方において GitHub トークン一切不要（完全認証レス）** でアプリ内更新を提供し、さらにユーザーが取得元チャネルを選択できるようにする：
1. **ホームタブでのチャネル切替・更新確認・ダウンロード導線**: `MainActivity` の **ホームタブ**（クイックアクション（「単語登録」カード）の下）に **「アプリ更新 (GitHub)」** カードを配置し、セグメント切替（`リリース版` / `リモートヘッド`）とボタン 1 つで最新バージョンの確認・バックグラウンドダウンロード・インストールを行える。
2. **2 チャネルの公開配布アーキテクチャ**:
   - **リリース版 (`RELEASE`)**: `https://api.github.com/repos/kenichikurihara/jotinput/releases/latest` を参照し、公式安定版 (`v*`) の署名済み Release APK を取得する（Release ビルドのデフォルト）。
   - **リモートヘッド (`REMOTE_HEAD`)**: `main` マージごとに `Android CI` (`publish-head`) が更新するローリング・プレリリース (`main-head`) の `head-metadata.json`（GitHub Releases 直接ダウンロードのため API レート制限非消費）を参照する（Debug ビルドのデフォルト）。実行中のビルド種別に応じて、Debug ビルドには `jotinput-main-head-debug.apk`（永続 Debug 鍵署名）、Release ビルドには `jotinput-main-head-release.apk`（正式 Release 鍵署名）を自動選択し、同一チャネル内での上書き更新互換性を保証する。
3. **完全なバックグラウンド実行と整合性検証**: APK のダウンロードは [`ProvisioningForegroundService`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ProvisioningForegroundService.kt) (`foregroundServiceType="dataSync"`, WakeLock, 進捗通知, キャンセルアクション, 全タブ共通進捗バナー) 上で実行され、ストリーミング中に `PK\x03\x04` ヘッダ検証と SHA-256 ダイジェスト検証を行う。

---

### 2. Jobs to be Done (JTBD)

| JTBD ID | Job Statement (状況・動機・期待する成果) |
| :--- | :--- |
| **JTBD-U1** | **トークン設定なしで最新版の有無を知る**: PAT の発行や設定を行わずに、一般ユーザーも開発者もホームタブからワンタップで公開リリースや最新 `main` ビルドの有無を確認したい。 |
| **JTBD-U2** | **リリース版とリモートヘッドを目的に応じて選ぶ**: 安定版を使いたいときは「リリース版」、最新の修正や機能をいち早く試したいときは「リモートヘッド」を UI 上で明示的に切り替えたい。 |
| **JTBD-U3** | **アプリから離れても止まらないバックグラウンド取得**: 数十 MB の APK ダウンロード中でも他アプリへ切り替えたり画面を閉じたりでき、完了通知から即座にインストール画面を開きたい。 |
| **JTBD-U4** | **改ざん・破損・鍵不一致の事前検知と確実な Fail-Safe**: ダウンロード破損（SHA-256 不一致）、レート制限、オフライン、Debug↔Release 切替時の署名鍵不一致を安全に検知し、クラッシュや意味不明な失敗（「アプリがインストールされていません」）を防ぎたい。 |
| **JTBD-U5** | **署名鍵の一貫性によるシームレスな上書き更新**: Debug ビルドは Debug 署名版、Release ビルドは Release 署名版の成果物を自動選択することで、毎回のアンインストールやデータ消去なしに更新したい。 |

---

### 3. Critical User Journeys (CUJ)

#### CUJ-U1: トークン不要での起動・チャネル選択
1. アプリを起動すると（Debug / Release いずれでも）、ホームタブのクイックアクション（「単語登録」カード）の下に **「アプリ更新 (GitHub)」** カードが表示される。
2. トークン設定ボタンやトークン要求ダイアログは一切存在しない。初期チャネルは Release ビルドでは `リリース版`、Debug ビルドでは `リモートヘッド` が自動選択され、ユーザーによる切り替え内容は `SharedPreferences` に保存される。
3. 起動時には自動通信を行わず（`IDLE` 状態）、ユーザーの操作でのみ更新チェックを開始する。
4. 過去のバージョンで `noBackupFilesDir/github_actions_pat.dat` に保存されていたレガシー PAT ファイルが存在する場合は、初期化時に安全に自動削除する。

#### CUJ-U2: チャネル選択 → 更新あり検知 → バックグラウンドダウンロード → インストール
1. ユーザーがセグメントトグルで **「リリース版」** または **「リモートヘッド」** を選び、**「更新を確認」** をタップする。
2. `AppUpdateRepository` が `GitHubAppUpdateClient` 経由で認証なし HTTPS リクエストを実行する：
   - `リリース版`: `https://api.github.com/repos/kenichikurihara/jotinput/releases/latest` を取得し、非ドラフト・非プレリリースの `*-release.apk` アセットを抽出。
   - `リモートヘッド`: `https://github.com/kenichikurihara/jotinput/releases/download/main-head/head-metadata.json` を取得し、実行中ビルドの `isDebugBuild` に一致するバリアント（`debugApk` または `releaseApk`）を抽出。
3. `AppBuildFreshnessDecisionTable` がチャネルに応じた比較ルール（Release: SemVer / `versionCode`、Remote HEAD: `gitSha` / `runId` / `commitEpochMs`）で新旧判定し、`UPDATE_AVAILABLE` ならプライマリボタンが **「バックグラウンドでダウンロード」** へ遷移する。
4. ユーザーがダウンロードをタップすると、`ProvisioningForegroundService` に `ProvisioningTaskKind.APP_UPDATE_APK`（`dictId = channel.id`）がエンキューされる。
5. ダウンロード中は他タブへの移動やバックグラウンド遷移が可能で、チャネル切替トグルは誤操作防止のため自動的にロックされる（`channelSelectorEnabled = false`）。
6. 直接 HTTPS ストリーミング中にサイズ上限、`PK\x03\x04` マジックバイト、期待 SHA-256 ダイジェストを検証し、検証成功後に原子的リネームして完了通知を発行する。
7. ユーザーが **「インストール」**（または通知）をタップすると、`AppUpdateInstaller` が `ApkInstallCompatibilityDecisionTable` によりパッケージ ID・ダウングレード有無・署名証明書 SHA-256 一致を検査し、整合かつ `REQUEST_INSTALL_PACKAGES` 許可済みなら Android パッケージインストーラを起動する（未許可なら設定画面へ誘導し復帰後に自動再開）。

#### CUJ-U3: Debug ↔ Release チャネル跨ぎの署名鍵不一致ハンドリング
1. Debug ビルド上で「リリース版」を選択し、取得・インストールを試みた場合など、実行中アプリと取得 APK の署名証明書が異なる場合は、UI サマリーで事前案内しつつ、インストール実行時にも `ApkInstallCompatibilityDecisionTable` が `SIGNATURE_MISMATCH` を事前検知する。
2. 実行中・取得済みの証明書フィンガープリント、1 回限りのアンインストール再導入案内、および **「キャッシュAPKを削除」** アクションを提示する。

#### CUJ-U4: チャネル切替時のキャッシュ隔離とダウンロード済み APK 管理
1. 「リモートヘッド」で APK をダウンロード済み（`READY_TO_INSTALL`）の状態で、ユーザーが「リリース版」へ切り替えた場合、別チャネルのダウンロード済み APK は誤って「リリース版としてインストール」に提示されない（厳格なチャネル隔離）。
2. 新しいバージョンの起動時に古いステージ済み APK が不要になった場合は、`AppUpdateRepository` が自動削除する。

#### CUJ-U5: レート制限・破損（SHA-256 不一致）・オフライン時の Fail-Safe
1. GitHub API のレート制限（HTTP 403 `X-RateLimit-Remaining: 0` または HTTP 429）時は `RATE_LIMITED`（バッジ `制限中`）へ遷移し、クラッシュせず再確認を促す（`リモートヘッド` は Releases 直接ダウンロードにより API クォータを消費しない）。
2. ダウンロードファイルの SHA-256 不一致や ZIP/APK ヘッダ異常時は一時ファイルを即時破棄し `INTEGRITY_CHECK_FAILED`（バッジ `検証失敗`）として安全に停止する。
3. ネットワーク不通時は `NETWORK_ERROR` / `DOWNLOAD_FAILED`（バッジ `接続不可`）として再確認ボタンを提示し、IME / 音声入力の動作には一切影響を与えない。

#### CUJ-U6: ダウンロード中のタブ切替・タスク単位キャンセル
1. ダウンロード中に他タブへ移動してもグローバル進捗バナー（タップでホームタブへ遷移）が進捗を表示する。
2. ホームカードまたは通知の「キャンセル」で `APP_UPDATE_APK` タスクのみを安全にキャンセルでき、`.tmp` ファイルを即時削除し、他の辞書・モデルダウンロードを巻き添えにしない。

---

## Part 2: Technical Architecture & Security Design

### 2.1 Bounded Context 配置 (`BC-6: Provisioning`)
- **Domain (`context.provisioning.domain`)** — Android UI / インフラ非依存の純粋 Kotlin:
  - [`AppUpdateModels.kt`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateModels.kt) — `AppUpdateChannel` (`RELEASE`, `REMOTE_HEAD`), `RemoteBuildArtifact`, `DownloadedApkInfo`, `AppUpdateCheckErrorKind`, `AppUpdateCardState`, `AppUpdatePrimaryAction`
  - [`AppUpdateDecisionModel.kt`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateDecisionModel.kt) — SemVer 比較と 3 つの DMN `DecisionTable`
- **Infrastructure (`context.provisioning.infrastructure`)**:
  - [`GitHubAppUpdateClient.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/GitHubAppUpdateClient.kt) — 認証なし GitHub Releases / `head-metadata.json` クライアント、HTTPS 専用リダイレクト追従、ストリーミング SHA-256 / `PK\x03\x04` 検証
  - [`AppUpdateRepository.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/AppUpdateRepository.kt) — チャネル設定永続化・チャネル別メタデータキャッシュ・レガシー PAT 自動削除・クロスチャネル隔離された APK ステージング
  - [`AppUpdateInstaller.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/AppUpdateInstaller.kt) — `FileProvider` + `REQUEST_INSTALL_PACKAGES` 判定 + 署名証明書 SHA-256 / パッケージ整合性検査
  - [`ProvisioningForegroundService.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ProvisioningForegroundService.kt) — `ProvisioningTaskKind.APP_UPDATE_APK`（`dictId` にチャネル ID を保持）と `cancelKind(...)`

### 2.2 CI/CD による `main-head` ローリング・プレリリース配信
- [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml):
  - `build-and-test` (`Build & Test`): `main` 向け全 PR（`paths-ignore` なし）および `main` push で単体テスト・`assembleDebug`（永続 Debug 署名検証）・`assembleRelease`（R8 + 16KB ELF 検証）を実行。
  - `publish-head` (`Publish Remote HEAD Prerelease`): `main` push / `workflow_dispatch` 時に `release` 環境で署名済み `jotinput-main-head-release.apk` をビルドし、`app-debug` から `jotinput-main-head-debug.apk` をパッケージング、両者の SHA-256 と `versionCode` / `versionName` / `gitSha` / `commitEpochMs` / `runId` を格納した `head-metadata.json` を生成する。
  - タグ `main-head` を最新 `GITHUB_SHA` に更新し、`gh release ... --prerelease --latest=false` でプレリリースを更新する。`--latest=false` により `/releases/latest` は公式 `v*` リリースを指し続け、`main-head` タグは `v*` に一致しないため [`release.yml`](.github/workflows/release.yml) を誤発火させない。
  - クライアントが半端な状態を読まないよう、APK 群と `SHA256SUMS.txt` を先にアップロードしたあと最後に `head-metadata.json` をアップロードする。

### 2.3 セキュリティ・権限・プライバシー設計
- **認証ヘッダ非使用**: 公開エンドポイントのみを使用し、`Authorization` ヘッダやトークン保存機構 (`GitHubTokenStore`) を完全に撤廃。
- **HTTPS ホスト許可リスト**: ダウンロード URL とリダイレクト先は `https://` かつ `github.com` / `api.github.com` / `*.githubusercontent.com` のみに制限し、HTTP ダウングレードや外部ドメインへの誘導を拒否。
- **改ざん検知**: APK ダウンロードは一時ファイルへストリーミングし、`PK\x03\x04` マジックバイト、サイズ範囲（1 MB〜220 MB）、および期待 SHA-256 ダイジェスト一致を検証した上で原子的に配置する。
- **インストール権限**: `app/src/main/AndroidManifest.xml` に `REQUEST_INSTALL_PACKAGES` を宣言し（`app/src/debug/AndroidManifest.xml` は削除）、ユーザーが明示的に「インストール」を押した際にのみシステム許可画面へ誘導する。

---

## Part 3: DMN 決定表設計 (Decision Model and Notation)

[`AppUpdateDecisionModel`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateDecisionModel.kt) に 3 つの `HitPolicy.FIRST` 決定表を定義する：

1. **`AppBuildFreshnessDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `InstalledAppBuildInfo` + `RemoteBuildArtifact?`
   - 判定順序:
     - `BUILD_FRESH_R1`: リモート不在・期限切れ・URL 空 → `NO_ARTIFACT`
     - `BUILD_FRESH_R2`〜`R5` (`RELEASE` チャネル):
       - `versionName` 欠落 → `NO_ARTIFACT`
       - `compareSemVer(remote.versionName, installed.versionName) > 0` → `UPDATE_AVAILABLE`
       - `remote.versionCode > installed.versionCode`（双方 `> 0`） → `UPDATE_AVAILABLE`
       - それ以外 → `UP_TO_DATE`
     - `BUILD_FRESH_R6`〜`R11` + `DEFAULT` (`REMOTE_HEAD` チャネル):
       - `gitSha` 一致 → `UP_TO_DATE`
       - 双方 `runId > 0` で `remote.runId > installed.githubRunId` → `UPDATE_AVAILABLE`、それ以外 → `UP_TO_DATE`
       - 双方エポック時刻 `> 0` で `remote.effectiveTimestampMs > installed.commitEpochMs` → `UPDATE_AVAILABLE`、それ以外 → `UP_TO_DATE`
       - リモート時刻欠落 → `NO_ARTIFACT`、ローカルメタ情報不明時フォールバック → `UPDATE_AVAILABLE`
2. **`AppUpdateUiDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `selectedChannel`, `isChecking`, `isDownloadActive`, `isDownloadQueued`, `freshnessStatus`, `hasMatchingDownloadedApk`, `lastErrorKind`
   - 出力: `cardState`, `primaryAction`, `badgeTone`, `primaryButtonEnabled`, `channelSelectorEnabled`, `showProgressBar`, `showSecondaryCheckButton`, `badgeText`, `primaryButtonLabel`
   - 特徴: ビルド種別によらず常にカードを表示し、ダウンロード中・待機中・確認中は `channelSelectorEnabled = false` としてチャネル切替をロックする。
3. **`ApkInstallCompatibilityDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `apkReadable`, `installedPackageName`, `archivePackageName`, `installedVersionCode`, `archiveVersionCode`, `installedSigningCertSha256s`, `archiveSigningCertSha256s`
   - 出力: `ApkInstallCompatibilityStatus` (`COMPATIBLE`, `UNREADABLE_ARCHIVE`, `PACKAGE_NAME_MISMATCH`, `VERSION_DOWNGRADE`, `SIGNATURE_MISMATCH`, `SIGNATURE_UNVERIFIABLE`), `canProceedToInstaller`, `reason`

---

## Part 4: Alloy による形式仕様 (Formal Verification)

[`app/src/test/resources/alloy/github_app_update_lifecycle.als`](app/src/test/resources/alloy/github_app_update_lifecycle.als) により以下を網羅検査する：
- **`BusyStateLocksChannelSelector`**: 確認中・ダウンロード待機中・ダウンロード実行中はチャネル切替が必ず無効化されること。
- **`ChannelNeverChangesWhileBusy`**: 確認中・ダウンロード待機中・ダウンロード実行中に選択チャネルが切り替わらないこと。
- **`ErrorStateNeverOffersDownloadOrInstall`**: レート制限・整合性検証失敗・通信/DL エラー時はダウンロードやインストールに誤遷移しないこと。
- **`DownloadOnlyWhenUpdateAvailableAndNotBusy`**: ダウンロード CTA は更新ありかつエラー・確認・DL が非アクティブのときのみ提示されること。
- **`InstallOnlyWhenChannelMatchingApkExists`**: インストール CTA は現在選択中チャネルと一致する検証済み APK が存在し、かつエラーがないときのみ提示されること。
- **`UpToDateNeverOffersDownloadOrInstall`**: 最新判定時は更新確認ボタンのみを表示すること。
- **`ActiveDownloadAlwaysShowsProgressAndCancel`**: バックグラウンド DL/待機中は進捗バーとキャンセル CTA を必ず表示すること。
