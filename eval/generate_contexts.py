#!/usr/bin/env python3
"""
generate_contexts.py: Generates realistic preceding text-box context (textBeforeCursor)
for each of the 1,000 tricky Japanese benchmark examples.
"""

import json
import os
import random

DATASET_IN = os.path.join(os.path.dirname(__file__), "dataset.json")
DATASET_OUT = os.path.join(os.path.dirname(__file__), "dataset_with_context.json")

# Predefined contextual templates providing rich semantic, lexical, and entity cues
PEOPLE_CONTEXT_TEMPLATES = [
    "お疲れ様です。本日のチームミーティングの議事録をまとめました。",
    "先ほど人事部から来期のプロジェクト体制に関する連絡がありました。",
    "クライアントからの問い合わせについて社内で確認を進めています。",
    "先日のカンファレンスでお会いした方々へ御礼のメールを作成しています。",
    "開発タスクの進捗状況について関係メンバーに共有いたします。",
    "次回の定例会のアジェンダについてご相談させてください。",
    "先週実施したコードレビューのフィードバックを確認しました。",
    "新メンバーのオンボーディング計画について調整を行っています。"
]

HOMOPHONE_CONTEXT_RULES = {
    "貴社": [
        "いつも大変お世話になっております。先ほどいただいたご提案書を拝読いたしました。",
        "平素は格別のご高配を賜り厚く御礼申し上げます。今後の協業案件に関しまして、"
    ],
    "記者": [
        "明日の午後に予定されている新製品発表会のご案内です。大手新聞各社やメディアの",
        "最新のニュース報道に関するクリッピングを作成しています。業界紙の社会部担当の"
    ],
    "帰社": [
        "外出先でのクライアント企業への商談がすべて終了いたしました。これより電車にて",
        "午後の客先訪問を終えました。夕方の定例ミーティングまでにオフィスへ戻る予定です。"
    ],
    "汽車": [
        "明治時代の交通史をテーマにした博物館の企画展示を見学してきました。蒸気機関車と",
        "鉄道博物館で昔の乗り物の仕組みを学びました。黒い煙を上げて力強く走る"
    ],
    "科学": [
        "宇宙の起源や素粒子の謎を解き明かす先端研究のドキュメンタリーを観ました。現代の自然",
        "文部科学省の学術振興プログラムに応募するための研究計画書を作成しています。"
    ],
    "化学": [
        "実験室で新素材の合成反応をテストしています。高分子化合物と有機溶媒を用いた",
        "薬品の安全管理に関するガイドラインを改定しました。無機物質と生体分子の"
    ],
    "市立": [
        "市役所からのお知らせを確認しました。市民サービスの向上を目指して運営されている",
        "地域包括支援センターの活動報告です。自治体が管理する公共施設および"
    ],
    "私立": [
        "来春の受験シーズンに向けた進路指導の資料です。国公立学校とは異なる独自の教育方針を持つ",
        "全国の学校法人の教育カリキュラムを比較調査しています。伝統ある有名な"
    ],
    "回答": [
        "先週実施した顧客満足度アンケートの集計作業を行いました。ユーザーの皆様から寄せられた",
        "サポート窓口へのお問い合わせメールに対して、担当スタッフより迅速かつ丁寧に"
    ],
    "解答": [
        "期末試験の採点がすべて完了しました。問題ごとの正答率を分析したところ、大問３の",
        "資格試験の過去問題集に取り組んでいます。解説書に記載されている模範的な"
    ],
    "異動": [
        "４月１日付の組織改編に伴う社内辞令が発令されました。各部署における役職者やメンバーの",
        "キャリア開発面談を実施いたしました。本人の希望を考慮した今後の部署"
    ],
    "移動": [
        "地方出張に伴う交通経路と所要時間を調べました。新幹線と在来線を乗り継いでの",
        "オフィスのレイアウト変更に伴い、大型モニターとデスクを別フロアへスムーズに"
    ]
}

TECH_CONTEXT_TEMPLATES = [
    "Androidアプリのパフォーマンス改善タスクに取り組んでいます。アプリの軽量化のため、",
    "次世代の機械学習基盤のアーキテクチャ設計を行っています。エッジ推論の実行速度を測定し、",
    "チーム内のリファクタリング方針についてディスカッションを行いました。コードベースの保守性を高めるため、",
    "クラウドとオンデバイスのハイブリッド構成を検討しています。プライバシーを最優先に考慮し、",
    "オープンソースの最新ライブラリをプロジェクトに導入するためのPoC検証を進めています。"
]

