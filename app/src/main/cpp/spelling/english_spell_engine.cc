#include "english_spell_engine.h"
#include "onnxruntime_cxx_api.h"

#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>

#define LOG_TAG "EnglishSpellEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace myasr {
namespace spelling {

static const int MAX_SEQ_LEN = 96;
static const int VOCAB_SIZE = 24000;
static const int BOS_ID = 2;
static const int EOS_ID = 3;

// QWERTY key positions for physical proximity distance
struct KeyCoord {
    float x;
    float y;
};

static std::unordered_map<char, KeyCoord> CreateQwertyKeyMap() {
    std::unordered_map<char, KeyCoord> map;
    const char* rows[] = {
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    };
    for (int r = 0; r < 3; ++r) {
        float offset = (r == 0) ? 0.0f : ((r == 1) ? 0.5f : 1.0f);
        int len = static_cast<int>(std::string(rows[r]).length());
        for (int c = 0; c < len; ++c) {
            char ch = rows[r][c];
            map[ch] = KeyCoord{c + offset, static_cast<float>(r)};
        }
    }
    return map;
}

static float KeyDistance(char c1, char c2, const std::unordered_map<char, KeyCoord>& map) {
    c1 = std::tolower(static_cast<unsigned char>(c1));
    c2 = std::tolower(static_cast<unsigned char>(c2));
    if (c1 == c2) return 0.0f;
    auto it1 = map.find(c1);
    auto it2 = map.find(c2);
    if (it1 != map.end() && it2 != map.end()) {
        float dx = it1->second.x - it2->second.x;
        float dy = it1->second.y - it2->second.y;
        return std::sqrt(dx * dx + dy * dy);
    }
    return 3.0f;
}

// SentencePiece Unigram Tokenizer
class UnigramTokenizer {
public:
    struct PieceEntry {
        std::string piece;
        float score;
    };

    bool Load(const std::string& bin_path) {
        std::ifstream ifs(bin_path, std::ios::binary);
        if (!ifs) {
            LOGE("Failed to open tokenizer bin: %s", bin_path.c_str());
            return false;
        }

        char magic[4];
        ifs.read(magic, 4);
        if (std::string(magic, 4) != "SPM1") {
            LOGE("Invalid magic in %s", bin_path.c_str());
            return false;
        }

        uint32_t count = 0;
        ifs.read(reinterpret_cast<char*>(&count), sizeof(count));

        pieces_.resize(count);
        piece_to_id_.clear();
        piece_to_score_.clear();

        for (uint32_t i = 0; i < count; ++i) {
            uint16_t len = 0;
            float score = 0.0f;
            ifs.read(reinterpret_cast<char*>(&len), sizeof(len));
            ifs.read(reinterpret_cast<char*>(&score), sizeof(score));

            std::string str(len, '\0');
            ifs.read(&str[0], len);

            pieces_[i] = PieceEntry{str, score};
            piece_to_id_[str] = static_cast<int>(i);
            piece_to_score_[str] = score;
        }

        LOGI("Loaded SPM unigram model with %u pieces", count);
        return true;
    }

    int PieceToId(const std::string& piece) const {
        auto it = piece_to_id_.find(piece);
        return (it != piece_to_id_.end()) ? it->second : 1; // 1 = <unk>
    }

    std::string IdToPiece(int id) const {
        if (id >= 0 && id < static_cast<int>(pieces_.size())) {
            return pieces_[id].piece;
        }
        return "<unk>";
    }

