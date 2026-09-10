package com.termux.terminal;

import android.system.ErrnoException;
import android.system.Os;

/**
 * RustDroid addition (plan §4.5 / §5.3): a public facade over the
 * package-private [JNI] bridge (and per-PID kill) for the Kotlin session
 * layer — dev.rustdroid.ide cannot see the package-private members, and
 * widening JNI itself would diverge the vendored file more than this
 * one small addition.
 *
 * Both methods THROW on failure (ESRCH/EPERM races etc.) by design: the
 * Kotlin controller wraps every call in runCatching so a teardown race
 * can never crash (review condition 8).
 */
public final class JNIHelper {

    private JNIHelper() {
    }

    /** kill(2) a single process by PID. */
    public static void signal(long pid, int sig) {
        try {
            Os.kill((int) pid, sig);
        } catch (ErrnoException e) {
            throw new RuntimeException("kill(" + pid + ", " + sig + ") failed: " + e.getMessage(), e);
        }
    }

    /** killpg(2) a process group — the native sendSignalToProcessGroup. */
    public static void sendSignalToGroup(long pgid, int sig) {
        JNI.sendSignalToProcessGroup((int) pgid, sig);
    }
}
