#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <chrono>
#include <algorithm>
#include <numeric>

#include "rewriter/llm_reranker.h"

struct EvalItem {
  std::string id;
  std::string context;
  std::string ground_truth;
  std::vector<std::string> candidates;
};

std::vector<EvalItem> LoadTsv(const std::string& path) {
  std::vector<EvalItem> items;
  std::ifstream infile(path);
  std::string line;
  while (std::getline(infile, line)) {
    if (line.empty()) continue;
    std::stringstream ss(line);
    std::string token;
    EvalItem item;
    if (std::getline(ss, item.id, '\t') &&
        std::getline(ss, item.context, '\t') &&
        std::getline(ss, item.ground_truth, '\t')) {
      while (std::getline(ss, token, '\t')) {
        if (!token.empty()) {
          item.candidates.push_back(token);
        }
      }
      if (!item.candidates.empty()) {
        items.push_back(item);
      }
    }
  }
  return items;
}

int main() {
  std::cout << "============================================================\n";
  std::cout << "   1,000-Sample Scale Benchmark: Context-Aware vs. Context-Free\n";
  std::cout << "   Model: Qwen2.5-0.5B-Instruct-Q4_K_M (4 threads)\n";
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

  auto items_1000 = LoadTsv("tools/eval_1000_cases.tsv");
  std::cout << "[Data] Loaded " << items_1000.size() << " stratified evaluation cases.\n\n";

  // -------------------------------------------------------------
  // Test A: Baseline Mozc Accuracy
  // -------------------------------------------------------------
  int baseline_correct = 0;
  for (const auto& item : items_1000) {
    if (item.candidates[0] == item.ground_truth) {
      baseline_correct++;
    }
  }
  double baseline_acc = (baseline_correct * 100.0) / items_1000.size();
  std::cout << "--- 1. Baseline Mozc (No SLM) ---\n";
  std::cout << "  Top-1 Accuracy: " << baseline_correct << " / " << items_1000.size()
            << " (" << std::fixed << std::setprecision(2) << baseline_acc << "%)\n\n";

  // -------------------------------------------------------------
  // Test B: Context-Aware SLM (Phase 2 Condition: With Context)
  // -------------------------------------------------------------
  std::cout << "--- 2. Context-Aware SLM (With Context Prefix) ---\n";
  int aware_correct = 0;
  std::vector<double> aware_lats;
  aware_lats.reserve(items_1000.size());

  auto t_aware_start = std::chrono::high_resolution_clock::now();
  for (const auto& item : items_1000) {
    std::vector<float> logprobs;
    auto t0 = std::chrono::high_resolution_clock::now();
    reranker->ScoreCandidatesAdaptive(item.context.empty() ? "<s>" : item.context, item.candidates, &logprobs);
    auto t1 = std::chrono::high_resolution_clock::now();
    aware_lats.push_back(std::chrono::duration<double, std::milli>(t1 - t0).count());

    int best_idx = 0;
    float best_lp = -999.0f;
    for (size_t i = 0; i < logprobs.size(); ++i) {
      if (logprobs[i] > best_lp) {
        best_lp = logprobs[i];
        best_idx = static_cast<int>(i);
      }
    }
    if (item.candidates[best_idx] == item.ground_truth) {
      aware_correct++;
    }
  }
  auto t_aware_end = std::chrono::high_resolution_clock::now();
  double aware_total_ms = std::chrono::duration<double, std::milli>(t_aware_end - t_aware_start).count();

  std::sort(aware_lats.begin(), aware_lats.end());
  double aware_acc = (aware_correct * 100.0) / items_1000.size();
  double aware_p50 = aware_lats[aware_lats.size() / 2];
  double aware_p95 = aware_lats[static_cast<size_t>(aware_lats.size() * 0.95)];
  double aware_avg = std::accumulate(aware_lats.begin(), aware_lats.end(), 0.0) / aware_lats.size();

  std::cout << "  Top-1 Accuracy : " << aware_correct << " / " << items_1000.size()
            << " (" << std::fixed << std::setprecision(2) << aware_acc << "%) [Gain: +" << (aware_acc - baseline_acc) << "%]\n";
  std::cout << "  Total Time     : " << aware_total_ms << " ms (" << (items_1000.size() / (aware_total_ms / 1000.0)) << " qps)\n";
  std::cout << "  Latency Avg    : " << aware_avg << " ms\n";
  std::cout << "  Latency P50    : " << aware_p50 << " ms\n";
  std::cout << "  Latency P95    : " << aware_p95 << " ms\n";
  std::cout << "  Cache Hits     : " << reranker->context_cache_hits() << " / Misses: " << reranker->context_cache_misses() << "\n\n";

  // -------------------------------------------------------------
  // Test C: Context-Free SLM (Forced Empty Context / <s>)
  // -------------------------------------------------------------
  std::cout << "--- 3. Context-Free SLM (Forced Empty Context Prefix) ---\n";
  int free_correct = 0;
  std::vector<double> free_lats;
  free_lats.reserve(items_1000.size());

  auto t_free_start = std::chrono::high_resolution_clock::now();
  for (const auto& item : items_1000) {
    std::vector<float> logprobs;
    auto t0 = std::chrono::high_resolution_clock::now();
    reranker->ScoreCandidatesAdaptive("<s>", item.candidates, &logprobs);
    auto t1 = std::chrono::high_resolution_clock::now();
    free_lats.push_back(std::chrono::duration<double, std::milli>(t1 - t0).count());

    int best_idx = 0;
    float best_lp = -999.0f;
    for (size_t i = 0; i < logprobs.size(); ++i) {
      if (logprobs[i] > best_lp) {
        best_lp = logprobs[i];
        best_idx = static_cast<int>(i);
      }
    }
    if (item.candidates[best_idx] == item.ground_truth) {
      free_correct++;
    }
  }
  auto t_free_end = std::chrono::high_resolution_clock::now();
  double free_total_ms = std::chrono::duration<double, std::milli>(t_free_end - t_free_start).count();

  std::sort(free_lats.begin(), free_lats.end());
  double free_acc = (free_correct * 100.0) / items_1000.size();
  double free_p50 = free_lats[free_lats.size() / 2];
  double free_p95 = free_lats[static_cast<size_t>(free_lats.size() * 0.95)];
  double free_avg = std::accumulate(free_lats.begin(), free_lats.end(), 0.0) / free_lats.size();

  std::cout << "  Top-1 Accuracy : " << free_correct << " / " << items_1000.size()
            << " (" << std::fixed << std::setprecision(2) << free_acc << "%) [Delta vs Baseline: " << (free_acc - baseline_acc) << "%]\n";
  std::cout << "  Total Time     : " << free_total_ms << " ms (" << (items_1000.size() / (free_total_ms / 1000.0)) << " qps)\n";
  std::cout << "  Latency Avg    : " << free_avg << " ms\n";
  std::cout << "  Latency P50    : " << free_p50 << " ms\n";
  std::cout << "  Latency P95    : " << free_p95 << " ms\n\n";

  // -------------------------------------------------------------
  // Test D: Naturally Empty Context (500 Real Sentence Openings)
  // -------------------------------------------------------------
  std::cout << "--- 4. Naturally Empty Context (500 Real Sentence Openings) ---\n";
  auto items_500_empty = LoadTsv("tools/eval_500_natural_empty.tsv");
  int natural_baseline_correct = 0;
  int natural_slm_correct = 0;

  for (const auto& item : items_500_empty) {
    if (item.candidates[0] == item.ground_truth) {
      natural_baseline_correct++;
    }
    std::vector<float> logprobs;
    reranker->ScoreCandidatesAdaptive("<s>", item.candidates, &logprobs);
    int best_idx = 0;
    float best_lp = -999.0f;
    for (size_t i = 0; i < logprobs.size(); ++i) {
      if (logprobs[i] > best_lp) {
        best_lp = logprobs[i];
        best_idx = static_cast<int>(i);
      }
    }
    if (item.candidates[best_idx] == item.ground_truth) {
      natural_slm_correct++;
    }
  }

  double nat_base_acc = (natural_baseline_correct * 100.0) / items_500_empty.size();
  double nat_slm_acc = (natural_slm_correct * 100.0) / items_500_empty.size();

  std::cout << "  Naturally Empty Baseline : " << natural_baseline_correct << " / " << items_500_empty.size()
            << " (" << std::fixed << std::setprecision(2) << nat_base_acc << "%)\n";
  std::cout << "  Naturally Empty SLM      : " << natural_slm_correct << " / " << items_500_empty.size()
            << " (" << std::fixed << std::setprecision(2) << nat_slm_acc << "%) [Delta: " << (nat_slm_acc - nat_base_acc) << "%]\n\n";

  std::cout << "============================================================\n";
  std::cout << "  SCALE BENCHMARK EVALUATION FINISHED\n";
  std::cout << "============================================================\n";
  return 0;
}
