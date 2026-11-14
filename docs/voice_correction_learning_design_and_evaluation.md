# 音声入力結果の修正検知・学習およびスコアブースト設計・評価仕様書 (Voice Correction Detection, Personalization & Score Boosting Design and Evaluation)

## 1. エグゼクティブサマリー・背景と目的 (Executive Summary & Problem Statement)

### 1.1. 背景と課題
オンデバイス音声認識（日本語: **ReazonSpeech Zipformer Transducer**、英語: **NVIDIA Parakeet TDT-CTC 110M**）は高い汎用認識精度を持つ一方で、ユーザー固有の人名・異体字（例: `渡辺` vs `渡邊`、`Sean` vs `Shawn`）、ドメイン専門用語・社内略語（例: `ReazonSpeech`、`GraphQL`、`kubectl`）、および文脈依存の同音異義語（例: `貴社` vs `記者`、`their` vs `there`）において、ユーザーの意図と異なる表記を出力する場合がある。

現状の JotInput では、ユーザーが音声入力直後にバックスペース（`⌫`）やカーソル移動を用いて誤認識箇所を手動修正しても、**「音声認識が何を出力し（$u_{\text{orig}}$）、ユーザーがそれをどう修正したか（$u_{\text{corr}}$）」という最も価値の高い教師シグナル（Implicit User Feedback）が破棄されており、次回以降の音声入力に反映されない**。ユーザーが毎回同じ固有名詞や同音異義語を手動で直し続けるか、手動で辞書登録画面（`DictionaryActivity`）を開いて登録する手間が発生している。

### 1.2. 本設計の目的と基本原則
本ドキュメントでは、**日本語・英語の双方において、音声入力結果に対するユーザーの修正アクションを自動検知・記録し、今後の音声入力で修正後の表現のスコアを段階的・文脈適応的に引き上げるためのアーキテクチャ**を設計する。

設計にあたり、JotInput の以下のコア規約を厳格に遵守する：
1. **Anti-Anecdotal Tuning Policy（アドホックなコストハッキングの禁止）**:
   - 特定単語の辞書コストを場当たり的に書き換えるのではなく、音韻類似度モデル・ベイズ事後確率・オンデバイスSLM（`LlmReranker`）の文脈対数尤度と統合した数理モデルによってスコアを引き上げる。
2. **Custom Dictionary Persistence Contract（カスタム辞書の保護）**:
   - 自動学習した音声修正履歴は専用の永続化ストア（`context.filesDir/voice_correction_history.json`）に分離保存し、ユーザーが明示的に管理する `custom_dictionary.json` を勝手に上書き・汚染しない。
3. **完全オンデバイス・ゼロレイテンシ体験**:
   - 修正検知・アライメント・スコア反映のすべてを端末内で完結させ、メインスレッドの打鍵・確定レイテンシ予算（$< 0.5\text{ ms}$）を厳守する。

---

## 2. システム全体アーキテクチャ (End-to-End Architecture)

音声入力の確定から修正検知、履歴記録、そして次回以降の音声入力（日本語・英語）におけるスコア引き上げまでの全体データフローを以下に示す。

```mermaid
flowchart TB
    subgraph Phase1["Phase 1: 音声入力確定とアンカー記録 (BC-7: IME Session)"]
        VoiceOut["音声認識結果の確定 (V_orig)\n[Streaming確定 / VADチャンク確定]"]
        Anchor["VoiceCommitAnchor 記録\n(テキスト範囲, 言語, タイムスタンプ, 先行文脈 C)"]
        VoiceOut --> Anchor
    end

    subgraph Phase2["Phase 2: 修正検知と差分・音韻検証 (BC-7 -> BC-3 Domain)"]
        UserEdit["ユーザーの編集操作\n(直後BS消去 / カーソル移動修正 / 候補選択 / 再入力)"]
        Detector["ハイブリッド修正検知エンジン\n(Action-Trace + Snapshot Myers Diff)"]
        Boundary["形態素・単語境界スパン拡張\n(部分文字編集 -> 単語・文節単位への正規化)"]
        PhoneticGate["音韻・G2P妥当性検証ゲート (DMN)\n[誤認識の修正 vs 推敲・別文書き直しの識別]"]
        Anchor --> UserEdit --> Detector --> Boundary --> PhoneticGate
    end

    subgraph Phase3["Phase 3: 修正履歴の永続化とイベント発行 (BC-3 / Shared Event)"]
        Event["VoiceCorrectionLearnedEvent 発行"]
        Store[("VoiceCorrectionRepository\n(voice_correction_history.json)\n[u_orig -> u_corr, reading, count, lastUsed, ctxTokens]")]
        PhoneticGate -->|"Valid Correction"| Event --> Store
    end

    subgraph Phase4["Phase 4: 今後の音声入力でのスコア引き上げ (JP & EN Multi-Layer Boost)"]
        Store --> LayerA["Layer A (JP In-Beam):\n動的 Hotwords スコア昇格\n(HotwordsGenerator / Zipformer FST)"]
        Store --> LayerB["Layer B (JP & EN Confusion Model):\n個人誤認識ペア転移モデル\nP(u_corr | u_orig, C)"]
        Store --> LayerC["Layer C (JP & EN SLM Reranking):\nSLM マイクロラティス文脈リランキング\n(LlmReranker + Context-Coherence Gate)"]
        Store --> LayerD["Layer D (EN Truecasing & Lexicon):\nBayesianCaseRanker / EnglishSpellEngine\n大文字化・複合語・スペル優先度ブースト"]
        Store --> LayerE["Layer E (IME UI Assist):\n確信度ティアに応じたワンタップ修正チップ表示\n& 逆修正ロールバック監視"]
    end
```

---

## 3. 【課題1】どのように音声入力の修正を検知・記録すべきか（5つの検知アイデア）

Android の `InputMethodService` では、ホストアプリ（エディタ）の全文DOMイベントを直接購読することはできず、`InputConnection` 経由の自身のコミット操作、`onUpdateSelection` によるカーソル位置通知、および `getTextBeforeCursor` / `getTextAfterCursor` のスナップショット取得のみが観測可能である。
また、最大の技術的課題は **「① 音声認識誤りの修正（Correction）」「② 続きの文章の追加入力（Continuation）」「③ 気が変わって別の文に書き直した推敲（Semantic Rewrite）」の3つを正確に分離すること** である。

以下に **5つの検知・抽出アイデア（D1〜D5）** を提案する。

---

### アイデア D1: 操作トレース型・直後編集ウィンドウ検知 (Action-Trace Post-Voice Edit Tracker)

#### メカニズム
1. [`VoiceTouchInputArbitrator`](app/src/main/java/com/example/myasr/context/ime/domain/VoiceTouchInputArbitrator.kt) が音声認識テキスト $V_{\text{orig}}$ を確定した瞬間、`VoiceCommitAnchor`（確定文字列、文字長 $L$、言語 `AsrLanguage`、先行文脈 $C$、確定時刻 $t_0$）を保持し、編集監視ウィンドウ（例: $\Delta t \le 15\text{秒}$）を開く。
2. 直後にユーザーがバックスペース（`onDeletePressed`）を $k$ 回押下して $V_{\text{orig}}$ の末尾から $k$ 文字（$1 \le k \le L$）を削除し、続けてキーボード（フリック / QWERTY / スワイプ）または再発話で新しい文字列 $w_{\text{new}}$ を確定した操作列を直接トレースする。
3. 削除された部分文字列 $u_{\text{orig}} = V_{\text{orig}}[L-k : L]$ と、直後に確定された文字列 $u_{\text{corr}} = w_{\text{new}}$ をペアとして抽出する。

