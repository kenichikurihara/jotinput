#include <chrono>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "llm_reranker.h"

namespace {

std::vector<std::string> SplitTabs(const std::string& line) {
  std::vector<std::string> out;
  size_t start = 0;
  while (true) {
    size_t pos = line.find('\t', start);
    if (pos == std::string::npos) {
      out.push_back(line.substr(start));
      break;
    }
    out.push_back(line.substr(start, pos - start));
    start = pos + 1;
  }
  return out;
}

std::vector<float> ParseCsvFloats(const std::string& csv) {
  std::vector<float> out;
  if (csv.empty() || csv == "-") return out;
  std::stringstream ss(csv);
  std::string item;
  while (std::getline(ss, item, ',')) {
    if (!item.empty()) {
      out.push_back(std::strtof(item.c_str(), nullptr));
    }
  }
  return out;
}

std::string UnescapeControl(const std::string& s) {
  std::string out;
  out.reserve(s.size());
  for (size_t i = 0; i < s.size(); ++i) {
    if (s[i] == '\\' && i + 1 < s.size()) {
      if (s[i + 1] == 'n') {
        out.push_back('\n');
        ++i;
        continue;
      } else if (s[i + 1] == 't') {
        out.push_back('\t');
        ++i;
        continue;
      } else if (s[i + 1] == '\\') {
        out.push_back('\\');
        ++i;
        continue;
      }
    }
    out.push_back(s[i]);
  }
  return out;
}

std::string EscapeTabs(const std::string& s) {
  std::string out;
  out.reserve(s.size());
  for (char c : s) {
    if (c == '\t') out += "\\t";
    else if (c == '\n') out += "\\n";
    else if (c == '\r') out += "\\r";
    else out.push_back(c);
  }
  return out;
}

}  // namespace

