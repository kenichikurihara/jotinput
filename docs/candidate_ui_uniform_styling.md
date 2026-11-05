# 変換候補表示UIの均一化（ユーザー辞書単語の見た目区別の撤廃）仕様書

## 1. 概要 (Executive Summary)

これまでJotInputの変換候補バー（[`CandidateStripView`](app/src/main/java/com/example/myasr/CandidateStripView.kt)）では、カスタム辞書から登録された単語（`candidate.source == "user"`）について、他の通常候補と識別できるように「📖 アイコンの付与」および「専用のピル背景色・文字色でのハイライト表示」を行っていました。

本改修ではユーザーからの要望に基づき、**辞書登録単語に対する見た目の区別を完全に撤廃**し、すべての変換候補を同一の標準ピルスタイル（Gboard準拠の均一なテキスト・背景）で統一表示する仕様に変更しました。

---

## 2. 変更仕様と設計方針

### 2.1 変更前後の比較

| 項目 | 変更前 (Before) | 変更後 (After) |
| :--- | :--- | :--- |
| **辞書登録単語のテキスト表示** | `📖 単語名`（本アイコンが付与されていた） | `単語名`（余計なプレフィックスなし） |
| **辞書登録単語の背景ピル** | 専用スタイル (`bg_candidate_pill_user`) | 標準スタイル (`bg_candidate_pill`) |
| **辞書登録単語の文字色** | 専用色 (`candidate_user_text`) | 標準色 (`candidate_text`) |
| **フォントウェイト** | 太字 (`Typeface.DEFAULT_BOLD`) | 標準 (`Typeface.DEFAULT`) |
| **変換順位（ランキング）** | **最優先 (1位)** | **最優先 (1位を維持)** |

> [!IMPORTANT]
> **ランキング契約 (ADR-004 / ADR-005) の維持**
> 見た目の差別化（アイコンや専用色）は撤廃されましたが、**「ユーザーが登録した単語は同音異義語の中で最優先にランクされる」という機能的優先順位は100%維持**されます。これにより、意図した変換効率を落とすことなく、ノイズのない自然で統一感のある候補リストを提供します。

---

## 3. 実装詳細

### 3.1 [`CandidateStripView.kt`](app/src/main/java/com/example/myasr/CandidateStripView.kt)

```kotlin
for (candidate in displayCandidates) {
    val tv = TextView(context).apply {
        // 辞書単語への📖アイコン付加を廃止し、すべての候補で純粋な表面形のみを表示
        text = candidate.surface
        textSize = textSizeSp
        typeface = Typeface.DEFAULT
        gravity = Gravity.CENTER

        // 辞書単語と一般語の区別をなくし、標準ピルデザインで均一化
        setTextColor(ContextCompat.getColor(context, R.color.candidate_text))
        setBackgroundResource(R.drawable.bg_candidate_pill)

        setPadding(padH, padV, padH, padV)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            pillHeight
        ).apply {
            setMargins(marginH, 0, marginH, 0)
        }
        layoutParams = lp

        setOnClickListener {
            listener?.onCandidateClick(candidate)
        }
    }
    container.addView(tv)
}
```

---

## 4. デプロイおよび実機動作確認

1. **単体テスト**: 全 51 件のテストが全て成功 (`BUILD SUCCESSFUL`)。
2. **デュアルターゲット・デプロイ ([`./deploy.sh`](deploy.sh))**:
   - **Pixel 10 Pro (`<device-ip>:37891`)**: インストール完了、正常起動・ウォームアップ確認済。
   - **ARCVM (`arc:5555`)**: インストール完了、正常起動・ウォームアップ確認済。
