/*
 * mock_jni.c — implementation. See mock_jni.h.
 */
#include "mock_jni.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

rd_mock mock;

static jclass mock_FindClass(JNIEnv* env, const char* name)
{
    (void) env;
    (void) name;
    return (jclass) (intptr_t) 1; /* non-NULL dummy token */
}

static jint mock_ThrowNew(JNIEnv* env, jclass clazz, const char* message)
{
    (void) env;
    (void) clazz;
    mock.threw = 1;
    snprintf(mock.last_thrown, sizeof mock.last_thrown, "%s", message);
    return 0;
}

static jsize mock_GetArrayLength(JNIEnv* env, jobjectArray array)
{
    (void) env;
    return array ? array->len : 0;
}

static jstring mock_GetObjectArrayElement(JNIEnv* env, jobjectArray array, jsize index)
{
    (void) env;
    if (!array || index < 0 || index >= array->len) return NULL;
    return array->items[index];
}

static const char* mock_GetStringUTFChars(JNIEnv* env, jstring str, jboolean* isCopy)
{
    (void) env;
    mock.string_get_calls++;
    if (mock.fail_string_get_at == mock.string_get_calls) {
        return NULL; /* injected marshalling failure */
    }
    if (!str || !str->content) return NULL;
    if (mock.pin_count >= RD_MOCK_MAX_PINS) return NULL;
    char* copy = strdup(str->content);
    if (!copy) return NULL;
    mock.pins[mock.pin_count].used = 1;
    mock.pins[mock.pin_count].from = str;
    mock.pins[mock.pin_count].copy = copy;
    mock.pin_count++;
    if (isCopy) *isCopy = (jboolean) 1;
    return copy;
}

static void mock_ReleaseStringUTFChars(JNIEnv* env, jstring str, const char* chars)
{
    (void) env;
    /* Find the pin record for this buffer and check it came from THIS
     * jstring. Upstream's defect 1 (release cmd_cwd against cmd) is
     * caught right here. */
    for (int i = 0; i < mock.pin_count; i++) {
        if (mock.pins[i].used && mock.pins[i].copy == (char*) chars) {
            if (mock.pins[i].from != str) {
                mock.pair_violations++;
            }
            free(mock.pins[i].copy);
            mock.pins[i].used = 0; /* released; double-release detected by verify */
            return;
        }
    }
    printf("MOCK VIOLATION: ReleaseStringUTFChars with unknown buffer %p\n", (const void*) chars);
    mock.pair_violations++;
}

static jint* mock_GetPrimitiveArrayCritical(JNIEnv* env, jintArray array, jboolean* isCopy)
{
    (void) env;
    mock.critical_calls++;
    if (mock.fail_critical_at == mock.critical_calls) return NULL;
    if (!array) return NULL;
    if (isCopy) *isCopy = (jboolean) 0;
    return array->data;
}

static void mock_ReleasePrimitiveArrayCritical(JNIEnv* env, jintArray array, jint* carray, jint mode)
{
    (void) env;
    (void) array;
    (void) carray;
    (void) mode;
}

const struct JNINativeInterface_ rd_mock_env_table = {
    .FindClass = mock_FindClass,
    .ThrowNew = mock_ThrowNew,
    .GetArrayLength = mock_GetArrayLength,
    .GetObjectArrayElement = mock_GetObjectArrayElement,
    .GetStringUTFChars = mock_GetStringUTFChars,
    .ReleaseStringUTFChars = mock_ReleaseStringUTFChars,
    .GetPrimitiveArrayCritical = mock_GetPrimitiveArrayCritical,
    .ReleasePrimitiveArrayCritical = mock_ReleasePrimitiveArrayCritical,
};

/* ---- mock object management ---------------------------------------- */

#define RD_MOCK_MAX_OBJECTS 256

static void* g_objects[RD_MOCK_MAX_OBJECTS];
static int g_object_count;

static void track_object(void* obj)
{
    if (obj && g_object_count < RD_MOCK_MAX_OBJECTS) {
        g_objects[g_object_count++] = obj;
    }
}

jstring mock_string_new(const char* content)
{
    struct _jstring* s = malloc(sizeof *s);
    if (!s) abort();
    s->content = content;
    track_object(s);
    return s;
}

jobjectArray mock_objarray_new(const char* const* items, int count)
{
    struct _jobjectarray* a = malloc(sizeof *a);
    if (!a) abort();
    a->items = malloc(sizeof(jstring) * (count > 0 ? (size_t) count : 1));
    if (!a->items) abort();
    for (int i = 0; i < count; i++) {
        a->items[i] = mock_string_new(items[i]);
    }
    a->len = count;
    track_object(a);
    track_object(a->items);
    return a;
}

jintArray mock_intarray_new(jint* data, int count)
{
    struct _jintarray* a = malloc(sizeof *a);
    if (!a) abort();
    a->data = data;
    a->len = count;
    track_object(a);
    return a;
}

void mock_reset(void)
{
    for (int i = 0; i < mock.pin_count; i++) {
        if (mock.pins[i].used) free(mock.pins[i].copy);
        mock.pins[i].used = 0;
        mock.pins[i].from = NULL;
        mock.pins[i].copy = NULL;
    }
    mock.pin_count = 0;
    mock.fail_string_get_at = 0;
    mock.fail_critical_at = 0;
    mock.critical_calls = 0;
    mock.threw = 0;
    mock.pair_violations = 0;
    mock.string_get_calls = 0;
    mock.last_thrown[0] = '\0';
}

int mock_verify_pins(void)
{
    int bad = mock.pair_violations > 0 ? 1 : 0;
    if (mock.pair_violations > 0) {
        printf("MOCK: %d release-pairing violation(s) (chars released against a "
               "jstring they did not come from)\n", mock.pair_violations);
    }
    for (int i = 0; i < mock.pin_count; i++) {
        if (mock.pins[i].used) {
            printf("MOCK VIOLATION: pin from a jstring never released (leaked JNI pin)\n");
            bad = 1;
        }
    }
    return bad;
}

void mock_destroy(void)
{
    mock_reset();
    for (int i = 0; i < g_object_count; i++) {
        free(g_objects[i]);
    }
    g_object_count = 0;
}

void mock_call_build(mock_call* c, const char* cmd, const char* cwd,
                     const char* const* args, int nargs,
                     const char* const* env, int nenv)
{
    c->cmd = mock_string_new(cmd);
    c->cwd = mock_string_new(cwd);
    c->args = mock_objarray_new(args, nargs);
    c->envVars = mock_objarray_new(env, nenv);
    c->pid_slot = 0;
    c->pidArray = mock_intarray_new(&c->pid_slot, 1);
    c->pts_dev_slot = 0;
    c->ptsArray = mock_intarray_new(&c->pts_dev_slot, 1);
}
