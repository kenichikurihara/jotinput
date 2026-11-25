#include "llm_reranker.h"

#include <algorithm>
#include <cctype>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#if defined(__linux__) || defined(__ANDROID__)
#include <sched.h>
#include <unistd.h>
#endif

#include "llama.h"

#define TAG "MyAsrLlm"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, "[INFO] " TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, "[ERROR] " TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGW(...) do { std::fprintf(stderr, "[WARN] " TAG ": " __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace myasr {

static constexpr uint64_t FNV_OFFSET_BASIS = 14695981039346656037ULL;
static constexpr uint64_t FNV_PRIME = 1099511628211ULL;

namespace {

std::string TrimWhitespace(const std::string& s) {
  size_t start = 0;
  size_t end = s.size();
  auto is_ascii_ws = [](char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  };
  while (start < end) {
    if (is_ascii_ws(s[start])) {
      ++start;
    } else if (start + 3 <= end &&
               static_cast<unsigned char>(s[start]) == 0xE3 &&
               static_cast<unsigned char>(s[start + 1]) == 0x80 &&
               static_cast<unsigned char>(s[start + 2]) == 0x80) {
      start += 3;
    } else {
      break;
    }
  }
  while (end > start) {
    if (is_ascii_ws(s[end - 1])) {
      --end;
    } else if (end >= start + 3 &&
               static_cast<unsigned char>(s[end - 3]) == 0xE3 &&
               static_cast<unsigned char>(s[end - 2]) == 0x80 &&
               static_cast<unsigned char>(s[end - 1]) == 0x80) {
      end -= 3;
    } else {
      break;
    }
  }
  return s.substr(start, end - start);
}

bool EndsWith(const std::string& s, const std::string& suffix) {
  return s.size() >= suffix.size() &&
         s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool IsCompleteUtf8(const std::string& s) {
  if (s.empty()) return true;
  const size_t n = s.size();
  size_t i = n;
  // Walk back at most 4 bytes to find the start of the last UTF-8 codepoint
  int cont = 0;
  while (i > 0 && cont < 4) {
    --i;
    unsigned char b = static_cast<unsigned char>(s[i]);
    if ((b & 0x80) == 0) {
      return cont == 0;
    } else if ((b & 0xC0) == 0x80) {
      ++cont;
    } else {
      int expected = 0;
      if ((b & 0xE0) == 0xC0) expected = 1;
      else if ((b & 0xF0) == 0xE0) expected = 2;
      else if ((b & 0xF8) == 0xF0) expected = 3;
      else return false;
      return cont == expected;
    }
  }
  return false;
}

void SanitizeIncompleteUtf8Tail(std::string* s) {
  if (!s || s->empty() || IsCompleteUtf8(*s)) return;
  size_t i = s->size();
  int steps = 0;
  while (i > 0 && steps < 4) {
    --i;
    ++steps;
    unsigned char b = static_cast<unsigned char>((*s)[i]);
    if ((b & 0x80) == 0) {
      s->resize(i + 1);
      return;
    }
    if ((b & 0xC0) != 0x80) {
      s->resize(i);
      return;
    }
  }
  s->clear();
}

size_t CountUtf8Chars(const std::string& s) {
  size_t count = 0;
  for (unsigned char b : s) {
    if ((b & 0xC0) != 0x80) ++count;
  }
  return count;
}

bool IsBunsetsuBoundary(const std::string& s) {
  if (!IsCompleteUtf8(s) || CountUtf8Chars(s) < 2) return false;
  static const char* kPredicateEndings[] = {
      "ております", "ています", "いたしました", "いたします",
      "ませんでした", "ました", "ません", "ます",
      "でした", "です", "ください",
      "する", "した", "して", "いる", "いた", "ない", "たい"
  };
  for (const char* ending : kPredicateEndings) {
    if (EndsWith(s, ending)) return true;
  }
  return false;
}

bool IsTerminalPunctuation(const std::string& s) {
  return EndsWith(s, "。") || EndsWith(s, "！") || EndsWith(s, "？") ||
         EndsWith(s, "、") || EndsWith(s, ".") || EndsWith(s, "!") || EndsWith(s, "?");
}

void AppendTokenPieceAndCheckTermination(const llama_vocab* vocab,
                                         llama_token tok,
                                         std::vector<llama_token>* tokens,
                                         std::string* text,
                                         bool* finished) {
  char next_piece_buf[256];
  int npl = llama_token_to_piece(vocab, tok, next_piece_buf, sizeof(next_piece_buf), 0, false);
  if (npl <= 0) {
    *finished = true;
    return;
  }
  std::string next_piece(next_piece_buf, npl);

  size_t nl_pos = next_piece.find_first_of("\n\r");
  if (nl_pos != std::string::npos) {
    if (nl_pos > 0) {
      *text += next_piece.substr(0, nl_pos);
    }
    *finished = true;
    return;
  }

  if (next_piece == "。" || next_piece == "！" || next_piece == "？") {
    if (!EndsWith(*text, next_piece)) {
      *text += next_piece;
    }
    *finished = true;
    return;
  }

  if (next_piece == "、") {
    *finished = true;
    return;
  }

  tokens->push_back(tok);
  *text += next_piece;

  if (IsBunsetsuBoundary(*text)) {
    *finished = true;
  }
}

}  // namespace

LlmReranker& LlmReranker::GetInstance() {
  static LlmReranker instance;
  return instance;
}

LlmReranker::~LlmReranker() {
  Release();
}

void LlmReranker::Release() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (ctx_) {
    llama_free(ctx_);
    ctx_ = nullptr;
  }
  if (model_) {
    llama_model_free(model_);
    model_ = nullptr;
  }
  initialized_ = false;
  cached_context_hash_ = 0;
  cached_context_n_tokens_ = 0;
  cached_context_tokens_.clear();
  cached_context_logits_.clear();
  cached_candidate_nlls_.clear();
  cached_exact_candidate_nlls_.clear();
  cached_context_log_denom_ = 0.0f;
  valid_token_bitmask_.clear();
  valid_token_ids_.clear();
  token_cache_.clear();
  partial_seq_rm_supported_ = true;
  seq_pos_add_supported_ = true;
  has_checkpoint_ = false;
  kv_cache_synced_with_context_ = true;
  affinity_applied_ = false;
  stats_ = LlmRerankerStats{};
  abort_prediction_.store(true);
  abort_scoring_.store(true);
  {
    std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
    for (auto& snap : snapshot_ring_) {
      snap.context_hash = 0;
      snap.last_access_seq = 0;
      snap.n_tokens = 0;
      snap.tokens.clear();
      snap.logits.clear();
      snap.candidate_nlls.clear();
      snap.exact_candidate_nlls.clear();
      snap.log_denom = 0.0f;
    }
    snapshot_access_counter_ = 0;
  }
  LOGI("LlmReranker released.");
}

void LlmReranker::SetConfig(const LlmRerankerConfig& config) {
  std::lock_guard<std::mutex> lock(mutex_);
  std::string prev_model_path = config_.model_path;
  config_ = config;
  if (config_.model_path.empty()) {
    config_.model_path = prev_model_path;
  }
}

void LlmReranker::ResetCaches() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (ctx_) {
    llama_memory_t mem = llama_get_memory(ctx_);
    llama_memory_clear(mem, true);
  }
  cached_context_hash_ = 0;
  cached_context_n_tokens_ = 0;
  cached_context_tokens_.clear();
  cached_context_logits_.clear();
  cached_candidate_nlls_.clear();
  cached_exact_candidate_nlls_.clear();
  cached_context_log_denom_ = 0.0f;
  token_cache_.clear();
  has_checkpoint_ = false;
  kv_cache_synced_with_context_ = true;
  cache_hits_ = 0;
  cache_misses_ = 0;
  stats_ = LlmRerankerStats{};
  {
    std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
    for (auto& snap : snapshot_ring_) {
      snap.context_hash = 0;
      snap.last_access_seq = 0;
      snap.n_tokens = 0;
      snap.tokens.clear();
      snap.logits.clear();
      snap.candidate_nlls.clear();
      snap.exact_candidate_nlls.clear();
      snap.log_denom = 0.0f;
    }
    snapshot_access_counter_ = 0;
  }
}

LlmRerankerStats LlmReranker::GetStats() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return stats_;
}

void LlmReranker::ResetStats() {
  std::lock_guard<std::mutex> lock(mutex_);
  cache_hits_ = 0;
  cache_misses_ = 0;
  stats_ = LlmRerankerStats{};
}

void LlmReranker::RequestAbortScoring() {
  abort_scoring_.store(true);
  abort_prediction_.store(true);
  active_request_seq_.fetch_add(1);
}

uint64_t LlmReranker::ComputeContextHash(const std::string& context) const {
  uint64_t hash = FNV_OFFSET_BASIS;
  for (unsigned char c : context) {
    hash ^= static_cast<uint64_t>(c);
    hash *= FNV_PRIME;
  }
  return hash;
}

