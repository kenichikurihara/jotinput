# PRD & Design Document: 予測候補選択・削除後の予測候補一覧復元 (Prediction Checkpoint Restoration)

## 1. 背景と課題 (Problem Statement)

日本語フリック入力において、ユーザーが文節を確定した直後には次文節予測候補（Zero-Query Prediction）が候補ストリップに表示される。しかし、ユーザーがその予測候補をタップして確定し、その後バックスペース（`⌫`）や選択削除、カーソル移動等で確定した候補を消去して**元の位置（例: `"明日の"` の直後）**に戻った場合、従来の JotInput では予測候補一覧が消えてアイドル状態のヒント文字列（`"話すか、フリックで入力"`）になってしまっていた。

具体的な例：
1. ユーザーが `"あしたの"` を入力し、`"明日の"` を確定する → 候補ストリップに `["会議", "件", "予定", "連絡", "内容", ...]` が表示される。
2. ユーザーが予測候補の `"会議"` をタップする → エディタが `"明日の会議"` になる。
3. ユーザーが `"会議"` をバックスペース2回で消す（`"明日の会"` → `"明日の"`）か、選択削除・カーソル復帰で `"明日の"` の位置に戻る。
4. **期待される動作**: `"明日の"` を確定した直後と同じ予測候補一覧 `["会議", "件", "予定", "連絡", "内容", ...]` が再び表示されてほしい。
5. **従来の問題（4つの根本原因）**:
   - **原因 1 (`VoiceInputMethodService.onDeletePressed`)**: かなモードにおける `DELETED_SURROUNDING_TEXT` および `DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY` で、無条件に `candidateStripView?.showEmptyStatus(getActiveIdleStatusPrompt())` を呼んでいた。
   - **原因 2 (`VoiceTouchInputArbitrator` の状態消失)**: バックスペース押下時に `previousCommittedPhrase = ""` と消去してしまい、各確定境界ごとの予測スナップショット（`PredictionCheckpoint`：カーソル前文脈・確定フレーズ・表示候補一覧・SLM リランク済みフラグ・AI バッジ・ロールバックトークン）を保持していなかった。
   - **原因 3 (`NextPhrasePredictor` の学習汚染と状態破壊)**: `"会議"` タップ時に即座に `recordTransition("明日の", "会議")` で Bigram / Skip-Bigram 学習カウントと `lastCommittedContentPhrase = "会議"` を更新してしまい、直後に `"会議"` を削除してもロールバックされず、学習履歴汚染とコンテキスト不整合を起こしていた。
   - **原因 4 (`NextPhrasePredictor` の助詞末尾複合文節の扱い)**: `"明日の"` のように末尾に助詞を含む複合文節が単一単位で確定された場合、`cleanLast` が `"明日の"` のままになり、`"の"` 1次 Bigram や `"明日|の"` Skip-Bigram の学習・予測が欠落していた。

---

## 2. Jobs to be Done (JTBD)

- **JTBD-1 (誤タップ・気が変わった後の即座な予測再選択)**:
  - **状況**: ユーザーが `"明日の"` を確定して表示された予測候補 `"会議"` をタップしたが、すぐ後に「やっぱり別の候補（`"予定"` や `"連絡"` など）にしたい」と思い、バックスペースで `"会議"` を消去したとき、
  - **動機**: もう一度 `"明日の"` を打ち直したり、最初から次の読みを入力し直したりせず、先ほど見えていた予測候補一覧から即座に選び直したい。
  - **期待成果**: カーソルが `"明日の"` の直後に戻った瞬間に、先ほど表示されていたものと同じ予測候補一覧が遅延・レイアウトジッターなく再表示される。

- **JTBD-2 (多段予測タップと段階的バックスペース削除での境界復元)**:
  - **状況**: ユーザーが `"明日"` → `"の"` → `"会議"` → `"に"` と予測候補を連続タップしたあと、バックスペースで1文字ずつ戻していくとき、
  - **動機**: 消して戻った各確定位置（`"明日の会議"`、`"明日の"`、`"明日"`）それぞれで、その文脈に対応した予測候補一覧を見てから続きを決めたい。
  - **期待成果**: 過去の確定境界のスタックを保持し、`"に"` を消せば `"明日の会議"` 直後の予測、`"会議"` を消せば `"明日の"` 直後の予測、`"の"` を消せば `"明日"` 直後の予測がそれぞれ復元される。

- **JTBD-3 (未確定文字キャンセル・カーソル復帰時の予測候補復帰)**:
  - **状況**: `"明日の"` を確定後、次の文字 `"か"` をフリック入力したがバックスペースで未確定文字を取り消したとき、またはカーソルを少し動かしたあと `"明日の"` の直後に戻したとき、
  - **動機**: 未確定入力のキャンセルやカーソル復帰後も、その位置に続く予測候補をすぐ使いたい。
  - **期待成果**: 未確定文字列が空に戻った時点・カーソルがチェックポイント位置に一致した時点で `"明日の"` の予測候補一覧が復元される。

