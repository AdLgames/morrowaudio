#include <whisper.h>
#include <emscripten.h>
#include <algorithm>
static whisper_context *ctx=nullptr;
static void segment(whisper_context *c,whisper_state *,int count,void *){
  for(int n=whisper_full_n_segments(c)-count;n<whisper_full_n_segments(c);n++){
    if(whisper_full_get_segment_no_speech_prob(c,n)>.7f)continue;
    const char *text=whisper_full_get_segment_text(c,n);
    EM_ASM({Module.onSegment($0,$1,UTF8ToString($2));},(double)whisper_full_get_segment_t0(c,n)/100.,(double)whisper_full_get_segment_t1(c,n)/100.,text);
  }
}
static void progress(whisper_context *,whisper_state *,int percent,void *){EM_ASM({Module.onProgress($0);},percent);}
static void quiet(enum ggml_log_level,const char *,void *){}
extern "C" {
int load_model(const char *path){whisper_log_set(quiet,nullptr);auto p=whisper_context_default_params();p.use_gpu=false;ctx=whisper_init_from_file_with_params(path,p);return ctx?0:-1;}
void free_model(){if(ctx)whisper_free(ctx);ctx=nullptr;}
int decode_audio(float *samples,int count,const char *prompt){
 if(!ctx)return -1;
 auto p=whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
 p.n_threads=1;p.language="en";p.translate=false;p.no_context=true;
 p.initial_prompt=prompt;p.carry_initial_prompt=true;p.beam_search.beam_size=3;
 p.temperature=0;p.temperature_inc=0;p.suppress_nst=true;p.no_speech_thold=.6;
 p.print_progress=false;p.print_realtime=false;p.print_timestamps=false;
 p.new_segment_callback=segment;p.progress_callback=progress;
 return whisper_full(ctx,p,samples,count);
}
}
