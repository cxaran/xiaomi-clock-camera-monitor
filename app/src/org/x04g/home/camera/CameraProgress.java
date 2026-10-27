package org.x04g.home.camera;

/** Monotonic session progress. No Android/socket calls; one clock is shared. */
final class CameraProgress {
    enum Cause { NONE, MESSAGE_TIMEOUT, VIDEO_TIMEOUT, FIRST_FRAME_TIMEOUT, FRAME_STALLED }
    static final long MESSAGE_MS=5000, VIDEO_MS=8000, FIRST_FRAME_MS=12000, RENDERED_MS=8000;
    private volatile long messageStart,streamStart,lastVideo,lastRendered;
    private volatile boolean messageReading,streamArmed,haveRendered;

    void beginMessage(long now){messageStart=now;messageReading=true;}
    void endMessage(){messageReading=false;}
    void armStream(long now){streamStart=lastVideo=now;streamArmed=true;}
    void videoPacket(long now){lastVideo=now;}
    void frameRendered(long now){lastRendered=now;haveRendered=true;}

    Cause expired(long now) {
        // Bytes arriving inside one message never reset its absolute budget.
        if(messageReading && now-messageStart>=MESSAGE_MS)return Cause.MESSAGE_TIMEOUT;
        if(!streamArmed)return Cause.NONE;
        if(haveRendered) {
            if(now-lastRendered>=RENDERED_MS)return Cause.FRAME_STALLED;
        }else if(now-streamStart>=FIRST_FRAME_MS)return Cause.FIRST_FRAME_TIMEOUT;
        if(now-lastVideo>=VIDEO_MS)return Cause.VIDEO_TIMEOUT;
        return Cause.NONE;
    }
}