void LlmReranker::DetectPerformanceCores() {
  perf_core_cpus_.clear();
#if defined(__linux__) || defined(__ANDROID__)
  long num_cpus_long = sysconf(_SC_NPROCESSORS_CONF);
  int num_cpus = (num_cpus_long > 0 && num_cpus_long <= 128) ? static_cast<int>(num_cpus_long) : 8;
  std::vector<std::pair<int, long>> cpu_freqs;
  long min_freq = LONG_MAX;
  long max_freq = 0;

  for (int cpu = 0; cpu < num_cpus; ++cpu) {
    std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq";
    std::ifstream ifs(path);
    long freq = 0;
    if (ifs >> freq && freq > 0) {
      cpu_freqs.emplace_back(cpu, freq);
      if (freq < min_freq) min_freq = freq;
      if (freq > max_freq) max_freq = freq;
    }
  }

  // Exclude Little cluster if heterogeneous topology exists (max_freq > min_freq * 1.15)
  if (!cpu_freqs.empty() && max_freq > (min_freq * 115) / 100) {
    for (const auto& kv : cpu_freqs) {
      if (kv.second > min_freq) {
        perf_core_cpus_.push_back(kv.first);
      }
    }
  }
#endif
  if (perf_core_cpus_.size() >= 2) {
    config_.n_threads = std::min<int>(4, static_cast<int>(perf_core_cpus_.size()));
    config_.n_threads_batch = std::min<int>(6, static_cast<int>(perf_core_cpus_.size()));
  }
}

void LlmReranker::ApplyThreadAffinityAndScaling(int batch_token_count) {
  if (!ctx_) return;
#if defined(__linux__) || defined(__ANDROID__)
  if (!affinity_applied_ && !perf_core_cpus_.empty()) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int cpu : perf_core_cpus_) {
      if (cpu >= 0 && cpu < CPU_SETSIZE) {
        CPU_SET(cpu, &cpuset);
      }
    }
    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
    affinity_applied_ = true;
  }
#endif
  int active_threads = (batch_token_count <= 2)
      ? std::min(3, config_.n_threads)
      : ((batch_token_count <= 8) ? config_.n_threads : config_.n_threads_batch);
  llama_set_n_threads(ctx_, active_threads, active_threads);
}

void LlmReranker::BuildValidTargetTokenBitmask() {
  int n_vocab = llama_vocab_n_tokens(vocab_);
  valid_token_bitmask_.assign((n_vocab + 63) / 64, 0ULL);
  valid_token_ids_.clear();
  valid_token_ids_.reserve(32768);

  char buf[256];
  for (int32_t v = 0; v < n_vocab; ++v) {
    if (llama_vocab_is_eog(vocab_, v) || llama_vocab_is_control(vocab_, v)) continue;
    int len = llama_token_to_piece(vocab_, v, buf, sizeof(buf), 0, false);
    if (len <= 0) continue;

    bool valid = true;
    bool has_content = false;
    int i = 0;
    while (i < len) {
      unsigned char c = static_cast<unsigned char>(buf[i]);
      if (c <= 0x7F) {
        if (c < 0x20 && c != '\n') {
          valid = false;
          break;
        }
        if (std::isalnum(c)) has_content = true;
        i += 1;
      } else if ((c & 0xF0) == 0xE0 && i + 2 < len) {
        uint32_t cp = ((c & 0x0F) << 12) |
                      ((static_cast<unsigned char>(buf[i + 1]) & 0x3F) << 6) |
                      (static_cast<unsigned char>(buf[i + 2]) & 0x3F);
        if ((cp >= 0x3000 && cp <= 0x30FF) ||
            (cp >= 0x4E00 && cp <= 0x9FFF) ||
            (cp >= 0xFF01 && cp <= 0xFF60)) {
          has_content = true;
        } else {
          valid = false;
          break;
        }
        i += 3;
      } else {
        valid = false;
        break;
      }
    }
    if (valid && has_content) {
      valid_token_bitmask_[static_cast<size_t>(v) >> 6] |= (1ULL << (v & 63));
      valid_token_ids_.push_back(v);
    }
  }
}

bool LlmReranker::Initialize(const std::string& model_path, int n_threads) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (initialized_ && config_.model_path == model_path) {
    return true;
  }
  if (initialized_) {
    if (ctx_) { llama_free(ctx_); ctx_ = nullptr; }
    if (model_) { llama_model_free(model_); model_ = nullptr; }
    initialized_ = false;
  }

  config_.model_path = model_path;
  config_.n_threads = n_threads;
  config_.n_threads_batch = std::max(n_threads, 6);
  DetectPerformanceCores();

  // Suppress verbose internal model logs
  llama_log_set([](ggml_log_level level, const char* text, void* user_data) {}, nullptr);

  llama_backend_init();

  llama_model_params mparams = llama_model_default_params();
  model_ = llama_model_load_from_file(config_.model_path.c_str(), mparams);
  if (!model_) {
    LOGE("Failed to load SLM model from: %s", config_.model_path.c_str());
    return false;
  }

  vocab_ = llama_model_get_vocab(model_);

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = config_.n_ctx;
  cparams.n_batch = config_.n_ctx;
  cparams.n_ubatch = config_.n_ctx;
  cparams.n_seq_max = config_.n_seq_max;
  cparams.n_threads = config_.n_threads;
  cparams.n_threads_batch = config_.n_threads_batch;
  cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
  if (config_.use_q8_kv_cache) {
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
  }

  ctx_ = llama_init_from_model(model_, cparams);
  if (!ctx_ && config_.use_q8_kv_cache) {
    LOGW("Q8_0 KV cache rejected by model architecture, falling back to F16 KV cache.");
    cparams.type_k = GGML_TYPE_F16;
    cparams.type_v = GGML_TYPE_F16;
    ctx_ = llama_init_from_model(model_, cparams);
  }
  if (!ctx_) {
    LOGE("Failed to initialize llama context.");
    llama_model_free(model_);
    model_ = nullptr;
    return false;
  }

  // Set cooperative abort callback to bail early when user types next character
  llama_set_abort_callback(ctx_, [](void* data) -> bool {
    auto* self = static_cast<LlmReranker*>(data);
    return self->abort_scoring_.load();
  }, this);

  BuildValidTargetTokenBitmask();

  initialized_ = true;
  cached_context_hash_ = 0;
  cached_context_n_tokens_ = 0;
  cached_context_tokens_.clear();
  cached_context_logits_.clear();
  cached_candidate_nlls_.clear();
  cached_exact_candidate_nlls_.clear();
  token_cache_.clear();
  partial_seq_rm_supported_ = true;
  seq_pos_add_supported_ = true;
  has_checkpoint_ = false;
  kv_cache_synced_with_context_ = true;
  affinity_applied_ = false;
  cache_hits_ = 0;
  cache_misses_ = 0;
  stats_ = LlmRerankerStats{};
  abort_scoring_.store(false);
  abort_prediction_.store(false);
  LOGI("LlmReranker initialized successfully (n_ctx=%d, n_seq_max=%d, perf_cores=%zu): %s",
       config_.n_ctx, config_.n_seq_max, perf_core_cpus_.size(), config_.model_path.c_str());
  return true;
}

float LlmReranker::ComputeLogDenominator(const float* logits, int n_vocab) {
  float max_l = -1e9f;
  for (int v = 0; v < n_vocab; ++v) {
    if (logits[v] > max_l) max_l = logits[v];
  }
  float sum_exp = 0.0f;
  const float threshold = max_l - 16.0f;
  for (int v = 0; v < n_vocab; ++v) {
    if (logits[v] >= threshold) {
      sum_exp += std::exp(logits[v] - max_l);
    }
  }
  return max_l + std::log(sum_exp);
}

float LlmReranker::ComputeLogDenominatorAdaptive(
    const float* logits,
    int n_vocab,
    const std::vector<int32_t>* extra_tokens) const {
  if (!config_.use_fast_subvocab_lse || valid_token_ids_.empty()) {
    return ComputeLogDenominator(logits, n_vocab);
  }
  float max_l = -1e9f;
  for (int32_t v : valid_token_ids_) {
    if (logits[v] > max_l) max_l = logits[v];
  }
  if (extra_tokens) {
    for (int32_t v : *extra_tokens) {
      if (v >= 0 && v < n_vocab && !IsTokenValidForPrediction(v) && logits[v] > max_l) {
        max_l = logits[v];
      }
    }
  }
  float sum_exp = 0.0f;
  const float threshold = max_l - 16.0f;
  for (int32_t v : valid_token_ids_) {
    if (logits[v] >= threshold) {
      sum_exp += std::exp(logits[v] - max_l);
    }
  }
  if (extra_tokens) {
    for (int32_t v : *extra_tokens) {
      if (v >= 0 && v < n_vocab && !IsTokenValidForPrediction(v) && logits[v] >= threshold) {
        sum_exp += std::exp(logits[v] - max_l);
      }
    }
  }
  return max_l + std::log(std::max(1e-12f, sum_exp));
}

const std::vector<int32_t>& LlmReranker::GetOrTokenizeCandidate(const std::string& surface) {
  auto it = token_cache_.find(surface);
  if (it != token_cache_.end()) {
    return it->second;
  }
  if (token_cache_.size() >= kMaxTokenCacheEntries) {
    token_cache_.clear();
  }
  std::vector<int32_t> tokens(64);
  int n = llama_tokenize(vocab_, surface.data(), surface.size(),
                         tokens.data(), tokens.size(), false, false);
  if (n > 0) {
    tokens.resize(n);
  } else {
    tokens.clear();
  }
  auto inserted = token_cache_.emplace(surface, std::move(tokens));
  return inserted.first->second;
}