    std::vector<int> Encode(const std::string& text) const {
        if (text.empty()) return {};

        // SentencePiece LOWER ONE EIGHTH BLOCK: chr(9601) = "\xE2\x96\x81" in UTF-8
        static const std::string SPACE_CHAR = "\xE2\x96\x81";

        std::string norm = text;
        // Trim leading / trailing spaces
        size_t start = norm.find_first_not_of(" \t\r\n");
        if (start == std::string::npos) return {};
        size_t end = norm.find_last_not_of(" \t\r\n");
        norm = norm.substr(start, end - start + 1);

        // Replace spaces with SPACE_CHAR
        std::string transformed;
        transformed.reserve(norm.size() + 4);
        for (char ch : norm) {
            if (ch == ' ') {
                transformed += SPACE_CHAR;
            } else {
                transformed += ch;
            }
        }
        if (transformed.rfind(SPACE_CHAR, 0) != 0) {
            transformed = SPACE_CHAR + transformed;
        }

        int n = static_cast<int>(transformed.size());
        std::vector<float> best_score(n + 1, -1e18f);
        std::vector<int> best_prev(n + 1, -1);
        std::vector<int> best_id(n + 1, -1);
        best_score[0] = 0.0f;

        for (int i = 0; i < n; ++i) {
            if (best_score[i] < -1e17f) continue;
            int max_len = std::min(n - i, 32);
            for (int l = 1; l <= max_len; ++l) {
                std::string sub = transformed.substr(i, l);
                auto it = piece_to_score_.find(sub);
                if (it != piece_to_score_.end()) {
                    float sc = best_score[i] + it->second;
                    if (sc > best_score[i + l]) {
                        best_score[i + l] = sc;
                        best_prev[i + l] = i;
                        best_id[i + l] = piece_to_id_.at(sub);
                    }
                }
            }
        }

        std::vector<int> token_ids;
        int curr = n;
        while (curr > 0) {
            int p = best_prev[curr];
            if (p == -1) {
                // Character-by-character fallback for unknown byte
                int id = PieceToId(transformed.substr(curr - 1, 1));
                token_ids.push_back(id);
                curr -= 1;
            } else {
                token_ids.push_back(best_id[curr]);
                curr = p;
            }
        }
        std::reverse(token_ids.begin(), token_ids.end());
        return token_ids;
    }

    std::string Decode(const std::vector<int>& ids) const {
        static const std::string SPACE_CHAR = "\xE2\x96\x81";
        std::string raw;
        for (int id : ids) {
            if (id == BOS_ID || id == EOS_ID || id == 0) continue;
            raw += IdToPiece(id);
        }

        // Replace SPACE_CHAR with standard space
        std::string out;
        size_t pos = 0;
        bool is_first = true;
        while (pos < raw.size()) {
            if (raw.compare(pos, SPACE_CHAR.size(), SPACE_CHAR) == 0) {
                if (!is_first) {
                    out += ' ';
                }
                pos += SPACE_CHAR.size();
                is_first = false;
            } else {
                out += raw[pos++];
                is_first = false;
            }
        }
        return out;
    }

private:
    std::vector<PieceEntry> pieces_;
    std::unordered_map<std::string, int> piece_to_id_;
    std::unordered_map<std::string, float> piece_to_score_;
};

class EnglishSpellEngine::Impl {
public:
    Impl()
        : env_(ORT_LOGGING_LEVEL_WARNING, "EnglishSpellEngine"),
          qwerty_map_(CreateQwertyKeyMap()) {}

    bool Initialize(const std::string& model_dir) {
        model_dir_ = model_dir;
        std::string enc_path = model_dir + "/gec-encoder-int8.onnx";
        std::string dec_path = model_dir + "/gec-decoder-int8.onnx";
        std::string spm_path = model_dir + "/spm_unigram.bin";
        std::string vocab_path = model_dir + "/vocab.txt";
        // Fallback check in futo_swipe if vocab.txt not in english_spelling
        if (!FileExists(vocab_path)) {
            vocab_path = model_dir + "/../futo_swipe/vocab.txt";
        }

        if (!FileExists(enc_path) || !FileExists(dec_path) || !FileExists(spm_path)) {
            LOGW("Missing model files in %s", model_dir.c_str());
            return false;
        }

        try {
            Ort::SessionOptions opts;
            opts.SetIntraOpNumThreads(2);
            opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

            enc_sess_ = std::make_unique<Ort::Session>(env_, enc_path.c_str(), opts);
            dec_sess_ = std::make_unique<Ort::Session>(env_, dec_path.c_str(), opts);

            if (!tokenizer_.Load(spm_path)) {
                LOGE("Failed to load tokenizer binary from %s", spm_path.c_str());
                return false;
            }

            LoadVocabulary(vocab_path);
            is_ready_ = true;
            LOGI("EnglishSpellEngine successfully initialized.");
            return true;
        } catch (const std::exception& e) {
            LOGE("Error initializing EnglishSpellEngine: %s", e.what());
            is_ready_ = false;
            return false;
        }
    }

