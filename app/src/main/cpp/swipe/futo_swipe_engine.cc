#include "futo_swipe_engine.h"

#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <algorithm>
#include <fstream>
#include <sstream>
#include <cstring>
#include <chrono>
#include <limits>
#include <mutex>
#include <unordered_set>

#include "onnxruntime_cxx_api.h"

#define TAG "FutoSwipeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace myasr {
namespace swipe {

namespace {

constexpr int FUTO_SWIPE_TIME_STEPS = 32;
constexpr int FUTO_SWIPE_CLASS_COUNT = 27; // 26 letters + 1 blank
constexpr int FUTO_SWIPE_MAX_KEYS = 64;
constexpr int FUTO_CONTEXT_LENGTH = 16;
constexpr int FUTO_CONTEXT_DIM = 16;
constexpr int FUTO_CONTEXT_VOCAB_SIZE = 32768;
constexpr int64_t FUTO_CONTEXT_HASHED_TOKEN_ID = 32768;

constexpr float kExtPass2GateBaseTot = -0.50f;
constexpr int kExtPass2BeamWidth = 40;
constexpr float kExtOovPenaltySpatial = 1.50f;
constexpr float kExtOovPenaltyContext = 2.50f;
constexpr float kExtLogFreqWeight = 0.20f;
constexpr int kExtMinWordLen = 3;
constexpr int kExtMaxWordLen = 28;
constexpr float kAdaptiveBoost = 1.20f;
constexpr float kAdaptiveMaxBaseGap = 4.50f;
constexpr float kAdaptiveBiasFloor = -4.20f;

std::string NormalizeSwipeTrieKey(const std::string& surface) {
    std::string key;
    key.reserve(surface.size());
    for (unsigned char uc : surface) {
        if (uc == '\'' || uc == '-') continue;
        if (uc >= 'A' && uc <= 'Z') {
            key.push_back(static_cast<char>(uc + 32));
        } else if (uc >= 'a' && uc <= 'z') {
            key.push_back(static_cast<char>(uc));
        } else {
            return "";
        }
    }
    return key;
}

constexpr uint64_t MASK_64 = 0xFFFFFFFFFFFFFFFFULL;
constexpr uint64_t CONSTANTS[4] = {
    0xa0761d6478bd642fULL,
    0xe7037ed1a0b428dbULL,
    0x8ebc6af09c88c6e3ULL,
    0x589965cc75374cc3ULL,
};
constexpr uint64_t BUCKET_MULTIPLIERS[2] = {
    0x9e3779b97f4a7c15ULL,
    0x517cc1b727220a95ULL,
};

inline uint64_t Mix(uint64_t a, uint64_t b) {
    __uint128_t prod = static_cast<__uint128_t>(a) * static_cast<__uint128_t>(b);
    return static_cast<uint64_t>(prod) ^ static_cast<uint64_t>(prod >> 64);
}

uint64_t FutoContextHash(const std::string& word) {
    const uint8_t* b = reinterpret_cast<const uint8_t*>(word.data());
    size_t n = word.size();

    auto read_u = [&](size_t start, size_t width) -> uint64_t {
        uint64_t val = 0;
        for (size_t i = 0; i < width; ++i) {
            val |= static_cast<uint64_t>(b[start + i]) << (8 * i);
        }
        return val;
    };

    uint64_t state = Mix(CONSTANTS[0], CONSTANTS[1]);
    uint64_t left = 0;
    uint64_t right = 0;

    if (n <= 16) {
        if (n >= 4) {
            size_t step = (n / 8) * 4;
            left = (read_u(0, 4) << 32) | read_u(step, 4);
            right = (read_u(n - 4, 4) << 32) | read_u(n - 4 - step, 4);
        } else if (n > 0) {
            left = (static_cast<uint64_t>(b[0]) << 16) |
                   (static_cast<uint64_t>(b[n / 2]) << 8) |
                   static_cast<uint64_t>(b[n - 1]);
        }
    } else {
        uint64_t lanes[3] = {state, state, state};
        size_t offset = 0;
        if (n > 48) {
            while (offset + 48 <= n) {
                for (size_t lane = 0; lane < 3; ++lane) {
                    size_t p = offset + lane * 16;
                    lanes[lane] = Mix(
                        read_u(p, 8) ^ CONSTANTS[lane + 1],
                        read_u(p + 8, 8) ^ lanes[lane]
                    );
                }
                offset += 48;
            }
        }
        while (offset + 16 <= n) {
            lanes[0] = Mix(
                read_u(offset, 8) ^ CONSTANTS[1],
                read_u(offset + 8, 8) ^ lanes[0]
            );
            offset += 16;
        }
        state = (n > 48) ? (lanes[0] ^ lanes[1] ^ lanes[2]) : lanes[0];
        left = read_u(n - 16, 8);
        right = read_u(n - 8, 8);
    }

    return Mix(
        CONSTANTS[1] ^ n,
        Mix(left ^ CONSTANTS[1], right ^ state)
    );
}

std::pair<int64_t, int64_t> FutoContextBuckets(const std::string& word) {
    uint64_t h = FutoContextHash(word);
    int64_t b0 = static_cast<int64_t>((static_cast<__uint128_t>(h) * BUCKET_MULTIPLIERS[0]) >> 49) & 0x7FFF;
    int64_t b1 = static_cast<int64_t>((static_cast<__uint128_t>(h) * BUCKET_MULTIPLIERS[1]) >> 49) & 0x7FFF;
    return {b0, b1};
}

// Trace Resampling (Tier 1 Idea 5):
// 1. Stationary mid-stroke dwell clamping (max_dt_ms = 65.0ms when spatial movement < 0.03)
// 2. 60Hz temporal interpolation
// 3. Time + cumulative arc-length hybrid parameterization (arc_blend = 0.02)
// 4. Endpoint-preserving 3-tap low-pass smoothing (smooth_alpha = 0.06)
std::vector<float> ResampleTrace(const std::vector<SwipePoint>& trace) {
    std::vector<float> result(2 * 64, 0.0f);
    size_t count = trace.size();
    if (count == 0) return result;

    int64_t t0 = trace[0].t;
    std::vector<double> times(count), xs(count), ys(count);
    for (size_t i = 0; i < count; ++i) {
        times[i] = static_cast<double>(trace[i].t - t0);
        xs[i] = trace[i].x;
        ys[i] = trace[i].y;
    }
    if (count > 1 && times.back() > 100000.0) {
        for (size_t i = 0; i < count; ++i) times[i] /= 1000.0;
    }

    // 1. Clamp stationary mid-stroke pauses
    if (count > 2) {
        constexpr double kMaxStationaryDtMs = 65.0;
        constexpr double kStationaryDistThr = 0.03;
        std::vector<double> clamped_times(count, 0.0);
        for (size_t i = 0; i + 1 < count; ++i) {
            double dt = times[i + 1] - times[i];
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            double dist = std::hypot(dx, dy);
            if (dt > kMaxStationaryDtMs && dist < kStationaryDistThr) {
                dt = kMaxStationaryDtMs;
            }
            clamped_times[i + 1] = clamped_times[i] + dt;
        }
        times = std::move(clamped_times);
    }

    double duration = times.back();
    std::vector<double> stage_x, stage_y;

    if (duration > 1e-3) {
        int samples = std::max(2, static_cast<int>(std::round(duration / (1000.0 / 60.0))) + 1);
        std::vector<double> grid(samples);
        for (int i = 0; i < samples; ++i) {
            grid[i] = duration * i / (samples - 1);
        }
        stage_x.resize(samples);
        stage_y.resize(samples);

        size_t curr = 0;
        for (int i = 0; i < samples; ++i) {
            double g = grid[i];
            while (curr + 1 < count && times[curr + 1] < g) curr++;
            if (curr + 1 >= count) {
                stage_x[i] = xs.back();
                stage_y[i] = ys.back();
            } else {
                double dt = times[curr + 1] - times[curr];
                double frac = (dt > 1e-6) ? (g - times[curr]) / dt : 0.0;
                stage_x[i] = xs[curr] + frac * (xs[curr + 1] - xs[curr]);
                stage_y[i] = ys[curr] + frac * (ys[curr + 1] - ys[curr]);
            }
        }
    } else {
        stage_x = xs;
        stage_y = ys;
    }

    // 2. Hybrid time + cumulative arc-length parameterization to 64 points
    size_t s_len = stage_x.size();
    std::vector<float> rx(64, 0.0f), ry(64, 0.0f);
    constexpr double kArcBlend = 0.02;

    if (s_len > 2 && kArcBlend > 0.0) {
        std::vector<double> cum_s(s_len, 0.0);
        for (size_t i = 0; i + 1 < s_len; ++i) {
            double ds = std::hypot(stage_x[i + 1] - stage_x[i], stage_y[i + 1] - stage_y[i]);
            cum_s[i + 1] = cum_s[i] + ds;
        }
        if (cum_s.back() > 1e-5) {
            std::vector<double> hybrid_param(s_len, 0.0);
            double total_s = cum_s.back();
            for (size_t i = 0; i < s_len; ++i) {
                double t_norm = static_cast<double>(i) / static_cast<double>(s_len - 1);
                double s_norm = cum_s[i] / total_s;
                hybrid_param[i] = (1.0 - kArcBlend) * t_norm + kArcBlend * s_norm;
            }
            size_t curr = 0;
            for (int i = 0; i < 64; ++i) {
                double target_p = static_cast<double>(i) / 63.0;
                while (curr + 1 < s_len && hybrid_param[curr + 1] < target_p) curr++;
                if (curr + 1 >= s_len) {
                    rx[i] = static_cast<float>(stage_x.back());
                    ry[i] = static_cast<float>(stage_y.back());
                } else {
                    double dp = hybrid_param[curr + 1] - hybrid_param[curr];
                    double frac = (dp > 1e-8) ? (target_p - hybrid_param[curr]) / dp : 0.0;
                    rx[i] = static_cast<float>(stage_x[curr] + frac * (stage_x[curr + 1] - stage_x[curr]));
                    ry[i] = static_cast<float>(stage_y[curr] + frac * (stage_y[curr + 1] - stage_y[curr]));
                }
            }
        } else {
            for (int i = 0; i < 64; ++i) {
                rx[i] = static_cast<float>(stage_x[0]);
                ry[i] = static_cast<float>(stage_y[0]);
            }
        }
    } else {
        for (int i = 0; i < 64; ++i) {
            double pos = static_cast<double>(s_len - 1) * i / 63.0;
            size_t p_floor = static_cast<size_t>(pos);
            size_t p_ceil = std::min(p_floor + 1, s_len - 1);
            double frac = pos - p_floor;
            rx[i] = static_cast<float>(stage_x[p_floor] + frac * (stage_x[p_ceil] - stage_x[p_floor]));
            ry[i] = static_cast<float>(stage_y[p_floor] + frac * (stage_y[p_ceil] - stage_y[p_floor]));
        }
    }

    // 3. Endpoint-preserving 3-tap low-pass smoothing on interior points [1..62]
    constexpr float kSmoothAlpha = 0.06f;
    result[0] = rx[0];
    result[64] = ry[0];
    result[63] = rx[63];
    result[64 + 63] = ry[63];
    for (int i = 1; i < 63; ++i) {
        result[i] = (1.0f - kSmoothAlpha) * rx[i] + 0.5f * kSmoothAlpha * (rx[i - 1] + rx[i + 1]);
        result[64 + i] = (1.0f - kSmoothAlpha) * ry[i] + 0.5f * kSmoothAlpha * (ry[i - 1] + ry[i + 1]);
    }
    return result;
}

inline float LogAdd(float a, float b) {
    if (std::isinf(a) && a < 0.0f) return b;
    if (std::isinf(b) && b < 0.0f) return a;
    float hi = std::max(a, b);
    float lo = std::min(a, b);
    return hi + std::log1p(std::exp(lo - hi));
}

// Flat-array Trie for ultra-fast CTC beam search
struct SurfaceEntry {
    std::string surface;
    int vocab_rank = 32768;
    float zipf_freq = 0.0f;
    bool has_apostrophe = false;
};

struct TrieNode {
    std::string text;
    int last = -1;
    int children[26];
    std::vector<SurfaceEntry> surfaces;
    bool has_plain_surface = false;
    float max_user_priority = 0.0f;
    bool has_user_subtree = false;

