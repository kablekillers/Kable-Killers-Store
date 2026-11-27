package com.kablekillers.installer.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** File transfer ("adb push" / "adb pull") and APK install on top of an AdbConnection. */
public final class AdbSync {
    private static final int CHUNK = 64 * 1024;

    public interface Progress {
        void onProgress(long done, long total);
    }

    private AdbSync() { }

    public static void push(AdbConnection c, File local, String remotePath, Progress progress) throws IOException {
        long total = local.length();
        try (AdbStream st = c.open("sync:"); InputStream is = new FileInputStream(local)) {
            byte[] spec = (remotePath + ",420").getBytes(StandardCharsets.UTF_8); // 420 = 0644
            st.write(packet("SEND", spec.length, spec, 0, spec.length));

            byte[] buf = new byte[CHUNK];
            long done = 0;
            int n;
            while ((n = readFull(is, buf)) > 0) {
                st.write(packet("DATA", n, buf, 0, n));
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
            int mtime = (int) (System.currentTimeMillis() / 1000L);
            st.write(packet("DONE", mtime, null, 0, 0));

            byte[] resp = st.readExactly(8);
            String id = new String(resp, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(id)) {
                int len = le32(resp, 4);
                String msg = len > 0 && len < 65536 ? new String(st.readExactly(len), StandardCharsets.UTF_8) : id;
                throw new IOException("Copy to TV failed: " + msg);
            }
            sendQuit(st);
        }
    }

    public static void pull(AdbConnection c, String remotePath, File local) throws IOException {
        try (AdbStream st = c.open("sync:"); OutputStream os = new FileOutputStream(local)) {
            byte[] path = remotePath.getBytes(StandardCharsets.UTF_8);
            st.write(packet("RECV", path.length, path, 0, path.length));
            while (true) {
                byte[] head = st.readExactly(8);
                String id = new String(head, 0, 4, StandardCharsets.US_ASCII);
                int len = le32(head, 4);
                if ("DATA".equals(id)) {
                    os.write(st.readExactly(len));
                } else if ("DONE".equals(id)) {
                    break;
                } else if ("FAIL".equals(id)) {
                    throw new IOException("Copy from TV failed: "
                            + new String(st.readExactly(len), StandardCharsets.UTF_8));
                } else {
                    throw new IOException("Unexpected reply from TV: " + id);
                }
            }
            sendQuit(st);
        }
    }

    /** Copies the APK to the TV and installs it (keeps app data when updating). */
    public static String install(AdbConnection c, File apk, Progress progress) throws IOException {
        String remote = "/data/local/tmp/kk_install_" + System.currentTimeMillis() + ".apk";
        push(c, apk, remote, progress);
        String out = c.shell("pm install -r '" + remote + "' 2>&1; rm -f '" + remote + "'").trim();
        if (!out.contains("Success")) {
            throw new IOException(out.isEmpty() ? "Install failed (no reply from TV)" : "Install failed: " + out);
        }
        return out;
    }

    private static void sendQuit(AdbStream st) {
        try {
            st.write(packet("QUIT", 0, null, 0, 0));
        } catch (IOException ignored) {
        }
    }

    private static byte[] packet(String id, int arg, byte[] data, int off, int len) {
        ByteBuffer b = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(StandardCharsets.US_ASCII));
        b.putInt(arg);
        if (len > 0) b.put(data, off, len);
        return b.array();
    }

    private static int le32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int readFull(InputStream is, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int r = is.read(buf, got, buf.length - got);
            if (r < 0) break;
            got += r;
        }
        return got;
    }
}