const ContextLogitsSnapshot* LlmReranker::FindSnapshotByHashLocked(uint64_t hash) const {
  const ContextLogitsSnapshot* best = nullptr;
  for (const auto& snap : snapshot_ring_) {
    if (snap.context_hash == hash && !snap.logits.empty()) {
      best = &snap;
      break;
    }
  }
  return best;
}

const ContextLogitsSnapshot* LlmReranker::FindSnapshotByTokensLocked(
    const std::vector<int32_t>& tokens) const {
  for (const auto& snap : snapshot_ring_) {
    if (snap.tokens == tokens && !snap.logits.empty()) {
      return &snap;
    }
  }
  return nullptr;
}

const ContextLogitsSnapshot* LlmReranker::FindSnapshotBySuffixLocked(
    const std::vector<int32_t>& tokens,
    int min_suffix_tokens) const {
  if (min_suffix_tokens <= 0 || static_cast<int>(tokens.size()) < min_suffix_tokens) {
    return nullptr;
  }
  const size_t suffix_len = static_cast<size_t>(min_suffix_tokens);
  const ContextLogitsSnapshot* best = nullptr;
  uint64_t best_seq = 0;
  for (const auto& snap : snapshot_ring_) {
    if (snap.logits.empty() || snap.tokens.size() < suffix_len) continue;
    bool match = std::equal(
        tokens.end() - suffix_len, tokens.end(),
        snap.tokens.end() - suffix_len);
    if (match && snap.last_access_seq >= best_seq) {
      best = &snap;
      best_seq = snap.last_access_seq;
    }
  }
  return best;
}

void LlmReranker::UpsertSnapshot(
    uint64_t hash,
    const std::vector<int32_t>& tokens,
    const std::vector<float>& logits,
    float log_denom,
    const std::unordered_map<std::string, float>& candidate_nlls,
    const std::unordered_map<std::string, float>& exact_candidate_nlls) {
  std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
  snapshot_access_counter_++;
  size_t target_idx = 0;
  uint64_t oldest_seq = UINT64_MAX;
  for (size_t i = 0; i < kSnapshotRingSize; ++i) {
    if (snapshot_ring_[i].context_hash == hash) {
      target_idx = i;
      break;
    }
    if (snapshot_ring_[i].last_access_seq < oldest_seq) {
      oldest_seq = snapshot_ring_[i].last_access_seq;
      target_idx = i;
    }
  }
  auto& slot = snapshot_ring_[target_idx];
  slot.context_hash = hash;
  slot.last_access_seq = snapshot_access_counter_;
  slot.n_tokens = static_cast<int>(tokens.size());
  slot.tokens = tokens;
  slot.logits = logits;
  slot.log_denom = log_denom;
  slot.candidate_nlls = candidate_nlls;
  slot.exact_candidate_nlls = exact_candidate_nlls;
}

