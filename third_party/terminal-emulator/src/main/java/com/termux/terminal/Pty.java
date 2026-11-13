package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Nautile: the pseudo-terminal of a local shell, provided by the platform
 * (Termux's JNI helper on Android, pty4j on desktop).
 */
public interface Pty {
    InputStream getInputStream();

    OutputStream getOutputStream();

    void setWindowSize(int columns, int rows);

    /** Blocks until the process ends; its exit code, or minus the signal that killed it. */
    int waitFor() throws InterruptedException;

    int getPid();

    /** Ends the process (SIGKILL where it applies). */
    void kill();

    /** Releases the terminal once the process has ended. */
    void close();

    /** Starts [shellPath] in a new pseudo-terminal. */
    interface Factory {
        Pty spawn(String shellPath, String cwd, String[] args, String[] env, int columns, int rows) throws IOException;
    }
}
