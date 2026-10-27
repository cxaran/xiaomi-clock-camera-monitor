package org.x04g.home.gate;

import java.io.Closeable;
import java.util.Arrays;

/** In-memory provisioning only. No cloud, DNS discovery, credential logging or storage. */
public final class GateProfile implements Closeable {
    final byte[] address;
    final String deviceId;
    final LearnedRfPulse pulse;
    private final byte[] key;
    private boolean destroyed;

    public GateProfile(String privateIpv4, String deviceId, byte[] localKey, LearnedRfPulse pulse) {
        this.address = parsePrivateAddress(privateIpv4);
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("device_id_invalid");
        if (localKey == null || localKey.length != 16) throw new IllegalArgumentException("key_length");
        this.deviceId = deviceId;
        this.key = localKey.clone();
        this.pulse = pulse;
    }

    public boolean hasDocumentedReplayProfile() { return pulse != null; }
    public boolean physicalReplayVerified() { return pulse != null && pulse.physicalReplayVerified(); }

    synchronized byte[] keyCopy() {
        if (destroyed) throw new IllegalStateException("profile_closed");
        return key.clone();
    }

    @Override public synchronized void close() { destroyed = true; Arrays.fill(key, (byte) 0); }
    @Override public String toString() { return "GateProfile[redacted]"; }

    static byte[] parsePrivateAddress(String text) {
        if (text == null || !text.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
            throw new IllegalArgumentException("private_ipv4_required");
        String[] parts = text.split("\\.");
        int[] value = new int[4];
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 1 && parts[i].charAt(0) == '0') throw new IllegalArgumentException("ipv4_ambiguous");
            value[i] = Integer.parseInt(parts[i]);
            if (value[i] > 255) throw new IllegalArgumentException("ipv4_invalid");
            result[i] = (byte) value[i];
        }
        boolean privateAddress = value[0] == 10 || value[0] == 172 && value[1] >= 16 && value[1] <= 31
                || value[0] == 192 && value[1] == 168;
        if (!privateAddress || value[3] == 0 || value[3] == 255)
            throw new IllegalArgumentException("private_unicast_ipv4_required");
        return result;
    }
}
