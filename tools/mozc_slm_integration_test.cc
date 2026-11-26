#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <memory>
#include <chrono>
#include <algorithm>
#include <cassert>

#include "rewriter/llm_reranker.h"

namespace mozc {

// Minimal representation of Mozc's Candidate and Segment for native demonstration
struct Candidate {
  std::string value;
  int cost = 0;
  float slm_logprob = -999.0f;
  float combined_score = 1e9f;
};

class MockSegment {
 public:
  void set_key(const std::string& key) { key_ = key; }
  const std::string& key() const { return key_; }

  void add_candidate(const std::string& value, int cost) {
    Candidate c;
    c.value = value;
    c.cost = cost;
    candidates_.push_back(c);
  }

  size_t candidates_size() const { return candidates_.size(); }
  Candidate* mutable_candidate(size_t i) { return &candidates_[i]; }
  const Candidate& candidate(size_t i) const { return candidates_[i]; }

  void move_candidate(size_t from_idx, size_t to_idx) {
    if (from_idx == to_idx || from_idx >= candidates_.size() || to_idx >= candidates_.size()) {
      return;
    }
    Candidate c = candidates_[from_idx];
    candidates_.erase(candidates_.begin() + from_idx);
    candidates_.insert(candidates_.begin() + to_idx, c);
  }

 private:
  std::string key_;
  std::vector<Candidate> candidates_;
};

class NativeLlmRewriter {
 public:
  explicit NativeLlmRewriter(std::shared_ptr<LlmReranker> reranker)
      : reranker_(std::move(reranker)) {}

  bool Rewrite(const std::string& preceding_text, MockSegment* segment) {
    if (!reranker_ || !reranker_->IsReady() || !segment) return false;
    if (segment->candidates_size() <= 1) return false;

    std::vector<std::string> cand_strings;
    for (size_t i = 0; i < segment->candidates_size(); ++i) {
      cand_strings.push_back(segment->candidate(i).value);
    }

    std::vector<float> logprobs;
    if (!reranker_->ScoreCandidates(preceding_text, cand_strings, &logprobs)) {
      return false; // Fallback: leave candidates unchanged
    }

    int best_idx = 0;
    float best_score = 1e9f;

    for (size_t i = 0; i < segment->candidates_size(); ++i) {
      Candidate* cand = segment->mutable_candidate(i);
      cand->slm_logprob = logprobs[i];
      cand->combined_score = reranker_->ComputeCombinedScore(cand->cost, cand->slm_logprob);
      if (cand->combined_score < best_score) {
        best_score = cand->combined_score;
        best_idx = static_cast<int>(i);
      }
    }

    if (best_idx != 0) {
      segment->move_candidate(best_idx, 0);
      return true;
    }
    return false;
  }

 private:
  std::shared_ptr<LlmReranker> reranker_;
};

}  // namespace mozc

