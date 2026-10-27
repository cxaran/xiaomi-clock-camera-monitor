package org.x04g.home.config;

/** Immutable metadata. Passwords and credential-bearing URLs are deliberately absent. */
public final class CameraConfig {
    public final String id, label, host, path, username, mac, onvifUuid, model;
    public final int port;
    public final boolean enabled, hasPassword;
    public final boolean needsVerification;
    public final long verifiedAtMillis;
    CameraConfig(CameraDraft d, boolean hasPassword) {
        id=d.id; label=d.label; host=d.host; path=d.path; username=d.username;
        mac=d.mac; onvifUuid=d.onvifUuid; model=d.model; port=d.port; enabled=d.enabled;
        this.hasPassword=hasPassword;
        needsVerification=d.needsVerification;verifiedAtMillis=d.verifiedAtMillis;
    }
    @Override public String toString() { return "CameraConfig["+id+"]"; }
}