PLACE_CONTEXT_TEMPLATES = [
    "来週の地方出張の交通スケジュールと移動ルートを共有いたします。最寄り駅の",
    "次の訪問先企業の所在地とアクセス方法を確認しています。地図アプリでルートを検索したところ、",
    "週末の小旅行のプランを立てています。観光名所や美味しいお店が集まる",
    "営業エリアの巡回ルートを見直しています。効率的に訪問先を回るため、"
]

NEGATIVE_CONTEXT_TEMPLATES = [
    "今日は朝から青空が広がっていてとても気持ちの良い一日です。",
    "週末に近所のスーパーマーケットで一週間分の食材を買い出ししてきました。",
    "少し肌寒くなってきたので、温かい緑茶を入れて一息つくことにしました。",
    "部屋の換気をして机の上を片付けたら、とても作業がはかどるようになりました。",
    "最近新しく買ったスニーカーの履き心地がとても良くて気に入っています。"
]

def generate_context_for_item(item, idx):
    cat = item.get("category", "")
    target = item.get("target_word", "")
    reading = item.get("reading", "")
    sentence = item.get("sentence", "")

    random.seed(idx * 7919 + 42)

    if cat == "people_names":
        prefix = random.choice(PEOPLE_CONTEXT_TEMPLATES)
        # In real typing, earlier messages in thread often mention the person or role
        if random.random() < 0.65 and target:
            context = f"{prefix}以前お世話になった{target}さんの件ですが、"
        else:
            context = f"{prefix}担当者の件について、"

    elif cat == "homophones":
        if target in HOMOPHONE_CONTEXT_RULES:
            context = random.choice(HOMOPHONE_CONTEXT_RULES[target])
        else:
            # Fallback contextual cue matching semantics of the sentence
            if any(k in sentence for k in ["会社", "社", "業務", "お祈り", "御社", "貴社"]):
                context = "平素より格別のご支援を賜り心より感謝申し上げます。今回の案件に関しまして、"
            elif any(k in sentence for k in ["新聞", "記事", "取材", "テレビ"]):
                context = "最新のニュース取材およびメディア報道に関する情報共有です。報道関係の"
            elif any(k in sentence for k in ["駅", "電車", "線路", "旅行"]):
                context = "交通機関の利用計画について確認を行いました。現地へ移動する"
            else:
                context = "前回の打ち合わせでお話ししたテーマについて、詳細な内容を検討いたしました。"

    elif cat == "tech_terms":
        prefix = random.choice(TECH_CONTEXT_TEMPLATES)
        if random.random() < 0.5 and target:
            context = f"{prefix}特に{target}の活用方法について議論を進めました。"
        else:
            context = prefix

    elif cat == "place_names":
        prefix = random.choice(PLACE_CONTEXT_TEMPLATES)
        context = prefix

    else: # negative_control
        context = random.choice(NEGATIVE_CONTEXT_TEMPLATES)

    return context

def main():
    with open(DATASET_IN, "r", encoding="utf-8") as f:
        items = json.load(f)

    print(f"Generating context for {len(items)} items...")
    enriched_items = []
    for idx, item in enumerate(items):
        ctx = generate_context_for_item(item, idx)
        new_item = dict(item)
        new_item["context_before"] = ctx
        enriched_items.append(new_item)

    with open(DATASET_OUT, "w", encoding="utf-8") as f:
        json.dump(enriched_items, f, ensure_ascii=False, indent=2)

    print(f"✓ Successfully wrote dataset with context to {DATASET_OUT}")

    # Print samples
    print("\nSample Enriched Entries:")
    for cat in ["people_names", "homophones", "tech_terms", "place_names", "negative_control"]:
        sample = next(it for it in enriched_items if it["category"] == cat)
        print(f"[{sample['category']}]")
        print(f"  Context Before: \"{sample['context_before']}\"")
        print(f"  Target: {sample.get('target_word')}")
        print(f"  Sentence: \"{sample['sentence']}\"\n")

if __name__ == "__main__":
    main()
