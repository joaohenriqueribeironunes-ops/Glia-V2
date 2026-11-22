#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>

#define LOG_TAG "GliaAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" {

// Calcula RMS (amplitude) de um buffer PCM 16-bit. Retorna 0..1.
JNIEXPORT jfloat JNICALL
Java_com_glia_assistant_audio_AudioProcessor_nativeRms(JNIEnv* env, jobject thiz,
                                                      jshortArray pcm, jint length) {
    if (pcm == nullptr || length <= 0) return 0.0f;
    jshort* data = env->GetShortArrayElements(pcm, nullptr);
    if (!data) return 0.0f;
    double sum = 0.0;
    for (int i = 0; i < length; i++) {
        double v = (double)data[i] / 32768.0;
        sum += v * v;
    }
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    float rms = (float)std::sqrt(sum / (double)length);
    return rms;
}

} // extern "C"