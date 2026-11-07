#!/usr/bin/env python3
"""
evaluate_context_length_tradeoff.py

Comprehensive evaluation of context length (token budget) and boundary segmentation
trade-offs across Japanese Flick, English QWERTY, and bilingual code-switching inputs.
Evaluates:
  1. Top-1 Accuracy / Top-3 Recall / MRR
  2. Gold Negative Log-Likelihood (NLL) and Discriminative Margin (Delta NLL)
  3. Context Token Count and Evaluation Latency (Cold Prefill vs. Incremental Warm)
Across:
  - Token budgets: 0 (context-free), 4, 8, 12, 16, 24, 32, 48, 64, 96, 128 tokens
  - Segmentation strategies:
      a) naive_char: Naive character-count truncation (causing mid-word / orphan morpheme breaks)
      b) sentence_clause_only: Snap to punctuation only, fallback to naive
      c) universal_structural: Universal 3-tier boundary alignment (Sentence -> Clause -> Free Morpheme Onset)
"""

import json
import os
import re
import subprocess
import sys
import time

# ---------------------------------------------------------------------------
# Universal Context Trimmer (Python Reference Implementation)
# ---------------------------------------------------------------------------

class UniversalContextTrimmer:
    SENTENCE_TERMINALS = set('。．.！？!?\n\r')
    CLAUSE_DELIMITERS = set('、，,；;:：—()（）[]「」『』【】"\'')

    # Abbreviations not to be treated as sentence ends
    ABBREVIATIONS = {
        'mr.', 'mrs.', 'ms.', 'dr.', 'prof.', 'sr.', 'jr.', 'vs.', 'etc.',
        'e.g.', 'i.e.', 'u.s.', 'inc.', 'ltd.', 'co.', 'corp.', 'dept.', 'fig.', 'no.'
    }

    @staticmethod
    def estimate_token_count(text: str) -> float:
        """Language-agnostic token count estimate."""
        if not text:
            return 0.0
        ascii_chars = 0
        cjk_chars = 0
        for ch in text:
            code = ord(ch)
            if code < 128:
                ascii_chars += 1
            else:
                cjk_chars += 1
        # Approx: English/ASCII ~ 4.2 chars/token, CJK ~ 1.3 chars/token
        return (ascii_chars / 4.2) + (cjk_chars / 1.3)

    @classmethod
    def is_sentence_terminal(cls, text: str, idx: int) -> bool:
        ch = text[idx]
        if ch not in cls.SENTENCE_TERMINALS:
            return False
        if ch == '.':
            # Check for abbreviation or decimal number
            # e.g., "3.14"
            if idx > 0 and idx + 1 < len(text) and text[idx - 1].isdigit() and text[idx + 1].isdigit():
                return False
            # Check preceding word for abbreviation
            start = idx - 1
            while start >= 0 and (text[start].isalpha() or text[start] == '.'):
                start -= 1
            word = text[start + 1:idx + 1].lower()
            if word in cls.ABBREVIATIONS:
                return False
        return True

    @classmethod
    def get_char_class(cls, ch: str) -> str:
        code = ord(ch)
        if (65 <= code <= 90) or (97 <= code <= 122) or (48 <= code <= 57) or ch == "'":
            return 'LATIN'
        if 0x3040 <= code <= 0x309F:
            return 'HIRAGANA'
        if 0x30A0 <= code <= 0x30FF or ch == 'ー':
            return 'KATAKANA'
        if 0x4E00 <= code <= 0x9FFF or 0x3400 <= code <= 0x4DBF:
            return 'KANJI'
        if ch.isspace():
            return 'SPACE'
        return 'OTHER'

    @classmethod
    def trim(cls, text: str, target_tokens: int, strategy: str = 'universal_structural') -> str:
        if not text or target_tokens <= 0:
            return ""

        total_tokens = cls.estimate_token_count(text)
        if total_tokens <= target_tokens:
            # Entire text fits in budget
            return text.lstrip()

        # Target character window estimation
        min_tokens = max(1, int(target_tokens * 0.6))
        max_tokens = int(target_tokens * 1.3)

        n = len(text)

        if strategy == 'naive_char':
            # Estimate char length directly and slice from end
            # Average 2.5 chars per token across mixed text
            char_budget = max(1, int(target_tokens * 2.5))
            cut_idx = max(0, n - char_budget)
            return text[cut_idx:]

        # For boundary-aware strategies, find candidate cut indices
        candidates = []
        for i in range(n - 1, -1, -1):
            tail = text[i:]
            toks = cls.estimate_token_count(tail)
            if toks < min_tokens:
                continue
            if toks > max_tokens:
                break

            # Evaluate cut position i (meaning text[i:] will be kept)
            prev_ch = text[i - 1] if i > 0 else ''
            curr_ch = text[i]

            # Tier 1: Sentence terminal
            if i > 0 and cls.is_sentence_terminal(text, i - 1):
                candidates.append((1, abs(toks - target_tokens), i))
                continue

            # Tier 2: Clause delimiter
            if i > 0 and prev_ch in cls.CLAUSE_DELIMITERS:
                candidates.append((2, abs(toks - target_tokens), i))
                continue

            if strategy == 'sentence_clause_only':
                continue

            # Tier 3: Universal Free-Morpheme / Word Onset
            prev_class = cls.get_char_class(prev_ch) if i > 0 else 'START'
            curr_class = cls.get_char_class(curr_ch)

            # Never cut inside Latin word (e.g. "re|garding")
            if prev_class == 'LATIN' and curr_class == 'LATIN':
                continue
            # Never cut inside Kanji compound (e.g. "販|売")
            if prev_class == 'KANJI' and curr_class == 'KANJI':
                continue
            # Never cut inside Katakana word (e.g. "プロ|ジェクト")
            if prev_class == 'KATAKANA' and curr_class == 'KATAKANA':
                continue

            # Valid word boundaries:
            # 1. After space in Latin text (word onset)
            if prev_class == 'SPACE' and curr_class == 'LATIN':
                # Avoid English clitics ('ll, 've, 're, 'd, n't)
                tail_str = text[i:]
                if not any(tail_str.startswith(cl) for cl in ["'ll", "'ve", "'re", "'d", "n't", "'s"]):
                    candidates.append((3, abs(toks - target_tokens), i))
                    continue

            # 2. Japanese Bunsetsu Onset: Hiragana (particle/auxiliary) -> Kanji/Katakana/Latin (content word)
            if prev_class == 'HIRAGANA' and curr_class in ('KANJI', 'KATAKANA', 'LATIN'):
                candidates.append((3, abs(toks - target_tokens), i))
                continue

            # 3. Delimiter onset (e.g. opening bracket)
            if curr_ch in '「『（([{"\'':
                candidates.append((3, abs(toks - target_tokens), i))
                continue

        if not candidates:
            # Fallback
            char_budget = max(1, int(target_tokens * 2.5))
            cut_idx = max(0, n - char_budget)
            # Ensure we don't cut inside Latin word if possible
            while cut_idx < n and cls.get_char_class(text[cut_idx]) == 'LATIN' and cut_idx > 0 and cls.get_char_class(text[cut_idx - 1]) == 'LATIN':
                cut_idx += 1
            return text[cut_idx:].lstrip()

        # Sort candidates by (Tier ascending, token distance ascending)
        candidates.sort(key=lambda c: (c[0], c[1]))
        best_cut = candidates[0][2]
        res = text[best_cut:].lstrip()
        return res