    bool IsReady() const {
        return is_ready_;
    }

    void SyncUserDictionary(const std::vector<UserSpellWord>& user_words) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (user_words.size() == user_words_.size()) {
            bool identical = true;
            for (size_t i = 0; i < user_words.size(); ++i) {
                if (user_words[i].surface != user_words_[i].surface ||
                    user_words[i].priority_score != user_words_[i].priority_score) {
                    identical = false;
                    break;
                }
            }
            if (identical) {
                return;
            }
        }

        word_to_count_.clear();
        deletes_dict_.clear();
        prefix_dict_.clear();
        user_words_ = user_words;

        for (const auto& uw : user_words) {
            std::string w_lower = uw.surface;
            std::transform(w_lower.begin(), w_lower.end(), w_lower.begin(), ::tolower);
            if (w_lower.empty()) continue;

            int count = (uw.priority_score >= 4.0f) ? 50000000 : ((uw.priority_score >= 2.5f) ? 30000000 : 15000000);
            word_to_count_[w_lower] = WordInfo{uw.surface, count};

            auto dels = GetDeletes(w_lower, 2);
            for (const auto& d : dels) {
                deletes_dict_[d].push_back(w_lower);
            }

            // Also register in user prefix_dict_
            int L = static_cast<int>(w_lower.length());
            for (int p_len = 2; p_len <= std::min(L, 10); ++p_len) {
                std::string p = w_lower.substr(0, p_len);
                auto& p_list = prefix_dict_[p];
                if (std::find(p_list.begin(), p_list.end(), w_lower) == p_list.end()) {
                    p_list.push_back(w_lower);
                    if (p_list.size() > 14) {
                        p_list.pop_back();
                    }
                }
            }
        }
        LOGI("Synced %zu user spell words (layered).", user_words.size());
    }

    std::vector<std::string> SuggestTypo(const std::string& word, int max_candidates) {
        if (word.empty()) return {};
        std::lock_guard<std::mutex> lock(mutex_);

        std::string typed_lower = word;
        std::transform(typed_lower.begin(), typed_lower.end(), typed_lower.begin(), ::tolower);
        int m = static_cast<int>(typed_lower.length());

        std::unordered_set<std::string> cands;

        // 1. Direct Prefix Completion candidates (e.g. examp -> example)
        auto up_it = prefix_dict_.find(typed_lower);
        if (up_it != prefix_dict_.end()) {
            for (const auto& w : up_it->second) {
                cands.insert(w);
            }
        }
        auto bp_it = base_prefix_dict_.find(typed_lower);
        if (bp_it != base_prefix_dict_.end()) {
            for (const auto& w : bp_it->second) {
                cands.insert(w);
            }
        }

        // 2. Exact match
        if (word_to_count_.find(typed_lower) != word_to_count_.end() ||
            base_word_to_count_.find(typed_lower) != base_word_to_count_.end()) {
            cands.insert(typed_lower);
        }

        // 3. Explicitly check user words
        for (const auto& uw : user_words_) {
            std::string w_lower = uw.surface;
            std::transform(w_lower.begin(), w_lower.end(), w_lower.begin(), ::tolower);
            cands.insert(w_lower);
        }

        // 4. SymSpell 2-deletes for typos
        auto deletes = GetDeletes(typed_lower, 2);
        for (const auto& d : deletes) {
            if (word_to_count_.find(d) != word_to_count_.end() ||
                base_word_to_count_.find(d) != base_word_to_count_.end()) {
                cands.insert(d);
            }
            auto uit = deletes_dict_.find(d);
            if (uit != deletes_dict_.end()) {
                for (const auto& w : uit->second) {
                    cands.insert(w);
                }
            }
            auto bit = base_deletes_dict_.find(d);
            if (bit != base_deletes_dict_.end()) {
                for (const auto& w : bit->second) {
                    cands.insert(w);
                }
            }
        }

        // 5. Fuzzy prefix matches (for 1-char typo during typing long word)
        if (m >= 4) {
            auto deletes1 = GetDeletes(typed_lower, 1);
            for (const auto& d : deletes1) {
                auto ufp_it = prefix_dict_.find(d);
                if (ufp_it != prefix_dict_.end()) {
                    int taken = 0;
                    for (const auto& w : ufp_it->second) {
                        cands.insert(w);
                        if (++taken >= 4) break;
                    }
                }
                auto bfp_it = base_prefix_dict_.find(d);
                if (bfp_it != base_prefix_dict_.end()) {
                    int taken = 0;
                    for (const auto& w : bfp_it->second) {
                        cands.insert(w);
                        if (++taken >= 4) break;
                    }
                }
            }
        }

        struct ScoredCandidate {
            std::string word;
            float score;
        };
        std::vector<ScoredCandidate> scored;

        for (const auto& w : cands) {
            const WordInfo* info = nullptr;
            auto uit = word_to_count_.find(w);
            if (uit != word_to_count_.end()) {
                info = &uit->second;
            } else {
                auto bit = base_word_to_count_.find(w);
                if (bit != base_word_to_count_.end()) {
                    info = &bit->second;
                }
            }
            if (!info) continue;

            int n = static_cast<int>(w.length());
            float log_freq = std::log(static_cast<float>(info->count + 1));

            if (w.rfind(typed_lower, 0) == 0 && n > m) {
                // Pure prefix completion
                float len_diff = static_cast<float>(n - m);
                float cov_ratio = static_cast<float>(m) / n;
                float score = log_freq - 0.45f * len_diff + 2.0f * cov_ratio;
                scored.push_back({info->surface, score});
            } else if (w == typed_lower) {
                // Exact word match
                float score = log_freq + 1.0f;
                scored.push_back({info->surface, score});
            } else {
                // Spatial edit distance for typos
                float cost = EditAndSpatialCost(typed_lower, w);
                float max_len = static_cast<float>(std::max(m, n));
                float norm_cost = (max_len > 0.0f) ? (cost / max_len) : cost;
                float max_cost = (n >= 7) ? 3.6f : 3.0f;
                if (norm_cost <= 0.38f && cost <= max_cost) {
                    float score = log_freq - 2.8f * cost;
                    scored.push_back({info->surface, score});
                }
            }
        }

        std::sort(scored.begin(), scored.end(), [](const auto& a, const auto& b) {
            return a.score > b.score;
        });

        std::vector<std::string> results;
        for (const auto& sc : scored) {
            results.push_back(sc.word);
            if (static_cast<int>(results.size()) >= max_candidates) break;
        }
        return results;
    }