- **JTBD-4 (撤回された候補による学習履歴汚染の防止)**:
  - **状況**: 予測候補 `"会議"` を誤ってタップし、直後にバックスペースで消去したとき、
  - **動機**: 採用しなかった `"会議"` が次回以降の高頻度学習候補（`history_bigram` / `history_skip_bigram`）として誤学習されてほしくない。
  - **期待成果**: チェックポイントを跨いで後方削除された候補の Bigram / Skip-Bigram カウントおよび `lastCommittedContentPhrase` が正確にロールバックされる。

---

## 3. Critical User Journeys (CUJ)

### CUJ-1: 予測候補タップ後のバックスペース削除による完全復元（代表シナリオ）
1. ユーザーが `"あしたの"` を入力して `"明日の"` を確定する。
2. `showNextPhrasePredictions("明日の")` が Fast Path + SLM リランクで予測候補一覧（例: `["会議", "件", "予定", "連絡", "内容", ...]`）を生成・表示し、`VoiceTouchInputArbitrator` に `"明日の"` の `PredictionCheckpoint`（候補一覧・SLM状態・AIバッジ・ロールバックトークン）を保存する。
3. ユーザーが候補 `"会議"` をタップする → エディタが `"明日の会議"` になり、`recordTransitionWithToken` が返す `TransitionRollbackToken` が `"明日の会議"` チェックポイントへ紐付けられる。
4. ユーザーが `⌫` を1回押す → `"明日の会"` になる。`"明日の会議"` チェックポイントはコンテキスト長が縮退したためスタックからポップされ、`rollbackTransition` により `"会議"` の学習カウントと `lastCommittedContentPhrase` が `"明日"` へ巻き戻される。
5. ユーザーが `⌫` をもう1回押す → `"明日の"` になる。`reconcilePredictionCheckpointsAfterDelete("明日の")` が `"明日の"` の `PredictionCheckpoint` に完全一致し、`CandidateRerankJudgement.predictionRestorationDecisionTable` が `RESTORE_CHECKPOINT_SINGLE_PASS` を選択、`"明日の"` 確定直後と同一の予測候補一覧が即座に単一パスで復元される。
6. ユーザーが別の予測候補 `"予定"` をタップして入力を継続できる。

### CUJ-2: 多段予測タップからの段階的バックスペース削除
1. ユーザーが `"明日"` を確定（Checkpoint #1）→ 予測 `"の"` をタップ（Checkpoint #2: `"明日の"`）→ 予測 `"会議"` をタップ（Checkpoint #3: `"明日の会議"`）→ 予測 `"に"` をタップ（Checkpoint #4: `"明日の会議に"`）。
2. `⌫` で `"に"` を消去 → Checkpoint #4 をポップ・ロールバックし、Checkpoint #3（`"明日の会議"`）の予測一覧を復元。
3. `⌫` 2回で `"会議"` を消去 → Checkpoint #3 をポップ・ロールバックし、Checkpoint #2（`"明日の"`）の予測一覧を復元。
4. `⌫` 1回で `"の"` を消去 → Checkpoint #2 をポップ・ロールバックし、Checkpoint #1（`"明日"`）の予測一覧を復元。

### CUJ-3: 範囲選択削除・長押し連続削除・カーソル復帰
1. `"明日の会議"` で `"会議"` を範囲選択して `⌫` を押す（一括削除）、または長押し `⌫` でバッチ削除して `"明日の"` へ戻る。
2. `onDeletePressed(count)` が削除後の実テキスト `"明日の"`（または予測削除長）を解決し、通過したチェックポイントを順番にポップ・ロールバックして `"明日の"` の予測一覧を復元する。
3. また、`◀`/`▶` キーやホストアプリのタップ操作で一時的にカーソル移動後、`"明日の"` の直後にカーソルが戻った場合も `onUpdateSelection` からチェックポイントを照合して予測候補を復元する（パスワード欄・非かなモードでは復元しない）。

### CUJ-4: 確定後の未確定入力キャンセル（Composing Now Empty）
1. `"明日の"` を確定し予測候補が表示されている状態で、ユーザーが `"か"` を1文字フリック入力する（キーボード未確定中）。
2. ユーザーがすぐ `⌫` を押して `"か"` を消す（`DeleteOutcome.DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY`）。
3. 従来のアイドルメッセージではなく、現在のカーソル前文脈 `"明日の"` に対するチェックポイントが照合され、再び `"明日の"` の予測候補一覧が表示される。

