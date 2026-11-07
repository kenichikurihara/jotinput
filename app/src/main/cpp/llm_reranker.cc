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
  cached_context_log_denom_ = 0.0f;
  LOGI("LlmReranker released.");
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

  initialized_ = true;
  cached_context_hash_ = 0;
  cached_context_n_tokens_ = 0;
  cache_hits_ = 0;
  cache_misses_ = 0;
  LOGI("LlmReranker initialized successfully with model: %s", config_.model_path.c_str());
  return true;
}

bool LlmReranker::EnsureContextCached(const std::string& context) {
  std::string effective_context = context.empty() ? "<s>" : context;
  uint64_t current_hash = ComputeContextHash(effective_context);
  if (current_hash == cached_context_hash_ && cached_context_n_tokens_ > 0 && !cached_context_tokens_.empty()) {
    cache_hits_++;
    return true;
  }

  cache_misses_++;
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

  float max_l = -1e9f;
  for (int v = 0; v < n_vocab; ++v) {
    if (cached_context_logits_[v] > max_l) max_l = cached_context_logits_[v];
  }
  double sum_exp = 0.0;
  for (int v = 0; v < n_vocab; ++v) {
    sum_exp += std::exp(cached_context_logits_[v] - max_l);
  }
  cached_context_log_denom_ = max_l + static_cast<float>(std::log(sum_exp));

  cached_context_hash_ = current_hash;
  cached_context_n_tokens_ = n_tokens;
  cached_context_tokens_ = std::move(context_tokens);
  return true;
}

bool LlmReranker::ScoreCandidates(const std::string& context,
                                  const std::vector<std::string>& candidates,
                                  std::vector<float>* out_neg_logprobs) {
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
  };

  std::vector<CandidateInfo> cand_infos;
  cand_infos.reserve(candidates.size());

  for (size_t i = 0; i < candidates.size(); ++i) {
    CandidateInfo info;
    info.index = i;
    info.surface = candidates[i];

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
    // In subword BPE tokenization (e.g. Qwen, LLaMA), words following whitespace or preceding
    // tokens have leading-space variants (e.g. " will" token 686 vs "will" token 14387).
    // If context does not end with whitespace and candidate does not start with whitespace,
    // evaluate both forms and select the one matching the language model's subword boundary.
    if (!context.empty() && !isspace(static_cast<unsigned char>(context.back())) &&
        !cand_str.empty() && !isspace(static_cast<unsigned char>(cand_str.front()))) {
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
    cand_infos.push_back(std::move(info));
  }

  for (const auto& info : cand_infos) {
    (*out_neg_logprobs)[info.index] = info.token0_neg_logprob;
  }

  // Stage 2: Full-Sequence Joint Negative Log-Likelihood (Idea 1)
  // Evaluate all tokens t_0 .. t_{L-1} for multi-token candidates so that
  // joint sequence probability -log P(W|C) = \sum_{k=0}^{L-1} -log P(t_k | C, t_{<k})
  // is compared on a uniform probability scale without subword length-division bias
  // or premature Stage-1 top-2 pruning.
  llama_memory_t mem = llama_get_memory(ctx_);
  int start_pos = cached_context_n_tokens_;

  for (size_t i = 0; i < cand_infos.size(); ++i) {
    const auto& tokens = cand_infos[i].tokens;
    if (tokens.size() <= 1) continue;

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

    if (llama_decode(ctx_, b) == 0) {
      float total_neg_logprob = cand_infos[i].token0_neg_logprob;
      for (int t = 0; t < n_extra; ++t) {
        float* step_logits = llama_get_logits_ith(ctx_, t);
        if (!step_logits) continue;
        float s_max = -1e9f;
        for (int v = 0; v < n_vocab; ++v) {
          if (step_logits[v] > s_max) s_max = step_logits[v];
        }
        double s_sum = 0.0;
        for (int v = 0; v < n_vocab; ++v) {
          s_sum += std::exp(step_logits[v] - s_max);
        }
        float s_denom = s_max + static_cast<float>(std::log(s_sum));
        float t_lp = step_logits[tokens[t + 1]] - s_denom;
        total_neg_logprob += (-t_lp);
      }
      (*out_neg_logprobs)[cand_infos[i].index] = total_neg_logprob;
    }
    llama_batch_free(b);

    // Roll back speculative tokens while retaining the cached context prefix KV
    llama_memory_seq_rm(mem, 0, start_pos, -1);
  }

  return true;
}

bool LlmReranker::PredictNextPhrases(const std::string& context,
                                     int max_candidates,
                                     int max_tokens,
                                     std::vector<std::string>* out_predictions) {
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

  llama_memory_t mem = llama_get_memory(ctx_);
  int start_pos = cached_context_n_tokens_;

  for (size_t k = 0; k < scored_tokens.size() && out_predictions->size() < static_cast<size_t>(max_candidates); ++k) {
    llama_token t0 = scored_tokens[k].second;

    char piece_buf[256];
    int piece_len = llama_token_to_piece(vocab_, t0, piece_buf, sizeof(piece_buf), 0, false);
    if (piece_len <= 0) continue;
    std::string piece(piece_buf, piece_len);
    if (piece.empty() || piece == " " || piece == "\n" || piece == "\t") continue;

    std::vector<llama_token> generated_tokens = { t0 };
    std::string generated_text = piece;

    // Speculatively decode next tokens
    for (int step = 1; step < max_tokens; ++step) {
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

      if (next_piece == "。" || next_piece == "\n" || next_piece == "！" || next_piece == "？") {
        generated_text += next_piece;
        break;
      }

      generated_tokens.push_back(best_next);
      generated_text += next_piece;
    }

    // Roll back speculative tokens in KV cache
    llama_memory_seq_rm(mem, 0, start_pos, -1);

    if (!generated_text.empty() &&
        std::find(out_predictions->begin(), out_predictions->end(), generated_text) == out_predictions->end()) {
      out_predictions->push_back(generated_text);
    }
  }

  return !out_predictions->empty();
}

}  // namespace myasr