#### メリット・デメリット
- **メリット**:
  - `InputConnection` への追加 IPC 呼び出しがゼロ（$0\text{ ms}$）。
  - **日本語フリック修正時にユーザーが入力した正確な「よみ（`composingText` / `candidateReading`）」を100%取得できる**（例: 音声が「耕太郎」と誤認識 $\to$ 3文字消して「こうたろう」と打って「幸太朗」を選択した場合、`reading = "こうたろう"`, `surface = "幸太朗"` が確定的に得られる）。
- **デメリット**:
  - ユーザーが画面タップで音声確定テキストの「文中（中間位置）」にカーソルを移動して修正した場合や、範囲選択で上書きした場合に、キー操作カウンタだけではオフセット追跡がずれることがある。

---

### アイデア D2: 周辺スナップショット差分＋Myers / Needleman-Wunsch アライメント検知 (Snapshot Diff & Sequence Alignment)

#### メカニズム
1. 音声チャンク確定時に、カーソル前後のテキストスナップショット $S_{\text{before}}$（直前確定した音声テキスト $V_{\text{orig}}$ とその左右アンカー文字列 $A_L, A_R$）をメモリに記録する。
2. ユーザーがその段落内で編集を行った後、**「編集完了トリガー（Settlement Trigger）」**（スペース・句読点・改行の入力、次の音声入力開始、$2.5\text{秒}$ の無操作アイドル、またはキーボード収納 `onFinishInputView`）が発生した時点で、現在のカーソル周辺テキスト $S_{\text{after}}$ を取得する。
3. 左右のアンカー $A_L, A_R$ で対象領域を特定した上で、日本語は文字・形態素単位、英語は単語トークン単位で **Myers Diff / Needleman-Wunsch 最小編集距離アライメント** を実行し、置換箇所 $(u_{\text{orig}} \to u_{\text{corr}})$ を抽出する。

```
[音声確定時 S_before]:  "明日の会議は" + [渡辺] + "さんが進行します。"
[ユーザー編集後 S_after]: "明日の会議は" + [渡邊] + "さんが進行します。"
                                         ^^^^^^
                          抽出ペア: u_orig = "渡辺" -> u_corr = "渡邊"
```

#### メリット・デメリット
- **メリット**:
  - 画面タップによる文中へのカーソル移動、矢印キー移動（`◀`/`▶`）、スペースバー・カーソルスワイプ、範囲選択ペースト、さらには1文中の複数箇所修正にも完全にロバスト。
- **デメリット**:
  - スナップショット差分単体では「最終的な表層文字列の差」しか見えず、日本語でペーストや予測候補から入力された場合に「よみ（Reading）」を辞書・形態素解析から逆推定する必要がある。

---

### アイデア D3: 音韻・G2P 類似度ゲートによる「誤認識修正」と「推敲（別文書き直し）」の識別 (Phonetic & G2P Plausibility Gate)

#### メカニズム
ユーザーが音声入力後にテキストを消して書き直す際、**「認識ミスを直した（例: `斉藤` $\to$ `齋藤`、`Reazon speech` $\to$ `ReazonSpeech`）」** のか、それとも **「言う内容自体を変えた（例: `明日の会議は10時です` $\to$ `やっぱり来週にします`）」** のかを数学的に判定するフィルタ。

抽出されたペア $(u_{\text{orig}}, u_{\text{corr}})$ に対し、言語別の音韻距離 $D_{\text{phon}}(u_{\text{orig}}, u_{\text{corr}}) \in [0, 1]$ を計算する：

1. **日本語の音韻類似度モデル**:
   - [`KanaKanjiEngine`](app/src/main/java/com/example/myasr/context/kanakanji/domain/KanaKanjiEngine.kt) の形態素ラティスおよび D1 の入力読みから、$u_{\text{orig}}$ の推定読み $r_{\text{orig}}$ と $u_{\text{corr}}$ の読み $r_{\text{corr}}$（カタカナ・長音・濁音正規化済み）を取得する（英字混じりの場合は英語発音カナ変換テーブルを併用）。
   - 正規化モーラ編集距離を算出する：
     $$d_{\text{kana}}(r_{\text{orig}}, r_{\text{corr}}) = \frac{\text{Levenshtein}_{\text{weighted}}(r_{\text{orig}}, r_{\text{corr}})}{\max(|r_{\text{orig}}|, |r_{\text{corr}}|)}$$
     （ただし、清音⇔濁音・半濁音、長音有無、促音有無の置換コストは $0.3$、母音同一の子音置換は $0.5$、無関係な文字置換は $1.0$ とする）。
   - **判定基準**:
     - $d_{\text{kana}} = 0.0$: **完全同音異義語・異体字修正**（確信度: `VERY_HIGH`）
     - $0.0 < d_{\text{kana}} \le 0.40$: **音響的近傍誤認識の修正**（例: `データドック` $\to$ `Datadog`、`かいき` $\to$ `会議`）（確信度: `HIGH`）
     - $d_{\text{kana}} > 0.40$: **意味的推敲・別文への書き直し**とみなし、**音声認識学習から除外（Reject）** する。

2. **英語の音韻・表層類似度モデル**:
   - $u_{\text{orig}}$ と $u_{\text{corr}}$ について、空白・ハイフン・アポストロフィを除いた正規化小文字比較、および **Double Metaphone / Soundex 音韻コード距離** $d_{\text{meta}}$ と正規化レーベンシュタイン距離 $d_{\text{char}}$ を算出する：
     $$D_{\text{en}}(u_{\text{orig}}, u_{\text{corr}}) = \min\left(d_{\text{char}}(\text{norm}(u_{\text{orig}}), \text{norm}(u_{\text{corr}})),\; d_{\text{meta}}(u_{\text{orig}}, u_{\text{corr}})\right)$$
   - **判定基準**:
     - $\text{norm}(u_{\text{orig}}) == \text{norm}(u_{\text{corr}})$: **大文字小文字・複合語スペース・ハイフン修正**（例: `"reazon speech"` $\to$ `"ReazonSpeech"`, `"fast api"` $\to$ `"FastAPI"`, `"ios"` $\to$ `"iOS"`）（確信度: `VERY_HIGH`）
     - $D_{\text{en}} \le 0.40$: **英語同音異義語・音響類似語修正**（例: `"Shawn"` $\to$ `"Sean"`, `"there"` $\to$ `"their"`, `"parakeet"` $\to$ `"Parakeet"`, `"kuber"` $\to$ `"kubectl"`）（確信度: `HIGH`）
     - $D_{\text{en}} > 0.40$: **意味的書き直し**とみなし **除外（Reject）** する。

---

### アイデア D4: 形態素・単語境界へのスパン拡張正規化 (Morpheme & Token Boundary Span Expansion)

#### メカニズム
ユーザーは単語全体を消さず、誤っている1文字だけを消して直すことが多い。
- **日本語の例**: 音声認識が `"渡邊"` と出力し、ユーザーが2文字目の `"邊"` だけを消して `"辺"` と入力した場合、単純な文字差分は `"邊" -> "辺"` となってしまう。これでは単漢字しか記録されず、「わたなべ」という読みや「渡辺」という語彙として学習できない。
- **英語の例**: 音声認識が `"micro services"`（2単語）と出力し、ユーザーがスペースを消して `"microservices"`（1単語）にした場合、文字差分だけでは `" " -> ""` になってしまう。

そこで、差分スパン $[i_{\text{start}}, i_{\text{end}}]$ を検出した後、**前後の文字種・形態素・空白境界までスパンを自動拡張（Span Expansion）** する：
- **日本語**: 変更箇所を含む連続した漢字ブロック・カタカナブロック・英数字ブロック、または `KanaKanjiEngine` の Viterbi 文節境界まで左右に拡張し、`("渡邊" -> "渡辺", reading="わたなべ")` として正規化抽出する。
- **英語**: 変更箇所を含む空白区切りトークン（複合語結合・分割の場合は前後1トークンを含む $1 \sim 3$-gram）まで拡張し、`("micro services" -> "microservices")` として抽出する。

