#include <iostream>
#include <iomanip>
#include <vector>
#include <string>
#include <sstream>
#include <memory>
#include <chrono>

#include "rewriter/llm_reranker.h"

int main(int argc, char* argv[]) {
    mozc::LlmRerankerConfig config;
    if (argc > 1) {
        config.model_path = argv[1];
    } else {
        config.model_path = "./tools/models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
    }
    config.n_threads = 4;
    config.n_ctx = 256;
    config.fast_path_threshold = 1.0f;

    auto reranker = std::make_shared<mozc::LlmReranker>(config);
    if (!reranker->Initialize()) {
        std::cerr << "Failed to initialize LlmReranker" << std::endl;
        return 1;
    }
    std::cerr << "[SLM Scorer] Model initialized successfully." << std::endl;

    // Read TSV from stdin: id \t context \t cand1 \t cand2 \t ...
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        std::stringstream ss(line);
        std::string id, context, cand;
        if (!std::getline(ss, id, '\t')) continue;
        if (!std::getline(ss, context, '\t')) continue;

        std::vector<std::string> cands;
        while (std::getline(ss, cand, '\t')) {
            if (!cand.empty()) cands.push_back(cand);
        }

        if (cands.empty()) {
            std::cout << id << "\n";
            continue;
        }

        std::vector<float> logprobs;
        std::string eff_ctx = context.empty() ? "<s>" : context;
        auto t0 = std::chrono::high_resolution_clock::now();
        bool ok = reranker->ScoreCandidatesAdaptive(eff_ctx, cands, &logprobs);
        auto t1 = std::chrono::high_resolution_clock::now();
        double lat_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();

        std::cout << id << "\t" << std::fixed << std::setprecision(2) << lat_ms;
        if (ok && logprobs.size() == cands.size()) {
            for (float lp : logprobs) {
                std::cout << "\t" << std::setprecision(4) << lp;
            }
        }
        std::cout << "\n";
        std::cout.flush();
    }
    return 0;
}
