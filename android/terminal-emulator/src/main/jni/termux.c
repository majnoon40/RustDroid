/*
 * RustDroid patch set on top of upstream termux.c
 * (termux-app @ 3b66f8799635a4dba4a206563048ff0e6792c487; the file was
 * unchanged upstream since 438cd73). Divergences, file-by-file, are
 * recorded in THIRD_PARTY.md. Summary of this patch set (plan §4.3–§4.5):
 *
 *  1. ReleaseStringUTFChars pairing fixed: each pinned buffer is released
 *     against the jstring it actually came from (upstream released
 *     cmd_cwd against cmd).
 *  2. PTY master fd leak fixed on every failure path (fork() failure,
 *     grantpt/unlockpt/ptsname_r failure, GetPrimitiveArrayCritical
 *     failure) via single-exit cleanup.
 *  3. argv/envp early-return leaks fixed: the JNI wrapper has ONE exit;
 *     every marshalling failure frees everything already built.
 *  4. fork→exec window restructured for async-signal-safety: ALL
 *     string/array/fd-enumeration work happens pre-fork in the parent;
 *     the child path (rd_child_exec, between the RD_CHILD_PATH markers)
 *     calls only whitelisted, async-signal-safe functions. NO
 *     close_range(2) — Android's seccomp allowlist may kill the child
 *     with SIGSYS; the pre-scanned close-list is the only fd cleanup.
 *     Do not re-add close_range without the forked-probe design in
 *     plan §4.4.
 *  5. Child resets signal dispositions to SIG_DFL for signals 1..64
 *     before execve (SIG_IGN survives execve; ART ignores SIGPIPE).
 *  6. execve with a parent-resolved absolute path replaces execvp (PATH
 *     search is not async-signal-safe; it now happens pre-fork).
 *  7. sendSignalToProcessGroup(pgid, sig) added (killpg(2)) for the
 *     RustDroid session controller (plan §4.5).
 *  8. Test seams RD_FORK()/RD_GRANTPT()/RD_MALLOC() — default to the
 *     libc calls; the host harness overrides them with -D to inject
 *     failures. Production builds are unaffected.
 *
 * Ownership table for the JNI wrapper (plan §4.3 defect 3):
 *   - argv[]: RD_MALLOC'd array of (size+1) pointers; argv[0..size-1]
 *     are strdup'd strings, argv[size] = NULL. Freed at the single exit.
 *   - envp[]: same shape as argv[].
 *   - cmd_utf8 / cmd_cwd: JNI-pinned via GetStringUTFChars; released at
 *     the single exit against the SAME jstring they came from.
 *   - close_list: RD_MALLOC'd int array of parent-side fds (excluding
 *     0/1/2, the scan's own dirfd, and ptm); owned and freed by the
 *     parent after fork; the child only READS it (never frees).
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))

/* Test seams (plan §6.2). The host harness compiles this file with
 * -DRD_FORK()=rd_fork() -DRD_GRANTPT(fd)=rd_grantpt(fd)
 * -DRD_MALLOC(n)=rd_malloc(n) to inject deterministic failures. */
#ifndef RD_FORK
#define RD_FORK() fork()
#endif
#ifndef RD_GRANTPT
#define RD_GRANTPT(fd) grantpt(fd)
#endif
#ifndef RD_MALLOC
#define RD_MALLOC(n) malloc(n)
#endif

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

/* RD_CHILD_PATH_BEGIN
 *
 * Async-signal-safe zone: everything between these markers runs in the
 * forked child between fork() and execve(). Only whitelisted calls
 * (sigprocmask, sigaction, close, setsid, open, dup2, chdir, execve,
 * write, _exit) appear here. The CI whitelist-grep script scans exactly
 * this region — see host-tests/check_child_path.sh.
 *
 * Writes "what failed: errno N" to fd 2 with a hand-rolled decimal
 * conversion (no stdio, no snprintf — neither is async-signal-safe).
 */
static void rd_write_errno(char const* what)
{
    char buf[64];
    size_t n = 0;
    while (what[n] != '\0' && n < 32) { buf[n] = what[n]; n++; }
    buf[n++] = ':'; buf[n++] = ' ';
    unsigned int e = (unsigned int) errno;
    char digits[10];
    size_t d = 0;
    do { digits[d++] = (char) ('0' + (e % 10)); e /= 10; } while (e > 0);
    while (d > 0) buf[n++] = digits[--d];
    buf[n++] = '\r'; buf[n++] = '\n';
    (void) write(2, buf, n);
}