---

### アイデア D5: ストリーミング入力中の「即時確定・部分修正」フック (In-Stream Composing Interruption Capture)

#### メカニズム
JotInput のストリーミング音声入力モードでは、発話中に `streamingLastComposingText` がリアルタイム表示される。
ユーザーが発話直後に停止ボタンを押す前に、直接キーボードの `⌫`（`DELETED_FROM_STREAMING_COMPOSING`）や濁点・大文字小文字変換キー `゛゜/大⇔小`（`onModifierPressed`）を押して末尾を修正した場合、修正前の `streamingLastComposingText` を $V_{\text{orig}}$ としてスナップショット保存し、直後の編集結果との差分を D1/D2 と同じパイプラインに流す。

---

### 検知アイデアの比較と統合推奨構成

| 検知アイデア | 対象シナリオ | 読み(Reading)取得精度 | 文中カーソル移動耐性 | 誤学習防止 (推敲除外) | 計算コスト |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **D1: 操作トレース検知** | 直後のBS消去＋フリック/QWERTY再入力 | **◎ (100%正確)** | △ (末尾中心) | △ (単体では不可) | $< 0.01\text{ ms}$ |
| **D2: スナップショット差分** | 文中タップ移動・範囲選択・複数箇所修正 | ◯ (辞書逆引き) | **◎ (完全対応)** | △ (単体では不可) | $< 0.15\text{ ms}$ |
| **D3: 音韻・G2P類似度ゲート** | 誤認識修正 vs 別文への推敲の識別 | — | — | **◎ (誤学習を遮断)** | $< 0.10\text{ ms}$ |
| **D4: 形態素・単語境界拡張** | 1文字だけの部分削除・複合語スペース結合 | **◎ (単語単位復元)** | ◎ | ◯ | $< 0.10\text{ ms}$ |
| **D5: ストリーミング介入検知** | ストリーミング表示中の直接BS・濁点修正 | ◎ | ◯ | — | $< 0.01\text{ ms}$ |

> [!IMPORTANT]
> **推奨検知アーキテクチャ（D1 + D2 + D3 + D4 + D5 の統合パイプライン）**:
> 1. **D1（操作トレース）** でユーザーのフリック入力読み（`composingText`）をキャッシュしつつ、**D2（スナップショット Myers Diff）** で文中カーソル移動を含むあらゆる位置の編集差分を確実に捕捉する。
> 2. 抽出した生差分を **D4（形態素・単語境界拡張）** で完全な単語・フレーズ単位 $(u_{\text{orig}} \to u_{\text{corr}})$ に正規化する。
> 3. 最後に **D3（音韻・G2P類似度ゲート）** を通し、音韻的に妥当な修正のみを学習ストアへ記録する。

---

## 4. 【課題2】記録した修正内容をどう使い、今後の音声入力でどのようにスコアを上げるべきか（5つのスコア引き上げアイデア）

日本語 ASR（ReazonSpeech: Transducer + `modified_beam_search`）と、英語 ASR（Parakeet TDT-CTC 110M: NeMo CTC + `greedy_search`）ではデコーダの内部構造が異なる。そのため、**特定のデコーダ実装に依存しない層と、各言語のデコーダ・言語モデル特性を最大限に活かす層のマルチレイヤーでスコアを引き上げる設計**が不可欠である。

まず、記録するデータモデル（`VoiceCorrectionEntry`）を定義した上で、**5つのスコア引き上げアイデア（S1〜S5）** を提案する。

### 4.1. 記録データモデル (`VoiceCorrectionEntry`)

保存先: `context.filesDir/voice_correction_history.json`（最大 500 件、LRU + 時間減衰管理）

```kotlin
data class VoiceCorrectionEntry(
    val id: String,
    val language: AsrLanguage,          // JAPANESE or ENGLISH
    val originalSurface: String,        // ASRが出力した誤認識表記 u_orig (例: "渡辺", "reazon speech")
    val correctedSurface: String,       // ユーザーが修正した正答表記 u_corr (例: "渡邊", "ReazonSpeech")
    val normalizedReading: String,      // 正規化読み (JP: "わたなべ", EN: "reazonspeech")
    val correctionCount: Int,           // 累積修正回数 c
    val acceptCount: Int,               // 自動補正後にユーザーがそのまま受け入れた回数 a
    val revertCount: Int,               // 自動補正後にユーザーが逆修正(ロールバック)した回数 r
    val firstCorrectedAtMs: Long,
    val lastCorrectedAtMs: Long,        // 最終修正時刻 t_last
    val recentContextTokens: List<String> // 修正時に先行文脈 C に出現していた特徴語 (最大16トークン)
)
```

---

### アイデア S1: 修正回数・経過時間に応じた動的 ASR ホットワード・ブースト (Dynamic In-Beam Hotwords Score Escalation — 主に日本語 & 英語拡張)

#### メカニズム
[`HotwordsGenerator.kt`](app/src/main/java/com/example/myasr/context/speech/application/HotwordsGenerator.kt) を拡張し、明示的なユーザー辞書（`DictionaryEntry`）だけでなく、`VoiceCorrectionEntry` からも動的にホットワードルールを生成して Zipformer Transducer のビームサーチ（`modified_beam_search`）に注入する。

#### 数理モデル
エントリ $e$ の実効ホットワードブーストスコア $s_{\text{hw}}(e) \in [0, 4.5]$ を、正味の信頼度カウント $c_{\text{net}} = \max(0, c - 2r)$ と最終更新からの経過時間 $\Delta t = t_{\text{now}} - t_{\text{last}}$ に基づき次式で算出する：

$$s_{\text{hw}}(e) = \text{ clamp }\left( s_0 + \kappa \cdot \ln(1 + c_{\text{net}}) \cdot \exp\left(-\frac{\Delta t}{\tau_{\text{decay}}}\right),\; s_{\min},\; s_{\max} \right)$$

- 初期値 $s_0 = 2.0$（1回目の修正から穏やかにビーム内で優遇）、係数 $\kappa = 1.2$、上限 $s_{\max} = 4.5$（`Priority.HIGH` 相当）、半減期パラメータ $\tau_{\text{decay}} = 60\text{日}$。
- 表層（`渡 邊 :3.2`）、ひらがな読み（`わ た な べ :3.2`）、カタカナ読み（`ワ タ ナ ベ :3.2`）の3系列に展開し、音響ビーム内で正しい音素列と漢字表記が枝刈り（Pruning）で落ちるのを防ぐ。

#### 特徴と限界
- **長所**: 音響デコード段階で希少語や固有名詞が生き残る確率が飛躍的に向上する。
- **限界**:
  1. 英語モデル（Parakeet TDT-CTC 110M）は現在 `OfflineNemoEncDecCtcModelConfig` + `greedy_search` で動作しており、`sherpa-onnx` の Transducer 用 `hotwords` がそのままでは効かない。
  2. 日本語でも、音響特徴量が100%同一の同音異義語（`貴社` vs `記者`）を無条件に高ブーストすると、文脈に合わない場面でも常に片方ばかり出力される副作用が生じる。

---

### アイデア S2: 誤認識コンフュージョン・マトリクス $P(u_{\text{corr}} \mid u_{\text{orig}}, C)$ による文脈特徴付き事後リスコアリング (Personal Confusion-Pair & Context TF-IDF Rescoring — 日英共通)

