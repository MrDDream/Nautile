package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

/** Nautile's changes to the session: writer failures, non-blocking writes, OSC 52 and XTGETTCAP. */
public class NautileSessionTest {

    /** The "main thread": tasks run when the test drains them. */
    private final LinkedBlockingQueue<Runnable> mMain = new LinkedBlockingQueue<>();

    private void drain(long waitMs) throws InterruptedException {
        long end = System.currentTimeMillis() + waitMs;
        while (true) {
            Runnable r = mMain.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (r == null) return;
            r.run();
        }
    }

    @Before
    public void setUp() {
        TerminalSession.setMainThread(mMain::add);
    }

    static class Client implements TerminalSessionClient {
        final List<String> remoteClips = new ArrayList<>();
        final List<String> userClips = new ArrayList<>();
        final List<String> refused = new ArrayList<>();
        int finished;

        @Override public void onTextChanged(TerminalSession s) {}
        @Override public void onTitleChanged(TerminalSession s) {}
        @Override public void onSessionFinished(TerminalSession s) { finished++; }
        @Override public void onCopyTextToClipboard(TerminalSession s, String text) { userClips.add(text); }
        @Override public void onRemoteCopyTextToClipboard(TerminalSession s, String text) { remoteClips.add(text); }
        @Override public void onPasteTextFromClipboard(TerminalSession s) {}
        @Override public void onInputRefused(TerminalSession s, int count, boolean tooLarge) { refused.add(count + (tooLarge ? " large" : " busy")); }
        @Override public void onBell(TerminalSession s) {}
        @Override public void onColorsChanged(TerminalSession s) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public Integer getTerminalCursorStyle() { return 0; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }

    /** A backend recording writes; [block] makes them hang, [fail] makes them throw. */
    static class Backend implements TerminalSession.Backend {
        final ConcurrentLinkedQueue<String> written = new ConcurrentLinkedQueue<>();
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean block;
        volatile boolean fail;
        final AtomicReference<IOException> failure = new AtomicReference<>();
        int closed;

        @Override public void start(int columns, int rows) {}

        @Override public void write(byte[] data, int offset, int count) throws IOException {
            if (fail) throw new IOException("write timeout");
            if (block) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            written.add(new String(data, offset, count, StandardCharsets.UTF_8));
        }

        @Override public void resize(int columns, int rows) {}

        @Override public void close() { closed++; }

        @Override public void writeFailed(IOException e) { failure.set(e); }
    }

    private TerminalSession start(Backend backend, Client client) {
        TerminalSession session = new TerminalSession(backend, 100, client);
        session.updateSize(80, 24);
        return session;
    }

    private static void type(TerminalSession session, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        session.write(b, 0, b.length);
    }

    /** Feeds from another thread (the input queue is small and feed() blocks) while the main thread runs. */
    private void feed(TerminalSession session, String text) throws InterruptedException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        Thread t = new Thread(() -> session.feed(b, 0, b.length));
        t.start();
        while (t.isAlive()) drain(10);
        drain(50);
    }

