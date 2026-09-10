/*
 * tests.c — host-side JNI test driver, ASan + LeakSanitizer build
 * (plan §6.2). Run via the Makefile (`make check`), from this
 * directory (sigprobe is resolved via getcwd()).
 *
 * Regression pins (each named defect in the plan):
 *   - defect 1 (release mismatch): test_release_pairing — the mock
 *     records which jstring every pinned buffer came from; upstream
 *     releases cmd_cwd against cmd and the mock flags it.
 *   - defect 2 (master-fd leaks): test_fd_leak_{fork,grantpt,critical}
 *     — failure injected via the RD_ seams, then fcntl(F_GETFD) must
 *     report EBADF (the ptm was closed). The probe-fd trick works
 *     because the harness is single-threaded, so /dev/ptmx reuses the
 *     just-closed probe fd number deterministically.
 *   - defect 3 (argv/envp marshalling leaks):
 *     test_marshall_leak_{argv_get,envp_malloc} — failures injected via
 *     the mock / RD_MALLOC seam; LeakSanitizer must report zero leaks.
 *   - fork-window restructure: test_sigpipe_reset (SIG_DFL observed by
 *     the exec'd probe; upstream inherits SIG_IGN) and
 *     tests_alloc/test_alloc_window (separate binary).
 *   - C-layer fd discipline: test_fd_count_200_cycles.
 *   - end-to-end: test_happy_path_echo.
 *
 * EIO on the master read after the child side is gone is treated as
 * normal session end everywhere (plan §5.3) — read_master_all().
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include "mock_jni.h"
#include "seams.h"

#if __has_include(<sanitizer/lsan_interface.h>)
#include <sanitizer/lsan_interface.h>
#define RD_HAVE_LSAN 1
#else
#define RD_HAVE_LSAN 0
#endif

/* The vendored C under test. */
JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env, jclass clazz, jstring cmd, jstring cwd, jobjectArray args,
        jobjectArray envVars, jintArray processIdArray, jint rows, jint columns,
        jint cell_width, jint cell_height);

static int g_failed = 0;
static int g_ran = 0;

#define CHECK(cond, ...) do { \
    g_ran++; \
    if (cond) { printf("  PASS %s\n", __func__); } \
    else { g_failed++; printf("  FAIL %s: ", __func__); printf(__VA_ARGS__); printf("\n"); } \
} while (0)

/* ---- helpers -------------------------------------------------------- */

static JNIEnv* mock_env(void)
{
    static JNIEnv table = NULL;
    if (table == NULL) table = &rd_mock_env_table;
    return &table;
}

static jint call_create(mock_call* c)
{
    return Java_com_termux_terminal_JNI_createSubprocess(
        mock_env(), NULL, c->cmd, c->cwd, c->args, c->envVars, c->pidArray,
        24, 80, 10, 20);
}

static int fd_is_closed(int fd)
{
    errno = 0;
    return fcntl(fd, F_GETFD) == -1 && errno == EBADF;
}

/* The next open() will deterministically reuse this fd number
 * (single-threaded harness, lowest-free-fd allocation). */
static int fd_probe(void)
{
    int fd = open("/dev/null", O_RDONLY);
    int num = fd;
    close(fd);
    return num;
}

static void reap_children(void)
{
    for (int i = 0; i < 100; i++) {
        int status;
        pid_t r = waitpid(-1, &status, WNOHANG);
        if (r <= 0) break;
    }
}

/* Read the master until EOF or EIO (normal end once the child side is
 * gone) — the same semantics the session reader will use. */
static ssize_t read_master_all(int ptm, char* out, size_t outsz)
{
    size_t total = 0;
    for (;;) {
        ssize_t n = read(ptm, out + total, outsz - total);
        if (n > 0) {
            total += (size_t) n;
            if (total >= outsz) break;
            continue;
        }
        if (n == 0) break;            /* EOF */
        if (errno == EIO) break;      /* normal: child side gone */
        if (errno == EINTR) continue;
        return -1;
    }
    return (ssize_t) total;
}

static int lsan_clean(void)
{
#if RD_HAVE_LSAN
    return __lsan_do_recoverable_leak_check() == 0;
#else
    return 1; /* exit-time LSan check still enforces this at process exit */
#endif
}

static void sigprobe_path(char* out, size_t outsz)
{
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof cwd) == NULL) abort();
    int n = snprintf(out, outsz, "%s/sigprobe", cwd);
    if (n < 0 || (size_t) n >= outsz) abort();
}

/* ---- tests ---------------------------------------------------------- */