    TrieNode(const std::string& t, int l) : text(t), last(l) {
        std::fill(std::begin(children), std::end(children), -1);
    }
};

struct PrefixMass {
    float blank = -std::numeric_limits<float>::infinity();
    float letter = -std::numeric_limits<float>::infinity();
    float priority = -std::numeric_limits<float>::infinity();

    inline float Total() const {
        return LogAdd(blank, letter);
    }
};

// Idea A: Compact CSR Trie node (16 bytes/node) & entry for ~68k OOV extended lexicon
struct ExtEntry {
    std::string surface;
    float log_freq = 0.0f;
};

struct ExtTrieNode {
    uint32_t edge_start = 0;
    uint32_t edge_end = 0;
    int32_t entry_idx = -1;
    int8_t first = -1;
    int8_t last = -1;
    uint8_t depth = 0;
};

} // namespace

class FutoSwipeEngine::Impl {
public:
    Impl() : env_(ORT_LOGGING_LEVEL_WARNING, "FutoSwipe") {}

    bool Initialize(const std::string& model_dir) {
        try {
            Ort::SessionOptions opts;
            opts.SetIntraOpNumThreads(4);
            opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

            std::string enc_path = model_dir + "/encoder.onnx";
            std::string dec_path = model_dir + "/decoder.onnx";
            std::string clm_path = model_dir + "/context_lm.onnx";
            std::string emb_path = model_dir + "/embeddings.onnx";
            std::string vocab_path = model_dir + "/vocab.txt";

            enc_sess_ = std::make_unique<Ort::Session>(env_, enc_path.c_str(), opts);
            dec_sess_ = std::make_unique<Ort::Session>(env_, dec_path.c_str(), opts);
            clm_sess_ = std::make_unique<Ort::Session>(env_, clm_path.c_str(), opts);
            emb_sess_ = std::make_unique<Ort::Session>(env_, emb_path.c_str(), opts);

            // Run embeddings extraction
            auto emb_outs = emb_sess_->Run(
                Ort::RunOptions{nullptr}, nullptr, nullptr, 0,
                emb_output_names_.data(), emb_output_names_.size()
            );

            // Shape: (32768, 16) exact_emb, (32768) exact_bias, (32768, 16) hash_emb, (32768) hash_bias
            const float* exact_emb_ptr = emb_outs[0].GetTensorData<float>();
            const float* exact_bias_ptr = emb_outs[1].GetTensorData<float>();
            const float* hash_emb_ptr = emb_outs[2].GetTensorData<float>();
            const float* hash_bias_ptr = emb_outs[3].GetTensorData<float>();

            exact_embeddings_.assign(exact_emb_ptr, exact_emb_ptr + 32768 * 16);
            exact_biases_.assign(exact_bias_ptr, exact_bias_ptr + 32768);
            hashed_embeddings_.assign(hash_emb_ptr, hash_emb_ptr + 32768 * 16);
            hashed_biases_.assign(hash_bias_ptr, hash_bias_ptr + 32768);

            // Load vocabulary & build Trie
            trie_nodes_.clear();
            trie_nodes_.reserve(120000);
            trie_nodes_.emplace_back("", -1); // Root at index 0

            std::ifstream vf(vocab_path);
            if (!vf.is_open()) {
                LOGE("Failed to open vocab file: %s", vocab_path.c_str());
                return false;
            }

            vocabulary_.clear();
            vocabulary_.reserve(35000);
            exact_ids_.clear();
            exact_ids_.reserve(35000);
            lowercase_ids_.clear();
            lowercase_ids_.reserve(35000);
            base_lowercase_set_.clear();
            base_lowercase_set_.reserve(35000);

            std::string line;
            int vocab_idx = 0;
            while (std::getline(vf, line)) {
                if (line.empty()) continue;
                vocabulary_.push_back(line);
                if (vocab_idx < 32767) {
                    int token_id = vocab_idx + 1;
                    exact_ids_[line] = token_id;
                    std::string lower = line;
                    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
                    lowercase_ids_.emplace(lower, token_id);
                }
                AddWordToTrie(line, vocab_idx);
                vocab_idx++;
            }

            // Idea 2: Derive missing base stems from possessive ('s) vocabulary entries
            // (e.g., York's -> York, Alzheimer's -> Alzheimer, Macy's -> Macy)
            for (size_t rank = 0; rank < vocabulary_.size(); ++rank) {
                const std::string& w = vocabulary_[rank];
                if (w.size() >= 4 && w[w.size() - 2] == '\'' && (w.back() == 's' || w.back() == 'S')) {
                    std::string stem = w.substr(0, w.size() - 2);
                    bool all_alpha = true;
                    std::string lower_stem = stem;
                    for (char& c : lower_stem) {
                        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                            all_alpha = false;
                            break;
                        }
                        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                    }
                    if (all_alpha && lowercase_ids_.find(lower_stem) == lowercase_ids_.end()) {
                        AddWordToTrie(stem, static_cast<int>(rank));
                    }
                }
            }

            // Init layout keys
            InitQwertyLayout();

            base_trie_nodes_ = trie_nodes_;

            ready_ = true;
            LOGI("FutoSwipeEngine initialized successfully. Vocab=%zu, Trie nodes=%zu",
                 vocabulary_.size(), trie_nodes_.size());
            return true;
        } catch (const std::exception& e) {
            LOGE("Initialization error: %s", e.what());
            ready_ = false;
            return false;
        }
    }