    @Test(timeout = 30000)
    public void feedLargerThanTheQueueIsDelivered() throws Exception {
        Backend backend = new Backend();
        Client client = new Client();
        TerminalSession session = start(backend, client);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) big.append("x");
        big.append("END");
        feed(session, big.toString() + big + big);
        String screen = session.getEmulator().getScreen().getTranscriptText();
        assertTrue(screen.endsWith("END"));
    }

    @Test(timeout = 30000)
    public void deadWriterEndsTheSession() throws Exception {
        Backend backend = new Backend();
        Client client = new Client();
        TerminalSession session = start(backend, client);
        backend.fail = true;
        type(session, "ls\r");
        drain(1000);
        assertTrue(backend.failure.get() != null);
        assertFalse(session.isRunning());
        assertEquals(1, client.finished);
        // Nothing more is queued once the writer is gone.
        type(session, "more");
        assertEquals(0, session.mTerminalToProcessIOQueue.size());
    }

    @Test(timeout = 30000)
    public void writesNeverBlockAndAStuckPeerEndsTheSession() throws Exception {
        long stall = TerminalSession.sStallNanos;
        TerminalSession.sStallNanos = TimeUnit.MILLISECONDS.toNanos(300);
        try {
            Backend backend = new Backend();
            backend.block = true;
            Client client = new Client();
            TerminalSession session = start(backend, client);
            byte[] chunk = new byte[1 << 20];
            java.util.Arrays.fill(chunk, (byte) 'a');
            long t0 = System.nanoTime();
            // Far more than the limit, from the "main thread": must return at once.
            for (int i = 0; i < 40; i++) session.write(chunk, 0, chunk.length);
            assertTrue("writes blocked", System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5));
            drain(100);
            // Not stuck yet (it may still read): the excess is refused, the session goes on.
            assertNull(backend.failure.get());
            assertTrue(session.isRunning());
            assertFalse(client.refused.isEmpty());
            assertEquals((1 << 20) + " busy", client.refused.get(0));
            // Nothing read for longer than the stall delay: the next write ends the session.
            Thread.sleep(400);
            session.write(chunk, 0, chunk.length);
            drain(500);
            assertTrue(backend.failure.get() instanceof TerminalSession.OutgoingOverflowException);
            assertFalse(session.isRunning());
            backend.release.countDown();
        } finally {
            TerminalSession.sStallNanos = stall;
        }
    }

    @Test(timeout = 30000)
    public void oversizedPasteIsRefusedWithoutEndingTheSession() throws Exception {
        Backend backend = new Backend();
        Client client = new Client();
        TerminalSession session = start(backend, client);
        byte[] huge = new byte[(int) TerminalSession.OUTGOING_LIMIT + 1];
        java.util.Arrays.fill(huge, (byte) 'a');
        session.write(huge, 0, huge.length);
        assertEquals(List.of(huge.length + " large"), client.refused);
        assertEquals(0, session.mTerminalToProcessIOQueue.size());
        type(session, "ls\r");
        drain(300);
        assertTrue(session.isRunning());
        assertNull(backend.failure.get());
        assertEquals("ls\r", String.join("", backend.written));
    }

    @Test
    public void outgoingQueueTracksTheConsumersProgress() throws Exception {
        OutgoingQueue q = new OutgoingQueue(100);
        assertEquals(0, q.stalledNanos());
        assertTrue(q.offer(new byte[10], 0, 10));
        Thread.sleep(20);
        assertTrue(q.stalledNanos() >= TimeUnit.MILLISECONDS.toNanos(15));
        q.read(new byte[4]);
        assertTrue(q.stalledNanos() < TimeUnit.MILLISECONDS.toNanos(15));
        q.read(new byte[10]);
        assertEquals(0, q.stalledNanos());
    }

    @Test(timeout = 30000)
    public void emulatorRepliesAreDroppedWhenThePeerDoesNotRead() throws Exception {
        Backend backend = new Backend();
        backend.block = true;
        Client client = new Client();
        TerminalSession session = start(backend, client);
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 4000; i++) flood.append("\033[c");
        for (int i = 0; i < 20; i++) feed(session, flood.toString());
        // Replies stop at the reply limit and the session stays open.
        assertTrue(session.mTerminalToProcessIOQueue.size() <= TerminalSession.REPLY_LIMIT);
        assertTrue(session.isRunning());
        assertNull(backend.failure.get());
        // What the user types still goes through.
        long before = session.mTerminalToProcessIOQueue.size();
        type(session, "x");
        assertTrue(session.mTerminalToProcessIOQueue.size() >= before);
        backend.release.countDown();
    }

    @Test(timeout = 30000)
    public void emulatorRepliesAreNotUserInput() throws Exception {
        Backend backend = new Backend();
        Client client = new Client();
        TerminalSession session = start(backend, client);
        List<String> typed = new ArrayList<>();
        session.mInputListener = (data, off, len) -> typed.add(new String(data, off, len, StandardCharsets.UTF_8));
        feed(session, "\033[c");
        drain(200);
        assertTrue(typed.isEmpty());
        type(session, "ls");
        assertEquals(List.of("ls"), typed);
    }

    @Test(timeout = 30000)
    public void osc52IsAProgramWriteAndIgnoresQueriesAndEmptyData() throws Exception {
        Backend backend = new Backend();
        Client client = new Client();
        TerminalSession session = start(backend, client);
        String b64 = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        feed(session, "\033]52;c;?\007");
        feed(session, "\033]52;c;\007");
        feed(session, "\033]52;c;" + b64 + "\007");
        drain(200);
        assertEquals(List.of("hello"), client.remoteClips);
        assertTrue(client.userClips.isEmpty());
    }

    @Test(timeout = 30000)
    public void osc52Bounds() {
        assertNull(TerminalEmulator.decodeOsc52("c;?"));
        assertNull(TerminalEmulator.decodeOsc52("c;"));
        assertNull(TerminalEmulator.decodeOsc52("c; "));
        assertNull(TerminalEmulator.decodeOsc52("c;!!!"));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= TerminalEmulator.OSC52_MAX_CHARS; i++) big.append('a');
        String encoded = Base64.getEncoder().encodeToString(big.toString().getBytes(StandardCharsets.UTF_8));
        assertNull(TerminalEmulator.decodeOsc52("c;" + encoded));
        assertEquals("aé", TerminalEmulator.decodeOsc52("c;" + Base64.getEncoder().encodeToString("aé".getBytes(StandardCharsets.UTF_8))));
    }

    @Test(timeout = 30000)
    public void xtgettcapRepliesOnlyToHexNames() throws Exception {
        Backend backend = new Backend();
        Client client = new Client();
        TerminalSession session = start(backend, client);
        // "Co" in hex: answered.
        feed(session, "\033P+q436f\033\\");
        drain(200);
        Thread.sleep(200);
        String replies = String.join("", backend.written);
        assertTrue(replies, replies.contains("\033P1+r436f="));
        backend.written.clear();
        // A name that is not hex would be echoed into the shell: no reply at all.
        feed(session, "\033P+q;7z echo pwned\033\\");
        feed(session, "\033P+q41zz\033\\");
        drain(200);
        Thread.sleep(200);
        assertTrue(String.join("", backend.written), backend.written.isEmpty());
        assertTrue(TerminalEmulator.isHexString("6b75"));
        assertFalse(TerminalEmulator.isHexString(""));
        assertFalse(TerminalEmulator.isHexString("6b\r"));
    }

    @Test(timeout = 30000)
    public void outgoingQueueKeepsOrderAndRefusesPastTheLimit() {
        OutgoingQueue q = new OutgoingQueue(10);
        assertTrue(q.offer("abcdef".getBytes(), 0, 6));
        assertFalse(q.offer("ghijkl".getBytes(), 0, 6));
        assertTrue(q.offer("ghij".getBytes(), 0, 4));
        byte[] buf = new byte[4];
        assertEquals(4, q.read(buf));
        assertEquals("abcd", new String(buf));
        byte[] rest = new byte[16];
        int n = q.read(rest);
        assertEquals("efghij", new String(rest, 0, n));
        q.close();
        assertEquals(-1, q.read(rest));
        assertFalse(q.offer("x".getBytes(), 0, 1));
    }
}