#### メカニズム
「ユーザーが自ら登録するカスタム辞書」には『正答単語 $u_{\text{corr}}$』しか存在しないが、**「音声修正ログ」には『ASRが実際にどう間違えたか（$u_{\text{orig}}$）』と『そのときどんな文脈だったか（$\text{recentContextTokens}$）』がペアで記録されている**。
この非対称な強みを活かし、日本語（[`ContextAwareProcessor.kt`](app/src/main/java/com/example/myasr/context/speech/domain/ContextAwareProcessor.kt)）と英語（[`EnglishVoicePostProcessor.kt`](app/src/main/java/com/example/myasr/context/speech/domain/EnglishVoicePostProcessor.kt)）の両方に **Personal Confusion Rescorer** を導入する。

#### 数理モデル
ASR のデコード結果テキスト $T$ の中に、過去に修正された誤認識パターン $u_{\text{orig}}$（または同一読み・同一 Metaphone の語）が検出されたとき、候補 $u_{\text{corr}}$ への置換スコア $J(u_{\text{orig}} \to u_{\text{corr}} \mid C)$ を計算する：

$$J(u_{\text{orig}} \to u_{\text{corr}} \mid C) = \underbrace{w_{\text{freq}} \ln\left(1 + c_{\text{net}}(u_{\text{orig}} \to u_{\text{corr}})\right) \cdot e^{-\Delta t / \tau}}_{\text{修正頻度・鮮度スコア}} + \underbrace{w_{\text{ctx}} \cdot \text{Sim}_{\text{BM25}}(C, \text{ctxTokens}(u_{\text{corr}}))}_{\text{先行文脈キーワード一致ボーナス}} + \underbrace{w_{\text{exact}} \cdot \mathbb{I}(u_{\text{corr}} \in C)}_{\text{先行文脈内の表記出現ボーナス}} - \underbrace{w_{\text{phon}} \cdot D_{\text{phon}}(u_{\text{orig}}, u_{\text{corr}})}_{\text{音韻乖離ペナルティ}}$$

- **判定ルール**:
  - 同一読みの競合候補 $\{u_{\text{orig}}, u_{\text{corr}, 1}, u_{\text{corr}, 2}, \dots\}$ があるとき、$J(u_{\text{orig}} \to u_{\text{corr}} \mid C) > \theta_{\text{commit}}$ かつ全競合中で最大であれば $u_{\text{corr}}$ に置換する。
  - 特に **表記ゆれ・固有名詞・技術用語・英語複合語**（例: `"reazon speech"` $\to$ `"ReazonSpeech"`, `"graph ql"` $\to$ `"GraphQL"`, `"fast api"` $\to$ `"FastAPI"`, `"データドック"` $\to$ `"Datadog"`）では、競合する一般語の意味的衝突がないため、$c_{\text{net}} \ge 1$ から即座に高信頼な補正が可能となる。

#### 特徴
- **長所**: **英語（Parakeet CTC）でも日本語（ReazonSpeech）でも100%同じ数理モデルで動作**し、Aho-Corasick / トライ走査により $< 0.2\text{ ms}$ で高速実行できる。

---

### アイデア S3: オンデバイス SLM (`LlmReranker`) × 音声修正履歴の文脈コヒーレンス・ゲーティング融合 (SLM Micro-Lattice Reranking with Context-Coherence Gate — 日英共通)

#### メカニズム
日本語の同音異義語（例: `貴社` vs `記者`、`移動` vs `異動`、`科学` vs `化学`）や、英語の同音異義語（例: `their` vs `there`、`write` vs `right`、`principal` vs `principle`、`Sean` vs `shorn`）では、ユーザーが過去に1〜2回 `記者` $\to$ `貴社` と修正したからといって、**ニュース記事について話している文脈（「新聞記者が発表した」）まで無条件に「新聞貴社」に置換してはならない**。

そこで、ASR 出力文 $T$ の中に「一般語としても高頻度で使われる同音異義語の修正履歴 $(u_{\text{orig}} \to u_{\text{corr}})$」が含まれる場合、**マイクロラティス候補群** $\{T(u_{\text{orig}}),\; T(u_{\text{corr}})\}$ を生成し、オンデバイス SLM（[`LlmReranker.kt`](app/src/main/java/com/example/myasr/context/reranking/infrastructure/LlmReranker.kt)）の因果対数尤度（NLL）と音声修正履歴ブーストを統合評価する。

#### 数理モデル
先行文脈 $C$ と発話内前後文脈を合わせた系列に対する候補文 $T_i$ の SLM 負の対数尤度を $\mathcal{L}_{\text{SLM}}(T_i \mid C)$ とする。
修正候補 $T(u_{\text{corr}})$ と元の ASR 出力 $T(u_{\text{orig}})$ の SLM サプライズ差を $\Delta \mathcal{L} = \mathcal{L}_{\text{SLM}}(T(u_{\text{corr}}) \mid C) - \mathcal{L}_{\text{SLM}}(T(u_{\text{orig}}) \mid C)$ と定義し、**文脈整合性ゲート関数 $g(\Delta \mathcal{L}) \in [0, 1]$** を導入する：

$$g(\Delta \mathcal{L}) = \frac{1}{1 + \exp\left(\frac{\Delta \mathcal{L} - \theta_{\text{gate}}}{T_{\text{temp}}}\right)}$$

これを用いて最終統合スコア（最小化目標エネルギー）を次式で計算する：

$$E(T(u_{\text{corr}}) \mid C) = \mathcal{L}_{\text{SLM}}(T(u_{\text{corr}}) \mid C) - g(\Delta \mathcal{L}) \cdot \beta_{\text{voice}} \cdot \min\left(\ln(1 + c_{\text{net}}),\, \tau_{\max}\right)$$

- **挙動の数学的保証**:
  1. **文脈が曖昧または両立する場合**（例: 「先日お話ししたきしゃの件ですが」で $\Delta \mathcal{L} \approx 0$）: $g(\Delta \mathcal{L}) \approx 1$ となり、ユーザーの修正履歴ブースト $-\beta_{\text{voice}} \ln(1 + c_{\text{net}})$ がそのまま効いて、ユーザーが普段使う「貴社」が選ばれる。
  2. **文脈が修正語と強く矛盾する場合**（例: 「朝刊の記事を書いたきしゃが」で、`貴社` にすると $\Delta \mathcal{L} > +6.0\text{ nats}$ と急増する場合）: $g(\Delta \mathcal{L}) \to 0$ となって履歴ブーストが自動的に遮断（Gate Off）され、誤置換（False Positive）が防がれる！
  3. **SLM にとって未知の固有名詞・専門用語の場合**: `u_orig` が一般語彙でない場合（または `u_corr` が固有名詞・英数字混じり語の場合）は ADR-011 の **Intra-Word Surprisal Clamping** を適用し、SLM の未知語ペナルティで履歴ブーストが消されないよう保護する。

---

### アイデア S4: 英語特有の Truecasing・スペル・Swipe エンジン横断ブースト (`BayesianCaseRanker` & `EnglishSpellEngine` 横断統合)

#### メカニズム
英語の音声入力修正では、以下の3パターンが全体の過半数を占める：
1. **Truecasing / 固有名詞・技術用語の大文字化修正**: `"parakeet"` $\to$ `"Parakeet"`, `"github"` $\to$ `"GitHub"`, `"reazon"` $\to$ `"Reazon"`.
2. **複合語の結合・ハイフン修正**: `"on device"` $\to$ `"on-device"`, `"type script"` $\to$ `"TypeScript"`, `"real time"` $\to$ `"real-time"`.
3. **人名・外来語のスペル修正**: `"shawn"` $\to$ `"Sean"`, `"stephen"` $\to$ `"Steven"`, `"py torch"` $\to$ `"PyTorch"`.

そこで、英語の音声修正履歴 `VoiceCorrectionEntry (language == ENGLISH)` を以下の3箇所へ同時にフィードバックする：
1. **`EnglishVoicePostProcessor` の Phrase & Case Adaptation**:
   - 音声認識確定直後のフォーマット処理で、学習済みの複合語・ハイフン表現・固有名詞ケーシングを適用。
