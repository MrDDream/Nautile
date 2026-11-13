package com.termux.terminal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;
    private static final int MSG_REMOTE_EXITED = 5;

    /**
     * Nautile: a non-process I/O peer (e.g. an SSH channel). Output from the peer is pushed with
     * {@link #feed(byte[], int, int)}; the end of the peer is reported with {@link #remoteExited(String)}.
     */
    public interface Backend {
        /** Called on the main thread once the emulator size is known. */
        void start(int columns, int rows);
        /** Called on a dedicated writer thread; may block. */
        void write(byte[] data, int offset, int count) throws IOException;
        /** Called on the main thread. */
        void resize(int columns, int rows);
        /** Called on the main thread when the user ends the session. */
        void close();
    }

    private final Backend mBackend;

    /**
     * Nautile: the UI thread, where the emulator runs and the client is called
     * (Android's main looper, the desktop's event thread). Set once by the app at startup.
     */
    private static volatile Executor sMainThread;

    /** Nautile: starts local shells (platform code). Set once by the app at startup. */
    private static volatile Pty.Factory sPtyFactory;

    public static void setMainThread(Executor mainThread) { sMainThread = mainThread; }

    public static void setPtyFactory(Pty.Factory factory) { sPtyFactory = factory; }

    private static Executor mainThread() {
        Executor e = sMainThread;
        if (e == null) throw new IllegalStateException("TerminalSession.setMainThread() was not called");
        return e;
    }

    /** Nautile: observes what the user types (main thread), e.g. for command history. */
    public interface InputListener {
        void onInput(byte[] data, int offset, int count);
    }

    public InputListener mInputListener;
    private volatile boolean mBackendRunning;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the pseudo-terminal or the backend.
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

    /** The pseudo-terminal of a local shell (null for a backend session or before the start). */
    private Pty mPty;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final MainThreadHandler mMainThreadHandler = new MainThreadHandler();

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
        this.mBackend = null;
    }

    /** Nautile: a session whose I/O is provided by {@code backend} instead of a subprocess. */
    public TerminalSession(Backend backend, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = null;
        this.mCwd = null;
        this.mArgs = null;
        this.mEnv = null;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mBackend = backend;
        this.mBackendRunning = true;
    }

    /** Nautile: push bytes received from the backend. Safe to call from any thread. */
    public void feed(byte[] data, int offset, int count) {
        if (!mProcessToTerminalIOQueue.write(data, offset, count)) return;
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** Nautile: report that the backend ended. Safe to call from any thread. */
    public void remoteExited(String message) {
        mMainThreadHandler.sendMessage(MSG_REMOTE_EXITED, message);
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
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            if (mBackend != null) mBackend.resize(columns, rows);
            else if (mPty != null) mPty.setWindowSize(columns, rows);
            mEmulator.resize(columns, rows);
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
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);

        if (mBackend != null) {
            new Thread("TermSessionBackendWriter") {
                @Override
                public void run() {
                    final byte[] buffer = new byte[4096];
                    try {
                        while (true) {
                            int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                            if (bytesToWrite == -1) return;
                            mBackend.write(buffer, 0, bytesToWrite);
                        }
                    } catch (IOException e) {
                        // Backend gone; remoteExited() reports it.
                    }
                }
            }.start();
            mBackend.start(columns, rows);
            return;
        }

        Pty.Factory factory = sPtyFactory;
        if (factory == null) throw new IllegalStateException("TerminalSession.setPtyFactory() was not called");
        try {
            mPty = factory.spawn(mShellPath, mCwd, mArgs, mEnv, columns, rows);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Failed starting " + mShellPath, e);
            mShellPid = -1;
            byte[] bytes = ("[" + e.getMessage() + "]").getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytes, bytes.length);
            mClient.onSessionFinished(this);
            return;
        }
        mShellPid = mPty.getPid();
        final Pty pty = mPty;

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = pty.getInputStream()) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (OutputStream termOut = pty.getOutputStream()) {
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
                int processExitCode;
                try {
                    processExitCode = pty.waitFor();
                } catch (InterruptedException e) {
                    processExitCode = -9;
                }
                mMainThreadHandler.sendMessage(MSG_PROCESS_EXITED, processExitCode);
            }
        }.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mInputListener != null) mInputListener.onInput(data, offset, count);
        if (mBackend != null ? mBackendRunning : mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
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
        if (mBackend != null) {
            if (mBackendRunning) mBackend.close();
            return;
        }
        if (isRunning() && mPty != null) mPty.kill();
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        if (mBackend == null && mPty != null) mPty.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        if (mBackend != null) return mBackendRunning;
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

    /** Nautile: what Android's Handler did, on any platform's UI thread. */
    final class MainThreadHandler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        void sendEmptyMessage(int what) {
            sendMessage(what, null);
        }

        void sendMessage(int what, Object obj) {
            mainThread().execute(() -> handleMessage(what, obj));
        }

        void handleMessage(int what, Object obj) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (what == MSG_REMOTE_EXITED) {
                if (!mBackendRunning) return;
                mBackendRunning = false;
                cleanupResources(0);
                if (mEmulator != null) {
                    byte[] bytes = ("\r\n[" + obj + "]").getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytes, bytes.length);
                    notifyScreenUpdate();
                }
                mClient.onSessionFinished(TerminalSession.this);
                return;
            }

            if (what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) obj;
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
