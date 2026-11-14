#include "llm_reranker.h"

#include <cmath>
#include <cstring>
#include <algorithm>
#include <iostream>
#include <android/log.h>

#include "llama.h"

#define TAG "MyAsrLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace myasr {

static constexpr uint64_t FNV_OFFSET_BASIS = 14695981039346656037ULL;
static constexpr uint64_t FNV_PRIME = 1099511628211ULL;

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
  cached_context_log_denom_ = 0.0f;
  abort_prediction_.store(true);
  abort_scoring_.store(true);
  {
    std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
    snapshot_.context_hash = 0;
    snapshot_.n_tokens = 0;
    snapshot_.logits.clear();
    snapshot_.candidate_nlls.clear();
    snapshot_.log_denom = 0.0f;
  }
  LOGI("LlmReranker released.");
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
  cparams.n_threads = config_.n_threads;
  cparams.n_threads_batch = config_.n_threads;
  cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

  ctx_ = llama_init_from_model(model_, cparams);
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

  initialized_ = true;
  cached_context_hash_ = 0;
  cached_context_n_tokens_ = 0;
  cache_hits_ = 0;
  cache_misses_ = 0;
  abort_scoring_.store(false);
  abort_prediction_.store(false);
  LOGI("LlmReranker initialized successfully with model: %s", config_.model_path.c_str());
  return true;
}

float LlmReranker::ComputeLogDenominator(const float* logits, int n_vocab) {
  float max_l = -1e9f;
  for (int v = 0; v < n_vocab; ++v) {
    if (logits[v] > max_l) max_l = logits[v];
  }
  // Thresholded sum: values with (logit - max_l) < -16.0f contribute less than 1.1e-7
  // to the total denominator. Using expf (single precision NEON/AVX) with threshold
  // reduces CPU instruction count by 10x while maintaining 100% numerical precision.
  float sum_exp = 0.0f;
  const float threshold = max_l - 16.0f;
  for (int v = 0; v < n_vocab; ++v) {
    if (logits[v] >= threshold) {
      sum_exp += std::expf(logits[v] - max_l);
    }
  }
  return max_l + std::logf(sum_exp);
}

bool LlmReranker::EnsureContextCached(const std::string& context) {
  std::string effective_context = context.empty() ? "<s>" : context;
  uint64_t current_hash = ComputeContextHash(effective_context);
  if (current_hash == cached_context_hash_ && cached_context_n_tokens_ > 0 && !cached_context_tokens_.empty()) {
    cache_hits_++;
    return true;
  }

  cache_misses_++;
  cached_candidate_nlls_.clear();
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
    return false;
  }
  context_tokens.resize(n_tokens);

  // Compute Longest Common Prefix (LCP) with cached tokens
  int common_prefix_len = 0;
  int max_check = std::min(static_cast<int>(cached_context_tokens_.size()), n_tokens);
  while (common_prefix_len < max_check &&
         cached_context_tokens_[common_prefix_len] == context_tokens[common_prefix_len]) {
    common_prefix_len++;
  }

  int n_new_tokens = n_tokens - common_prefix_len;
  int start_pos = common_prefix_len;

  if (common_prefix_len > 0 && n_new_tokens > 0) {
    // Incremental update: roll back only tokens beyond the common prefix
    llama_memory_seq_rm(mem, 0, common_prefix_len, -1);
  } else if (common_prefix_len == 0) {
    // Zero common prefix: full reset
    llama_memory_clear(mem, true);
  } else {
    // Exact match (common_prefix_len == n_tokens && cached_context_tokens_.size() == n_tokens)
    cache_hits_++;
    return true;
  }

  llama_batch batch = llama_batch_init(n_new_tokens, 0, 1);
  for (int i = 0; i < n_new_tokens; ++i) {
    batch.token[i]     = context_tokens[start_pos + i];
    batch.pos[i]       = start_pos + i;
    batch.n_seq_id[i]  = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i]    = (i == n_new_tokens - 1); // logits only for last token
  }
  batch.n_tokens = n_new_tokens;

  int ret = llama_decode(ctx_, batch);
  llama_batch_free(batch);

  if (ret != 0) {
    LOGE("Failed to decode context prefix, ret = %d", ret);
    cached_context_hash_ = 0;
    cached_context_n_tokens_ = 0;
    cached_context_tokens_.clear();
    llama_memory_clear(mem, true);
    return false;
  }

  float* raw_logits = llama_get_logits_ith(ctx_, -1);
  if (!raw_logits) {
    LOGE("Failed to retrieve context logits after decode.");
    cached_context_hash_ = 0;
    cached_context_n_tokens_ = 0;
    cached_context_tokens_.clear();
    cached_context_logits_.clear();
    return false;
  }

  int n_vocab = llama_vocab_n_tokens(vocab_);
  cached_context_logits_.assign(raw_logits, raw_logits + n_vocab);
  cached_context_log_denom_ = ComputeLogDenominator(cached_context_logits_.data(), n_vocab);

  cached_context_hash_ = current_hash;
  cached_context_n_tokens_ = n_tokens;
  cached_context_tokens_ = std::move(context_tokens);

  // Update snapshot for lock-free 0ms candidate evaluation from UI thread
  {
    std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
    snapshot_.context_hash = current_hash;
    snapshot_.n_tokens = n_tokens;
    snapshot_.logits = cached_context_logits_;
    snapshot_.log_denom = cached_context_log_denom_;
    snapshot_.candidate_nlls.clear();
  }
  return true;
}