### CUJ-5: チェックポイント外の既存文削除・空白／文頭削除・パスワード欄保護
1. `"明日の会議"` で `"議"` だけ1文字消した `"明日の会"` のようにチェックポイントに一致しない中間テキストでは、`"会"` が末尾予測可能語であれば `RECOMPUTE_FROM_SURROUNDING_CONTEXT` により文脈末尾からの予測更新、そうでなければ空ステータス表示を行う（ただし `"明日の"` のチェックポイントは先頭一致するためスタックに残し、もう1文字消した `"明日の"` 到達時に元の予測を復元する）。
2. 全文字消去（空文字）、末尾が空白・改行・句読点のみ、非かなモード（QWERTY/英字等）、またはパスワード入力欄の場合は、`PredictionRestorationPolicy` により確実に `SHOW_EMPTY_STATUS` を返し、学習・復元・表示を行わない。

---

## 4. Design Document (DD)

### 4.1 アーキテクチャ概要と Bounded Context 分担
- **BC-1 (`context/kanakanji/domain`)**:
  - `JapaneseKanaUtils.hasTrailingPredictablePhrase(contextPrefix)`: カーソル前文字列の末尾が空白・改行・句読点ではなく、予測可能な語または助詞で終わるかを判定する純粋ドメイン関数。
  - `NextPhrasePredictor`:
    - `BigramKeySnapshot` / `TransitionRollbackToken` による Bigram・Skip-Bigram・`bigramReadings`・`lastCommittedContentPhrase` の原子的スナップショットと `rollbackTransition(token)`。
    - `"明日の"` のような助詞末尾複合文節における `extractTrailingContentAndParticle` を用いた `"の"` 1次遷移・`"明日|の"` Skip-Bigram 学習と予測の一貫処理。
- **BC-2 (`context/reranking/domain`)**:
  - `CandidateRerankJudgement.predictionRestorationDecisionTable` (`PredictionRestorationPolicy`, `HitPolicy.FIRST`):
    - 入力: `PredictionRestorationInput(isKanaMode, isComposingOrStreaming, isPasswordOrBypassedField, textBeforeCursor, hasMatchingCheckpoint, checkpointIsFullyReranked, hasTrailingPredictablePhrase)`
    - 出力: `PredictionRestorationAction`（`SHOW_EMPTY_STATUS`, `RESTORE_CHECKPOINT_SINGLE_PASS`, `RESTORE_CHECKPOINT_AND_COMPLETE_SLM`, `RECOMPUTE_FROM_SURROUNDING_CONTEXT`）
- **BC-7 (`context/ime/domain` & `VoiceInputMethodService`)**:
  - `VoiceTouchInputArbitrator`:
    - `PredictionCheckpoint`（`MAX_PREDICTION_CHECKPOINTS = 16`, `MAX_CHECKPOINT_CONTEXT_KEY_LEN = 128`）の有界スタック管理。
    - `recordOrUpdatePredictionCheckpoint`, `snapshotDisplayedCandidatesToCheckpoint`, `attachRollbackTokenToCheckpoint`, `findPredictionCheckpoint`, `reconcilePredictionCheckpointsAfterDelete`。
  - `VoiceInputMethodService`:
    - 候補確定・Enter確定・予測チップタップ時に表示候補スナップショットと `TransitionRollbackToken` をチェックポイントへ保存。
    - 削除時（`DELETED_SURROUNDING_TEXT`, `DELETED_FROM_KEYBOARD_COMPOSING_NOW_EMPTY`）および選択更新時（`onUpdateSelection`）に DMN `PredictionRestorationPolicy` に従ってチェックポイント復元／学習ロールバック／文脈再予測を実行。

### 4.2 状態不変条件と Alloy 6 形式検証
[`app/src/test/resources/alloy/prediction_checkpoint_restoration.als`](app/src/test/resources/alloy/prediction_checkpoint_restoration.als) にて以下を SAT4J で全数検証する：
1. `RestorationTableIsTotal`: `PredictionRestorationPolicy` があらゆる入力組み合わせに対して一意の結論に解決されること。
2. `NeverRestoresInPasswordOrNonKanaOrComposing`: パスワード欄・非かなモード・未確定入力中は常に `SHOW_EMPTY_STATUS` となること。
3. `CheckpointMatchAlwaysRestoresPredictions`: かなモード・非入力中・非パスワード欄で一致チェックポイントが存在すれば、必ず `RESTORE_CHECKPOINT_SINGLE_PASS` または `RESTORE_CHECKPOINT_AND_COMPLETE_SLM` を選択すること。
4. `DeleteSelectedPredictionRestoresExactCandidatesAndRevertsHistory`: 「確定 → 予測候補選択 → 削除による復帰」トレースにおいて、復帰後の表示候補が確定直後の予測候補一覧と完全一致し、かつ撤回された予測候補の学習履歴カウントが元に戻ること。