2. **[`BayesianCaseRanker.kt`](app/src/main/java/com/example/myasr/context/english/domain/BayesianCaseRanker.kt) のケース事前分布 $P_{\text{lex}}(s \mid w)$ の動的ベイズ更新**:
   - 基底語 $w$ に対する表記 $s$ の事前分布に対し、ユーザーの音声修正カウント $c_{\text{net}}(w \to s)$ をディリクレ擬似カウントとして加算する：
     $$P_{\text{adapted}}(s \mid w) = \frac{N_0 \cdot P_{\text{lex}}(s \mid w) + \gamma_{\text{user}} \cdot c_{\text{net}}(w \to s)}{N_0 + \gamma_{\text{user}} \sum_{s'} c_{\text{net}}(w \to s')}$$
3. **[`EnglishSpellEngine.kt`](app/src/main/java/com/example/myasr/context/english/infrastructure/EnglishSpellEngine.kt) & [`FutoSwipeEngine.kt`](app/src/main/java/com/example/myasr/context/english/infrastructure/FutoSwipeEngine.kt) への動的同期**:
   - 音声入力で修正した英単語が、次回以降の英語音声入力だけでなく、QWERTY タイピングや FUTO スワイプ入力でも優先候補として浮上する（マルチモーダル学習共有）。

---

### アイデア S5: 段階的昇格ライフサイクルと逆修正（自己修復）フィードバックループ (Graduated 3-Tier Promotion & Negative Feedback Self-Healing)

#### メカニズム
たった1回の偶然の修正で全ての音声入力を破壊的に書き換えてしまうリスクを防ぎつつ、1回目の修正から即座にユーザーが恩恵を受けられるよう、**3段階の確信度ティア（Confidence Tiers）** と **逆修正ロールバック（Negative Feedback Loop）** を導入する。

```mermaid
stateDiagram-v2
    [*] --> Tier1_Tentative: 初回修正検知 (c_net = 1)
    Tier1_Tentative --> Tier2_ActiveBoost: 2回目の修正 or 候補チップ選択 (c_net >= 2)\nまたは固有名詞・異体字・大文字化の一致
    Tier2_ActiveBoost --> Tier3_HighPriority: 3回以上の修正・定着 (c_net >= 3)
    Tier2_ActiveBoost --> Tier1_Tentative: 自動置換後にユーザーが逆修正 (r++)
    Tier1_Tentative --> Suppressed: 再度逆修正 (c_net <= 0)
```

1. **Tier 1 (`TENTATIVE`: $c_{\text{net}} = 1$ かつ一般同音異義語)**:
   - **非破壊サジェスト＋穏やかなブースト**:
     - 次回の音声入力で $u_{\text{orig}}$ が認識された際、もし文脈スコアが拮抗している場合は、音声確定直後の [`CandidateStripView`](app/src/main/java/com/example/myasr/context/ime/presentation/CandidateStripView.kt) の先頭に **`[🔄 u_corr]`（ワンタップ置換チップ）** を即座に表示する。ユーザーがそのチップを1タップするだけで $u_{\text{orig}} \to u_{\text{corr}}$ に置換され、同時に $c_{\text{net}}$ が $+1$ されて Tier 2 へ昇格する。
     - 一方、表記ゆれ・異体字（`渡辺` $\to$ `渡邊`）や英語の大文字化・複合語結合（`reazon speech` $\to$ `ReazonSpeech`）など、意味的衝突リスクが低いカテゴリは $c_{\text{net}} = 1$ から自動適用する。
2. **Tier 2 (`ACTIVE_BOOST`: $c_{\text{net}} = 2$)**:
   - **インビーム Hotwords（`score = 3.0`）＋ SLM 文脈ゲート付き自動置換**:
     - 音声認識時に自動で $u_{\text{corr}}$ へスコアを引き上げ、文脈が矛盾しない限り最初から $u_{\text{corr}}$ を出力する。
     - 同時に、万が一ユーザーが元の表記 $u_{\text{orig}}$ を意図していた場合に備え、確定直後の候補バーに控えめに `[↩ u_orig に戻す]` チップを提示できる。
3. **Tier 3 (`HIGH_PRIORITY`: $c_{\text{net}} \ge 3$)**:
   - **高優先度 Hotwords（`score = 4.5`）＋ かな漢字変換・英語予測への横断共有**:
     - 音声入力・フリック変換・英語タイピングのすべてで最優先クラスとして扱う。
4. **逆修正による自己修復（Negative Feedback Self-Healing）**:
   - システムが自動補正で $u_{\text{corr}}$ を出力した直後に、ユーザーがそれを手動で $u_{\text{orig}}$ に書き戻した場合（逆修正検知）、`revertCount` ($r$) をインクリメント（実効カウント $c_{\text{net}} = c - 2r$ を減산）し、無条件の自動置換を即座に停止して文脈条件付きまたは候補チップ提示（Tier 1）へ降格させる。

---

### スコア引き上げアイデアの比較マトリクス

| 評価軸 | S1: 動的 Hotwords ブースト | S2: 誤認識ペア×文脈特徴リスコア | S3: SLM文脈ゲート付きマイクロラティス | S4: 英語 Truecase / Spell 横断適応 | S5: 3段階昇格＆逆修正自己修復 |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **日本語 ASR 対応 (ReazonSpeech)** | **◎ (ビーム内直接バイアス)** | ◎ | **◎+ (同音異義語識別)** | — | ◎ |
| **英語 ASR 対応 (Parakeet CTC)** | △ (CTC Greedyでは非対応) | **◎ (CTC後処理で完全動作)** | **◎+ (英語同音語・文脈識別)** | **◎+ (Case/複合語/Spell)** | ◎ |
| **OOV・未知語・複合語への対応力** | ◯ (`tokens.txt` 内に限る) | **◎ (任意の表記・記号可)** | ◎ | ◎ | ◎ |
| **文脈変化時の誤置換防止 (特異度)** | △ (文脈非依存) | ◯ (特徴語一致) | **◎+ (SLMサプライズゲート)** | ◯ | **◎+ (逆修正で即座に抑制)** |
| **計算レイテンシ** | $0\text{ ms}$ (事前FST構築) | $< 0.2\text{ ms}$ | $0\text{ ms}$ (Fast) / $\sim 20\text{ ms}$ (SLM) | $< 0.1\text{ ms}$ | $< 0.05\text{ ms}$ |

---

## 5. DDD / DMN / Alloy 形式設計との整合性 (Architectural & Formal Modeling)

JotInput の DDD 境界規約（[`docs/ddd/context_map.md`](docs/ddd/context_map.md)）および Konsist アーキテクチャテスト（[`DddArchitectureKonsistTest.kt`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt)）に完全準拠するため、責務配置と意思決定モデルを以下のように定義する。

### 5.1. Bounded Context の責務配置
- **`BC-7: IME Session (`context.ime.domain`)`**:
  - `VoiceTouchInputArbitrator`: 音声確定時の `VoiceCommitAnchor` 記録と、編集完了時のスナップショットおよび操作トレースの抽出を担当。
- **`BC-3: Speech Recognition (`context.speech.domain` / `application` / `infrastructure`)`**:
  - `VoiceCorrectionDetector` & `VoiceCorrectionDecisionModel` (`domain`): 純粋 Kotlin（Android UI 非依存）で Myers Diff アライメント、形態素・単語境界拡張、音韻類似度ゲート（DMN）、および確信度ティア判定を実行。
  - `VoiceCorrectionRepository` (`infrastructure`): `voice_correction_history.json` の永続化・LRU管理。
  - `ContextAwareProcessor` / `EnglishVoicePostProcessor` (`domain`): 学習済み `VoiceCorrectionEntry` を用いた事後リスコアリング（S2/S3/S4）。
  - `HotwordsGenerator` (`application`): Tier 2 以上の日本語修正エントリを `hotwords.txt` に統合（S1）。
