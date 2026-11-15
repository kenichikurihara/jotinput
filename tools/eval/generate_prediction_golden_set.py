#!/usr/bin/env python3
"""
Generate Reference Bigram Prediction Golden Set for Evaluation.

Constructs a standard baseline prediction evaluation dataset covering:
- Both Japanese (ja) and English (en)
- Bigram contexts (previous word committed -> next word target)
- 0-char prefix (zero-query immediately after previous word commit)
- 1-char prefix (first character of next word)
- 2-char prefix (first two characters of next word)
- Dual mode: "no_history" (clean zero-prior session) vs "with_history" (repeated sentence / active context memory)
- Top-3 candidates for each condition.
"""

import json
import os

def build_golden_set():
    japanese_cases = [
        {
            "id": "ja_bi_001",
            "domain": "business_greeting",
            "full_sentence": "よろしく お願い します",
            "prev_word": "よろしく",
            "target_word": "お願い",
            "target_reading": "おねがい",
            "zero_char": {
                "no_history": ["お願いします", "お願いいたします", "どうぞ"],
                "with_history": ["お願い", "お願いします", "お願いいたします"]
            },
            "one_char": {
                "query": "お",
                "no_history": ["お願いします", "お願いいたします", "お知らせ"],
                "with_history": ["お願い", "お願いします", "お願いいたします"]
            },
            "two_char": {
                "query": "おね",
                "no_history": ["お願いします", "お願い", "お願いいたします"],
                "with_history": ["お願い", "お願いします", "お願いいたします"]
            }
        },
        {
            "id": "ja_bi_002",
            "domain": "business_greeting",
            "full_sentence": "よろしく お願い します",
            "prev_word": "お願い",
            "target_word": "します",
            "target_reading": "します",
            "zero_char": {
                "no_history": ["します", "いたします", "申し上げます"],
                "with_history": ["します", "いたします", "申し上げます"]
            },
            "one_char": {
                "query": "し",
                "no_history": ["します", "した", "してください"],
                "with_history": ["します", "した", "して"]
            },
            "two_char": {
                "query": "しま",
                "no_history": ["します", "した", "しました"],
                "with_history": ["します", "した", "しました"]
            }
        },
        {
            "id": "ja_bi_003",
            "domain": "business_greeting",
            "full_sentence": "いつも お世話 に なっております",
            "prev_word": "いつも",
            "target_word": "お世話",
            "target_reading": "おせわ",
            "zero_char": {
                "no_history": ["お世話になっております", "ありがとうございます", "お世話になります"],
                "with_history": ["お世話", "お世話になっております", "ありがとうございます"]
            },
            "one_char": {
                "query": "お",
                "no_history": ["お世話になっております", "お世話", "お願い"],
                "with_history": ["お世話", "お世話になっております", "お願い"]
            },
            "two_char": {
                "query": "おせ",
                "no_history": ["お世話になっております", "お世話", "お世話になります"],
                "with_history": ["お世話", "お世話になっております", "お世話になります"]
            }
        },
        {
            "id": "ja_bi_004",
            "domain": "business_greeting",
            "full_sentence": "いつも お世話 に なっております",
            "prev_word": "お世話",
            "target_word": "に",
            "target_reading": "に",
            "zero_char": {
                "no_history": ["になっております", "になります", "の"],
                "with_history": ["に", "になっております", "になります"]
            },
            "one_char": {
                "query": "に",
                "no_history": ["になっております", "になります", "には"],
                "with_history": ["に", "になっております", "になります"]
            },
            "two_char": {
                "query": "にな",
                "no_history": ["になっております", "になります", "らなかった"],
                "with_history": ["になっております", "になります", "らない"]
            }
        },
        {
            "id": "ja_bi_005",
            "domain": "business_action",
            "full_sentence": "確認 しました",
            "prev_word": "確認",
            "target_word": "しました",
            "target_reading": "しました",
            "zero_char": {
                "no_history": ["しました", "いたします", "お願いします"],
                "with_history": ["しました", "いたします", "お願いします"]
            },
            "one_char": {
                "query": "し",
                "no_history": ["しました", "します", "してください"],
                "with_history": ["しました", "します", "して"]
            },
            "two_char": {
                "query": "しま",
                "no_history": ["しました", "します", "した"],
                "with_history": ["しました", "します", "した"]
            }
        },
        {
            "id": "ja_bi_006",
            "domain": "daily_greeting",
            "full_sentence": "お疲れ 様 です",
            "prev_word": "お疲れ",
            "target_word": "様",
            "target_reading": "さま",
            "zero_char": {
                "no_history": ["様です", "様でした", "様"],
                "with_history": ["様", "様です", "様でした"]
            },
            "one_char": {
                "query": "さ",
                "no_history": ["様です", "様でした", "様"],
                "with_history": ["様", "様です", "様でした"]
            },
            "two_char": {
                "query": "さま",
                "no_history": ["様です", "様でした", "様"],
                "with_history": ["様", "様です", "様でした"]
            }
        },
        {
            "id": "ja_bi_007",
            "domain": "daily_greeting",
            "full_sentence": "お疲れ 様 です",
            "prev_word": "様",
            "target_word": "です",
            "target_reading": "です",
            "zero_char": {
                "no_history": ["です", "でした", "！"],
                "with_history": ["です", "でした", "！"]
            },
            "one_char": {
                "query": "で",
                "no_history": ["です", "でした", "でも"],
                "with_history": ["です", "でした", "でも"]
            },
            "two_char": {
                "query": "です",
                "no_history": ["です", "ですか", "ですよね"],
                "with_history": ["です", "ですか", "ですよね"]
            }
        },
        {
            "id": "ja_bi_008",
            "domain": "daily_polite",
            "full_sentence": "ありがとう ございます",
            "prev_word": "ありがとう",
            "target_word": "ございます",
            "target_reading": "ございます",
            "zero_char": {
                "no_history": ["ございます", "ございました", "！"],
                "with_history": ["ございます", "ございました", "！"]
            },
            "one_char": {
                "query": "ご",
                "no_history": ["ございます", "ございました", "座います"],
                "with_history": ["ございます", "ございました", "座います"]
            },
            "two_char": {
                "query": "ござ",
                "no_history": ["ございます", "ございました", "座います"],
                "with_history": ["ございます", "ございました", "座います"]
            }
        },
        {
            "id": "ja_bi_009",
            "domain": "business_apology",
            "full_sentence": "申し訳 ありません",
            "prev_word": "申し訳",
            "target_word": "ありません",
            "target_reading": "ありません",
            "zero_char": {
                "no_history": ["ございません", "ありません", "ない"],
                "with_history": ["ありません", "ございません", "ない"]
            },
            "one_char": {
                "query": "あ",
                "no_history": ["ありません", "ある", "あれば"],
                "with_history": ["ありません", "ある", "あれば"]
            },
            "two_char": {
                "query": "あり",
                "no_history": ["ありません", "ありがとうございます", "あります"],
                "with_history": ["ありません", "あります", "ありがとうございます"]
            }
        },
        {
            "id": "ja_bi_010",
            "domain": "weather_casual",
            "full_sentence": "今日 は 良い 天気 です",
            "prev_word": "今日",
            "target_word": "は",
            "target_reading": "は",
            "zero_char": {
                "no_history": ["は", "の", "も"],
                "with_history": ["は", "の", "も"]
            },
            "one_char": {
                "query": "は",
                "no_history": ["は", "早く", "花"],
                "with_history": ["は", "早く", "花"]
            },
            "two_char": {
                "query": "はい",
                "no_history": ["はい", "入って", "配達"],
                "with_history": ["はい", "配達", "入って"]
            }
        },
        {
            "id": "ja_bi_011",
            "domain": "weather_casual",
            "full_sentence": "今日 は 良い 天気 です",
            "prev_word": "良い",
            "target_word": "天気",
            "target_reading": "てんき",
            "zero_char": {
                "no_history": ["天気", "一日を", "人"],
                "with_history": ["天気", "一日を", "方向"]
            },
            "one_char": {
                "query": "て",
                "no_history": ["天気", "提案", "手紙"],
                "with_history": ["天気", "提案", "手紙"]
            },
            "two_char": {
                "query": "てん",
                "no_history": ["天気", "点", "展望"],
                "with_history": ["天気", "点", "展開"]
            }
        },
        {
            "id": "ja_bi_012",
            "domain": "scheduling",
            "full_sentence": "明日 の 会議 に 参加 します",
            "prev_word": "明日",
            "target_word": "の",
            "target_reading": "の",
            "zero_char": {
                "no_history": ["の", "は", "も"],
                "with_history": ["の", "は", "も"]
            },
            "one_char": {
                "query": "の",
                "no_history": ["の", "残して", "予定"],
                "with_history": ["の", "予定", "残して"]
            },
            "two_char": {
                "query": "のち",
                "no_history": ["のち", "後ほど", "後"],
                "with_history": ["のち", "後ほど", "後"]
            }
        },
        {
            "id": "ja_bi_013",
            "domain": "scheduling",
            "full_sentence": "明日 の 会議 に 参加 します",
            "prev_word": "の",
            "target_word": "会議",
            "target_reading": "かいぎ",
            "zero_char": {
                "no_history": ["件", "件名", "予定"],
                "with_history": ["会議", "件", "予定"]
            },
            "one_char": {
                "query": "か",
                "no_history": ["会議", "確認", "関係"],
                "with_history": ["会議", "確認", "関係"]
            },
            "two_char": {
                "query": "かい",
                "no_history": ["会議", "会社", "開始"],
                "with_history": ["会議", "会社", "開始"]
            }
        },
        {
            "id": "ja_bi_014",
            "domain": "scheduling",
            "full_sentence": "明日 の 会議 に 参加 します",
            "prev_word": "会議",
            "target_word": "に",
            "target_reading": "に",
            "zero_char": {
                "no_history": ["の", "に", "は"],
                "with_history": ["に", "の", "は"]
            },
            "one_char": {
                "query": "に",
                "no_history": ["について", "に関する", "における"],
                "with_history": ["に", "について", "に関する"]
            },
            "two_char": {
                "query": "につ",
                "no_history": ["について", "についても", "に関する"],
                "with_history": ["について", "についても", "に関する"]
            }
        },
        {
            "id": "ja_bi_015",
            "domain": "scheduling",
            "full_sentence": "明日 の 会議 に 参加 します",
            "prev_word": "に",
            "target_word": "参加",
            "target_reading": "さんか",
            "zero_char": {
                "no_history": ["関して", "ついて", "おいて"],
                "with_history": ["参加", "関して", "ついて"]
            },
            "one_char": {
                "query": "さ",
                "no_history": ["参加", "させて", "際して"],
                "with_history": ["参加", "させて", "際して"]
            },
            "two_char": {
                "query": "さん",
                "no_history": ["参加", "賛成", "参考"],
                "with_history": ["参加", "参考", "賛成"]
            }
        },
        {
            "id": "ja_bi_016",
            "domain": "business_report",
            "full_sentence": "資料 を 送信 しました",
            "prev_word": "資料",
            "target_word": "を",
            "target_reading": "を",
            "zero_char": {
                "no_history": ["を", "の", "作成"],
                "with_history": ["を", "の", "作成"]
            },
            "one_char": {
                "query": "を",
                "no_history": ["を", "お送り", "添付"],
                "with_history": ["を", "お送り", "添付"]
            },
            "two_char": {
                "query": "をお",
                "no_history": ["をお送りします", "をお願いします", "を送りいたします"],
                "with_history": ["をお送りします", "をお願いします", "を送りいたします"]
            }
        },
        {
            "id": "ja_bi_017",
            "domain": "business_report",
            "full_sentence": "資料 を 送信 しました",
            "prev_word": "を",
            "target_word": "送信",
            "target_reading": "そうしん",
            "zero_char": {
                "no_history": ["ご確認", "お送り", "作成"],
                "with_history": ["送信", "ご確認", "お送り"]
            },
            "one_char": {
                "query": "そ",
                "no_history": ["送信", "送付", "添えて"],
                "with_history": ["送信", "送付", "添えて"]
            },
            "two_char": {
                "query": "そう",
                "no_history": ["送信", "送付", "相談"],
                "with_history": ["送信", "送付", "早期"]
            }
        },
        {
            "id": "ja_bi_018",
            "domain": "business_request",
            "full_sentence": "ご 検討 ください",
            "prev_word": "ご",
            "target_word": "検討",
            "target_reading": "けんとう",
            "zero_char": {
                "no_history": ["連絡", "確認", "案内"],
                "with_history": ["検討", "連絡", "確認"]
            },
            "one_char": {
                "query": "け",
                "no_history": ["検討", "計画", "結果"],
                "with_history": ["検討", "計画", "結果"]
            },
            "two_char": {
                "query": "けん",
                "no_history": ["検討", "件名", "研究"],
                "with_history": ["検討", "件名", "研究"]
            }
        },
        {
            "id": "ja_bi_019",
            "domain": "business_request",
            "full_sentence": "ご 検討 ください",
            "prev_word": "検討",
            "target_word": "ください",
            "target_reading": "ください",
            "zero_char": {
                "no_history": ["のほどよろしくお願いいたします", "ください", "中"],
                "with_history": ["ください", "のほどよろしくお願いいたします", "中"]
            },
            "one_char": {
                "query": "く",
                "no_history": ["ください", "くださいますよう", "比べ"],
                "with_history": ["ください", "くださいますよう", "比べ"]
            },
            "two_char": {
                "query": "くだ",
                "no_history": ["ください", "くださいますよう", "くださり"],
                "with_history": ["ください", "くださいますよう", "くださり"]
            }
        },
        {
            "id": "ja_bi_020",
            "domain": "confirmation",
            "full_sentence": "問題 ありません",
            "prev_word": "問題",
            "target_word": "ありません",
            "target_reading": "ありません",
            "zero_char": {
                "no_history": ["ありません", "ない", "ですか"],
                "with_history": ["ありません", "ない", "ですか"]
            },
            "one_char": {
                "query": "あ",
                "no_history": ["ありません", "ある", "あれば"],
                "with_history": ["ありません", "ある", "あれば"]
            },
            "two_char": {
                "query": "あり",
                "no_history": ["ありません", "あります", "ありました"],
                "with_history": ["ありません", "あります", "ありました"]
            }
        },
        {
            "id": "ja_bi_021",
            "domain": "confirmation",
            "full_sentence": "了解 しました",
            "prev_word": "了解",
            "target_word": "しました",
            "target_reading": "しました",
            "zero_char": {
                "no_history": ["しました", "いたしました", "です"],
                "with_history": ["しました", "いたしました", "です"]
            },
            "one_char": {
                "query": "し",
                "no_history": ["しました", "します", "して"],
                "with_history": ["しました", "します", "して"]
            },
            "two_char": {
                "query": "しま",
                "no_history": ["しました", "します", "した"],
                "with_history": ["しました", "します", "した"]
            }
        },
        {
            "id": "ja_bi_022",
            "domain": "confirmation",
            "full_sentence": "承知 いたしました",
            "prev_word": "承知",
            "target_word": "いたしました",
            "target_reading": "いたしました",
            "zero_char": {
                "no_history": ["いたしました", "しました", "致しました"],
                "with_history": ["いたしました", "しました", "致しました"]
            },
            "one_char": {
                "query": "い",
                "no_history": ["いたしました", "致します", "いたします"],
                "with_history": ["いたしました", "いたします", "致します"]
            },
            "two_char": {
                "query": "いた",
                "no_history": ["いたしました", "いたします", "致しました"],
                "with_history": ["いたしました", "いたします", "致しました"]
            }
        },
        {
            "id": "ja_bi_023",
            "domain": "polite_waiting",
            "full_sentence": "お待たせ いたしました",
            "prev_word": "お待たせ",
            "target_word": "いたしました",
            "target_reading": "いたしました",
            "zero_char": {
                "no_history": ["いたしました", "しました", "して申し訳ございません"],
                "with_history": ["いたしました", "しました", "して申し訳ございません"]
            },
            "one_char": {
                "query": "い",
                "no_history": ["いたしました", "いたします", "致します"],
                "with_history": ["いたしました", "いたします", "致します"]
            },
            "two_char": {
                "query": "いた",
                "no_history": ["いたしました", "いたします", "致しました"],
                "with_history": ["いたしました", "いたします", "致しました"]
            }
        },
        {
            "id": "ja_bi_024",
            "domain": "communication",
            "full_sentence": "電話 します",
            "prev_word": "電話",
            "target_word": "します",
            "target_reading": "します",
            "zero_char": {
                "no_history": ["します", "いたします", "番号"],
                "with_history": ["します", "いたします", "番号"]
            },
            "one_char": {
                "query": "し",
                "no_history": ["します", "しました", "して"],
                "with_history": ["します", "しました", "して"]
            },
            "two_char": {
                "query": "しま",
                "no_history": ["します", "した", "しました"],
                "with_history": ["します", "した", "しました"]
            }
        },
        {
            "id": "ja_bi_025",
            "domain": "business_work",
            "full_sentence": "作業 を 進めます",
            "prev_word": "作業",
            "target_word": "を",
            "target_reading": "を",
            "zero_char": {
                "no_history": ["中", "を", "完了"],
                "with_history": ["を", "中", "完了"]
            },
            "one_char": {
                "query": "を",
                "no_history": ["を進めます", "をお願いします", "を行います"],
                "with_history": ["を", "を進めます", "をお願いします"]
            },
            "two_char": {
                "query": "を進",
                "no_history": ["を進めます", "を進めてまいります", "を進めております"],
                "with_history": ["を進めます", "を進めてまいります", "を進めております"]
            }
        },
        {
            "id": "ja_bi_026",
            "domain": "business_work",
            "full_sentence": "作業 を 進めます",
            "prev_word": "を",
            "target_word": "進めます",
            "target_reading": "すすめます",
            "zero_char": {
                "no_history": ["進めます", "行います", "開始します"],
                "with_history": ["進めます", "行います", "開始します"]
            },
            "one_char": {
                "query": "す",
                "no_history": ["進めます", "進めて", "すぐ"],
                "with_history": ["進めます", "進めて", "すぐ"]
            },
            "two_char": {
                "query": "すす",
                "no_history": ["進めます", "進めて", "進めさせていただきます"],
                "with_history": ["進めます", "進めて", "進めさせていただきます"]
            }
        },
        {
            "id": "ja_bi_027",
            "domain": "business_report",
            "full_sentence": "準備 が 完了 しました",
            "prev_word": "準備",
            "target_word": "が",
            "target_reading": "が",
            "zero_char": {
                "no_history": ["中", "が", "を"],
                "with_history": ["が", "中", "を"]
            },
            "one_char": {
                "query": "が",
                "no_history": ["完了しました", "整いました", "できました"],
                "with_history": ["が", "完了しました", "整いました"]
            },
            "two_char": {
                "query": "が完",
                "no_history": ["が完了しました", "が完了いたしました", "が完了次第"],
                "with_history": ["が完了しました", "が完了いたしました", "が完了次第"]
            }
        },
        {
            "id": "ja_bi_028",
            "domain": "business_report",
            "full_sentence": "準備 が 完了 しました",
            "prev_word": "が",
            "target_word": "完了",
            "target_reading": "かんりょう",
            "zero_char": {
                "no_history": ["完了", "必要", "可能"],
                "with_history": ["完了", "必要", "可能"]
            },
            "one_char": {
                "query": "か",
                "no_history": ["完了", "確認", "可能"],
                "with_history": ["完了", "確認", "可能"]
            },
            "two_char": {
                "query": "かん",
                "no_history": ["完了", "関係", "完了しました"],
                "with_history": ["完了", "完了しました", "関係"]
            }
        },
        {
            "id": "ja_bi_029",
            "domain": "inquiry",
            "full_sentence": "何 か 質問 は ありますか",
            "prev_word": "質問",
            "target_word": "は",
            "target_reading": "は",
            "zero_char": {
                "no_history": ["は", "が", "等"],
                "with_history": ["は", "が", "等"]
            },
            "one_char": {
                "query": "は",
                "no_history": ["ありますか", "ございませんか", "ない"],
                "with_history": ["は", "ありますか", "ございませんか"]
            },
            "two_char": {
                "query": "はあ",
                "no_history": ["ありますか", "あるか", "ありますでしょうか"],
                "with_history": ["ありますか", "ありますでしょうか", "あるか"]
            }
        },
        {
            "id": "ja_bi_030",
            "domain": "inquiry",
            "full_sentence": "何 か 質問 は ありますか",
            "prev_word": "は",
            "target_word": "ありますか",
            "target_reading": "ありますか",
            "zero_char": {
                "no_history": ["ありますか", "ございませんか", "どうでしょうか"],
                "with_history": ["ありますか", "ございませんか", "どうでしょうか"]
            },
            "one_char": {
                "query": "あ",
                "no_history": ["ありますか", "ある", "ありがとうございます"],
                "with_history": ["ありますか", "ある", "ありがとうございます"]
            },
            "two_char": {
                "query": "あり",
                "no_history": ["ありますか", "あります", "ありません"],
                "with_history": ["ありますか", "あります", "ありません"]
            }
        }
    ]

    english_cases = [
        {
            "id": "en_bi_001",
            "domain": "courtesy",
            "full_sentence": "Thank you very much",
            "prev_word": "Thank",
            "target_word": "you",
            "target_reading": "you",
            "zero_char": {
                "no_history": ["you", "God", "the"],
                "with_history": ["you", "your", "again"]
            },
            "one_char": {
                "query": "y",
                "no_history": ["you", "your", "yet"],
                "with_history": ["you", "your", "yes"]
            },
            "two_char": {
                "query": "yo",
                "no_history": ["you", "your", "yours"],
                "with_history": ["you", "your", "yours"]
            }
        },
        {
            "id": "en_bi_002",
            "domain": "courtesy",
            "full_sentence": "Thank you very much",
            "prev_word": "you",
            "target_word": "very",
            "target_reading": "very",
            "zero_char": {
                "no_history": ["are", "can", "so"],
                "with_history": ["very", "are", "so"]
            },
            "one_char": {
                "query": "v",
                "no_history": ["very", "via", "view"],
                "with_history": ["very", "via", "view"]
            },
            "two_char": {
                "query": "ve",
                "no_history": ["very", "version", "venue"],
                "with_history": ["very", "version", "venue"]
            }
        },
        {
            "id": "en_bi_003",
            "domain": "courtesy",
            "full_sentence": "Thank you very much",
            "prev_word": "very",
            "target_word": "much",
            "target_reading": "much",
            "zero_char": {
                "no_history": ["much", "good", "well"],
                "with_history": ["much", "well", "good"]
            },
            "one_char": {
                "query": "m",
                "no_history": ["much", "many", "more"],
                "with_history": ["much", "many", "more"]
            },
            "two_char": {
                "query": "mu",
                "no_history": ["much", "must", "music"],
                "with_history": ["much", "must", "music"]
            }
        },
        {
            "id": "en_bi_004",
            "domain": "request",
            "full_sentence": "Please let me know",
            "prev_word": "Please",
            "target_word": "let",
            "target_reading": "let",
            "zero_char": {
                "no_history": ["find", "let", "see"],
                "with_history": ["let", "find", "see"]
            },
            "one_char": {
                "query": "l",
                "no_history": ["let", "look", "like"],
                "with_history": ["let", "look", "like"]
            },
            "two_char": {
                "query": "le",
                "no_history": ["let", "leave", "learn"],
                "with_history": ["let", "leave", "less"]
            }
        },
        {
            "id": "en_bi_005",
            "domain": "request",
            "full_sentence": "Please let me know",
            "prev_word": "let",
            "target_word": "me",
            "target_reading": "me",
            "zero_char": {
                "no_history": ["me", "us", "you"],
                "with_history": ["me", "us", "you"]
            },
            "one_char": {
                "query": "m",
                "no_history": ["me", "make", "more"],
                "with_history": ["me", "my", "more"]
            },
            "two_char": {
                "query": "me",
                "no_history": ["me", "meet", "message"],
                "with_history": ["me", "meet", "men"]
            }
        },
        {
            "id": "en_bi_006",
            "domain": "request",
            "full_sentence": "Please let me know",
            "prev_word": "me",
            "target_word": "know",
            "target_reading": "know",
            "zero_char": {
                "no_history": ["know", "if", "a"],
                "with_history": ["know", "if", "a"]
            },
            "one_char": {
                "query": "k",
                "no_history": ["know", "keep", "key"],
                "with_history": ["know", "keep", "key"]
            },
            "two_char": {
                "query": "kn",
                "no_history": ["know", "knowledge", "known"],
                "with_history": ["know", "knowledge", "known"]
            }
        },
        {
            "id": "en_bi_007",
            "domain": "casual_greeting",
            "full_sentence": "How are you doing",
            "prev_word": "How",
            "target_word": "are",
            "target_reading": "are",
            "zero_char": {
                "no_history": ["are", "to", "is"],
                "with_history": ["are", "to", "is"]
            },
            "one_char": {
                "query": "a",
                "no_history": ["are", "about", "all"],
                "with_history": ["are", "about", "all"]
            },
            "two_char": {
                "query": "ar",
                "no_history": ["are", "around", "area"],
                "with_history": ["are", "around", "area"]
            }
        },
        {
            "id": "en_bi_008",
            "domain": "casual_greeting",
            "full_sentence": "How are you doing",
            "prev_word": "are",
            "target_word": "you",
            "target_reading": "you",
            "zero_char": {
                "no_history": ["you", "the", "not"],
                "with_history": ["you", "your", "not"]
            },
            "one_char": {
                "query": "y",
                "no_history": ["you", "your", "yet"],
                "with_history": ["you", "your", "yes"]
            },
            "two_char": {
                "query": "yo",
                "no_history": ["you", "your", "yours"],
                "with_history": ["you", "your", "yours"]
            }
        },
        {
            "id": "en_bi_009",
            "domain": "casual_greeting",
            "full_sentence": "How are you doing",
            "prev_word": "you",
            "target_word": "doing",
            "target_reading": "doing",
            "zero_char": {
                "no_history": ["doing", "are", "have"],
                "with_history": ["doing", "are", "have"]
            },
            "one_char": {
                "query": "d",
                "no_history": ["doing", "do", "don't"],
                "with_history": ["doing", "do", "done"]
            },
            "two_char": {
                "query": "do",
                "no_history": ["doing", "do", "done"],
                "with_history": ["doing", "do", "done"]
            }
        },
        {
            "id": "en_bi_010",
            "domain": "future_promise",
            "full_sentence": "I will be there soon",
            "prev_word": "I",
            "target_word": "will",
            "target_reading": "will",
            "zero_char": {
                "no_history": ["have", "am", "will"],
                "with_history": ["will", "have", "am"]
            },
            "one_char": {
                "query": "w",
                "no_history": ["will", "was", "would"],
                "with_history": ["will", "was", "would"]
            },
            "two_char": {
                "query": "wi",
                "no_history": ["will", "with", "wish"],
                "with_history": ["will", "with", "wish"]
            }
        },
        {
            "id": "en_bi_011",
            "domain": "future_promise",
            "full_sentence": "I will be there soon",
            "prev_word": "will",
            "target_word": "be",
            "target_reading": "be",
            "zero_char": {
                "no_history": ["be", "have", "not"],
                "with_history": ["be", "have", "not"]
            },
            "one_char": {
                "query": "b",
                "no_history": ["be", "by", "back"],
                "with_history": ["be", "back", "by"]
            },
            "two_char": {
                "query": "be",
                "no_history": ["be", "been", "better"],
                "with_history": ["be", "been", "become"]
            }
        },
        {
            "id": "en_bi_012",
            "domain": "future_promise",
            "full_sentence": "I will be there soon",
            "prev_word": "be",
            "target_word": "there",
            "target_reading": "there",
            "zero_char": {
                "no_history": ["able", "a", "in"],
                "with_history": ["there", "able", "a"]
            },
            "one_char": {
                "query": "t",
                "no_history": ["the", "there", "to"],
                "with_history": ["there", "the", "to"]
            },
            "two_char": {
                "query": "th",
                "no_history": ["the", "there", "that"],
                "with_history": ["there", "the", "that"]
            }
        },
        {
            "id": "en_bi_013",
            "domain": "future_promise",
            "full_sentence": "I will be there soon",
            "prev_word": "there",
            "target_word": "soon",
            "target_reading": "soon",
            "zero_char": {
                "no_history": ["is", "are", "for"],
                "with_history": ["soon", "is", "are"]
            },
            "one_char": {
                "query": "s",
                "no_history": ["so", "soon", "still"],
                "with_history": ["soon", "so", "still"]
            },
            "two_char": {
                "query": "so",
                "no_history": ["so", "soon", "some"],
                "with_history": ["soon", "so", "some"]
            }
        },
        {
            "id": "en_bi_014",
            "domain": "social_greeting",
            "full_sentence": "Looking forward to seeing you",
            "prev_word": "Looking",
            "target_word": "forward",
            "target_reading": "forward",
            "zero_char": {
                "no_history": ["forward", "at", "for"],
                "with_history": ["forward", "at", "for"]
            },
            "one_char": {
                "query": "f",
                "no_history": ["forward", "for", "from"],
                "with_history": ["forward", "for", "from"]
            },
            "two_char": {
                "query": "fo",
                "no_history": ["forward", "for", "follow"],
                "with_history": ["forward", "for", "follow"]
            }
        },
        {
            "id": "en_bi_015",
            "domain": "social_greeting",
            "full_sentence": "Looking forward to seeing you",
            "prev_word": "forward",
            "target_word": "to",
            "target_reading": "to",
            "zero_char": {
                "no_history": ["to", "and", "with"],
                "with_history": ["to", "and", "with"]
            },
            "one_char": {
                "query": "t",
                "no_history": ["to", "the", "that"],
                "with_history": ["to", "the", "that"]
            },
            "two_char": {
                "query": "to",
                "no_history": ["to", "today", "tomorrow"],
                "with_history": ["to", "today", "tomorrow"]
            }
        },
        {
            "id": "en_bi_016",
            "domain": "social_greeting",
            "full_sentence": "Looking forward to seeing you",
            "prev_word": "to",
            "target_word": "seeing",
            "target_reading": "seeing",
            "zero_char": {
                "no_history": ["the", "see", "hearing"],
                "with_history": ["seeing", "see", "hearing"]
            },
            "one_char": {
                "query": "s",
                "no_history": ["see", "seeing", "start"],
                "with_history": ["seeing", "see", "start"]
            },
            "two_char": {
                "query": "se",
                "no_history": ["see", "seeing", "send"],
                "with_history": ["seeing", "see", "send"]
            }
        },
        {
            "id": "en_bi_017",
            "domain": "daily_courtesy",
            "full_sentence": "Have a great day",
            "prev_word": "Have",
            "target_word": "a",
            "target_reading": "a",
            "zero_char": {
                "no_history": ["a", "been", "to"],
                "with_history": ["a", "been", "to"]
            },
            "one_char": {
                "query": "a",
                "no_history": ["a", "all", "any"],
                "with_history": ["a", "all", "any"]
            },
            "two_char": {
                "query": "an",
                "no_history": ["an", "and", "another"],
                "with_history": ["an", "and", "another"]
            }
        },
        {
            "id": "en_bi_018",
            "domain": "daily_courtesy",
            "full_sentence": "Have a great day",
            "prev_word": "a",
            "target_word": "great",
            "target_reading": "great",
            "zero_char": {
                "no_history": ["great", "good", "lot"],
                "with_history": ["great", "good", "lot"]
            },
            "one_char": {
                "query": "g",
                "no_history": ["great", "good", "go"],
                "with_history": ["great", "good", "group"]
            },
            "two_char": {
                "query": "gr",
                "no_history": ["great", "group", "ground"],
                "with_history": ["great", "group", "ground"]
            }
        },
        {
            "id": "en_bi_019",
            "domain": "daily_courtesy",
            "full_sentence": "Have a great day",
            "prev_word": "great",
            "target_word": "day",
            "target_reading": "day",
            "zero_char": {
                "no_history": ["day", "time", "week"],
                "with_history": ["day", "time", "weekend"]
            },
            "one_char": {
                "query": "d",
                "no_history": ["day", "deal", "difference"],
                "with_history": ["day", "deal", "difference"]
            },
            "two_char": {
                "query": "da",
                "no_history": ["day", "days", "daily"],
                "with_history": ["day", "daily", "days"]
            }
        },
        {
            "id": "en_bi_020",
            "domain": "work_action",
            "full_sentence": "Let me check the details",
            "prev_word": "check",
            "target_word": "the",
            "target_reading": "the",
            "zero_char": {
                "no_history": ["the", "it", "out"],
                "with_history": ["the", "out", "it"]
            },
            "one_char": {
                "query": "t",
                "no_history": ["the", "this", "that"],
                "with_history": ["the", "this", "that"]
            },
            "two_char": {
                "query": "th",
                "no_history": ["the", "this", "that"],
                "with_history": ["the", "this", "that"]
            }
        },
        {
            "id": "en_bi_021",
            "domain": "work_action",
            "full_sentence": "Let me check the details",
            "prev_word": "the",
            "target_word": "details",
            "target_reading": "details",
            "zero_char": {
                "no_history": ["same", "first", "new"],
                "with_history": ["details", "same", "first"]
            },
            "one_char": {
                "query": "d",
                "no_history": ["data", "difference", "details"],
                "with_history": ["details", "data", "difference"]
            },
            "two_char": {
                "query": "de",
                "no_history": ["details", "detail", "development"],
                "with_history": ["details", "detail", "development"]
            }
        },
        {
            "id": "en_bi_022",
            "domain": "social_check",
            "full_sentence": "I hope you are well",
            "prev_word": "hope",
            "target_word": "you",
            "target_reading": "you",
            "zero_char": {
                "no_history": ["you", "this", "to"],
                "with_history": ["you", "this", "to"]
            },
            "one_char": {
                "query": "y",
                "no_history": ["you", "your", "yes"],
                "with_history": ["you", "your", "yes"]
            },
            "two_char": {
                "query": "yo",
                "no_history": ["you", "your", "yours"],
                "with_history": ["you", "your", "yours"]
            }
        },
        {
            "id": "en_bi_023",
            "domain": "social_check",
            "full_sentence": "I hope you are well",
            "prev_word": "are",
            "target_word": "well",
            "target_reading": "well",
            "zero_char": {
                "no_history": ["doing", "going", "well"],
                "with_history": ["well", "doing", "going"]
            },
            "one_char": {
                "query": "w",
                "no_history": ["well", "with", "working"],
                "with_history": ["well", "working", "with"]
            },
            "two_char": {
                "query": "we",
                "no_history": ["well", "welcome", "went"],
                "with_history": ["well", "welcome", "week"]
            }
        },
        {
            "id": "en_bi_024",
            "domain": "assistance",
            "full_sentence": "Can you help me with this",
            "prev_word": "help",
            "target_word": "me",
            "target_reading": "me",
            "zero_char": {
                "no_history": ["me", "you", "us"],
                "with_history": ["me", "you", "us"]
            },
            "one_char": {
                "query": "m",
                "no_history": ["me", "make", "my"],
                "with_history": ["me", "my", "more"]
            },
            "two_char": {
                "query": "me",
                "no_history": ["me", "meet", "men"],
                "with_history": ["me", "meet", "men"]
            }
        },
        {
            "id": "en_bi_025",
            "domain": "assistance",
            "full_sentence": "Can you help me with this",
            "prev_word": "with",
            "target_word": "this",
            "target_reading": "this",
            "zero_char": {
                "no_history": ["the", "a", "this"],
                "with_history": ["this", "the", "a"]
            },
            "one_char": {
                "query": "t",
                "no_history": ["the", "this", "that"],
                "with_history": ["this", "the", "that"]
            },
            "two_char": {
                "query": "th",
                "no_history": ["this", "the", "that"],
                "with_history": ["this", "the", "that"]
            }
        },
        {
            "id": "en_bi_026",
            "domain": "scheduling",
            "full_sentence": "See you tomorrow morning",
            "prev_word": "See",
            "target_word": "you",
            "target_reading": "you",
            "zero_char": {
                "no_history": ["you", "the", "what"],
                "with_history": ["you", "the", "again"]
            },
            "one_char": {
                "query": "y",
                "no_history": ["you", "your", "yet"],
                "with_history": ["you", "your", "yes"]
            },
            "two_char": {
                "query": "yo",
                "no_history": ["you", "your", "yours"],
                "with_history": ["you", "your", "yours"]
            }
        },
        {
            "id": "en_bi_027",
            "domain": "scheduling",
            "full_sentence": "See you tomorrow morning",
            "prev_word": "you",
            "target_word": "tomorrow",
            "target_reading": "tomorrow",
            "zero_char": {
                "no_history": ["soon", "there", "later"],
                "with_history": ["tomorrow", "soon", "later"]
            },
            "one_char": {
                "query": "t",
                "no_history": ["there", "tomorrow", "then"],
                "with_history": ["tomorrow", "there", "then"]
            },
            "two_char": {
                "query": "to",
                "no_history": ["tomorrow", "tonight", "today"],
                "with_history": ["tomorrow", "today", "tonight"]
            }
        },
        {
            "id": "en_bi_028",
            "domain": "scheduling",
            "full_sentence": "See you tomorrow morning",
            "prev_word": "tomorrow",
            "target_word": "morning",
            "target_reading": "morning",
            "zero_char": {
                "no_history": ["morning", "at", "night"],
                "with_history": ["morning", "at", "night"]
            },
            "one_char": {
                "query": "m",
                "no_history": ["morning", "meeting", "more"],
                "with_history": ["morning", "meeting", "more"]
            },
            "two_char": {
                "query": "mo",
                "no_history": ["morning", "more", "most"],
                "with_history": ["morning", "more", "most"]
            }
        },
        {
            "id": "en_bi_029",
            "domain": "apology",
            "full_sentence": "I am sorry for the delay",
            "prev_word": "sorry",
            "target_word": "for",
            "target_reading": "for",
            "zero_char": {
                "no_history": ["for", "to", "about"],
                "with_history": ["for", "to", "about"]
            },
            "one_char": {
                "query": "f",
                "no_history": ["for", "from", "first"],
                "with_history": ["for", "from", "first"]
            },
            "two_char": {
                "query": "fo",
                "no_history": ["for", "following", "forward"],
                "with_history": ["for", "forward", "following"]
            }
        },
        {
            "id": "en_bi_030",
            "domain": "apology",
            "full_sentence": "I am sorry for the delay",
            "prev_word": "the",
            "target_word": "delay",
            "target_reading": "delay",
            "zero_char": {
                "no_history": ["first", "inconvenience", "late"],
                "with_history": ["delay", "inconvenience", "first"]
            },
            "one_char": {
                "query": "d",
                "no_history": ["delay", "difference", "day"],
                "with_history": ["delay", "difference", "damage"]
            },
            "two_char": {
                "query": "de",
                "no_history": ["delay", "details", "decision"],
                "with_history": ["delay", "details", "decision"]
            }
        }
    ]

    golden_set = {
        "metadata": {
            "version": "1.0.0",
            "description": "Standard Reference Bigram Prediction Golden Set for Contextual & History-Aware Evaluation",
            "total_cases": len(japanese_cases) + len(english_cases),
            "languages": {
                "ja": {
                    "count": len(japanese_cases),
                    "description": "Japanese bigram next-phrase and prefix predictions"
                },
                "en": {
                    "count": len(english_cases),
                    "description": "English bigram next-word and prefix predictions"
                }
            },
            "evaluation_axes": [
                "zero_char_no_history_hit_rate_at_3",
                "zero_char_with_history_hit_rate_at_3",
                "one_char_no_history_hit_rate_at_3",
                "one_char_with_history_hit_rate_at_3",
                "two_char_no_history_hit_rate_at_3",
                "two_char_with_history_hit_rate_at_3"
            ]
        },
        "japanese": japanese_cases,
        "english": english_cases
    }

    return golden_set

def main():
    golden_set = build_golden_set()
    
    paths = [
        "app/src/test/resources/prediction_eval/reference_bigram_prediction_golden_set.json",
        "tools/eval/reference_bigram_prediction_golden_set.json"
    ]
    
    for path in paths:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(golden_set, f, ensure_ascii=False, indent=2)
        print(f"Written: {path} ({len(golden_set['japanese'])} ja + {len(golden_set['english'])} en cases)")

if __name__ == "__main__":
    main()
