package com.termux.terminal;

/**
 * Native methods for creating and managing pseudoterminal subprocesses. C code is in jni/termux.c.
 */
final class JNI {

    static {
        // RustDroid divergence (plan §4.2): library renamed from "termux"
        // to "rustdroidpty"; the Java package and JNI symbol names are
        // unchanged.
        System.loadLibrary("rustdroidpty");
    }

    /**
     * Create a subprocess. Differs from {@link ProcessBuilder} in that a pseudoterminal is used to communicate with the
     * subprocess.
     * <p/>
     * Callers are responsible for calling {@link #close(int)} on the returned file descriptor.
     *
     * @param cmd       The command to execute
     * @param cwd       The current working directory for the executed command
     * @param args      An array of arguments to the command
     * @param envVars   An array of strings of the form "VAR=value" to be added to the environment of the process
     * @param processId A one-element array to which the process ID of the started process will be written.
     * @param ptsDevice A one-element array to which the pts slave's device number (fstat st_rdev, computed in the
     *                  parent at PTY creation) will be written. RustDroid addition (plan §5.2): the session
     *                  controller unions session-, descendant-, and tty-nr-keyed discovery, and /proc stat field 7
     *                  (tty_nr) carries this device number even after setsid while the tty is held.
     * @return the file descriptor resulting from opening /dev/ptmx master device. The sub process will have opened the
     * slave device counterpart (/dev/pts/$N) and have it as stdint, stdout and stderr.
     */
    public static native int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int[] ptsDevice, int rows, int columns, int cellWidth, int cellHeight);

    /** Set the window size for a given pty, which allows connected programs to learn how large their screen is. */
    public static native void setPtyWindowSize(int fd, int rows, int cols, int cellWidth, int cellHeight);

    /**
     * Causes the calling thread to wait for the process associated with the receiver to finish executing.
     *
     * @return if >= 0, the exit status of the process. If < 0, the signal causing the process to stop negated.
     */
    public static native int waitFor(int processId);

    /** Close a file descriptor through the close(2) system call. */
    public static native void close(int fileDescriptor);

    /**
     * RustDroid addition (plan §4.5): send a signal to every process in the
     * process group {@code pgid} via {@code killpg(2)}. Throws a RuntimeException
     * on failure (e.g. ESRCH if the group no longer exists) — callers wrap this
     * in runCatching so a teardown race can never crash.
     */
    public static native void sendSignalToProcessGroup(int pgid, int sig);

}
