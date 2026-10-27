package org.x04g.home.adminweb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.x04g.home.config.CameraConfig;
import org.x04g.home.config.CameraDraft;
import org.x04g.home.config.ConfigException;
import org.x04g.home.config.ConfigRepository;
import org.x04g.home.config.ConfigSnapshot;

/** Explicit, temporary configuration only. Construction does not open a listener or touch Keystore. */
public final class TemporaryAdminWeb implements AutoCloseable {
    public static final long LIFETIME_MS = 300000, IDLE_MS = 90000;
    public static final int MAX_ASSET = 65536, MAX_RESPONSE = 131072;
    private static final long REQUEST_MS = 5000;
    private static final AtomicReference<TemporaryAdminWeb> OWNER = new AtomicReference<TemporaryAdminWeb>();
    private static final SecureRandom RANDOM = new SecureRandom();
    public enum State { NEW, STARTING, HTTP, PAIRED, STOPPED, FAILED }
    public interface Listener {
        /** All callbacks run on the main looper. No camera transport is started here. */
        void onState(State state);
        void onReady(Descriptor descriptor);
        /** Display only on the device; never put this code or session material into a URL. */
        void onPairingCode(String eightDigits);
        /** Ask the user on the physical device before running its existing bounded manual test. */
        void onManualTestRequested(String cameraId, long revision);
    }
    public static final class Descriptor {
        public final String url;
        public final long expiresAtElapsedRealtime;
        private Descriptor(String url, long expires) {
            this.url = url;
            expiresAtElapsedRealtime = expires;
        }
    }
    private final Context context;
    private final ConfigRepository repository;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sockets = new Object();
    private final AtomicBoolean manualTestPending = new AtomicBoolean();
    private volatile State state = State.NEW;
    private volatile boolean stopping, done;
    private volatile long expiry, idleDeadline, requestDeadline;
    private volatile ServerSocket server;
    private volatile Socket transport;
    private volatile String authority = "", origin = "";
    private InetAddress address;
    private String interfaceName;
    private PairingSession pairing;
    private long nextInterfaceCheck;

    public TemporaryAdminWeb(Context context, ConfigRepository repository, Listener listener) {
        if (context == null || repository == null || listener == null) throw new IllegalArgumentException();
        this.context = context.getApplicationContext(); this.repository = repository; this.listener = listener;
    }
    public State state() { return state; }

