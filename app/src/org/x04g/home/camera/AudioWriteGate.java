package org.x04g.home.camera;

/** One worker lease. Its short metadata lock never encloses an Android/native call. */
final class AudioWriteGate {
    static final class Snapshot {
        final boolean enabled,writeInFlight,quiescent;
        final long samplesWritten,samplesDropped,revocationSerial,samplesCompletedAfterRevocation;
        Snapshot(boolean enabled,boolean inFlight,long written,long dropped,long serial,long late) {
            this.enabled=enabled;writeInFlight=inFlight;quiescent=!enabled&&!inFlight;
            samplesWritten=written;samplesDropped=dropped;revocationSerial=serial;samplesCompletedAfterRevocation=late;
        }
    }
    private boolean enabled,inFlight,closed;
    private long written,dropped,revocationSerial,leaseSerial,completedAfterRevocation;

    AudioWriteGate(boolean enabled){this.enabled=enabled;}
    synchronized void setEnabled(boolean value) {
        if(closed)return;
        if(enabled&&!value)revocationSerial++;
        enabled=value;
    }
    synchronized void close() {
        if(enabled)revocationSerial++;
        enabled=false;closed=true;
    }
    synchronized boolean beginWrite() {
        if(!enabled||inFlight)return false;
        inFlight=true;leaseSerial=revocationSerial;return true;
    }
    synchronized boolean mayWrite() {
        return inFlight&&enabled&&leaseSerial==revocationSerial;
    }
    synchronized void finishWrite(int accepted,int rejected) {
        if(!inFlight||accepted<0||rejected<0)throw new IllegalStateException("camera_audio_lease");
        written+=accepted;dropped+=rejected;
        // An already-started native write may finish after revocation. Never
        // hide its accepted samples or report quiescence before it returns.
        if(leaseSerial!=revocationSerial)completedAfterRevocation+=accepted;
        inFlight=false;
    }
    synchronized void recordDropped(int samples) {
        if(samples<0)throw new IllegalArgumentException("camera_audio_samples");
        dropped+=samples;
    }
    synchronized Snapshot snapshot() {
        return new Snapshot(enabled,inFlight,written,dropped,revocationSerial,completedAfterRevocation);
    }
}
