#ifndef MYASR_LLM_RERANKER_H_
#define MYASR_LLM_RERANKER_H_

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_vocab;

namespace myasr {

struct LlmRerankerConfig {
  std::string model_path;
  int n_ctx = 256;                        // Idea 8: Tightened from 512 to 256 for L3/SLC cache residency
  int n_seq_max = 16;                     // Idea 1 & 6: Support up to 16 parallel sequences
  int n_threads = 4;                      // Default decode threads (Mid/Prime cores)
  int n_threads_batch = 6;                // Idea 9: Batch prefill / tree decode threads
  bool use_q8_kv_cache = true;            // Idea 8: Q8_0 KV cache quantization (F16 fallback)
  float alpha_weight = 0.20f;
  float fast_path_threshold = 1.5f;
  float decisive_top1_gap_nats = 3.5f;    // Idea 3: Early exit when Top-1 leads by >= 3.5 nats
  float stage2_prune_margin_nats = 4.5f;  // Idea 3: Admissible lower-bound pruning margin
  int max_tree_batch_tokens = 32;         // Idea 1: Cap total nodes in single-pass prefix tree batch
};

// Idea 7: Multi-Slot Context Snapshot for 0ms Undo / Backspace / Cursor bounce
struct ContextLogitsSnapshot {
  uint64_t context_hash = 0;
  uint64_t last_access_seq = 0;
  int n_tokens = 0;
  std::vector<int32_t> tokens;
  std::vector<float> logits;
  float log_denom = 0.0f;
  std::unordered_map<std::string, float> candidate_nlls;
};

// Idea 1: Prefix-Tree node for packed single-pass Stage 2 evaluation
struct PrefixTreeNode {
  int32_t token = -1;
  int depth = 0;                          // 0-indexed depth relative to context end L_C
  int parent_node_idx = -1;               // -1 for root children at pos = L_C
  int batch_order_idx = -1;               // Index inside llama_batch
  bool needs_logits = false;              // True if at least one candidate needs P(next_token | this_node)
  float computed_log_denom = 0.0f;        // Idea 4: Computed once per node and shared across all child edges
  bool log_denom_ready = false;
  std::vector<int32_t> seq_ids;           // Active branch seq_ids (1 .. B) passing through this node
  std::vector<int> child_node_indices;    // Indices into flat tree_nodes vector
};

class LlmReranker {
 public:
  static constexpr size_t kSnapshotRingSize = 4;        // Idea 7: 4-slot LRU ring buffer
  static constexpr size_t kMaxTokenCacheEntries = 4096; // Idea 10: Pre-tokenized surface cache
  static constexpr int kCheckpointSeqId = 15;           // Reserved checkpoint slot in [0 .. n_seq_max-1]

  static LlmReranker& GetInstance();

  bool Initialize(const std::string& model_path, int n_threads = 4);
  void Release();

  bool ScoreCandidates(const std::string& context,
                       const std::vector<std::string>& candidates,
                       std::vector<float>* out_neg_logprobs);

  // Idea 3: Prior-aware scoring entry point
  bool ScoreCandidatesWithPriors(const std::string& context,
                                 const std::vector<std::string>& candidates,
                                 const std::vector<float>& prior_penalties,
                                 std::vector<float>* out_neg_logprobs);

  bool TryFastScoreCandidates(const std::string& context,
                              const std::vector<std::string>& candidates,
                              std::vector<float>* out_neg_logprobs,
                              std::vector<bool>* out_is_complete);

  void RequestAbortScoring();

  bool WarmContext(const std::string& context);

  // Idea 6: Multi-sequence parallel beam prediction
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
  bool RollbackOrRestoreContextState();
  static float ComputeLogDenominator(const float* logits, int n_vocab);

  // Idea 6: Precompute bitmask of valid Japanese/English tokens to filter multilingual noise
  void BuildValidTargetTokenBitmask();
  inline bool IsTokenValidForPrediction(int32_t token_id) const {
    if (token_id < 0) return false;
    size_t word_idx = static_cast<size_t>(token_id) >> 6;
    if (word_idx >= valid_token_bitmask_.size()) return false;
    return (valid_token_bitmask_[word_idx] & (1ULL << (token_id & 63))) != 0;
  }

  // Idea 9: Big.LITTLE core topology discovery & thread pinning
  void DetectPerformanceCores();
  void ApplyThreadAffinityAndScaling(int batch_token_count);

  // Idea 10: O(1) surface-to-BPE-tokens lookup with automatic cache management
  const std::vector<int32_t>& GetOrTokenizeCandidate(const std::string& surface);

  // Idea 7: Snapshot ring management
  const ContextLogitsSnapshot* FindSnapshotByHashLocked(uint64_t hash) const;
  const ContextLogitsSnapshot* FindSnapshotByTokensLocked(const std::vector<int32_t>& tokens) const;
  void UpsertSnapshot(uint64_t hash,
                      const std::vector<int32_t>& tokens,
                      const std::vector<float>& logits,
                      float log_denom,
                      const std::unordered_map<std::string, float>& candidate_nlls);

  LlmRerankerConfig config_;
  llama_model* model_ = nullptr;
  llama_context* ctx_ = nullptr;
  const llama_vocab* vocab_ = nullptr;
  bool initialized_ = false;

  // KV cache state (seq_id = 0 holds active context prefix)
  uint64_t cached_context_hash_ = 0;
  int cached_context_n_tokens_ = 0;
  std::vector<int32_t> cached_context_tokens_;
  std::vector<float> cached_context_logits_;
  float cached_context_log_denom_ = 0.0f;
  std::unordered_map<std::string, float> cached_candidate_nlls_;
  bool partial_seq_rm_supported_ = true;
  bool seq_pos_add_supported_ = true;    // Idea 2: Whether llama_memory_seq_add is supported
  bool has_checkpoint_ = false;
  int cache_hits_ = 0;
  int cache_misses_ = 0;

  // Idea 6: Precomputed valid JP/EN token bitmask (~19 KB)
  std::vector<uint64_t> valid_token_bitmask_;

  // Idea 7: 4-slot LRU Snapshot Ring Buffer for lock-free UI fast path & 0ms Undo recovery
  mutable std::mutex snapshot_mutex_;
  std::array<ContextLogitsSnapshot, kSnapshotRingSize> snapshot_ring_;
  uint64_t snapshot_access_counter_ = 0;

  // Idea 9: Performance CPU core IDs (excluding Little cores like Cortex-A520)
  std::vector<int> perf_core_cpus_;
  bool affinity_applied_ = false;

  // Idea 10: Tokenization cache (surface string -> BPE token sequence)
  std::unordered_map<std::string, std::vector<int32_t>> token_cache_;

  std::atomic<bool> abort_prediction_{false};
  std::atomic<bool> abort_scoring_{false};
  std::atomic<uint64_t> active_request_seq_{0};
  mutable std::mutex mutex_;
};

}  // namespace myasr

#endif  // MYASR_LLM_RERANKER_H_
