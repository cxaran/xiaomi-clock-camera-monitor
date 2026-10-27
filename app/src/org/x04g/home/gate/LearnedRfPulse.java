package org.x04g.home.gate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed TinyTuya 1.20.0 RFRemoteControlDevice.rf_send_button schema, not an IR schema. */
public final class LearnedRfPulse {
    private final String capturedBase64;
    private final boolean physicallyVerified;

    private LearnedRfPulse(String code, boolean verified) { capturedBase64 = code; physicallyVerified = verified; }

    /** Caller obtains capture through a separate private provisioning flow. Never reads Windows DPAPI. */
    public static LearnedRfPulse fromCapturedBase64(String code, boolean physicalReplayVerified) {
        try {
            if (code == null || code.length() == 0 || code.length() > 32768
                    || !code.matches("[A-Za-z0-9+/]*={0,2}") || (code.length() & 3) != 0)
                throw new IllegalArgumentException();
            byte[] decoded = Base64.getDecoder().decode(code);
            if (decoded.length > 24576) throw new IllegalArgumentException();
            JSONObject capture = GateStatus.parseObject(new String(decoded, StandardCharsets.UTF_8));
            // Actual public capture metadata says ver=2, num=1, DP202; retain the entire container.
            if (!"2".equals(capture.optString("ver")) || !(capture.opt("num") instanceof Number)
                    || capture.getDouble("num") != 1.0) throw new IllegalArgumentException();
            String signal = capture.getString("data0");
            if (signal.isEmpty() || signal.length() > 32768 || Base64.getDecoder().decode(signal).length == 0)
                throw new IllegalArgumentException();
            Object levels = capture.opt("lv");
            if (levels != null && levels != JSONObject.NULL) {
                if (!(levels instanceof JSONArray) || ((JSONArray) levels).length() != 1)
                    throw new IllegalArgumentException();
                Object level = ((JSONArray) levels).get(0);
                if (!(level instanceof Number) || (((Number) level).doubleValue() != 0.0
                        && ((Number) level).doubleValue() != 1.0)) throw new IllegalArgumentException();
            }
            return new LearnedRfPulse(code, physicalReplayVerified);
        } catch (Exception invalid) { throw new IllegalArgumentException("rf_capture_invalid"); }
    }

    JSONObject dps() throws org.json.JSONException {
        JSONObject key = new JSONObject().put("code", capturedBase64).put("times", 4)
                .put("delay", 0).put("intervals", 0);
        JSONObject command = new JSONObject().put("control", "rfstudy_send").put("rf_type", "sub_2g")
                .put("feq", 0).put("mode", 0).put("rate", 0).put("ver", "2").put("key1", key);
        return new JSONObject().put("201", command.toString());
    }

    public boolean physicalReplayVerified() { return physicallyVerified; }
    @Override public String toString() { return "LearnedRfPulse[redacted;times=4]"; }
}
