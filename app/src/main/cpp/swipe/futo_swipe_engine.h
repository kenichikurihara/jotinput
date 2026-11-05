#pragma once

#include <string>
#include <vector>
#include <memory>
#include <unordered_map>

namespace myasr {
namespace swipe {

struct SwipePoint {
    float x;
    float y;
    int64_t t; // timestamp (ms or us)
};

struct SwipeCandidate {
    std::string word;
    float score;
};

struct UserSwipeWord {
    std::string surface;
    float priority_score;
};

class FutoSwipeEngine {
public:
    FutoSwipeEngine();
    ~FutoSwipeEngine();

    // Load ONNX models and vocabulary from external files directory
    bool Initialize(const std::string& model_dir);
    bool IsReady() const;

    // Sync active user dictionary entries into Trie
    void SyncUserDictionary(const std::vector<UserSwipeWord>& user_words);

    // Decode swipe trajectory with optional context words
    std::vector<SwipeCandidate> Decode(
        const std::vector<SwipePoint>& trajectory,
        const std::vector<std::string>& previous_words,
        int beam_width = 100,
        int max_candidates = 5
    );

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace swipe
} // namespace myasr
