/*
 * alloc_tests.c — fork-window allocation discipline test (plan §6.2).
 * Built WITHOUT sanitizers, with link-time --wrap=malloc and friends
 * (see interposers.c); ASan intercepts malloc and cannot coexist with
 * --wrap.
 *
 * Spawns a child whose execve MUST fail (absolute path to a nonexistent
 * file), so the child walks the full path up to execve and then the
 * exec-failure exit. The interposed execve reports the child's
 * allocation count ("RD_ALLOCS(execve)=n") through a pipe right before
 * the real call; the test asserts n == 0 and exit code 127.
 *
 * Honest scope (review Part 2): link-time wrapping observes only OUR
 * compilation units — this proves the RustDroid child path does not
 * allocate; it is not an oracle for libc/bionic internals and not an
 * Android-seccomp oracle (plan §6.6 owns that).
 */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include "interposers.h"
#include "mock_jni.h"
#include "seams.h"

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env, jclass clazz, jstring cmd, jstring cwd, jobjectArray args,
        jobjectArray envVars, jintArray processIdArray, jint rows, jint columns,
        jint cell_width, jint cell_height);

static JNIEnv* mock_env(void)
{
    static JNIEnv table = NULL;
    if (table == NULL) table = &rd_mock_env_table;
    return &table;
}

int main(void)
{
    int failed = 0;

    mock_reset();
    mock_call c;
    const char* args[] = { "probe" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/nonexistent-rd-exec-failure-probe", "/", args, 1, env, 1);

    int report_pipe[2];
    if (pipe(report_pipe) != 0) {
        printf("FAIL: pipe() failed\n");
        return 1;
    }
    g_report_fd = report_pipe[1];

    JNIEnv* env_ptr = mock_env();
    jint ptm = Java_com_termux_terminal_JNI_createSubprocess(
        env_ptr, NULL, c.cmd, c.cwd, c.args, c.envVars, c.pidArray,
        24, 80, 10, 20);

    /* Parent's copy of the write end can go now; the child holds its own. */
    g_report_fd = -1;
    close(report_pipe[1]);

    int exit_code = -1;
    if (ptm >= 0 && c.pid_slot > 0) {
        int status;
        waitpid(c.pid_slot, &status, 0);
        if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
        close(ptm);
    } else {
        printf("FAIL: createSubprocess did not spawn (ptm=%d pid=%d)\n", (int) ptm, (int) c.pid_slot);
        failed++;
    }

    /* Read the report(s) written by the interposed execve/_exit. */
    char report[256];
    ssize_t total = 0;
    for (;;) {
        ssize_t n = read(report_pipe[0], report + total, sizeof report - 1 - (size_t) total);
        if (n > 0) {
            total += n;
            if ((size_t) total >= sizeof report - 1) break;
            continue;
        }
        break;
    }
    close(report_pipe[0]);
    if (total > 0) report[total] = '\0';

    long allocs_at_execve = -1;
    if (total > 0) {
        char* line = strstr(report, "RD_ALLOCS(execve)=");
        if (line != NULL) {
            allocs_at_execve = strtol(line + strlen("RD_ALLOCS(execve)="), NULL, 10);
        }
    }

    printf("  report: %s", total > 0 ? report : "(none)\n");
    printf("  exit_code=%d allocs_at_execve=%ld\n", exit_code, allocs_at_execve);

    if (allocs_at_execve != 0) {
        printf("FAIL: child allocated %ld time(s) between fork and execve (must be 0)\n",
               allocs_at_execve);
        failed++;
    }
    if (exit_code != 127) {
        printf("FAIL: exec-failure exit code is %d (expected 127)\n", exit_code);
        failed++;
    }

    int pins_ok = mock_verify_pins() == 0;
    mock_destroy();
    if (!pins_ok) {
        printf("FAIL: JNI pins not cleanly released\n");
        failed++;
    }

    printf("\n%s\n", failed == 0 ? "alloc window: OK (0 allocations before execve)" : "alloc window: FAILED");
    return failed == 0 ? 0 : 1;
}
