package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor;

    /**
     * The pts slave's device number (fstat st_rdev, computed in the parent at PTY creation). RustDroid
     * addition (plan §5.2): consumed by the session controller's union discovery — /proc stat field 7
     * (tty_nr) carries this number even after setsid while the tty is still held. 0 if not started.
     */
    private int mPtsDevice;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    /**
     * RustDroid additions (plan §5.3): the reader's wakeup pipe — teardown
     * writes one byte to [mReaderWakeWrite] so the poll()-ing reader exits
     * without closing anything under it — plus the reader thread reference
     * (for a bounded join) and the master-close ownership flag.
     */
    private FileDescriptor mReaderWakeRead;
    private volatile FileDescriptor mReaderWakeWrite;
    private volatile Thread mReaderThread;
    private boolean mMasterClosed;

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            // External bug report (Critical #2, confirmed): a layout pass
            // can call updateSize() after closeMasterFd() has already run
            // (e.g. a rotation racing session teardown) — without this
            // guard, setPtyWindowSize() would target an fd number the
            // kernel may already have reused for something unrelated.
            synchronized (this) {
                if (mMasterClosed) return;
            }
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);

        int[] processId = new int[1];
        int[] ptsDevice = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, ptsDevice, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        mPtsDevice = ptsDevice[0];
        mClient.setTerminalShellPid(this, mShellPid);

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        try {
            FileDescriptor[] wakePipe = Os.pipe();
            mReaderWakeRead = wakePipe[0];
            mReaderWakeWrite = wakePipe[1];
        } catch (ErrnoException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "pipe() for the reader wakeup failed", e);
        }

        mReaderThread = new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                // RustDroid restructure (plan §5.3 / review P1-4): the
                // reader polls {master fd, wakeup pipe} instead of blocking
                // in read(2) — teardown wakes it through the pipe, joins it,
                // and only then is the master closed (closing under a
                // blocked read is the fd-reuse use-after-close). EIO after
                // the child side is gone is NORMAL session end, never an
                // error (Linux pty semantics).
                final byte[] buffer = new byte[4096];
                final byte[] drain = new byte[1];
                final StructPollfd[] watched = new StructPollfd[2];
                watched[0] = new StructPollfd();
                watched[0].fd = terminalFileDescriptorWrapped;
                watched[0].events = (short) OsConstants.POLLIN;
                watched[1] = new StructPollfd();
                watched[1].fd = mReaderWakeRead;
                watched[1].events = (short) OsConstants.POLLIN;
                while (true) {
                    try {
                        Os.poll(watched, -1);
                    } catch (Exception e) {
                        break;
                    }
                    if (watched[1].revents != 0) {
                        // teardown wakeup: drain and exit without touching
                        // the master fd — the I/O owner closes it after the join
                        try { Os.read(mReaderWakeRead, drain, 0, 1); } catch (Exception ignored) { }
                        break;
                    }
                    if (watched[0].revents != 0) {
                        try {
                            int read = Os.read(terminalFileDescriptorWrapped, buffer, 0, buffer.length);
                            if (read > 0) {
                                if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                                mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                            } else {
                                return; // EOF: child side gone — normal session end
                            }
                        } catch (ErrnoException e) {
                            if (e.errno == OsConstants.EINTR) continue;
                            return; // EIO and friends: normal session end, never an error
                        } catch (Exception e) {
                            return;
                        }
                    }
                }
            }
        };
        mReaderThread.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

    }

    /**
     * RustDroid addition (plan §5.3): wake the reader thread so it exits
     * its poll loop (the teardown's "stop the reader" step). Idempotent
     * and safe after cleanup — the pipe may already be closed, which is
     * fine: that only means the reader already exited via EIO.
     */
    public void requestReaderStop() {
        try {
            FileDescriptor wake = mReaderWakeWrite;
            if (wake != null) Os.write(wake, new byte[]{1}, 0, 1);
        } catch (Exception ignored) {
        }
    }

    /**
     * RustDroid addition (plan §5.3): join the reader with a bounded
     * timeout. True when the reader thread has exited.
     */
    public boolean joinReader(long timeoutMs) {
        Thread reader = mReaderThread;
        if (reader == null) return true;
        try {
            reader.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !reader.isAlive();
    }

    /**
     * RustDroid addition (plan §5.3): the master-fd close, performed by
     * the session's I/O owner AFTER the reader is joined — never from
     * the main thread while a reader could still be blocked on the fd
     * (review P1-4, the fd-reuse use-after-close). Idempotent. Closing
     * the master is also the kernel-delivered graceful hangup: the
     * terminal's foreground process group receives SIGHUP.
     */
    public void closeMasterFd() {
        int fd;
        synchronized (this) {
            if (mMasterClosed || mTerminalFileDescriptor <= 0) return;
            mMasterClosed = true;
            fd = mTerminalFileDescriptor;
        }
        // External bug report (Critical #2, confirmed): cleanupResources()
        // used to close the wake pipe unconditionally on the exit callback,
        // which can race the reader thread still being watched on it in
        // Os.poll (POLLNVAL, then a read on a possibly-already-reused fd
        // number) — and separately, the writer thread and updateSize() kept
        // using mTerminalFileDescriptor after this method ran, on a session
        // killed by teardown BEFORE the shell exited naturally (cleanup
        // Resources() only fires on natural exit, so its own IOQueue.close()
        // never covered that path at all).
        //
        // Fix: this method is the SOLE owner of every fd this session holds,
        // called by the I/O owner strictly after requestReaderStop() ->
        // joinReader() have completed (that contract is the caller's, not
        // enforced here). Closing the queue first wakes the writer thread's
        // blocked read() immediately so it stops using the fd; JNI.close(fd)
        // is then the real close; the wake pipe closes last since nothing
        // needs it anymore once the reader is already known-joined.
        //
        // Residual, stated rather than hidden: the writer thread's own
        // try-with-resources FileOutputStream (wrapping this same fd number
        // via reflection, not a fd it opened itself) still runs its own
        // close() when its loop notices the queue closed and returns — a
        // narrow scheduling window exists between JNI.close(fd) here and
        // that thread waking up and closing its wrapped stream. This exact
        // window already exists today on the natural-exit path (cleanup
        // Resources() closes the same queue there); this change extends the
        // same, already-accepted pattern to the explicit-teardown path
        // rather than introducing a new one.
        mTerminalToProcessIOQueue.close();
        JNI.close(fd);
        try { if (mReaderWakeRead != null) Os.close(mReaderWakeRead); } catch (Exception ignored) { }
        try { if (mReaderWakeWrite != null) Os.close(mReaderWakeWrite); } catch (Exception ignored) { }
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams.
        // RustDroid restructure (plan §5.3, hardened by external bug report
        // Critical #2): neither the master fd NOR the wake pipe are closed
        // here anymore. Upstream closed the master from the main thread
        // while the reader could still be watching it (the fd-reuse
        // use-after-close, review P1-4) — and this method's OWN wake-pipe
        // closes had the identical bug: cleanupResources() runs on the exit
        // callback, asynchronously with respect to whatever the session's
        // I/O owner is doing, so it could close the wake pipe while the
        // reader was still polling it. closeMasterFd() is now the sole
        // owner of every fd this session holds (master, wake pipe, and the
        // writer queue), called by the owner strictly after
        // requestReaderStop() -> joinReader() have completed.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** The pts slave's device number for the controller's union discovery (RustDroid addition, plan §5.2). */
    public int getPtsDevice() {
        return mPtsDevice;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            // RustDroid fix (v0.1.8): upstream called System.exit(1) here —
            // a SILENT instant process kill with no crash dialog and no
            // reportable trace. The app's crash recorder can never see a
            // System.exit. Thrown instead: the failure stays fatal for the
            // session (the fd genuinely cannot be wrapped) but is caught,
            // persisted and surfaced like every other crash.
            throw new RuntimeException("Cannot wrap fd " + fileDescriptor
                    + " — java.io.FileDescriptor#descriptor reflection failed", e);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                String exitDescription = "\r\n[Process completed";
                if (exitCode > 0) {
                    // Non-zero process exit.
                    exitDescription += " (code " + exitCode + ")";
                } else if (exitCode < 0) {
                    // Negated signal.
                    exitDescription += " (signal " + (-exitCode) + ")";
                }
                exitDescription += " - press Enter]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