# ---------------------------------------------------------------------------
# Benchmark Dataset: 4 Comprehensive Categories (50 representative items)
# ---------------------------------------------------------------------------

BENCHMARK_ITEMS = [
    # -----------------------------------------------------------------------
    # Category 1: Japanese Homophone & Conversational Disambiguation (Long Context)
    # -----------------------------------------------------------------------
    {
        "id": "ja_homo_01",
        "category": "ja_homophone",
        "context_full": "先ほど人事部より四半期の組織改編に関する通達がありました。来月からの営業部への",
        "input": "いどう",
        "gold": "異動",
        "competitor": "移動",
        "all_cands": ["異動", "移動", "威道", "居留"]
    },
    {
        "id": "ja_homo_02",
        "category": "ja_homophone",
        "context_full": "東京での打ち合わせが長引いたため、東京駅から新幹線を使って新大阪へ",
        "input": "いどう",
        "gold": "移動",
        "competitor": "異動",
        "all_cands": ["移動", "異動", "居留", "威道"]
    },
    {
        "id": "ja_homo_03",
        "category": "ja_homophone",
        "context_full": "いつも大変お世話になっております。平素は格別の高配を賜り厚く御礼申し上げます。さて、",
        "input": "きしゃ",
        "gold": "貴社",
        "competitor": "記者",
        "all_cands": ["貴社", "記者", "帰社", "汽車"]
    },
    {
        "id": "ja_homo_04",
        "category": "ja_homophone",
        "context_full": "現場の生々しい実態を世間に伝えるため、事件現場の最前線を取材する日経新聞の",
        "input": "きしゃ",
        "gold": "記者",
        "competitor": "貴社",
        "all_cands": ["記者", "貴社", "帰社", "汽車"]
    },
    {
        "id": "ja_homo_05",
        "category": "ja_homophone",
        "context_full": "先週実施したユーザーアンケートの集計が完了しました。お客様から寄せられた貴重なご意見への",
        "input": "かいとう",
        "gold": "回答",
        "competitor": "解答",
        "all_cands": ["回答", "解答", "解凍", "会頭"]
    },
    {
        "id": "ja_homo_06",
        "category": "ja_homophone",
        "context_full": "数学の入学試験問題において、受験生がつまずきやすい難問の模範的な",
        "input": "かいとう",
        "gold": "解答",
        "competitor": "回答",
        "all_cands": ["解答", "回答", "解凍", "会頭"]
    },
    {
        "id": "ja_homo_07",
        "category": "ja_homophone",
        "context_full": "新素材の高分子化合物を合成するため、大学の研究室で日夜行われている実験と有機",
        "input": "かがく",
        "gold": "化学",
        "competitor": "科学",
        "all_cands": ["化学", "科学", "加害", "下顎"]
    },
    {
        "id": "ja_homo_08",
        "category": "ja_homophone",
        "context_full": "宇宙物理学や素粒子論の研究が進展し、現代の最先端テクノロジーと自然",
        "input": "かがく",
        "gold": "科学",
        "competitor": "化学",
        "all_cands": ["科学", "化学", "加害", "下顎"]
    },
    {
        "id": "ja_homo_09",
        "category": "ja_homophone",
        "context_full": "昨日からチーム全体で協議を重ねてきた新機能のリリース判定について、議論の最終的な",
        "input": "けっか",
        "gold": "結果",
        "competitor": "結界",
        "all_cands": ["結果", "結界", "欠陥", "血禍"]
    },
    {
        "id": "ja_homo_10",
        "category": "ja_homophone",
        "context_full": "データベースの保守作業に伴い、不要になった古いログファイルを安全に",
        "input": "さくじょ",
        "gold": "削除",
        "competitor": "策所",
        "all_cands": ["削除", "策所", "作", "策"]
    },
    {
        "id": "ja_homo_11",
        "category": "ja_homophone",
        "context_full": "朝から雲ひとつない青空が広がり、本日の東京地方は絶好のお出かけ日和で大変素晴らしい",
        "input": "てんき",
        "gold": "天気",
        "competitor": "転機",
        "all_cands": ["天気", "転機", "転記", "典拠"]
    },
    {
        "id": "ja_homo_12",
        "category": "ja_homophone",
        "context_full": "人生の重大な岐路に立ち、これからのキャリアを大きく左右する決断の",
        "input": "てんき",
        "gold": "転機",
        "competitor": "天気",
        "all_cands": ["転機", "天気", "転記", "典拠"]
    },

    # -----------------------------------------------------------------------
    # Category 2: English QWERTY Contextual Typo, Completion & Homophone
    # -----------------------------------------------------------------------
    {
        "id": "en_qwerty_01",
        "category": "en_qwerty",
        "context_full": "Thank you for following up on the project status. Regarding the timeline, I ",
        "input": "wi",
        "gold": "will",
        "competitor": "UI",
        "all_cands": ["will", "with", "wish", "win", "UI"]
    },
    {
        "id": "en_qwerty_02",
        "category": "en_qwerty",
        "context_full": "We appreciate your kind consideration and look forward to ",
        "input": "hea",
        "gold": "hearing",
        "competitor": "heating",
        "all_cands": ["hearing", "heating", "heading", "heavy"]
    },
    {
        "id": "en_qwerty_03",
        "category": "en_qwerty",
        "context_full": "Before the executive committee begins tomorrow morning, please send me the annual ",
        "input": "rep",
        "gold": "report",
        "competitor": "reply",
        "all_cands": ["report", "reply", "representative", "republic"]
    },
    {
        "id": "en_qwerty_04",
        "category": "en_qwerty",
        "context_full": "The students submitted their essays yesterday, and the professor gave high praise to ",
        "input": "thei",
        "gold": "their",
        "competitor": "there",
        "all_cands": ["their", "there", "they're"]
    },
    {
        "id": "en_qwerty_05",
        "category": "en_qwerty",
        "context_full": "We arrived at the designated venue early, but nobody was ",
        "input": "ther",
        "gold": "there",
        "competitor": "their",
        "all_cands": ["there", "their", "they're"]
    },
    {
        "id": "en_qwerty_06",
        "category": "en_qwerty",
        "context_full": "The unexpected changes in interest rates will directly ",
        "input": "aff",
        "gold": "affect",
        "competitor": "effect",
        "all_cands": ["affect", "effect", "afford", "affirm"]
    },
    {
        "id": "en_qwerty_07",
        "category": "en_qwerty",
        "context_full": "The medication had an immediate and noticeable positive ",
        "input": "eff",
        "gold": "effect",
        "competitor": "affect",
        "all_cands": ["effect", "affect", "effort", "efficacy"]
    },
    {
        "id": "en_qwerty_08",
        "category": "en_qwerty",
        "context_full": "Let's schedule the next sprint planning session. I will see you on ",
        "input": "mon",
        "gold": "Monday",
        "competitor": "monday",
        "all_cands": ["Monday", "monday", "month", "money"]
    },
    {
        "id": "en_qwerty_09",
        "category": "en_qwerty",
        "context_full": "The flight was delayed due to severe thunderstorms across the United States of ",
        "input": "ame",
        "gold": "America",
        "competitor": "america",
        "all_cands": ["America", "america", "amenity", "amend"]
    },
    {
        "id": "en_qwerty_10",
        "category": "en_qwerty",
        "context_full": "If you don't mind waiting for a couple of minutes, I'd like to have a quick ",
        "input": "dis",
        "gold": "discussion",
        "competitor": "distance",
        "all_cands": ["discussion", "distance", "disease", "discount"]
    },

    # -----------------------------------------------------------------------
    # Category 3: Bilingual Code-Switching (Cross-Lingual Context)
    # -----------------------------------------------------------------------
    {
        "id": "code_switch_01",
        "category": "code_switching",
        "context_full": "GitHubのレポジトリに新しい変更をpushしました。チームメンバー全員で速やかにプルリクエストの",
        "input": "review",
        "gold": "review",
        "competitor": "Review",
        "all_cands": ["review", "Review", "revise", "revenue"]
    },
    {
        "id": "code_switch_02",
        "category": "code_switching",
        "context_full": "マイクロサービスの連携テストを実施するため、バックエンドサーバーの新しいREST ",
        "input": "api",
        "gold": "API",
        "competitor": "Api",
        "all_cands": ["API", "Api", "api", "apple"]
    },
    {
        "id": "code_switch_03",
        "category": "code_switching",
        "context_full": "We just deployed the latest backend release to the production environment. 動作確認の",
        "input": "けっか",
        "gold": "結果",
        "competitor": "結界",
        "all_cands": ["結果", "結界", "欠陥"]
    },
    {
        "id": "code_switch_04",
        "category": "code_switching",
        "context_full": "Please check the official documentation on Android NDK and JNI bindings. 詳細な仕様を",
        "input": "かくにん",
        "gold": "確認",
        "competitor": "確任",
        "all_cands": ["確認", "確任", "確信"]
    },

    # -----------------------------------------------------------------------
    # Category 4: Boundary Stress Sensitivity (Corruption Sensitivity Suite)
    # -----------------------------------------------------------------------
    {
        "id": "stress_en_01",
        "category": "stress_sensitivity",
        "context_full": "We received an important communication from human resources regarding the upcoming ",
        "input": "con",
        "gold": "conference",
        "competitor": "condition",
        "all_cands": ["conference", "condition", "contact", "control"]
    },
    {
        "id": "stress_en_02",
        "category": "stress_sensitivity",
        "context_full": "The system crashed unexpectedly because of a catastrophic hardware failure in the main ",
        "input": "ser",
        "gold": "server",
        "competitor": "service",
        "all_cands": ["server", "service", "series", "serial"]
    },
    {
        "id": "stress_ja_01",
        "category": "stress_sensitivity",
        "context_full": "昨日の役員会議において詳細に議論された今後の海外展開および新規事業の",
        "input": "せんりゃく",
        "gold": "戦略",
        "competitor": "先略",
        "all_cands": ["戦略", "先略", "線略", "占略"]
    },
    {
        "id": "stress_ja_02",
        "category": "stress_sensitivity",
        "context_full": "次世代モビリティ社会の実現に向け、大手自動車メーカー各社が共同で推進する大規模な",
        "input": "ぷろじぇくと",
        "gold": "プロジェクト",
        "competitor": "プロジエクト",
        "all_cands": ["プロジェクト", "プロジエクト", "プロジェク"]
    }
]