    /** Asynchronous, explicit action: temporary HTTP configuration on the selected private LAN. */
    public synchronized boolean startHttp(String numericIpv4) {
        return begin(numericIpv4);
    }
    /** Native rejection/cancellation or completion of the bounded test releases its slot. Does not run a test. */
    public void acknowledgeManualTestRequest() { manualTestPending.set(false); }
    private boolean begin(String ip) {
        if (stopping || state != State.NEW || !OWNER.compareAndSet(null, this)) return false;
        try {
            address = privateIpv4(ip);
            NetworkInterface net = NetworkInterface.getByInetAddress(address);
            if (net == null || !net.isUp() || net.isLoopback()) throw new IOException("LAN_UNAVAILABLE");
            interfaceName = net.getName();
        } catch (Exception error) { OWNER.compareAndSet(this, null); return false; }
        expiry = now() + LIFETIME_MS; idleDeadline = now() + IDLE_MS;
        setState(State.STARTING);
        Thread worker = new Thread(null, new Runnable() { @Override public void run() { runServer(); } },
            "x04g-adminweb", 262144L);
        Thread deadline = new Thread(null, new Runnable() { @Override public void run() { watch(); } },
            "x04g-adminweb-deadline", 131072L);
        worker.setDaemon(true); deadline.setDaemon(true);
        try { deadline.start(); worker.start(); return true; }
        catch (RuntimeException error) { stopping = true; done = true; closeNetwork(); OWNER.compareAndSet(this, null); setState(State.FAILED); return false; }
    }
    private void runServer() {
        boolean failure = false;
        try {
            byte[] html = readAsset("adminweb/index.html");
            ServerSocket socket = new ServerSocket();
            // Own the descriptor before bind/listen/options can fail. Every startup failure and
            // concurrent stop then reaches closeNetwork, including a partially bound socket.
            synchronized (sockets) {
                if (stopping) { socket.close(); return; } server = socket;
            }
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(address, 0), 1); socket.setSoTimeout(250);
            synchronized (sockets) { if (stopping) { socket.close(); return; } }
            authority = address.getHostAddress() + ":" + socket.getLocalPort();
            origin = "http://" + authority;
            pairing = new PairingSession(); final String code = pairing.codeForDevice();
            main.post(new Runnable() { @Override public void run() { if (!stopping) listener.onPairingCode(code); } });
            setState(State.HTTP);
            final Descriptor descriptor = new Descriptor(origin + "/", expiry);
            main.post(new Runnable() { @Override public void run() { if (!stopping) listener.onReady(descriptor); } });
            while (!stopping) {
                Socket raw = null;
                try {
                    checkInterface(); if (stopping) break;
                    raw = socket.accept(); raw.setTcpNoDelay(true); raw.setSoTimeout((int) REQUEST_MS);
                    raw.setSoLinger(false, 0);
                    synchronized (sockets) {
                        if (stopping) { raw.close(); continue; }
                        transport = raw; requestDeadline = now() + REQUEST_MS;
                    }
                    if (!privatePeer(raw.getInetAddress())) continue;
                    if (!pairing.acceptsPeer(raw.getInetAddress().getAddress())) continue;
                    try (HttpMessage request = HttpMessage.read(raw.getInputStream(), new HttpMessage.Deadline() {
                        @Override public void check() throws IOException { checkDeadline(); }
                    })) {
                        if (!authority.equals(request.headers.get("host"))) throw new HttpMessage.BadRequest(400);
                        handleHttp(request, raw.getOutputStream(), html, raw.getInetAddress());
                    }
                } catch (SocketTimeoutException timeout) {
                    // Absolute checks/watchdog also bound repeated partial reads and stalled writes.
                } catch (HttpMessage.BadRequest rejected) {
                    // Reject malformed or wrong-Host requests by closing this exact connection.
                } catch (IOException disconnected) {
                    if (!stopping && socket.isClosed()) throw disconnected;
                } finally {
                    synchronized (sockets) {
                        if (transport == raw) { transport = null; requestDeadline = 0; }
                    }
                    close(raw);
                }
            }
        } catch (Exception error) { failure = !stopping; }
        finally {
            stopping = true; closeNetwork();
            if (pairing != null) { pairing.close(); pairing = null; }
            manualTestPending.set(false); done = true; OWNER.compareAndSet(this, null);
            setState(failure ? State.FAILED : State.STOPPED);
        }
    }
    private void handleHttp(HttpMessage request, OutputStream output, byte[] html, InetAddress peer) throws IOException {
        if ("GET".equals(request.method) && "/".equals(request.path)) {
            String nonce = PairingSession.hex(random(18));
            byte[] page = new String(html, StandardCharsets.UTF_8).replace("{{nonce}}", nonce).getBytes(StandardCharsets.UTF_8);
            respond(output, 200, "text/html; charset=utf-8", page,
                "Content-Security-Policy: default-src 'none'; script-src 'nonce-" + nonce
                + "'; style-src 'nonce-" + nonce + "'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'\r\n");
            return;
        }
        if ("POST".equals(request.method)) {
            if (!origin.equals(request.headers.get("origin"))
                    || !"application/json".equalsIgnoreCase(request.headers.get("content-type"))) {
                error(output, 403, "ORIGIN_OR_TYPE"); return;
            }
        }
        try {
            if ("POST".equals(request.method) && "/api/pair".equals(request.path)) {
                JSONObject json = json(request); String candidate = json.optString("code", "");
                PairingSession.Result paired = pairing.pair(candidate, peer.getAddress());
                if (paired == PairingSession.Result.ALREADY_PAIRED) { error(output, 409, "ALREADY_PAIRED"); return; }
                if (paired != PairingSession.Result.PAIRED) {
                    error(output, 403, "PAIRING_FAILED"); if (paired == PairingSession.Result.LOCKED_OUT) stop(); return;
                }
                JSONObject result = new JSONObject(); result.put("csrf", pairing.csrfToken());
                result.put("manual_test", "confirmation_on_device_required");
                jsonResponse(output, 200, result, "Set-Cookie: x04g_session=" + pairing.sessionToken()
                    + "; Path=/; HttpOnly; SameSite=Strict\r\n");
                idleDeadline = now() + IDLE_MS; setState(State.PAIRED); return;
            }
            if (!pairing.authenticated(request.headers.get("cookie"), peer.getAddress())) { error(output, 401, "PAIRING_REQUIRED"); return; }
            if ("POST".equals(request.method) && !pairing.csrfMatches(request.headers.get("x-csrf-token"))) {
                error(output, 403, "CSRF"); return;
            }
            idleDeadline = now() + IDLE_MS;
            if ("GET".equals(request.method) && "/api/cameras".equals(request.path)) {
                jsonResponse(output, 200, snapshot(repository.load()), "");
            } else if ("POST".equals(request.method) && "/api/save".equals(request.path)) {
                save(json(request), output);
            } else if ("POST".equals(request.method) && "/api/delete".equals(request.path)) {
                JSONObject body = json(request);
                jsonResponse(output, 200, snapshot(repository.remove(body.getString("id"), revision(body))), "");
            } else if ("POST".equals(request.method) && "/api/reorder".equals(request.path)) {
                JSONObject body = json(request); JSONArray rows = body.getJSONArray("ids");
                if (rows.length() > 12) throw new HttpMessage.BadRequest(400);
                List<String> ids = new ArrayList<String>();
                for (int i = 0; i < rows.length(); i++) ids.add(rows.getString(i));
                jsonResponse(output, 200, snapshot(repository.reorder(ids, revision(body))), "");
            } else if ("POST".equals(request.method) && "/api/test-request".equals(request.path)) {
                JSONObject body = json(request); ConfigSnapshot current = repository.load();
                final long revision = revision(body); final String id = body.getString("id");
                if (current.revision != revision) throw new ConfigException(ConfigException.Code.CONFLICT);
                if (current.find(id) == null) throw new ConfigException(ConfigException.Code.NOT_FOUND);
                if (!manualTestPending.compareAndSet(false, true)) { error(output, 409, "DEVICE_CONFIRMATION_PENDING"); return; }
                main.post(new Runnable() { @Override public void run() {
                    if (!stopping && manualTestPending.get()) listener.onManualTestRequested(id, revision);
                } });
                JSONObject result = new JSONObject(); result.put("state", "device_confirmation_required");
                result.put("test_executed", false); jsonResponse(output, 202, result, "");
            } else if ("POST".equals(request.method) && "/api/close".equals(request.path)) {
                JSONObject result = new JSONObject(); result.put("state", "closing"); jsonResponse(output, 200, result, ""); stop();
            } else error(output, 404, "NOT_FOUND");
        } catch (ConfigException error) {
            int status = error.code == ConfigException.Code.CONFLICT ? 409
                : error.code == ConfigException.Code.NOT_FOUND ? 404
                : error.code == ConfigException.Code.STORAGE || error.code == ConfigException.Code.CORRUPT
                    || error.code == ConfigException.Code.SECRET_UNAVAILABLE ? 500 : 400;
            error(output, status, error.code.name());
        } catch (JSONException malformed) { error(output, 400, "INVALID_JSON"); }
    }
    private void save(JSONObject json, OutputStream output) throws ConfigException, JSONException, IOException {
        long revision = revision(json); ConfigSnapshot before = repository.load();
        if (before.revision != revision) throw new ConfigException(ConfigException.Code.CONFLICT);
        String id = json.optString("id", ""); CameraConfig previous = before.find(id);
        CameraDraft draft = previous == null ? new CameraDraft() : new CameraDraft(previous);
        draft.id = id; draft.label = json.getString("label"); draft.host = json.getString("host");
        Object port = json.get("port");
        if (!(port instanceof Number) || ((Number) port).doubleValue() != ((Number) port).intValue())
            throw new HttpMessage.BadRequest(400);
        draft.port = ((Number) port).intValue(); draft.path = json.getString("path");
        String username = json.optString("username", ""); if (!username.isEmpty()) draft.username = username;
        draft.mac = json.optString("mac", ""); draft.onvifUuid = json.optString("onvif_uuid", "");
        draft.model = json.optString("model", ""); draft.enabled = json.getBoolean("enabled");
        // A browser cannot assert that a camera was physically verified or mark its credentials trusted.
        draft.needsVerification = true; draft.verifiedAtMillis = 0;
        String input = json.optString("password", "");
        if (input.length() > 512) throw new HttpMessage.BadRequest(400);
        char[] password = input.isEmpty() ? null : input.toCharArray();
        try { jsonResponse(output, 200, snapshot(repository.upsert(draft, password, revision)), ""); }
        finally { if (password != null) Arrays.fill(password, '\0'); }
    }
    private static long revision(JSONObject json) throws JSONException, HttpMessage.BadRequest {
        Object value = json.get("revision");
        if (!(value instanceof Number)) throw new HttpMessage.BadRequest(400);
        long n = ((Number) value).longValue();
        if (n < 0 || ((Number) value).doubleValue() != (double) n) throw new HttpMessage.BadRequest(400);
        return n;
    }
    private static JSONObject snapshot(ConfigSnapshot snapshot) throws JSONException {
        JSONObject result = new JSONObject(); result.put("revision", snapshot.revision);
        JSONArray rows = new JSONArray();
        for (CameraConfig camera : snapshot.cameras) {
            JSONObject row = new JSONObject(); row.put("id", camera.id); row.put("label", camera.label);
            row.put("host", camera.host); row.put("port", camera.port); row.put("path", camera.path);
            row.put("mac", camera.mac); row.put("onvif_uuid", camera.onvifUuid); row.put("model", camera.model);
            row.put("enabled", camera.enabled); row.put("needs_verification", camera.needsVerification);
            row.put("verified_at_millis", camera.verifiedAtMillis);
            row.put("username_configured", !camera.username.isEmpty()); row.put("password_configured", camera.hasPassword);
            rows.put(row);
        }
        result.put("cameras", rows); return result;
    }
    private static JSONObject json(HttpMessage request) throws IOException, JSONException {
        if (request.body.length == 0) throw new HttpMessage.BadRequest(400);
        String text = request.utf8(); JsonBounds.check(text); return new JSONObject(text);
    }
    private void jsonResponse(OutputStream output, int status, JSONObject json, String extra) throws IOException {
        respond(output, status, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8), extra);
    }
    private void error(OutputStream output, int status, String code) throws IOException {
        // All codes are internal constants/enums. Never include exception text or client/provider input.
        respond(output, status, "application/json; charset=utf-8",
            ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.US_ASCII), "");
    }
    private void respond(OutputStream output, int status, String type, byte[] body, String extra) throws IOException {
        checkDeadline(); if (body.length > MAX_RESPONSE) throw new IOException("RESPONSE_LIMIT");
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\nContent-Type: " + type
            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n" + extra + "\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII)); checkDeadline();
        output.write(body); output.flush(); checkDeadline();
    }
    private static String reason(int status) {
        switch (status) { case 200: return "OK"; case 202: return "Accepted"; case 400: return "Bad Request";
            case 401: return "Unauthorized"; case 403: return "Forbidden"; case 404: return "Not Found";
            case 405: return "Method Not Allowed"; case 409: return "Conflict";
            case 500: return "Internal Server Error"; default: return "Rejected"; }
    }
    private byte[] readAsset(String path) throws IOException {
        final Context assets;
        try {
            // Bootstrap verifies the companion APK pin before integration. Storage/Keystore stay in
            // the executing OEM context; a package asset context grants no access to its private data.
            assets = context.createPackageContext("org.x04g.home", Context.CONTEXT_IGNORE_SECURITY);
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            throw new IOException("ASSET_PACKAGE_UNAVAILABLE");
        }
        try (InputStream input = assets.getAssets().open(path)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] chunk = new byte[2048]; int n;
            while ((n = input.read(chunk)) != -1) {
                if (b.size() + n > MAX_ASSET) throw new IOException("ASSET_LIMIT"); b.write(chunk, 0, n);
            }
            return b.toByteArray();
        }
    }
    private void watch() {
        while (!done) {
            long now = now();
            if (now >= expiry || now >= idleDeadline) stop();
            long request = requestDeadline;
            if (request != 0 && now >= request) {
                synchronized (sockets) {
                    // Always close the currently leased exact transport, never a process/global socket.
                    if (requestDeadline != 0 && now() >= requestDeadline) close(transport);
                }
            }
            try { Thread.sleep(100); } catch (InterruptedException cancelled) { stop(); }
        }
    }
    private void checkInterface() {
        if (now() < nextInterfaceCheck) return; nextInterfaceCheck = now() + 1000;
        try {
            NetworkInterface net = NetworkInterface.getByName(interfaceName);
            if (net == null || !net.isUp() || !java.util.Collections.list(net.getInetAddresses()).contains(address)) stop();
        } catch (Exception unavailable) { stop(); }
    }
    private void checkDeadline() throws IOException {
        long n = now();
        if (stopping || n >= expiry || n >= idleDeadline || requestDeadline == 0 || n >= requestDeadline)
            throw new SocketTimeoutException("DEADLINE");
    }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static InetAddress privateIpv4(String text) throws IOException {
        if (text == null || !text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("IPV4_REQUIRED");
        String[] parts = text.split("\\."); byte[] bytes = new byte[4]; int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            n[i] = Integer.parseInt(parts[i]); if (n[i] > 255) throw new IOException("IPV4_REQUIRED"); bytes[i] = (byte) n[i];
        }
        if (!(n[0] == 10 || n[0] == 172 && n[1] >= 16 && n[1] <= 31 || n[0] == 192 && n[1] == 168))
            throw new IOException("PRIVATE_LAN_REQUIRED");
        return InetAddress.getByAddress(bytes);
    }
    private static boolean privatePeer(InetAddress peer) {
        // Authentication is the one-time code and cookie, never subnet membership. Do not accept WAN/IPv6 peers.
        if (!(peer instanceof Inet4Address)) return false;
        byte[] b = peer.getAddress(); int a = b[0] & 255, c = b[1] & 255;
        return a == 10 || a == 172 && c >= 16 && c <= 31 || a == 192 && c == 168;
    }
    private static byte[] random(int size) { byte[] b = new byte[size]; RANDOM.nextBytes(b); return b; }
    private void setState(final State next) {
        state = next; main.post(new Runnable() { @Override public void run() { listener.onState(next); } });
    }
    private void closeNetwork() {
        synchronized (sockets) { close(transport); close(server); transport = null; server = null; requestDeadline = 0; }
    }
    private static void close(java.io.Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }
    public void stop() { stopping = true; closeNetwork(); }
    @Override public void close() { stop(); }
}