    bool IsReady() const { return ready_; }

    size_t ExtendedWordCount() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return ext_entries_.size();
    }

    bool ContainsBaseWord(const std::string& lower_word) const {
        std::string key = NormalizeSwipeTrieKey(lower_word);
        if (key.empty()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return base_lowercase_set_.find(key) != base_lowercase_set_.end();
    }

    bool LoadExtendedLexicon(const std::string& tsv_path) {
        std::unordered_set<std::string> base_copy;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!ready_) return false;
            base_copy = base_lowercase_set_;
        }

        std::ifstream tf(tsv_path);
        if (!tf.is_open()) {
            LOGW("Failed to open extended swipe lexicon: %s", tsv_path.c_str());
            return false;
        }

        std::vector<ExtEntry> new_entries;
        std::vector<std::string> entry_keys;
        new_entries.reserve(75000);
        entry_keys.reserve(75000);
        std::unordered_map<std::string, int32_t> seen_keys;
        seen_keys.reserve(75000);

        std::string line;
        while (std::getline(tf, line)) {
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }
            if (line.empty() || line[0] == '#') continue;

            size_t t1 = line.find('\t');
            if (t1 == std::string::npos || t1 < static_cast<size_t>(kExtMinWordLen) || t1 > static_cast<size_t>(kExtMaxWordLen)) {
                continue;
            }
            size_t t2 = line.find('\t', t1 + 1);
            if (t2 == std::string::npos || t2 <= t1 + 1 || t2 + 1 >= line.size()) {
                continue;
            }

            std::string key = line.substr(0, t1);
            bool valid_alpha = true;
            for (char c : key) {
                if (c < 'a' || c > 'z') {
                    valid_alpha = false;
                    break;
                }
            }
            if (!valid_alpha) continue;
            if (base_copy.find(key) != base_copy.end()) continue;

            std::string surf = line.substr(t1 + 1, t2 - (t1 + 1));
            if (surf.empty()) continue;

            float lf = 0.0f;
            try {
                lf = std::stof(line.substr(t2 + 1));
            } catch (...) {
                continue;
            }
            if (!std::isfinite(lf) || lf <= 0.0f) continue;

            auto sit = seen_keys.find(key);
            if (sit != seen_keys.end()) {
                if (lf > new_entries[sit->second].log_freq) {
                    new_entries[sit->second].surface = std::move(surf);
                    new_entries[sit->second].log_freq = lf;
                }
            } else {
                int32_t idx = static_cast<int32_t>(new_entries.size());
                seen_keys.emplace(key, idx);
                entry_keys.push_back(std::move(key));
                new_entries.push_back(ExtEntry{std::move(surf), lf});
            }
        }

        if (new_entries.empty()) {
            LOGW("Extended swipe lexicon contained 0 eligible OOV words: %s", tsv_path.c_str());
            return false;
        }

        struct TempExtNode {
            int32_t entry_idx = -1;
            int8_t first = -1;
            int8_t last = -1;
            uint8_t depth = 0;
            std::vector<std::pair<uint8_t, int32_t>> edges;
        };

        std::vector<TempExtNode> temp_nodes;
        temp_nodes.reserve(new_entries.size() * 3 + 16);
        temp_nodes.emplace_back(); // Root at 0

        for (size_t e_idx = 0; e_idx < new_entries.size(); ++e_idx) {
            const std::string& key = entry_keys[e_idx];
            int32_t curr = 0;
            int8_t first_ch = static_cast<int8_t>(key[0] - 'a');
            for (size_t pos = 0; pos < key.size(); ++pos) {
                uint8_t ch = static_cast<uint8_t>(key[pos] - 'a');
                int32_t next_idx = -1;
                for (const auto& edge : temp_nodes[curr].edges) {
                    if (edge.first == ch) {
                        next_idx = edge.second;
                        break;
                    }
                }
                if (next_idx < 0) {
                    next_idx = static_cast<int32_t>(temp_nodes.size());
                    temp_nodes.emplace_back();
                    temp_nodes[next_idx].first = first_ch;
                    temp_nodes[next_idx].last = static_cast<int8_t>(ch);
                    temp_nodes[next_idx].depth = static_cast<uint8_t>(pos + 1);
                    temp_nodes[curr].edges.emplace_back(ch, next_idx);
                }
                curr = next_idx;
            }
            temp_nodes[curr].entry_idx = static_cast<int32_t>(e_idx);
        }

        size_t total_edges = 0;
        for (const auto& tn : temp_nodes) {
            total_edges += tn.edges.size();
        }

        std::vector<ExtTrieNode> new_nodes(temp_nodes.size());
        std::vector<int32_t> new_edge_child;
        std::vector<uint8_t> new_edge_char;
        new_edge_child.reserve(total_edges);
        new_edge_char.reserve(total_edges);

        for (size_t i = 0; i < temp_nodes.size(); ++i) {
            auto& edges = temp_nodes[i].edges;
            if (edges.size() > 1) {
                std::sort(edges.begin(), edges.end(), [](const auto& a, const auto& b) {
                    return a.first < b.first;
                });
            }
            new_nodes[i].edge_start = static_cast<uint32_t>(new_edge_child.size());
            for (const auto& edge : edges) {
                new_edge_char.push_back(edge.first);
                new_edge_child.push_back(edge.second);
            }
            new_nodes[i].edge_end = static_cast<uint32_t>(new_edge_child.size());
            new_nodes[i].entry_idx = temp_nodes[i].entry_idx;
            new_nodes[i].first = temp_nodes[i].first;
            new_nodes[i].last = temp_nodes[i].last;
            new_nodes[i].depth = temp_nodes[i].depth;
        }

        size_t words_loaded = new_entries.size();
        size_t nodes_loaded = new_nodes.size();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            ext_nodes_.swap(new_nodes);
            ext_edge_child_.swap(new_edge_child);
            ext_edge_char_.swap(new_edge_char);
            ext_entries_.swap(new_entries);
            ext_stamp_.assign(ext_nodes_.size(), 0);
            ext_mass_.resize(ext_nodes_.size());
            ext_stamp_gen_ = 0;
        }

        LOGI("Loaded extended swipe CSR Trie: words=%zu, nodes=%zu, edges=%zu",
             words_loaded, nodes_loaded, total_edges);
        return true;
    }

    void SyncUserDictionary(const std::vector<UserSwipeWord>& user_words) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (base_trie_nodes_.empty()) return;
        cached_user_words_ = user_words;
        RebuildActiveTrieLocked();
    }

    void SyncAdaptiveCache(const std::vector<std::string>& adaptive_words) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (base_trie_nodes_.empty()) return;
        cached_adaptive_words_ = adaptive_words;
        RebuildActiveTrieLocked();
    }

    void SetLayoutKeys(const std::vector<float>& key_centers_xy) {
        if (key_centers_xy.size() < 52) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (layout_keys_.size() != FUTO_SWIPE_MAX_KEYS * 2) {
            layout_keys_.assign(FUTO_SWIPE_MAX_KEYS * 2, 0.0f);
            layout_mask_.assign(FUTO_SWIPE_MAX_KEYS, false);
        }
        for (int i = 0; i < 26; ++i) {
            key_centers_[i][0] = key_centers_xy[i * 2];
            key_centers_[i][1] = key_centers_xy[i * 2 + 1];
            layout_keys_[i * 2] = key_centers_[i][0];
            layout_keys_[i * 2 + 1] = key_centers_[i][1];
            layout_mask_[i] = true;
        }
    }

    std::vector<SwipeCandidate> Decode(
        const std::vector<SwipePoint>& trajectory,
        const std::vector<std::string>& previous_words,
        int beam_width,
        int max_candidates
    ) {
        if (!ready_ || trajectory.size() < 2) return {};
        std::lock_guard<std::mutex> lock(mutex_);

        // 1. Trace Resampling (Idea 5)
        std::vector<float> resampled = ResampleTrace(trajectory); // [2, 64]
        float px0 = resampled[0], py0 = resampled[64];
        float px1 = resampled[63], py1 = resampled[127];

        // 2. Run Encoder
        int64_t feat_shape[3] = {1, 2, 64};
        int64_t key_shape[3] = {1, 64, 2};
        int64_t mask_shape[2] = {1, 64};

        auto memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

        Ort::Value feat_tensor = Ort::Value::CreateTensor<float>(
            memory_info, resampled.data(), resampled.size(), feat_shape, 3
        );
        Ort::Value key_tensor = Ort::Value::CreateTensor<float>(
            memory_info, layout_keys_.data(), layout_keys_.size(), key_shape, 3
        );
        Ort::Value mask_tensor = Ort::Value::CreateTensor<bool>(
            memory_info, reinterpret_cast<bool*>(layout_mask_.data()), layout_mask_.size(), mask_shape, 2
        );

        const char* enc_in_names[] = {"features", "layout_keys", "layout_mask"};
        const char* enc_out_names[] = {"log_emissions", "coefficients", "intention"};
        Ort::Value enc_inputs[] = {std::move(feat_tensor), std::move(key_tensor), std::move(mask_tensor)};

        auto enc_outs = enc_sess_->Run(
            Ort::RunOptions{nullptr}, enc_in_names, enc_inputs, 3, enc_out_names, 3
        );

        const float* log_emissions = enc_outs[0].GetTensorData<float>(); // [1, 32, 65]
        const float* coefficients = enc_outs[1].GetTensorData<float>();  // [1, 32, 64]
        const float* intention = enc_outs[2].GetTensorData<float>();     // [1, 32, 1]

        // 3. Assemble Decoder Input: [1, 32, 92]
        std::vector<float> dec_input(FUTO_SWIPE_TIME_STEPS * 92, 0.0f);
        for (int t = 0; t < FUTO_SWIPE_TIME_STEPS; ++t) {
            float* row = &dec_input[t * 92];
            // Letters 0..25
            std::memcpy(row, &log_emissions[t * 65], 26 * sizeof(float));
            // Blank index 26
            row[26] = log_emissions[t * 65 + 64];
            // Coefficients 27..90
            std::memcpy(&row[27], &coefficients[t * 64], 64 * sizeof(float));
            // Intention 91
            row[91] = intention[t];
        }

        // 4. Run Decoder
        int64_t dec_shape[3] = {1, FUTO_SWIPE_TIME_STEPS, 92};
        Ort::Value dec_tensor = Ort::Value::CreateTensor<float>(
            memory_info, dec_input.data(), dec_input.size(), dec_shape, 3
        );
        const char* dec_in_names[] = {"features"};
        const char* dec_out_names[] = {"log_emissions"};
        auto dec_outs = dec_sess_->Run(
            Ort::RunOptions{nullptr}, dec_in_names, &dec_tensor, 1, dec_out_names, 1
        );
        const float* refined_logits = dec_outs[0].GetTensorData<float>(); // [1, 32, 27]

        // 5. CTC Prefix Search on Trie
        bool use_context = !previous_words.empty();
        float gamma_prune = 0.1902f;
        float beta_prune = 1.2727f;
        float gamma = use_context ? 0.1126f : 0.5949f;
        float beta = use_context ? 2.2138f : 0.7271f;
        float alpha = use_context ? 0.6387f : 0.0f;

        std::unordered_map<int, PrefixMass> states;
        states[0].blank = 0.0f;

        float divisors[FUTO_SWIPE_TIME_STEPS + 1];
        for (int i = 0; i <= FUTO_SWIPE_TIME_STEPS; ++i) {
            divisors[i] = std::pow(std::max(1.0f, static_cast<float>(i)), gamma_prune);
        }

        for (int t = 0; t < FUTO_SWIPE_TIME_STEPS; ++t) {
            const float* row = &refined_logits[t * FUTO_SWIPE_CLASS_COUNT];
            int blank = FUTO_SWIPE_CLASS_COUNT - 1;
            std::unordered_map<int, PrefixMass> next_states;

            for (const auto& kv : states) {
                int node_idx = kv.first;
                const PrefixMass& old = kv.second;
                const TrieNode& node = trie_nodes_[node_idx];

                // Blank extension
                PrefixMass& same = next_states[node_idx];
                same.blank = LogAdd(same.blank, old.Total() + row[blank]);

                // Repeat final letter
                if (node.last >= 0) {
                    same.letter = LogAdd(same.letter, old.letter + row[node.last]);
                }

                // Extend with children
                for (int c = 0; c < 26; ++c) {
                    int child_idx = node.children[c];
                    if (child_idx < 0) continue;
                    PrefixMass& dest = next_states[child_idx];
                    float prev = (c == node.last) ? old.blank : old.Total();
                    dest.letter = LogAdd(dest.letter, prev + row[c]);
                }
            }

            // Prune to beam width
            std::vector<std::pair<int, float>> prios;
            prios.reserve(next_states.size());
            for (auto& kv : next_states) {
                int l = trie_nodes_[kv.first].text.size();
                float div = divisors[std::min(l, FUTO_SWIPE_TIME_STEPS)];
                float subtree_bonus = trie_nodes_[kv.first].has_user_subtree ? 0.75f : 0.0f;
                kv.second.priority = kv.second.Total() / div + beta_prune * l + subtree_bonus;
                prios.emplace_back(kv.first, kv.second.priority);
            }
            std::sort(prios.begin(), prios.end(), [](const auto& a, const auto& b) {
                return a.second > b.second;
            });

            states.clear();
            size_t take = std::min(static_cast<size_t>(beam_width), prios.size());
            for (size_t i = 0; i < take; ++i) {
                states[prios[i].first] = next_states[prios[i].first];
            }
        }

        // 6. Context state & scoring (Ideas 2, 3, 4)
        std::vector<float> context_state;
        if (use_context) {
            context_state = ComputeContextState(previous_words);
        }

        float max_base = -1e9f;
        float best_base_tot = -1e9f;
        float best_common_tot = -1e9f;
        for (const auto& kv : states) {
            int node_idx = kv.first;
            const TrieNode& node = trie_nodes_[node_idx];
            if (node.surfaces.empty()) continue;

            float tot = kv.second.Total();
            if (tot > best_base_tot) {
                best_base_tot = tot;
            }
            for (const auto& se : node.surfaces) {
                if (se.vocab_rank < 6000) {
                    if (tot > best_common_tot) best_common_tot = tot;
                    break;
                }
            }

            float l = static_cast<float>(node.text.size());
            float base = tot / std::pow(l, gamma) + beta * l;
            if (base > max_base) max_base = base;
        }

        std::vector<SwipeCandidate> candidates;
        for (const auto& kv : states) {
            int node_idx = kv.first;
            const TrieNode& node = trie_nodes_[node_idx];
            if (node.surfaces.empty() || node.text.empty()) continue;

            float l = static_cast<float>(node.text.size());
            float tot = kv.second.Total();

            // Idea 3: Start & End Key Radial Anchor Distance Penalty
            int c0 = node.text.front() - 'a';
            int c1 = node.text.back() - 'a';
            float dx0 = px0 - key_centers_[c0][0];
            float dy0 = py0 - key_centers_[c0][1];
            float d0_sq = dx0 * dx0 + dy0 * dy0;
            float dx1 = px1 - key_centers_[c1][0];
            float dy1 = py1 - key_centers_[c1][1];
            float d1_sq = dx1 * dx1 + dy1 * dy1;

            float base = tot / std::pow(l, gamma) + beta * l - 2.0f * d0_sq - 3.5f * d1_sq;

            for (const auto& se : node.surfaces) {
                float total_score = base;

                // Idea 4: Shared-node apostrophe disambiguation (e.g., its vs it's, were vs we're)
                if (se.has_apostrophe && node.has_plain_surface) {
                    total_score -= 2.5f;
                }

                auto u_it = user_word_priorities_.find(se.surface);
                bool is_user_word = (u_it != user_word_priorities_.end());
                bool is_adaptive_word = (!is_user_word && adaptive_word_surfaces_.find(se.surface) != adaptive_word_surfaces_.end());

                if (!use_context) {
                    // Idea 2: Unigram frequency prior + embedding unigram bias in spatial mode
                    float uni_bias = LookupUnigramBias(se.surface);
                    if ((is_user_word || is_adaptive_word) && uni_bias < kAdaptiveBiasFloor) {
                        uni_bias = kAdaptiveBiasFloor;
                    }
                    total_score += 0.004f * se.zipf_freq + 0.25f * uni_bias;
                } else {
                    // Idea 2: Mild Zipf rank prior in context mode (exact_biases_ already in ScoreWordInContext)
                    total_score += 0.0015f * se.zipf_freq;
                    if (!context_state.empty()) {
                        float cs = ScoreWordInContext(context_state, se.surface);
                        // Idea 4: Confidence-gated ContextLM length bonus protection
                        float tot_gap = best_common_tot - tot;
                        if (best_common_tot > -0.55f && tot_gap > 1.7f && cs < 1.0f) {
                            total_score -= 0.80f * (tot_gap - 1.7f);
                        }
                        total_score += alpha * cs;
                    }
                }

                if (is_user_word) {
                    if (base >= max_base - 6.5f) {
                        float boost = (u_it->second >= 4.0f) ? 5.5f : ((u_it->second >= 2.5f) ? 3.8f : 2.0f);
                        total_score += boost;
                    }
                } else if (is_adaptive_word) {
                    if (base >= max_base - kAdaptiveMaxBaseGap) {
                        total_score += kAdaptiveBoost;
                    }
                }
                candidates.push_back({se.surface, total_score});
            }
        }

        // Idea A + E: Confidence-gated Pass 2 over Compact CSR Dual-Trie (68k+ OOV words)
        if (!ext_nodes_.empty() && best_base_tot < kExtPass2GateBaseTot) {
            if (ext_stamp_gen_ > std::numeric_limits<int32_t>::max() - 128) {
                std::fill(ext_stamp_.begin(), ext_stamp_.end(), 0);
                ext_stamp_gen_ = 0;
            }

            std::vector<int32_t> cur_ids;
            std::vector<int32_t> next_ids;
            std::vector<PrefixMass> cur_masses;
            std::vector<std::pair<int32_t, float>> ext_prios;
            cur_ids.reserve(kExtPass2BeamWidth);
            next_ids.reserve(256);
            cur_masses.reserve(kExtPass2BeamWidth);
            ext_prios.reserve(256);

            const int32_t init_stamp = ++ext_stamp_gen_;
            ext_stamp_[0] = init_stamp;
            ext_mass_[0].blank = 0.0f;
            ext_mass_[0].letter = -std::numeric_limits<float>::infinity();
            cur_ids.push_back(0);

            for (int t = 0; t < FUTO_SWIPE_TIME_STEPS; ++t) {
                const float* row = &refined_logits[t * FUTO_SWIPE_CLASS_COUNT];
                const int blank = FUTO_SWIPE_CLASS_COUNT - 1;
                const int32_t next_stamp = ++ext_stamp_gen_;
                next_ids.clear();

                cur_masses.resize(cur_ids.size());
                for (size_t i = 0; i < cur_ids.size(); ++i) {
                    cur_masses[i] = ext_mass_[cur_ids[i]];
                }

                auto touch_next = [&](int32_t node_id) {
                    if (ext_stamp_[node_id] != next_stamp) {
                        ext_stamp_[node_id] = next_stamp;
                        ext_mass_[node_id].blank = -std::numeric_limits<float>::infinity();
                        ext_mass_[node_id].letter = -std::numeric_limits<float>::infinity();
                        next_ids.push_back(node_id);
                    }
                };

                for (size_t i = 0; i < cur_ids.size(); ++i) {
                    const int32_t node_idx = cur_ids[i];
                    const PrefixMass& old = cur_masses[i];
                    const float old_total = old.Total();
                    const ExtTrieNode& node = ext_nodes_[node_idx];

                    touch_next(node_idx);
                    ext_mass_[node_idx].blank = LogAdd(ext_mass_[node_idx].blank, old_total + row[blank]);

                    if (node.last >= 0) {
                        ext_mass_[node_idx].letter = LogAdd(
                            ext_mass_[node_idx].letter,
                            old.letter + row[static_cast<int>(node.last)]
                        );
                    }

                    for (uint32_t e = node.edge_start; e < node.edge_end; ++e) {
                        const int c = static_cast<int>(ext_edge_char_[e]);
                        const int32_t child_idx = ext_edge_child_[e];
                        touch_next(child_idx);
                        const float prev = (c == static_cast<int>(node.last)) ? old.blank : old_total;
                        ext_mass_[child_idx].letter = LogAdd(ext_mass_[child_idx].letter, prev + row[c]);
                    }
                }

                ext_prios.clear();
                for (int32_t nid : next_ids) {
                    const int l = static_cast<int>(ext_nodes_[nid].depth);
                    const float div = divisors[std::min(l, FUTO_SWIPE_TIME_STEPS)];
                    const float prio = ext_mass_[nid].Total() / div + beta_prune * static_cast<float>(l);
                    ext_prios.emplace_back(nid, prio);
                }

                if (ext_prios.size() > static_cast<size_t>(kExtPass2BeamWidth)) {
                    std::partial_sort(
                        ext_prios.begin(),
                        ext_prios.begin() + kExtPass2BeamWidth,
                        ext_prios.end(),
                        [](const auto& a, const auto& b) { return a.second > b.second; }
                    );
                    ext_prios.resize(kExtPass2BeamWidth);
                }

                cur_ids.clear();
                for (const auto& kv : ext_prios) {
                    cur_ids.push_back(kv.first);
                }
            }

            const float oov_penalty = use_context ? kExtOovPenaltyContext : kExtOovPenaltySpatial;
            for (int32_t nid : cur_ids) {
                const ExtTrieNode& node = ext_nodes_[nid];
                if (node.entry_idx < 0 || node.depth < kExtMinWordLen) continue;
                if (node.first < 0 || node.last < 0) continue;

                const float l = static_cast<float>(node.depth);
                const float tot = ext_mass_[nid].Total();

                const int c0 = static_cast<int>(node.first);
                const int c1 = static_cast<int>(node.last);
                const float dx0 = px0 - key_centers_[c0][0];
                const float dy0 = py0 - key_centers_[c0][1];
                const float d0_sq = dx0 * dx0 + dy0 * dy0;
                const float dx1 = px1 - key_centers_[c1][0];
                const float dy1 = py1 - key_centers_[c1][1];
                const float d1_sq = dx1 * dx1 + dy1 * dy1;

                const float base = tot / std::pow(l, gamma) + beta * l - 2.0f * d0_sq - 3.5f * d1_sq;
                const ExtEntry& ee = ext_entries_[node.entry_idx];
                const float total_score = base + kExtLogFreqWeight * ee.log_freq - oov_penalty;
                candidates.push_back({ee.surface, total_score});
            }
        }

        std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) {
            return a.score > b.score;
        });

        // Deduplicate lowercase
        std::vector<SwipeCandidate> deduped;
        std::unordered_map<std::string, bool> seen;
        for (const auto& cand : candidates) {
            std::string lower = cand.word;
            std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
            if (!seen[lower]) {
                seen[lower] = true;
                deduped.push_back(cand);
                if (deduped.size() >= static_cast<size_t>(max_candidates)) break;
            }
        }
        return deduped;
    }

