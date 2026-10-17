/* App-owned bridge to Tesseract's public C API. No persistent pixels or text/log output. */
#include <jni.h>
#include <android/bitmap.h>
#include <dlfcn.h>
#include <stdio.h>

JNIEXPORT jstring JNICALL Java_app_televault_ocr_OcrScript_nativeScript(JNIEnv *env,jobject self,jstring path,jobject bitmap) {
    (void)self;
    void *library=dlopen("libtesseract.so",RTLD_NOW|RTLD_LOCAL);
    if(!library)return NULL;
    void *(*create)(void)=dlsym(library,"TessBaseAPICreate");
    int (*init)(void*,const char*,const char*)=dlsym(library,"TessBaseAPIInit3");
    void (*set_image)(void*,const unsigned char*,int,int,int,int)=dlsym(library,"TessBaseAPISetImage");
    int (*detect)(void*,int*,float*,const char**,float*)=dlsym(library,"TessBaseAPIDetectOrientationScript");
    int (*variable)(void*,const char*,const char*)=dlsym(library,"TessBaseAPISetVariable");
    void (*destroy)(void*)=dlsym(library,"TessBaseAPIDelete");
    if(!create || !init || !set_image || !detect || !destroy){dlclose(library);return NULL;}
    AndroidBitmapInfo info;
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width>2400 || info.height>2400){dlclose(library);return NULL;}
    const char *directory=(*env)->GetStringUTFChars(env,path,NULL);
    if(!directory){dlclose(library);return NULL;}
    void *api=create();jstring result=NULL;
    if(api){
        if(variable)variable(api,"debug_file","/dev/null");
        if(init(api,directory,"osd")==0){
            void *pixels=NULL;
            if(AndroidBitmap_lockPixels(env,bitmap,&pixels)==ANDROID_BITMAP_RESULT_SUCCESS){
                set_image(api,pixels,(int)info.width,(int)info.height,4,(int)info.stride);
                AndroidBitmap_unlockPixels(env,bitmap);
                int orientation=0;float confidence=0,script_confidence=0;const char *script=NULL;
                if(detect(api,&orientation,&confidence,&script,&script_confidence) && script){
                    char text[160];snprintf(text,sizeof(text),"%s\n%d\n%.3f",script,orientation,script_confidence);
                    result=(*env)->NewStringUTF(env,text);
                }
            }
        }
        destroy(api);
    }
    (*env)->ReleaseStringUTFChars(env,path,directory);dlclose(library);return result;
}
