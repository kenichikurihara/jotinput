#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <chrono>
#include <algorithm>
#include <numeric>
#include <cmath>

#include "rewriter/llm_reranker.h"

struct EvalItem {
  std::string id;
  std::string context;
  std::string ground_truth;
  std::vector<std::string> candidates;
};

struct ScoredItem {
  std::string id;
  std::string context;
  std::string ground_truth;
  std::vector<std::string> candidates;
  std::vector<float> neg_logprobs_aware;
  std::vector<float> neg_logprobs_free;
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

// Predict Top-1 candidate index given alpha and negative logprobs:
// Score(i) = alpha * i + (1 - alpha) * neg_logprobs[i]
// Lower score wins.
int PredictTop1(const std::vector<float>& neg_logprobs, float alpha) {
  int best_idx = 0;
  float best_score = 1e9f;
  for (size_t i = 0; i < neg_logprobs.size(); ++i) {
    float score = alpha * static_cast<float>(i) + (1.0f - alpha) * neg_logprobs[i];
    if (score < best_score) {
      best_score = score;
      best_idx = static_cast<int>(i);
    }
  }
  return best_idx;
}

struct CVResult {
  float best_alpha;
  double train_acc;
  double test_acc;
  int test_correct;
  int test_total;
};

void Run5FoldCV(const std::string& condition_name,
               const std::vector<ScoredItem>& dataset,
               bool use_context) {
  std::cout << "\n============================================================\n";
  std::cout << "  5-Fold Stratified Cross-Validation on 1,000 Samples\n";
  std::cout << "  Condition: " << condition_name << "\n";
  std::cout << "============================================================\n";

  const int K = 5;
  std::vector<CVResult> fold_results(K);

  // Grid search candidates for alpha from 0.00 to 1.00 in steps of 0.01
  std::vector<float> alpha_grid;
  for (int step = 0; step <= 100; ++step) {
    alpha_grid.push_back(step / 100.0f);
  }

  int total_oof_correct = 0;
  std::vector<int> oof_predictions(dataset.size());

  for (int fold = 0; fold < K; ++fold) {
    std::vector<size_t> train_indices;
    std::vector<size_t> test_indices;

    for (size_t i = 0; i < dataset.size(); ++i) {
      if ((i % K) == static_cast<size_t>(fold)) {
        test_indices.push_back(i);
      } else {
        train_indices.push_back(i);
      }
    }

    // 1. Train: Find optimal alpha on train_indices (800 samples)
    float best_alpha = 0.0f;
    int max_train_correct = -1;

    for (float alpha : alpha_grid) {
      int correct = 0;
      for (size_t idx : train_indices) {
        const auto& item = dataset[idx];
        const auto& lps = use_context ? item.neg_logprobs_aware : item.neg_logprobs_free;
        int pred_idx = PredictTop1(lps, alpha);
        if (item.candidates[pred_idx] == item.ground_truth) {
          correct++;
        }
      }
      if (correct > max_train_correct) {
        max_train_correct = correct;
        best_alpha = alpha;
      }
    }

    // 2. Test: Evaluate chosen best_alpha on test_indices (200 samples)
    int test_correct = 0;
    for (size_t idx : test_indices) {
      const auto& item = dataset[idx];
      const auto& lps = use_context ? item.neg_logprobs_aware : item.neg_logprobs_free;
      int pred_idx = PredictTop1(lps, best_alpha);
      oof_predictions[idx] = pred_idx;
      if (item.candidates[pred_idx] == item.ground_truth) {
        test_correct++;
        total_oof_correct++;
      }
    }

    CVResult res;
    res.best_alpha = best_alpha;
    res.train_acc = (max_train_correct * 100.0) / train_indices.size();
    res.test_correct = test_correct;
    res.test_total = test_indices.size();
    res.test_acc = (test_correct * 100.0) / test_indices.size();
    fold_results[fold] = res;

    std::cout << "  Fold " << (fold + 1) << " | Optimal Alpha: " << std::fixed << std::setprecision(2) << best_alpha
              << " | Train Acc: " << std::setprecision(2) << res.train_acc << "% (" << max_train_correct << "/" << train_indices.size() << ")"
              << " | Test Acc: " << std::setprecision(2) << res.test_acc << "% (" << test_correct << "/" << test_indices.size() << ")\n";
  }

  double avg_best_alpha = 0.0;
  double avg_test_acc = 0.0;
  for (const auto& r : fold_results) {
    avg_best_alpha += r.best_alpha;
    avg_test_acc += r.test_acc;
  }
  avg_best_alpha /= K;
  avg_test_acc /= K;

  double var_test_acc = 0.0;
  for (const auto& r : fold_results) {
    var_test_acc += (r.test_acc - avg_test_acc) * (r.test_acc - avg_test_acc);
  }
  double std_test_acc = std::sqrt(var_test_acc / K);

  double oof_acc = (total_oof_correct * 100.0) / dataset.size();

  std::cout << "\n  >>> [Cross-Validation Summary: " << condition_name << "] <<<\n";
  std::cout << "  Mean Optimal Alpha  : " << std::fixed << std::setprecision(3) << avg_best_alpha << "\n";
  std::cout << "  Mean Test Accuracy  : " << std::fixed << std::setprecision(2) << avg_test_acc << "% ± " << std_test_acc << "%\n";
  std::cout << "  Out-of-Fold (OOF)   : " << std::fixed << std::setprecision(2) << oof_acc << "% (" << total_oof_correct << " / " << dataset.size() << ")\n";

  // Alpha Sensitivity Curve across full 1,000 cases
  std::cout << "\n  Alpha Sensitivity Curve (Full 1,000 dataset):\n";
  std::cout << "  --------------------------------------------------\n";
  std::cout << "   Alpha   | Top-1 Acc | Top-1 Count | Delta vs Baseline\n";
  std::cout << "  --------------------------------------------------\n";

  int base_correct = 0;
  for (const auto& item : dataset) {
    if (item.candidates[0] == item.ground_truth) base_correct++;
  }
  double base_acc = (base_correct * 100.0) / dataset.size();

  for (float a : {0.00f, 0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f, 0.80f, 0.90f, 1.00f}) {
    int cor = 0;
    for (const auto& item : dataset) {
      const auto& lps = use_context ? item.neg_logprobs_aware : item.neg_logprobs_free;
      int p = PredictTop1(lps, a);
      if (item.candidates[p] == item.ground_truth) cor++;
    }
    double acc = (cor * 100.0) / dataset.size();
    std::cout << "   " << std::fixed << std::setprecision(2) << a << "    |   "
              << std::setprecision(2) << acc << "%   |  " << std::setw(4) << cor << " / 1000 |  "
              << std::showpos << (acc - base_acc) << "%" << std::noshowpos << "\n";
  }
  std::cout << "  --------------------------------------------------\n";
}

int main() {
  std::cout << "============================================================\n";
  std::cout << "  SLM Alpha Optimization & 5-Fold Cross-Validation\n";
  std::cout << "  Dataset: tools/eval_1000_cases.tsv (1,000 stratified samples)\n";
  std::cout << "  Threads: 8\n";
  std::cout << "============================================================\n\n";

  auto items_1000 = LoadTsv("tools/eval_1000_cases.tsv");
  std::cout << "[Data] Loaded " << items_1000.size() << " evaluation cases.\n";

  mozc::LlmRerankerConfig config;
  config.model_path = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
  config.n_threads = 8;
  config.n_ctx = 256;
  config.fast_path_threshold = 1.0f;

  auto reranker = std::make_shared<mozc::LlmReranker>(config);
  std::cout << "[Init] Initializing LlmReranker with 8 threads...\n";
  if (!reranker->Initialize()) {
    std::cerr << "Failed to initialize LlmReranker.\n";
    return 1;
  }
  std::cout << "[Init] Model ready.\n\n";

  std::cout << "[Inference] Scoring all 1,000 cases under Context-Aware & Context-Free...\n";
  auto t_start = std::chrono::high_resolution_clock::now();

  std::vector<ScoredItem> scored_items;
  scored_items.reserve(items_1000.size());

  for (size_t i = 0; i < items_1000.size(); ++i) {
    const auto& item = items_1000[i];
    ScoredItem sitem;
    sitem.id = item.id;
    sitem.context = item.context;
    sitem.ground_truth = item.ground_truth;
    sitem.candidates = item.candidates;

    // 1. Context-Aware scoring
    std::vector<float> aware_lps;
    reranker->ScoreCandidatesAdaptive(item.context.empty() ? "<s>" : item.context, item.candidates, &aware_lps);
    sitem.neg_logprobs_aware.resize(aware_lps.size());
    for (size_t j = 0; j < aware_lps.size(); ++j) {
      sitem.neg_logprobs_aware[j] = -aware_lps[j]; // positive neg-logprob
    }

    // 2. Context-Free scoring (forced <s>)
    std::vector<float> free_lps;
    reranker->ScoreCandidatesAdaptive("<s>", item.candidates, &free_lps);
    sitem.neg_logprobs_free.resize(free_lps.size());
    for (size_t j = 0; j < free_lps.size(); ++j) {
      sitem.neg_logprobs_free[j] = -free_lps[j]; // positive neg-logprob
    }

    scored_items.push_back(std::move(sitem));

    if ((i + 1) % 200 == 0) {
      std::cout << "  Progress: " << (i + 1) << " / " << items_1000.size() << " cases scored.\n";
    }
  }

  auto t_end = std::chrono::high_resolution_clock::now();
  double elapsed_s = std::chrono::duration<double>(t_end - t_start).count();
  std::cout << "[Inference] Completed in " << std::fixed << std::setprecision(1) << elapsed_s << " s.\n";

  // Run 5-Fold Cross-Validation for Context-Aware
  Run5FoldCV("Context-Aware SLM (With Context Prefix)", scored_items, true);

  // Run 5-Fold Cross-Validation for Context-Free
  Run5FoldCV("Context-Free SLM (Forced Empty Context <s>)", scored_items, false);

  return 0;
}
