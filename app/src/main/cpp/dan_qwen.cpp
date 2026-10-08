#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <mutex>
#include <string>
#include <vector>
#include <cstring>

static std::mutex engine_lock;
static llama_model * model = nullptr;
static llama_context * context = nullptr;
static bool backend_initialized = false;

static void throw_java(JNIEnv *env, const std::string &text) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, text.c_str());
}

static void unload() {
    if (context) { llama_free(context); context = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dimitriazzarone_chatgptmessenger_DanNativeQwen_nativeLoad(JNIEnv *env, jclass, jstring file) {
    std::lock_guard<std::mutex> lock(engine_lock);
    if (context && model) return JNI_TRUE;
    if (!backend_initialized) { llama_backend_init(); backend_initialized = true; }
    const char *path = env->GetStringUTFChars(file, nullptr);
    if (!path) return JNI_FALSE;
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU-only on Android 12L; deterministic and portable.
    mp.use_mmap = true;
    model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(file, path);
    if (!model) return JNI_FALSE;
    auto cp = llama_context_default_params();
    cp.n_ctx = 4096;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = 2;
    cp.n_threads_batch = 2;
    context = llama_init_from_model(model, cp);
    if (!context) { unload(); return JNI_FALSE; }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dimitriazzarone_chatgptmessenger_DanNativeQwen_nativeGenerate(JNIEnv *env, jclass, jstring input, jint max_tokens) {
    std::lock_guard<std::mutex> lock(engine_lock);
    if (!context || !model) { throw_java(env, "Motore Qwen non caricato"); return nullptr; }
    const char *utf = env->GetStringUTFChars(input, nullptr);
    if (!utf) return nullptr;
    std::string prompt(utf);
    env->ReleaseStringUTFChars(input, utf);

    const llama_vocab * vocab = llama_model_get_vocab(model);
    std::vector<llama_token> tokens(4096);
    const int needed = llama_tokenize(vocab, prompt.data(), (int32_t)prompt.size(),
                                      tokens.data(), (int32_t)tokens.size(), true, true);
    if (needed <= 0 || needed > 4096) {
        throw_java(env, "Prompt troppo lungo o non tokenizzabile"); return nullptr;
    }
    tokens.resize(needed);
    max_tokens = std::max(1, std::min((int)max_tokens, 256));
    if ((int)tokens.size() + max_tokens + 1 > 4096) {
        throw_java(env, "Contesto Qwen esaurito: riduci i messaggi o la memoria"); return nullptr;
    }
    llama_memory_clear(llama_get_memory(context), false);
    llama_batch batch = llama_batch_init(256, 0, 1);
    bool ok = true;
    for (int start = 0; start < (int) tokens.size(); start += 256) {
        const int n = std::min(256, (int)tokens.size() - start);
        batch.n_tokens = n;
        for (int i = 0; i < n; i++) {
            batch.token[i] = tokens[start+i];
            batch.pos[i] = start+i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (start+i == (int)tokens.size()-1) ? 1 : 0;
        }
        if (llama_decode(context, batch) != 0) { ok = false; break; }
    }
    if (!ok) { llama_batch_free(batch); throw_java(env,"Errore decodifica prompt"); return nullptr; }
    llama_sampler * sampler = llama_sampler_init_greedy();
    std::string output;
    for (int i = 0; i < max_tokens; i++) {
        llama_token token = llama_sampler_sample(sampler, context, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) break;
        char bytes[256];
        const int n = llama_token_to_piece(vocab, token, bytes, (int32_t)sizeof(bytes), 0, false);
        if (n > 0 && n < (int)sizeof(bytes)) output.append(bytes, n);
        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = (int)tokens.size() + i;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(context, batch) != 0) { ok = false; break; }
    }
    llama_sampler_free(sampler);
    llama_batch_free(batch);
    if (!ok) { throw_java(env,"Errore generazione risposta"); return nullptr; }
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_dimitriazzarone_chatgptmessenger_DanNativeQwen_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(engine_lock);
    unload();
}