private:
    void RebuildActiveTrieLocked() {
        if (base_trie_nodes_.empty()) return;
        trie_nodes_ = base_trie_nodes_;
        user_word_priorities_.clear();
        adaptive_word_surfaces_.clear();

        // 1. Adaptive LRU Cache entries (Idea C: mild boost, NO has_user_subtree propagation)
        std::unordered_set<std::string> seen_adaptive_keys;
        seen_adaptive_keys.reserve(cached_adaptive_words_.size() + 16);
        for (const auto& surf : cached_adaptive_words_) {
            std::string key = NormalizeSwipeTrieKey(surf);
            if (key.size() < static_cast<size_t>(kExtMinWordLen) ||
                key.size() > static_cast<size_t>(kExtMaxWordLen)) {
                continue;
            }
            if (base_lowercase_set_.find(key) != base_lowercase_set_.end()) {
                continue;
            }
            if (!seen_adaptive_keys.insert(key).second) {
                continue;
            }

            int curr = 0;
            for (char ch : key) {
                int idx = ch - 'a';
                if (trie_nodes_[curr].children[idx] < 0) {
                    int next_idx = static_cast<int>(trie_nodes_.size());
                    trie_nodes_[curr].children[idx] = next_idx;
                    trie_nodes_.emplace_back(trie_nodes_[curr].text + ch, idx);
                }
                curr = trie_nodes_[curr].children[idx];
            }

            bool has_apos = (surf.find('\'') != std::string::npos);
            auto it = std::find_if(
                trie_nodes_[curr].surfaces.begin(),
                trie_nodes_[curr].surfaces.end(),
                [&](const SurfaceEntry& se) { return se.surface == surf; }
            );
            if (it == trie_nodes_[curr].surfaces.end()) {
                trie_nodes_[curr].surfaces.push_back(SurfaceEntry{surf, 35000, 0.0f, has_apos});
            }
            if (!has_apos) {
                trie_nodes_[curr].has_plain_surface = true;
            }
            adaptive_word_surfaces_.insert(surf);
        }

        // 2. Explicit custom user dictionary entries (full priority boost & subtree protection)
        for (const auto& uw : cached_user_words_) {
            std::string key = uw.surface;
            std::transform(key.begin(), key.end(), key.begin(), ::tolower);
            key.erase(std::remove_if(key.begin(), key.end(), [](char c) {
                return c < 'a' || c > 'z';
            }), key.end());

            if (key.length() < 2) continue;

            user_word_priorities_[uw.surface] = uw.priority_score;
            adaptive_word_surfaces_.erase(uw.surface);

            int curr = 0;
            trie_nodes_[curr].has_user_subtree = true;
            for (char ch : key) {
                int idx = ch - 'a';
                if (trie_nodes_[curr].children[idx] < 0) {
                    int next_idx = static_cast<int>(trie_nodes_.size());
                    trie_nodes_[curr].children[idx] = next_idx;
                    trie_nodes_.emplace_back(trie_nodes_[curr].text + ch, idx);
                }
                curr = trie_nodes_[curr].children[idx];
                trie_nodes_[curr].has_user_subtree = true;
            }

            bool has_apos = (uw.surface.find('\'') != std::string::npos);
            auto it = std::find_if(
                trie_nodes_[curr].surfaces.begin(),
                trie_nodes_[curr].surfaces.end(),
                [&](const SurfaceEntry& se) { return se.surface == uw.surface; }
            );
            if (it != trie_nodes_[curr].surfaces.end()) {
                trie_nodes_[curr].surfaces.erase(it);
            }
            trie_nodes_[curr].surfaces.insert(
                trie_nodes_[curr].surfaces.begin(),
                SurfaceEntry{uw.surface, 0, 255.0f, has_apos}
            );
            if (!has_apos) {
                trie_nodes_[curr].has_plain_surface = true;
            }
            trie_nodes_[curr].max_user_priority = std::max(trie_nodes_[curr].max_user_priority, uw.priority_score);
        }

        LOGI("Rebuilt active swipe Trie: user=%zu, adaptive=%zu, nodes=%zu",
             cached_user_words_.size(), adaptive_word_surfaces_.size(), trie_nodes_.size());
    }

    void AddWordToTrie(const std::string& surface, int vocab_rank) {
        std::string key = surface;
        std::transform(key.begin(), key.end(), key.begin(), ::tolower);
        key.erase(std::remove(key.begin(), key.end(), '\''), key.end());
        if (key.empty()) return;

        int curr = 0;
        for (char ch : key) {
            if (ch < 'a' || ch > 'z') return;
            int idx = ch - 'a';
            if (trie_nodes_[curr].children[idx] < 0) {
                int next_idx = static_cast<int>(trie_nodes_.size());
                trie_nodes_[curr].children[idx] = next_idx;
                trie_nodes_.emplace_back(trie_nodes_[curr].text + ch, idx);
            }
            curr = trie_nodes_[curr].children[idx];
        }

        base_lowercase_set_.insert(key);
        float zipf_freq = std::max(
            0.0f,
            static_cast<float>(255.0 * (1.0 - std::log(static_cast<double>(vocab_rank) + 1.0) / std::log(32768.0)))
        );
        bool has_apos = (surface.find('\'') != std::string::npos);
        trie_nodes_[curr].surfaces.push_back(SurfaceEntry{surface, vocab_rank, zipf_freq, has_apos});
        if (!has_apos) {
            trie_nodes_[curr].has_plain_surface = true;
        }
    }

    void InitQwertyLayout() {
        const char* rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        const float starts[] = {0.05f, 0.10f, 0.20f};

        for (int r = 0; r < 3; ++r) {
            int len = std::strlen(rows[r]);
            for (int c = 0; c < len; ++c) {
                int ch_idx = rows[r][c] - 'a';
                key_centers_[ch_idx][0] = starts[r] + c * 0.1f;
                key_centers_[ch_idx][1] = (r + 0.5f) / 3.0f;
            }
        }

        layout_keys_.assign(FUTO_SWIPE_MAX_KEYS * 2, 0.0f);
        layout_mask_.assign(FUTO_SWIPE_MAX_KEYS, false);

        for (int i = 0; i < 26; ++i) {
            layout_keys_[i * 2] = key_centers_[i][0];
            layout_keys_[i * 2 + 1] = key_centers_[i][1];
            layout_mask_[i] = true;
        }
    }

    int LookupTokenId(const std::string& word) const {
        auto it = exact_ids_.find(word);
        if (it != exact_ids_.end()) return it->second;
        std::string lower = word;
        std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
        auto lit = lowercase_ids_.find(lower);
        if (lit != lowercase_ids_.end()) return lit->second;
        return -1;
    }

    float LookupUnigramBias(const std::string& word) const {
        int row = LookupTokenId(word);
        if (row >= 0 && static_cast<size_t>(row) < exact_biases_.size()) {
            return exact_biases_[row];
        }
        auto [b0, b1] = FutoContextBuckets(word);
        return hashed_biases_[b0] + hashed_biases_[b1];
    }

    std::vector<float> ComputeContextState(const std::vector<std::string>& words) {
        std::vector<int64_t> token_ids(FUTO_CONTEXT_LENGTH, 0);
        std::vector<int64_t> hash_buckets(FUTO_CONTEXT_LENGTH * 2, 0);

        size_t start = (words.size() > FUTO_CONTEXT_LENGTH) ? words.size() - FUTO_CONTEXT_LENGTH : 0;
        size_t n_tokens = words.size() - start;

        for (size_t i = 0; i < n_tokens; ++i) {
            const std::string& w = words[start + i];
            int tok_id = LookupTokenId(w);
            if (tok_id >= 0) {
                token_ids[i] = tok_id;
            } else {
                token_ids[i] = FUTO_CONTEXT_HASHED_TOKEN_ID;
                auto [b0, b1] = FutoContextBuckets(w);
                hash_buckets[i * 2] = b0;
                hash_buckets[i * 2 + 1] = b1;
            }
        }

        auto memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        int64_t shape_ids[2] = {1, FUTO_CONTEXT_LENGTH};
        int64_t shape_buckets[3] = {1, FUTO_CONTEXT_LENGTH, 2};

        Ort::Value tensor_ids = Ort::Value::CreateTensor<int64_t>(
            memory_info, token_ids.data(), token_ids.size(), shape_ids, 2
        );
        Ort::Value tensor_buckets = Ort::Value::CreateTensor<int64_t>(
            memory_info, hash_buckets.data(), hash_buckets.size(), shape_buckets, 3
        );

        const char* clm_in_names[] = {"token_ids", "hash_buckets"};
        const char* clm_out_names[] = {"context_states"};
        Ort::Value clm_inputs[] = {std::move(tensor_ids), std::move(tensor_buckets)};

        auto clm_outs = clm_sess_->Run(
            Ort::RunOptions{nullptr}, clm_in_names, clm_inputs, 2, clm_out_names, 1
        );

        const float* states_data = clm_outs[0].GetTensorData<float>(); // [1, 16, 16]
        size_t offset = (n_tokens > 0) ? (n_tokens - 1) * FUTO_CONTEXT_DIM : 0;

        return std::vector<float>(states_data + offset, states_data + offset + FUTO_CONTEXT_DIM);
    }

    float ScoreWordInContext(const std::vector<float>& state, const std::string& word) {
        int row = LookupTokenId(word);
        if (row >= 0) {
            float score = exact_biases_[row];
            const float* emb = &exact_embeddings_[row * FUTO_CONTEXT_DIM];
            for (int d = 0; d < FUTO_CONTEXT_DIM; ++d) {
                score += state[d] * emb[d];
            }
            return score;
        } else {
            auto u_it = user_word_priorities_.find(word);
            bool is_user_word = (u_it != user_word_priorities_.end()) ||
                                (adaptive_word_surfaces_.find(word) != adaptive_word_surfaces_.end());
            auto [b0, b1] = FutoContextBuckets(word);
            int64_t buckets[2] = {b0, b1};
            float score = 0.0f;
            for (int64_t b : buckets) {
                float bias = hashed_biases_[b];
                if (is_user_word && bias < -4.2f) {
                    bias = -4.2f;
                }
                score += bias;
                const float* emb = &hashed_embeddings_[b * FUTO_CONTEXT_DIM];
                for (int d = 0; d < FUTO_CONTEXT_DIM; ++d) {
                    score += state[d] * emb[d];
                }
            }
            return score;
        }
    }

    Ort::Env env_;
    std::unique_ptr<Ort::Session> enc_sess_;
    std::unique_ptr<Ort::Session> dec_sess_;
    std::unique_ptr<Ort::Session> clm_sess_;
    std::unique_ptr<Ort::Session> emb_sess_;

    std::vector<const char*> emb_output_names_{
        "exact_embeddings", "exact_biases", "hashed_embeddings", "hashed_biases"
    };

    std::vector<float> exact_embeddings_;
    std::vector<float> exact_biases_;
    std::vector<float> hashed_embeddings_;
    std::vector<float> hashed_biases_;

    std::vector<std::string> vocabulary_;
    std::unordered_map<std::string, int> exact_ids_;
    std::unordered_map<std::string, int> lowercase_ids_;

    std::vector<TrieNode> trie_nodes_;
    std::vector<TrieNode> base_trie_nodes_;
    std::unordered_set<std::string> base_lowercase_set_;
    std::vector<UserSwipeWord> cached_user_words_;
    std::vector<std::string> cached_adaptive_words_;
    std::unordered_map<std::string, float> user_word_priorities_;
    std::unordered_set<std::string> adaptive_word_surfaces_;

    // Idea A Compact CSR Extended Lexicon & Flat Search Scratch Buffers
    std::vector<ExtTrieNode> ext_nodes_;
    std::vector<int32_t> ext_edge_child_;
    std::vector<uint8_t> ext_edge_char_;
    std::vector<ExtEntry> ext_entries_;
    std::vector<int32_t> ext_stamp_;
    std::vector<PrefixMass> ext_mass_;
    int32_t ext_stamp_gen_ = 0;

    mutable std::mutex mutex_;
    float key_centers_[26][2] = {};
    std::vector<float> layout_keys_;
    std::vector<uint8_t> layout_mask_;

    bool ready_ = false;
};

