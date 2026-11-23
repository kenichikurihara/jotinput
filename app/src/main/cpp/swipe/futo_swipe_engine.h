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

    // Load extended OOV swipe vocabulary (Idea A Compact CSR Trie + Idea E freq/surface)
    bool LoadExtendedLexicon(const std::string& tsv_path);
    size_t ExtendedWordCount() const;
    bool ContainsBaseWord(const std::string& lower_word) const;

    // Sync active user dictionary entries into Pass 1 Trie
    void SyncUserDictionary(const std::vector<UserSwipeWord>& user_words);

    // Sync adaptive OOV LRU cache entries (Idea C) into Pass 1 Trie
    void SyncAdaptiveCache(const std::vector<std::string>& adaptive_words);

    // Update normalized (x, y) centers for the 26 QWERTY alphabet keys ('a'..'z', size = 52)
    void SetLayoutKeys(const std::vector<float>& key_centers_xy);

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
