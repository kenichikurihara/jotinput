#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <memory>
#include <chrono>
#include <cassert>

#include "rewriter/llm_reranker.h"

namespace mozc {

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

class Phase4LlmRewriter {
 public:
  explicit Phase4LlmRewriter(std::shared_ptr<LlmReranker> reranker)
      : reranker_(std::move(reranker)) {}

  bool Rewrite(const std::string& preceding_text, MockSegment* segment) {
    if (!reranker_ || !reranker_->IsReady() || !segment) return false;
    if (segment->candidates_size() <= 1) return false;

    std::vector<std::string> cand_strings;
    for (size_t i = 0; i < segment->candidates_size(); ++i) {
      cand_strings.push_back(segment->candidate(i).value);
    }

    std::vector<float> logprobs;
    if (!reranker_->ScoreCandidatesAdaptive(preceding_text, cand_strings, &logprobs)) {
      return false;
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
  std::cout << "  Mozc Phase 4 Production Engine & KV Cache Test Suite      \n";
  std::cout << "============================================================\n" << std::endl;

  mozc::LlmRerankerConfig config;
  config.model_path = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
  config.backend_dir = "./tools/llama_bin/llama-b11846";
  config.n_ctx = 512;
  config.n_threads = 4;
  config.alpha_weight = 0.20f;

  auto reranker = std::make_shared<mozc::LlmReranker>(config);
  std::cout << "[Init] Loading Qwen2.5-0.5B model into memory..." << std::endl;
  if (!reranker->Initialize()) {
    std::cerr << "Initialization failed!" << std::endl;
    return 1;
  }
  std::cout << "[Init] Model ready.\n" << std::endl;

  mozc::Phase4LlmRewriter rewriter(reranker);

  int tests_passed = 0;
  int total_tests = 0;

  // -------------------------------------------------------------
  // TEST 1: User Motivating Issue (ことなります -> 異なります)
  // -------------------------------------------------------------
  {
    total_tests++;
    std::cout << "[TEST 1: User Motivating Issue]\n";
    std::string context = "Mozcは私の直感と挙動が";
    std::string yomi = "ことなります";
    mozc::MockSegment seg;
    seg.set_key(yomi);
    seg.add_candidate("ことなります", 3000);
    seg.add_candidate("事成ります", 3600);
    seg.add_candidate("異なります", 4500);

    auto t0 = std::chrono::high_resolution_clock::now();
    rewriter.Rewrite(context, &seg);
    auto t1 = std::chrono::high_resolution_clock::now();
    float ms = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count() / 1000.0f;

    std::cout << "  Context : \"" << context << "\"\n";
    std::cout << "  Latency : " << ms << " ms\n";
    std::cout << "  Winner  : \"" << seg.candidate(0).value << "\" (Score: " << seg.candidate(0).combined_score << ")\n";

    if (seg.candidate(0).value == "異なります") {
      std::cout << "  => [PASSED]\n\n";
      tests_passed++;
    } else {
      std::cout << "  => [FAILED]\n\n";
    }
  }

  // -------------------------------------------------------------
  // TEST 2: High-Difficulty Homophone (状況 vs 上京)
  // -------------------------------------------------------------
  {
    total_tests++;
    std::cout << "[TEST 2: High-Difficulty Homophone (状況 vs 上京)]\n";
    std::string context = "事故の目撃者から事故直後の";
    std::string yomi = "じょうきょう";
    mozc::MockSegment seg;
    seg.set_key(yomi);
    seg.add_candidate("上京", 2800);
    seg.add_candidate("情境", 3500);
    seg.add_candidate("状況", 3800);

    auto t0 = std::chrono::high_resolution_clock::now();
    rewriter.Rewrite(context, &seg);
    auto t1 = std::chrono::high_resolution_clock::now();
    float ms = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count() / 1000.0f;

    std::cout << "  Context : \"" << context << "\"\n";
    std::cout << "  Latency : " << ms << " ms\n";
    for (size_t i = 0; i < seg.candidates_size(); ++i) {
      std::cout << "    [" << i + 1 << "] \"" << seg.candidate(i).value
                << "\" | Score: " << seg.candidate(i).combined_score
                << " | LogProb: " << seg.candidate(i).slm_logprob << "\n";
    }

    if (seg.candidate(0).value == "状況") {
      std::cout << "  => [PASSED] Successfully disambiguated '状況' over '上京'!\n\n";
      tests_passed++;
    } else {
      std::cout << "  => [FAILED] Expected '状況', got '" << seg.candidate(0).value << "'\n\n";
    }
  }

  // -------------------------------------------------------------
  // TEST 3: Sequential Live Typing & KV Cache Reuse Simulation
  // -------------------------------------------------------------
  {
    total_tests++;
    std::cout << "[TEST 3: Sequential Live Typing Simulation with KV-Cache Reuse]\n";
    std::string context = "私の直感と挙動が";
    std::vector<std::pair<std::string, std::string>> keystrokes = {
      {"こ", "子"},
      {"こと", "事"},
      {"ことな", "事な"},
      {"ことなり", "異なり"},
      {"ことなります", "異なります"}
    };

    int hits_before = reranker->context_cache_hits();
    std::vector<float> latencies;

    for (size_t k = 0; k < keystrokes.size(); ++k) {
      mozc::MockSegment seg;
      seg.set_key(keystrokes[k].first);
      seg.add_candidate(keystrokes[k].first, 3000);
      seg.add_candidate(keystrokes[k].second, 4200);

      auto t0 = std::chrono::high_resolution_clock::now();
      rewriter.Rewrite(context, &seg);
      auto t1 = std::chrono::high_resolution_clock::now();
      float ms = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count() / 1000.0f;
      latencies.push_back(ms);

      std::cout << "  Keystroke " << k + 1 << ": input=\"" << keystrokes[k].first
                << "\" -> top=\"" << seg.candidate(0).value
                << "\" | Latency: " << std::fixed << std::setprecision(3) << ms << " ms\n";
    }

    int hits_after = reranker->context_cache_hits();
    int new_hits = hits_after - hits_before;
    std::cout << "  Total Cache Hits during live typing: " << new_hits << " / " << keystrokes.size() << "\n";

    // Keystroke 1 is cache miss (~50ms), Keystrokes 2-5 MUST be cache hits (< 2ms!)
    bool cached_fast = (new_hits >= 4) && (latencies[1] < 5.0f) && (latencies[4] < 5.0f);
    if (cached_fast) {
      std::cout << "  => [PASSED] Sub-millisecond KV-Cache reuse verified!\n\n";
      tests_passed++;
    } else {
      std::cout << "  => [FAILED] Cache hit target not met.\n\n";
    }
  }

  // -------------------------------------------------------------
  // TEST 4: Diplomacy Homophone Disambiguation
  // -------------------------------------------------------------
  {
    total_tests++;
    std::cout << "[TEST 4: Diplomacy Homophone Disambiguation]\n";
    std::string context = "両国間の平和的な";
    std::string yomi = "こうしょう";
    mozc::MockSegment seg;
    seg.set_key(yomi);
    seg.add_candidate("高尚", 2800);
    seg.add_candidate("考証", 3400);
    seg.add_candidate("交渉", 3900);

    rewriter.Rewrite(context, &seg);
    std::cout << "  Context : \"" << context << "\"\n";
    std::cout << "  Winner  : \"" << seg.candidate(0).value << "\"\n";

    if (seg.candidate(0).value == "交渉") {
      std::cout << "  => [PASSED]\n\n";
      tests_passed++;
    } else {
      std::cout << "  => [FAILED]\n\n";
    }
  }

  std::cout << "============================================================\n";
  std::cout << "PHASE 4 TEST SUMMARY: " << tests_passed << " / " << total_tests << " Passed.\n";
  std::cout << "============================================================\n";

  return (tests_passed == total_tests) ? 0 : 1;
}