    std::string CorrectSentence(const std::string& text, const std::string& lang) {
        if (!is_ready_ || text.empty()) return text;

        try {
            std::string lang_tag = "<" + lang + ">";
            int lang_id = tokenizer_.PieceToId(lang_tag);
            auto text_ids = tokenizer_.Encode(text);

            std::vector<int64_t> src(MAX_SEQ_LEN, 0);
            src[0] = lang_id;
            int num_ids = static_cast<int>(text_ids.size());
            int copy_len = std::min(num_ids, MAX_SEQ_LEN - 1);
            for (int i = 0; i < copy_len; ++i) {
                src[i + 1] = text_ids[i];
            }
            int total_src_len = copy_len + 1;

            Ort::MemoryInfo mem_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

            // 1. Encoder Run
            int64_t src_shape[] = {1, MAX_SEQ_LEN};
            Ort::Value src_val = Ort::Value::CreateTensor<int64_t>(
                mem_info, src.data(), MAX_SEQ_LEN, src_shape, 2
            );

            const char* enc_in_names[] = {"src"};
            const char* enc_out_names[] = {"memory"};
            auto enc_outs = enc_sess_->Run(
                Ort::RunOptions{nullptr}, enc_in_names, &src_val, 1, enc_out_names, 1
            );

            // memory output shape: [1, 96, 256]
            float* mem_data = enc_outs[0].GetTensorMutableData<float>();
            int64_t mem_shape[] = {1, MAX_SEQ_LEN, 256};
            Ort::Value mem_val = Ort::Value::CreateTensor<float>(
                mem_info, mem_data, MAX_SEQ_LEN * 256, mem_shape, 3
            );

            // 2. Decoder Autoregressive Loop
            std::vector<int64_t> tgt(MAX_SEQ_LEN, 0);
            tgt[0] = BOS_ID;

            std::vector<int> generated_ids;
            int max_steps = std::min(total_src_len + 8, MAX_SEQ_LEN - 1);

            const char* dec_in_names[] = {"tgt", "pos", "memory", "src"};
            const char* dec_out_names[] = {"logits"};

            for (int pos = 0; pos < max_steps; ++pos) {
                int64_t pos_val = pos;
                int64_t pos_shape[] = {1};
                Ort::Value pos_tensor = Ort::Value::CreateTensor<int64_t>(
                    mem_info, &pos_val, 1, pos_shape, 1
                );

                int64_t tgt_shape[] = {1, MAX_SEQ_LEN};
                Ort::Value tgt_tensor = Ort::Value::CreateTensor<int64_t>(
                    mem_info, tgt.data(), MAX_SEQ_LEN, tgt_shape, 2
                );

                Ort::Value src_tensor_copy = Ort::Value::CreateTensor<int64_t>(
                    mem_info, src.data(), MAX_SEQ_LEN, src_shape, 2
                );

                Ort::Value dec_inputs[] = {
                    std::move(tgt_tensor),
                    std::move(pos_tensor),
                    Ort::Value::CreateTensor<float>(mem_info, mem_data, MAX_SEQ_LEN * 256, mem_shape, 3),
                    std::move(src_tensor_copy)
                };

                auto dec_outs = dec_sess_->Run(
                    Ort::RunOptions{nullptr}, dec_in_names, dec_inputs, 4, dec_out_names, 1
                );

                const float* logits = dec_outs[0].GetTensorData<float>(); // [1, 24000]
                int nxt = 0;
                float max_l = logits[0];
                for (int v = 1; v < VOCAB_SIZE; ++v) {
                    if (logits[v] > max_l) {
                        max_l = logits[v];
                        nxt = v;
                    }
                }

                if (nxt == EOS_ID) {
                    break;
                }

                tgt[pos + 1] = nxt;
                generated_ids.push_back(nxt);
            }

            return tokenizer_.Decode(generated_ids);
        } catch (const std::exception& e) {
            LOGE("Error during CorrectSentence: %s", e.what());
            return text;
        }
    }

private:
    struct WordInfo {
        std::string surface;
        int count;
    };

