/*
 * SPDX-License-Identifier: MIT
 *
 * shims/android_shims.c — Bionic compatibility shims for rustc on Android host.
 *
 * Compiled into libandroid_shims.a (static) by build.sh and linked into
 * stage2 rustc/cargo via -landroid_shims. All functions here are fallbacks
 * for symbols that glibc has but Bionic lacks (or exposes differently).
 *
 * Verified-empirical (smoke build, 2026-08-31):
 *  - Bionic at API 24 does NOT declare syncfs() in <unistd.h>. Bionic added
 *    the declaration in API 30 (Android 11). The kernel syscall __NR_syncfs
 *    IS available since API 21 via <sys/syscall.h>.
 *  - Therefore we always emit a weak syncfs() symbol: if Bionic provides
 *    one (API 30+), Bionic's definition wins and our weak def is dropped.
 *    If Bionic doesn't (API < 30), our weak def is linked in and dispatches
 *    to the syscall directly (or falls back to sync() if the kernel rejects
 *    the syscall with ENOSYS).
 *
 * Theoretical:
 *  - backtrace()/backtrace_symbols() are NOT in Bionic at any API level.
 *    Rust std uses libunwind-based backtracing via the `backtrace` crate;
 *    this shim does NOT provide backtrace symbols — see
 *    patches/0002-std-no-libexecinfo-on-android.patch instead.
 */

#include <errno.h>
#include <unistd.h>
#include <sys/syscall.h>

/* ---- syncfs() weak fallback ----------------------------------------------
 *
 * Provide as a WEAK symbol so:
 *   - On Bionic >= API 30, Bionic's syncfs() wins (our weak def is dropped).
 *   - On Bionic < API 30, our weak def is linked in: dispatch to the kernel
 *     syscall. If the kernel rejects (ENOSYS — possible on older Android
 *     kernels), fall back to sync() which flushes all filesystems
 *     (semantically a superset of syncfs(fd); correct but coarse).
 */
__attribute__((weak, visibility("default")))
int syncfs(int fd) {
#ifdef __NR_syncfs
    long ret = syscall(__NR_syncfs, fd);
    if (ret == -1 && errno == ENOSYS) {
        sync();
        return 0;
    }
    return (int)ret;
#else
    /* No syscall number known — fall back to sync() unconditionally. */
    (void)fd;
    sync();
    return 0;
#endif
}

/* ---- end of file --------------------------------------------------------- */
