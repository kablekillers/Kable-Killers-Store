package com.kablekillers.installer.adb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A plain ADB-over-network connection (the same thing as "adb connect IP:5555" on a PC).
 * Works with Fire TV / Fire Stick ("ADB debugging" on) and onn / Google TV / Android TV
 * ("USB debugging" on, which also opens network port 5555).
 */
public final class AdbConnection implements AutoCloseable {
    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_AUTH = 0x48545541;
    static final int A_STLS = 0x534c5453;

    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_RSAPUBLICKEY = 3;

    static final int VERSION = 0x01000000;
    static final int MAX_PAYLOAD_OFFER = 256 * 1024;

    public interface Listener {
        /** The TV is showing "Allow USB debugging?" — the user has to accept it on the TV. */
        void onAllowPromptOnTv();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile boolean closed;
    private volatile IOException failure;
    private int maxData = 4096;
    private String deviceBanner = "";
    private Thread reader;

    private AdbConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    public static AdbConnection connect(String host, int port, AdbKey key, String keyName,
                                        Listener listener) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw new IOException("Couldn't reach " + host + ":" + port
                    + ". Check the IP address, that the TV is on the same Wi-Fi, and that ADB/USB debugging is on.", e);
        }
        AdbConnection c = new AdbConnection(s);
        try {
            c.handshake(key, keyName, listener);
        } catch (IOException e) {
            c.close();
            throw e;
        }
        c.startReader();
        return c;
    }

    private void handshake(AdbKey key, String keyName, Listener listener) throws IOException {
        socket.setSoTimeout(10000);
        send(A_CNXN, VERSION, MAX_PAYLOAD_OFFER, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentSignature = false;
        boolean sentPublicKey = false;
        while (true) {
            Message m;
            try {
                m = readMessage();
            } catch (SocketTimeoutException e) {
                if (sentPublicKey) {
                    throw new IOException("Nobody tapped Allow on the TV. Connect again and choose "
                            + "\"Always allow from this computer\", then OK.");
                }
                throw new IOException("The TV didn't answer. Turn ADB/USB debugging off and on again, then retry.");
            } catch (EOFException e) {
                throw new IOException(sentPublicKey
                        ? "The TV refused the connection (Allow was declined)."
                        : "The TV closed the connection. Make sure ADB/USB debugging is on.");
            }
            if (m.cmd == A_CNXN) {
                maxData = Math.max(1024, Math.min(m.arg1, MAX_PAYLOAD_OFFER));
                deviceBanner = new String(m.data, StandardCharsets.UTF_8);
                socket.setSoTimeout(0);
                return;
            } else if (m.cmd == A_AUTH && m.arg0 == AUTH_TOKEN) {
                if (!sentSignature) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    sentSignature = true;
                } else if (!sentPublicKey) {
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.androidPublicKey(keyName));
                    sentPublicKey = true;
                    if (listener != null) listener.onAllowPromptOnTv();
                    socket.setSoTimeout(90000);
                } else {
                    throw new IOException("The TV refused this phone's key.");
                }
            } else if (m.cmd == A_STLS) {
                throw new IOException("That port wants Android 11+ \"Wireless debugging\" pairing. "
                        + "Use port 5555 with ADB/USB debugging turned on instead.");
            }
        }
    }

    private void startReader() {
        reader = new Thread(this::readLoop, "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            while (!closed) {
                Message m = readMessage();
                switch (m.cmd) {
                    case A_OKAY: {
                        AdbStream st = streams.get(m.arg1);
                        if (st != null) st.onOkay(m.arg0);
                        break;
                    }
                    case A_WRTE: {
                        AdbStream st = streams.get(m.arg1);
                        if (st != null) {
                            st.onData(m.data);
                            send(A_OKAY, m.arg1, m.arg0, null);
                        } else {
                            send(A_CLSE, 0, m.arg0, null);
                        }
                        break;
                    }
                    case A_CLSE: {
                        AdbStream st = streams.remove(m.arg1);
                        if (st != null) st.onRemoteClose();
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            if (!closed) failure = new IOException("Lost connection to the TV.", e);
        } finally {
            closed = true;
            for (AdbStream st : streams.values()) st.onRemoteClose();
            streams.clear();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Opens a service such as "shell:ls", "exec:screencap -p" or "sync:". */
    public AdbStream open(String destination) throws IOException {
        ensureOpen();
        int localId = nextId.getAndIncrement();
        AdbStream st = new AdbStream(this, localId);
        streams.put(localId, st);
        send(A_OPEN, localId, 0, (destination + "\0").getBytes(StandardCharsets.UTF_8));
        if (!st.awaitOpen(15000)) {
            streams.remove(localId);
            ensureOpen();
            throw new IOException("The TV refused: " + destination);
        }
        return st;
    }

    /** Runs a shell command and returns everything it printed. */
    public String shell(String command) throws IOException {
        try (AdbStream st = open("shell:" + command)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = st.inputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8").replace("\r\n", "\n");
        }
    }

    public boolean isOpen() {
        return !closed;
    }

    public String banner() {
        return deviceBanner;
    }

    int maxData() {
        return maxData;
    }

    void ensureOpen() throws IOException {
        if (closed) throw failure != null ? failure : new IOException("Not connected to a TV.");
    }

    void forget(int localId) {
        streams.remove(localId);
    }

    synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Message readMessage() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum (not verified; newer adbd sends 0)
        int magic = h.getInt();
        if (magic != ~m.cmd) throw new IOException("Bad data from the TV");
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Bad packet size from the TV");
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }
}
