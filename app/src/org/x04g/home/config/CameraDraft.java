package org.x04g.home.config;

/** Input only. A blank id generates a stable UUID; editing must reuse the original id. */
public final class CameraDraft {
    public String id = "", label = "", host = "", path = "/stream2", username = "";
    public String mac = "", onvifUuid = "", model = "";
    public int port = 554;
    public boolean enabled = true;
    public boolean needsVerification = true;
    public long verifiedAtMillis = 0;
    public CameraDraft() { }
    public CameraDraft(CameraConfig c) {
        id=c.id; label=c.label; host=c.host; path=c.path; username=c.username;
        mac=c.mac; onvifUuid=c.onvifUuid; model=c.model; port=c.port; enabled=c.enabled;
        needsVerification=c.needsVerification;verifiedAtMillis=c.verifiedAtMillis;
    }
    @Override public String toString() { return "CameraDraft[redacted]"; }
}
