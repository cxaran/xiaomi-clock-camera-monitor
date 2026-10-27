package org.x04g.home.adminweb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Deliberately one request/connection, no chunking, pipelining or implicit request retries. */
final class HttpMessage implements AutoCloseable {
    static final int MAX_HEADERS = 8192, MAX_BODY = 65536;
    interface Deadline { void check() throws IOException; }
    static final class BadRequest extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        BadRequest(int status) { super("HTTP_REJECTED"); this.status = status; }
    }
    final String method, path;
    final Map<String, String> headers;
    final byte[] body;
    private HttpMessage(String method, String path, Map<String, String> headers, byte[] body) {
        this.method = method; this.path = path; this.headers = headers; this.body = body;
    }
    static HttpMessage read(InputStream input, Deadline deadline) throws IOException {
        int[] used = new int[] { 0 };
        String first = line(input, deadline, used, 2048);
        String[] parts = first.split(" ", -1);
        if (parts.length != 3 || !"HTTP/1.1".equals(parts[2])
                || !("GET".equals(parts[0]) || "POST".equals(parts[0]))
                || !parts[1].matches("/[A-Za-z0-9/._-]*")) throw new BadRequest(400);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (;;) {
            String row = line(input, deadline, used, MAX_HEADERS);
            if (row.isEmpty()) break;
            int colon = row.indexOf(':');
            if (colon <= 0 || headers.size() >= 64) throw new BadRequest(400);
            String name = row.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || headers.containsKey(name))
                throw new BadRequest(400);
            String value = row.substring(colon + 1).trim();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i); if (c < 32 || c > 126) throw new BadRequest(400);
            }
            headers.put(name, value);
        }
        if (!headers.containsKey("host") || headers.containsKey("transfer-encoding")
                || headers.containsKey("expect") || headers.containsKey("upgrade")
                || headers.containsKey("content-encoding")) throw new BadRequest(400);
        int length = 0;
        if (headers.containsKey("content-length")) {
            String n = headers.get("content-length");
            if (!n.matches("[0-9]{1,7}")) throw new BadRequest(400);
            length = Integer.parseInt(n); if (length > MAX_BODY) throw new BadRequest(413);
        }
        if ("GET".equals(parts[0]) && length != 0) throw new BadRequest(400);
        if ("POST".equals(parts[0]) && !headers.containsKey("content-length")) throw new BadRequest(411);
        byte[] body = new byte[length];
        try {
            for (int offset = 0; offset < length;) {
                deadline.check(); int n = input.read(body, offset, length - offset); deadline.check();
                if (n < 0) throw new BadRequest(400); if (n == 0) continue; offset += n;
            }
            deadline.check(); return new HttpMessage(parts[0], parts[1], headers, body);
        } catch (IOException error) { Arrays.fill(body, (byte) 0); throw error; }
    }
    String utf8() throws BadRequest {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException error) { throw new BadRequest(400); }
    }
    private static String line(InputStream input, Deadline deadline, int[] used, int max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (;;) {
            deadline.check(); int c = input.read(); deadline.check();
            if (++used[0] > MAX_HEADERS) throw new BadRequest(431);
            if (c < 0) throw new BadRequest(400);
            if (c == 13) {
                deadline.check(); int end = input.read(); deadline.check();
                if (++used[0] > MAX_HEADERS) throw new BadRequest(431);
                if (end != 10) throw new BadRequest(400); break;
            }
            if (c < 32 || c > 126 || bytes.size() >= max) throw new BadRequest(400);
            bytes.write(c);
        }
        return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
    }
    @Override public void close() { Arrays.fill(body, (byte) 0); }
}
