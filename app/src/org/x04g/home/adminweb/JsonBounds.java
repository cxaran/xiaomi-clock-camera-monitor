package org.x04g.home.adminweb;

/** Preflight before recursive JSONObject parsing; not a replacement JSON parser. */
final class JsonBounds {
    private JsonBounds() { }
    static void check(String text) throws HttpMessage.BadRequest {
        boolean string = false, escaped = false, started = false, ended = false;
        int depth = 0, elements = 0, stringChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (++stringChars > 4096 || c < 32) throw new HttpMessage.BadRequest(400);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
            } else if (ended) {
                if (c != ' ' && c != '\r' && c != '\n' && c != '\t') throw new HttpMessage.BadRequest(400);
            } else if (!started) {
                if (c == ' ' || c == '\r' || c == '\n' || c == '\t') continue;
                if (c != '{') throw new HttpMessage.BadRequest(400); started = true; depth = 1; elements = 1;
            } else if (c == '"') { string = true; stringChars = 0; }
            else if (c == '{' || c == '[') {
                if (++depth > 8 || ++elements > 512) throw new HttpMessage.BadRequest(400);
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new HttpMessage.BadRequest(400);
                if (depth == 0) ended = true;
            } else if (c == ',' && ++elements > 512) throw new HttpMessage.BadRequest(400);
        }
        if (!started || !ended || string || depth != 0) throw new HttpMessage.BadRequest(400);
    }
}