FutoSwipeEngine::FutoSwipeEngine() : impl_(std::make_unique<Impl>()) {}
FutoSwipeEngine::~FutoSwipeEngine() = default;

bool FutoSwipeEngine::Initialize(const std::string& model_dir) {
    return impl_->Initialize(model_dir);
}

bool FutoSwipeEngine::IsReady() const {
    return impl_->IsReady();
}

bool FutoSwipeEngine::LoadExtendedLexicon(const std::string& tsv_path) {
    return impl_->LoadExtendedLexicon(tsv_path);
}

size_t FutoSwipeEngine::ExtendedWordCount() const {
    return impl_->ExtendedWordCount();
}

bool FutoSwipeEngine::ContainsBaseWord(const std::string& lower_word) const {
    return impl_->ContainsBaseWord(lower_word);
}

void FutoSwipeEngine::SyncUserDictionary(const std::vector<UserSwipeWord>& user_words) {
    impl_->SyncUserDictionary(user_words);
}

void FutoSwipeEngine::SyncAdaptiveCache(const std::vector<std::string>& adaptive_words) {
    impl_->SyncAdaptiveCache(adaptive_words);
}

void FutoSwipeEngine::SetLayoutKeys(const std::vector<float>& key_centers_xy) {
    impl_->SetLayoutKeys(key_centers_xy);
}

std::vector<SwipeCandidate> FutoSwipeEngine::Decode(
    const std::vector<SwipePoint>& trajectory,
    const std::vector<std::string>& previous_words,
    int beam_width,
    int max_candidates
) {
    return impl_->Decode(trajectory, previous_words, beam_width, max_candidates);
}

} // namespace swipe
} // namespace myasr
