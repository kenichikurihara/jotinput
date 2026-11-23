# PRD & System Design: GitHub Actions 開発ビルド更新チェック・バックグラウンドダウンロード機能

## Part 1: Product Requirements Document (PRD)

### 1. 概要 (Executive Summary)
JotInput では GitHub Actions CI/CD ([`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)) により、`main` ブランチのプッシュごとに debug APK アーティファクト (`app-debug` -> `app-debug.apk`) が自動ビルド・保存されている。しかし実機（Pixel 10 Pro や ARCVM 等）で開発版を試用する際、手元に PC/ADB がない状況では「実行中のアプリより新しいビルドが GitHub に存在するか」を確認して端末上だけで取得・更新する手段がなかった。

本機能は、**あくまで開発用機能 (`BuildConfig.DEBUG` 限定)** として以下を実現する：
1. **ホームタブでの最新ビルド確認・ダウンロード導線**: `MainActivity` の **ホームタブ**（クイックアクション行の下）にコンパクトな **「開発ビルド更新 (GitHub)」** カードを配置し、ボタン 1 つで最新アーティファクトの有無を確認・ダウンロード・インストールできる。
2. **完全なバックグラウンド実行**: アーティファクト（ZIP 約 37MB）のダウンロードおよび `app-debug.apk` の自動展開は既存の [`ProvisioningForegroundService`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ProvisioningForegroundService.kt) (`foregroundServiceType="dataSync"`, WakeLock, 進捗通知, キャンセルアクション, 全タブ共通進捗バナー) 上で実行され、アプリを閉じたり他アプリへ移動しても中断しない。
3. **非公開リポジトリ認証と徹底した Fail-Safe**: `kenichikurihara/jotinput` はプライベートリポジトリであり、GitHub Actions アーティファクトのダウンロード API は認証必須である。Fine-Grained PAT (`Actions: Read-only`) を端末内で Android Keystore (`AES/GCM/NoPadding`) 暗号化 + `noBackupFilesDir` に保持し、**PAT 未設定・ネットワーク不通・認証エラー・期限切れ・Release ビルド時は決してクラッシュせず、起動をブロックせず、不要なエラー通信も行わず安全に Fail-Safe する**。

---

### 2. Jobs to be Done (JTBD)

| JTBD ID | Job Statement (状況・動機・期待する成果) |
| :--- | :--- |
| **JTBD-U1** | **新しい CI ビルドの有無を端末だけで知る**: 外出先や PC なしの実機検証時に、いま動いている JotInput より新しい `main` のビルドが GitHub Actions アーティファクトに上がっているかワンタップで確認したい。 |
| **JTBD-U2** | **アプリから離れても止まらないバックグラウンド取得**: 数十 MB のビルド取得中でも他のアプリへ切り替えたり画面を閉じたりでき、完了通知から即座に結果へアクセスしたい。 |
| **JTBD-U3** | **ZIP 解凍を意識せずワンタップで更新**: ダウンロードした GitHub アーティファクト（ZIP）の解凍を手作業で行わず、完了通知またはホームのボタンからワンタップで APK インストール画面を開きたい。 |
| **JTBD-U4** | **GitHub アクセスがない環境での確実な Fail-Safe**: 開発トークン未設定時・オフライン時・権限エラー時・Release ビルド時でも、アプリの基本機能（IME / 音声入力 / 変換）に一切影響を与えず静かに安全側へフォールバックしたい。 |

---

### 3. Critical User Journeys (CUJ)

#### CUJ-U1: 初回（PAT 未設定・Fail-Safe）とトークン設定
1. Debug ビルドを起動すると、ホームタブのクイックアクション行の下に **「開発ビルド更新 (GitHub)」** カードが表示される。
2. 起動時には自動通信を行わず、トークン未設定のためカードは `未連携 (Fail-Safe)` 状態となり、**「トークンを設定」** CTA のみを安全に提示する（ダウンロードボタンは提示しない）。
3. ユーザーが「トークンを設定」または右上の「トークン設定」をタップすると、Fine-Grained PAT (`Actions: Read-only`) の入力ダイアログが開く。
4. トークンを保存すると Android Keystore で暗号化して `noBackupFilesDir` に保存され、そのまま最新ビルド確認を実行できる。空またはキャンセル時は安全に `未連携` のまま維持される。

#### CUJ-U2: 更新あり検知 → バックグラウンドダウンロード → ワンタップインストール
1. ユーザーがホームタブで **「更新を確認」** をタップする。
2. `GitHubArtifactUpdateClient` が GitHub Actions Artifacts API (`per_page=20&name=app-debug`) を取得し、`head_branch == "main"` かつ `!expired` の最新アーティファクトを抽出する。
3. `AppBuildFreshnessDecisionTable` が実行中ビルドのメタデータ（`BUILD_GITHUB_RUN_ID`, `BUILD_GIT_SHA`, `BUILD_GIT_COMMIT_EPOCH_MS`）と最新アーティファクトを比較し、`UPDATE_AVAILABLE` と判定する。
4. カードが `更新あり`（対象 Run ID / コミット短縮 SHA / サイズを表示）に変わり、プライマリボタンが **「バックグラウンドでダウンロード」** になる（横に「再確認」ボタンも表示）。
5. ユーザーがダウンロードをタップすると、`ProvisioningForegroundService` に `ProvisioningTaskKind.APP_UPDATE_APK` がエンキューされる。
6. タブ横断のグローバル進捗バナー・カード内プログレスバー・システム通知に進捗（0〜90%: ZIP 取得、90〜99%: APK 自動展開）が表示される。ユーザーは設定タブ・データタブ・別アプリへ自由に移動できる。
7. ダウンロードと Zip-Slip 安全な APK 展開が完了すると、`完了通知`（「インストール」アクションおよびタップで即インストール起動）が発行され、ホームカードのボタンが **「インストール」** に変わる。
8. ユーザーが「インストール」をタップすると、`AppUpdateInstaller` が `REQUEST_INSTALL_PACKAGES` 権限を確認し、未許可ならシステムの「不明なアプリのインストール」設定へ誘導して復帰後に自動再開、許可済みなら `FileProvider` 経由で Android パッケージインストーラを起動する。
9. インストール後の新ビルドでアプリを開くと、`AppUpdateRepository` が古い/導入済みキャッシュ APK を自動クリーンアップし、最新状態を保つ。

#### CUJ-U3: すでに最新ビルドを実行中
1. ローカル `./deploy.sh` ビルドや最新 CI ビルドと同一 Run ID / 同一 SHA / 新しいコミット時刻のビルドを実行している状態で「更新を確認」をタップする。
2. `AppBuildFreshnessDecisionTable` が `UP_TO_DATE` を返し、カードは緑バッジ `最新` となり「再確認」のみを表示する（不要なダウンロード・インストールボタンは表示しない）。

#### CUJ-U4: オフライン / 認証エラー (401 / 403) / アーティファクト期限切れ・不在の Fail-Safe
1. ネットワークエラーやタイムアウト時は `NETWORK_ERROR` 状態へ遷移し、クラッシュせず再試行用「更新を確認」ボタンを表示する。
2. HTTP 401 / 403（トークン失効・権限不足）時は `AUTH_ERROR` 状態へ遷移し、トークン再設定 CTA と再確認ボタンを表示する。
3. 有効な `app-debug` アーティファクトが存在しない（全て `expired=true` 等）場合は `NO_ARTIFACT` 状態となり、安全に待機・再確認可能にする。

#### CUJ-U5: バックグラウンド中のタブ切替・タスク単位キャンセル
1. ダウンロード中に他タブ（設定/データ）へ移動しても、`ProvisioningForegroundService` とグローバル進捗バナー（タップでホームタブへ遷移）により進捗が維持される。
2. ホームカードまたは通知の「キャンセル」から `APP_UPDATE_APK` タスクのみを安全にキャンセルでき、同時にキューされている辞書・音声モデルダウンロードを巻き添えで破壊しない（`.zip.tmp` / `.apk.tmp` も即時削除）。

#### CUJ-U6: Release ビルドでの完全非表示
1. `BuildConfig.DEBUG == false`（リリースビルド）では、`AppUpdateUiDecisionTable` の最優先ルール `APP_UPD_R1` によりカード自体が `View.GONE` となり、`app/src/debug/AndroidManifest.xml` のみに `REQUEST_INSTALL_PACKAGES` を閉じ込めるため Google Play ポリシーにも影響しない。

---

## Part 2: Technical Architecture & Security Design

### 2.1 Bounded Context 配置 (`BC-6: Provisioning`)
- **Domain (`context.provisioning.domain`)** — Android UI / インフラ非依存の純粋 Kotlin:
  - [`AppUpdateModels.kt`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateModels.kt)
  - [`AppUpdateDecisionModel.kt`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateDecisionModel.kt)
- **Infrastructure (`context.provisioning.infrastructure`)**:
  - [`GitHubTokenStore.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/GitHubTokenStore.kt) — Android Keystore (`AES/GCM/NoPadding`) + `noBackupFilesDir` 保存（Keystore 利用不可環境での Fail-Safe あり）
  - [`GitHubArtifactUpdateClient.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/GitHubArtifactUpdateClient.kt) — API クライアント・リダイレクト時トークン剥離・Zip-Slip 防御解凍
  - [`AppUpdateRepository.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/AppUpdateRepository.kt) — メタデータキャッシュ・APK 鮮度照合・クリーンアップ
  - [`AppUpdateInstaller.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/AppUpdateInstaller.kt) — `FileProvider` + インストール許可判定
  - [`ProvisioningForegroundService.kt`](app/src/main/java/com/example/myasr/context/provisioning/infrastructure/ProvisioningForegroundService.kt) — `ProvisioningTaskKind.APP_UPDATE_APK` と `cancelKind(...)`

### 2.2 リダイレクト時の Authorization 漏洩防止と Zip-Slip 防御
- GitHub Actions の `/repos/{owner}/{repo}/actions/artifacts/{id}/zip` は HTTP 302 で Azure Blob Storage SAS URL へリダイレクトする。
- `GitHubArtifactUpdateClient` は `instanceFollowRedirects = false` で手動追従し、`Authorization: Bearer <token>` は **`https://api.github.com` 宛てリクエストにのみ付与** し、別ホストへのリダイレクト時はヘッダを確実に剥離する（HTTP へのダウングレードも拒否）。
- ZIP 解凍時はエントリ名をパス構築に一切使用せず固定の `destApkFile.tmp` へストリーミング展開し、`PK\x03\x04` マジックバイトとサイズ上下限を検証した上で原子的にリネームする。

---

## Part 3: DMN 決定表設計 (Decision Model and Notation)

[`AppUpdateDecisionModel`](app/src/main/java/com/example/myasr/context/provisioning/domain/AppUpdateDecisionModel.kt) に 2 つの `HitPolicy.FIRST` 決定表を定義する：

1. **`AppBuildFreshnessDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `InstalledAppBuildInfo` + `RemoteBuildArtifact?`
   - 判定順序:
     1. リモート不在または期限切れ (`expired`) → `NO_ARTIFACT`
     2. 双方の `runId > 0` なら `runId` 比較（CI ビルド間比較）
     3. `gitSha` 一致なら `UP_TO_DATE`
     4. 双方のエポック時刻 `> 0` なら `remote.createdAtMs > local.commitEpochMs` で新旧判定（ローカル `./deploy.sh` ビルド ↔ CI ビルド比較）
     5. 時刻不明時は `NO_ARTIFACT` / フォールバック `UPDATE_AVAILABLE`
2. **`AppUpdateUiDecisionTable` (`HitPolicy.FIRST`)**:
   - 入力: `isDebugBuild`, `hasGitHubToken`, `isChecking`, `isDownloadActive`, `isDownloadQueued`, `freshnessStatus`, `hasMatchingDownloadedApk`, `lastErrorKind`
   - 出力: `AppUpdateCardState`, `AppUpdatePrimaryAction`, `AppUpdateBadgeTone`, `isCardVisible`, `primaryButtonEnabled`, `showProgressBar`, `showSecondaryCheckButton`

---

## Part 4: Alloy による形式仕様 (Formal Verification)

[`app/src/test/resources/alloy/github_app_update_lifecycle.als`](app/src/test/resources/alloy/github_app_update_lifecycle.als) により以下を網羅検査する：
- **`ReleaseBuildNeverShowsCard`**: 非 Debug ビルドでは常にカード非表示。
- **`NoTokenFailSafeNeverOffersDownload`**: トークン未設定（かつ一致済 APK 不在）時は決してダウンロードやインストールを提示しない。
- **`ErrorStateNeverOffersDownloadOrInstall`**: 認証・通信・DL 失敗時はダウンロードやインストールに誤遷移しない。
- **`DownloadOnlyWhenTokenAndUpdateAvailable`**: ダウンロード CTA は Debug・トークン有・更新あり時のみ表示。
- **`InstallOnlyWhenMatchingApkExistsAndDebug`**: インストール CTA は Debug かつ整合 APK 保持時のみ表示。
- **`UpToDateNeverOffersDownloadOrInstall`**: 最新判定時は更新ボタンのみを表示。
- **`ActiveDownloadAlwaysShowsProgressAndCancel`**: バックグラウンド DL/待機中は進捗バーとキャンセル CTA を必ず表示。