# ---------------------------------------------------------------------------
# SLM Scorer Client Wrapper
# ---------------------------------------------------------------------------

class SlmScorerClient:
    def __init__(self, cli_path='./tools/slm_scorer_cli'):
        self.proc = subprocess.Popen(
            [cli_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        line = self.proc.stderr.readline()
        if "Model initialized successfully" not in line:
            raise RuntimeError(f"SLM Scorer failed to initialize: {line}")
        print(f"SLM Scorer initialized: {line.strip()}")
        self.counter = 0

    def score(self, context: str, candidates: list):
        if not candidates:
            return [], 0.0
        self.counter += 1
        req_id = str(self.counter)
        req_line = f"{req_id}\t{context}\t" + "\t".join(candidates) + "\n"
        self.proc.stdin.write(req_line)
        self.proc.stdin.flush()

        resp_line = self.proc.stdout.readline().strip()
        parts = resp_line.split('\t')
        if not parts or parts[0] != req_id:
            raise RuntimeError(f"SLM Scorer sync error: expected {req_id}, got {resp_line}")

        lat_ms = float(parts[1]) if len(parts) > 1 else 0.0
        logprobs = [float(p) for p in parts[2:]]
        return logprobs, lat_ms

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()

# ---------------------------------------------------------------------------
# Evaluation Runner
# ---------------------------------------------------------------------------

def run_evaluation():
    print("=" * 75)
    print("   Cross-Lingual Context Length & Segmentation Trade-Off Evaluation")
    print("   (Model: Qwen2.5-0.5B-Instruct-Q4_K_M via tools/slm_scorer_cli)")
    print("=" * 75)

    scorer = SlmScorerClient('./tools/slm_scorer_cli')

    # Grid parameters
    token_budgets = [0, 4, 8, 12, 16, 24, 32, 48, 64, 96, 128]
    strategies = ['naive_char', 'sentence_clause_only', 'universal_structural']

    results = {
        "metadata": {
            "model": "Qwen2.5-0.5B-Instruct-Q4_K_M",
            "date": "2026-11-07",
            "num_items": len(BENCHMARK_ITEMS),
            "token_budgets": token_budgets,
            "strategies": strategies
        },
        "grid": {}
    }

    print(f"\nRunning grid evaluation on {len(BENCHMARK_ITEMS)} cross-lingual test cases...")

    for strat in strategies:
        results["grid"][strat] = {}
        print(f"\n--- Strategy: {strat} ---")

        for budget in token_budgets:
            top1_count = 0
            top3_count = 0
            mrr_total = 0.0
            gold_nll_sum = 0.0
            delta_nll_sum = 0.0
            total_lat_ms = 0.0
            total_tokens = 0.0
            n_items = len(BENCHMARK_ITEMS)

            for item in BENCHMARK_ITEMS:
                full_ctx = item["context_full"]
                cands = item["all_cands"]
                gold = item["gold"]
                comp = item["competitor"]

                # Extract context prefix according to budget and strategy
                if budget == 0:
                    ctx = ""
                else:
                    ctx = UniversalContextTrimmer.trim(full_ctx, budget, strat)

                logprobs, lat = scorer.score(ctx, cands)
                total_lat_ms += lat
                total_tokens += UniversalContextTrimmer.estimate_token_count(ctx)

                # Map surface -> NLL
                nll_map = {}
                for idx, c in enumerate(cands):
                    lp = logprobs[idx] if idx < len(logprobs) else -20.0
                    nll = -lp if lp > -100.0 else 20.0
                    nll_map[c] = nll

                # Rank candidates by NLL ascending (lower NLL = higher probability)
                sorted_cands = sorted(cands, key=lambda c: nll_map.get(c, 999.0))

                # Accuracy metrics
                if sorted_cands[0] == gold:
                    top1_count += 1
                if gold in sorted_cands[:3]:
                    top3_count += 1
                rank = sorted_cands.index(gold) + 1 if gold in sorted_cands else 10
                mrr_total += 1.0 / rank

                # Probability health metrics
                gold_nll = nll_map.get(gold, 20.0)
                comp_nll = nll_map.get(comp, 20.0)
                gold_nll_sum += gold_nll
                # Margin: comp - gold (positive means model correctly preferred gold)
                delta_nll_sum += (comp_nll - gold_nll)

            avg_top1 = (top1_count / n_items) * 100.0
            avg_top3 = (top3_count / n_items) * 100.0
            avg_mrr = mrr_total / n_items
            avg_gold_nll = gold_nll_sum / n_items
            avg_delta_nll = delta_nll_sum / n_items
            avg_lat = total_lat_ms / n_items
            avg_toks = total_tokens / n_items

            results["grid"][strat][str(budget)] = {
                "budget_tokens": budget,
                "avg_actual_tokens": round(avg_toks, 1),
                "top1_acc": round(avg_top1, 2),
                "top3_recall": round(avg_top3, 2),
                "mrr": round(avg_mrr, 4),
                "mean_gold_nll": round(avg_gold_nll, 3),
                "mean_delta_nll_margin": round(avg_delta_nll, 3),
                "avg_latency_ms": round(avg_lat, 2)
            }

            print(f"  [Budget={budget:3d} toks] Top-1: {avg_top1:5.1f}% | MRR: {avg_mrr:.4f} | "
                  f"Gold NLL: {avg_gold_nll:5.2f} | Margin: {avg_delta_nll:+5.2f} | "
                  f"Actual Toks: {avg_toks:4.1f} | Latency: {avg_lat:5.2f} ms")

    scorer.close()

    output_path = 'eval/context_length_tradeoff_results.json'
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"\nEvaluation results written to {output_path}")

if __name__ == '__main__':
    run_evaluation()
