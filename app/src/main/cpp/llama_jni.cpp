#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <mutex>
#include <cstring>

#include "llama.h"

#define LOG_TAG "GliaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static std::mutex g_mutex;
static std::atomic<bool> g_loaded{false};
static std::atomic<bool> g_generating{false};
static std::atomic<bool> g_stop{false};

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static const llama_vocab* g_vocab = nullptr;
static llama_sampler* g_sampler = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static std::vector<llama_token> tokenize(const std::string& text, bool add_bos) {
    int n = llama_tokenize(g_vocab, text.c_str(), (int)text.size(), nullptr, 0, add_bos, true);
    std::vector<llama_token> toks(n < 0 ? -n : n);
    if (n < 0) {
        llama_tokenize(g_vocab, text.c_str(), (int)text.size(), toks.data(), (int)toks.size(), add_bos, true);
    } else {
        toks.resize(n);
        llama_tokenize(g_vocab, text.c_str(), (int)text.size(), toks.data(), (int)toks.size(), add_bos, true);
    }
    return toks;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_glia_assistant_llm_LlamaBridge_nativeLoadModel(JNIEnv* env, jobject thiz,
                                                       jstring modelPath, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("nativeLoadModel: %s (ctx=%d, threads=%d)", path, nCtx, nThreads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only (Helio G96)
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_model) {
        LOGE("Falha ao carregar modelo");
        return -1;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? nCtx : 2048;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Falha ao criar contexto");
        llama_model_free(g_model); g_model = nullptr;
        return -2;
    }

    g_vocab = llama_model_get_vocab(g_model);

    // sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    g_loaded.store(true);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_glia_assistant_llm_LlamaBridge_nativeUnloadModel(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop.store(true);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_loaded.store(false);
    LOGI("nativeUnloadModel");
}

JNIEXPORT jboolean JNICALL
Java_com_glia_assistant_llm_LlamaBridge_nativeIsLoaded(JNIEnv* env, jobject thiz) {
    return g_loaded.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_glia_assistant_llm_LlamaBridge_nativeGenerate(JNIEnv* env, jobject thiz,
                                                      jstring prompt, jobject callback, jfloat temperature) {
    if (!g_loaded.load() || !g_ctx || !g_model || !g_vocab) {
        LOGE("Modelo nao carregado");
        return;
    }
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(p);
    env->ReleaseStringUTFChars(prompt, p);

    g_generating.store(true);
    g_stop.store(false);

    jobject cbGlobal = env->NewGlobalRef(callback);
    jclass cbClass = env->GetObjectClass(cbGlobal);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onDone = env->GetMethodID(cbClass, "onDone", "()V");

    // atualiza temperatura no sampler (recria chain simples)
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    {
        llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
        g_sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temperature > 0 ? temperature : 0.7f));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(0.0f, 1));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(g_vocab), 64, 1.0f, 0.0f, 1.5f));
        llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // tokeniza prompt
    std::vector<llama_token> tokens = tokenize(promptStr, true);

    // decode prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Falha no decode do prompt");
    }

    const int max_new = 512;
    int tokenCount = 0;
    for (int i = 0; i < max_new; i++) {
        if (g_stop.load()) break;
        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, new_token)) { LOGI("EOG at %d", i); break; }

        // converte token em texto
        char buf[256];
        int n = llama_token_to_piece(g_vocab, new_token, buf, sizeof(buf), 0, true);
        std::string piece;
        if (n > 0) piece.assign(buf, n);
        else if (n < 0) {
            std::vector<char> big(-n);
            llama_token_to_piece(g_vocab, new_token, big.data(), (int)big.size(), 0, true);
            piece.assign(big.data(), big.size());
        }

        tokenCount++;
        // callback onToken
        {
            JNIEnv* e = nullptr;
            bool attached = (g_vm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK);
            if (attached) g_vm->AttachCurrentThread(&e, nullptr);
            if (e) {
                jstring js = e->NewStringUTF(piece.c_str());
                e->CallVoidMethod(cbGlobal, onToken, js);
                e->DeleteLocalRef(js);
                if (e->ExceptionCheck()) e->ExceptionClear();
            }
            if (attached) g_vm->DetachCurrentThread();
        }

        // decode novo token
        llama_batch b = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, b) != 0) {
            LOGE("Falha no decode do token");
            break;
        }
    }

    // callback onDone
    {
        JNIEnv* e = nullptr;
        bool attached = (g_vm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK);
        if (attached) g_vm->AttachCurrentThread(&e, nullptr);
        if (e) {
            e->CallVoidMethod(cbGlobal, onDone);
            if (e->ExceptionCheck()) e->ExceptionClear();
        }
        if (attached) g_vm->DetachCurrentThread();
    }

    LOGI("generate done, tokens=%d", tokenCount);
    env->DeleteGlobalRef(cbGlobal);
    g_generating.store(false);
}

JNIEXPORT void JNICALL
Java_com_glia_assistant_llm_LlamaBridge_nativeStopGeneration(JNIEnv* env, jobject thiz) {
    g_stop.store(true);
}

JNIEXPORT jboolean JNICALL
Java_com_glia_assistant_llm_LlamaBridge_nativeIsGenerating(JNIEnv* env, jobject thiz) {
    return g_generating.load() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"