    static bool FileExists(const std::string& path) {
        std::ifstream f(path.c_str());
        return f.good();
    }

    void LoadVocabulary(const std::string& vocab_path) {
        std::ifstream ifs(vocab_path);
        if (!ifs) {
            LOGW("Vocabulary file not found: %s", vocab_path.c_str());
            return;
        }

        base_word_to_count_.clear();
        base_deletes_dict_.clear();
        base_prefix_dict_.clear();
        base_word_to_count_.reserve(35000);
        base_deletes_dict_.reserve(450000);
        base_prefix_dict_.reserve(120000);

        std::string line;
        int rank = 0;
        while (std::getline(ifs, line)) {
            // Trim
            size_t s = line.find_first_not_of(" \t\r\n");
            if (s == std::string::npos) continue;
            size_t e = line.find_last_not_of(" \t\r\n");
            std::string word = line.substr(s, e - s + 1);

            std::string w_lower = word;
            std::transform(w_lower.begin(), w_lower.end(), w_lower.begin(), ::tolower);

            int count = static_cast<int>(10000000.0 / (rank + 1));
            if (base_word_to_count_.find(w_lower) == base_word_to_count_.end()) {
                base_word_to_count_.emplace(w_lower, WordInfo{std::move(word), count});
                auto dels = GetDeletes(w_lower, 2);
                for (auto& d : dels) {
                    base_deletes_dict_[std::move(d)].push_back(w_lower);
                }

                // Register in base_prefix_dict_ (length 2 to min(L, 10))
                int L = static_cast<int>(w_lower.length());
                for (int p_len = 2; p_len <= std::min(L, 10); ++p_len) {
                    auto& p_list = base_prefix_dict_[w_lower.substr(0, p_len)];
                    if (p_list.size() < 12) {
                        p_list.push_back(w_lower);
                    }
                }
            }
            rank++;
        }
        LOGI("Loaded %zu base words, %zu delete index entries, %zu prefix entries (zero-copy).",
             base_word_to_count_.size(), base_deletes_dict_.size(), base_prefix_dict_.size());
    }

