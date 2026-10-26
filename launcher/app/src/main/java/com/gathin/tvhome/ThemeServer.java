package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A very small web server that runs only while ThemeReceiveActivity is on screen. A phone on the same network opens
 * the TV's address, types the PIN shown on the TV and sends a config.json, a theme .zip or pictures; each file goes
 * through {@link ThemeIntake}. Only private-network clients are served, every upload needs the PIN, and after
 * {@link #MAX_FAILED_PINS} wrong PINs it refuses everything until it is started again. Uploads are the raw body of a
 * POST (no multipart), so the parser has to know only a request line, headers and a length.
 */
final class ThemeServer {
    interface Listener {
        /** Called from a server thread after each file: its name, whether it was taken, and the words to show. */
        void onReceived(String fileName, boolean ok, String message);
    }

    static final int FIRST_PORT = 8080;
    static final int LAST_PORT = 8089;
    static final int MAX_FAILED_PINS = 5;
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 60;
    private static final int TIMEOUT_MS = 30_000;
    private static final int BACKLOG = 8;
    private static final int MAX_QUEUED = 8;
    private static final int MAX_ACCEPT_FAILURES = 25;
    /** The server stops by itself when nobody has connected for this long (the owner forgot it, or left the screen on). */
    static final long IDLE_TIMEOUT_MS = 10L * 60 * 1000;
    private static final long MAX_DRAIN_BYTES = 64L * 1024 * 1024;

    private final Context app;
    private final String pin;
    private final Listener listener;
    private final AtomicInteger failedPins = new AtomicInteger();
    private final Object importLock = new Object();
    private ExecutorService pool;
    private ServerSocket server;
    private volatile boolean stopped;
    private final long idleMillis;
    private final AtomicInteger active = new AtomicInteger();
    private volatile long lastActivity = nowMillis();
    private volatile Runnable onIdle;

    ThemeServer(Context ctx, String pin, Listener listener) {
        this(ctx, pin, listener, IDLE_TIMEOUT_MS);
    }

    /** {@code idleMillis} is how long without a connection ends the server (a short one lets tests see it stop). */
    ThemeServer(Context ctx, String pin, Listener listener, long idleMillis) {
        this.app = ctx.getApplicationContext();
        this.pin = pin;
        this.listener = listener;
        this.idleMillis = idleMillis;
    }

    /** Called, from the server's own thread, after the server stopped because nobody used it for the idle time. */
    void setOnIdle(Runnable onIdle) {
        this.onIdle = onIdle;
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * True when the server should stop: nothing is being served and the last connection was {@code idleMillis} ago or
     * more. A transfer in progress never counts as idle, however long it takes.
     */
    static boolean idleExpired(long now, long lastActivity, long idleMillis, int activeConnections) {
        return activeConnections == 0 && now - lastActivity >= idleMillis;
    }

    /** A new six-digit PIN. */
    static String newPin() {
        return String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
    }

    /** Starts listening on the first free port from {@link #FIRST_PORT}; returns it. */
    int start() throws IOException {
        IOException last = null;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                server = new ServerSocket(port, BACKLOG);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no free port");
        // Three workers and a short queue: a burst of connections is refused (closed) instead of piling up.
        pool = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_QUEUED), r -> {
            Thread t = new Thread(r, "theme-server");
            t.setDaemon(true);
            return t;
        });
        Thread acceptor = new Thread(() -> {
            int failures = 0;
            while (!stopped) {
                Socket socket = null;
                try {
                    socket = server.accept();
                    failures = 0;
                    final Socket accepted = socket;
                    pool.execute(() -> handle(accepted));
                } catch (RejectedExecutionException busy) {
                    closeQuietly(socket);   // too many at once: drop this one
                } catch (IOException | RuntimeException e) {
                    closeQuietly(socket);
                    if (stopped || ++failures > MAX_ACCEPT_FAILURES) return;   // do not spin on a broken socket
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }, "theme-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        lastActivity = nowMillis();
        Thread watchdog = new Thread(() -> {
            while (!stopped) {
                long now = nowMillis();
                if (idleExpired(now, lastActivity, idleMillis, active.get())) {
                    stop();
                    Runnable done = onIdle;
                    if (done != null) done.run();
                    return;
                }
                long wait = active.get() > 0 ? 1000 : idleMillis - (now - lastActivity);
                try {
                    Thread.sleep(Math.max(100, Math.min(wait, 5000)));
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "theme-server-idle");
        watchdog.setDaemon(true);
        watchdog.start();
        return server.getLocalPort();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    void stop() {
        stopped = true;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        if (pool != null) pool.shutdownNow();
    }

    /** The IPv4 addresses this TV has on a private network (Wi-Fi or Ethernet), most useful first. */
    static List<String> localAddresses() {
        List<String> found = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return found;
            for (NetworkInterface nif : Collections.list(interfaces)) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) found.add(address.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
            // no interfaces to read: no address to show
        }
        return found;
    }

    /** True for a client on this network: private, link-local or loopback addresses only. */
    static boolean isLocalClient(InetAddress a) {
        if (a == null) return false;
        if (a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()) return true;
        byte[] raw = a.getAddress();
        return raw.length == 16 && (raw[0] & 0xFE) == 0xFC;   // IPv6 unique local, fc00::/7
    }

    private void handle(Socket socket) {
        active.incrementAndGet();
        lastActivity = nowMillis();
        try {
            serve(socket);
        } finally {
            lastActivity = nowMillis();   // the idle time counts from the end of the last request
            active.decrementAndGet();
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(TIMEOUT_MS);
            if (!isLocalClient(s.getInetAddress())) return;
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) {
                respond(out, 400, "Bad Request", "text/plain", "bad request");
                return;
            }
            Map<String, String> headers = readHeaders(in);
            if (headers == null) {
                respond(out, 431, "Request Header Fields Too Large", "text/plain", "too many headers");
                return;
            }
            String method = parts[0];
            String path = parts[1];
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            Resources res = app.getResources();
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                respond(out, 200, "OK", "text/html", page(res));
            } else if ("GET".equals(method) && "/favicon.ico".equals(path)) {
                respond(out, 204, "No Content", "text/plain", "");
            } else if ("POST".equals(method) && "/upload".equals(path)) {
                upload(in, out, headers, res);
            } else {
                respond(out, 404, "Not Found", "text/plain", "not found");
            }
        } catch (IOException | RuntimeException ignored) {
            // a phone that went away mid-request: nothing to answer
        }
    }

    private void upload(InputStream in, OutputStream out, Map<String, String> headers, Resources res) throws IOException {
        long length = parseLength(headers.get("content-length"));
        if (failedPins.get() >= MAX_FAILED_PINS) {
            refuse(in, out, length, 429, "Too Many Requests", null, res.getString(R.string.theme_web_locked));
            return;
        }
        String sent = headers.get("x-pin");
        if (sent == null || !MessageDigest.isEqual(sent.trim().getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8))) {
            failedPins.incrementAndGet();
            refuse(in, out, length, 403, "Forbidden", null, res.getString(R.string.theme_web_wrong_pin));
            return;
        }
        String name = fileName(headers.get("x-file-name"));
        ThemeImport.Kind kind = ThemeImport.kind(name);
        if (kind == ThemeImport.Kind.UNKNOWN) {
            refuse(in, out, length, 415, "Unsupported Media Type", name, ThemeIntake.message(res, ThemeImport.Problem.UNSUPPORTED));
            return;
        }
        if (length < 0) {
            respond(out, 411, "Length Required", "text/plain", "length required");
            return;
        }
        if (length == 0) {
            refuse(in, out, 0, 400, "Bad Request", name, ThemeIntake.message(res, ThemeImport.Problem.EMPTY));
            return;
        }
        if (length > ThemeImport.maxBytes(kind)) {
            refuse(in, out, length, 413, "Payload Too Large", name, ThemeIntake.message(res, ThemeImport.Problem.TOO_BIG));
            return;
        }
        File temp = new File(app.getCacheDir(), "theme-up-" + System.nanoTime() + ".tmp");
        try {
            ThemeIntake.copy(new Limited(in, length), temp, length);
            if (temp.length() != length) {
                refuse(in, out, 0, 400, "Bad Request", name, ThemeIntake.message(res, ThemeImport.Problem.UNREADABLE));
                return;
            }
            ThemeIntake.Result result;
            synchronized (importLock) {
                result = ThemeIntake.ingest(app, temp, name);
            }
            if (listener != null) listener.onReceived(name, true, ThemeIntake.summary(res, result));
            respond(out, 200, "OK", "text/plain", ThemeIntake.summary(res, result));
        } catch (ThemeImport.ImportException e) {
            String message = ThemeIntake.message(res, e.problem);
            if (listener != null) listener.onReceived(name, false, message);
            respond(out, 400, "Bad Request", "text/plain", message);
        } finally {
            temp.delete();
        }
    }

    /**
     * Answers a request that is refused before its body is read, then reads (and drops) the body: a browser that is
     * still sending would otherwise see the connection reset and never show the answer.
     */
    private void refuse(InputStream in, OutputStream out, long pending, int code, String reason, String name, String message)
            throws IOException {
        if (name != null && listener != null) listener.onReceived(name, false, message);
        respond(out, code, reason, "text/plain", message);
        long left = Math.min(Math.max(pending, 0), MAX_DRAIN_BYTES);
        byte[] buffer = new byte[16 * 1024];
        try {
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) break;
                left -= n;
            }
        } catch (IOException ignored) {
            // the phone gave up sending: the answer was already written
        }
    }

    /** The file name a browser sent (percent-encoded), reduced to its last path segment. */
    static String fileName(String header) {
        if (header == null) return "";
        String name;
        try {
            name = URLDecoder.decode(header, "UTF-8");
        } catch (Exception e) {
            return "";
        }
        name = name.replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1).trim();
    }

    private static long parseLength(String header) {
        if (header == null) return -1;
        try {
            long n = Long.parseLong(header.trim());
            return n < 0 ? -1 : n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i <= MAX_HEADERS; i++) {
            String line = readLine(in);
            if (line == null) return headers;
            if (line.isEmpty()) return headers;
            int colon = line.indexOf(':');
            if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        return null;
    }

    /** One line without its CR LF, or null at the end of the stream; a line over {@link #MAX_LINE} bytes is an error. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > MAX_LINE) throw new IOException("line too long");
        }
        if (c < 0 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(OutputStream out, int code, String reason, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** At most {@code left} bytes of a stream, so the body of one request cannot run into anything after it. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private long left;

        Limited(InputStream in, long left) {
            this.in = in;
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(buffer, offset, (int) Math.min(count, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** The page a phone sees: PIN, a file chooser and a log, in the TV's language. */
    static String page(Resources res) {
        String sending = JSONObject.quote(res.getString(R.string.theme_web_sending));
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(res.getString(R.string.theme_web_title)) + "</title>"
                + "<style>body{font-family:sans-serif;max-width:32em;margin:2em auto;padding:0 1em;background:#101419;color:#fff}"
                + "input,button{font-size:1.1em;margin:.4em 0;width:100%;box-sizing:border-box;padding:.6em}"
                + "button{background:#4dae6d;border:0;color:#fff;border-radius:.4em}button:disabled{opacity:.5}"
                + "li{margin:.3em 0;word-break:break-all}</style></head><body>"
                + "<h2>" + esc(res.getString(R.string.theme_web_title)) + "</h2>"
                + "<p>" + esc(res.getString(R.string.theme_web_hint)) + "</p>"
                + "<label>" + esc(res.getString(R.string.theme_web_pin_label)) + "</label>"
                + "<input id=\"pin\" inputmode=\"numeric\" autocomplete=\"off\">"
                + "<input id=\"f\" type=\"file\" multiple accept=\".json,.zip,image/*\">"
                + "<button id=\"go\">" + esc(res.getString(R.string.theme_web_send)) + "</button><ul id=\"log\"></ul>"
                + "<script>var go=document.getElementById('go'),log=document.getElementById('log');"
                + "function add(t){var li=document.createElement('li');li.textContent=t;log.appendChild(li);}"
                + "go.onclick=async function(){var pin=document.getElementById('pin').value.trim();"
                + "var files=document.getElementById('f').files;if(!files.length)return;go.disabled=true;"
                + "for(var i=0;i<files.length;i++){var f=files[i];add(" + sending + "+' '+f.name);"
                + "try{var r=await fetch('/upload',{method:'POST',headers:{'X-Pin':pin,'X-File-Name':encodeURIComponent(f.name)},body:f});"
                + "var t=await r.text();add((r.ok?'\\u2713 ':'\\u2717 ')+f.name+': '+t);if(r.status==403||r.status==429)break;}"
                + "catch(e){add('\\u2717 '+f.name+': '+e);}}go.disabled=false;};</script></body></html>";
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
