/*
 * Fake jni.h for the host-side JNI test harness (plan §6.2).
 *
 * The harness compiles the vendored termux.c against THIS header (via
 * -I include, shadowing any real JDK jni.h), so the mock JNIEnv only
 * needs the function-pointer members termux.c actually uses — member
 * access is by name, so table order is irrelevant.
 *
 * jstring / jobjectArray / jintArray are mock objects carrying their
 * own data, so the mock can record GetStringUTFChars/ReleaseStringUTFChars
 * pairing and inject failures.
 */
#ifndef RD_FAKE_JNI_H
#define RD_FAKE_JNI_H

#include <stddef.h>
#include <stdint.h>

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

typedef int8_t jboolean;
typedef int32_t jint;
typedef jint jsize;

/* Mock types with payload. */
struct _jstring { const char* content; };
typedef struct _jstring* jstring;

struct _jobjectarray { jstring* items; jsize len; };
typedef struct _jobjectarray* jobjectArray;

struct _jintarray { jint* data; jsize len; };
typedef struct _jintarray* jintArray;

typedef void* jclass; /* mock FindClass returns a dummy non-NULL token */

/*
 * JNIEnv is a pointer to the function table, exactly like real jni.h in
 * C mode — so `(*env)->FindClass(env, ...)` in termux.c works unchanged.
 * The typedef must precede the table definition: members are declared
 * with JNIEnv* parameters.
 */
typedef const struct JNINativeInterface_* JNIEnv;

struct JNINativeInterface_ {
    jclass (*FindClass)(JNIEnv* env, const char* name);
    jint (*ThrowNew)(JNIEnv* env, jclass clazz, const char* message);
    jsize (*GetArrayLength)(JNIEnv* env, jobjectArray array);
    jstring (*GetObjectArrayElement)(JNIEnv* env, jobjectArray array, jsize index);
    const char* (*GetStringUTFChars)(JNIEnv* env, jstring str, jboolean* isCopy);
    void (*ReleaseStringUTFChars)(JNIEnv* env, jstring str, const char* chars);
    jint* (*GetPrimitiveArrayCritical)(JNIEnv* env, jintArray array, jboolean* isCopy);
    void (*ReleasePrimitiveArrayCritical)(JNIEnv* env, jintArray array, jint* carray, jint mode);
};

#endif /* RD_FAKE_JNI_H */
