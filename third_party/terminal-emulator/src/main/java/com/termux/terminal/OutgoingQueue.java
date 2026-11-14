package com.termux.terminal;

import java.util.ArrayDeque;

/**
 * Nautile: what the UI thread sends to the shell or the backend (keys, pastes, emulator replies).
 * Unlike {@link ByteQueue}, writing never blocks: a peer that stops reading must not freeze the UI.
 * The queue grows up to a limit, past which {@link #offer} refuses the data.
 * One producer (the UI thread) and one consumer (the writer thread).
 */
final class OutgoingQueue {

    private final ArrayDeque<byte[]> mChunks = new ArrayDeque<>();
    private final long mLimit;
    /** Bytes of the head chunk already handed to the consumer. */
    private int mHeadOffset;
    private long mStored;
    private boolean mOpen = true;
    /** When the consumer last took data, or when data started waiting in an empty queue (System.nanoTime). */
    private long mLastProgressNanos = System.nanoTime();

    OutgoingQueue(long limit) {
        mLimit = limit;
    }

    /** Queued bytes not yet read. */
    synchronized long size() {
        return mStored;
    }

    synchronized boolean isOpen() {
        return mOpen;
    }

    /** How long queued data has waited without the consumer taking any; 0 when the queue is empty. */
    synchronized long stalledNanos() {
        return mStored == 0 ? 0 : System.nanoTime() - mLastProgressNanos;
    }

    /** Queues a copy of the data. False, without queueing anything, if closed or if it would go past the limit. */
    synchronized boolean offer(byte[] data, int offset, int count) {
        if (count < 0 || offset < 0 || offset + count > data.length) throw new IllegalArgumentException("bad range");
        if (!mOpen) return false;
        if (count == 0) return true;
        if (mStored + count > mLimit) return false;
        byte[] copy = new byte[count];
        System.arraycopy(data, offset, copy, 0, count);
        if (mStored == 0) mLastProgressNanos = System.nanoTime();
        mChunks.addLast(copy);
        mStored += count;
        notifyAll();
        return true;
    }

    /** Blocks until data is available; -1 once closed (pending data is dropped). */
    synchronized int read(byte[] buffer) {
        while (mStored == 0 && mOpen) {
            try {
                wait();
            } catch (InterruptedException e) {
                // Ignore, as ByteQueue does.
            }
        }
        if (!mOpen) return -1;
        int total = 0;
        while (total < buffer.length && !mChunks.isEmpty()) {
            byte[] head = mChunks.peekFirst();
            int n = Math.min(buffer.length - total, head.length - mHeadOffset);
            System.arraycopy(head, mHeadOffset, buffer, total, n);
            total += n;
            mHeadOffset += n;
            if (mHeadOffset == head.length) {
                mChunks.removeFirst();
                mHeadOffset = 0;
            }
        }
        mStored -= total;
        if (total > 0) mLastProgressNanos = System.nanoTime();
        return total;
    }

    synchronized void close() {
        mOpen = false;
        mChunks.clear();
        mStored = 0;
        notifyAll();
    }
}