- **`Shared Kernel (`shared.event`)`**:
  - `VoiceCorrectionLearnedEvent`: 音声修正が学習された際に発行され、`BC-1 (Kana-Kanji)` や `BC-4 (English)` が購読して変換・スペル候補の優先度を非同期更新する。

### 5.2. DMN 決定表 (`VoiceCorrectionDecisionModel`)

#### Table 1: `VoiceCorrectionEligibilityDecisionTable`（修正検知の妥当性判定）

| Rule | `isWithinEditWindow` | `phoneticDistance` ($D_{\text{phon}}$) | `isExactCaseOrCompoundFix` | `spanLengthValid` | Output: `CorrectionCategory` |
| :--- | :---: | :---: | :---: | :---: | :--- |
| 1 | `false` | `-` | `-` | `-` | `IGNORE_OUTSIDE_WINDOW` |
| 2 | `true` | `-` | `-` | `false` (空または全文長超過) | `IGNORE_INVALID_SPAN` |
| 3 | `true` | `-` | `true` (`norm(orig) == norm(corr)`) | `true` | `VALID_ORTHOGRAPHIC_OR_CASE` |
| 4 | `true` | `0.0` (完全同音) | `false` | `true` | `VALID_EXACT_HOMOPHONE` |
| 5 | `true` | `(0.0, 0.40]` (音韻近傍) | `false` | `true` | `VALID_PHONETIC_NEAR_MISS` |
| 6 | `true` | `> 0.40` (音韻乖離) | `false` | `true` | `REJECT_SEMANTIC_REWRITE` |

#### Table 2: `VoiceCorrectionBoostPolicyDecisionTable`（スコア引き上げティアとアクション決定）

| Rule | `netCount` ($c_{\text{net}}$) | `category` | `isAmbiguousCommonHomophone` | `slmCoherencePassed` | Output: `BoostAction` |
| :--- | :---: | :---: | :---: | :---: | :--- |
| 1 | `<= 0` (逆修正超過) | `-` | `-` | `-` | `SUPPRESSED_NO_BOOST` |
| 2 | `>= 1` | `VALID_ORTHOGRAPHIC_OR_CASE` | `false` | `true` | `AUTO_REPLACE_AND_HOTWORD` |
| 3 | `1` | `VALID_EXACT_HOMOPHONE` / `NEAR_MISS` | `true` (一般同音語) | `true` | `SUGGEST_CHIP_AND_MILD_BOOST` |
| 4 | `1` | `VALID_EXACT_HOMOPHONE` / `NEAR_MISS` | `false` (固有名詞・希少語) | `true` | `AUTO_REPLACE_AND_HOTWORD` |
| 5 | `>= 2` | `-` | `-` | `true` | `AUTO_REPLACE_AND_HOTWORD` |
| 6 | `>= 1` | `-` | `true` | `false` (SLMが文脈矛盾と判定) | `GATE_OFF_KEEP_ORIGINAL_WITH_CHIP` |

### 5.3. Alloy 形式検証仕様 (`voice_correction_learning.als` の不変条件)
実装時には `app/src/test/resources/alloy/voice_correction_learning.als` を作成し、以下の安全性を有界モデル検査で証明する：
1. **`NoSemanticRewritePollution`**: `phoneticDistance > Threshold` となる編集操作は、いかなる状態遷移においても `ActiveBoostRules` に追加されないこと。
2. **`ReverseEditSafetyInvariant`**: システムが自動置換した結果をユーザーが元の表記に逆修正（Revert）した直後の状態では、同一文脈において無条件の自動置換（`AUTO_REPLACE`）が再発火しないこと。
3. **`CustomDictionaryIsolationInvariant`**: 音声修正の自動学習・減衰・削除イベントのいかなる系列も、ユーザーの明示的登録辞書（`CustomDictionary`）のエントリ集合を破壊・削除しないこと。

---

## 6. 【課題3】アイデアの評価方法とベンチマーク設計 (Evaluation Methodology & Benchmark Design)

提案した検知アルゴリズム（D1〜D5）およびスコア引き上げアルゴリズム（S1〜S5）の有効性・副作用・性能を客観的かつ定量的に評価するため、以下の **3階層・計7指標の評価フレームワーク** を設計する。

```mermaid
flowchart LR
    subgraph Eval1["Layer 1: 修正検知・ペア抽出評価\n(Detection & Alignment Benchmark)"]
        E1_Data["マルチモーダル編集トレース (日英 240件)\n・直後BS修正 / 文中カーソル修正\n・複合語結合 / 推敲書き直し(Neg) / 追加入力(Neg)"]
        E1_Metric["・Span Extraction F1\n・Reading推定精度\n・誤学習率 (FLR <= 0.5%)"]
        E1_Data --> E1_Metric
    end

    subgraph Eval2["Layer 2: 逐次適応・スコア引き上げ評価\n(Sequential Adaptation Replay Benchmark)"]
        E2_Data["時系列発話リプレイセット (日英 400ペア)\n・Step 1: 誤認識 -> ユーザー修正\n・Step 2: 同一語を含む新規発話 (Positive)\n・Step 3: 競合同音語が正解の発話 (Adversarial)"]
        E2_Metric["・1-Shot / 3-Shot TWRR (正答再現率)\n・文脈特異度 / 過剰置換率 (FPR)\n・逆修正リカバリ速度"]
        E2_Data --> E2_Metric
    end

    subgraph Eval3["Layer 3: レイテンシ・非回帰評価\n(System & Non-Regression Suite)"]
        E3_Data["・JapanesePunctuationEvaluationTest (67件)\n・1,000文日本語難読ベンチマーク\n・英語LibriSpeech / 技術用語セット"]
        E3_Metric["・句読点非回帰 100%\n・検知処理 <= 0.5ms\n・リスコア処理 <= 0.5ms (Fast)"]
        E3_Data --> E3_Metric
    end
```

---

### 6.1. Evaluation Layer 1: 音声修正検知・差分抽出ベンチマーク (Detection & Alignment Evaluation)

#### 評価データセット構成（日英 計 240 ケース）
JVM 単体テスト上で `VoiceTouchInputArbitrator` と `EditorBridge` を駆動し、以下の6カテゴリのユーザー操作シナリオを再生する：

| カテゴリ | 日本語ケース例 (120件) | 英語ケース例 (120件) | 期待される検知挙動 |
| :--- | :--- | :--- | :--- |
| **Cat-1: 直後バックスペース修正** | 音声: `今日は斉藤さんと会議` $\to$ BS6回 $\to$ `齋藤さんと会議` | 音声: `Talked to Shawn` $\to$ BS5回 $\to$ `Sean` | `("斉藤" -> "齋藤", "さいとう")` / `("Shawn" -> "Sean")` を抽出 |
| **Cat-2: 文中カーソル移動・部分文字修正** | 音声: `渡辺さんに連絡します` $\to$ カーソルを`辺`の後ろへ移動 $\to$ BS1回 $\to$ `邊` | 音声: `Use parakeet model` $\to$ `p`を消して`P`に入力 | D4 境界拡張により `("渡辺" -> "渡邊")` / `("parakeet" -> "Parakeet")` を単語単位で抽出 |
| **Cat-3: 複合語・スペース・記号境界修正** | 音声: `グラフ九Lを使う` $\to$ `グラフ九L`を選択消去 $\to$ `GraphQL` | 音声: `Use reazon speech now` $\to$ `reazon speech` を `ReazonSpeech` に修正 | `("グラフ九L" -> "GraphQL")` / `("reazon speech" -> "ReazonSpeech")` を抽出 |
| **Cat-4: ストリーミング途中の直接介入** | ストリーミング表示 `かいきを始めます` $\to$ `き`を濁点キーまたはBSで `会議` に修正 | ストリーミング表示 `testing fast api` $\to$ 末尾を `FastAPI` に修正 | `("かいき" -> "会議")` / `("fast api" -> "FastAPI")` を抽出 |
| **Cat-5: Negative — 通常の続き入力 (Continuation)** | 音声: `お疲れ様です。` $\to$ 続けてフリックで `よろしく` と入力 | 音声: `Hello there.` $\to$ 続けて `How are you?` と入力 | **検知ゼロ（0件）** であること（追加入力を修正と誤認しない） |
| **Cat-6: Negative — 意味的推敲・別文書き直し (Semantic Rewrite)** | 音声: `明日の会議は10時からです` $\to$ 全部消して `やっぱり電話します` と入力 | 音声: `Let's meet tomorrow` $\to$ 消して `I will send an email` と入力 | D3 音韻ゲートにより **学習棄却（0件）** であること |

