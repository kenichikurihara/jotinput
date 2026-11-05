#pragma once

#include <string>
#include <vector>
#include <memory>

namespace myasr {
namespace spelling {

struct UserSpellWord {
    std::string surface;
    float priority_score;
};

class EnglishSpellEngine {
public:
    EnglishSpellEngine();
    ~EnglishSpellEngine();

    // Load models and vocabulary from external files directory
    // model_dir should contain gec-encoder-int8.onnx, gec-decoder-int8.onnx, spm_unigram.bin, and vocab.txt
    bool Initialize(const std::string& model_dir);
    bool IsReady() const;

    // Sync active user dictionary entries
    void SyncUserDictionary(const std::vector<UserSpellWord>& user_words);

    // Fast-path single-word typo and spatial proximity correction (< 1ms)
    std::vector<std::string> SuggestTypo(const std::string& word, int max_candidates = 5);

    // Neural sentence-level context correction (~20ms)
    std::string CorrectSentence(const std::string& text, const std::string& lang = "en");

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace spelling
} // namespace myasr
