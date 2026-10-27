package org.x04g.home.adminweb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/** One temporary client identity; all calls are serialized by the server's one worker. */
final class PairingSession implements AutoCloseable {
    enum Result { PAIRED, FAILED, LOCKED_OUT, ALREADY_PAIRED }
    private final char[] code;
    private byte[] session, csrf, peer;
    private int failures;
    private boolean closed;
    PairingSession() {
        code = String.format(Locale.US, "%08d", new SecureRandom().nextInt(100000000)).toCharArray();
    }
    String codeForDevice() { return closed || peer != null ? "" : new String(code); }
    Result pair(String candidate, byte[] address) {
        if (closed) return Result.LOCKED_OUT;
        if (peer != null) return Result.ALREADY_PAIRED;
        if (!same(candidate, new String(code))) {
            if (++failures >= 5) { close(); return Result.LOCKED_OUT; }
            return Result.FAILED;
        }
        if (address == null || address.length != 4) return Result.FAILED;
        peer = address.clone(); session = random(32); csrf = random(32); Arrays.fill(code, '\0');
        return Result.PAIRED;
    }
    boolean acceptsPeer(byte[] candidate) { return !closed && (peer == null || Arrays.equals(peer, candidate)); }
    boolean authenticated(String cookie, byte[] address) {
        if (closed || peer == null || !Arrays.equals(peer, address) || cookie == null || cookie.length() > 1024) return false;
        String found = null;
        for (String part : cookie.split(";")) {
            String field = part.trim();
            if (field.startsWith("x04g_session=")) {
                if (found != null) return false; found = field.substring("x04g_session=".length());
            }
        }
        return same(found, sessionToken());
    }
    boolean csrfMatches(String candidate) { return !closed && peer != null && same(candidate, csrfToken()); }
    String sessionToken() { return closed ? "" : hex(session); }
    String csrfToken() { return closed ? "" : hex(csrf); }
    private static boolean same(String a, String b) {
        if (a == null || b == null || a.length() > 1024 || b.length() > 1024) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] random(int size) { byte[] bytes = new byte[size]; new SecureRandom().nextBytes(bytes); return bytes; }
    static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] chars = "0123456789abcdef".toCharArray(); char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { out[i * 2] = chars[(bytes[i] >>> 4) & 15]; out[i * 2 + 1] = chars[bytes[i] & 15]; }
        return new String(out);
    }
    @Override public void close() {
        closed = true; Arrays.fill(code, '\0');
        if (session != null) Arrays.fill(session, (byte) 0);
        if (csrf != null) Arrays.fill(csrf, (byte) 0);
        if (peer != null) Arrays.fill(peer, (byte) 0);
        session = null; csrf = null; peer = null;
    }
}