/*
 * The child path. Parent-prepared inputs only: devname (pts path),
 * cwd, argv, envp (wholesale environment), cmd (absolute path), and
 * the close-list scanned by the parent before fork. Never returns.
 */
static void rd_child_exec(int ptm,
        char const* devname,
        char const* cwd,
        char* const argv[],
        char* const envp[],
        char const* cmd,
        int const* close_list,
        size_t close_count)
{
    /* Unblock everything (explicit empty-mask form). */
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);

    /* Reset every disposition to SIG_DFL (signals 1..64). SIGKILL and
     * SIGSTOP reject harmlessly. SIG_IGN survives execve — ART ignores
     * SIGPIPE, and the shell's pipelines misbehave without this loop
     * (yes | head -1 would never terminate). */
    for (int i = 1; i <= 64; i++) {
        struct sigaction dfl = { .sa_handler = SIG_DFL };
        (void) sigaction(i, &dfl, NULL);
    }

    close(ptm);
    setsid();

    /* No O_NOCTTY: the slave becomes the controlling terminal of the
     * new session. */
    int pts = open(devname, O_RDWR);
    if (pts < 0) {
        rd_write_errno("open(pts)");
        _exit(127);
    }

    dup2(pts, 0);
    dup2(pts, 1);
    dup2(pts, 2);
    if (pts > 2) close(pts);

    /* Close leftover fds from the parent's PRE-SCANNED list only. A
     * kernel bulk-close facility is deliberately NOT used: if the
     * device's seccomp policy does not permit it, the child receives
     * SIGSYS and dies before exec — a device-dependent, silent
     * blank-terminal failure. Closing one descriptor at a time is
     * unconditionally allowed. */
    for (size_t i = 0; i < close_count; i++) {
        close(close_list[i]);
    }

    if (chdir(cwd) != 0) {
        /* Report and continue to exec — matches upstream behavior. */
        rd_write_errno("chdir");
    }

    /* The environment is passed to execve wholesale; no per-variable
     * environment mutation happens here (none of it is safe in this
     * window). */
    execve(cmd, (char* const*) argv, (char* const*) envp);

    rd_write_errno("execve");
    _exit(127);
}
/* RD_CHILD_PATH_END */

/* Scan /proc/self/fd in the PARENT (opendir/readdir are fine here; in
 * the forked child they would allocate against a dead allocator).
 * Returns a RD_MALLOC'd int array (caller frees in the parent) of fds
 * for the child to close, excluding 0/1/2, the scan's own directory
 * fd, and ptm. [always_close] is GUARANTEED to be in the returned
 * list (appended if the scan missed it — it is the pts probe fd, and
 * a child that kept it would never deliver EIO-on-exit to the
 * session reader). On opendir failure returns a list containing just
 * [always_close] (tolerated, matching upstream's opendir tolerance);
 * on allocation failure returns NULL with *count = 0 — callers must
 * then fail the session loudly (a child without the fd discipline is
 * a broken session, not a degraded one).
 * Residual: regular fds beyond the 256-entry scan window are dropped
 * (documented in plan §4.4 — no worse than the pre-scan residual).
 */
static int* scan_fds_to_close(size_t* count, int ptm, int always_close)
{
    *count = 0;
    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir == NULL) {
        int* only = (int*) RD_MALLOC(sizeof(int));
        if (only == NULL) return NULL;
        only[0] = always_close;
        *count = 1;
        return only;
    }

    int self_dir_fd = dirfd(self_dir);
    struct dirent* entry;
    int list[256];
    size_t n = 0;
    while ((entry = readdir(self_dir)) != NULL) {
        int fd = atoi(entry->d_name);
        if (fd > 2 && fd != self_dir_fd && fd != ptm && fd != always_close && n < 256) {
            list[n++] = fd;
        }
    }
    closedir(self_dir);

    size_t total = n + 1; /* + always_close, appended below */
    int* result = (int*) RD_MALLOC(total * sizeof(int));
    if (result == NULL) return NULL;
    memcpy(result, list, n * sizeof(int));
    result[n] = always_close;
    *count = total;
    return result;
}

/* Resolve the executable to an absolute path in the PARENT (string
 * work; the child execve()s the result). Absolute paths are used
 * as-is; paths containing '/' are anchored at cwd; bare names are
 * searched in PATH. Parent-side only: getenv/snprintf/strdup/access
 * are fine here. Returns 0 on success. */