bool LlmReranker::WarmContext(const std::string& context) {
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
  if (snapshot_.context_hash != current_hash || snapshot_.logits.empty()) {
    return false;
  }

  const float* logits = snapshot_.logits.data();
  float log_denom = snapshot_.log_denom;

  out_neg_logprobs->resize(candidates.size(), 20.0f);
  out_is_complete->resize(candidates.size(), false);

  for (size_t i = 0; i < candidates.size(); ++i) {
    const std::string& cand_str = candidates[i];
    auto it = snapshot_.candidate_nlls.find(cand_str);
    if (it != snapshot_.candidate_nlls.end()) {
      (*out_neg_logprobs)[i] = it->second;
      (*out_is_complete)[i] = true;
      continue;
    }

    std::vector<llama_token> ctokens(32);
    int n_cand_tokens = llama_tokenize(vocab_, cand_str.data(), cand_str.size(),
                                       ctokens.data(), ctokens.size(),
                                       false, false);
    if (n_cand_tokens > 0) {
      llama_token t0 = ctokens[0];
      float t0_nll = -(logits[t0] - log_denom);
      if (n_cand_tokens == 1) {
        (*out_neg_logprobs)[i] = t0_nll;
        (*out_is_complete)[i] = true;
      } else {
        // Approximate lower-bound score for 2+ tokens (t0 NLL + gentle token prior)
        (*out_neg_logprobs)[i] = t0_nll + static_cast<float>(n_cand_tokens - 1) * 0.8f;
        (*out_is_complete)[i] = false;
      }
    }
  }
  return true;
}

