package com.kablekillers.installer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.kablekillers.installer.adb.AdbConnection;
import com.kablekillers.installer.adb.AdbKey;
import com.kablekillers.installer.adb.AdbSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/assets/index.html";
    private static final int REQ_PICK_APK = 21;
    private static final int REQ_PICK_FILE = 22;

    // Kept across screen rotations / activity restarts.
    private static AdbConnection conn;
    private static String connectedTo = "";
    private static final ExecutorService adbExec = Executors.newSingleThreadExecutor();
    private static final ExecutorService ioExec = Executors.newCachedThreadPool();

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private String pickCallback;
    private File sharedApk;
    private String sharedName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF07090D);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        web.loadUrl(HOME);
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    /** An APK opened or shared from another app ("Open with Kable Killers Installer"). */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) uri = (Uri) extra;
        }
        if (uri == null) return;
        final Uri src = uri;
        final String name = displayName(src, "shared.apk");
        ioExec.execute(() -> {
            try {
                File f = new File(getCacheDir(), "shared.apk");
                copyUri(src, f);
                sharedApk = f;
                sharedName = name;
                event("shared", new JSONObject().put("name", name));
            } catch (Exception e) {
                toast("Couldn't open that file: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final String cb = pickCallback;
        pickCallback = null;
        if (cb == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            fail(cb, "cancelled");
            return;
        }
        final Uri uri = data.getData();
        final String name = displayName(uri, requestCode == REQ_PICK_APK ? "app.apk" : "file");
        ioExec.execute(() -> {
            try {
                File f = new File(getCacheDir(), requestCode == REQ_PICK_APK ? "picked.apk" : "picked.bin");
                copyUri(uri, f);
                if (requestCode == REQ_PICK_APK) {
                    runInstall(cb, f, name);
                } else {
                    final String remote = "/sdcard/Download/" + name.replace("'", "");
                    adbExec.execute(() -> {
                        try {
                            AdbSync.push(need(), f, remote, (d, t) -> progress(cb, "Copying", d, t));
                            done(cb, remote);
                        } catch (Exception e) {
                            fail(cb, e);
                        }
                    });
                }
            } catch (Exception e) {
                fail(cb, e);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private static AdbConnection need() throws IOException {
        if (conn == null || !conn.isOpen()) throw new IOException("Not connected to a TV. Tap a device first.");
        return conn;
    }

    private void runInstall(String cb, File apk, String name) {
        adbExec.execute(() -> {
            try {
                String out = AdbSync.install(need(), apk, (d, t) -> progress(cb, "Sending " + name + " to TV", d, t));
                done(cb, out);
            } catch (Exception e) {
                fail(cb, e);
            }
        });
    }

    private void download(String url, File dest, String cb, String label) throws IOException {
        URL u = new URL(url);
        HttpURLConnection hc = null;
        for (int i = 0; i < 8; i++) {
            hc = (HttpURLConnection) u.openConnection();
            hc.setInstanceFollowRedirects(false);
            hc.setConnectTimeout(15000);
            hc.setReadTimeout(30000);
            hc.setRequestProperty("User-Agent", "KableKillersInstaller");
            int code = hc.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = hc.getHeaderField("Location");
                hc.disconnect();
                if (loc == null) throw new IOException("Bad redirect");
                u = new URL(u, loc);
                continue;
            }
            if (code != 200) throw new IOException("Download failed (HTTP " + code + ")");
            break;
        }
        long total = hc.getContentLengthLong();
        try (InputStream is = hc.getInputStream(); OutputStream os = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            long lastTick = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                os.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (cb != null && now - lastTick > 250) {
                    lastTick = now;
                    progress(cb, label, done, total);
                }
            }
        } finally {
            hc.disconnect();
        }
    }

    private String fetchText(String url) throws IOException {
        File tmp = File.createTempFile("fetch", ".txt", getCacheDir());
        try {
            download(url, tmp, null, null);
            return new String(Files.readAllBytes(tmp.toPath()), "UTF-8");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private void copyUri(Uri uri, File dest) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(dest)) {
            if (is == null) throw new IOException("File not readable");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    private String displayName(Uri uri, String fallback) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = fallback;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String localIPv4() {
        try {
            String fallback = null;
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ JS plumbing

    private void js(String code) {
        main.post(() -> web.evaluateJavascript(code, null));
    }

    private void done(String cb, Object result) {
        js("window.kkCb(" + JSONObject.quote(cb) + ",true," + jsonValue(result) + ")");
    }

    private void fail(String cb, Object err) {
        String msg = err instanceof Throwable
                ? (((Throwable) err).getMessage() != null ? ((Throwable) err).getMessage() : err.toString())
                : String.valueOf(err);
        js("window.kkCb(" + JSONObject.quote(cb) + ",false," + JSONObject.quote(msg) + ")");
    }

    private void progress(String cb, String label, long doneBytes, long total) {
        try {
            event("progress", new JSONObject().put("cb", cb).put("label", label)
                    .put("done", doneBytes).put("total", total));
        } catch (Exception ignored) {
        }
    }

    private void event(String type, JSONObject data) {
        js("window.kkEvent && window.kkEvent(" + JSONObject.quote(type) + "," + data + ")");
    }

    private static String jsonValue(Object o) {
        if (o == null) return "null";
        if (o instanceof JSONObject || o instanceof JSONArray) return o.toString();
        if (o instanceof Boolean || o instanceof Number) return String.valueOf(o);
        return JSONObject.quote(String.valueOf(o));
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ the page's API

    /** Methods the page can call as window.AndroidApp.* — results come back through window.kkCb. */
    class Bridge {
        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public String connectedTo() {
            return conn != null && conn.isOpen() ? connectedTo : "";
        }

        @JavascriptInterface
        public String localIp() {
            String ip = localIPv4();
            return ip == null ? "" : ip;
        }

        @JavascriptInterface
        public void connect(String cb, String host, int port) {
            adbExec.execute(() -> {
                try {
                    if (conn != null) conn.close();
                    conn = null;
                    AdbKey key = AdbKey.loadOrCreate(new File(getFilesDir(), "adb"));
                    String keyName = "KableKillersInstaller@" + Build.MODEL.replace(' ', '_');
                    conn = AdbConnection.connect(host, port, key, keyName,
                            () -> event("allowPrompt", new JSONObject()));
                    connectedTo = host + ":" + port;
                    JSONObject info = new JSONObject();
                    info.put("target", connectedTo);
                    info.put("banner", conn.banner());
                    String props = conn.shell("getprop ro.product.manufacturer; getprop ro.product.model; "
                            + "getprop ro.build.version.release; getprop ro.build.version.sdk");
                    String[] p = props.trim().split("\n");
                    info.put("manufacturer", p.length > 0 ? p[0].trim() : "");
                    info.put("model", p.length > 1 ? p[1].trim() : "");
                    info.put("android", p.length > 2 ? p[2].trim() : "");
                    info.put("sdk", p.length > 3 ? p[3].trim() : "");
                    done(cb, info);
                } catch (Exception e) {
                    fail(cb, e);
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            adbExec.execute(() -> {
                if (conn != null) conn.close();
                conn = null;
                connectedTo = "";
            });
        }

        @JavascriptInterface
        public void shell(String cb, String command) {
            adbExec.execute(() -> {
                try {
                    done(cb, need().shell(command));
                } catch (Exception e) {
                    fail(cb, e);
                }
            });
        }

        /** Quick fire-and-forget command (remote control buttons). */
        @JavascriptInterface
        public void key(String command) {
            adbExec.execute(() -> {
                try {
                    need().shell(command);
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void installUrl(String cb, String url, String name) {
            ioExec.execute(() -> {
                try {
                    File f = new File(getCacheDir(), "download.apk");
                    download(url, f, cb, "Downloading " + name);
                    runInstall(cb, f, name);
                } catch (Exception e) {
                    fail(cb, e);
                }
            });
        }

        @JavascriptInterface
        public void pickAndInstall(String cb) {
            main.post(() -> pick(cb, REQ_PICK_APK));
        }

        @JavascriptInterface
        public void pickAndPush(String cb) {
            main.post(() -> pick(cb, REQ_PICK_FILE));
        }

        @JavascriptInterface
        public String sharedName() {
            return sharedApk != null ? sharedName : "";
        }

        @JavascriptInterface
        public void installShared(String cb) {
            if (sharedApk == null) {
                fail(cb, "No shared APK");
                return;
            }
            File f = sharedApk;
            String n = sharedName;
            sharedApk = null;
            runInstall(cb, f, n);
        }

        @JavascriptInterface
        public void screenshot(String cb) {
            adbExec.execute(() -> {
                try {
                    AdbConnection c = need();
                    String remote = "/data/local/tmp/kk_shot.png";
                    c.shell("screencap -p " + remote);
                    File f = new File(getCacheDir(), "shot.png");
                    AdbSync.pull(c, remote, f);
                    c.shell("rm -f " + remote);
                    byte[] png = Files.readAllBytes(f.toPath());
                    if (png.length < 100) throw new IOException("The TV didn't give a screenshot (some apps block it).");
                    done(cb, "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                } catch (Exception e) {
                    fail(cb, e);
                }
            });
        }

        @JavascriptInterface
        public void fetch(String cb, String url) {
            ioExec.execute(() -> {
                try {
                    done(cb, fetchText(url));
                } catch (Exception e) {
                    fail(cb, e);
                }
            });
        }

        /** Looks for TVs on this Wi-Fi with ADB port 5555 open. */
        @JavascriptInterface
        public void scan(String cb) {
            ioExec.execute(() -> {
                String ip = localIPv4();
                if (ip == null) {
                    fail(cb, "Connect this phone to Wi-Fi first.");
                    return;
                }
                String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                ExecutorService pool = Executors.newFixedThreadPool(48);
                List<Future<String>> jobs = new ArrayList<>();
                for (int i = 1; i < 255; i++) {
                    final String host = prefix + i;
                    if (host.equals(ip)) continue;
                    jobs.add(pool.submit(() -> {
                        try (Socket sk = new Socket()) {
                            sk.connect(new InetSocketAddress(host, 5555), 600);
                            return host;
                        } catch (IOException e) {
                            return null;
                        }
                    }));
                }
                JSONArray found = new JSONArray();
                for (Future<String> f : jobs) {
                    try {
                        String h = f.get(5, TimeUnit.SECONDS);
                        if (h != null) found.put(h);
                    } catch (Exception ignored) {
                    }
                }
                pool.shutdownNow();
                done(cb, found);
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {
                }
            });
        }
    }

    private void pick(String cb, int req) {
        pickCallback = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (req == REQ_PICK_APK) {
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/vnd.android.package-archive", "application/octet-stream"});
        }
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            pickCallback = null;
            fail(cb, "No file picker on this phone");
        }
    }
}
