#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global context holder
static whisper_context* g_context = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_inboxiq_app_voice_WhisperLib_initContext(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU only for maximum compatibility

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_inboxiq_app_voice_WhisperLib_freeContext(
    JNIEnv* env,
    jobject /* this */,
    jlong contextPtr) {

    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Context freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_inboxiq_app_voice_WhisperLib_transcribeAudio(
    JNIEnv* env,
    jobject /* this */,
    jlong contextPtr,
    jfloatArray audioData,
    jstring language,
    jobject progressCallback) {

    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) {
        LOGE("Context is null");
        return env->NewStringUTF("");
    }

    // Get audio data
    jsize audioLen = env->GetArrayLength(audioData);
    jfloat* audioPtr = env->GetFloatArrayElements(audioData, nullptr);

    LOGI("Transcribing %d samples", audioLen);

    // Get language
    const char* lang = env->GetStringUTFChars(language, nullptr);
    LOGI("Transcribing with language: %s", lang);

    // Configure whisper parameters
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = std::min(4, (int)std::thread::hardware_concurrency());
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;

    // Progress callback
    jclass callbackClass = nullptr;
    jmethodID onProgressMethod = nullptr;

    if (progressCallback != nullptr) {
        callbackClass = env->GetObjectClass(progressCallback);
        onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(I)V");
    }

    struct CallbackData {
        JNIEnv* env;
        jobject callback;
        jmethodID method;
    };

    CallbackData cbData = { env, progressCallback, onProgressMethod };

    params.progress_callback_user_data = &cbData;
    params.progress_callback = [](struct whisper_context* /*ctx*/,
                                   struct whisper_state* /*state*/,
                                   int progress,
                                   void* user_data) {
        auto* data = static_cast<CallbackData*>(user_data);
        if (data->callback != nullptr && data->method != nullptr) {
            data->env->CallVoidMethod(data->callback, data->method, progress);
        }
    };

    // Run transcription
    int result = whisper_full(ctx, params, audioPtr, audioLen);

    env->ReleaseFloatArrayElements(audioData, audioPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("Transcription failed with code: %d", result);
        return env->NewStringUTF("");
    }

    // Build result JSON with segments
    std::string jsonResult = "[";
    int numSegments = whisper_full_n_segments(ctx);

    for (int i = 0; i < numSegments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(ctx, i);

        // Convert to milliseconds
        double startMs = t0 * 10.0;
        double endMs = t1 * 10.0;

        if (i > 0) jsonResult += ",";

        // Escape text for JSON
        std::string escapedText;
        for (const char* p = text; *p; p++) {
            switch (*p) {
                case '"': escapedText += "\\\""; break;
                case '\\': escapedText += "\\\\"; break;
                case '\n': escapedText += "\\n"; break;
                case '\r': escapedText += "\\r"; break;
                case '\t': escapedText += "\\t"; break;
                default: escapedText += *p;
            }
        }

        jsonResult += "{";
        jsonResult += "\"text\":\"" + escapedText + "\",";
        jsonResult += "\"start\":" + std::to_string(startMs) + ",";
        jsonResult += "\"end\":" + std::to_string(endMs);
        jsonResult += "}";
    }

    jsonResult += "]";

    LOGI("Transcription complete: %d segments", numSegments);
    return env->NewStringUTF(jsonResult.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_inboxiq_app_voice_WhisperLib_getSystemInfo(
    JNIEnv* env,
    jobject /* this */) {

    const char* sysInfo = whisper_print_system_info();
    return env->NewStringUTF(sysInfo);
}

JNIEXPORT jboolean JNICALL
Java_com_inboxiq_app_voice_WhisperLib_isMultilingual(
    JNIEnv* env,
    jobject /* this */,
    jlong contextPtr) {

    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) return JNI_FALSE;

    return whisper_is_multilingual(ctx) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
