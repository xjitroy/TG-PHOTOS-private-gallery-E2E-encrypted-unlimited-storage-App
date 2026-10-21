#include <jni.h>
#include <android/bitmap.h>
#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void quiet(ggml_log_level, const char *, void *) {}
struct Engine {
 llama_model * model = nullptr;
 llama_context * context = nullptr;
 mtmd_context * vision = nullptr;
 std::chrono::steady_clock::time_point deadline;
 ~Engine() { if (vision) mtmd_free(vision); if (context) llama_free(context); if (model) llama_model_free(model); }
};
bool expired(void * p) { return std::chrono::steady_clock::now() > static_cast<Engine *>(p)->deadline; }
void fail(JNIEnv * env) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "On-device model could not complete this request"); }
std::string string(JNIEnv * env, jstring value) { const char * p=env->GetStringUTFChars(value,nullptr); if (!p) throw std::bad_alloc(); std::string s(p);env->ReleaseStringUTFChars(value,p);return s; }
}
extern "C" JNIEXPORT jlong JNICALL Java_app_televault_smart_VlmNative_open(JNIEnv * env,jclass,jstring model,jstring projector,jstring backend) {
 try {
  llama_log_set(quiet,nullptr); mtmd_helper_log_set(quiet,nullptr);
  auto path=string(env,backend); if (!ggml_backend_load(path.c_str())) throw std::runtime_error("CPU backend");
  llama_backend_init();
  auto e=std::make_unique<Engine>(); e->deadline=std::chrono::steady_clock::now()+std::chrono::seconds(90);
  auto mp=llama_model_default_params();mp.n_gpu_layers=0;
  e->model=llama_model_load_from_file(string(env,model).c_str(),mp);if(!e->model)throw std::runtime_error("model");
  auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=256;cp.n_ubatch=128;cp.n_threads=2;cp.n_threads_batch=2;cp.abort_callback=expired;cp.abort_callback_data=e.get();
  e->context=llama_init_from_model(e->model,cp);if(!e->context)throw std::runtime_error("context");
  auto vp=mtmd_context_params_default();vp.use_gpu=false;vp.n_threads=2;vp.print_timings=false;vp.warmup=false;vp.image_max_tokens=256;
  e->vision=mtmd_init_from_file(string(env,projector).c_str(),e->model,vp);
  if(!e->vision || !mtmd_support_vision(e->vision))throw std::runtime_error("vision");
  return reinterpret_cast<jlong>(e.release());
 } catch (...) { fail(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_app_televault_smart_VlmNative_close(JNIEnv *,jclass,jlong handle) { delete reinterpret_cast<Engine *>(handle); }
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_televault_smart_VlmNative_read(JNIEnv * env,jclass,jlong handle,jobject bitmap,jstring request) {
 try {
  auto * e=reinterpret_cast<Engine *>(handle);if(!e)throw std::runtime_error("closed");
  e->deadline=std::chrono::steady_clock::now()+std::chrono::seconds(75);
  AndroidBitmapInfo info{};if(AndroidBitmap_getInfo(env,bitmap,&info)!=0 || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width>768 || info.height>768)throw std::runtime_error("bitmap");
  void * pixels=nullptr;if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=0)throw std::runtime_error("pixels");
  std::vector<unsigned char> rgb(info.width*info.height*3);
  for(uint32_t y=0;y<info.height;y++)for(uint32_t x=0;x<info.width;x++){auto * p=static_cast<unsigned char *>(pixels)+y*info.stride+x*4;auto i=(y*info.width+x)*3;rgb[i]=p[0];rgb[i+1]=p[1];rgb[i+2]=p[2];}
  AndroidBitmap_unlockPixels(env,bitmap);
  std::unique_ptr<mtmd_bitmap,decltype(&mtmd_bitmap_free)> image(mtmd_bitmap_init(info.width,info.height,rgb.data()),mtmd_bitmap_free);
  std::fill(rgb.begin(),rgb.end(),0);if(!image)throw std::runtime_error("image");
  auto user=std::string(mtmd_default_marker())+"\n"+string(env,request)+" /no_think";
  llama_chat_message msg{"user",user.c_str()};std::vector<char> prompt(16384);
  int n=llama_chat_apply_template(llama_model_chat_template(e->model,nullptr),&msg,1,true,prompt.data(),prompt.size());
  if(n<0 || n>=static_cast<int>(prompt.size()))throw std::runtime_error("template");
  std::unique_ptr<mtmd_input_chunks,decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(),mtmd_input_chunks_free);
  const mtmd_bitmap * images[]={image.get()};mtmd_input_text text{prompt.data(),static_cast<size_t>(n),true,true};
  llama_memory_clear(llama_get_memory(e->context),true);
  if(mtmd_tokenize(e->vision,chunks.get(),&text,images,1)!=0 || mtmd_helper_get_n_tokens(chunks.get())>1600)throw std::runtime_error("tokens");
  llama_pos pos=0;if(mtmd_helper_eval_chunks(e->vision,e->context,chunks.get(),0,0,256,true,&pos)!=0)throw std::runtime_error("encode");
  std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
  auto * vocab=llama_model_get_vocab(e->model);std::string result;
  for(int i=0;i<256 && pos<2000 && !expired(e);i++){
   auto token=llama_sampler_sample(sampler.get(),e->context,-1);if(llama_vocab_is_eog(vocab,token))break;
   char piece[512];int bytes=llama_token_to_piece(vocab,token,piece,sizeof(piece),0,false);if(bytes<0 || bytes>512)throw std::runtime_error("piece");result.append(piece,bytes);
   auto batch=llama_batch_get_one(&token,1);if(llama_decode(e->context,batch)!=0)throw std::runtime_error("decode");pos++;
  }
  llama_memory_clear(llama_get_memory(e->context),true);if(expired(e) || result.empty())throw std::runtime_error("timeout");
  jbyteArray out=env->NewByteArray(result.size());if(out)env->SetByteArrayRegion(out,0,result.size(),reinterpret_cast<const jbyte *>(result.data()));std::fill(result.begin(),result.end(),0);return out;
 } catch (...) { fail(env);return nullptr; }
}
