package com.kablekillers.installer.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Fire TV / onn / Android TV devices on the phone's Wi-Fi two ways:
 * 1. SSDP/DIAL broadcast — every Fire TV and Google TV answers this, even with ADB off.
 * 2. A scan of the whole Wi-Fi subnet for ADB port 5555.
 */
public final class TvFinder {
    public static final class Tv {
        public final String ip;
        public boolean adb;
        public String name = "";
        public String maker = "";
        public String model = "";

        Tv(String ip) {
            this.ip = ip;
        }
    }

    public static final class Net {
        public final String ip;
        public final int prefix;

        Net(String ip, int prefix) {
            this.ip = ip;
            this.prefix = prefix;
        }
    }

    private static final Pattern TV_HINT = Pattern.compile(
            "amazon|fire ?tv|\\baft|android ?tv|google ?tv|chromecast|\\bonn\\b|walmart|nvidia shield",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_TV = Pattern.compile("echo|kindle|ring|blink|eero|nest|home mini|speaker",
            Pattern.CASE_INSENSITIVE);

    private TvFinder() { }

    /** The phone's Wi-Fi address and subnet size. */
    public static Net wifi() {
        Net fallback = null;
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        Net n = new Net(a.getHostAddress(), ia.getNetworkPrefixLength());
                        if (ni.getName().startsWith("wlan")) return n;
                        if (fallback == null) fallback = n;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    public static List<Tv> find(Net net) {
        Map<String, Tv> found = new ConcurrentHashMap<>();

        // SSDP runs in the background while the port scan goes.
        Thread ssdp = new Thread(() -> ssdp(found), "ssdp");
        ssdp.start();

        // Port scan the whole subnet (clamped to /22 = 1022 hosts max).
        int prefix = Math.max(22, Math.min(30, net.prefix <= 0 ? 24 : net.prefix));
        int self = toInt(net.ip);
        int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
        int base = self & mask;
        int count = (1 << (32 - prefix)) - 2;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<Future<?>> jobs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int addr = base + i;
            if (addr == self) continue;
            final String host = toIp(addr);
            jobs.add(pool.submit(() -> {
                if (portOpen(host, 1200)) found.computeIfAbsent(host, Tv::new).adb = true;
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get(20, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        try {
            ssdp.join(6000);
        } catch (InterruptedException ignored) {
        }

        // Second, slower check of TVs SSDP found but the scan didn't (sleepy Wi-Fi).
        for (Tv tv : found.values()) {
            if (!tv.adb) tv.adb = portOpen(tv.ip, 2500);
        }

        List<Tv> out = new ArrayList<>();
        for (Tv tv : found.values()) {
            String desc = tv.name + " " + tv.maker + " " + tv.model;
            if (tv.adb || (TV_HINT.matcher(desc).find() && !NOT_TV.matcher(tv.name + " " + tv.model).find())) out.add(tv);
        }
        Collections.sort(out, (a, b) -> Integer.compare(toInt(a.ip), toInt(b.ip)));
        return out;
    }

    static boolean portOpen(String host, int timeoutMs) {
        try (Socket sk = new Socket()) {
            sk.connect(new InetSocketAddress(host, 5555), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void ssdp(Map<String, Tv> found) {
        Map<String, String> locations = new LinkedHashMap<>();
        Map<String, String> servers = new LinkedHashMap<>();
        String[] targets = {"urn:dial-multiscreen-org:service:dial:1", "ssdp:all", "upnp:rootdevice"};
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int round = 0; round < 2; round++) {
                for (String st : targets) {
                    byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                            + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII);
                    sock.send(new DatagramPacket(msg, msg.length, group, 1900));
                }
            }
            long end = System.currentTimeMillis() + 3500;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String ip = p.getAddress().getHostAddress();
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                String loc = header(text, "LOCATION");
                String server = header(text, "SERVER");
                if (loc != null && !locations.containsKey(ip)) locations.put(ip, loc);
                if (server != null && !servers.containsKey(ip)) servers.put(ip, server);
            }
        } catch (IOException ignored) {
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> jobs = new ArrayList<>();
        for (Map.Entry<String, String> e : locations.entrySet()) {
            final String ip = e.getKey();
            final String loc = e.getValue();
            jobs.add(pool.submit(() -> {
                String xml = fetch(loc);
                Tv tv = found.computeIfAbsent(ip, Tv::new);
                if (xml != null) {
                    tv.name = tag(xml, "friendlyName");
                    tv.maker = tag(xml, "manufacturer");
                    tv.model = tag(xml, "modelName");
                }
                if (tv.maker.isEmpty() && servers.containsKey(ip)) tv.maker = servers.get(ip);
            }));
        }
        for (Future<?> f : jobs) {
            try {
                f.get(4, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
    }

    private static String header(String text, String name) {
        for (String line : text.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">", Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim().replace("&amp;", "&").replace("&apos;", "'") : "";
    }

    private static String fetch(String url) {
        HttpURLConnection hc = null;
        try {
            hc = (HttpURLConnection) new URL(url).openConnection();
            hc.setConnectTimeout(2000);
            hc.setReadTimeout(2000);
            try (InputStream is = hc.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = is.read(b)) != -1 && bos.size() < 256 * 1024) bos.write(b, 0, n);
                return bos.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (hc != null) hc.disconnect();
        }
    }

    static int toInt(String ip) {
        String[] p = ip.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16)
                | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    static String toIp(int v) {
        return String.format(Locale.US, "%d.%d.%d.%d", (v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255);
    }
}
