#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include <deque>
#include <mutex>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <dlfcn.h>
#include <android/log.h>
#include "game/Defines.h"
#include "game/Platform.h"
#include "game/Game.h"
#include "game/FixedMath.h"
#ifdef __has_include
#if __has_include("libretro.h")
#include "libretro.h"
#define HAVE_LIBRETRO 1
#endif
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ArduboyBW", __VA_ARGS__)
static uint8_t inputMask = 0;
static uint8_t screenBuffer[DISPLAY_WIDTH * DISPLAY_HEIGHT / 8] = {};
static bool initialized = false;
static std::atomic<bool> audioEnabled{true};
static std::vector<uint8_t> loadedRomData;
struct Tone { uint16_t hz; uint16_t duration; };
static std::deque<Tone> tones;
static uint32_t toneSamplesLeft = 0;
static double phase = 0.0;
static uint16_t currentToneHz = 0;
static std::mutex audioMutex;
static std::vector<int16_t> coreAudio;
static size_t coreAudioRead = 0;
static bool coreActive = false;

uint8_t Platform::GetInput() { return inputMask; }
void Platform::SetLED(uint8_t, uint8_t, uint8_t) {}
void Platform::PlaySound(const uint16_t* pattern) {
    if (!audioEnabled || !pattern) return;
    std::lock_guard<std::mutex> lock(audioMutex);
    tones.clear(); toneSamplesLeft = 0; phase = 0.0;
    for (int i=0; i<512; i+=2) {
        uint16_t hz = pgm_read_word(pattern+i);
        if (hz == 0x8000) break;
        uint16_t duration = pgm_read_word(pattern+i+1);
        tones.push_back({hz, duration});
    }
}
bool Platform::IsAudioEnabled() { return audioEnabled.load(); }
void Platform::SetAudioEnabled(bool enabled) { audioEnabled.store(enabled); if(!enabled){std::lock_guard<std::mutex> lock(audioMutex);tones.clear();coreAudio.clear();coreAudioRead=0;toneSamplesLeft=0;currentToneHz=0;phase=0.0;} }
void Platform::ExpectLoadDelay() {}
uint8_t* Platform::GetScreenBuffer() { return screenBuffer; }
void Platform::FillScreen(uint8_t colour) { std::memset(screenBuffer, colour ? 0xFF : 0x00, sizeof(screenBuffer)); }
void Platform::PutPixel(uint8_t x, uint8_t y, uint8_t colour) {
    if (x >= DISPLAY_WIDTH || y >= DISPLAY_HEIGHT) return;
    const int index = (y & 0xF8) * DISPLAY_WIDTH / 8 + x;
    const uint8_t bit = static_cast<uint8_t>(1u << (y & 7));
    if (colour) screenBuffer[index] |= bit; else screenBuffer[index] &= static_cast<uint8_t>(~bit);
}
void Platform::DrawVLine(uint8_t x, int8_t y0, int8_t y1, uint8_t pattern) {
    if (x >= DISPLAY_WIDTH || y1 < y0) return;
    for (int y=y0; y<=y1; ++y) if(y>=0&&y<DISPLAY_HEIGHT) PutPixel(x,(uint8_t)y,(pattern>>(y&7))&1);
}
void Platform::DrawBitmap(int16_t x,int16_t y,const uint8_t* bitmap) {
    const uint8_t w=pgm_read_byte(bitmap),h=pgm_read_byte(bitmap+1); const uint8_t* data=bitmap+2; const int blocks=(w+7)/8;
    for(int j=0;j<h;++j) for(int i=0;i<w;++i){int idx=((j/8)*blocks+i/8)*8+(i%8);bool on=(pgm_read_byte(data+idx)>>(j&7))&1;if(x+i>=0&&x+i<DISPLAY_WIDTH&&y+j>=0&&y+j<DISPLAY_HEIGHT)PutPixel((uint8_t)(x+i),(uint8_t)(y+j),on);}
}
void Platform::DrawSolidBitmap(int16_t x,int16_t y,const uint8_t* bitmap){const uint8_t w=pgm_read_byte(bitmap),h=pgm_read_byte(bitmap+1);for(int j=0;j<h;++j)for(int i=0;i<w;++i)if(x+i>=0&&x+i<DISPLAY_WIDTH&&y+j>=0&&y+j<DISPLAY_HEIGHT)PutPixel((uint8_t)(x+i),(uint8_t)(y+j),0);DrawBitmap(x,y,bitmap);}
void Platform::DrawSprite(int16_t x,int16_t y,const uint8_t* bitmap,uint8_t){const uint8_t w=pgm_read_byte(bitmap),h=pgm_read_byte(bitmap+1);bitmap+=2;for(int j=0;j<h;++j)for(int i=0;i<w;++i){int idx=(w*(j/8)+i)*2;uint8_t px=pgm_read_byte(bitmap+idx),mask=pgm_read_byte(bitmap+idx+1),bit=1u<<(j&7);if((mask&bit)&&x+i>=0&&x+i<DISPLAY_WIDTH&&y+j>=0&&y+j<DISPLAY_HEIGHT)PutPixel((uint8_t)(x+i),(uint8_t)(y+j),(px&bit)!=0);}}
void Platform::DrawSprite(int16_t x,int16_t y,const uint8_t* bitmap,const uint8_t* mask,uint8_t,uint8_t){const uint8_t w=pgm_read_byte(bitmap),h=pgm_read_byte(bitmap+1);bitmap+=2;for(int j=0;j<h;++j)for(int i=0;i<w;++i){int idx=w*(j/8)+i;uint8_t px=pgm_read_byte(bitmap+idx),m=pgm_read_byte(mask+idx),bit=1u<<(j&7);if((m&bit)&&x+i>=0&&x+i<DISPLAY_WIDTH&&y+j>=0&&y+j<DISPLAY_HEIGHT)PutPixel((uint8_t)(x+i),(uint8_t)(y+j),(px&bit)!=0);}}
void Platform::DrawBackground(){for(int y=0;y<DISPLAY_HEIGHT;++y)for(int x=0;x<DISPLAY_WIDTH;++x)PutPixel(x,y,y<DISPLAY_HEIGHT/2?((x|y)&1)==0:((x^y)&1)!=0);}

