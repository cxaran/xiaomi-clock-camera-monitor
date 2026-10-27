package org.x04g.home.camera;

import java.io.IOException;

final class RtspFailure extends IOException {
    private static final long serialVersionUID = 1L;
    enum Kind { PROTOCOL, AUTH, AUTH_UNSUPPORTED, SDP_UNSUPPORTED, TIMEOUT, TOO_LARGE }
    final Kind kind;
    RtspFailure(Kind kind) { super("camera_" + kind.name().toLowerCase(java.util.Locale.US)); this.kind = kind; }
}