static int resolve_executable(char const* cmd, char const* cwd, char* out, size_t outsz)
{
    if (cmd[0] == '/') {
        if (snprintf(out, outsz, "%s", cmd) >= (int) outsz) return -1;
        return 0;
    }
    if (strchr(cmd, '/') != NULL) {
        if (snprintf(out, outsz, "%s/%s", cwd, cmd) >= (int) outsz) return -1;
        return 0;
    }
    char const* path = getenv("PATH");
    if (path == NULL) return -1;
    char* path_copy = strdup(path);
    if (path_copy == NULL) return -1;
    char* save = NULL;
    for (char* dir = strtok_r(path_copy, ":", &save);
         dir != NULL; dir = strtok_r(NULL, ":", &save)) {
        if (snprintf(out, outsz, "%s/%s", dir, cmd) >= (int) outsz) continue;
        if (access(out, X_OK) == 0) {
            free(path_copy);
            return 0;
        }
    }
    free(path_copy);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char* const envp[],
        int* pProcessId,
        int* pPtsDevice,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (RD_GRANTPT(ptm) || unlockpt(ptm) ||
            ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm); /* RustDroid fix: fd leak on the grantpt/unlockpt/ptsname_r path */
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    /* Compute the pts device number in the PARENT (plan §5.2): fstat
     * the slave and take st_rdev, so the session controller can
     * union-discover by tty_nr (/proc/<pid>/stat field 7 keeps the
     * controlling-terminal device number even after setsid while the
     * tty is still held). The probe is opened O_NOCTTY — the parent is
     * not a session leader, so no controlling-terminal acquisition is
     * possible anyway — and held open ACROSS the fork (closing it
     * before the child opens its own slave would put the master into
     * the latched EIO state and lose output: the slave fd count must
     * never hit zero in that window). The parent closes its copy
     * right after fork; the child closes the inherited copy via the
     * pre-scanned close-list — which is why the probe is guaranteed a
     * slot in that list — after opening its own slave. */
    int pts_probe = open(devname, O_RDONLY | O_NOCTTY);
    if (pts_probe < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot open the pts slave for fstat");
    }
    struct stat pts_stat;
    if (fstat(pts_probe, &pts_stat) != 0) {
        close(pts_probe);
        close(ptm);
        return throw_runtime_exception(env, "Cannot fstat the pts slave");
    }
    *pPtsDevice = (int) pts_stat.st_rdev;

    // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /** Set initial winsize. */
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns, .ws_xpixel = (unsigned short) (columns * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height)};
    ioctl(ptm, TIOCSWINSZ, &sz);

    /* Pre-fork (parent): scan the fds to close in the child (the pts
     * probe is guaranteed a slot — see its comment). */
    size_t close_count = 0;
    int* close_list = scan_fds_to_close(&close_count, ptm, pts_probe);
    if (close_list == NULL) {
        /* Allocation failure is NOT tolerated here: without the list,
         * the child would leak the probe (breaking EIO-on-exit session
         * semantics) and every other parent fd into the exec'd shell. */
        close(pts_probe);
        close(ptm);
        return throw_runtime_exception(env, "Cannot allocate the child fd close-list");
    }

    pid_t pid = RD_FORK();
    if (pid < 0) {
        free(close_list);
        close(pts_probe); /* parent's copy; fork failed, no child holds one */
        close(ptm); /* RustDroid fix: fd leak on the fork-failure path */
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        free(close_list);
        close(pts_probe); /* the child holds its own inherited copy until its close-list runs */
        *pProcessId = (int) pid;
        return ptm;
    } else {
        /* The child keeps the inherited probe fd open until its own
         * slave open (inside rd_child_exec) precedes the close-list
         * loop — the slave count never reaches zero in the window. */
        rd_child_exec(ptm, devname, cwd, argv, envp, cmd, close_list, close_count);
        /* never returns */
        _exit(127);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jintArray ptsDeviceArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    char resolved_cmd[PATH_MAX];
    char** argv = NULL;
    char** envp = NULL;
    char const* cmd_utf8 = NULL;
    char const* cmd_cwd = NULL;
    int ptm = -1;
    int procId = 0;
    int ptsDevice = 0;

    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    if (size > 0) {
        argv = (char**) RD_MALLOC((size + 1) * sizeof(char*));
        if (!argv) { throw_runtime_exception(env, "Couldn't allocate argv array"); goto cleanup; }
        /* Zero immediately: the single-exit cleanup walks to the first
         * NULL, so every slot must be NULL before anything is built. */
        memset(argv, 0, (size_t) (size + 1) * sizeof(char*));
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* arg_utf8 = (*env)->GetStringUTFChars(env, arg_java_string, NULL);
            if (!arg_utf8) { throw_runtime_exception(env, "GetStringUTFChars() failed for argv"); goto cleanup; }
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
            if (!argv[i]) { throw_runtime_exception(env, "strdup() failed for argv"); goto cleanup; }
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    if (size > 0) {
        envp = (char**) RD_MALLOC((size + 1) * sizeof(char *));
        if (!envp) { throw_runtime_exception(env, "malloc() for envp array failed"); goto cleanup; }
        memset(envp, 0, (size_t) (size + 1) * sizeof(char*));
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
            char const* env_utf8 = (*env)->GetStringUTFChars(env, env_java_string, 0);
            if (!env_utf8) { throw_runtime_exception(env, "GetStringUTFChars() failed for env"); goto cleanup; }
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
            if (!envp[i]) { throw_runtime_exception(env, "strdup() failed for env"); goto cleanup; }
        }
        envp[size] = NULL;
    }

    cmd_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (!cmd_cwd) { throw_runtime_exception(env, "GetStringUTFChars() failed for cwd"); goto cleanup; }
    cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (!cmd_utf8) { throw_runtime_exception(env, "GetStringUTFChars() failed for cmd"); goto cleanup; }

    /* Parent-side executable resolution: the child execve()s an
     * absolute path (string work happens here, never in the child). */
    if (resolve_executable(cmd_utf8, cmd_cwd, resolved_cmd, sizeof resolved_cmd) != 0) {
        throw_runtime_exception(env, "Cannot resolve executable (absolute, cwd-anchored, or PATH search failed)");
        goto cleanup;
    }

    ptm = create_subprocess(env, resolved_cmd, cmd_cwd, argv, envp, &procId, &ptsDevice, rows, columns, cell_width, cell_height);
    if (ptm < 0) goto cleanup; /* create_subprocess already threw and cleaned up its own fds */

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) {
        close(ptm); /* RustDroid fix: fd leak on the GetPrimitiveArrayCritical failure path */
        ptm = -1;
        throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(processIdArray, &isCopy) failed");
        goto cleanup;
    }

    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    /* The pts device number (st_rdev of the slave, plan §5.2), written
     * in a SEPARATE critical section — two simultaneous critical
     * sections are not supported by the JNI contract. */
    int* pPtsDev = (int*) (*env)->GetPrimitiveArrayCritical(env, ptsDeviceArray, NULL);
    if (!pPtsDev) {
        close(ptm); /* same fd-leak discipline as the processId critical failure */
        ptm = -1;
        throw_runtime_exception(env, "JNI call GetPrimitiveArrayCritical(ptsDeviceArray) failed");
        goto cleanup;
    }
    *pPtsDev = ptsDevice;
    (*env)->ReleasePrimitiveArrayCritical(env, ptsDeviceArray, pPtsDev, 0);

