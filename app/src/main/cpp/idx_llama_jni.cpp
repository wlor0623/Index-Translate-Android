// Index-Translate-Android JNI 封装
//
// 提供:模型加载 / 单轮流式生成 / 中断 / 释放。
// 提示词由 Kotlin 侧按 Qwen3.5 chat 格式(思考默认关闭)拼好直接送入。

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "idxtranslate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

void android_log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("%s", text);
    } else {
        LOGI("%s", text);
    }
}

// 返回 s 末尾完整 UTF-8 前缀的长度(不完整的尾部字节留待下个 token)
size_t utf8_complete_len(const std::string & s) {
    const size_t n = s.size();
    const size_t back = n < 3 ? n : 3;
    for (size_t k = 0; k < back; ++k) {
        const unsigned char c = (unsigned char) s[n - 1 - k];
        if ((c & 0x80) == 0x00) {
            return n - k;                       // ASCII 边界
        }
        if ((c & 0xC0) == 0xC0) {               // 首字节
            const int need = (c & 0x20) ? ((c & 0x10) ? 4 : 3) : 2;
            return (need > k + 1) ? (n - k - 1) : n;
        }
    }
    return n - back;
}

struct IdxHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> stop{false};
    std::mutex gen_mtx; // 同时只允许一个生成
};

IdxHandle * as_handle(jlong ptr) {
    return reinterpret_cast<IdxHandle *>(ptr);
}

// 完成码:0=自然结束 1=用户中断 2=达到 max_tokens;负数=错误
constexpr jint FINISH_EOG = 0;
constexpr jint FINISH_STOPPED = 1;
constexpr jint FINISH_MAX_TOKENS = 2;

} // namespace

