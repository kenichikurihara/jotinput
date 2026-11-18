#ifndef MYASR_LLM_RERANKER_H_
#define MYASR_LLM_RERANKER_H_

#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <unordered_map>
#include <atomic>
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

struct ContextLogitsSnapshot {
  uint64_t context_hash = 0;
  int n_tokens = 0;
  std::vector<float> logits;
  float log_denom = 0.0f;
  std::unordered_map<std::string, float> candidate_nlls;
};

class LlmReranker {
 public:
  static LlmReranker& GetInstance();

  bool Initialize(const std::string& model_path, int n_threads = 4);
  void Release();

  bool ScoreCandidates(const std::string& context,
                       const std::vector<std::string>& candidates,
                       std::vector<float>* out_neg_logprobs);

  bool TryFastScoreCandidates(const std::string& context,
                              const std::vector<std::string>& candidates,
                              std::vector<float>* out_neg_logprobs,
                              std::vector<bool>* out_is_complete);

  void RequestAbortScoring();

  bool WarmContext(const std::string& context);

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
  bool SaveContextState();
  bool RollbackOrRestoreContextState();
  static float ComputeLogDenominator(const float* logits, int n_vocab);

  LlmRerankerConfig config_;
  llama_model* model_ = nullptr;
  llama_context* ctx_ = nullptr;
  const llama_vocab* vocab_ = nullptr;
  bool initialized_ = false;

  // KV cache state
  uint64_t cached_context_hash_ = 0;
  int cached_context_n_tokens_ = 0;
  std::vector<int32_t> cached_context_tokens_;
  std::vector<float> cached_context_logits_;
  float cached_context_log_denom_ = 0.0f;
  std::unordered_map<std::string, float> cached_candidate_nlls_;
  bool partial_seq_rm_supported_ = true;
  std::vector<uint8_t> cached_context_state_;
  int cache_hits_ = 0;
  int cache_misses_ = 0;

  // Fast snapshot for lock-free 0ms candidate evaluation from UI thread
  mutable std::mutex snapshot_mutex_;
  ContextLogitsSnapshot snapshot_;

  std::atomic<bool> abort_prediction_{false};
  std::atomic<bool> abort_scoring_{false};
  std::atomic<uint64_t> active_request_seq_{0};
  mutable std::mutex mutex_;
};

}  // namespace myasr

#endif  // MYASR_LLM_RERANKER_H_
