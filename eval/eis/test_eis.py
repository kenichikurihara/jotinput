"""Sanity tests for EIS-v1.

Run: python3 -m unittest discover -s eval/eis -p 'test_*.py'
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))

from klm import CUJ_WEIGHTS, KLM, correction_cost, error_spans, voice_item_time  # noqa: E402
import adapters  # noqa: E402
from run_eis import global_eis  # noqa: E402


class KlmTests(unittest.TestCase):
    def test_weights_sum_to_one(self):
        self.assertAlmostEqual(sum(CUJ_WEIGHTS.values()), 1.0)

    def test_perfect_output_has_zero_correction(self):
        self.assertEqual(correction_cost("今日は晴れです", "今日は晴れです"), 0.0)

    def test_errors_cost_time_monotonically(self):
        one = correction_cost("渡邊さんが来ます", "渡辺さんが来ます")
        two = correction_cost("渡邊さんが来ます", "渡辺さんが来まず")
        self.assertGreater(one, 0)
        self.assertGreater(two, one)

    def test_adjacent_errors_merge_into_one_span(self):
        self.assertEqual(len(error_spans("ABCDEF", "AXCYEF", merge_gap=1)), 1)
        self.assertEqual(len(error_spans("ABCDEFGH", "AXCDEFGY", merge_gap=1)), 2)

    def test_select_cost_monotonic_in_rank(self):
        costs = [KLM.select_cost(k) for k in range(1, 9)]
        self.assertEqual(costs, sorted(costs))
        self.assertEqual(len(set(costs)), len(costs))
        self.assertGreater(KLM.select_cost(None) + KLM.K_f * 4, KLM.select_cost(8))

    def test_latency_below_threshold_is_free(self):
        self.assertEqual(KLM.wait(0.09, KLM.wait_free_key), 0.0)
        self.assertGreater(KLM.wait(0.3, KLM.wait_free_key), 0.0)

    def test_voice_error_slows_eis(self):
        ok = voice_item_time("明日は雨が降ります。", "明日は雨が降ります", 0.25)
        bad = voice_item_time("明日は雨が降ります。", "明日は飴が降ります", 0.25)
        self.assertEqual(ok[0], bad[0])
        self.assertGreater(bad[1], ok[1])


class AggregationTests(unittest.TestCase):
    def test_global_is_total_chars_over_total_time_when_weights_are_char_shares(self):
        # chars shares == weights; per-CUJ time = chars / eis_c  => harmonic mean == total chars / total time
        eis_c = {"CUJ-1": 100.0, "CUJ-2": 200.0, "CUJ-3": 50.0, "CUJ-4": 150.0}
        total_chars = 1000.0
        total_time = sum(total_chars * w / eis_c[c] for c, w in CUJ_WEIGHTS.items())
        self.assertAlmostEqual(global_eis(eis_c), 60 * total_chars / total_time * 1.0 / 60 * 1.0, places=6)

    def test_split_is_deterministic_and_roughly_30_percent(self):
        ids = [f"item_{i:04d}" for i in range(2000)]
        s1 = [adapters.split_of(i) for i in ids]
        self.assertEqual(s1, [adapters.split_of(i) for i in ids])
        frac = s1.count("test") / len(s1)
        self.assertTrue(0.25 < frac < 0.35, frac)


class ScoringSyncTests(unittest.TestCase):
    def test_lm_first_score_matches_reference_implementation(self):
        import tune_and_eval_lm_first as ref
        cases = [
            ({"source": "system", "mozc_cost": 3500, "obs_penalty": 0.4}, 7.0, 6.0),
            ({"source": "user", "priority": 1.0, "mozc_cost": 3000, "obs_penalty": 0.0}, 12.0, 6.0),
        ]
        for cand, nll, sys_best in cases:
            a = ref.compute_lm_first_score(cand, nll, sys_best, 0.15, 0.5, 2.0)
            b = adapters._lm_first_score(cand, nll, sys_best, 0.15, 0.5, 2.0)
            self.assertAlmostEqual(a, b, places=9)


if __name__ == "__main__":
    unittest.main()