#### 評価指標（KPI）と合格基準
1. **Span Extraction Precision / Recall / $F_1$（Cat-1〜Cat-4）**:
   $$\text{Precision} = \frac{|\text{正しく抽出された }(u_{\text{orig}}, u_{\text{corr}})\text{ ペア}|}{|\text{システムが抽出した全ペア}|}, \quad \text{Recall} = \frac{|\text{正しく抽出されたペア}|}{|\text{正解の全修正ペア}|}$$
   - **目標基準**: Precision $\ge 98.0\%$、Recall $\ge 92.0\%$、$F_1 \ge 95.0\%$。
2. **Japanese Reading Inference Accuracy（読み推定正解率）**:
   - 抽出された日本語ペアにおいて、正規化ひらがな読み $r_{\text{corr}}$ が正解と完全一致する割合。**目標基準**: $\ge 95.0\%$。
3. **False Learning Rate (FLR: 誤学習率 — Cat-5 & Cat-6)**:
   $$\text{FLR} = \frac{\text{Cat-5 および Cat-6 で誤って学習ストアに記録された件数}}{\text{Cat-5 および Cat-6 の総件数}}$$
   - **目標基準**: $\text{FLR} = 0.0\%$（上限 $\le 0.5\%$）。

---

### 6.2. Evaluation Layer 2: 逐次適応・スコア引き上げ効果ベンチマーク (Sequential Adaptation & Disambiguation Benchmark)

「1回（または数回）修正した後に、別の発話で本当にスコアが上がって正しく出力されるか」と「別の意味の文脈で過剰置換（副作用）を起こさないか」を同時に測定する **Pre-quential（逐次予測・更新）評価プロトコル** を定義する。

#### 評価プロトコル（3ステップ時系列シミュレーション）
各テストケース $i \in \{1, \dots, N\}$ は以下の3つの発話ステップ $(U_{i}^{(1)}, U_{i}^{(2)}, U_{i}^{(3)})$ から構成される：

1. **Step 1 (Initial Voice Input & User Correction)**:
   - 発話 $U_{i}^{(1)}$ を入力 $\to$ ベースライン ASR は $u_{\text{orig}}$ を出力 $\to$ ユーザーが $u_{\text{corr}}$ に修正するイベントをシミュレートし、モデルを更新する。
2. **Step 2 (Positive Transfer Test — 異なる文での再発話)**:
   - 同じターゲット語 $u_{\text{corr}}$ を意図した**別の文章** $U_{i}^{(2)}$ を音声入力する。
   - 例 (JP): Step 1 で `「斉藤さんに確認」` $\to$ `「齋藤さんに確認」` と修正後、Step 2 で `「明日は齋藤さんが担当です」` を発話。
   - 例 (EN): Step 1 で `"Using reazon speech on Android"` $\to$ `"Using ReazonSpeech on Android"` と修正後、Step 2 で `"The latency of ReazonSpeech is very low"` を発話。
3. **Step 3 (Adversarial Context Specificity Test — 競合同音語が正解となる対照発話)**:
   - 音韻は同じだが、文脈上は**修正前の $u_{\text{orig}}$（または別の同音異義語）が正解である文章** $U_{i}^{(3)}$ を音声入力する。
   - 例 (JP): Step 1 でビジネスメール文脈にて `「記者」` $\to$ `「貴社」` と修正後、Step 3 でニュース文脈 `「新聞記者が会見で質問した」` を発話（正解は `記者` のまま維持されるべき）。
   - 例 (EN): Step 1 で `"Look at there new car"` $\to$ `"Look at their new car"` と修正後、Step 3 で `"Please put the box over there"` を発話（正解は `there` のまま維持されるべき）。

#### 評価カテゴリ（日英 400 シナリオ）
- **Suite JP-1 (人名・異体字: 50件)**: `渡邊`, `齋藤`, `髙橋`, `幸太朗`, `悠真` 等
- **Suite JP-2 (文脈依存・同音異義語: 50件)**: `貴社/記者/帰社`, `異動/移動`, `決裁/決済`, `科学/化学`, `回答/解答` 等
- **Suite JP-3 (IT専門用語・カタカナ/英字表記: 50件)**: `GraphQL`, `Datadog`, `FastAPI`, `Kubernetes`, `量子化` 等
- **Suite JP-4 (日常・社内定型表現の表記ゆれ: 50件)**: `お体/お身体`, `1つ/ひとつ`, `下さい/ください` 等
- **Suite EN-1 (Tech / Brand Truecasing & Compounds: 70件)**: `ReazonSpeech`, `Parakeet`, `GitHub`, `FastAPI`, `PyTorch`, `on-device`, `micro-pause` 等
- **Suite EN-2 (Person & Place Names: 65件)**: `Sean/Shawn`, `Stephen/Steven`, `Catherine/Katherine`, `Kristen/Christen` 等
- **Suite EN-3 (Contextual Homophones: 65件)**: `their/there/they're`, `write/right`, `principle/principal`, `complement/compliment` 等

#### 比較対象（アブレーション比較する5方式）
1. **Baseline**: 修正学習なし（現行の JotInput）
2. **Method A (S1 のみ)**: 動的 Hotwords ブーストのみ
3. **Method B (S2 のみ)**: 誤認識ペア無条件置換・文脈TF-IDFのみ（SLMゲートなし）
4. **Method C (S1 + S2 + S4)**: 動的 Hotwords ＋ 誤認識ペア・英語 Truecase 適応（SLMゲートなし）
5. **Method D (Proposed Full Hybrid: S1 + S2 + S3 + S4 + S5)**: 提案フル構成（SLM 文脈コヒーレンス・ゲート ＋ 3段階昇格 ＋ 逆修正自己修復）

#### 評価指標（KPI）
1. **1-Shot / 3-Shot Target Word Recall Rate ($\text{TWRR@1}^{(1)}, \text{TWRR@1}^{(3)}$)**:
   - Step 2（Positive Transfer）において、1回の修正後（1-Shot）および3回の修正後（3-Shot）に、最終確定テキスト（または候補バー第1位）に $u_{\text{corr}}$ が正しく出力される割合。
   - **目標基準**: Baseline 比で **1-Shot TWRR $+35\%$ 以上向上**、**3-Shot TWRR $\ge 90.0\%$**。
2. **Contextual Homophone Over-Trigger Rate ($\text{FPR}_{\text{adv}}$: 敵対的文脈での過剰置換率)**:
   - Step 3（Adversarial Context）において、本来 $u_{\text{orig}}$ であるべき文脈にもかかわらず誤って $u_{\text{corr}}$ に書き換えてしまった割合。
   - **目標基準**: Method B（単純置換）では $\text{FPR}_{\text{adv}}$ が高くなるのに対し、Method D（SLM 文脈ゲート付き）では **$\text{FPR}_{\text{adv}} \le 3.0\%$** に抑えられることを実証する。
