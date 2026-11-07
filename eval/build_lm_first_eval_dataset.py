#!/usr/bin/env python3
"""
build_lm_first_eval_dataset.py

Constructs the comprehensive evaluation & parameter tuning dataset for the LM-First
architecture, covering:
1. Context-Aware Homophones (50 cases)
2. Conversational Phrases / Collocations (50 cases)
3. Diagnostic Cases: けっか (結果) and さくじょ (削除) across contexts (12 cases)
4. User Dictionary Respect & Surprisal Clamping (20 cases: exact, embedded, multi-token, tie-breaking)
5. Observation Model: Flick Typo & Voicing Fuzzy Matches (15 cases)
6. Natural Conversational Corpus (100 cases from tools/eval_1000_cases.tsv)

Total: ~247 cases.
"""

import json
import os
import sys

def build_dataset():
    items = []

    # 1. Homophones (50 cases from evaluate_slm_mandatory_rerank.py)
    homophone_raw = [
        ("来月から営業部への", "いどう", ["異動"], ["移動", "居留", "威道"]),
        ("新幹線を使って大阪へ", "いどう", ["移動"], ["異動", "居留", "威道"]),
        ("日経新聞の敏腕", "きしゃ", ["記者"], ["貴社", "汽車", "帰社"]),
        ("ますますご清栄のこととお慶び申し上げます。", "きしゃ", ["貴社"], ["記者", "汽車", "帰社"]),
        ("煙を吐いて走る昔ながらの", "きしゃ", ["汽車"], ["記者", "貴社", "帰社"]),
        ("外出先での打ち合わせを終えて", "きしゃ", ["帰社"], ["記者", "貴社", "汽車"]),
        ("最新の人工知能技術に強い", "かんしん", ["関心"], ["感心", "歓心", "寒心"]),
        ("彼の素晴らしい礼儀正しさにとても", "かんしん", ["感心"], ["関心", "歓心", "寒心"]),
        ("上司の機嫌をとって", "かんしん", ["歓心"], ["関心", "感心", "寒心"]),
        ("緊急時に迅速に対応できる", "たいせい", ["態勢"], ["体制", "大勢", "体勢"]),
        ("民主主義を支える強固な社会", "たいせい", ["体制"], ["態勢", "大勢", "体勢"]),
        ("転倒しないように低く構えた", "たいせい", ["体勢"], ["体制", "態勢", "大勢"]),
        ("一部の反対があっても", "たいせい", ["大勢"], ["態勢", "体制", "体勢"]),
        ("築地で仕入れた新鮮な魚が並ぶ", "しじょう", ["市場"], ["史上", "詩情", "死条"]),
        ("ニューヨーク株式", "しじょう", ["市場"], ["史上", "詩情", "死条"]),
        ("オリンピックの歴史上、", "しじょう", ["史上"], ["市場", "詩情", "死条"]),
        ("この法律は国民の権利を", "ほしょう", ["保障"], ["補償", "保証", "舗装"]),
        ("メーカーによる製品の無償修理", "ほしょう", ["保証"], ["保障", "補償", "舗装"]),
        ("事故による損害を", "ほしょう", ["補償"], ["保障", "保証", "舗装"]),
        ("道路の舗装工事を", "ほしょう", ["舗装"], ["保障", "保証", "補償"]),
        ("自分の信念に対する確固たる", "かくしん", ["確信"], ["核心", "革新", "画期"]),
        ("問題の本質に迫る", "かくしん", ["核心"], ["確信", "革新", "画期"]),
        ("技術革新による社会の", "かくしん", ["革新"], ["確信", "核心", "画期"]),
        ("調査の対象となる", "たいしょう", ["対象"], ["対照", "対称", "大正"]),
        ("光と影の見事な", "たいしょう", ["対照"], ["対象", "対称", "大正"]),
        ("左右対称の美しい", "たいしょう", ["対称"], ["対象", "対照", "大正"]),
        ("大正時代に建てられた", "たいしょう", ["大正"], ["対象", "対照", "対称"]),
        ("科学的な実験を行う", "じっけん", ["実験"], ["実権", "十軒", "十件"]),
        ("独裁者が権力を握り", "じっけん", ["実権"], ["実験", "十軒", "十件"]),
        ("現場の状況を冷静に", "はあく", ["把握"], ["波及", "歯垢", "葉隠"]),
        ("病気にかかった原因を", "きゅうめい", ["究明"], ["救命", "糾明", "窮命"]),
        ("救急車による救命活動", "きゅうめい", ["救命"], ["究明", "糾明", "窮命"]),
        ("不正の真相を厳しく", "きゅうめい", ["糾明"], ["究明", "救命", "窮命"]),
        ("素晴らしい成果を", "おさめる", ["収める"], ["納める", "治める", "修める"]),
        ("税金を期日までに", "おさめる", ["納める"], ["収める", "治める", "修める"]),
        ("国を平和に", "おさめる", ["治める"], ["収める", "納める", "修める"]),
        ("学問や芸能を", "おさめる", ["修める"], ["収める", "納める", "治める"]),
        ("将来の夢を熱く", "かたる", ["語る"], ["騙る", "過たる", "固る"]),
        ("身元を偽って", "かたる", ["騙る"], ["語る", "過たる", "固る"]),
        ("新しい法律を", "せいてい", ["制定"], ["清純", "静止", "晴天"]),
        ("澄み渡るような青空の", "せいてん", ["晴天"], ["聖典", "正典", "生誕"]),
        ("キリスト教の聖なる", "せいてん", ["聖典"], ["晴天", "正典", "生誕"]),
        ("物価の継続的な上昇を示す", "いんふれ", ["インフレ"], ["院振れ", "因触れ", "隠府例"]),
        ("海外旅行で訪れた美しい", "ぱり", ["パリ"], ["破裏", "把利", "針"]),
        ("裁縫箱から取り出した一本の", "はり", ["針"], ["張り", "巴里", "播磨"]),
        ("壁にポスターを", "はる", ["貼る"], ["春", "張る", "遥"]),
        ("桜が咲き誇る暖かい", "はる", ["春"], ["貼る", "張る", "遥"]),
        ("テントの布地をピンと", "はる", ["張る"], ["貼る", "春", "遥"]),
        ("川の流れに沿って", "くだる", ["下る"], ["降る", "百済", "件る"]),
        ("電車からホームに", "おりる", ["降りる"], ["下りる", "織る", "檻る"])
    ]

    for idx, (ctx, reading, targets, alts) in enumerate(homophone_raw, 1):
        target = targets[0]
        cands = [target] + [a for a in alts if a != target]
        cand_objs = []
        for c_idx, c in enumerate(cands):
            cand_objs.append({
                "surface": c,
                "source": "system",
                "priority": 0.0,
                "obs_penalty": 0.0,
                "mozc_cost": 3000 + c_idx * 500
            })
        items.append({
            "id": f"homophone_{idx:03d}",
            "category": "homophone",
            "context": ctx,
            "input": reading,
            "target": target,
            "candidates": cand_objs
        })

    # 2. Phrases / Collocations (50 cases)
    phrase_raw = [
        ("Mozcは私の直感と挙動が", "ことなります", "異なります", ["ことなります", "事成ります", "言成ります"]),
        ("一般的な仕様とは大きく", "ことなり", "異なり", ["ことなり", "事なり", "言なり"]),
        ("来週より担当者が変更される", "ことになりました", "ことになりました", ["異になりました", "事になりました"]),
        ("私の個人的な意見としてはそう", "おもいます", "思います", ["おもいます", "重います", "想います"]),
        ("別の多角的な視点からも", "かんがえます", "考えます", ["かんがえます", "勘が得ます"]),
        ("季節の移り変わりによって気温が", "かわります", "変わります", ["代わります", "替わります", "かわります"]),
        ("担当者が急病のため急遽", "かわります", "代わります", ["変わります", "替わります", "かわります"]),
        ("古くなった電池を新しいものに", "かえます", "替えます", ["変えます", "代えます", "かえます"]),
        ("これからも日々の努力を", "つづけます", "続けます", ["つづけます", "続け増す"]),
        ("計算の手順をうっかり", "まちがえます", "間違えます", ["まちがえます", "間違う得ます"]),
        ("明日の朝一番で会社に", "むかいます", "向かいます", ["迎えます", "むかいます"]),
        ("空港で大事なお客様を", "むかえます", "迎えます", ["向かいます", "むかえます"]),
        ("美味しい料理をたくさん", "つくります", "作ります", ["造ります", "創ります", "つくります"]),
        ("新しい会社組織を", "つくります", "創ります", ["作ります", "造ります", "つくります"]),
        ("巨大なダムを川に", "つくります", "造ります", ["作ります", "創ります", "つくります"]),
        ("先生が優しく生徒に", "おしえます", "教えます", ["惜し得ます", "おしえます"]),
        ("困っている友達をみんなで", "たすけます", "助けます", ["たすけます", "足す蹴ます"]),
        ("試験に合格することを心から", "ねがいます", "願います", ["ねがいます", "根が居ます"]),
        ("約束の時間をしっかり", "まもります", "守ります", ["まもります", "真盛ります"]),
        ("重い荷物を二人で一緒に", "はこびます", "運びます", ["はこびます", "歯来ます"]),
        ("朝の澄んだ空気を胸いっぱいに", "すいます", "吸います", ["すいます", "巣居ます"]),
        ("きれいな字で丁寧に手紙を", "かきます", "書きます", ["描きます", "掻きます", "かきます"]),
        ("スケッチブックに風景を", "かきます", "描きます", ["書きます", "掻きます", "かきます"]),
        ("頭の痒いところをボリボリ", "かきます", "掻きます", ["書きます", "描きます", "かきます"]),
        ("走った後に喉がとても", "かわきます", "渇きます", ["乾きます", "かわきます"]),
        ("洗った洗濯物が天日でよく", "かわきます", "乾きます", ["渇きます", "かわきます"]),
        ("冷たい風が強く", "ふきます", "吹きます", ["拭きます", "ふきます"]),
        ("汚れた机の上を雑巾で", "ふきます", "拭きます", ["吹きます", "ふきます"]),
        ("お風呂に入って汗を", "ながします", "流します", ["ながします", "名が沁ます"]),
        ("大きな声で元気に", "さけびます", "叫びます", ["さけびます", "酒美ます"]),
        ("悲しい出来事に涙を", "ながします", "流します", ["ながします", "名が沁ます"]),
        ("先生の貴重なアドバイスに深く", "うなずきます", "頷きます", ["うなずきます", "首肯きます"]),
        ("新しい挑戦に胸がワクワク", "おどります", "躍ります", ["踊ります", "おどります"]),
        ("音楽に合わせて楽しくリズムよく", "おどります", "踊ります", ["躍ります", "おどります"]),
        ("冷たい水の中に深く", "もぐります", "潜ります", ["もぐります", "藻繰ります"]),
        ("急な坂道をゆっくり慎重に", "のぼります", "登ります", ["上ります", "昇ります", "のぼります"]),
        ("階段を一段ずつ", "のぼります", "上ります", ["登ります", "昇ります", "のぼります"]),
        ("太陽が東の空から高く", "のぼります", "昇ります", ["登ります", "上ります", "のぼります"]),
        ("暗い部屋のカーテンを勢いよく", "あけます", "開けます", ["明けます", "空けます", "あけます"]),
        ("新しい年がめでたく", "あけます", "明けます", ["開けます", "空けます", "あけます"]),
        ("旅行のためにスケジュールを", "あけます", "空けます", ["開けます", "明けます", "あけます"]),
        ("部屋の鍵をカバンから取り出して", "しめます", "閉めます", ["締めます", "占めます", "しめます"]),
        ("ネクタイを首元できっちり", "しめます", "締めます", ["閉めます", "占めます", "しめます"]),
        ("市場の過半数のシェアを", "しめます", "占めます", ["閉めます", "締めます", "しめます"]),
        ("静かに目を", "とじます", "閉じます", ["とじます", "綴じます"]),
        ("書類をホチキスでしっかり", "とじます", "綴じます", ["閉じます", "とじます"]),
        ("新しいアイデアを次々に", "うみだします", "生み出します", ["産み出します", "うみだします"]),
        ("母猫が元気な子猫を", "うみだします", "産み出します", ["生み出します", "うみだします"]),
        ("遠くに見える高い山の頂を", "のぞみます", "望みます", ["臨みます", "のぞみます"]),
        ("明日の重要な入学試験に", "のぞみます", "臨みます", ["望みます", "のぞみます"])
    ]

    for idx, (ctx, reading, target, alts) in enumerate(phrase_raw, 1):
        cands = [target] + [a for a in alts if a != target]
        cand_objs = []
        for c_idx, c in enumerate(cands):
            cand_objs.append({
                "surface": c,
                "source": "system",
                "priority": 0.0,
                "obs_penalty": 0.0,
                "mozc_cost": 2500 + c_idx * 600
            })
        items.append({
            "id": f"phrase_{idx:03d}",
            "category": "phrase",
            "context": ctx,
            "input": reading,
            "target": target,
            "candidates": cand_objs
        })

    # 3. Diagnostic Cases (12 cases: けっか and さくじょ under various contexts)
    diagnostic_raw = [
        # けっか: Contexts "", "。", "は", "の"
        ("", "けっか", "結果", ["結果", "結家", "欠課", "決河", "けっ課", "蹴っか"]),
        ("。", "けっか", "結果", ["結果", "結家", "欠課", "決河", "けっ課", "蹴っか"]),
        ("実験の", "けっか", "結果", ["結果", "結家", "欠課", "決河", "けっ課", "蹴っか"]),
        ("検査の", "けっか", "結果", ["結果", "結家", "欠課", "決河", "けっ課", "蹴っか"]),
        ("投票の", "けっか", "結果", ["結果", "結家", "欠課", "決河", "けっ課", "蹴っか"]),
        ("その", "けっか", "結果", ["結果", "結家", "欠課", "決河", "けっ課", "蹴っか"]),
        # さくじょ: Contexts "", "。", "を", "の"
        ("", "さくじょ", "削除", ["削除", "策所", "咲くじょ", "さ9時ょ", "咲所", "サク所"]),
        ("。", "さくじょ", "削除", ["削除", "策所", "咲くじょ", "さ9時ょ", "咲所", "サク所"]),
        ("ファイルを", "さくじょ", "削除", ["削除", "策所", "咲くじょ", "さ9時ょ", "咲所", "サク所"]),
        ("不要な行を", "さくじょ", "削除", ["削除", "策所", "咲くじょ", "さ9時ょ", "咲所", "サク所"]),
        ("データベースのレコードを", "さくじょ", "削除", ["削除", "策所", "咲くじょ", "さ9時ょ", "咲所", "サク所"]),
        ("ユーザーアカウントの", "さくじょ", "削除", ["削除", "策所", "咲くじょ", "さ9時ょ", "咲所", "サク所"]),
    ]

    for idx, (ctx, reading, target, cands) in enumerate(diagnostic_raw, 1):
        cand_objs = []
        for c_idx, c in enumerate(cands):
            # assign Mozc cost: target is lowest, chimeric is higher
            cost = 1000 if c == target else 4000 + c_idx * 500
            cand_objs.append({
                "surface": c,
                "source": "system",
                "priority": 0.0,
                "obs_penalty": 0.0,
                "mozc_cost": cost
            })
        items.append({
            "id": f"diagnostic_{idx:03d}",
            "category": "diagnostic",
            "context": ctx,
            "input": reading,
            "target": target,
            "candidates": cand_objs
        })

    # 4. User Dictionary Respect & Surprisal Clamping (20 cases)
    # Covers:
    # - Exact match single words
    # - Multi-token breakdown words (ReazonSpeech, 氣志團, 栗原)
    # - Embedded phrases (くりはらさんにれんらくする)
    # - Contextual tie-breaking between user words (きしゃ with 貴社 vs 記者)
    user_dict_raw = [
        # Scenario 1: Exact match with system competitor
        ("", "くりはら", "栗原",
         [{"surface": "栗原", "source": "user", "priority": 3.0, "tokens": ["栗", "原"]},
          {"surface": "栗腹", "source": "system", "priority": 0.0},
          {"surface": "クリア", "source": "system", "priority": 0.0}]),
        ("", "れいぞんすぴーち", "ReazonSpeech",
         [{"surface": "ReazonSpeech", "source": "user", "priority": 3.0, "tokens": ["Re", "azon", "Spe", "ech"]},
          {"surface": "令息地", "source": "system", "priority": 0.0}]),
        ("", "あんちぐらびてぃ", "Antigravity",
         [{"surface": "Antigravity", "source": "user", "priority": 3.0, "tokens": ["Anti", "gravity"]},
          {"surface": "安地グラビティ", "source": "system", "priority": 0.0}]),
        ("", "きしだん", "氣志團",
         [{"surface": "氣志團", "source": "user", "priority": 3.0, "tokens": ["氣", "志", "團"]},
          {"surface": "騎士団", "source": "system", "priority": 0.0},
          {"surface": "岸団", "source": "system", "priority": 0.0}]),
        ("", "じょっといんぷっと", "JotInput",
         [{"surface": "JotInput", "source": "user", "priority": 4.5, "tokens": ["J", "ot", "Input"]},
          {"surface": "じょっとインプット", "source": "system", "priority": 0.0}]),

        # Scenario 2: Embedded user dictionary in long phrases
        ("", "くりはらさんにれんらくする", "栗原さんに連絡する",
         [{"surface": "栗原さんに連絡する", "source": "user", "priority": 3.0, "user_term": "栗原"},
          {"surface": "栗腹さんに連絡する", "source": "system", "priority": 0.0},
          {"surface": "クリアさんに連絡する", "source": "system", "priority": 0.0}]),
        ("昨日の会議で", "くりはらさんがはつげんした", "栗原さんが発言した",
         [{"surface": "栗原さんが発言した", "source": "user", "priority": 3.0, "user_term": "栗原"},
          {"surface": "栗腹さんが発言した", "source": "system", "priority": 0.0}]),
        ("新しい音声認識エンジンとして", "れいぞんすぴーちをじっそうした", "ReazonSpeechを実装した",
         [{"surface": "ReazonSpeechを実装した", "source": "user", "priority": 3.0, "user_term": "ReazonSpeech"},
          {"surface": "令息地を実装した", "source": "system", "priority": 0.0}]),
        ("オープンソースの", "あんちぐらびてぃをつかう", "Antigravityを使う",
         [{"surface": "Antigravityを使う", "source": "user", "priority": 3.0, "user_term": "Antigravity"},
          {"surface": "安地グラビティを使う", "source": "system", "priority": 0.0}]),
        ("ロックバンドの", "きしだんのらいぶにいく", "氣志團のライブに行く",
         [{"surface": "氣志團のライブに行く", "source": "user", "priority": 3.0, "user_term": "氣志團"},
          {"surface": "騎士団のライブに行く", "source": "system", "priority": 0.0}]),

        # Scenario 3: Contextual Tie-breaking between 2 user words under same reading
        # User registered BOTH "貴社" (priority=3.0) and "記者" (priority=3.0)
        ("平素は格別のご高配を賜り、", "きしゃのますますのごはってんを", "貴社の益々のご発展を",
         [{"surface": "貴社の益々のご発展を", "source": "user", "priority": 3.0, "user_term": "貴社"},
          {"surface": "記者の益々のご発展を", "source": "user", "priority": 3.0, "user_term": "記者"},
          {"surface": "汽車の益々のご発展を", "source": "system", "priority": 0.0}]),
        ("朝日新聞の政治部", "きしゃがしゅざいにきた", "記者が取材に来た",
         [{"surface": "記者が取材に来た", "source": "user", "priority": 3.0, "user_term": "記者"},
          {"surface": "貴社が取材に来た", "source": "user", "priority": 3.0, "user_term": "貴社"},
          {"surface": "汽車が取材に来た", "source": "system", "priority": 0.0}]),
        ("来週の月曜日に", "きしゃのオフィスへうかがいます", "貴社のオフィスへ伺います",
         [{"surface": "貴社のオフィスへ伺います", "source": "user", "priority": 3.0, "user_term": "貴社"},
          {"surface": "記者のオフィスへ伺います", "source": "user", "priority": 3.0, "user_term": "記者"}]),
        ("事件の真相を解明すべく敏腕", "きしゃがげんばをちょうさした", "記者が現場を調査した",
         [{"surface": "記者が現場を調査した", "source": "user", "priority": 3.0, "user_term": "記者"},
          {"surface": "貴社が現場を調査した", "source": "user", "priority": 3.0, "user_term": "貴社"}]),

        # Scenario 4: User entry priority differentiation (HIGH vs NORMAL)
        ("", "とうきょう", "東京都庁",
         [{"surface": "東京都庁", "source": "user", "priority": 4.5},  # HIGH
          {"surface": "東京", "source": "user", "priority": 3.0},      # NORMAL
          {"surface": "東京都", "source": "system", "priority": 0.0}]),
        ("日本の首都である", "とうきょう", "東京",
         [{"surface": "東京", "source": "user", "priority": 3.0},
          {"surface": "東京都庁", "source": "user", "priority": 1.5},  # LOW
          {"surface": "登校", "source": "system", "priority": 0.0}]),

        # Scenario 5: Rare name with high unigram penalty vs generic homophone
        ("担当エンジニアの", "くりはら", "栗原",
         [{"surface": "栗原", "source": "user", "priority": 3.0},
          {"surface": "繰り原", "source": "system", "priority": 0.0},
          {"surface": "くりはら", "source": "system", "priority": 0.0}]),
        ("営業責任者の", "くりはら", "栗原",
         [{"surface": "栗原", "source": "user", "priority": 3.0},
          {"surface": "繰りはら", "source": "system", "priority": 0.0}]),
        ("プロジェクトリーダーの", "くりはら", "栗原",
         [{"surface": "栗原", "source": "user", "priority": 3.0},
          {"surface": "繰原", "source": "system", "priority": 0.0}]),
        ("取締役の", "くりはら", "栗原",
         [{"surface": "栗原", "source": "user", "priority": 3.0},
          {"surface": "繰原", "source": "system", "priority": 0.0}])
    ]

    for idx, (ctx, reading, target, cands) in enumerate(user_dict_raw, 1):
        cand_objs = []
        for c_idx, c in enumerate(cands):
            surf = c["surface"]
            cand_objs.append({
                "surface": surf,
                "source": c.get("source", "system"),
                "priority": c.get("priority", 0.0),
                "obs_penalty": 0.0,
                "mozc_cost": 2000 if c.get("source") == "user" else 4000 + c_idx * 500
            })
        items.append({
            "id": f"user_dict_{idx:03d}",
            "category": "user_dict",
            "context": ctx,
            "input": reading,
            "target": target,
            "candidates": cand_objs
        })

    # 5. Observation Model: Flick Typo & Voicing Fuzzy (15 cases)
    obs_raw = [
        ("明日は全国的に", "てんくがよい", "天気が良い",
         [{"surface": "天気が良い", "obs_penalty": 1.5, "mozc_cost": 2500},  # typo てんく -> てんき
          {"surface": "点区が良い", "obs_penalty": 0.0, "mozc_cost": 4500},
          {"surface": "店苦が良い", "obs_penalty": 0.0, "mozc_cost": 5000}]),
        ("午前中に重要な", "かいきがある", "会議がある",
         [{"surface": "会議がある", "obs_penalty": 1.2, "mozc_cost": 2200},  # unvoiced かいき -> かいぎ
          {"surface": "会期がある", "obs_penalty": 0.0, "mozc_cost": 3800},
          {"surface": "回帰がある", "obs_penalty": 0.0, "mozc_cost": 4200}]),
        ("近所の大型", "すーはーにいく", "スーパーに行く",
         [{"surface": "スーパーに行く", "obs_penalty": 1.2, "mozc_cost": 2000},  # unvoiced すーはー -> すーぱー
          {"surface": "吸う歯に行く", "obs_penalty": 0.0, "mozc_cost": 6000}]),
        ("夕食に温かい", "こはんをたべる", "ご飯を食べる",
         [{"surface": "ご飯を食べる", "obs_penalty": 1.2, "mozc_cost": 2100},  # unvoiced こはん -> ごはん
          {"surface": "弧版を食べる", "obs_penalty": 0.0, "mozc_cost": 7000}]),
        ("駅前の交差点で", "しんこうがかわる", "信号が変わる",
         [{"surface": "信号が変わる", "obs_penalty": 1.2, "mozc_cost": 2300},  # unvoiced しんこう -> しんごう
          {"surface": "信仰が変わる", "obs_penalty": 0.0, "mozc_cost": 3500},
          {"surface": "進行が変わる", "obs_penalty": 0.0, "mozc_cost": 3600}]),
        ("冷たい風が", "ふいてさむい", "吹いて寒い",
         [{"surface": "吹いて寒い", "obs_penalty": 0.0, "mozc_cost": 2400},
          {"surface": "拭いて寒い", "obs_penalty": 0.0, "mozc_cost": 3800}]),
        ("美味しい料理を", "たへる", "食べる",
         [{"surface": "食べる", "obs_penalty": 1.2, "mozc_cost": 2000},  # unvoiced たへる -> たべる
          {"surface": "他経る", "obs_penalty": 0.0, "mozc_cost": 8000}]),
        ("朝の満員", "てんしゃにのる", "電車に乗る",
         [{"surface": "電車に乗る", "obs_penalty": 1.2, "mozc_cost": 2200},  # unvoiced てんしゃ -> でんしゃ
          {"surface": "転写に乗る", "obs_penalty": 0.0, "mozc_cost": 4500}]),
        ("大切な荷物を", "ととける", "届ける",
         [{"surface": "届ける", "obs_penalty": 1.2, "mozc_cost": 2200},  # unvoiced ととける -> とどける
          {"surface": "途溶ける", "obs_penalty": 0.0, "mozc_cost": 7500}]),
        ("病院で薬を", "もらつてかえる", "もらって帰る",
         [{"surface": "もらって帰る", "obs_penalty": 1.0, "mozc_cost": 2300},  # large つ -> small っ
          {"surface": "貰う津で帰る", "obs_penalty": 0.0, "mozc_cost": 8000}]),
        ("雨が降って道路が", "ぬれてすべる", "濡れて滑る",
         [{"surface": "濡れて滑る", "obs_penalty": 0.0, "mozc_cost": 2400},
          {"surface": "塗れて統べる", "obs_penalty": 0.0, "mozc_cost": 6500}]),
        ("夜空に輝く満天の", "ほしそらをみる", "星空を見る",
         [{"surface": "星空を見る", "obs_penalty": 0.0, "mozc_cost": 2200},
          {"surface": "干し空を見る", "obs_penalty": 0.0, "mozc_cost": 6000}]),
        ("友達と仲良く", "あそふ", "遊ぶ",
         [{"surface": "遊ぶ", "obs_penalty": 1.2, "mozc_cost": 2100},  # unvoiced あそふ -> あそぶ
          {"surface": "麻布", "obs_penalty": 0.0, "mozc_cost": 4200}]),
        ("お腹が空いたのでパンを", "かじく", "かじる",
         [{"surface": "かじる", "obs_penalty": 1.5, "mozc_cost": 2600},  # typo かじく -> かじる
          {"surface": "加重く", "obs_penalty": 0.0, "mozc_cost": 7000}]),
        ("冷たいアイスを", "かちる", "かじる",
         [{"surface": "かじる", "obs_penalty": 1.2, "mozc_cost": 2600},  # unvoiced かちる -> かじる
          {"surface": "勝る", "obs_penalty": 0.0, "mozc_cost": 4000}])
    ]

    for idx, (ctx, reading, target, cands) in enumerate(obs_raw, 1):
        cand_objs = []
        for c in cands:
            cand_objs.append({
                "surface": c["surface"],
                "source": "system",
                "priority": 0.0,
                "obs_penalty": c.get("obs_penalty", 0.0),
                "mozc_cost": c.get("mozc_cost", 3000)
            })
        items.append({
            "id": f"observation_{idx:03d}",
            "category": "observation",
            "context": ctx,
            "input": reading,
            "target": target,
            "candidates": cand_objs
        })

    # 6. Natural Conversational Corpus (Sample 100 cases from tools/eval_1000_cases.tsv)
    tsv_path = "tools/eval_1000_cases.tsv"
    if os.path.exists(tsv_path):
        count = 0
        with open(tsv_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line: continue
                parts = line.split("\t")
                if len(parts) >= 4:
                    case_id = parts[0]
                    context = parts[1]
                    target = parts[2]
                    reading = parts[3]
                    # build alternative candidates if available or generic
                    alts = parts[4:] if len(parts) > 4 else []
                    cands = [target] + [a for a in alts if a != target]
                    if len(cands) == 1:
                        cands.append(reading)
                    cand_objs = []
                    for c_idx, c in enumerate(cands):
                        cand_objs.append({
                            "surface": c,
                            "source": "system",
                            "priority": 0.0,
                            "obs_penalty": 0.0,
                            "mozc_cost": 2500 + c_idx * 500
                        })
                    items.append({
                        "id": f"natural_{count+1:03d}_{case_id}",
                        "category": "natural",
                        "context": context,
                        "input": reading,
                        "target": target,
                        "candidates": cand_objs
                    })
                    count += 1
                    if count >= 100:
                        break

    out_path = "eval/lm_first_eval_dataset.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)

    print(f"Generated {len(items)} evaluation items across categories:")
    categories = {}
    for item in items:
        cat = item["category"]
        categories[cat] = categories.get(cat, 0) + 1
    for cat, cnt in categories.items():
        print(f"  - {cat}: {cnt} cases")
    print(f"Saved dataset to {out_path}")

if __name__ == "__main__":
    build_dataset()