extern "C" {

// ---- 初始化(进程内一次):注册日志 + 从 nativeLibraryDir 加载后端变体 ----

JNIEXPORT void JNICALL
Java_com_index_translate_LlamaEngine_nativeInit(JNIEnv * env, jobject, jstring jNativeLibDir) {
    llama_log_set(android_log_callback, nullptr);

    const char * dir = env->GetStringUTFChars(jNativeLibDir, nullptr);
    LOGI("loading ggml backends from %s", dir);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(jNativeLibDir, dir);

    llama_backend_init();
    LOGI("llama backend initialized");
}

// ---- 模型加载:成功返回句柄,失败返回 0 ----

JNIEXPORT jlong JNICALL
Java_com_index_translate_LlamaEngine_nativeLoadModel(
        JNIEnv * env, jobject, jstring jPath, jint nCtx, jint nThreads, jint nBatch) {
    const char * path = env->GetStringUTFChars(jPath, nullptr);
    LOGI("loading model: %s (ctx=%d threads=%d batch=%d)", path, nCtx, nThreads, nBatch);

    llama_model_params mparams = llama_model_default_params();
    // n_gpu_layers 在 CPU-only 构建下无意义,保持默认
    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jPath, path);
    if (!model) {
        LOGE("llama_model_load_from_file failed");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = (uint32_t) nBatch;
    cparams.n_ubatch = (uint32_t) nBatch;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto * h = new IdxHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    LOGI("model loaded");
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_index_translate_LlamaEngine_nativeFreeModel(JNIEnv *, jobject, jlong ptr) {
    auto * h = as_handle(ptr);
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->gen_mtx);
    h->stop = true;
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

JNIEXPORT void JNICALL
Java_com_index_translate_LlamaEngine_nativeStop(JNIEnv *, jobject, jlong ptr) {
    auto * h = as_handle(ptr);
    if (h) h->stop = true;
}

/**
 * 同步执行单轮生成,逐 token 回调 Kotlin listener.onToken(byte[])。
 * 返回完成码(FINISH_*)。
 */
JNIEXPORT jint JNICALL
Java_com_index_translate_LlamaEngine_nativeCompletion(
        JNIEnv * env, jobject,
        jlong ptr, jbyteArray jPrompt, jint maxTokens, jfloat temperature, jobject listener) {

    auto * h = as_handle(ptr);
    if (!h || !h->model || !h->ctx) return -1;

    std::lock_guard<std::mutex> lock(h->gen_mtx);
    h->stop = false;

    const jsize promptLen = env->GetArrayLength(jPrompt);
    std::string prompt(promptLen, '\0');
    env->GetByteArrayRegion(jPrompt, 0, promptLen, reinterpret_cast<jbyte *>(prompt.data()));

    // 回调方法
    jclass cbClass = env->GetObjectClass(listener);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)V");
    env->DeleteLocalRef(cbClass);
    if (!onToken) {
        LOGE("listener.onToken([B)V not found");
        return -1;
    }

    // 分词(parse_special=true 以解析 <|im_start|> 等)
    const llama_vocab * vocab = h->vocab;
    const int n_prompt = llama_tokenize(
            vocab, prompt.data(), (int32_t) prompt.size(), nullptr, 0, false, true);
    if (n_prompt < 0) {
        LOGE("tokenization failed: %d", n_prompt);
        return -1;
    }

    const uint32_t n_ctx = llama_n_ctx(h->ctx);
    if ((uint32_t) n_prompt >= n_ctx - 8) {
        LOGE("prompt too long: %d tokens (ctx=%u)", n_prompt, n_ctx);
        return -3;
    }

    std::vector<llama_token> tokens((size_t) n_prompt);
    if (llama_tokenize(vocab, prompt.data(), (int32_t) prompt.size(),
                       tokens.data(), n_prompt, false, true) != n_prompt) {
        LOGE("tokenize mismatch");
        return -1;
    }

    // 每次生成都从干净状态开始(单轮翻译,不做多轮复用)
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // 采样链:temp<=0 贪心;否则 temp+dist
    llama_sampler * smpl = nullptr;
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // 预填(按 n_batch 分块)
    const uint32_t n_batch = llama_n_batch(h->ctx);
    for (uint32_t i = 0; i < (uint32_t) n_prompt; i += n_batch) {
        const uint32_t n_chunk = std::min(n_batch, (uint32_t) n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, (int32_t) n_chunk);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("prefill decode failed at %u", i);
            llama_sampler_free(smpl);
            return -2;
        }
    }

    // 生成
    jint finish = FINISH_MAX_TOKENS;
    int32_t n_generated = 0;
    std::string pending;

    while (!h->stop) {
        const llama_token id = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) {
            finish = FINISH_EOG;
            break;
        }

        char buf[512];
        int32_t n_piece = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n_piece > 0) {
            pending.append(buf, (size_t) n_piece);
            const size_t emit = utf8_complete_len(pending);
            if (emit > 0) {
                jbyteArray arr = env->NewByteArray((jsize) emit);
                env->SetByteArrayRegion(arr, 0, (jsize) emit,
                                        reinterpret_cast<const jbyte *>(pending.data()));
                env->CallVoidMethod(listener, onToken, arr);
                env->DeleteLocalRef(arr);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    finish = -1;
                    break;
                }
                pending.erase(0, emit);
            }
        }

        ++n_generated;
        if (n_generated >= maxTokens) {
            finish = FINISH_MAX_TOKENS;
            break;
        }

        llama_token next_tok = id; // llama_batch_get_one 需要非 const 指针
        llama_batch batch = llama_batch_get_one(&next_tok, 1);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("decode failed after %d tokens", n_generated);
            finish = -2;
            break;
        }
    }

    if (h->stop) finish = FINISH_STOPPED;

    // 尾部残留字节(半字符)兜底发出
    if (!pending.empty() && finish >= 0) {
        jbyteArray arr = env->NewByteArray((jsize) pending.size());
        env->SetByteArrayRegion(arr, 0, (jsize) pending.size(),
                                reinterpret_cast<const jbyte *>(pending.data()));
        env->CallVoidMethod(listener, onToken, arr);
        env->DeleteLocalRef(arr);
    }

    llama_sampler_free(smpl);
    LOGI("completion done: finish=%d generated=%d", finish, n_generated);
    return finish;
}

JNIEXPORT jint JNICALL
Java_com_index_translate_LlamaEngine_nativeNCtx(JNIEnv *, jobject, jlong ptr) {
    auto * h = as_handle(ptr);
    return h && h->ctx ? (jint) llama_n_ctx(h->ctx) : 0;
}

} // extern "C"
