#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llm_reranker.h"

#define TAG "MyAsrLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string SanitizeUtf8(const std::string& str) {
  if (str.empty()) return "";
  size_t len = str.length();
  size_t i = 0;
  size_t last_valid_end = 0;

  while (i < len) {
    unsigned char c = static_cast<unsigned char>(str[i]);
    size_t seq_len = 0;
    if (c <= 0x7F) {
      seq_len = 1;
    } else if ((c & 0xE0) == 0xC0) {
      seq_len = 2;
    } else if ((c & 0xF0) == 0xE0) {
      seq_len = 3;
    } else if ((c & 0xF8) == 0xF0) {
      seq_len = 4;
    } else {
      break;
    }

    if (i + seq_len > len) {
      // Incomplete multibyte sequence at end of string
      break;
    }

    bool valid_continuation = true;
    for (size_t k = 1; k < seq_len; ++k) {
      unsigned char cont = static_cast<unsigned char>(str[i + k]);
      if ((cont & 0xC0) != 0x80) {
        valid_continuation = false;
        break;
      }
    }

    if (!valid_continuation) {
      break;
    }

    i += seq_len;
    last_valid_end = i;
  }

  return str.substr(0, last_valid_end);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_LlmReranker_nativeInit(JNIEnv* env, jobject /* thiz */,
                                              jstring jModelPath, jint nThreads) {
  if (jModelPath == nullptr) {
    LOGE("nativeInit: modelPath is null");
    return JNI_FALSE;
  }

  const char* model_path_str = env->GetStringUTFChars(jModelPath, nullptr);
  std::string model_path(model_path_str);
  env->ReleaseStringUTFChars(jModelPath, model_path_str);

  int threads = (nThreads > 0) ? nThreads : 4;
  bool success = myasr::LlmReranker::GetInstance().Initialize(model_path, threads);
  return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_myasr_LlmReranker_nativeScoreCandidates(JNIEnv* env, jobject /* thiz */,
                                                         jstring jContext,
                                                         jobjectArray jCandidates) {
  if (jCandidates == nullptr) {
    return nullptr;
  }

  std::string context_str = "";
  if (jContext != nullptr) {
    const char* c_str = env->GetStringUTFChars(jContext, nullptr);
    context_str = c_str;
    env->ReleaseStringUTFChars(jContext, c_str);
  }

  jsize num_candidates = env->GetArrayLength(jCandidates);
  std::vector<std::string> candidates;
  candidates.reserve(num_candidates);

  for (jsize i = 0; i < num_candidates; ++i) {
    jstring cand_str = (jstring)env->GetObjectArrayElement(jCandidates, i);
    if (cand_str != nullptr) {
      const char* utf = env->GetStringUTFChars(cand_str, nullptr);
      candidates.emplace_back(utf);
      env->ReleaseStringUTFChars(cand_str, utf);
      env->DeleteLocalRef(cand_str);
    } else {
      candidates.emplace_back("");
    }
  }

  std::vector<float> neg_logprobs;
  bool success = myasr::LlmReranker::GetInstance().ScoreCandidates(context_str, candidates, &neg_logprobs);
  if (!success || neg_logprobs.size() != static_cast<size_t>(num_candidates)) {
    return nullptr;
  }

  jfloatArray result = env->NewFloatArray(num_candidates);
  if (result == nullptr) {
    return nullptr;
  }

  env->SetFloatArrayRegion(result, 0, num_candidates, neg_logprobs.data());
  return result;
}

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_LlmReranker_nativeWarmContext(JNIEnv* env, jobject /* thiz */,
                                                     jstring jContext) {
  std::string context_str = "";
  if (jContext != nullptr) {
    const char* c_str = env->GetStringUTFChars(jContext, nullptr);
    context_str = c_str;
    env->ReleaseStringUTFChars(jContext, c_str);
  }
  bool success = myasr::LlmReranker::GetInstance().WarmContext(context_str);
  return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_myasr_LlmReranker_nativePredictNextPhrases(JNIEnv* env, jobject /* thiz */,
                                                            jstring jContext,
                                                            jint maxCandidates,
                                                            jint maxTokens) {
  std::string context_str = "";
  if (jContext != nullptr) {
    const char* c_str = env->GetStringUTFChars(jContext, nullptr);
    context_str = c_str;
    env->ReleaseStringUTFChars(jContext, c_str);
  }

  int limit_candidates = (maxCandidates > 0) ? maxCandidates : 3;
  int limit_tokens = (maxTokens > 0) ? maxTokens : 5;

  std::vector<std::string> predictions;
  bool success = myasr::LlmReranker::GetInstance().PredictNextPhrases(
      context_str, limit_candidates, limit_tokens, &predictions);

  if (!success || predictions.empty()) {
    return nullptr;
  }

  std::vector<std::string> valid_predictions;
  valid_predictions.reserve(predictions.size());
  for (const auto& p : predictions) {
    std::string safe = SanitizeUtf8(p);
    if (!safe.empty()) {
      valid_predictions.push_back(std::move(safe));
    }
  }

  if (valid_predictions.empty()) {
    return nullptr;
  }

  jclass string_cls = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(valid_predictions.size(), string_cls, nullptr);
  for (size_t i = 0; i < valid_predictions.size(); ++i) {
    jstring s = env->NewStringUTF(valid_predictions[i].c_str());
    env->SetObjectArrayElement(result, i, s);
    env->DeleteLocalRef(s);
  }

  return result;
}

JNIEXPORT void JNICALL
Java_com_example_myasr_LlmReranker_nativeRelease(JNIEnv* /* env */, jobject /* thiz */) {
  myasr::LlmReranker::GetInstance().Release();
}

JNIEXPORT jboolean JNICALL
Java_com_example_myasr_LlmReranker_nativeIsReady(JNIEnv* /* env */, jobject /* thiz */) {
  return myasr::LlmReranker::GetInstance().IsReady() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
