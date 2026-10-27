package org.x04g.home.gate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Safe UI metadata. A pulse remote has no verified open/closed sensor in the current profile. */
public final class GateStatus {
    public enum State { ONLINE_STATUS, ONLINE_QUERY_REJECTED, REACHABLE_UNCONFIRMED, OFFLINE, BUSY, CLOSED, ERROR }
    public enum Problem { NONE, TIMEOUT, IO, INVALID_FRAME, KEY_OR_VERSION, DEVICE_REJECTED, CANCELLED }
    public final State state;
    public final Problem problem;
    public final boolean tcpConnected;
    /** Known plaintext decrypted with the local key; Tuya3.3 CRC is NOT a keyed authentication MAC. */
    public final boolean keyConfirmed;
    public final long observedAtNanos;
    /** Only numeric DP names and type names. Never codes, values, device IDs or credentials. */
    public final Map<String, String> dpTypes;

    GateStatus(State state, Problem problem, boolean tcp, boolean key, Map<String, String> dps, long now) {
        this.state = state; this.problem = problem; tcpConnected = tcp; keyConfirmed = key;
        observedAtNanos = now;
        dpTypes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(dps));
    }

    static GateStatus simple(State state, Problem problem, boolean tcp, long now) {
        return new GateStatus(state, problem, tcp, false, Collections.<String, String>emptyMap(), now);
    }

    static Decoded inspect(byte[] plaintext) throws Exception {
        String text = new String(plaintext, StandardCharsets.UTF_8).trim();
        if (text.equals("json obj data unvalid") || text.equals("data unvalid") || text.equals("json data unvalid"))
            return new Decoded(true, true, Collections.<String, String>emptyMap(), false);
        JSONObject object = parseObject(text);
        JSONObject current = object;
        JSONObject dps = null;
        for (int depth = 0; depth < 4; depth++) {
            if (current.opt("dps") instanceof JSONObject) { dps = current.getJSONObject("dps"); break; }
            if (!(current.opt("data") instanceof JSONObject)) break;
            current = current.getJSONObject("data");
        }
        boolean rejected = object.has("error") || object.has("err") || object.has("Err")
                || object.has("success") && !object.optBoolean("success", false);
        Map<String, String> types = new LinkedHashMap<String, String>();
        if (dps != null) {
            if (dps.length() > 128) throw new IllegalArgumentException("too_many_dps");
            Iterator<String> keys = dps.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!key.matches("[0-9]{1,5}")) throw new IllegalArgumentException("invalid_dp");
                Object value = dps.get(key);
                types.put(key, value == JSONObject.NULL ? "null" : value instanceof String ? "string"
                        : value instanceof Boolean ? "boolean" : value instanceof Number ? "number" : "structured");
            }
        }
        // A decrypted arbitrary JSON object is not a known-key proof unless the expected schema is present.
        return new Decoded(dps != null || object.has("success") || rejected, rejected, types, dps != null);
    }

    static JSONObject parseObject(String text) throws Exception {
        if (text == null || text.length() == 0 || text.length() > 65536) throw new IllegalArgumentException("json_size");
        // Bound nesting before org.json recurses. This scan never exposes content in errors.
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; }
            else if (c == '"') quoted = true;
            else if (c == '\'') throw new IllegalArgumentException("json_quote");
            else if (c == '{' || c == '[') { if (++depth > 16) throw new IllegalArgumentException("json_depth"); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new IllegalArgumentException("json_shape"); }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("json_shape");
        JSONTokener input = new JSONTokener(text);
        Object value = input.nextValue();
        if (!(value instanceof JSONObject) || input.nextClean() != 0) throw new IllegalArgumentException("json_object_required");
        return (JSONObject) value;
    }

    static final class Decoded {
        final boolean keyConfirmed, rejected, hasDps;
        final Map<String, String> types;
        Decoded(boolean key, boolean rejection, Map<String, String> dps, boolean has) {
            keyConfirmed = key; rejected = rejection; types = dps; hasDps = has;
        }
    }
}
