#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <memory>
#include <chrono>
#include <cmath>
#include <algorithm>

#include "rewriter/llm_reranker.h"

// Struct for benchmark test cases
struct TestCase {
  std::string reading;
  std::string ground_truth;
  std::vector<std::string> candidates;
};

int main() {
  std::cout << "============================================================\n";
  std::cout << "   Context-Free SLM Candidate Re-ranking Evaluation\n";
  std::cout << "   (Model: Qwen2.5-0.5B-Instruct-Q4_K_M)\n";
  std::cout << "============================================================\n\n";

  mozc::LlmRerankerConfig config;
  config.model_path = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
  config.n_threads = 4;
  config.n_ctx = 256;
  config.fast_path_threshold = 1.0f;

  auto reranker = std::make_shared<mozc::LlmReranker>(config);
  std::cout << "[Init] Loading Qwen2.5-0.5B model...\n";
  auto t_start = std::chrono::high_resolution_clock::now();
  if (!reranker->Initialize()) {
    std::cerr << "Failed to initialize LlmReranker." << std::endl;
    return 1;
  }
  auto t_end = std::chrono::high_resolution_clock::now();
  double init_ms = std::chrono::duration<double, std::milli>(t_end - t_start).count();
  std::cout << "[Init] Model ready in " << std::fixed << std::setprecision(1) << init_ms << " ms.\n\n";

  // Test suite: 20 representative cases covering:
  // - Tricky verb contractions (むかってる, しってる, やってる, はしってる, もってる)
  // - Inflections & particles (むかって, むかった, でんわしています, おまちしています)
  // - Long multi-bunsetsu phrases (きょうはいいてんき, さぎょうちゅうですので, つとめております, かぐとうかいぼうしの)
  // - Common sentence-initial conversational phrases (よろしく, ありがとう, おつかれさまです, かくにん)
  std::vector<TestCase> test_cases = {
    {"むかってる", "向かってる", {"向っテル", "向かってる", "向ってる", "向かっテル", "向っ照"}},
    {"むかって", "向かって", {"無買って", "向かって", "無勝手", "向って", "剥かって"}},
    {"むかった", "向かった", {"無買った", "向かった", "向っ多", "向っ他", "向った"}},
    {"しってる", "知ってる", {"知っテル", "知ってる", "知っ照", "しってる"}},
    {"やってる", "やってる", {"やっテル", "やってる", "やっ照", "やッてる", "夜ってる"}},
    {"はしってる", "走ってる", {"走っテル", "走ってる", "走っ照", "走ッてる"}},
    {"もってる", "持ってる", {"保っテル", "持ってる", "保っ照", "盛ってる"}},
    {"きょうはいいてんき", "今日はいい天気", {"共派いい天気", "今日はいい天気", "今日は良い天気", "今日はイイ天気"}},
    {"さぎょうちゅうですので", "作業中ですので", {"作業厨デスので", "作業中ですので", "作業注ですので", "作業虫ですので"}},
    {"つとめております", "努めております", {"努めて折摩す", "努めております", "務めております", "勤めております"}},
    {"でんわしています", "電話しています", {"電話四ています", "電話しています", "電話して今す"}},
    {"れんらくをおまちしています", "連絡をお待ちしています", {"連絡をお待ち四ています", "連絡をお待ちしています", "連絡ヲお待ちしています"}},
    {"かぐとうかいぼうしの", "家具倒壊防止の", {"家具倒壊某しの", "家具倒壊防止の", "家具倒壊ボウしの", "家具倒壊ぼうしの"}},
    {"よろしく", "よろしく", {"宜しく", "よろしく", "夜露死苦", "ヨロシク"}},
    {"ありがとうございます", "ありがとうございます", {"有難う御座います", "ありがとうございます", "有り難うございます"}},
    {"おつかれさまです", "お疲れ様です", {"お疲れ様です", "お疲れ様デス", "お疲れさまです"}},
    {"かくにん", "確認", {"確認", "確任", "カクニン", "核仁"}},
    {"かいぎ", "会議", {"会議", "怪奇", "カイギ", "快気"}},
    {"よてい", "予定", {"予定", "余程", "ヨテイ", "予選"}},
    {"しりょう", "資料", {"資料", "思量", "シリョウ", "飼料"}}
  };

  // We test 2 Context-Free strategies:
  // Strategy A: Minimal BOS Token Only (<s>)
  // Strategy B: Chat/Prompt Sentence-Start Prefix ("<|im_start|>user\n<|im_end|>\n<|im_start|>assistant\n")
  std::vector<std::pair<std::string, std::string>> strategies = {
    {"Strategy A: Sentence-Start BOS Token (<s>)", "<s>"},
    {"Strategy B: Chat Template BOS (<|im_start|>assistant\\n)", "<|im_start|>user\n<|im_end|>\n<|im_start|>assistant\n"}
  };

  for (const auto& strat : strategies) {
    const std::string& strat_name = strat.first;
    const std::string& context = strat.second;

    std::cout << "------------------------------------------------------------\n";
    std::cout << " EVALUATING: " << strat_name << "\n";
    std::cout << "------------------------------------------------------------\n";

    int top1_matches = 0;
    int top3_matches = 0;
    int bizarre_top1 = 0;
    int n = static_cast<int>(test_cases.size());

    double total_eval_time_ms = 0.0;
    std::vector<double> latencies;

    for (const auto& tc : test_cases) {
      std::vector<float> logprobs;
      auto t0 = std::chrono::high_resolution_clock::now();
      reranker->ScoreCandidatesAdaptive(context, tc.candidates, &logprobs);
      auto t1 = std::chrono::high_resolution_clock::now();
      double lat = std::chrono::duration<double, std::milli>(t1 - t0).count();
      latencies.push_back(lat);
      total_eval_time_ms += lat;

      // Rank candidates by logprob descending (higher is better)
      std::vector<int> sorted_indices(tc.candidates.size());
      for (size_t i = 0; i < tc.candidates.size(); ++i) sorted_indices[i] = i;
      std::sort(sorted_indices.begin(), sorted_indices.end(),
                [&](int a, int b) { return logprobs[a] > logprobs[b]; });

      std::string best_cand = tc.candidates[sorted_indices[0]];
      bool is_top1 = (best_cand == tc.ground_truth);
      bool is_top3 = false;
      for (size_t r = 0; r < std::min<size_t>(3, sorted_indices.size()); ++r) {
        if (tc.candidates[sorted_indices[r]] == tc.ground_truth) {
          is_top3 = true;
          break;
        }
      }

      bool is_bizarre = (best_cand.find("テル") != std::string::npos ||
                         best_cand.find("デス") != std::string::npos ||
                         best_cand.find("四て") != std::string::npos ||
                         best_cand.find("無買") != std::string::npos ||
                         best_cand.find("某し") != std::string::npos ||
                         best_cand.find("夜露死苦") != std::string::npos);

      if (is_top1) top1_matches++;
      if (is_top3) top3_matches++;
      if (is_bizarre) bizarre_top1++;

      std::cout << "  [" << (is_top1 ? "OK  " : "FAIL") << "] " 
                << std::setw(20) << tc.reading << " -> Top-1: " 
                << std::setw(18) << best_cand 
                << " (logprob: " << std::fixed << std::setprecision(2) << logprobs[sorted_indices[0]] << ")"
                << " | Expected: " << tc.ground_truth
                << " | Latency: " << std::setprecision(2) << lat << " ms\n";
    }

    std::sort(latencies.begin(), latencies.end());
    double median_lat = latencies[latencies.size() / 2];
    double p95_lat = latencies[static_cast<size_t>(latencies.size() * 0.95)];
    double avg_lat = total_eval_time_ms / n;

    std::cout << "\n  === SUMMARY: " << strat_name << " ===\n";
    std::cout << "  Top-1 Accuracy : " << top1_matches << " / " << n 
              << " (" << std::fixed << std::setprecision(1) << (top1_matches * 100.0 / n) << "%)\n";
    std::cout << "  Top-3 Recall   : " << top3_matches << " / " << n 
              << " (" << (top3_matches * 100.0 / n) << "%)\n";
    std::cout << "  Bizarre Top-1  : " << bizarre_top1 << " / " << n 
              << " (" << (bizarre_top1 * 100.0 / n) << "%)\n";
    std::cout << "  Latency (Avg)  : " << std::setprecision(2) << avg_lat << " ms\n";
    std::cout << "  Latency (Med)  : " << std::setprecision(2) << median_lat << " ms\n";
    std::cout << "  Latency (P95)  : " << std::setprecision(2) << p95_lat << " ms\n";
    std::cout << "  KV Cache Hits  : " << reranker->context_cache_hits() 
              << " / Misses: " << reranker->context_cache_misses() << "\n\n";
  }

  // -------------------------------------------------------------
  // Latency Deep-Dive: Fast-Path vs Deep-Path vs Candidate Pool Size
  // -------------------------------------------------------------
  std::cout << "============================================================\n";
  std::cout << "   LATENCY PROFILING: Context-Free Cold vs Warm Keystrokes\n";
  std::cout << "============================================================\n";

  std::vector<std::string> sample_cands = {
    "向かってる", "向っテル", "向ってる", "向かっテル", "向っ照", "向っ光", "無買ってる", "剥かってる"
  };

  // Test across pool size K = 2, 4, 8
  for (int k : {2, 4, 8}) {
    std::vector<std::string> sub(sample_cands.begin(), sample_cands.begin() + k);
    std::vector<float> lp;

    // Iteration 1: Cold (first keystroke, cache miss)
    auto t0 = std::chrono::high_resolution_clock::now();
    reranker->ScoreCandidatesAdaptive("<s>", sub, &lp);
    auto t1 = std::chrono::high_resolution_clock::now();
    double cold_lat = std::chrono::duration<double, std::milli>(t1 - t0).count();

    // Iteration 2..10: Warm (cached context, repeated keystroke)
    std::vector<double> warm_lats;
    for (int it = 0; it < 10; ++it) {
      auto tw0 = std::chrono::high_resolution_clock::now();
      reranker->ScoreCandidatesAdaptive("<s>", sub, &lp);
      auto tw1 = std::chrono::high_resolution_clock::now();
      warm_lats.push_back(std::chrono::duration<double, std::milli>(tw1 - tw0).count());
    }
    double avg_warm = 0.0;
    for (double w : warm_lats) avg_warm += w;
    avg_warm /= warm_lats.size();

    std::cout << "  Pool Size K = " << k 
              << " | Cold Start: " << std::fixed << std::setprecision(2) << cold_lat << " ms"
              << " | Warm KV Cache: " << avg_warm << " ms\n";
  }

  std::cout << "\n============================================================\n";
  std::cout << "   EVALUATION COMPLETE\n";
  std::cout << "============================================================\n";
  return 0;
}
