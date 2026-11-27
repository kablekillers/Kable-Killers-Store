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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    private interface Attempt {
        String run(String extraFlags) throws IOException;
    }

    /**
     * Copies an APK — or a split bundle (.apks / .xapk / zip of APKs) — to the TV and installs it,
     * keeping app data when updating. Works around Play Protect / old-target-SDK blocks automatically.
     */
    public static String install(AdbConnection c, File file, Progress progress) throws IOException {
        List<File> apks = extractBundle(c, file);
        long total = 0;
        for (File f : apks) total += f.length();
        final long grand = total;
        String stamp = String.valueOf(System.currentTimeMillis());
        List<String> remotes = new ArrayList<>();
        long sent = 0;
        try {
            for (int i = 0; i < apks.size(); i++) {
                File f = apks.get(i);
                String remote = "/data/local/tmp/kk_" + stamp + "_" + i + ".apk";
                final long base = sent;
                push(c, f, remote, progress == null ? null : (d, t) -> progress.onProgress(base + d, grand));
                remotes.add(remote);
                sent += f.length();
            }
            Attempt attempt = remotes.size() == 1
                    ? flags -> c.shell("pm install -r " + flags + " '" + remotes.get(0) + "' 2>&1").trim()
                    : flags -> sessionInstall(c, apks, remotes, flags);
            return withFixes(c, attempt);
        } finally {
            if (!remotes.isEmpty()) {
                StringBuilder rm = new StringBuilder("rm -f");
                for (String r : remotes) rm.append(" '").append(r).append('\'');
                try { c.shell(rm.toString()); } catch (IOException ignored) { }
            }
            if (apks.size() > 1 || apks.get(0) != file) {
                for (File f : apks) if (f != file) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
    }

    private static String withFixes(AdbConnection c, Attempt attempt) throws IOException {
        String flags = "";
        String out = attempt.run(flags);
        for (int round = 0; round < 3 && !out.contains("Success"); round++) {
            if (out.contains("VERIFICATION_FAILURE") || out.contains("Install not allowed")) {
                // Google Play Protect on onn / Google TV blocks ADB installs; turn that check off.
                c.shell("settings put global verifier_verify_adb_installs 0; "
                        + "settings put global package_verifier_enable 0; "
                        + "settings put global package_verifier_user_consent -1");
            } else if (out.contains("DEPRECATED_SDK_VERSION") && !flags.contains("bypass")) {
                // Android 14 refuses very old apps unless told otherwise.
                flags = (flags + " --bypass-low-target-sdk-block").trim();
            } else {
                break;
            }
            out = attempt.run(flags);
        }
        if (out.contains("Success")) return out;
        throw new IOException(explain(out));
    }

    static String explain(String out) {
        if (out.isEmpty()) return "Install failed (no reply from the TV).";
        String hint = null;
        if (out.contains("MISSING_SPLIT")) {
            hint = "This APK is only one piece of a split app (Google Play delivers it in parts). "
                    + "Get the full APK, or the .apks / .xapk bundle, and install that.";
        } else if (out.contains("UPDATE_INCOMPATIBLE") || out.contains("signatures do not match")) {
            hint = "A different copy of this app is already on the TV (signed by someone else). "
                    + "Uninstall it in the Apps tab, then install again.";
        } else if (out.contains("VERSION_DOWNGRADE")) {
            hint = "The TV already has a newer version. Uninstall it in the Apps tab first to go back.";
        } else if (out.contains("INSUFFICIENT_STORAGE")) {
            hint = "Not enough space on the TV. Free some up in the Files tab or uninstall unused apps.";
        } else if (out.contains("NO_MATCHING_ABIS")) {
            hint = "This APK was built for a different kind of processor. Get the ARM (armeabi-v7a / arm64) version.";
        } else if (out.contains("OLDER_SDK")) {
            hint = "This app needs a newer Android version than this TV has.";
        } else if (out.contains("VERIFICATION_FAILURE")) {
            hint = "Google Play Protect on the TV blocked it. On the TV open Play Store → profile icon → "
                    + "Play Protect → turn off \"Scan apps with Play Protect\", then install again.";
        }
        return hint == null ? "Install failed: " + out : hint + "\n\n(" + out + ")";
    }

    private static String sessionInstall(AdbConnection c, List<File> apks, List<String> remotes, String flags)
            throws IOException {
        String created = c.shell("pm install-create -r " + flags + " 2>&1").trim();
        int a = created.indexOf('['), b = created.indexOf(']');
        if (a < 0 || b < a) return created;
        String id = created.substring(a + 1, b);
        for (int i = 0; i < remotes.size(); i++) {
            String w = c.shell("pm install-write -S " + apks.get(i).length() + " " + id + " " + i + "_split.apk '"
                    + remotes.get(i) + "' 2>&1").trim();
            if (!w.contains("Success")) {
                c.shell("pm install-abandon " + id);
                return w;
            }
        }
        return c.shell("pm install-commit " + id + " 2>&1").trim();
    }

    /** A plain APK comes back as itself; a bundle is unpacked to the APKs this TV needs. */
    static List<File> extractBundle(AdbConnection c, File file) throws IOException {
        List<File> one = new ArrayList<>();
        one.add(file);
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("AndroidManifest.xml") != null) return one;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry z = e.nextElement();
                if (!z.isDirectory() && z.getName().toLowerCase(Locale.US).endsWith(".apk")) names.add(z.getName());
            }
            if (names.isEmpty()) throw new IOException("That file isn't an APK or an APK bundle.");

            // Keep only the processor (ABI) split that best fits this TV.
            String[] abis = c.shell("getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi").trim()
                    .replace('\n', ',').split(",");
            String[] known = {"arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips"};
            String best = null;
            for (String abi : abis) {
                String norm = abi.trim().replace('-', '_');
                if (norm.isEmpty()) continue;
                for (String n : names) {
                    if (n.toLowerCase(Locale.US).contains("config." + norm + ".apk")) { best = norm; break; }
                }
                if (best != null) break;
            }
            List<File> out = new ArrayList<>();
            File dir = file.getParentFile();
            int idx = 0;
            for (String n : names) {
                String lower = n.toLowerCase(Locale.US);
                boolean abiSplit = false;
                for (String k : known) if (lower.contains("config." + k + ".apk")) { abiSplit = true; break; }
                if (abiSplit && (best == null || !lower.contains("config." + best + ".apk"))) continue;
                File f = new File(dir, "bundle_" + (idx++) + ".apk");
                try (InputStream is = zip.getInputStream(zip.getEntry(n)); OutputStream os = new FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = is.read(buf)) != -1) os.write(buf, 0, r);
                }
                out.add(f);
            }
            return out;
        } catch (java.util.zip.ZipException e) {
            throw new IOException("That file isn't a valid APK (it may be damaged or only partly downloaded).");
        }
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