static void test_release_pairing(void)
{
    mock_reset();
    mock_call c;
    const char* args[] = { "true" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/bin/true", "/", args, 1, env, 1);

    jint ptm = call_create(&c);
    int ok = ptm >= 0;
    if (ok) {
        ok = c.pid_slot > 0;
        waitpid(c.pid_slot, NULL, 0);
        close(ptm);
    }
    int threw = mock.threw;
    int pins_ok = mock_verify_pins() == 0;
    mock_destroy();
    CHECK(ok && !threw && pins_ok, "ptm=%d pid=%d threw=%d pins_bad", (int) ptm, (int) c.pid_slot, threw);
}

static void test_fd_leak_fork_failure(void)
{
    mock_reset();
    int probe = fd_probe();
    mock_call c;
    const char* args[] = { "true" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/bin/true", "/", args, 1, env, 1);

    g_fail_fork = 1;
    jint ptm = call_create(&c);
    g_fail_fork = 0;

    int threw = mock.threw && strstr(mock.last_thrown, "Fork failed") != NULL;
    int closed = fd_is_closed(probe);
    mock_destroy();
    CHECK(ptm < 0 && threw && closed, "ptm=%d threw=%d closed=%d", (int) ptm, threw, closed);
}

static void test_fd_leak_grantpt_failure(void)
{
    mock_reset();
    int probe = fd_probe();
    mock_call c;
    const char* args[] = { "true" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/bin/true", "/", args, 1, env, 1);

    g_fail_grantpt = 1;
    jint ptm = call_create(&c);
    g_fail_grantpt = 0;

    int threw = mock.threw && strstr(mock.last_thrown, "grantpt") != NULL;
    int closed = fd_is_closed(probe);
    mock_destroy();
    CHECK(ptm < 0 && threw && closed, "ptm=%d threw=%d closed=%d", (int) ptm, threw, closed);
}

static void test_fd_leak_critical_failure(void)
{
    mock_reset();
    int probe = fd_probe();
    mock_call c;
    const char* args[] = { "true" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/bin/true", "/", args, 1, env, 1);

    mock.fail_critical = 1;
    jint ptm = call_create(&c);
    mock.fail_critical = 0;

    int threw = mock.threw && strstr(mock.last_thrown, "GetPrimitiveArrayCritical") != NULL;
    int closed = fd_is_closed(probe);
    reap_children(); /* the fork succeeded; the pid was never delivered */
    mock_destroy();
    CHECK(ptm < 0 && threw && closed, "ptm=%d threw=%d closed=%d", (int) ptm, threw, closed);
}

static void test_marshall_leak_argv_get_failure(void)
{
    mock_reset();
    mock_call c;
    const char* args[] = { "true", "x", "y", "z" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/bin/true", "/", args, 4, env, 1);

    /* Fail the 3rd GetStringUTFChars call = argv element 2 ("y"): argv
     * elements 0..1 are already strdup'd by then. */
    mock.fail_string_get_at = 3;
    jint ptm = call_create(&c);
    mock.fail_string_get_at = 0;

    int threw = mock.threw;
    int leak_clean = lsan_clean();
    mock_destroy();
    CHECK(ptm < 0 && threw && leak_clean, "ptm=%d threw=%d lsan_clean=%d", (int) ptm, threw, leak_clean);
}

static void test_marshall_leak_envp_malloc_failure(void)
{
    mock_reset();
    mock_call c;
    const char* args[] = { "true", "x" };
    const char* env[] = { "RD_TEST=1", "B=2", "C=3" };
    mock_call_build(&c, "/bin/true", "/", args, 2, env, 3);

    /* envp array = (3 + 1) pointers: exactly the allocation to fail.
     * argv is fully built at that point — upstream leaks it wholesale. */
    g_fail_malloc_size = (size_t) (3 + 1) * sizeof(char*);
    jint ptm = call_create(&c);
    g_fail_malloc_size = 0;

    int threw = mock.threw;
    int leak_clean = lsan_clean();
    mock_destroy();
    CHECK(ptm < 0 && threw && leak_clean, "ptm=%d threw=%d lsan_clean=%d", (int) ptm, threw, leak_clean);
}

static void test_sigpipe_reset(void)
{
    mock_reset();
    char probe[PATH_MAX];
    sigprobe_path(probe, sizeof probe);

    /* Mimic ART: parent ignores SIGPIPE; SIG_IGN survives execve. */
    struct sigaction ign = { .sa_handler = SIG_IGN };
    struct sigaction old;
    sigaction(SIGPIPE, &ign, NULL);

    mock_call c;
    const char* args[] = { "sigprobe" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, probe, "/", args, 1, env, 1);

    jint ptm = call_create(&c);
    char out[512];
    ssize_t n = -1;
    int exited_ok = 0;
    if (ptm >= 0) {
        n = read_master_all(ptm, out, sizeof out - 1);
        if (n >= 0) out[n] = '\0';
        waitpid(c.pid_slot, NULL, 0);
        close(ptm);
    }
    sigaction(SIGPIPE, &old, NULL); /* restore */

    int saw_dfl = n > 0 && strstr(out, "SIGPIPE=SIG_DFL") != NULL;
    mock_destroy();
    CHECK(saw_dfl, "n=%d out=%.64s", (int) n, n > 0 ? out : "(none)");
    (void) exited_ok;
}

static void test_happy_path_echo(void)
{
    mock_reset();
    mock_call c;
    const char* args[] = { "sh", "-c", "echo ok" };
    const char* env[] = { "RD_TEST=1" };
    mock_call_build(&c, "/bin/sh", "/", args, 3, env, 1);

    jint ptm = call_create(&c);
    char out[512];
    ssize_t n = -1;
    int status = -1;
    if (ptm >= 0) {
        n = read_master_all(ptm, out, sizeof out - 1);
        if (n >= 0) out[n] = '\0';
        waitpid(c.pid_slot, &status, 0);
        close(ptm);
    }
    int echoed = n > 0 && strstr(out, "ok") != NULL;
    int exited0 = WIFEXITED(status) && WEXITSTATUS(status) == 0;
    int pins_ok = mock_verify_pins() == 0;
    mock_destroy();
    CHECK(ptm >= 0 && echoed && exited0 && pins_ok,
          "ptm=%d n=%d exited0=%d pins_ok=%d", (int) ptm, (int) n, exited0, pins_ok);
}

static int count_open_fds(void)
{
    int count = 0;
    DIR* d = opendir("/proc/self/fd");
    if (!d) return -1;
    struct dirent* e;
    while ((e = readdir(d)) != NULL) {
        int fd = atoi(e->d_name);
        if (fd == dirfd(d)) continue;
        count++;
    }
    closedir(d);
    return count;
}

static void test_fd_count_200_cycles(void)
{
    mock_reset();
    int before = count_open_fds();
    int ok = before >= 0;
    for (int i = 0; i < 200 && ok; i++) {
        mock_call c;
        const char* args[] = { "true" };
        const char* env[] = { "RD_TEST=1" };
        mock_call_build(&c, "/bin/true", "/", args, 1, env, 1);
        jint ptm = call_create(&c);
        if (ptm < 0 || c.pid_slot <= 0) { ok = 0; break; }
        /* Reap BEFORE closing the master: closing first races the
         * child's slave open (parent's close can destroy the pty
         * before the child ever opened it). */
        waitpid(c.pid_slot, NULL, 0);
        close(ptm);
        /* The mock's pin/object records are finite (64 pins, 256
         * objects): reset per cycle — each cycle is a fresh session. */
        mock_destroy();
    }
    int after = count_open_fds();
    mock_destroy();
    CHECK(ok && after == before, "before=%d after=%d", before, after);
}

/* ---- main ----------------------------------------------------------- */

static void on_alarm(int sig)
{
    (void) sig;
    const char msg[] = "TIMEOUT: test harness watchdog fired\n";
    write(2, msg, sizeof msg - 1);
    _exit(99);
}

int main(void)
{
    /* Unbuffered: LSan's abort path _exits without flushing, which
     * would swallow the per-test FAIL lines we most need to see. */
    setvbuf(stdout, NULL, _IONBF, 0);

    signal(SIGALRM, on_alarm);
    alarm(120); /* watchdog: no single read may hang the suite */

    if (!lsan_clean()) {
        printf("BASELINE LSan violation — environment problem, aborting\n");
        return 2;
    }

    test_release_pairing();
    test_fd_leak_fork_failure();
    test_fd_leak_grantpt_failure();
    test_fd_leak_critical_failure();
    test_marshall_leak_argv_get_failure();
    test_marshall_leak_envp_malloc_failure();
    test_sigpipe_reset();
    test_happy_path_echo();
    test_fd_count_200_cycles();

    alarm(0);
    printf("\n%d checks, %d failures\n", g_ran, g_failed);
    /* exit-time LSan check runs here too (belt and braces) */
    return g_failed == 0 ? 0 : 1;
}