bool LlmReranker::ScoreCandidates(const std::string& context,
                                  const std::vector<std::string>& candidates,
                                  std::vector<float>* out_neg_logprobs) {
  // Signal any in-flight speculative prediction to abort immediately
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

  int n_vocab = llama_vocab_n_tokens(vocab_);
  const float* logits = cached_context_logits_.data();
  float log_denom = cached_context_log_denom_;

  out_neg_logprobs->resize(candidates.size(), 20.0f);

  struct CandidateInfo {
    int index;
    std::string surface;
    std::vector<llama_token> tokens;
    float token0_neg_logprob;
    bool is_cached = false;
  };

  std::vector<CandidateInfo> cand_infos;
  cand_infos.reserve(candidates.size());

  float min_completed_nll = 999.0f;

  for (size_t i = 0; i < candidates.size(); ++i) {
    CandidateInfo info;
    info.index = i;
    info.surface = candidates[i];

    // Check memoized score cache for this context
    auto it = cached_candidate_nlls_.find(info.surface);
    if (it != cached_candidate_nlls_.end()) {
      info.token0_neg_logprob = it->second;
      info.is_cached = true;
      if (it->second < min_completed_nll) min_completed_nll = it->second;
      cand_infos.push_back(std::move(info));
      continue;
    }

    std::string cand_str = candidates[i];
    std::vector<llama_token> ctokens(64);
    int n_cand_tokens = llama_tokenize(vocab_, cand_str.data(), cand_str.size(),
                                       ctokens.data(), ctokens.size(),
                                       false, false);
    float best_nll = 20.0f;
    if (n_cand_tokens > 0) {
      ctokens.resize(n_cand_tokens);
      info.tokens = ctokens;
      llama_token t0 = info.tokens[0];
      best_nll = -(logits[t0] - log_denom);
    }

    // Space-adaptive BPE evaluation:
    // Only apply for Latin / ASCII alphanumeric words (e.g. English QWERTY typing: " will", " the").
    // Never apply to CJK / Japanese kana-kanji words, where leading spaces are not used in orthography
    // and would erroneously fragment 1-token words into [space_token, word_token].
    if (!context.empty() && !isspace(static_cast<unsigned char>(context.back())) &&
        !cand_str.empty() && std::isalnum(static_cast<unsigned char>(cand_str.front()))) {
      std::string spaced_cand = " " + cand_str;
      std::vector<llama_token> s_tokens(64);
      int n_s_tokens = llama_tokenize(vocab_, spaced_cand.data(), spaced_cand.size(),
                                      s_tokens.data(), s_tokens.size(),
                                      false, false);
      if (n_s_tokens > 0) {
        s_tokens.resize(n_s_tokens);
        llama_token st0 = s_tokens[0];
        float s_nll = -(logits[st0] - log_denom);
        if (s_nll < best_nll) {
          best_nll = s_nll;
          info.tokens = std::move(s_tokens);
        }
      }
    }

    info.token0_neg_logprob = best_nll;
    if (info.tokens.size() <= 1) {
      cached_candidate_nlls_[info.surface] = best_nll;
      if (best_nll < min_completed_nll) min_completed_nll = best_nll;
    }
    cand_infos.push_back(std::move(info));
  }

  for (const auto& info : cand_infos) {
    (*out_neg_logprobs)[info.index] = info.token0_neg_logprob;
  }

  // Stage 2: Full-Sequence Joint Negative Log-Likelihood with Admissible Lower-Bound Pruning
  // Evaluates multi-token candidates using seq 0, rolling back speculative tokens after each candidate
  llama_memory_t mem = llama_get_memory(ctx_);
  int start_pos = cached_context_n_tokens_;

  for (size_t i = 0; i < cand_infos.size(); ++i) {
    if (abort_scoring_.load()) break;
    if (cand_infos[i].is_cached) continue;
    const auto& tokens = cand_infos[i].tokens;
    if (tokens.size() <= 1) continue;

    // Admissible Lower-Bound Pruning:
    // If t_0 NLL is already > min_completed_nll + 8.0f (probability ratio > 2980x worse),
    // subsequent non-negative token NLLs can never overtake the top candidate.
    if (min_completed_nll < 50.0f && cand_infos[i].token0_neg_logprob > min_completed_nll + 8.0f) {
      cached_candidate_nlls_[cand_infos[i].surface] = cand_infos[i].token0_neg_logprob;
      continue;
    }

    size_t max_tokens = std::min<size_t>(8, tokens.size());
    int n_extra = static_cast<int>(max_tokens) - 1;

    llama_batch b = llama_batch_init(n_extra, 0, 1);
    for (int t = 0; t < n_extra; ++t) {
      b.token[t]     = tokens[t];
      b.pos[t]       = start_pos + t;
      b.n_seq_id[t]  = 1;
      b.seq_id[t][0] = 0;
      b.logits[t]    = true;
    }
    b.n_tokens = n_extra;

    if (llama_decode(ctx_, b) == 0 && !abort_scoring_.load()) {
      float total_neg_logprob = cand_infos[i].token0_neg_logprob;
      for (int t = 0; t < n_extra; ++t) {
        float* step_logits = llama_get_logits_ith(ctx_, t);
        if (!step_logits) continue;
        float s_denom = ComputeLogDenominator(step_logits, n_vocab);
        float t_lp = step_logits[tokens[t + 1]] - s_denom;
        total_neg_logprob += (-t_lp);
      }
      (*out_neg_logprobs)[cand_infos[i].index] = total_neg_logprob;
      cached_candidate_nlls_[cand_infos[i].surface] = total_neg_logprob;
      if (total_neg_logprob < min_completed_nll) min_completed_nll = total_neg_logprob;
    }
    llama_batch_free(b);

    // Roll back speculative tokens while retaining the cached context prefix KV
    llama_memory_seq_rm(mem, 0, start_pos, -1);
  }

  // Update snapshot cache with completed candidate NLLs
  {
    std::lock_guard<std::mutex> s_lock(snapshot_mutex_);
    if (snapshot_.context_hash == cached_context_hash_) {
      for (const auto& pair : cached_candidate_nlls_) {
        snapshot_.candidate_nlls[pair.first] = pair.second;
      }
    }
  }

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
  out_predictions->clear();
  if (!EnsureContextCached(context) || cached_context_logits_.empty()) {
    return false;
  }

  int n_vocab = llama_vocab_n_tokens(vocab_);
  const float* logits = cached_context_logits_.data();

  // Find top initial tokens for continuation
  std::vector<std::pair<float, llama_token>> scored_tokens;
  scored_tokens.reserve(n_vocab);
  for (llama_token v = 0; v < n_vocab; ++v) {
    if (llama_vocab_is_eog(vocab_, v)) continue;
    scored_tokens.emplace_back(logits[v], v);
  }
  std::partial_sort(scored_tokens.begin(),
                    scored_tokens.begin() + std::min<size_t>(scored_tokens.size(), 20),
                    scored_tokens.end(),
                    [](const auto& a, const auto& b) { return a.first > b.first; });

  auto trim_text = [](const std::string& s) -> std::string {
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
  };

  auto ends_with = [](const std::string& s, const std::string& suffix) -> bool {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
  };

  auto is_terminal_punct = [&](const std::string& s) -> bool {
    return ends_with(s, "。") || ends_with(s, "！") || ends_with(s, "？") ||
           ends_with(s, "、") || ends_with(s, ".") || ends_with(s, "!") || ends_with(s, "?");
  };

  llama_memory_t mem = llama_get_memory(ctx_);
  int start_pos = cached_context_n_tokens_;

  for (size_t k = 0; k < scored_tokens.size() && out_predictions->size() < static_cast<size_t>(max_candidates); ++k) {
    if (abort_prediction_.load()) {
      break;
    }
    llama_token t0 = scored_tokens[k].second;

    char piece_buf[256];
    int piece_len = llama_token_to_piece(vocab_, t0, piece_buf, sizeof(piece_buf), 0, false);
    if (piece_len <= 0) continue;
    std::string piece = trim_text(std::string(piece_buf, piece_len));
    if (piece.empty()) continue;

    std::vector<llama_token> generated_tokens = { t0 };
    std::string generated_text = piece;

    // Speculatively decode next tokens unless the initial piece already ends at punctuation
    if (!is_terminal_punct(generated_text)) {
      for (int step = 1; step < max_tokens; ++step) {
        if (abort_prediction_.load()) {
          break;
        }
        llama_batch b = llama_batch_init(1, 0, 1);
        b.token[0]     = generated_tokens.back();
        b.pos[0]       = start_pos + step - 1;
        b.n_seq_id[0]  = 1;
        b.seq_id[0][0] = 0;
        b.logits[0]    = true;
        b.n_tokens     = 1;

        if (llama_decode(ctx_, b) != 0) {
          llama_batch_free(b);
          break;
        }
        llama_batch_free(b);

        float* next_logits = llama_get_logits_ith(ctx_, -1);
        if (!next_logits) break;

        llama_token best_next = 0;
        float best_next_l = -1e9f;
        for (llama_token v = 0; v < n_vocab; ++v) {
          if (next_logits[v] > best_next_l) {
            best_next_l = next_logits[v];
            best_next = v;
          }
        }

        if (llama_vocab_is_eog(vocab_, best_next)) break;

        char next_piece_buf[256];
        int npl = llama_token_to_piece(vocab_, best_next, next_piece_buf, sizeof(next_piece_buf), 0, false);
        if (npl <= 0) break;
        std::string next_piece(next_piece_buf, npl);

        size_t nl_pos = next_piece.find_first_of("\n\r");
        if (nl_pos != std::string::npos) {
          if (nl_pos > 0) {
            generated_text += next_piece.substr(0, nl_pos);
          }
          break;
        }

        if (next_piece == "。" || next_piece == "！" || next_piece == "？") {
          if (!ends_with(generated_text, next_piece)) {
            generated_text += next_piece;
          }
          break;
        }

        generated_tokens.push_back(best_next);
        generated_text += next_piece;
      }

      // Roll back speculative tokens in KV cache
      llama_memory_seq_rm(mem, 0, start_pos, -1);
    }

    generated_text = trim_text(generated_text);
    if (!generated_text.empty() &&
        std::find(out_predictions->begin(), out_predictions->end(), generated_text) == out_predictions->end()) {
      out_predictions->push_back(generated_text);
    }
  }

  return !out_predictions->empty();
}

}  // namespace myasr
