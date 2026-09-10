/*
 * mock_jni: controllable JNIEnv for the host-side harness (plan §6.2).
 *
 * Records every GetStringUTFChars / ReleaseStringUTFChars pair (with the
 * jstring each pinned buffer actually came from — catches the upstream
 * ReleaseStringUTFChars(env, cmd, cmd_cwd) mismatch), records ThrowNew
 * messages, and supports injectable failures:
 *
 *   mock.fail_string_get_at   — return NULL from the Nth GetStringUTFChars
 *                               call (0 = never fail) — argv/envp
 *                               marshalling failure injection.
 *   mock.fail_critical_at     — return NULL from the Nth
 *                               GetPrimitiveArrayCritical call (1 =
 *                               processIdArray, 2 = ptsDeviceArray).
 *
 * GetStringUTFChars returns a malloc'd COPY by default (a real JVM is
 * free to copy), so a release against the wrong jstring is detectable
 * both by pair record and by ownership of the copy.
 */
#ifndef RD_MOCK_JNI_H
#define RD_MOCK_JNI_H

#include <jni.h>

#define RD_MOCK_MAX_PINS 64

typedef struct {
    int used;
    jstring from;
    char* copy;
} rd_pin_record;

typedef struct {
    /* injectable failures */
    int fail_string_get_at;   /* 1-based call counter; 0 = never */
    int fail_critical_at;     /* 1-based critical-call counter; 0 = never */

    /* call records */
    rd_pin_record pins[RD_MOCK_MAX_PINS];
    int pin_count;

    char last_thrown[256];
    int threw;

    /* Number of ReleaseStringUTFChars calls whose jstring did not match
     * the jstring the chars were pinned from (upstream defect 1). */
    int pair_violations;

    /* stats */
    int string_get_calls;
    int critical_calls;
} rd_mock;

extern rd_mock mock;

void mock_reset(void);
/* Verifies: every pin released exactly once, against the jstring its
 * chars came from. Returns 0 if clean; prints violations otherwise. */
int mock_verify_pins(void);
/* Frees mock-owned objects and internal records. */
void mock_destroy(void);

/* Constructors for mock Java objects. Freed by mock_destroy(). */
jstring mock_string_new(const char* content);
jobjectArray mock_objarray_new(const char* const* items, int count);
jintArray mock_intarray_new(jint* data, int count);

/* Convenience: a fully-built mock argument set for a createSubprocess
 * call (pid written back into pid_slot, pts device into pts_dev_slot). */
typedef struct {
    jstring cmd, cwd;
    jobjectArray args, envVars;
    jintArray pidArray;
    jint pid_slot;
    jintArray ptsArray;
    jint pts_dev_slot;
} mock_call;

void mock_call_build(mock_call* c, const char* cmd, const char* cwd,
                     const char* const* args, int nargs,
                     const char* const* env, int nenv);

/* The shared mock function table (installed as the JNIEnv). */
extern const struct JNINativeInterface_ rd_mock_env_table;

#endif /* RD_MOCK_JNI_H */