#ifdef HAVE_LIBRETRO
static void* coreHandle=nullptr;
static retro_environment_t retroEnv=nullptr; static retro_video_refresh_t retroVideo=nullptr; static retro_audio_sample_t retroAudio=nullptr; static retro_audio_sample_batch_t retroAudioBatch=nullptr; static retro_input_poll_t retroInputPoll=nullptr; static retro_input_state_t retroInputState=nullptr;
static retro_init_t p_init=nullptr; static retro_deinit_t p_deinit=nullptr; static retro_api_version_t p_api=nullptr; static retro_get_system_info_t p_info=nullptr; static retro_get_system_av_info_t p_av=nullptr; static retro_set_environment_t p_setenv=nullptr; static retro_set_video_refresh_t p_setvideo=nullptr; static retro_set_audio_sample_t p_setaudio=nullptr; static retro_set_audio_sample_batch_t p_setaudiobatch=nullptr; static retro_set_input_poll_t p_setpoll=nullptr; static retro_set_input_state_t p_setstate=nullptr; static retro_load_game_t p_load=nullptr; static retro_unload_game_t p_unload=nullptr; static retro_run_t p_run=nullptr;
static uint32_t retroPixels[128*64]; static unsigned retroW=128,retroH=64; static retro_pixel_format retroFormat=RETRO_PIXEL_FORMAT_0RGB1555;
static void video_cb(const void* data,unsigned w,unsigned h,size_t pitch){if(!data||!w||!h)return;retroW=std::min(w,128u);retroH=std::min(h,64u);for(unsigned y=0;y<retroH;y++)for(unsigned x=0;x<retroW;x++){uint32_t c=0xff000000;if(retroFormat==RETRO_PIXEL_FORMAT_XRGB8888){uint32_t v;memcpy(&v,(const uint8_t*)data+y*pitch+x*4,4);c=0xff000000|(v&0xffffff);}else if(retroFormat==RETRO_PIXEL_FORMAT_RGB565){uint16_t v;memcpy(&v,(const uint8_t*)data+y*pitch+x*2,2);unsigned r=(v>>11)&31,g=(v>>5)&63,b=v&31;c=0xff000000|((r*255/31)<<16)|((g*255/63)<<8)|(b*255/31);}else{uint16_t v;memcpy(&v,(const uint8_t*)data+y*pitch+x*2,2);unsigned r=(v>>10)&31,g=(v>>5)&31,b=v&31;c=0xff000000|((r*255/31)<<16)|((g*255/31)<<8)|(b*255/31);}retroPixels[y*128+x]=c;}}
static void audio_cb(int16_t l,int16_t r){std::lock_guard<std::mutex> lock(audioMutex);if(coreAudio.size()>131072){coreAudio.clear();coreAudioRead=0;}coreAudio.push_back(l);coreAudio.push_back(r);}
static size_t audio_batch_cb(const int16_t* data,size_t frames){std::lock_guard<std::mutex> lock(audioMutex);size_t samples=frames*2;if(coreAudio.size()>131072){coreAudio.clear();coreAudioRead=0;}coreAudio.insert(coreAudio.end(),data,data+samples);return frames;}
static bool env_cb(unsigned cmd,void* data){if(cmd==RETRO_ENVIRONMENT_SET_PIXEL_FORMAT){retroFormat=*(retro_pixel_format*)data;return true;}if(cmd==RETRO_ENVIRONMENT_GET_CAN_DUPE){*(bool*)data=true;return true;}return false;}
static void input_poll_cb(){}
static int16_t input_state_cb(unsigned port,unsigned device,unsigned index,unsigned id){if(port||device!=RETRO_DEVICE_JOYPAD)return 0;switch(id){case RETRO_DEVICE_ID_JOYPAD_LEFT:return inputMask&INPUT_LEFT?1:0;case RETRO_DEVICE_ID_JOYPAD_RIGHT:return inputMask&INPUT_RIGHT?1:0;case RETRO_DEVICE_ID_JOYPAD_UP:return inputMask&INPUT_UP?1:0;case RETRO_DEVICE_ID_JOYPAD_DOWN:return inputMask&INPUT_DOWN?1:0;case RETRO_DEVICE_ID_JOYPAD_A:return inputMask&INPUT_A?1:0;case RETRO_DEVICE_ID_JOYPAD_B:return inputMask&INPUT_B?1:0;default:return 0;}}
static bool loadCore(){if(coreHandle)return true;coreHandle=dlopen("libarduous_android.so",RTLD_NOW);if(!coreHandle){LOGE("dlopen arduous failed: %s",dlerror());return false;}
#define SYM(v,n) v=(decltype(v))dlsym(coreHandle,n); if(!v){LOGE("Missing core symbol %s",n);return false;}
SYM(p_init,"retro_init");SYM(p_deinit,"retro_deinit");SYM(p_api,"retro_api_version");SYM(p_info,"retro_get_system_info");SYM(p_av,"retro_get_system_av_info");SYM(p_setenv,"retro_set_environment");SYM(p_setvideo,"retro_set_video_refresh");SYM(p_setaudio,"retro_set_audio_sample");SYM(p_setaudiobatch,"retro_set_audio_sample_batch");SYM(p_setpoll,"retro_set_input_poll");SYM(p_setstate,"retro_set_input_state");SYM(p_load,"retro_load_game");SYM(p_unload,"retro_unload_game");SYM(p_run,"retro_run");
#undef SYM
p_setenv(env_cb);p_setvideo(video_cb);p_setaudio(audio_cb);p_setaudiobatch(audio_batch_cb);p_setpoll(input_poll_cb);p_setstate(input_state_cb);p_init();return true;}
#endif