    std::vector<std::string> GetDeletes(const std::string& word, int max_edit_distance) {
        std::unordered_set<std::string> deletes;
        std::vector<std::string> queue = {word};

        for (int d = 0; d < max_edit_distance; ++d) {
            std::vector<std::string> temp;
            for (const auto& w : queue) {
                if (w.length() > 1) {
                    for (size_t i = 0; i < w.length(); ++i) {
                        std::string del = w.substr(0, i) + w.substr(i + 1);
                        if (deletes.insert(del).second) {
                            temp.push_back(del);
                        }
                    }
                }
            }
            queue = std::move(temp);
        }
        return std::vector<std::string>(deletes.begin(), deletes.end());
    }

    float EditAndSpatialCost(const std::string& typed, const std::string& cand) const {
        static constexpr float kSpatialBoundaryGapCost = 1.5f;
        static constexpr float kSpatialIndelCost = 1.2f;
        static constexpr float kSpatialSubBaseCost = 0.8f;
        static constexpr float kSpatialSubDistSlope = 0.3f;
        static constexpr float kSpatialSubMaxDist = 2.0f;
        static constexpr float kSpatialTranspositionCost = 0.6f;

        int m = static_cast<int>(typed.length());
        int n = static_cast<int>(cand.length());
        std::vector<std::vector<float>> dp(m + 1, std::vector<float>(n + 1, 0.0f));

        for (int i = 0; i <= m; ++i) dp[i][0] = i * kSpatialBoundaryGapCost;
        for (int j = 0; j <= n; ++j) dp[0][j] = j * kSpatialBoundaryGapCost;

        for (int i = 1; i <= m; ++i) {
            for (int j = 1; j <= n; ++j) {
                float kd = KeyDistance(typed[i - 1], cand[j - 1], qwerty_map_);
                float sub_cost = (kd == 0.0f) ? 0.0f : (kSpatialSubBaseCost + kSpatialSubDistSlope * std::min(kd, kSpatialSubMaxDist));
                dp[i][j] = std::min({
                    dp[i - 1][j] + kSpatialIndelCost,
                    dp[i][j - 1] + kSpatialIndelCost,
                    dp[i - 1][j - 1] + sub_cost
                });
                if (i > 1 && j > 1 && typed[i - 1] == cand[j - 2] && typed[i - 2] == cand[j - 1]) {
                    dp[i][j] = std::min(dp[i][j], dp[i - 2][j - 2] + kSpatialTranspositionCost);
                }
            }
        }
        return dp[m][n];
    }

    std::string model_dir_;
    bool is_ready_ = false;

    Ort::Env env_;
    std::unique_ptr<Ort::Session> enc_sess_;
    std::unique_ptr<Ort::Session> dec_sess_;

    UnigramTokenizer tokenizer_;
    std::unordered_map<char, KeyCoord> qwerty_map_;
    std::unordered_map<std::string, WordInfo> word_to_count_;
    std::unordered_map<std::string, WordInfo> base_word_to_count_;
    std::unordered_map<std::string, std::vector<std::string>> deletes_dict_;
    std::unordered_map<std::string, std::vector<std::string>> base_deletes_dict_;
    std::unordered_map<std::string, std::vector<std::string>> prefix_dict_;
    std::unordered_map<std::string, std::vector<std::string>> base_prefix_dict_;
    std::vector<UserSpellWord> user_words_;
    std::mutex mutex_;
};

EnglishSpellEngine::EnglishSpellEngine() : impl_(std::make_unique<Impl>()) {}
EnglishSpellEngine::~EnglishSpellEngine() = default;

bool EnglishSpellEngine::Initialize(const std::string& model_dir) {
    return impl_->Initialize(model_dir);
}

bool EnglishSpellEngine::IsReady() const {
    return impl_->IsReady();
}

void EnglishSpellEngine::SyncUserDictionary(const std::vector<UserSpellWord>& user_words) {
    impl_->SyncUserDictionary(user_words);
}

std::vector<std::string> EnglishSpellEngine::SuggestTypo(const std::string& word, int max_candidates) {
    return impl_->SuggestTypo(word, max_candidates);
}

std::string EnglishSpellEngine::CorrectSentence(const std::string& text, const std::string& lang) {
    return impl_->CorrectSentence(text, lang);
}

} // namespace spelling
} // namespace myasr