int main() {
  std::cout << "============================================================\n";
  std::cout << "  Mozc Native C++ In-Process SLM Re-ranking Test (Phase 3)  \n";
  std::cout << "============================================================\n" << std::endl;

  mozc::LlmRerankerConfig config;
  config.model_path = "tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
  config.backend_dir = "tools/llama_bin/llama-b11846";
  config.n_ctx = 512;
  config.n_threads = 4;
  config.timeout_ms = 200;
  config.alpha_weight = 0.20f; // Combined cost balance (80% SLM log-likelihood, 20% Mozc cost)

  auto reranker = std::make_shared<mozc::LlmReranker>(config);
  std::cout << "[Init] Loading Qwen2.5-0.5B model into memory..." << std::endl;
  auto t_load0 = std::chrono::high_resolution_clock::now();
  if (!reranker->Initialize()) {
    std::cerr << "Initialization failed!" << std::endl;
    return 1;
  }
  auto t_load1 = std::chrono::high_resolution_clock::now();
  auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_load1 - t_load0).count();
  std::cout << "[Init] Model loaded in " << load_ms << " ms. Ready for in-process inference.\n" << std::endl;

  mozc::NativeLlmRewriter rewriter(reranker);

  struct TestCase {
    std::string test_id;
    std::string context;
    std::string yomi;
    std::vector<std::pair<std::string, int>> baseline_cands; // (word, mozc_cost)
    std::string expected_winner;
  };

  std::vector<TestCase> cases = {
    {
      "CASE 1 (User Issue)",
      "Mozcは私の直感と挙動が",
      "ことなります",
      {{"ことなります", 3000}, {"事成ります", 3600}, {"異なります", 4500}},
      "異なります"
    },
    {
      "CASE 2 (Verb Inflection)",
      "一般的な仕様とは",
      "ことなり",
      {{"ことなり", 2900}, {"事なり", 3500}, {"異なり", 4200}},
      "異なり"
    },
    {
      "CASE 3 (Hiragana Phrase)",
      "担当者が急遽変更になる",
      "ことになりました",
      {{"事になりました", 3100}, {"異になりました", 4200}, {"ことになりました", 3200}},
      "ことになりました"
    },
    {
      "CASE 4 (Homophone: Travel)",
      "新幹線を使って大阪へ",
      "いどう",
      {{"異動", 2800}, {"威道", 3500}, {"移動", 3800}},
      "移動"
    },
    {
      "CASE 5 (Homophone: Peace Talks)",
      "両国間の平和的な",
      "こうしょう",
      {{"高尚", 2800}, {"考証", 3400}, {"交渉", 3900}},
      "交渉"
    },
    {
      "CASE 6 (Homophone: Journalist)",
      "日経新聞の敏腕",
      "きしゃ",
      {{"貴社", 2500}, {"汽車", 3100}, {"帰社", 3500}, {"記者", 3800}},
      "記者"
    },
    {
      "CASE 7 (Homophone: Business Letter)",
      "平素は格別のご高配を賜り厚く御礼申し上げます。",
      "きしゃ",
      {{"記者", 2500}, {"汽車", 3100}, {"帰社", 3500}, {"貴社", 3800}},
      "貴社"
    }
  };

  int passed_count = 0;

  for (const auto& tc : cases) {
    mozc::MockSegment seg;
    seg.set_key(tc.yomi);
    for (const auto& c : tc.baseline_cands) {
      seg.add_candidate(c.first, c.second);
    }

    std::cout << "------------------------------------------------------------\n";
    std::cout << "[" << tc.test_id << "]\n";
    std::cout << "  Preceding Context : \"" << tc.context << "\"\n";
    std::cout << "  Input Yomi        : \"" << tc.yomi << "\"\n";
    std::cout << "  Baseline Rank 1   : \"" << seg.candidate(0).value << "\" (Cost: " << seg.candidate(0).cost << ")\n";

    auto t0 = std::chrono::high_resolution_clock::now();
    bool rewritten = rewriter.Rewrite(tc.context, &seg);
    auto t1 = std::chrono::high_resolution_clock::now();
    auto latency_us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    float latency_ms = latency_us / 1000.0f;

    std::cout << "  Inference Latency : " << std::fixed << std::setprecision(2) << latency_ms << " ms\n";
    std::cout << "  Candidates After SLM Scoring:\n";
    for (size_t i = 0; i < seg.candidates_size(); ++i) {
      const auto& cand = seg.candidate(i);
      std::cout << "    [" << i + 1 << "] \"" << cand.value << "\""
                << " | Combined Score: " << std::setprecision(3) << cand.combined_score
                << " (Mozc: " << cand.cost << ", SLM LogProb: " << cand.slm_logprob << ")\n";
    }

    const std::string& actual_winner = seg.candidate(0).value;
    if (actual_winner == tc.expected_winner) {
      std::cout << "  => RESULT: [PASSED] Successfully promoted \"" << actual_winner << "\"\n";
      passed_count++;
    } else {
      std::cout << "  => RESULT: [FAILED] Expected \"" << tc.expected_winner << "\", but got \"" << actual_winner << "\"\n";
    }
  }

  std::cout << "\n============================================================\n";
  std::cout << "SUMMARY: " << passed_count << " / " << cases.size() << " test cases passed.\n";
  std::cout << "============================================================\n";

  return (passed_count == cases.size()) ? 0 : 1;
}
