package org.x04g.home.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConfigSnapshot {
    public final int schemaVersion = 1;
    public final long revision;
    public final List<CameraConfig> cameras;
    ConfigSnapshot(long revision, List<CameraConfig> cameras) {
        this.revision=revision;
        this.cameras=Collections.unmodifiableList(new ArrayList<CameraConfig>(cameras));
    }
    public CameraConfig find(String id) {
        for(CameraConfig c:cameras) if(c.id.equals(id)) return c;
        return null;
    }
    @Override public String toString() { return "ConfigSnapshot[revision="+revision+",cameras="+cameras.size()+"]"; }
}
