package org.x04g.home.camera;

import java.net.URI;
import java.util.Arrays;

/** Credentials remain in RAM. The caller owns storage and must close this object. */
public final class CameraEndpoint implements AutoCloseable {
    final String host, path, username;
    final int port;
    private char[] password;

    public CameraEndpoint(String host, int port, String path, String username, char[] password) {
        if (host == null || !host.matches("[A-Za-z0-9._-]{1,253}") || port < 1 || port > 65535
                || path == null || !path.startsWith("/") || path.startsWith("//") || path.length() > 1024
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('#') >= 0
                || path.indexOf('?')>=0 || path.indexOf('@')>=0 || path.indexOf('\\')>=0
                || username == null || username.length() > 256 || username.indexOf(':') >= 0
                || username.indexOf('\r') >= 0 || username.indexOf('\n') >= 0
                || password == null || password.length > 1024) {
            throw new IllegalArgumentException("invalid_camera_configuration");
        }
        for(int i=0;i<path.length();i++)if(Character.isWhitespace(path.charAt(i)) || Character.isISOControl(path.charAt(i)))
            throw new IllegalArgumentException("invalid_camera_path");
        for(char c:password)if(c=='\0' || c=='\r' || c=='\n' || Character.isSurrogate(c))throw new IllegalArgumentException("invalid_camera_password");
        this.host = host; this.port = port; this.path = path; this.username = username;
        this.password = password.clone();
    }

    synchronized CameraEndpoint copy() {
        if (password == null) throw new IllegalStateException("camera_credentials_closed");
        return new CameraEndpoint(host, port, path, username, password);
    }

    synchronized char[] passwordCopy() {
        if (password == null) throw new IllegalStateException("camera_credentials_closed");
        return password.clone();
    }

    String uri() {
        try { return new URI("rtsp://"+host+":"+port+path).toASCIIString(); }
        catch (Exception e) { throw new IllegalArgumentException("invalid_camera_uri"); }
    }

    @Override public synchronized void close() {
        if (password != null) { Arrays.fill(password, '\0'); password = null; }
    }

    // Deliberately no endpoint/credential-bearing toString().
}