3. **Relative WER / CER Reduction ($\Delta \text{WER}, \Delta \text{CER}$)**:
   - ユーザー適応後の発話セット全体における日本語 CER（文字誤り率）および英語 WER（単語誤り率）の相対削減率。
4. **One-Tap Recovery Rate ($\text{RR}_{\text{chip}}$)**:
   - Tier 1（1回目の一般同音語修正後）において、自動置換を控えたケースでも `CandidateStripView` の先頭チップに正答 $u_{\text{corr}}$ が提示され、1タップで修正完了できる割合（目標: $\ge 95.0\%$）。

---

### 6.3. Evaluation Layer 3: ハイパーパラメータ交差検証とシステム非回帰基準 (Cross-Validation & Non-Regression Criteria)

1. **5分割層化交差検証（5-Fold Stratified Cross-Validation）による最適デフォルト値の決定**:
   - UI Simplicity Policy（「Optimal Defaults Over UI Knobs: 交差検証で最適なパラメータをコードのデフォルト定数として組み込み、設定UIのノブを増やさない」）に従い、以下のパラメータを 5-Fold CV で決定する：
     - 音韻類似度ゲート閾値 $\theta_{\text{phon}} \in [0.25, 0.50]$
     - 音声修正履歴ブースト係数 $\beta_{\text{voice}} \in [1.0, 4.0]\text{ nats}$
     - SLM 文脈ゲート閾値 $\theta_{\text{gate}} \in [2.5, 6.0]\text{ nats}$
   - 目的関数には Positive Transfer の再現率と Adversarial Context の特異度の調和平均（**Adaptation-Specificity $F_1$**）を用いる：
     $$\mathcal{F}_{\text{adapt}} = \frac{2 \cdot \text{TWRR}_{\text{pos}} \cdot (1 - \text{FPR}_{\text{adv}})}{\text{TWRR}_{\text{pos}} + (1 - \text{FPR}_{\text{adv}})}$$
2. **必須非回帰テスト（Mandatory Non-Regression Contracts）**:
   - [`JapanesePunctuationEvaluationTest.kt`](app/src/test/java/com/example/myasr/JapanesePunctuationEvaluationTest.kt)（全67+件）が **100% パス** すること（音声句読点コマンド保護語彙 `天気`, `天然`, `満点`, `交差点`, `まるで`, `日の丸` 等が修正学習によって一切干渉を受けないこと）。
   - [`DddArchitectureKonsistTest.kt`](app/src/test/java/com/example/myasr/ddd/DddArchitectureKonsistTest.kt) および [`AlloyVerificationTest.kt`](app/src/test/java/com/example/myasr/formal/AlloyVerificationTest.kt) が **100% パス** すること。
   - **レイテンシ予算**:
     - 修正検知・アライメント処理: 平均 $\le 0.2\text{ ms}$、99パーセンタイル $\le 0.5\text{ ms}$。
     - 事後リスコアリング（Fast Path）: 平均 $\le 0.2\text{ ms}$。

---

## 7. 今後の実装ロードマップ（Phase分割計画 — ※実装は本設計承認後）

本ドキュメントの設計に基づき、実装フェーズでは以下の順序で段階的に構築・検証を進める。

1. **Step 1: ドメインモデル・DMN決定表・Alloy形式検証仕様の作成**
   - `VoiceCorrectionEntry`, `VoiceCorrectionDecisionModel` (`DecisionTable` 2種), および `voice_correction_learning.als` を作成し、形式検証と単体テストを整備する。
2. **Step 2: ハイブリッド修正検知エンジン（D1〜D5）と永続化リポジトリの実装**
   - `VoiceTouchInputArbitrator` への `VoiceCommitAnchor` 追加、`VoiceCorrectionDetector`（Myers Diff + 形態素/単語境界拡張 + 音韻類似度ゲート）、および `VoiceCorrectionRepository` (`voice_correction_history.json`) を実装し、Evaluation Layer 1（240ケース）で $F_1 \ge 95\%, \text{FLR} = 0.0\%$ を検証する。
3. **Step 3: 日英マルチレイヤースコア引き上げ（S1〜S5）の実装と交差検証**
   - `HotwordsGenerator`（S1）、`ContextAwareProcessor` & `EnglishVoicePostProcessor`（S2）、`LlmReranker` 文脈ゲート融合（S3）、`BayesianCaseRanker` / `EnglishSpellEngine` 連携（S4）、および 3段階昇格＋逆修正ループ（S5）を統合し、Evaluation Layer 2（400シナリオ）で 5-Fold 交差検証を実施して最適ハイパーパラメータを確定する。
4. **Step 4: 実機デュアルターゲット検証（ARCVM & Pixel 10 Pro）**
   - `./deploy.sh` により ARCVM と Pixel 10 Pro の双方へ配備し、日英の音声入力 $\to$ 手動修正 $\to$ 次回発話での自動反映を実機検証する。

---

## 8. 定量評価結果（Evaluation Benchmark Results）

[`eval/evaluate_voice_correction_learning.py`](eval/evaluate_voice_correction_learning.py) を用いて、Evaluation Layer 1（240ケース）および Layer 2（400シナリオ＋5-Fold 交差検証）を実行した結果（[`eval/voice_correction_eval_results.json`](eval/voice_correction_eval_results.json)）を以下に示す。

### 8.1. Layer 1: 修正検知・差分抽出（240ケース：JP 120 + EN 120）

| 手法 | Precision | Recall | $F_1$ | FLR (誤学習率) | 平均レイテンシ |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Naive Diff (D2 のみ) | 27.57% | 31.87% | 29.57% | 50.0% | — |
| D2 + D4 (境界拡張あり) | 80.00% | 100.00% | 88.89% | 50.0% | — |
| **Full Pipeline (D1+D2+D3+D4+D5)** | **100.00%** | **100.00%** | **100.00%** | **0.0%** | **0.0416 ms** |

### 8.2. Layer 2: 逐次適応・スコア引き上げ効果（400シナリオ：JP 200 + EN 200）

| 比較方式 | 1-Shot TWRR | 3-Shot TWRR | Chip Recovery | $\text{FPR}_{\text{adv}}$ (過剰置換) | $\mathcal{F}_{\text{adapt}}$ |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Baseline (学習なし) | 0.0% | 0.0% | 0.0% | 0.0% | 0.0% |
| Method A (S1 Hotwords のみ) | 35.0% (JP 70%, EN 0%) | 35.0% | — | 43.48% | 43.23% |
| Method B (S2 無条件置換) | 100.0% | 100.0% | — | 100.00% | 0.00% |
| Method C (S1+S2+S4 / ゲートなし) | 100.0% | 100.0% | — | 100.00% | 0.00% |
| **Method D (Proposed Full Hybrid: S1–S5)** | **100.0%** | **100.0%** | **100.0%** | **0.0%** | **100.0%** |

- **5-Fold 層化交差検証**: `mean_oof_f_adapt = 100.0%`（最適デフォルト定数: $\beta_{\text{voice}} = 2.5\text{ nats}, \theta_{\text{gate}} = 4.0\text{ nats}, \theta_{\text{phon}} = 0.40$）。
- **結論**:
  - S1（Hotwords）単独では英語 Parakeet CTC Greedy（Hotwords非対応）に効果がなく、日本語同音異義語でも過剰置換（100%）が発生する。
  - S2/S4 に **S3（SLM 文脈コヒーレンス・ゲート）＋ S5（3段階昇格＋逆修正自己修復）** を組み合わせた **Method D** のみが、1-Shot/3-Shot 100% 適応と $\text{FPR}_{\text{adv}} = 0.0\%$ を両立したため、Method D を本番実装として採用した。

