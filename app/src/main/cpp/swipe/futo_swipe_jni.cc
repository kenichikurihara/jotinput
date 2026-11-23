#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "futo_swipe_engine.h"

#define TAG "FutoSwipeJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static myasr::swipe::FutoSwipeEngine* g_swipe_engine = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeInitialize(
    JNIEnv* env,
    jobject /* this */,
    jstring model_dir_jstr
) {
    if (model_dir_jstr == nullptr) return JNI_FALSE;
    const char* model_dir_cstr = env->GetStringUTFChars(model_dir_jstr, nullptr);
    std::string model_dir(model_dir_cstr);
    env->ReleaseStringUTFChars(model_dir_jstr, model_dir_cstr);

    if (g_swipe_engine == nullptr) {
        g_swipe_engine = new myasr::swipe::FutoSwipeEngine();
    }

    bool success = g_swipe_engine->Initialize(model_dir);
    LOGI("nativeInitialize success: %s", success ? "true" : "false");
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeIsReady(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return (g_swipe_engine != nullptr && g_swipe_engine->IsReady()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeSyncUserDictionary(
    JNIEnv* env,
    jobject /* this */,
    jobjectArray words_jarr,
    jfloatArray priorities_jarr
) {
    if (g_swipe_engine == nullptr || words_jarr == nullptr || priorities_jarr == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(words_jarr);
    if (len == 0) {
        g_swipe_engine->SyncUserDictionary({});
        return;
    }
    jfloat* prios = env->GetFloatArrayElements(priorities_jarr, nullptr);
    std::vector<myasr::swipe::UserSwipeWord> user_words;
    user_words.reserve(len);

    for (jsize i = 0; i < len; ++i) {
        jstring w_jstr = static_cast<jstring>(env->GetObjectArrayElement(words_jarr, i));
        if (w_jstr != nullptr) {
            const char* w_cstr = env->GetStringUTFChars(w_jstr, nullptr);
            user_words.push_back({std::string(w_cstr), prios[i]});
            env->ReleaseStringUTFChars(w_jstr, w_cstr);
            env->DeleteLocalRef(w_jstr);
        }
    }
    env->ReleaseFloatArrayElements(priorities_jarr, prios, JNI_ABORT);

    g_swipe_engine->SyncUserDictionary(user_words);
}

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeLoadExtendedLexicon(
    JNIEnv* env,
    jobject /* this */,
    jstring tsv_path_jstr
) {
    if (g_swipe_engine == nullptr || tsv_path_jstr == nullptr || !g_swipe_engine->IsReady()) {
        return JNI_FALSE;
    }
    const char* path_cstr = env->GetStringUTFChars(tsv_path_jstr, nullptr);
    if (path_cstr == nullptr) return JNI_FALSE;
    std::string path(path_cstr);
    env->ReleaseStringUTFChars(tsv_path_jstr, path_cstr);
    return g_swipe_engine->LoadExtendedLexicon(path) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeSyncAdaptiveCache(
    JNIEnv* env,
    jobject /* this */,
    jobjectArray words_jarr
) {
    if (g_swipe_engine == nullptr || !g_swipe_engine->IsReady()) {
        return;
    }
    if (words_jarr == nullptr) {
        g_swipe_engine->SyncAdaptiveCache({});
        return;
    }
    jsize len = env->GetArrayLength(words_jarr);
    std::vector<std::string> adaptive_words;
    adaptive_words.reserve(len);
    for (jsize i = 0; i < len; ++i) {
        jstring w_jstr = static_cast<jstring>(env->GetObjectArrayElement(words_jarr, i));
        if (w_jstr != nullptr) {
            const char* w_cstr = env->GetStringUTFChars(w_jstr, nullptr);
            if (w_cstr != nullptr) {
                adaptive_words.emplace_back(w_cstr);
                env->ReleaseStringUTFChars(w_jstr, w_cstr);
            }
            env->DeleteLocalRef(w_jstr);
        }
    }
    g_swipe_engine->SyncAdaptiveCache(adaptive_words);
}

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeContainsBaseWord(
    JNIEnv* env,
    jobject /* this */,
    jstring word_jstr
) {
    if (g_swipe_engine == nullptr || word_jstr == nullptr || !g_swipe_engine->IsReady()) {
        return JNI_FALSE;
    }
    const char* w_cstr = env->GetStringUTFChars(word_jstr, nullptr);
    if (w_cstr == nullptr) return JNI_FALSE;
    std::string word(w_cstr);
    env->ReleaseStringUTFChars(word_jstr, w_cstr);
    return g_swipe_engine->ContainsBaseWord(word) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeGetExtendedWordCount(
    JNIEnv* /* env */,
    jobject /* this */
) {
    if (g_swipe_engine == nullptr || !g_swipe_engine->IsReady()) {
        return 0;
    }
    return static_cast<jint>(g_swipe_engine->ExtendedWordCount());
}

JNIEXPORT void JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeSetLayoutKeys(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray key_centers_jarr
) {
    if (g_swipe_engine == nullptr || key_centers_jarr == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(key_centers_jarr);
    if (len < 52) return;
    jfloat* ptr = env->GetFloatArrayElements(key_centers_jarr, nullptr);
    std::vector<float> key_centers(ptr, ptr + len);
    env->ReleaseFloatArrayElements(key_centers_jarr, ptr, JNI_ABORT);
    g_swipe_engine->SetLayoutKeys(key_centers);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeDecode(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray xs_jarr,
    jfloatArray ys_jarr,
    jlongArray ts_jarr,
    jobjectArray prev_words_jarr,
    jint beam_width,
    jint max_candidates
) {
    if (g_swipe_engine == nullptr || !g_swipe_engine->IsReady()) {
        return nullptr;
    }

    jsize len = env->GetArrayLength(xs_jarr);
    if (len < 2) return nullptr;

    jfloat* xs = env->GetFloatArrayElements(xs_jarr, nullptr);
    jfloat* ys = env->GetFloatArrayElements(ys_jarr, nullptr);
    jlong* ts = env->GetLongArrayElements(ts_jarr, nullptr);

    std::vector<myasr::swipe::SwipePoint> trajectory(len);
    for (jsize i = 0; i < len; ++i) {
        trajectory[i] = {xs[i], ys[i], static_cast<int64_t>(ts[i])};
    }

    env->ReleaseFloatArrayElements(xs_jarr, xs, JNI_ABORT);
    env->ReleaseFloatArrayElements(ys_jarr, ys, JNI_ABORT);
    env->ReleaseLongArrayElements(ts_jarr, ts, JNI_ABORT);

    std::vector<std::string> prev_words;
    if (prev_words_jarr != nullptr) {
        jsize n_words = env->GetArrayLength(prev_words_jarr);
        prev_words.reserve(n_words);
        for (jsize i = 0; i < n_words; ++i) {
            jstring w_jstr = static_cast<jstring>(env->GetObjectArrayElement(prev_words_jarr, i));
            if (w_jstr != nullptr) {
                const char* w_cstr = env->GetStringUTFChars(w_jstr, nullptr);
                prev_words.emplace_back(w_cstr);
                env->ReleaseStringUTFChars(w_jstr, w_cstr);
                env->DeleteLocalRef(w_jstr);
            }
        }
    }

    auto cands = g_swipe_engine->Decode(trajectory, prev_words, beam_width, max_candidates);

    jclass string_cls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(cands.size(), string_cls, nullptr);

    for (size_t i = 0; i < cands.size(); ++i) {
        jstring cand_str = env->NewStringUTF(cands[i].word.c_str());
        env->SetObjectArrayElement(result, i, cand_str);
        env->DeleteLocalRef(cand_str);
    }

    return result;
}

JNIEXPORT void JNICALL
Java_com_example_myasr_context_english_infrastructure_FutoSwipeEngine_nativeRelease(
    JNIEnv* /* env */,
    jobject /* this */
) {
    if (g_swipe_engine != nullptr) {
        delete g_swipe_engine;
        g_swipe_engine = nullptr;
    }
}

} // extern "C"
