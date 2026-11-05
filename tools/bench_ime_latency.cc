#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <memory>
#include <chrono>
#include <numeric>
#include <algorithm>

#include "rewriter/llm_reranker.h"

int main() {
  std::cout << "============================================================\n";
  std::cout << "   IME Keystroke Latency Offline Benchmark (Linux/Workstation)\n";
  std::cout << "============================================================\n\n";

  mozc::LlmRerankerConfig config;
  config.model_path = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
  config.n_threads = 4;
  config.n_ctx = 256;
  config.fast_path_threshold = 1.0f;

  auto reranker = std::make_shared<mozc::LlmReranker>(config);
  std::cout << "[Init] Loading Qwen2.5-0.5B model...\n";
  auto t_load_start = std::chrono::high_resolution_clock::now();
  if (!reranker->Initialize()) {
    std::cerr << "Failed to initialize LlmReranker." << std::endl;
    return 1;
  }
  auto t_load_end = std::chrono::high_resolution_clock::now();
  double load_ms = std::chrono::duration<double, std::milli>(t_load_end - t_load_start).count();
  std::cout << "[Init] Model ready in " << std::fixed << std::setprecision(1) << load_ms << " ms.\n\n";

  // -------------------------------------------------------------
  // Benchmark 1: Candidate Pool Size Scaling (K = 4, 8, 16, 32, 50)
  // -------------------------------------------------------------
  std::cout << "--- Benchmark 1: Candidate Pool Size Scaling (Prefix: 'Mozcは私の直感と挙動が') ---\n";
  std::string context = "Mozcは私の直感と挙動が";
  std::vector<std::string> all_candidates = {
    "異なります", "違い", "ちがう", "異なって", "変わって", "変わる", "ことなる", "違います",
    "異なる", "違う", "相違", "変異", "乖離", "違憲", "異存", "異同",
    "言葉", "今年", "事", "琴", "毎", "古都", "湖東", "孤島",
    "異議", "異変", "異端", "異質", "異色", "異様", "異論", "異例",
    "ことなり", "ことなる", "ことのほか", "ことごとく", "ことづて", "ことさらに", "ことたりる", "ことばづかい",
    "対立", "矛盾", "不一致", "齟齬", "差異", "ギャップ", "反駁", "反対", "反発", "隔たり"
  };

  std::vector<int> pool_sizes = {4, 8, 16, 32, 50};
  for (int k : pool_sizes) {
    std::vector<std::string> subset(all_candidates.begin(), all_candidates.begin() + std::min<size_t>(k, all_candidates.size()));
    std::vector<float> logprobs;

    // Run 5 iterations to get steady state
    std::vector<double> latencies;
    for (int rep = 0; rep < 5; ++rep) {
      auto t0 = std::chrono::high_resolution_clock::now();
      reranker->ScoreCandidatesAdaptive(context, subset, &logprobs);
      auto t1 = std::chrono::high_resolution_clock::now();
      latencies.push_back(std::chrono::duration<double, std::milli>(t1 - t0).count());
    }
    double avg_lat = std::accumulate(latencies.begin(), latencies.end(), 0.0) / latencies.size();
    double min_lat = *std::min_element(latencies.begin(), latencies.end());

    std::cout << "  K = " << std::setw(2) << k << " candidates | Avg: " 
              << std::setw(6) << std::setprecision(2) << avg_lat << " ms | Min: " 
              << std::setw(6) << std::setprecision(2) << min_lat << " ms | Top-1: " 
              << (subset.empty() ? "" : subset[0]) << "\n";
  }
  std::cout << "\n";

  // -------------------------------------------------------------
  // Benchmark 2: Continuous Typing Simulation (KV Cache Reuse)
  // -------------------------------------------------------------
  std::cout << "--- Benchmark 2: Continuous Live Typing Simulation ---\n";
  struct KeystrokeStep {
    std::string reading;
    std::vector<std::string> candidates;
  };

  std::vector<KeystrokeStep> typing_sequence = {
    {"こ", {"こ", "子", "小", "個", "弧", "粉", "呼", "故"}},
    {"こと", {"事", "今年", "言葉", "古都", "琴", "湖東", "孤島", "毎"}},
    {"ことな", {"事な", "異な", "小名", "ことな", "古名", "小菜", "言な", "異名"}},
    {"ことなり", {"異なり", "事なり", "ことなり", "小隣", "琴鳴り", "古都なり", "湖東なり", "言葉なり"}},
    {"ことなります", {"異なります", "事なります", "ことなります", "事態になります", "言葉になります", "琴鳴ります", "古都なります", "湖東なります"}}
  };

  std::cout << "  Preceding context: \"" << context << "\"\n";
  double total_seq_ms = 0.0;
  for (size_t i = 0; i < typing_sequence.size(); ++i) {
    const auto& step = typing_sequence[i];
    std::vector<float> logprobs;
    auto t0 = std::chrono::high_resolution_clock::now();
    reranker->ScoreCandidatesAdaptive(context, step.candidates, &logprobs);
    auto t1 = std::chrono::high_resolution_clock::now();
    double lat = std::chrono::duration<double, std::milli>(t1 - t0).count();
    total_seq_ms += lat;

    // Find best candidate
    int best_idx = 0;
    float best_lp = -999.0f;
    for (size_t c = 0; c < logprobs.size(); ++c) {
      if (logprobs[c] > best_lp) {
        best_lp = logprobs[c];
        best_idx = static_cast<int>(c);
      }
    }

    std::cout << "  Step " << (i + 1) << " ('" << step.reading << "') -> Top: '" 
              << step.candidates[best_idx] << "' (logprob=" << std::setprecision(2) << best_lp 
              << ") | Latency: " << std::setprecision(2) << lat << " ms\n";
  }
  std::cout << "  Total typing sequence inference time: " << total_seq_ms << " ms\n";
  std::cout << "  Cache Hit Stats: hits=" << reranker->context_cache_hits() 
            << ", misses=" << reranker->context_cache_misses() << "\n\n";

  std::cout << "============================================================\n";
  std::cout << "  BENCHMARK SUMMARY COMPLETE\n";
  std::cout << "============================================================\n";
  return 0;
}