static void copyFrame(JNIEnv* env,jintArray out){jint pixels[128*64];for(int y=0;y<64;y++)for(int x=0;x<128;x++){int idx=(y&0xF8)*128/8+x;bool white=(screenBuffer[idx]>>(y&7))&1;pixels[y*128+x]=white?0xFFFFFFFF:0xFF000000;}
#ifdef HAVE_LIBRETRO
if(coreActive){for(int i=0;i<128*64;i++)pixels[i]=(retroPixels[i]&0x00ffffff)==0?0xFF000000:0xFFFFFFFF;}
#endif
env->SetIntArrayRegion(out,0,128*64,pixels);}
extern "C" JNIEXPORT jintArray JNICALL Java_com_catacombs_game_MainActivity_nativeFrame(JNIEnv* env,jclass,jint keys){inputMask=(uint8_t)(keys&0x3f);jintArray out=env->NewIntArray(128*64);
#ifdef HAVE_LIBRETRO
if(coreActive&&p_run){p_run();jint px[128*64];for(int i=0;i<128*64;i++){uint32_t v=retroPixels[i];unsigned lum=(((v>>16)&255)*30+((v>>8)&255)*59+(v&255)*11)/100;px[i]=lum>127?0xffffffff:0xff000000;}env->SetIntArrayRegion(out,0,128*64,px);return out;}
#endif
if(!initialized){SeedRandom(0x5A17);Game::Init();initialized=true;}Game::Tick();Game::Draw();copyFrame(env,out);return out;}
extern "C" JNIEXPORT jboolean JNICALL Java_com_catacombs_game_MainActivity_nativeLoadHex(JNIEnv* env,jclass,jstring path){
#ifdef HAVE_LIBRETRO
const char* p=env->GetStringUTFChars(path,nullptr);if(!loadCore()){env->ReleaseStringUTFChars(path,p);return JNI_FALSE;}if(coreActive&&p_unload)p_unload();if(p_deinit)p_deinit();p_init();coreActive=false;loadedRomData.clear();FILE* f=fopen(p,"rb");env->ReleaseStringUTFChars(path,p);if(!f)return JNI_FALSE;fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);if(n<=0||n>1024*1024){fclose(f);return JNI_FALSE;}loadedRomData.resize((size_t)n);size_t got=fread(loadedRomData.data(),1,(size_t)n,f);fclose(f);if(got!=(size_t)n){loadedRomData.clear();return JNI_FALSE;}retro_game_info info{};info.data=loadedRomData.data();info.size=loadedRomData.size();info.path=nullptr;info.meta=nullptr;if(!p_load(&info)){loadedRomData.clear();return JNI_FALSE;}coreActive=true;return JNI_TRUE;
#else
return JNI_FALSE;
#endif
}
extern "C" JNIEXPORT void JNICALL Java_com_catacombs_game_MainActivity_nativeStopHex(JNIEnv*,jclass){
#ifdef HAVE_LIBRETRO
if(coreActive&&p_unload) p_unload();
coreActive=false; loadedRomData.clear(); retroPixels[0]=0;
std::lock_guard<std::mutex> lock(audioMutex); coreAudio.clear(); coreAudioRead=0;
#endif
}
extern "C" JNIEXPORT jshortArray JNICALL Java_com_catacombs_game_MainActivity_nativeDrainAudio(JNIEnv* env,jclass,jint requested){int count=std::max(0,std::min(requested,4096));std::vector<int16_t> out(count,0);std::lock_guard<std::mutex> lock(audioMutex);if(!audioEnabled.load()){coreAudio.clear();coreAudioRead=0;tones.clear();toneSamplesLeft=0;currentToneHz=0;jshortArray a=env->NewShortArray(count);env->SetShortArrayRegion(a,0,count,out.data());return a;}
#ifdef HAVE_LIBRETRO
if(coreActive&&coreAudioRead+1<coreAudio.size()){int frames=std::min(count,(int)((coreAudio.size()-coreAudioRead)/2));for(int i=0;i<frames;i++){int32_t mixed=(int32_t)coreAudio[coreAudioRead+i*2]+(int32_t)coreAudio[coreAudioRead+i*2+1];out[i]=(int16_t)(mixed/2);}coreAudioRead+=(size_t)frames*2;if(coreAudioRead==coreAudio.size()){coreAudio.clear();coreAudioRead=0;}jshortArray a=env->NewShortArray(count);env->SetShortArrayRegion(a,0,count,out.data());return a;}
#endif
const double rate=44100.0;for(int i=0;i<count;i++){if(toneSamplesLeft==0){if(tones.empty())break;Tone t=tones.front();tones.pop_front();toneSamplesLeft=(uint32_t)((uint64_t)t.duration*44100/1024);if(toneSamplesLeft==0)toneSamplesLeft=1;phase=0.0;currentToneHz=t.hz;}if(currentToneHz){out[i]=(phase<0.5?5000:-5000);phase+=currentToneHz/rate;if(phase>=1.0)phase-=1.0;}toneSamplesLeft--;}
jshortArray a=env->NewShortArray(count);env->SetShortArrayRegion(a,0,count,out.data());return a;}
extern "C" JNIEXPORT void JNICALL Java_com_catacombs_game_MainActivity_nativeResetGame(JNIEnv*,jclass){initialized=false;coreActive=false;loadedRomData.clear();std::memset(screenBuffer,0,sizeof(screenBuffer));std::lock_guard<std::mutex> lock(audioMutex);tones.clear();toneSamplesLeft=0;currentToneHz=0;coreAudio.clear();coreAudioRead=0;}
extern "C" JNIEXPORT void JNICALL Java_com_catacombs_game_MainActivity_nativeSetAudioEnabled(JNIEnv*,jclass,jboolean enabled){Platform::SetAudioEnabled(enabled==JNI_TRUE);}
