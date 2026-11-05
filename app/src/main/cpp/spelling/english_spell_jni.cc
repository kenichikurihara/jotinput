#include "english_spell_engine.h"
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOG_TAG "EnglishSpellJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<myasr::spelling::EnglishSpellEngine> g_spell_engine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_EnglishSpellEngine_nativeInitialize(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_dir
) {
    if (!model_dir) return JNI_FALSE;
    const char* path_chars = env->GetStringUTFChars(model_dir, nullptr);
    std::string path(path_chars);
    env->ReleaseStringUTFChars(model_dir, path_chars);

    if (!g_spell_engine) {
        g_spell_engine = std::make_unique<myasr::spelling::EnglishSpellEngine>();
    }

    bool success = g_spell_engine->Initialize(path);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_EnglishSpellEngine_nativeIsReady(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return (g_spell_engine && g_spell_engine->IsReady()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_myasr_EnglishSpellEngine_nativeSyncUserDictionary(
    JNIEnv* env,
    jobject /* thiz */,
    jobjectArray words_jarr,
    jfloatArray priorities_jarr
) {
    if (!g_spell_engine || words_jarr == nullptr || priorities_jarr == nullptr) {
        return;
    }
    jsize len = env->GetArrayLength(words_jarr);
    if (len == 0) {
        g_spell_engine->SyncUserDictionary({});
        return;
    }
    jfloat* prios = env->GetFloatArrayElements(priorities_jarr, nullptr);
    std::vector<myasr::spelling::UserSpellWord> user_words;
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

    g_spell_engine->SyncUserDictionary(user_words);
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_myasr_EnglishSpellEngine_nativeSuggestTypo(
    JNIEnv* env,
    jobject /* thiz */,
    jstring word,
    jint max_candidates
) {
    if (!g_spell_engine || !g_spell_engine->IsReady() || !word) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }

    const char* word_chars = env->GetStringUTFChars(word, nullptr);
    std::string w(word_chars);
    env->ReleaseStringUTFChars(word, word_chars);

    auto suggestions = g_spell_engine->SuggestTypo(w, max_candidates);

    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(suggestions.size(), str_cls, nullptr);
    for (size_t i = 0; i < suggestions.size(); ++i) {
        jstring jstr = env->NewStringUTF(suggestions[i].c_str());
        env->SetObjectArrayElement(result, i, jstr);
        env->DeleteLocalRef(jstr);
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_myasr_EnglishSpellEngine_nativeCorrectSentence(
    JNIEnv* env,
    jobject /* thiz */,
    jstring text,
    jstring lang
) {
    if (!g_spell_engine || !g_spell_engine->IsReady() || !text) {
        return text;
    }

    const char* text_chars = env->GetStringUTFChars(text, nullptr);
    std::string t(text_chars);
    env->ReleaseStringUTFChars(text, text_chars);

    std::string l = "en";
    if (lang) {
        const char* lang_chars = env->GetStringUTFChars(lang, nullptr);
        l = lang_chars;
        env->ReleaseStringUTFChars(lang, lang_chars);
    }

    std::string corrected = g_spell_engine->CorrectSentence(t, l);
    return env->NewStringUTF(corrected.c_str());
}

} // extern "C"
