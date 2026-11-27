package com.kablekillers.installer.adb;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** One ADB service stream (a shell command, a file transfer, ...). */
public final class AdbStream implements AutoCloseable {
    private static final byte[] EOF = new byte[0];

    private final AdbConnection conn;
    private final int localId;
    private volatile int remoteId;
    private volatile boolean remoteClosed;
    private boolean localClosed;

    private final Object lock = new Object();
    private boolean opened;
    private boolean writeReady;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    private byte[] current;
    private int currentPos;

    AdbStream(AdbConnection conn, int localId) {
        this.conn = conn;
        this.localId = localId;
    }

    // ---- called by the reader thread ----

    void onOkay(int remote) {
        synchronized (lock) {
            if (!opened) {
                remoteId = remote;
                opened = true;
            }
            writeReady = true;
            lock.notifyAll();
        }
    }

    void onData(byte[] data) {
        if (data.length > 0) queue.add(data);
    }

    void onRemoteClose() {
        synchronized (lock) {
            remoteClosed = true;
            lock.notifyAll();
        }
        queue.add(EOF);
    }

    // ---- used by callers ----

    boolean awaitOpen(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!opened && !remoteClosed) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return opened;
        }
    }

    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int off, int len) throws IOException {
        int max = conn.maxData();
        while (len > 0) {
            int n = Math.min(len, max);
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            synchronized (lock) {
                // Wait for the TV to acknowledge the previous packet.
                long end = System.currentTimeMillis() + 30000;
                while (!writeReady && !remoteClosed) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("The TV stopped responding.");
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Cancelled");
                    }
                }
                if (remoteClosed) throw new IOException("The TV closed the transfer.");
                writeReady = false;
            }
            conn.send(AdbConnection.A_WRTE, localId, remoteId, chunk);
            off += n;
            len -= n;
        }
    }

    /** Next chunk of data, or null when the TV has closed the stream. */
    public byte[] read(long timeoutMs) throws IOException {
        if (current != null && currentPos < current.length) {
            byte[] rest = new byte[current.length - currentPos];
            System.arraycopy(current, currentPos, rest, 0, rest.length);
            current = null;
            return rest;
        }
        try {
            byte[] b = timeoutMs > 0 ? queue.poll(timeoutMs, TimeUnit.MILLISECONDS) : queue.take();
            if (b == null) throw new IOException("Timed out waiting for the TV.");
            if (b == EOF) {
                queue.add(EOF);
                return null;
            }
            return b;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled");
        }
    }

    public InputStream inputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n <= 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (current == null || currentPos >= current.length) {
                    byte[] next = AdbStream.this.read(0);
                    if (next == null) return -1;
                    current = next;
                    currentPos = 0;
                }
                int n = Math.min(len, current.length - currentPos);
                System.arraycopy(current, currentPos, b, off, n);
                currentPos += n;
                return n;
            }
        };
    }

    /** Reads exactly n bytes (used by the file-transfer protocol). */
    public byte[] readExactly(int n) throws IOException {
        byte[] out = new byte[n];
        InputStream is = inputStream();
        int got = 0;
        while (got < n) {
            int r = is.read(out, got, n - got);
            if (r < 0) throw new IOException("The TV ended the transfer early.");
            got += r;
        }
        return out;
    }

    @Override
    public void close() {
        if (localClosed) return;
        localClosed = true;
        conn.forget(localId);
        if (!remoteClosed && remoteId != 0) {
            try {
                conn.send(AdbConnection.A_CLSE, localId, remoteId, null);
            } catch (IOException ignored) {
            }
        }
    }
}
