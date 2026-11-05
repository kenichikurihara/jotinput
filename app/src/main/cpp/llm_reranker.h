#ifndef MYASR_LLM_RERANKER_H_
#define MYASR_LLM_RERANKER_H_

#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <cstdint>

struct llama_model;
struct llama_context;
struct llama_vocab;

namespace myasr {

struct LlmRerankerConfig {
  std::string model_path;
  int n_ctx = 512;
  int n_threads = 4;
  float alpha_weight = 0.20f;
  float fast_path_threshold = 1.5f;
};

class LlmReranker {
 public:
  static LlmReranker& GetInstance();

  bool Initialize(const std::string& model_path, int n_threads = 4);
  void Release();

  bool ScoreCandidates(const std::string& context,
                       const std::vector<std::string>& candidates,
                       std::vector<float>* out_neg_logprobs);

  bool PredictNextPhrases(const std::string& context,
                          int max_candidates,
                          int max_tokens,
                          std::vector<std::string>* out_predictions);

  bool IsReady() const { return initialized_; }

 private:
  LlmReranker() = default;
  ~LlmReranker();

  LlmReranker(const LlmReranker&) = delete;
  LlmReranker& operator=(const LlmReranker&) = delete;

  uint64_t ComputeContextHash(const std::string& context) const;
  bool EnsureContextCached(const std::string& context);

  LlmRerankerConfig config_;
  llama_model* model_ = nullptr;
  llama_context* ctx_ = nullptr;
  const llama_vocab* vocab_ = nullptr;
  bool initialized_ = false;

  // KV cache state
  uint64_t cached_context_hash_ = 0;
  int cached_context_n_tokens_ = 0;
  std::vector<float> cached_context_logits_;
  float cached_context_log_denom_ = 0.0f;
  int cache_hits_ = 0;
  int cache_misses_ = 0;

  mutable std::mutex mutex_;
};

}  // namespace myasr

#endif  // MYASR_LLM_RERANKER_H_
