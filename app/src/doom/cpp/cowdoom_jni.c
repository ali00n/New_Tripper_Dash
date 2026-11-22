/*
 * Cow DOOM: ponte entre o doomgeneric (motor GPL) e o app.
 * O motor roda numa thread própria; cada quadro é convertido para RGBA e
 * fica guardado para o Java pegar e mandar ao painel. Teclas chegam por fila.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <pthread.h>
#include <android/log.h>

#include "doomgeneric.h"
#include "doomkeys.h"

#define TAG "CowDoom"
#define QUEUE 128

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t *rgba;
static volatile int frameSeq;
static unsigned short keys[QUEUE];
static int keyHead, keyTail;

void DG_Init(void) {
    rgba = calloc((size_t) DOOMGENERIC_RESX * DOOMGENERIC_RESY, 4);
}

/* Paleta do doomgeneric: 0x00RRGGBB. Bitmap ARGB_8888 do Android: bytes R,G,B,A. */
void DG_DrawFrame(void) {
    const int n = DOOMGENERIC_RESX * DOOMGENERIC_RESY;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < n; i++) {
        uint32_t p = DG_ScreenBuffer[i];
        rgba[i] = 0xFF000000u | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu);
    }
    frameSeq++;
    pthread_mutex_unlock(&lock);
}

void DG_SleepMs(uint32_t ms) {
    usleep(ms * 1000);
}

uint32_t DG_GetTicksMs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint32_t) (t.tv_sec * 1000ull + t.tv_nsec / 1000000);
}

int DG_GetKey(int *pressed, unsigned char *key) {
    pthread_mutex_lock(&lock);
    if (keyHead == keyTail) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    unsigned short e = keys[keyTail];
    keyTail = (keyTail + 1) % QUEUE;
    pthread_mutex_unlock(&lock);
    *pressed = e >> 8;
    *key = (unsigned char) (e & 0xFF);
    return 1;
}

void DG_SetWindowTitle(const char *title) {
    (void) title;
}

JNIEXPORT jint JNICALL
Java_br_com_evandro_tripper_DoomEngine_nativeWidth(JNIEnv *env, jclass cls) { return DOOMGENERIC_RESX; }

JNIEXPORT jint JNICALL
Java_br_com_evandro_tripper_DoomEngine_nativeHeight(JNIEnv *env, jclass cls) { return DOOMGENERIC_RESY; }

/* 0 frente, 1 trás, 2 esquerda, 3 direita, 4 tiro, 5 usar, 6 enter, 7 esc */
JNIEXPORT jint JNICALL
Java_br_com_evandro_tripper_DoomEngine_nativeKeyCode(JNIEnv *env, jclass cls, jint which) {
    switch (which) {
        case 0: return KEY_UPARROW;
        case 1: return KEY_DOWNARROW;
        case 2: return KEY_LEFTARROW;
        case 3: return KEY_RIGHTARROW;
#ifdef KEY_FIRE
        case 4: return KEY_FIRE;
#else
        case 4: return KEY_RCTRL;
#endif
#ifdef KEY_USE
        case 5: return KEY_USE;
#else
        case 5: return ' ';
#endif
        case 6: return KEY_ENTER;
        case 7: return KEY_ESCAPE;
        default: return 0;
    }
}

JNIEXPORT void JNICALL
Java_br_com_evandro_tripper_DoomEngine_nativeKey(JNIEnv *env, jclass cls, jint key, jboolean down) {
    if (key <= 0) return;
    pthread_mutex_lock(&lock);
    int next = (keyHead + 1) % QUEUE;
    if (next != keyTail) {
        keys[keyHead] = (unsigned short) (((down ? 1 : 0) << 8) | (key & 0xFF));
        keyHead = next;
    }
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jint JNICALL
Java_br_com_evandro_tripper_DoomEngine_nativeFrame(JNIEnv *env, jclass cls, jobject buffer) {
    void *dst = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    const jlong need = (jlong) DOOMGENERIC_RESX * DOOMGENERIC_RESY * 4;
    if (dst == NULL || cap < need || rgba == NULL) return -1;
    pthread_mutex_lock(&lock);
    memcpy(dst, rgba, (size_t) need);
    int seq = frameSeq;
    pthread_mutex_unlock(&lock);
    return seq;
}

static char dirArg[512];
static char wadArg[512];

/* Roda para sempre (o DOOM sai do processo com exit() ao fechar pelo menu). */
JNIEXPORT void JNICALL
Java_br_com_evandro_tripper_DoomEngine_nativeRun(JNIEnv *env, jclass cls, jstring dir, jstring wad) {
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    const char *w = (*env)->GetStringUTFChars(env, wad, NULL);
    strncpy(dirArg, d, sizeof dirArg - 1);
    strncpy(wadArg, w, sizeof wadArg - 1);
    (*env)->ReleaseStringUTFChars(env, dir, d);
    (*env)->ReleaseStringUTFChars(env, wad, w);

    chdir(dirArg);
    setenv("HOME", dirArg, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG, "iniciando com %s", wadArg);

    static char *argv[] = {"cowdoom", "-iwad", wadArg, "-warp", "1", "1", "-skill", "3", NULL};
    doomgeneric_Create(8, argv);
    for (;;) doomgeneric_Tick();
}
