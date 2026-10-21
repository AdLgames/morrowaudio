#include <jni.h>
#include <whisper.h>
#include <vector>
#include <string>
#include <algorithm>
#include <thread>
// JNI is synchronous on Narrumo's existing background executor. Only this calling
// thread calls Java; ggml worker threads inspect a native atomic cancellation flag.
#include <atomic>
struct Callback { JNIEnv *env; jobject sink; jmethodID segment, progress, cancelled; std::atomic<bool> stop{false}; };
static void progress(whisper_context *, whisper_state *, int value, void *arg) {
    auto *c = static_cast<Callback *>(arg);
    c->env->CallVoidMethod(c->sink, c->progress, value);
    c->stop.store(c->env->ExceptionCheck() || c->env->CallBooleanMethod(c->sink, c->cancelled));
}
static bool aborted(void *arg) { return static_cast<Callback *>(arg)->stop.load(); }
static bool encoder(whisper_context *, whisper_state *, void *arg) {
    auto *c = static_cast<Callback *>(arg);
    c->stop.store(c->env->ExceptionCheck() || c->env->CallBooleanMethod(c->sink,c->cancelled));
    return !c->stop.load();
}
static void segments(whisper_context *ctx, whisper_state *, int count, void *arg) {
    auto *c = static_cast<Callback *>(arg);
    for (int i=whisper_full_n_segments(ctx)-count;i<whisper_full_n_segments(ctx);++i) {
        const char *text=whisper_full_get_segment_text(ctx,i);
        if (whisper_full_get_segment_no_speech_prob(ctx,i) > .7f) continue;
        jstring value=c->env->NewStringUTF(text);
        c->env->CallVoidMethod(c->sink,c->segment,(jlong)(whisper_full_get_segment_t0(ctx,i)*10),(jlong)(whisper_full_get_segment_t1(ctx,i)*10),value);
        c->env->DeleteLocalRef(value);
        if (c->env->ExceptionCheck()) { c->stop.store(true); break; }
    }
}
extern "C" JNIEXPORT jint JNICALL Java_com_morrow_audio_WhisperSpeech_decode(JNIEnv *env,jclass,jstring path,jfloatArray samples,jstring hint,jobject sink) {
    const char *file=env->GetStringUTFChars(path,nullptr), *prompt=env->GetStringUTFChars(hint,nullptr);
    auto cfg=whisper_context_default_params();cfg.use_gpu=false;
    auto *ctx=whisper_init_from_file_with_params(file,cfg);
    env->ReleaseStringUTFChars(path,file);
    if (!ctx) { env->ReleaseStringUTFChars(hint,prompt);return -2; }
    std::vector<float> audio(env->GetArrayLength(samples));env->GetFloatArrayRegion(samples,0,audio.size(),audio.data());
    jclass cls=env->GetObjectClass(sink);
    Callback callback{env,sink,env->GetMethodID(cls,"segment","(JJLjava/lang/String;)V"),env->GetMethodID(cls,"progress","(I)V"),env->GetMethodID(cls,"cancelled","()Z")};
    auto p=whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    p.n_threads=std::min(4u,std::max(1u,std::thread::hardware_concurrency()));
    p.language="en";p.translate=false;p.no_context=true;p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;
    p.initial_prompt=prompt;p.carry_initial_prompt=true;p.beam_search.beam_size=3;
    p.temperature=0;p.temperature_inc=0;p.suppress_nst=true;p.no_speech_thold=.6f;
    p.progress_callback=progress;p.progress_callback_user_data=&callback;
    p.encoder_begin_callback=encoder;p.encoder_begin_callback_user_data=&callback;
    p.abort_callback=aborted;p.abort_callback_user_data=&callback;
    p.new_segment_callback=segments;p.new_segment_callback_user_data=&callback;
    int result=whisper_full(ctx,p,audio.data(),audio.size());
    whisper_free(ctx);env->ReleaseStringUTFChars(hint,prompt);env->DeleteLocalRef(cls);
    return callback.stop.load() ? -3 : result;
}