int main(int argc, char* argv[]) {
  std::ios::sync_with_stdio(false);
  std::cin.tie(nullptr);

  auto& reranker = myasr::LlmReranker::GetInstance();
  myasr::LlmRerankerConfig cfg = reranker.GetConfig();

  if (argc >= 2) {
    std::string model_path = argv[1];
    int n_threads = (argc >= 3) ? std::atoi(argv[2]) : 4;
    if (!reranker.Initialize(model_path, n_threads)) {
      std::cerr << "INIT_FAIL " << model_path << "\n";
      return 1;
    }
    cfg = reranker.GetConfig();
    std::cout << "INIT_OK\n" << std::flush;
  }

  std::string line;
  while (std::getline(std::cin, line)) {
    if (line.empty()) continue;
    auto parts = SplitTabs(line);
    const std::string& cmd = parts[0];

    if (cmd == "INIT" && parts.size() >= 2) {
      int threads = (parts.size() >= 3) ? std::atoi(parts[2].c_str()) : 4;
      bool ok = reranker.Initialize(parts[1], threads);
      cfg = reranker.GetConfig();
      std::cout << (ok ? "INIT_OK\n" : "INIT_FAIL\n") << std::flush;
    } else if (cmd == "CONFIG") {
      cfg = reranker.GetConfig();
      for (size_t i = 1; i < parts.size(); ++i) {
        size_t eq = parts[i].find('=');
        if (eq == std::string::npos) continue;
        std::string k = parts[i].substr(0, eq);
        std::string v = parts[i].substr(eq + 1);
        if (k == "stage2_policy") {
          int iv = std::atoi(v.c_str());
          cfg.stage2_policy = static_cast<myasr::Stage2Policy>(iv);
        } else if (k == "close_gap") {
          cfg.stage2_close_gap_nats = std::strtof(v.c_str(), nullptr);
        } else if (k == "extra_prior") {
          cfg.token0_extra_token_nll_prior = std::strtof(v.c_str(), nullptr);
        } else if (k == "max_cands") {
          cfg.max_stage2_candidates = std::atoi(v.c_str());
        } else if (k == "max_depth") {
          cfg.max_stage2_tree_depth = std::atoi(v.c_str());
        } else if (k == "fast_lse") {
          cfg.use_fast_subvocab_lse = (std::atoi(v.c_str()) != 0);
        } else if (k == "suffix_cache") {
          cfg.enable_suffix_approx_cache = (std::atoi(v.c_str()) != 0);
        } else if (k == "suffix_min") {
          cfg.suffix_approx_min_tokens = std::atoi(v.c_str());
        } else if (k == "zero_decode_next") {
          cfg.enable_zero_decode_next_phrase = (std::atoi(v.c_str()) != 0);
        } else if (k == "decisive_gap") {
          cfg.decisive_top1_gap_nats = std::strtof(v.c_str(), nullptr);
        } else if (k == "prune_margin") {
          cfg.stage2_prune_margin_nats = std::strtof(v.c_str(), nullptr);
        }
      }
      reranker.SetConfig(cfg);
      std::cout << "CONFIG_OK\n" << std::flush;
    } else if (cmd == "RESET") {
      reranker.ResetCaches();
      std::cout << "RESET_OK\n" << std::flush;
    } else if (cmd == "RESET_STATS") {
      reranker.ResetStats();
      std::cout << "RESET_STATS_OK\n" << std::flush;
    } else if (cmd == "STATS") {
      auto st = reranker.GetStats();
      std::cout << "STATS\t{"
                << "\"cache_hits\":" << st.cache_hits
                << ",\"cache_misses\":" << st.cache_misses
                << ",\"suffix_approx_hits\":" << st.suffix_approx_hits
                << ",\"stage2_calls\":" << st.stage2_calls
                << ",\"stage2_skipped_calls\":" << st.stage2_skipped_calls
                << ",\"stage2_tree_nodes_decoded\":" << st.stage2_tree_nodes_decoded
                << ",\"prefill_tokens_decoded\":" << st.prefill_tokens_decoded
                << ",\"next_phrase_calls\":" << st.next_phrase_calls
                << ",\"next_phrase_decode_calls\":" << st.next_phrase_decode_calls
                << "}\n" << std::flush;
    } else if (cmd == "WARM" && parts.size() >= 3) {
      const std::string& id = parts[1];
      std::string ctx = UnescapeControl(parts[2]);
      auto t0 = std::chrono::steady_clock::now();
      bool ok = reranker.WarmContext(ctx);
      auto t1 = std::chrono::steady_clock::now();
      double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
      std::cout << "WARM\t" << id << "\t" << std::fixed << std::setprecision(4) << ms
                << "\t" << (ok ? 1 : 0) << "\n" << std::flush;
    } else if (cmd == "SCORE" && parts.size() >= 4) {
      const std::string& id = parts[1];
      std::string ctx = UnescapeControl(parts[2]);
      std::vector<float> priors = ParseCsvFloats(parts[3]);
      std::vector<std::string> cands;
      cands.reserve(parts.size() - 4);
      for (size_t i = 4; i < parts.size(); ++i) {
        cands.push_back(UnescapeControl(parts[i]));
      }
      if (priors.size() != cands.size()) {
        priors.assign(cands.size(), 0.0f);
      }
      std::vector<float> nlls;
      auto t0 = std::chrono::steady_clock::now();
      bool ok = reranker.ScoreCandidatesWithPriors(ctx, cands, priors, &nlls);
      auto t1 = std::chrono::steady_clock::now();
      double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
      std::cout << "SCORE\t" << id << "\t" << std::fixed << std::setprecision(4) << ms;
      if (ok && nlls.size() == cands.size()) {
        for (float x : nlls) {
          std::cout << "\t" << std::setprecision(5) << x;
        }
      }
      std::cout << "\n" << std::flush;
    } else if (cmd == "FAST_SCORE" && parts.size() >= 3) {
      const std::string& id = parts[1];
      std::string ctx = UnescapeControl(parts[2]);
      std::vector<std::string> cands;
      for (size_t i = 3; i < parts.size(); ++i) {
        cands.push_back(UnescapeControl(parts[i]));
      }
      std::vector<float> nlls;
      std::vector<bool> complete;
      auto t0 = std::chrono::steady_clock::now();
      bool ok = reranker.TryFastScoreCandidates(ctx, cands, &nlls, &complete);
      auto t1 = std::chrono::steady_clock::now();
      double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
      bool all_done = ok;
      for (bool b : complete) {
        if (!b) { all_done = false; break; }
      }
      std::cout << "FAST_SCORE\t" << id << "\t" << (ok ? 1 : 0) << "\t" << (all_done ? 1 : 0)
                << "\t" << std::fixed << std::setprecision(4) << ms;
      if (ok && nlls.size() == cands.size()) {
        for (float x : nlls) {
          std::cout << "\t" << std::setprecision(5) << x;
        }
      }
      std::cout << "\n" << std::flush;
    } else if (cmd == "PREDICT" && parts.size() >= 5) {
      const std::string& id = parts[1];
      std::string ctx = UnescapeControl(parts[2]);
      int max_cands = std::atoi(parts[3].c_str());
      int max_toks = std::atoi(parts[4].c_str());
      std::vector<std::string> preds;
      auto t0 = std::chrono::steady_clock::now();
      bool ok = reranker.PredictNextPhrases(ctx, max_cands, max_toks, &preds);
      auto t1 = std::chrono::steady_clock::now();
      double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
      std::cout << "PREDICT\t" << id << "\t" << std::fixed << std::setprecision(4) << ms
                << "\t" << (ok ? 1 : 0);
      for (const auto& p : preds) {
        std::cout << "\t" << EscapeTabs(p);
      }
      std::cout << "\n" << std::flush;
    } else if (cmd == "QUIT") {
      break;
    }
  }

  reranker.Release();
  return 0;
}
