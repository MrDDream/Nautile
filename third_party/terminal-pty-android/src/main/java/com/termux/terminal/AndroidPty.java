package com.termux.terminal;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/** Nautile: {@link Pty} through Termux's JNI helper (the code that used to live in TerminalSession). */
public final class AndroidPty implements Pty {
    public static final Pty.Factory FACTORY = (shellPath, cwd, args, env, columns, rows) -> {
        int[] processId = new int[1];
        int fd = JNI.createSubprocess(shellPath, cwd, args, env, processId, rows, columns);
        return new AndroidPty(fd, processId[0]);
    };

    private final int mFd;
    private final int mPid;
    private final FileDescriptor mWrapped;

    private AndroidPty(int fd, int pid) throws IOException {
        mFd = fd;
        mPid = pid;
        mWrapped = wrap(fd);
    }

    @Override public InputStream getInputStream() { return new FileInputStream(mWrapped); }

    @Override public OutputStream getOutputStream() { return new FileOutputStream(mWrapped); }

    @Override public void setWindowSize(int columns, int rows) { JNI.setPtyWindowSize(mFd, rows, columns); }

    @Override public int waitFor() { return JNI.waitFor(mPid); }

    @Override public int getPid() { return mPid; }

    @Override public void kill() {
        try {
            Os.kill(mPid, OsConstants.SIGKILL);
        } catch (ErrnoException e) {
            System.err.println("W/AndroidPty: failed sending SIGKILL: " + e.getMessage());
        }
    }

    @Override public void close() { JNI.close(mFd); }

    private static FileDescriptor wrap(int fd) throws IOException {
        FileDescriptor result = new FileDescriptor();
        try {
            Field field;
            try {
                field = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                field = FileDescriptor.class.getDeclaredField("fd");
            }
            field.setAccessible(true);
            field.set(result, fd);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            throw new IOException("Cannot wrap the terminal's file descriptor", e);
        }
        return result;
    }
}
