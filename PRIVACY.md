# Privacy Policy / プライバシーポリシー (JotInput)

Last updated: 2026-11-26

JotInput (**J**apanese **O**n-device **T**ext **Input**) is an open-source, offline-first Android Input Method Editor (IME) and voice keyboard. Privacy by local execution is a core design principle of this application.

---

## English

### 1. Zero Cloud Telemetry & 100% On-Device Processing
- **Typed Text & Keystrokes**: Everything you type on the flick or QWERTY keyboard, swipe gestures, and Kana-Kanji conversions are processed **100% locally on your device**. JotInput never logs, uploads, or transmits your keystrokes, composed text, or surrounding editor context to any external server.
- **Voice Input (Audio)**: Microphone audio captured for speech recognition is processed **100% locally on your device** via ReazonSpeech / Parakeet models running in `sherpa-onnx`. Raw audio is processed in memory and is never uploaded or stored on external servers.
- **No Analytics or Ads**: JotInput contains **no analytics SDKs, no crash-reporting telemetry, no advertising SDKs, and no tracking identifiers**.

### 2. Data Stored Locally on Device
The following data stays exclusively inside your device's app storage (`filesDir` / `getExternalFilesDir` / `SharedPreferences`):
- **Custom User Dictionary** (`custom_dictionary.json`): Words and readings you explicitly add. Empty by default on fresh installs.
- **Typing / Conversion History & Voice Corrections**: Local learning caches used solely to improve on-device candidate ranking.
- **Preferences & GitHub Token (Developer Update Check)**: Settings and (only if explicitly entered by a developer) a GitHub Fine-Grained PAT encrypted via Android Keystore (`GitHubTokenStore`), sent exclusively to `https://api.github.com` when manually checking for dev artifacts.
- **Backup Disabled**: `android:allowBackup="false"` is configured in `AndroidManifest.xml` so Android cloud backup does not automatically upload your local dictionaries, history, or preferences.

**Deleting Local Data**: You can delete individual dictionary entries inside the app, clear history/caches from app settings, or remove all local app data at any time via **Android Settings -> Apps -> JotInput -> Storage & cache -> Clear storage** (or by uninstalling the app).

### 3. Android Permissions & Why Each Is Requested
| Permission | Purpose |
| :--- | :--- |
| `RECORD_AUDIO` | Captures 16 kHz PCM audio solely when you press the microphone / voice-input button for on-device ASR. |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Used **only** when you explicitly trigger on-demand downloads of optional speech models, language models, dictionaries, or check for app updates. Never used in the background to send user input. |
| `POST_NOTIFICATIONS` | Displays progress notifications while downloading/compiling optional models or dictionaries. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC` | Keeps user-initiated model/dictionary downloads and dictionary compilation running reliably without interruption. |
| `WAKE_LOCK` | Prevents the CPU from sleeping mid-download or mid-compilation during large dictionary/model provisioning. |
| `RECEIVE_BOOT_COMPLETED` | Allows Android `JobScheduler` (`HistorySyncJobService`) to reschedule local-only history maintenance jobs across reboots. |

### 4. External Domains Contacted (Only on Explicit Download / Update Check)
When you choose to download optional models, dictionaries, or check for updates, JotInput connects over HTTPS only to the following upstream hosts:
- `huggingface.co` (ReazonSpeech Zipformer, FUTO Swipe ONNX/vocab, gl-keyboard-correction, optional Qwen GGUF SLM)
- `github.com` / `raw.githubusercontent.com` / `gist.githubusercontent.com` / `api.github.com` (sherpa-onnx Parakeet model, Mozc dictionaries & connection matrix, mozcdic-ut dictionaries, base dictionary, optional GitHub update check)
- `formulae.brew.sh` (Optional English technical vocabulary JSON)

---

## 日本語

### 1. 完全オンデバイス処理・外部送信なし
- **入力テキスト・打鍵データ**: フリック入力、QWERTY 入力、スワイプ入力、かな漢字変換、周辺テキスト参照はすべて **端末内で 100% ローカル処理** されます。入力内容が外部サーバーへ送信・収集されることは一切ありません。
- **音声データ**: 音声入力ボタン押下時に取得されるマイク音声は、端末内の `sherpa-onnx`（ReazonSpeech / Parakeet）でローカル認識されます。音声データが外部サーバーへ送信・保存されることはありません。
- **解析・広告 SDK 不使用**: アクセス解析、クラッシュテレメトリ、広告 SDK、トラッキング ID は一切含まれていません。

### 2. 端末内に保存されるデータと削除方法
以下のデータはすべて端末内のアプリ専用ストレージのみに保存されます。
- **カスタム辞書** (`custom_dictionary.json`)：ユーザーが手動登録した語彙（初期状態は空）
- **入力履歴・音声訂正学習キャッシュ**：端末内の変換候補精度向上のみに使用
- **アプリ設定・（任意設定時のみ）開発者向け GitHub PAT**：`GitHubTokenStore` により Android Keystore で暗号化保存され、手動確認時のみ `https://api.github.com` へ送信
- **自動クラウドバックアップ無効化**: `AndroidManifest.xml` で `android:allowBackup="false"` を指定しており、辞書や入力履歴がクラウドへ自動バックアップされることはありません。

**削除方法**: アプリ内の辞書画面や設定画面から個別に削除できるほか、Android の **「設定」→「アプリ」→「JotInput」→「ストレージとキャッシュ」→「ストレージを消去」** またはアプリアンインストールにより全データを完全に消去できます。

### 3. 要求する Android 権限と利用目的
| 権限 | 利用目的 |
| :--- | :--- |
| `RECORD_AUDIO` | ユーザーが音声入力を開始したときのみ、端末内音声認識用に 16kHz PCM 音声を取得するため |
| `INTERNET` / `ACCESS_NETWORK_STATE` | モデル・辞書のオンデマンドダウンロード、またはアップデート確認を明示的に実行したときのみ通信するため（入力内容を送信する用途では使用しません） |
| `POST_NOTIFICATIONS` | モデルや辞書のダウンロード・辞書コンパイルの進捗通知を表示するため |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 大容量のモデル・辞書のダウンロードや構築を途中で中断させず完了させるため |
| `WAKE_LOCK` | ダウンロードや辞書構築処理中のスリープによる中断を防ぐため |
| `RECEIVE_BOOT_COMPLETED` | 端末再起動後にローカル入力履歴の同期ジョブ（`HistorySyncJobService`）を適切に再スケジュールするため |

### 4. 外部接続先ドメイン（ダウンロード・更新確認時のみ）
ユーザーがモデルや辞書のダウンロード等を実行した場合に限り、以下の HTTPS ホストへ接続します：
- `huggingface.co`（ReazonSpeech Zipformer, FUTO Swipe, gl-keyboard-correction, 任意 Qwen GGUF SLM）
- `github.com` / `raw.githubusercontent.com` / `gist.githubusercontent.com` / `api.github.com`（Parakeet 英語音声モデル, Mozc 辞書・連接行列, mozcdic-ut 各種辞書, 基本辞書, 更新確認）
- `formulae.brew.sh`（任意の英語技術語彙辞書）