bool LlmReranker::RollbackOrRestoreContextState() {
  if (!ctx_) return false;
  llama_memory_t mem = llama_get_memory(ctx_);
  if (partial_seq_rm_supported_ && llama_memory_seq_rm(mem, 0, cached_context_n_tokens_, -1)) {
    return true;
  }
  partial_seq_rm_supported_ = false;
  if (has_checkpoint_) {
    llama_memory_seq_rm(mem, 0, -1, -1);
    llama_memory_seq_cp(mem, kCheckpointSeqId, 0, -1, -1);
    return true;
  }
  // First-time fallback before checkpoint was created: clear memory, re-decode, and checkpoint
  llama_memory_clear(mem, true);
  if (cached_context_tokens_.empty()) return false;
  int n_tokens = static_cast<int>(cached_context_tokens_.size());
  ApplyThreadAffinityAndScaling(n_tokens);
  llama_batch batch = llama_batch_init(n_tokens, 0, 1);
  for (int i = 0; i < n_tokens; ++i) {
    batch.token[i] = cached_context_tokens_[i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = (i == n_tokens - 1);
  }
  batch.n_tokens = n_tokens;
  int ret = llama_decode(ctx_, batch);
  llama_batch_free(batch);
  if (ret != 0) return false;
  llama_memory_seq_cp(mem, 0, kCheckpointSeqId, -1, -1);
  has_checkpoint_ = true;
  return true;
}

bool LlmReranker::DecodeFullContextIntoKvLocked(const std::vector<int32_t>& context_tokens) {
  if (!ctx_ || context_tokens.empty()) return false;
  llama_memory_t mem = llama_get_memory(ctx_);
  llama_memory_clear(mem, true);
  has_checkpoint_ = false;

  const int n_tokens = static_cast<int>(context_tokens.size());
  ApplyThreadAffinityAndScaling(n_tokens);
  llama_batch batch = llama_batch_init(n_tokens, 0, 1);
  for (int i = 0; i < n_tokens; ++i) {
    batch.token[i] = context_tokens[i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = (i == n_tokens - 1);
  }
  batch.n_tokens = n_tokens;
  int ret = llama_decode(ctx_, batch);
  llama_batch_free(batch);
  if (ret != 0) return false;

  stats_.prefill_tokens_decoded += static_cast<uint64_t>(n_tokens);
  float* raw_logits = llama_get_logits_ith(ctx_, -1);
  if (!raw_logits) return false;
  const int n_vocab = llama_vocab_n_tokens(vocab_);
  cached_context_logits_.assign(raw_logits, raw_logits + n_vocab);
  cached_context_log_denom_ = ComputeLogDenominatorAdaptive(cached_context_logits_.data(), n_vocab);
  cached_context_n_tokens_ = n_tokens;
  cached_context_tokens_ = context_tokens;
  kv_cache_synced_with_context_ = true;
  if (!partial_seq_rm_supported_) {
    llama_memory_seq_rm(mem, kCheckpointSeqId, -1, -1);
    llama_memory_seq_cp(mem, 0, kCheckpointSeqId, -1, -1);
    has_checkpoint_ = true;
  }
  UpsertSnapshot(cached_context_hash_, cached_context_tokens_, cached_context_logits_,
                 cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
  return true;
}

bool LlmReranker::EnsureContextCached(const std::string& context) {
  std::string effective_context = context.empty() ? "<s>" : context;
  uint64_t current_hash = ComputeContextHash(effective_context);
  if (current_hash == cached_context_hash_ && cached_context_n_tokens_ > 0 && !cached_context_tokens_.empty()) {
    cache_hits_++;
    stats_.cache_hits++;
    return true;
  }

  llama_memory_t mem = llama_get_memory(ctx_);

  std::vector<llama_token> context_tokens(config_.n_ctx);
  int n_tokens = llama_tokenize(vocab_, effective_context.data(), effective_context.size(),
                                context_tokens.data(), context_tokens.size(),
                                true, false);
  if (n_tokens <= 0) {
    LOGW("Context tokenization failed or empty context.");
    cached_context_hash_ = 0;
    cached_context_n_tokens_ = 0;
    cached_context_tokens_.clear();
    has_checkpoint_ = false;
    kv_cache_synced_with_context_ = true;
    cache_misses_++;
    stats_.cache_misses++;
    return false;
  }
  context_tokens.resize(n_tokens);

  // If the KV cache was not synced due to a prior suffix-approx hit, LCP/seq_add cannot reuse KV directly
  int old_n = kv_cache_synced_with_context_ ? static_cast<int>(cached_context_tokens_.size()) : 0;
  int common_prefix_len = 0;
  int max_check = std::min(old_n, n_tokens);
  while (common_prefix_len < max_check &&
         cached_context_tokens_[common_prefix_len] == context_tokens[common_prefix_len]) {
    common_prefix_len++;
  }

  // CASE A: Exact token sequence match
  if (kv_cache_synced_with_context_ && common_prefix_len == n_tokens && old_n == n_tokens) {
    if (!partial_seq_rm_supported_ && !has_checkpoint_) {
      llama_memory_seq_rm(mem, kCheckpointSeqId, -1, -1);
      llama_memory_seq_cp(mem, 0, kCheckpointSeqId, -1, -1);
      has_checkpoint_ = true;
    }
    cached_context_hash_ = current_hash;
    cache_hits_++;
    stats_.cache_hits++;
    return true;
  }

  // CASE B (Idea 7): Prefix Truncation on Undo / Backspace (common_prefix_len == n_tokens < old_n)
  if (kv_cache_synced_with_context_ && common_prefix_len == n_tokens && n_tokens < old_n && partial_seq_rm_supported_) {
    const ContextLogitsSnapshot* ring_snap = nullptr;
    {
      std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
      ring_snap = FindSnapshotByHashLocked(current_hash);
      if (!ring_snap) {
        ring_snap = FindSnapshotByTokensLocked(context_tokens);
      }
      if (ring_snap != nullptr && llama_memory_seq_rm(mem, 0, n_tokens, -1)) {
        cached_context_hash_ = current_hash;
        cached_context_n_tokens_ = n_tokens;
        cached_context_tokens_ = std::move(context_tokens);
        cached_context_logits_ = ring_snap->logits;
        cached_context_log_denom_ = ring_snap->log_denom;
        cached_candidate_nlls_ = ring_snap->candidate_nlls;
        cached_exact_candidate_nlls_ = ring_snap->exact_candidate_nlls;
        has_checkpoint_ = false;
        kv_cache_synced_with_context_ = true;
        cache_hits_++;
        stats_.cache_hits++;
        return true;  // 0ms instant Undo recovery without llama_decode!
      }
    }
    if (n_tokens >= 1 && llama_memory_seq_rm(mem, 0, n_tokens - 1, -1)) {
      common_prefix_len = n_tokens - 1;
    } else if (n_tokens >= 1) {
      partial_seq_rm_supported_ = false;
    }
  }

  // CASE C (Idea 2): Sliding Window Overlap Detection & RoPE Position-Shift (seq_add)
  if (kv_cache_synced_with_context_ && common_prefix_len <= 1 && old_n >= 6 && n_tokens >= 6 &&
      partial_seq_rm_supported_ && seq_pos_add_supported_ &&
      cached_context_tokens_[0] == context_tokens[0]) {
    int best_shift_k = 0;
    int best_overlap = 0;
    int max_shift = std::min(old_n - 4, 24);
    for (int k = 1; k <= max_shift; ++k) {
      int overlap = 0;
      while (1 + k + overlap < old_n && 1 + overlap < n_tokens &&
             cached_context_tokens_[1 + k + overlap] == context_tokens[1 + overlap]) {
        overlap++;
      }
      if (overlap >= 4 && overlap > best_overlap) {
        best_overlap = overlap;
        best_shift_k = k;
        if (1 + k + overlap == old_n) break;
      }
    }

    if (best_shift_k > 0 && best_overlap >= 4) {
      bool rm_head_ok = llama_memory_seq_rm(mem, 0, 1, 1 + best_shift_k);
      int kept_old_end = 1 + best_shift_k + best_overlap;
      bool rm_tail_ok = (kept_old_end >= old_n) || llama_memory_seq_rm(mem, 0, kept_old_end, -1);
      if (rm_head_ok && rm_tail_ok) {
        llama_memory_seq_add(mem, 0, 1 + best_shift_k, kept_old_end, -best_shift_k);
        common_prefix_len = 1 + best_overlap;
        if (common_prefix_len == n_tokens) {
          if (llama_memory_seq_rm(mem, 0, n_tokens - 1, -1)) {
            common_prefix_len = n_tokens - 1;
          } else {
            llama_memory_clear(mem, true);
            common_prefix_len = 0;
          }
        }
      } else {
        seq_pos_add_supported_ = false;
        llama_memory_clear(mem, true);
        common_prefix_len = 0;
      }
    }
  }

  // CASE D (Idea C1): Suffix-Approx Cache Hit on cold KV miss (reuses snapshot logits without decode)
  if (config_.enable_suffix_approx_cache && common_prefix_len <= 1 &&
      n_tokens >= config_.suffix_approx_min_tokens) {
    std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
    const ContextLogitsSnapshot* approx_snap =
        FindSnapshotBySuffixLocked(context_tokens, config_.suffix_approx_min_tokens);
    if (approx_snap != nullptr) {
      cached_context_hash_ = current_hash;
      cached_context_n_tokens_ = n_tokens;
      cached_context_tokens_ = std::move(context_tokens);
      cached_context_logits_ = approx_snap->logits;
      cached_context_log_denom_ = approx_snap->log_denom;
      cached_candidate_nlls_.clear();
      cached_exact_candidate_nlls_.clear();
      has_checkpoint_ = false;
      kv_cache_synced_with_context_ = false;
      cache_hits_++;
      stats_.cache_hits++;
      stats_.suffix_approx_hits++;
      return true;
    }
  }

  cache_misses_++;
  stats_.cache_misses++;
  cached_candidate_nlls_.clear();
  cached_exact_candidate_nlls_.clear();

  int n_new_tokens = n_tokens - common_prefix_len;
  int start_pos = common_prefix_len;

  if (n_new_tokens <= 0) {
    has_checkpoint_ = false;
    llama_memory_clear(mem, true);
    start_pos = 0;
    n_new_tokens = n_tokens;
  } else if (common_prefix_len > 0) {
    if (has_checkpoint_) {
      llama_memory_seq_rm(mem, kCheckpointSeqId, -1, -1);
      has_checkpoint_ = false;
    }
    if (common_prefix_len < old_n) {
      if (!partial_seq_rm_supported_ || !llama_memory_seq_rm(mem, 0, common_prefix_len, -1)) {
        partial_seq_rm_supported_ = false;
        llama_memory_clear(mem, true);
        start_pos = 0;
        n_new_tokens = n_tokens;
      }
    }
  } else {
    has_checkpoint_ = false;
    llama_memory_clear(mem, true);
  }

  ApplyThreadAffinityAndScaling(n_new_tokens);

  llama_batch batch = llama_batch_init(n_new_tokens, 0, 1);
  for (int i = 0; i < n_new_tokens; ++i) {
    batch.token[i]     = context_tokens[start_pos + i];
    batch.pos[i]       = start_pos + i;
    batch.n_seq_id[i]  = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i]    = (i == n_new_tokens - 1);
  }
  batch.n_tokens = n_new_tokens;

  int ret = llama_decode(ctx_, batch);
  llama_batch_free(batch);

  if (ret != 0) {
    LOGE("Failed to decode context prefix, ret = %d", ret);
    cached_context_hash_ = 0;
    cached_context_n_tokens_ = 0;
    cached_context_tokens_.clear();
    has_checkpoint_ = false;
    kv_cache_synced_with_context_ = true;
    llama_memory_clear(mem, true);
    return false;
  }

  stats_.prefill_tokens_decoded += static_cast<uint64_t>(n_new_tokens);

  float* raw_logits = llama_get_logits_ith(ctx_, -1);
  if (!raw_logits) {
    LOGE("Failed to retrieve context logits after decode.");
    cached_context_hash_ = 0;
    cached_context_n_tokens_ = 0;
    cached_context_tokens_.clear();
    cached_context_logits_.clear();
    has_checkpoint_ = false;
    kv_cache_synced_with_context_ = true;
    return false;
  }

  int n_vocab = llama_vocab_n_tokens(vocab_);
  cached_context_logits_.assign(raw_logits, raw_logits + n_vocab);
  cached_context_log_denom_ = ComputeLogDenominatorAdaptive(cached_context_logits_.data(), n_vocab);

  cached_context_hash_ = current_hash;
  cached_context_n_tokens_ = n_tokens;
  cached_context_tokens_ = std::move(context_tokens);
  kv_cache_synced_with_context_ = true;
  if (!partial_seq_rm_supported_) {
    llama_memory_seq_rm(mem, kCheckpointSeqId, -1, -1);
    llama_memory_seq_cp(mem, 0, kCheckpointSeqId, -1, -1);
    has_checkpoint_ = true;
  }

  UpsertSnapshot(current_hash, cached_context_tokens_, cached_context_logits_,
                 cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
  return true;
}

bool LlmReranker::WarmContext(const std::string& context) {
  std::string effective_context = context.empty() ? "<s>" : context;
  uint64_t target_hash = ComputeContextHash(effective_context);
  {
    std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
    if (lock.owns_lock() && initialized_ &&
        target_hash == cached_context_hash_ && cached_context_n_tokens_ > 0) {
      return true;
    }
  }
  abort_prediction_.store(true);
  abort_scoring_.store(false);
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !ctx_ || !model_ || !vocab_) {
    return false;
  }
  return EnsureContextCached(context);
}

bool LlmReranker::TryFastScoreCandidates(
    const std::string& context,
    const std::vector<std::string>& candidates,
    std::vector<float>* out_neg_logprobs,
    std::vector<bool>* out_is_complete) {
  if (!initialized_ || !vocab_) return false;
  if (candidates.empty()) {
    out_neg_logprobs->clear();
    out_is_complete->clear();
    return true;
  }

  std::string effective_context = context.empty() ? "<s>" : context;
  uint64_t current_hash = ComputeContextHash(effective_context);

  std::unique_lock<std::mutex> s_lock(snapshot_mutex_, std::try_to_lock);
  if (!s_lock.owns_lock()) {
    return false;
  }
  const ContextLogitsSnapshot* snap = FindSnapshotByHashLocked(current_hash);
  if (!snap || snap->logits.empty()) {
    return false;
  }

  const float* logits = snap->logits.data();
  float log_denom = snap->log_denom;

  out_neg_logprobs->assign(candidates.size(), 20.0f);
  out_is_complete->assign(candidates.size(), false);

  std::vector<llama_token> ctokens(32);
  std::vector<llama_token> first_tokens(candidates.size(), -1);
  std::vector<float> surrogate_nlls(candidates.size(), 20.0f);
  bool has_incomplete = false;

  for (size_t i = 0; i < candidates.size(); ++i) {
    const std::string& cand_str = candidates[i];
    auto it = snap->candidate_nlls.find(cand_str);
    if (it != snap->candidate_nlls.end()) {
      (*out_neg_logprobs)[i] = it->second;
      (*out_is_complete)[i] = true;
      surrogate_nlls[i] = it->second;
      continue;
    }

    int n_cand_tokens = llama_tokenize(vocab_, cand_str.data(), cand_str.size(),
                                       ctokens.data(), ctokens.size(),
                                       false, false);
    if (n_cand_tokens > 0) {
      llama_token t0 = ctokens[0];
      first_tokens[i] = t0;
      float t0_nll = std::max(0.0f, -(logits[t0] - log_denom));
      if (n_cand_tokens == 1) {
        (*out_neg_logprobs)[i] = t0_nll;
        (*out_is_complete)[i] = true;
        surrogate_nlls[i] = t0_nll;
      } else {
        size_t extra_toks = std::min<size_t>(3, static_cast<size_t>(n_cand_tokens - 1));
        float s_nll = t0_nll + config_.token0_extra_token_nll_prior * static_cast<float>(extra_toks);
        (*out_neg_logprobs)[i] = s_nll;
        surrogate_nlls[i] = s_nll;
        if (config_.stage2_policy == Stage2Policy::NEVER_TOKEN0_ONLY) {
          (*out_is_complete)[i] = true;
        } else {
          (*out_is_complete)[i] = false;
          has_incomplete = true;
        }
      }
    }
  }

  if (has_incomplete &&
      (config_.stage2_policy == Stage2Policy::COLLISION_AND_CLOSE_ONLY ||
       config_.stage2_policy == Stage2Policy::EXACT_COLLISION_ONLY)) {
    std::vector<size_t> order(candidates.size());
    for (size_t i = 0; i < candidates.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
      return surrogate_nlls[a] < surrogate_nlls[b];
    });
    if (config_.stage2_policy == Stage2Policy::EXACT_COLLISION_ONLY) {
      bool top1_collides = false;
      if (!order.empty() && first_tokens[order[0]] >= 0) {
        llama_token t0_top1 = first_tokens[order[0]];
        float cutoff = surrogate_nlls[order[0]] + config_.stage2_close_gap_nats + 1.0f;
        for (size_t b = 1; b < order.size(); ++b) {
          if (surrogate_nlls[order[b]] > cutoff) break;
          if (first_tokens[order[b]] == t0_top1 &&
              (!(*out_is_complete)[order[0]] || !(*out_is_complete)[order[b]])) {
            top1_collides = true;
            break;
          }
        }
      }
      if (!top1_collides) {
        for (size_t i = 0; i < candidates.size(); ++i) {
          if (first_tokens[i] >= 0) {
            (*out_is_complete)[i] = true;
          }
        }
      }
    } else {
      bool has_collision = false;
      size_t top_check = std::min<size_t>(order.size(), 6);
      for (size_t a = 0; a < top_check && !has_collision; ++a) {
        llama_token ta = first_tokens[order[a]];
        if (ta < 0) continue;
        for (size_t b = a + 1; b < top_check; ++b) {
          if (first_tokens[order[b]] == ta &&
              (!(*out_is_complete)[order[a]] || !(*out_is_complete)[order[b]])) {
            has_collision = true;
            break;
          }
        }
      }
      bool wide_gap = (order.size() < 2) ||
                      ((surrogate_nlls[order[1]] - surrogate_nlls[order[0]]) >= (config_.stage2_close_gap_nats + 1.5f));
      if (!has_collision && wide_gap) {
        for (size_t i = 0; i < candidates.size(); ++i) {
          if (first_tokens[i] >= 0) {
            (*out_is_complete)[i] = true;
          }
        }
      }
    }
  }
  return true;
}

bool LlmReranker::ScoreCandidates(const std::string& context,
                                  const std::vector<std::string>& candidates,
                                  std::vector<float>* out_neg_logprobs) {
  std::vector<float> zero_priors(candidates.size(), 0.0f);
  return ScoreCandidatesWithPriors(context, candidates, zero_priors, out_neg_logprobs);
}

bool LlmReranker::ScoreCandidatesWithPriors(
    const std::string& context,
    const std::vector<std::string>& candidates,
    const std::vector<float>& prior_penalties,
    std::vector<float>* out_neg_logprobs,
    int force_stage2_prefix_count) {
  abort_prediction_.store(true);
  abort_scoring_.store(false);

  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !ctx_ || !model_ || !vocab_) {
    return false;
  }

  if (candidates.empty()) {
    out_neg_logprobs->clear();
    return true;
  }

  if (!EnsureContextCached(context) || cached_context_logits_.empty()) {
    return false;
  }

  const int n_vocab = llama_vocab_n_tokens(vocab_);
  const float* logits = cached_context_logits_.data();
  const float log_denom = cached_context_log_denom_;

  out_neg_logprobs->assign(candidates.size(), 20.0f);

  const int forced_limit = std::clamp(force_stage2_prefix_count, 0, static_cast<int>(candidates.size()));

  struct CandidateInfo {
    int index = 0;
    std::string surface;
    std::vector<llama_token> tokens;
    float token0_nll = 20.0f;
    float surrogate_nll = 20.0f;
    float prior_penalty = 0.0f;
    float lower_bound_total = 20.0f;
    float surrogate_total = 20.0f;
    bool is_complete = false;
    bool force_full_stage2 = false;
    bool scored_in_stage2 = false;
    std::vector<int> tree_node_path;
  };

  std::vector<CandidateInfo> cand_infos;
  cand_infos.reserve(candidates.size());
  float best_completed_total_score = 999.0f;
  const bool use_approx_policy = (config_.stage2_policy != Stage2Policy::ALWAYS_FULL);
  bool has_pending_forced = false;

  // --- STAGE 1: Token-0 Evaluation & Memoized Cache Lookup ---
  for (size_t i = 0; i < candidates.size(); ++i) {
    CandidateInfo info;
    info.index = static_cast<int>(i);
    info.surface = candidates[i];
    info.prior_penalty = (i < prior_penalties.size()) ? prior_penalties[i] : 0.0f;
    info.force_full_stage2 = (static_cast<int>(i) < forced_limit);

    const auto& lookup_map = info.force_full_stage2 ? cached_exact_candidate_nlls_ : cached_candidate_nlls_;
    auto it = lookup_map.find(info.surface);
    if (it != lookup_map.end()) {
      info.token0_nll = it->second;
      info.surrogate_nll = it->second;
      info.lower_bound_total = it->second + info.prior_penalty;
      info.surrogate_total = info.lower_bound_total;
      info.is_complete = true;
      (*out_neg_logprobs)[i] = it->second;
      if (info.lower_bound_total < best_completed_total_score) {
        best_completed_total_score = info.lower_bound_total;
      }
      cand_infos.push_back(std::move(info));
      continue;
    }

    const auto& base_tokens = GetOrTokenizeCandidate(info.surface);
    float best_t0_nll = 20.0f;
    if (!base_tokens.empty()) {
      info.tokens = base_tokens;
      best_t0_nll = std::max(0.0f, -(logits[info.tokens[0]] - log_denom));
    }

    // Space-adaptive BPE check for Latin / ASCII alphanumeric words
    if (!context.empty() && !std::isspace(static_cast<unsigned char>(context.back())) &&
        !info.surface.empty() && std::isalnum(static_cast<unsigned char>(info.surface.front()))) {
      const auto& spaced_tokens = GetOrTokenizeCandidate(" " + info.surface);
      if (!spaced_tokens.empty()) {
        float s_nll = std::max(0.0f, -(logits[spaced_tokens[0]] - log_denom));
        if (s_nll < best_t0_nll) {
          best_t0_nll = s_nll;
          info.tokens = spaced_tokens;
        }
      }
    }

    info.token0_nll = best_t0_nll;
    info.lower_bound_total = best_t0_nll + info.prior_penalty;
    if (info.tokens.size() <= 1) {
      info.surrogate_nll = best_t0_nll;
      info.surrogate_total = info.lower_bound_total;
      info.is_complete = true;
      (*out_neg_logprobs)[i] = best_t0_nll;
      cached_candidate_nlls_[info.surface] = best_t0_nll;
      cached_exact_candidate_nlls_[info.surface] = best_t0_nll;
      if (info.lower_bound_total < best_completed_total_score) {
        best_completed_total_score = info.lower_bound_total;
      }
    } else {
      if (info.force_full_stage2) {
        has_pending_forced = true;
      }
      size_t extra_toks = std::min<size_t>(3, info.tokens.size() - 1);
      info.surrogate_nll = best_t0_nll + config_.token0_extra_token_nll_prior * static_cast<float>(extra_toks);
      info.surrogate_total = info.surrogate_nll + info.prior_penalty;
      (*out_neg_logprobs)[i] = use_approx_policy ? info.surrogate_nll : best_t0_nll;
    }
    cand_infos.push_back(std::move(info));
  }

  // --- IDEA 3: Best-First Ordering & Decisive Top-1 Early Exit ---
  std::vector<int> eval_order(cand_infos.size());
  for (size_t i = 0; i < cand_infos.size(); ++i) eval_order[i] = static_cast<int>(i);
  std::sort(eval_order.begin(), eval_order.end(), [&](int a, int b) {
    if (cand_infos[a].force_full_stage2 != cand_infos[b].force_full_stage2) {
      return cand_infos[a].force_full_stage2;
    }
    if (use_approx_policy) {
      return cand_infos[a].surrogate_total < cand_infos[b].surrogate_total;
    }
    return cand_infos[a].lower_bound_total < cand_infos[b].lower_bound_total;
  });

  if (!has_pending_forced && eval_order.size() >= 2) {
    const auto& top1 = cand_infos[eval_order[0]];
    const auto& top2 = cand_infos[eval_order[1]];
    float gap = use_approx_policy
        ? (top2.surrogate_total - top1.surrogate_total)
        : (top2.lower_bound_total - top1.lower_bound_total);
    if (top1.is_complete && gap >= config_.decisive_top1_gap_nats) {
      stats_.stage2_skipped_calls++;
      UpsertSnapshot(cached_context_hash_, cached_context_tokens_, cached_context_logits_,
                     cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
      return true;
    }
  }

  // --- IDEA A2: Zero-Stage-2 / Collision-Only Stage 2 Gating ---
  if (config_.stage2_policy == Stage2Policy::NEVER_TOKEN0_ONLY && !has_pending_forced) {
    for (const auto& info : cand_infos) {
      if (!info.is_complete) {
        cached_candidate_nlls_[info.surface] = info.surrogate_nll;
      }
    }
    stats_.stage2_skipped_calls++;
    if (kv_cache_synced_with_context_) {
      UpsertSnapshot(cached_context_hash_, cached_context_tokens_, cached_context_logits_,
                     cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
    }
    return true;
  }

  llama_token exact_collision_filter_tok = -1;
  if (!has_pending_forced && config_.stage2_policy == Stage2Policy::EXACT_COLLISION_ONLY) {
    bool need_stage2 = false;
    if (!eval_order.empty()) {
      const auto& top1 = cand_infos[eval_order[0]];
      if (!top1.tokens.empty()) {
        llama_token t0_top1 = top1.tokens[0];
        float cutoff = top1.surrogate_total + config_.stage2_close_gap_nats;
        for (size_t b = 1; b < eval_order.size(); ++b) {
          const auto& cb = cand_infos[eval_order[b]];
          if (cb.surrogate_total > cutoff) break;
          if (!cb.tokens.empty() && cb.tokens[0] == t0_top1 &&
              (!top1.is_complete || !cb.is_complete)) {
            need_stage2 = true;
            exact_collision_filter_tok = t0_top1;
            break;
          }
        }
      }
    }

    if (!need_stage2) {
      for (const auto& info : cand_infos) {
        if (!info.is_complete) {
          cached_candidate_nlls_[info.surface] = info.surrogate_nll;
        }
      }
      stats_.stage2_skipped_calls++;
      if (kv_cache_synced_with_context_) {
        UpsertSnapshot(cached_context_hash_, cached_context_tokens_, cached_context_logits_,
                       cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
      }
      return true;
    }
  } else if (!has_pending_forced && config_.stage2_policy == Stage2Policy::COLLISION_AND_CLOSE_ONLY) {
    bool need_stage2 = false;
    float best_surrogate = !eval_order.empty() ? cand_infos[eval_order[0]].surrogate_total : 0.0f;
    size_t max_check = std::min<size_t>(eval_order.size(),
        static_cast<size_t>(std::max(6, config_.max_stage2_candidates)));

    // 1. Check token-0 prefix collision among top close candidates (with >= 1 multi-token candidate)
    for (size_t a = 0; a < max_check && !need_stage2; ++a) {
      const auto& ca = cand_infos[eval_order[a]];
      if (ca.tokens.empty() || ca.surrogate_total > best_surrogate + config_.stage2_prune_margin_nats) {
        continue;
      }
      for (size_t b = a + 1; b < max_check; ++b) {
        const auto& cb = cand_infos[eval_order[b]];
        if (cb.tokens.empty() || cb.surrogate_total > best_surrogate + config_.stage2_prune_margin_nats) {
          continue;
        }
        if (ca.tokens[0] == cb.tokens[0] && (!ca.is_complete || !cb.is_complete)) {
          need_stage2 = true;
          break;
        }
      }
    }

    // 2. Check close ambiguous race where multi-token candidates are within stage2_close_gap_nats
    if (!need_stage2 && eval_order.size() >= 2) {
      float second_surrogate = cand_infos[eval_order[1]].surrogate_total;
      if ((second_surrogate - best_surrogate) < config_.stage2_close_gap_nats) {
        for (size_t k = 0; k < max_check; ++k) {
          const auto& ck = cand_infos[eval_order[k]];
          if (ck.surrogate_total > best_surrogate + config_.stage2_close_gap_nats) break;
          if (!ck.is_complete && ck.tokens.size() >= 2) {
            need_stage2 = true;
            break;
          }
        }
      }
    }

    if (!need_stage2) {
      for (const auto& info : cand_infos) {
        if (!info.is_complete) {
          cached_candidate_nlls_[info.surface] = info.surrogate_nll;
        }
      }
      stats_.stage2_skipped_calls++;
      if (kv_cache_synced_with_context_) {
        UpsertSnapshot(cached_context_hash_, cached_context_tokens_, cached_context_logits_,
                       cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
      }
      return true;
    }
  }

  // Ensure KV cache is genuinely materialized if a suffix-approx cache hit deferred Prefill
  if (!kv_cache_synced_with_context_) {
    if (!DecodeFullContextIntoKvLocked(cached_context_tokens_)) {
      return !has_pending_forced;  // Forced full-span Stage 2 requires valid KV cache
    }
    const float* exact_logits = cached_context_logits_.data();
    const float exact_denom = cached_context_log_denom_;
    best_completed_total_score = 999.0f;
    for (auto& info : cand_infos) {
      if (!info.tokens.empty() && !info.is_complete) {
        info.token0_nll = std::max(0.0f, -(exact_logits[info.tokens[0]] - exact_denom));
        info.lower_bound_total = info.token0_nll + info.prior_penalty;
        if (info.tokens.size() <= 1) {
          info.surrogate_nll = info.token0_nll;
          info.surrogate_total = info.lower_bound_total;
          info.is_complete = true;
          (*out_neg_logprobs)[info.index] = info.token0_nll;
          cached_candidate_nlls_[info.surface] = info.token0_nll;
          cached_exact_candidate_nlls_[info.surface] = info.token0_nll;
          if (info.lower_bound_total < best_completed_total_score) {
            best_completed_total_score = info.lower_bound_total;
          }
        } else {
          size_t extra_toks = std::min<size_t>(3, info.tokens.size() - 1);
          info.surrogate_nll = info.token0_nll + config_.token0_extra_token_nll_prior * static_cast<float>(extra_toks);
          info.surrogate_total = info.surrogate_nll + info.prior_penalty;
          (*out_neg_logprobs)[info.index] = use_approx_policy ? info.surrogate_nll : info.token0_nll;
        }
      }
    }
    std::sort(eval_order.begin(), eval_order.end(), [&](int a, int b) {
      if (cand_infos[a].force_full_stage2 != cand_infos[b].force_full_stage2) {
        return cand_infos[a].force_full_stage2;
      }
      if (use_approx_policy) {
        return cand_infos[a].surrogate_total < cand_infos[b].surrogate_total;
      }
      return cand_infos[a].lower_bound_total < cand_infos[b].lower_bound_total;
    });
  }

  float dynamic_prune_cutoff = best_completed_total_score + config_.stage2_prune_margin_nats;
  if (best_completed_total_score > 100.0f && !eval_order.empty()) {
    const auto& best_cand = cand_infos[eval_order[0]];
    size_t bounded_len = std::min<size_t>(4, std::max<size_t>(1, best_cand.tokens.size()));
    float est_best_total = best_cand.lower_bound_total + 2.2f * static_cast<float>(bounded_len - 1);
    dynamic_prune_cutoff = est_best_total + config_.stage2_prune_margin_nats;
  }

  // --- IDEA 1: Prefix-Tree (Trie) Construction for Surviving Multi-Token Candidates ---
  const int effective_max_nodes = has_pending_forced
      ? std::max(config_.max_tree_batch_tokens, 64)
      : config_.max_tree_batch_tokens;
  std::vector<PrefixTreeNode> tree_nodes;
  tree_nodes.reserve(effective_max_nodes);
  std::vector<int> root_children;
  std::vector<int> active_cand_indices;
  int next_branch_seq_id = 1;  // Branch seq_ids: 1 .. (kCheckpointSeqId - 1) = 1..14
  const int max_branch_seqs = kCheckpointSeqId - 1;
  const int effective_max_cands = has_pending_forced
      ? std::min(max_branch_seqs, std::max(8, forced_limit))
      : (use_approx_policy ? std::min(max_branch_seqs, std::max(1, config_.max_stage2_candidates)) : max_branch_seqs);
  const size_t effective_max_toks = has_pending_forced
      ? static_cast<size_t>(8)
      : (use_approx_policy ? static_cast<size_t>(std::max(2, config_.max_stage2_tree_depth + 1)) : static_cast<size_t>(8));

  for (int ord_idx : eval_order) {
    auto& info = cand_infos[ord_idx];
    if (info.is_complete || info.tokens.size() <= 1) continue;
    if (has_pending_forced && config_.stage2_policy == Stage2Policy::NEVER_TOKEN0_ONLY && !info.force_full_stage2) {
      cached_candidate_nlls_[info.surface] = info.surrogate_nll;
      continue;
    }
    if (!info.force_full_stage2 && exact_collision_filter_tok >= 0 &&
        (info.tokens.empty() || info.tokens[0] != exact_collision_filter_tok)) {
      cached_candidate_nlls_[info.surface] = info.surrogate_nll;
      continue;
    }

    if (!info.force_full_stage2 && info.lower_bound_total > dynamic_prune_cutoff) {
      cached_candidate_nlls_[info.surface] = use_approx_policy ? info.surrogate_nll : info.token0_nll;
      continue;
    }
    if (static_cast<int>(active_cand_indices.size()) >= effective_max_cands ||
        next_branch_seq_id > max_branch_seqs) {
      if (use_approx_policy && !info.force_full_stage2) {
        cached_candidate_nlls_[info.surface] = info.surrogate_nll;
      }
      continue;
    }

    size_t max_toks = std::min<size_t>(effective_max_toks, info.tokens.size());
    int n_prefix = static_cast<int>(max_toks) - 1;  // Last-Token Elision

    int curr_parent = -1;
    info.tree_node_path.clear();
    info.tree_node_path.reserve(n_prefix);

    for (int d = 0; d < n_prefix; ++d) {
      int32_t tok = info.tokens[d];
      const std::vector<int>& siblings = (curr_parent == -1)
          ? root_children
          : tree_nodes[curr_parent].child_node_indices;

      int found_node = -1;
      for (int sib_idx : siblings) {
        if (tree_nodes[sib_idx].token == tok) {
          found_node = sib_idx;
          break;
        }
      }

      if (found_node == -1) {
        if (static_cast<int>(tree_nodes.size()) >= effective_max_nodes) {
          break;
        }
        PrefixTreeNode new_node;
        new_node.token = tok;
        new_node.depth = d;
        new_node.parent_node_idx = curr_parent;
        new_node.needs_logits = true;
        found_node = static_cast<int>(tree_nodes.size());
        tree_nodes.push_back(std::move(new_node));
        if (curr_parent == -1) {
          root_children.push_back(found_node);
        } else {
          tree_nodes[curr_parent].child_node_indices.push_back(found_node);
        }
      } else {
        tree_nodes[found_node].needs_logits = true;
      }

      info.tree_node_path.push_back(found_node);
      curr_parent = found_node;
    }

    if (static_cast<int>(info.tree_node_path.size()) == n_prefix) {
      int leaf_node = info.tree_node_path.back();
      if (tree_nodes[leaf_node].seq_ids.empty()) {
        int assigned_seq = next_branch_seq_id++;
        for (int node_idx : info.tree_node_path) {
          tree_nodes[node_idx].seq_ids.push_back(assigned_seq);
        }
      }
      active_cand_indices.push_back(ord_idx);
    }
  }

  // --- STAGE 2 EXECUTION: Single-Pass Packed Prefix-Tree Decode ---
  bool need_sequential_fallback = false;
  if (!tree_nodes.empty() && !active_cand_indices.empty() && !abort_scoring_.load()) {
    stats_.stage2_calls++;
    llama_memory_t mem = llama_get_memory(ctx_);
    const int start_pos = cached_context_n_tokens_;
    const int num_assigned_seqs = next_branch_seq_id - 1;
    const bool use_tree_batch = partial_seq_rm_supported_;

    if (use_tree_batch) {
      if (num_assigned_seqs > 1) {
        for (int s = 1; s <= num_assigned_seqs; ++s) {
          llama_memory_seq_rm(mem, s, -1, -1);
          llama_memory_seq_cp(mem, 0, s, -1, -1);
        }
      }

      const int n_batch_nodes = static_cast<int>(tree_nodes.size());
      stats_.stage2_tree_nodes_decoded += static_cast<uint64_t>(n_batch_nodes);
      ApplyThreadAffinityAndScaling(n_batch_nodes);

      llama_batch tree_batch = llama_batch_init(n_batch_nodes, 0, max_branch_seqs + 1);
      for (int idx = 0; idx < n_batch_nodes; ++idx) {
        auto& node = tree_nodes[idx];
        node.batch_order_idx = idx;
        tree_batch.token[idx] = node.token;
        tree_batch.pos[idx] = start_pos + node.depth;
        if (num_assigned_seqs == 1) {
          tree_batch.n_seq_id[idx] = 1;
          tree_batch.seq_id[idx][0] = 0;
        } else {
          tree_batch.n_seq_id[idx] = static_cast<int32_t>(node.seq_ids.size());
          for (size_t s = 0; s < node.seq_ids.size(); ++s) {
            tree_batch.seq_id[idx][s] = node.seq_ids[s];
          }
        }
        tree_batch.logits[idx] = node.needs_logits;
      }
      tree_batch.n_tokens = n_batch_nodes;

      int decode_ret = llama_decode(ctx_, tree_batch);
      if (decode_ret == 0 && !abort_scoring_.load()) {
        // Idea 4 & B3: Compute adaptive log_denom once per internal tree node
        for (int ord_idx : active_cand_indices) {
          auto& info = cand_infos[ord_idx];
          float total_nll = info.token0_nll;
          int n_prefix = static_cast<int>(info.tree_node_path.size());
          for (int d = 0; d < n_prefix; ++d) {
            int node_idx = info.tree_node_path[d];
            auto& node = tree_nodes[node_idx];
            float* step_logits = llama_get_logits_ith(ctx_, node.batch_order_idx);
            if (!step_logits) continue;
            if (!node.log_denom_ready) {
              node.computed_log_denom = ComputeLogDenominatorAdaptive(step_logits, n_vocab);
              node.log_denom_ready = true;
            }
            llama_token next_tok = info.tokens[d + 1];
            total_nll += std::max(0.0f, -(step_logits[next_tok] - node.computed_log_denom));
          }
          if (use_approx_policy && info.tokens.size() > static_cast<size_t>(n_prefix + 1)) {
            total_nll += config_.token0_extra_token_nll_prior *
                         static_cast<float>(info.tokens.size() - static_cast<size_t>(n_prefix + 1));
          }
          (*out_neg_logprobs)[info.index] = total_nll;
          cached_candidate_nlls_[info.surface] = total_nll;
          cached_exact_candidate_nlls_[info.surface] = total_nll;
          info.scored_in_stage2 = true;
        }
      } else if (!abort_scoring_.load()) {
        // Hybrid recurrent models may fail multi-seq tree batch before partial seq_rm is flagged false
        partial_seq_rm_supported_ = false;
        need_sequential_fallback = true;
      }
      llama_batch_free(tree_batch);

      if (num_assigned_seqs == 1) {
        if (!RollbackOrRestoreContextState()) {
          cached_context_hash_ = 0;
          cached_context_n_tokens_ = 0;
          cached_context_tokens_.clear();
          has_checkpoint_ = false;
          kv_cache_synced_with_context_ = false;
          llama_memory_clear(mem, true);
        }
      } else {
        for (int s = 1; s <= num_assigned_seqs; ++s) {
          llama_memory_seq_rm(mem, s, -1, -1);
        }
        if (need_sequential_fallback && !has_checkpoint_) {
          llama_memory_seq_rm(mem, kCheckpointSeqId, -1, -1);
          llama_memory_seq_cp(mem, 0, kCheckpointSeqId, -1, -1);
          has_checkpoint_ = true;
        }
      }
    } else {
      need_sequential_fallback = true;
    }

    if (need_sequential_fallback && !abort_scoring_.load()) {
      // Ensure seq 0 context state is checkpointed before decoding onto seq 0
      if (!has_checkpoint_) {
        llama_memory_seq_rm(mem, kCheckpointSeqId, -1, -1);
        llama_memory_seq_cp(mem, 0, kCheckpointSeqId, -1, -1);
        has_checkpoint_ = true;
      }
      // Fallback for hybrid recurrent models where partial seq_rm is disabled
      for (int ord_idx : active_cand_indices) {
        if (abort_scoring_.load()) break;
        auto& info = cand_infos[ord_idx];
        int n_prefix = static_cast<int>(info.tree_node_path.size());
        stats_.stage2_tree_nodes_decoded += static_cast<uint64_t>(n_prefix);
        ApplyThreadAffinityAndScaling(n_prefix);

        llama_batch b = llama_batch_init(n_prefix, 0, 1);
        for (int d = 0; d < n_prefix; ++d) {
          b.token[d]     = info.tokens[d];
          b.pos[d]       = start_pos + d;
          b.n_seq_id[d]  = 1;
          b.seq_id[d][0] = 0;
          b.logits[d]    = true;
        }
        b.n_tokens = n_prefix;

        if (llama_decode(ctx_, b) == 0 && !abort_scoring_.load()) {
          float total_nll = info.token0_nll;
          for (int d = 0; d < n_prefix; ++d) {
            float* step_logits = llama_get_logits_ith(ctx_, d);
            if (!step_logits) continue;
            float s_denom = ComputeLogDenominatorAdaptive(step_logits, n_vocab);
            total_nll += std::max(0.0f, -(step_logits[info.tokens[d + 1]] - s_denom));
          }
          if (use_approx_policy && info.tokens.size() > static_cast<size_t>(n_prefix + 1)) {
            total_nll += config_.token0_extra_token_nll_prior *
                         static_cast<float>(info.tokens.size() - static_cast<size_t>(n_prefix + 1));
          }
          (*out_neg_logprobs)[info.index] = total_nll;
          cached_candidate_nlls_[info.surface] = total_nll;
          cached_exact_candidate_nlls_[info.surface] = total_nll;
          info.scored_in_stage2 = true;
        }
        llama_batch_free(b);
        if (!RollbackOrRestoreContextState()) {
          cached_context_hash_ = 0;
          cached_context_n_tokens_ = 0;
          cached_context_tokens_.clear();
          has_checkpoint_ = false;
          kv_cache_synced_with_context_ = false;
          llama_memory_clear(mem, true);
          break;
        }
      }
    }

    if (has_pending_forced && abort_scoring_.load()) {
      return false;
    }
  } else {
    if (has_pending_forced && abort_scoring_.load()) {
      return false;
    }
    stats_.stage2_skipped_calls++;
  }

  // Scale-consistency guard for any forced candidate not decoded in Stage 2
  if (has_pending_forced) {
    float max_forced_scored_nll = 0.0f;
    for (const auto& info : cand_infos) {
      if (info.force_full_stage2 && (info.is_complete || info.scored_in_stage2)) {
        max_forced_scored_nll = std::max(max_forced_scored_nll, (*out_neg_logprobs)[info.index]);
      }
    }
    for (auto& info : cand_infos) {
      if (info.force_full_stage2 && !info.is_complete && !info.scored_in_stage2) {
        size_t tok_count = std::max<size_t>(1, info.tokens.size());
        (*out_neg_logprobs)[info.index] = std::max(
            max_forced_scored_nll + 4.0f,
            info.token0_nll + 5.5f * static_cast<float>(tok_count - 1));
      }
    }
  }

  UpsertSnapshot(cached_context_hash_, cached_context_tokens_, cached_context_logits_,
                 cached_context_log_denom_, cached_candidate_nlls_, cached_exact_candidate_nlls_);
  return true;
}

bool LlmReranker::PredictNextPhrases(const std::string& context,
                                     int max_candidates,
                                     int max_tokens,
                                     std::vector<std::string>* out_predictions) {
  abort_prediction_.store(false);
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !ctx_ || !model_ || !vocab_) {
    return false;
  }
  stats_.next_phrase_calls++;
  out_predictions->clear();
  if (!EnsureContextCached(context) || cached_context_logits_.empty()) {
    return false;
  }

  const int n_vocab = llama_vocab_n_tokens(vocab_);
  const float* logits = cached_context_logits_.data();

  // Filter initial tokens using precomputed valid_token_ids_ (Idea 6 & B3)
  std::vector<std::pair<float, llama_token>> scored_tokens;
  if (!valid_token_ids_.empty()) {
    scored_tokens.reserve(valid_token_ids_.size());
    for (int32_t v : valid_token_ids_) {
      scored_tokens.emplace_back(logits[v], v);
    }
  } else {
    scored_tokens.reserve(4096);
    for (llama_token v = 0; v < n_vocab; ++v) {
      if (!IsTokenValidForPrediction(v)) continue;
      scored_tokens.emplace_back(logits[v], v);
    }
  }
  size_t top_k_init = std::min<size_t>(scored_tokens.size(), 24);
  std::partial_sort(scored_tokens.begin(),
                    scored_tokens.begin() + top_k_init,
                    scored_tokens.end(),
                    [](const auto& a, const auto& b) { return a.first > b.first; });

  struct ActiveBeam {
    int seq_id = 1;
    std::vector<llama_token> tokens;
    std::string text;
    bool finished = false;
  };

  const int target_beams = config_.enable_zero_decode_next_phrase
      ? std::min(max_candidates, 12)
      : std::min(max_candidates, 6);
  std::vector<ActiveBeam> beams;
  beams.reserve(target_beams);

  for (size_t k = 0; k < top_k_init && static_cast<int>(beams.size()) < target_beams; ++k) {
    llama_token t0 = scored_tokens[k].second;
    char piece_buf[256];
    int piece_len = llama_token_to_piece(vocab_, t0, piece_buf, sizeof(piece_buf), 0, false);
    if (piece_len <= 0) continue;
    std::string piece = TrimWhitespace(std::string(piece_buf, piece_len));
    if (piece.empty()) continue;

    ActiveBeam beam;
    beam.seq_id = static_cast<int>(beams.size()) + 1;
    beam.tokens.push_back(t0);
    beam.text = piece;
    beam.finished = IsTerminalPunctuation(piece);
    beams.push_back(std::move(beam));
  }

  if (beams.empty()) return false;

  if (config_.enable_zero_decode_next_phrase || max_tokens <= 1) {
    for (auto& beam : beams) {
      SanitizeIncompleteUtf8Tail(&beam.text);
      std::string cleaned = TrimWhitespace(beam.text);
      if (!cleaned.empty() &&
          std::find(out_predictions->begin(), out_predictions->end(), cleaned) == out_predictions->end()) {
        out_predictions->push_back(std::move(cleaned));
      }
    }
    return !out_predictions->empty();
  }

  if (!kv_cache_synced_with_context_) {
    if (!DecodeFullContextIntoKvLocked(cached_context_tokens_)) {
      return false;
    }
  }

  llama_memory_t mem = llama_get_memory(ctx_);
  const int start_pos = cached_context_n_tokens_;

  auto select_best_next = [&](const float* next_logits) -> llama_token {
    llama_token best_next = -1;
    float best_next_l = -1e9f;
    if (!valid_token_ids_.empty()) {
      for (int32_t v : valid_token_ids_) {
        if (next_logits[v] > best_next_l) {
          best_next_l = next_logits[v];
          best_next = v;
        }
      }
    } else {
      for (llama_token v = 0; v < n_vocab; ++v) {
        if (!IsTokenValidForPrediction(v)) continue;
        if (next_logits[v] > best_next_l) {
          best_next_l = next_logits[v];
          best_next = v;
        }
      }
    }
    return best_next;
  };

  if (partial_seq_rm_supported_) {
    // PARALLEL LOCKSTEP DECODING across all K beams (Idea 6)
    for (const auto& beam : beams) {
      llama_memory_seq_rm(mem, beam.seq_id, -1, -1);
      llama_memory_seq_cp(mem, 0, beam.seq_id, -1, -1);
    }

    for (int step = 1; step < max_tokens && !abort_prediction_.load(); ++step) {
      std::vector<int> active_beam_indices;
      for (size_t b_idx = 0; b_idx < beams.size(); ++b_idx) {
        if (!beams[b_idx].finished) active_beam_indices.push_back(static_cast<int>(b_idx));
      }
      if (active_beam_indices.empty()) break;

      int n_active = static_cast<int>(active_beam_indices.size());
      ApplyThreadAffinityAndScaling(n_active);
      llama_batch batch = llama_batch_init(n_active, 0, 1);
      for (int i = 0; i < n_active; ++i) {
        const auto& beam = beams[active_beam_indices[i]];
        batch.token[i]     = beam.tokens.back();
        batch.pos[i]       = start_pos + step - 1;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = beam.seq_id;
        batch.logits[i]    = true;
      }
      batch.n_tokens = n_active;

      stats_.next_phrase_decode_calls++;
      int ret = llama_decode(ctx_, batch);
      llama_batch_free(batch);
      if (ret != 0 || abort_prediction_.load()) break;

      for (int i = 0; i < n_active; ++i) {
        auto& beam = beams[active_beam_indices[i]];
        float* next_logits = llama_get_logits_ith(ctx_, i);
        if (!next_logits) {
          beam.finished = true;
          continue;
        }

        llama_token best_next = select_best_next(next_logits);
        if (best_next < 0 || llama_vocab_is_eog(vocab_, best_next)) {
          beam.finished = true;
          continue;
        }
        AppendTokenPieceAndCheckTermination(vocab_, best_next, &beam.tokens, &beam.text, &beam.finished);
      }
    }

    for (const auto& beam : beams) {
      llama_memory_seq_rm(mem, beam.seq_id, -1, -1);
    }
  } else {
    // Sequential fallback for hybrid recurrent models without partial seq_rm
    for (auto& beam : beams) {
      if (abort_prediction_.load()) break;
      if (beam.finished) continue;
      for (int step = 1; step < max_tokens && !beam.finished && !abort_prediction_.load(); ++step) {
        ApplyThreadAffinityAndScaling(1);
        llama_batch b = llama_batch_init(1, 0, 1);
        b.token[0]     = beam.tokens.back();
        b.pos[0]       = start_pos + step - 1;
        b.n_seq_id[0]  = 1;
        b.seq_id[0][0] = 0;
        b.logits[0]    = true;
        b.n_tokens     = 1;

        stats_.next_phrase_decode_calls++;
        if (llama_decode(ctx_, b) != 0) {
          llama_batch_free(b);
          break;
        }
        llama_batch_free(b);

        float* next_logits = llama_get_logits_ith(ctx_, -1);
        if (!next_logits) break;

        llama_token best_next = select_best_next(next_logits);
        if (best_next < 0 || llama_vocab_is_eog(vocab_, best_next)) break;
        AppendTokenPieceAndCheckTermination(vocab_, best_next, &beam.tokens, &beam.text, &beam.finished);
      }
      RollbackOrRestoreContextState();
    }
  }

  for (auto& beam : beams) {
    SanitizeIncompleteUtf8Tail(&beam.text);
    std::string cleaned = TrimWhitespace(beam.text);
    if (!cleaned.empty() &&
        std::find(out_predictions->begin(), out_predictions->end(), cleaned) == out_predictions->end()) {
      out_predictions->push_back(std::move(cleaned));
    }
  }

  return !out_predictions->empty();
}

}  // namespace myasr