cleanup:
    /* RustDroid fix (defects 1 and 3): ONE exit — every marshalling
     * failure frees what was built and releases every pin, each against
     * the jstring its chars actually came from. */
    if (argv) {
        for (char** tmp = argv; *tmp; ++tmp) free(*tmp);
        free(argv);
    }
    if (envp) {
        for (char** tmp = envp; *tmp; ++tmp) free(*tmp);
        free(envp);
    }
    if (cmd_utf8) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    if (cmd_cwd) (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);

    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols, .ws_xpixel = (unsigned short) (cols * cell_width), .ws_ypixel = (unsigned short) (rows * cell_height) };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        // Should never happen - waitpid(2) says "One of the first three macros will evaluate to a non-zero (true) value".
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}

/* RustDroid addition (plan §4.5, recorded in THIRD_PARTY.md): killpg(2)
 * for the session controller. Thin and unconditional — identity
 * validation before every signal stays in Kotlin, where the /proc
 * discipline lives; Kotlin call sites wrap this in runCatching so an
 * ESRCH/EPERM race can never crash a teardown. This is parent-side
 * code: snprintf/strerror are fine here (the async-signal-safety
 * constraint applies only to the fork→exec window in rd_child_exec). */
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_sendSignalToProcessGroup(JNIEnv* env, jclass TERMUX_UNUSED(clazz), jint pgid, jint sig)
{
    if (killpg((pid_t) pgid, (int) sig) != 0) {
        char msg[128];
        snprintf(msg, sizeof msg, "killpg(%d, %d) failed: %s", (int) pgid, (int) sig, strerror(errno));
        throw_runtime_exception(env, msg);
    }
}
