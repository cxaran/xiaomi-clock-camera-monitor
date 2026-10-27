package org.x04g.home.camera;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One stream, one decoder, one optional audio output. No UI, file IO, logs or reconnect loop. */
public final class RtspCameraPlayer implements AutoCloseable {
    public enum State { STOPPED, CONNECTING, BUFFERING, PLAYING, ERROR }
    public enum ErrorCode { NONE, INVALID_CONFIGURATION, CONNECTION, TIMEOUT, AUTHENTICATION,
        AUTH_UNSUPPORTED, PROTOCOL, UNSUPPORTED_STREAM, FRAME_TOO_LARGE, VIDEO_CODEC,
        AUDIO_OUTPUT, SURFACE_LOST, FIRST_FRAME_TIMEOUT, VIDEO_STALLED }
    public interface Listener {
        void onState(State state,ErrorCode error);
        void onVideoSize(int width,int height);
        default void onAudioAvailability(boolean available,ErrorCode error){}
        default void onStats(Stats stats){}
    }
    public static final class Stats {
        public final long bytesReceived,videoPackets,audioPackets,framesToSurface,framesRendered,
                videoDrops,rtpDiscontinuities,audioSamplesWritten,audioSamplesDropped;
        public final boolean hardwareDecoder,audioAvailable;
        public final String decoderName;
        private Stats(Session s) {
            bytesReceived=s.bytes;videoPackets=s.videoPackets;audioPackets=s.audioPackets;
            framesToSurface=s.outputFrames;framesRendered=s.renderedFrames.get();videoDrops=s.videoDrops;
            rtpDiscontinuities=s.assembler==null?0:s.assembler.lostPackets;
            AudioWriteGate.Snapshot audio=s.audioWrites.snapshot();
            audioSamplesWritten=audio.samplesWritten;audioSamplesDropped=audio.samplesDropped;
            hardwareDecoder=s.hardware;audioAvailable=s.audioAvailable;decoderName=s.decoderName;
        }
        private Stats(){bytesReceived=videoPackets=audioPackets=framesToSurface=framesRendered=videoDrops
                =rtpDiscontinuities=audioSamplesWritten=audioSamplesDropped=0;hardwareDecoder=false;audioAvailable=false;decoderName="";}
    }
    /** Fresh write acknowledgement, independent of the periodic general Stats snapshot. */
    public static final class AudioProgress {
        public final boolean enabled,writeInFlight,quiescent;
        public final long samplesWritten,samplesDropped,revocationSerial,samplesCompletedAfterRevocation,streamGeneration;
        private AudioProgress(AudioWriteGate.Snapshot s,long generation) {
            enabled=s.enabled;writeInFlight=s.writeInFlight;quiescent=s.quiescent;
            samplesWritten=s.samplesWritten;samplesDropped=s.samplesDropped;revocationSerial=s.revocationSerial;
            samplesCompletedAfterRevocation=s.samplesCompletedAfterRevocation;streamGeneration=generation;
        }
    }
    private static final int MAX_WIDTH=1280,MAX_HEIGHT=720;
    private static final long POLL_DRAIN_MS=32;
    private final Object gate=new Object();
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private long generation;
    private boolean closed;
    private Pending pending;
    private Session active;
    private Thread worker;
    private volatile boolean audioEnabled;
    private volatile float volume=1f;
    private volatile Stats latestStats=new Stats();
    private volatile AudioProgress lastAudioProgress=new AudioProgress(new AudioWriteGate(false).snapshot(),0);

    public RtspCameraPlayer(Listener listener) {
        if(listener==null)throw new IllegalArgumentException("camera_listener_required");
        this.listener=listener;
    }

    /** Surface ownership stays with the UI. Call only when its Surface is valid. */
    public void start(CameraEndpoint endpoint,Surface surface) {
        if(endpoint==null || surface==null || !surface.isValid())throw new IllegalArgumentException("camera_surface_required");
        CameraEndpoint copy=endpoint.copy();Session old;long token;
        synchronized(gate) {
            if(closed){copy.close();throw new IllegalStateException("camera_player_closed");}
            token=++generation;
            // Counters belong to this start, never to the previously selected camera.
            latestStats=new Stats();
            if(pending!=null)pending.endpoint.close();
            pending=new Pending(copy,surface,token);old=active;
            state(token,State.CONNECTING,ErrorCode.NONE);
            if(worker==null){worker=new Thread(new Runnable(){public void run(){workLoop();}},"X04G-Camera");worker.start();}
            gate.notifyAll();
        }
        if(old!=null)old.cancel();
    }

    /** Asynchronous, idempotent. Socket close/mute are immediate; worker owns releases. */
    public void stop() { stop(false); }
    @Override public void close() { stop(true); }
    private void stop(boolean permanently) {
        Session old;long token;boolean idle;
        synchronized(gate) {
            if(closed)return;
            if(permanently)closed=true;
            token=++generation;
            if(pending!=null){pending.endpoint.close();pending=null;}
            old=active;idle=old==null;gate.notifyAll();
        }
        if(old!=null)old.cancel();
        if(idle)state(token,State.STOPPED,ErrorCode.NONE);
    }
    public void setAudioEnabled(boolean enabled) {
        audioEnabled=enabled;Session session;
        synchronized(gate){session=active;}
        if(session!=null){session.audioWrites.setEnabled(enabled&&!session.cancelled);session.updateVolume();}
    }
    /** Local track gain only; never changes the original system volume. */
    public void setVolume(float value) {
        if(Float.isNaN(value) || value<0f || value>1f)throw new IllegalArgumentException("camera_volume_bound");
        volume=value;Session session;synchronized(gate){session=active;}if(session!=null)session.updateVolume();
    }
    public boolean isAudioEnabled(){return audioEnabled;}
    public AudioProgress audioProgress(){synchronized(gate){return active==null?lastAudioProgress:new AudioProgress(active.audioWrites.snapshot(),active.token);}}
    public Stats stats(){return latestStats;}
    public boolean isRunning(){synchronized(gate){return active!=null || pending!=null;}}

    private static final class Pending {
        final CameraEndpoint endpoint;final Surface surface;final long token;
        Pending(CameraEndpoint endpoint,Surface surface,long token){this.endpoint=endpoint;this.surface=surface;this.token=token;}
    }
    private void workLoop() {
        while(true) {
            Pending next;
            synchronized(gate) {
                while(pending==null && !closed)try{gate.wait();}catch(InterruptedException ignored){}
                if(closed && pending==null){worker=null;return;}
                next=pending;pending=null;active=new Session(next);
            }
            Session session;synchronized(gate){session=active;}
            try {session.run();}
            catch(Exception e) {if(!session.cancelled && session.failure.get()==CameraProgress.Cause.NONE)state(session.token,State.ERROR,error(e,session));}
            finally {
                boolean wasCancelled=session.cancelled;
                session.release();
                long stoppedToken;boolean stopped;
                synchronized(gate) {
                    if(active==session)active=null;
                    stoppedToken=generation;stopped=pending==null && (wasCancelled || closed);
                }
                if(stopped)state(stoppedToken,State.STOPPED,ErrorCode.NONE);
            }
        }
    }

    private final class Session {
        final CameraEndpoint endpoint;final Surface surface;final long token;
        final Object audioGate=new Object();
        // Gain changes serialize only other gain calls, never decoding,
        // AudioTrack construction/write or release. Prevent a late worker gain
        // from undoing a completed mute/cancel on the UI thread.
        final Object gainGate=new Object();
        final AtomicLong renderedFrames=new AtomicLong();
        final AudioWriteGate audioWrites;
        final CameraProgress progress=new CameraProgress();
        final AtomicReference<CameraProgress.Cause> failure=new AtomicReference<>(CameraProgress.Cause.NONE);
        final MediaCodec.BufferInfo outputInfo=new MediaCodec.BufferInfo();
        ScheduledFuture<?> watchdog;
        volatile boolean cancelled;
        volatile RtspConnection connection;
        MediaCodec codec;
        volatile AudioTrack audio;
        // Guarded by gainGate. A replacement track always receives its gain;
        // failed native calls are never cached as applied.
        AudioTrack appliedGainTrack;
        float appliedGain=Float.NaN;
        H264Assembler assembler;
        int videoChannel=-1,audioChannel=-1,videoPayload=-1,audioPayload=-1,lastAudioSequence=-1;
        int configuredWidth,configuredHeight;
        boolean hardware,audioAvailable,audioPlaying,audioFailed;
        String decoderName="";
        long bytes,videoPackets,audioPackets,outputFrames,videoDrops;
        long streamStart,lastVideo,statsAt;
        long lastDrainAt=Long.MIN_VALUE;
        short[] pcm=new short[4096];
        Session(Pending next){endpoint=next.endpoint;surface=next.surface;token=next.token;audioWrites=new AudioWriteGate(audioEnabled);lastAudioProgress=new AudioProgress(audioWrites.snapshot(),token);}
        void run()throws Exception {
            if(!surface.isValid())throw new SurfaceFailure();
            connection=new RtspConnection(endpoint,new RtspConnection.Sink(){
                public void packet(int channel,byte[] data)throws Exception{receive(channel,data,true);}
                public void controlPacket(int channel,byte[] data)throws Exception{receive(channel,data,false);}
            },progress);
            watchdog=RtspConnection.DEADLINES.scheduleWithFixedDelay(new Runnable(){public void run(){
                if(cancelled || !current(token))return;
                CameraProgress.Cause cause=progress.expired(now());
                if(cause!=CameraProgress.Cause.NONE && failure.compareAndSet(CameraProgress.Cause.NONE,cause)) {
                    // Show failure even if a vendor codec call is stuck. Only
                    // the worker may stop/release codec/audio; no second decoder.
                    state(token,State.ERROR,progressError(cause));
                    RtspConnection own=connection;if(own!=null)own.expire();
                }
            }},250,250,TimeUnit.MILLISECONDS);
            if(cancelled){connection.cancel();return;}
            RtspConnection.Setup setup=connection.connect();
            if(cancelled)return;
            videoChannel=setup.videoChannel;audioChannel=setup.audioChannel;
            videoPayload=setup.sdp.video.payloadType;audioPayload=setup.sdp.audio==null?-1:setup.sdp.audio.payloadType;
            audioAvailable=setup.sdp.audio!=null;
            audioAvailability(token,audioAvailable,audioAvailable?ErrorCode.NONE:ErrorCode.UNSUPPORTED_STREAM);
            // SDP parameter sets are optional. Without both, wait for a valid
            // in-band IDR/SPS/PPS before creating a decoder; never guess a size
            // or reserve the maximum supported adaptive playback dimensions.
            if(setup.sdp.video.sps!=null && setup.sdp.video.pps!=null)
                try{prepareCodec(setup.sdp.video.sps,setup.sdp.video.pps);}catch(IOException|RuntimeException e){throw new DecoderFailure();}
            assembler=new H264Assembler(new H264Assembler.Sink(){public void accessUnit(byte[] data,long pts,boolean idr)throws Exception{queueVideo(data,pts);}},setup.sdp.video.sps,setup.sdp.video.pps);
            streamStart=lastVideo=statsAt=now();
            progress.armStream(streamStart);
            state(token,State.BUFFERING,ErrorCode.NONE);
            connection.play();
            if(!cancelled)connection.run();
        }
        private void prepareCodec(byte[] sps,byte[] pps)throws IOException {
            if(sps==null || pps==null)throw new IOException("camera_parameter_sets_required");
            H264Sps dimensions=H264Sps.parse(sps);
            MediaFormat format=MediaFormat.createVideoFormat("video/avc",dimensions.width,dimensions.height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,H264Assembler.MAX_ACCESS_UNIT);
            format.setByteBuffer("csd-0",ByteBuffer.wrap(annex(sps)));
            format.setByteBuffer("csd-1",ByteBuffer.wrap(annex(pps)));
            MediaCodecInfo selected=null;
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if(info.isEncoder())continue;
                for(String type:info.getSupportedTypes())if(type.equalsIgnoreCase("video/avc")) {
                    try {
                        if(info.getCapabilitiesForType(type).isFormatSupported(format)
                                && (selected==null || (!selected.isHardwareAccelerated() && info.isHardwareAccelerated())))selected=info;
                    }catch(RuntimeException ignored){}
                }
            }
            if(selected==null)throw new IOException("camera_decoder_unavailable");
            hardware=selected.isHardwareAccelerated();decoderName=selected.getName();
            codec=MediaCodec.createByCodecName(decoderName);
            codec.configure(format,surface,null,0);
            codec.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener(){
                public void onFrameRendered(MediaCodec source,long presentationTimeUs,long nanoTime) {
                    if(cancelled || failure.get()!=CameraProgress.Cause.NONE || !current(token))return;
                    progress.frameRendered(now());
                    if(renderedFrames.incrementAndGet()==1)state(token,State.PLAYING,ErrorCode.NONE);
                }
            },main);
            codec.start();
            configuredWidth=dimensions.width;configuredHeight=dimensions.height;
        }
        private void receive(int channel,byte[] data,boolean decode)throws Exception {
            if(cancelled)return;
            if(failure.get()!=CameraProgress.Cause.NONE)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
            bytes+=data.length;
            if(assembler==null)return; // No media accepted before negotiated setup.
            if(!surface.isValid())throw new SurfaceFailure();
            if(channel==videoChannel) {
                RtpPacket p=RtpPacket.parse(data,data.length);
                if(p.payloadType!=videoPayload)throw new RtspFailure(RtspFailure.Kind.PROTOCOL);
                videoPackets++;lastVideo=now();progress.videoPacket(lastVideo);
                // A keepalive must reach its RTSP response within the existing
                // request budget. Dropped video needs a fresh IDR, never a
                // partial AU or a codec/audio call on this lightweight path.
                if(decode)assembler.packet(p);else assembler.reset();
            }else if(channel==audioChannel && audioAvailable) {
                RtpPacket p=RtpPacket.parse(data,data.length);
                if(p.payloadType!=audioPayload)throw new RtspFailure(RtspFailure.Kind.PROTOCOL);
                audioPackets++;if(decode)playAudio(p);
            }
            // Other negotiated channels are RTCP; ignored, never fed to a decoder.
            if(decode)pollDrain();
            checkVideoGap(now(),lastVideo);
            if(renderedFrames.get()==0 && now()-streamStart>12000)throw new FirstFrameFailure();
            if(now()-statsAt>=1000){statsAt=now();publishStats(this);}
        }
        private void queueVideo(byte[] data,long pts)throws Exception {
            if(cancelled)return;
            if(failure.get()!=CameraProgress.Cause.NONE)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
            byte[] sps=assembler.parameterSps();
            if(codec==null) {
                try{prepareCodec(sps,assembler.parameterPps());}catch(IOException|RuntimeException e){throw new DecoderFailure();}
            }else {
                H264Sps dimensions=H264Sps.parse(sps);
                // A resolution change needs a fresh user-selected connection;
                // do not silently grow an adaptive decoder reservation.
                if(dimensions.width!=configuredWidth || dimensions.height!=configuredHeight)
                    throw new RtspFailure(RtspFailure.Kind.SDP_UNSUPPORTED);
            }
            // Always free ready output before asking for another input slot.
            drain();
            if(cancelled)return;
            if(failure.get()!=CameraProgress.Cause.NONE)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
            int slot=codec.dequeueInputBuffer(3000);
            if(slot<0){videoDrops++;assembler.reset();return;}
            ByteBuffer input=codec.getInputBuffer(slot);
            if(input==null || input.capacity()<data.length)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
            input.clear();input.put(data);
            if(cancelled)return;
            if(failure.get()!=CameraProgress.Cause.NONE)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
            codec.queueInputBuffer(slot,0,data.length,pts,0);pollDrain();
        }
        private void pollDrain()throws Exception {
            if(lastDrainAt==Long.MIN_VALUE || now()-lastDrainAt>=POLL_DRAIN_MS)drain();
        }
        private void drain()throws Exception {
            if(codec==null || cancelled)return;
            int last=-1;
            try {
                for(int i=0;i<8;i++) {
                    if(cancelled)break;
                    if(failure.get()!=CameraProgress.Cause.NONE)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
                    int slot=codec.dequeueOutputBuffer(outputInfo,0);
                    if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f=codec.getOutputFormat();int w=f.getInteger(MediaFormat.KEY_WIDTH),h=f.getInteger(MediaFormat.KEY_HEIGHT);
                        if(w<1 || h<1 || w>MAX_WIDTH || h>736)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
                        if(f.containsKey("crop-left") && f.containsKey("crop-right"))w=f.getInteger("crop-right")-f.getInteger("crop-left")+1;
                        if(f.containsKey("crop-top") && f.containsKey("crop-bottom"))h=f.getInteger("crop-bottom")-f.getInteger("crop-top")+1;
                        if(w<1 || h<1 || w>MAX_WIDTH || h>MAX_HEIGHT)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
                        videoSize(token,w,h);
                    }else if(slot>=0) {
                        if(last>=0){codec.releaseOutputBuffer(last,false);videoDrops++;}
                        last=slot;
                    }else break;
                }
                if(last>=0){
                    boolean render=!cancelled && failure.get()==CameraProgress.Cause.NONE;
                    codec.releaseOutputBuffer(last,render);if(render)outputFrames++;
                }
            }finally{lastDrainAt=now();}
        }
        private void playAudio(RtpPacket packet) {
            if(audioFailed)return;
            if(packet.length>pcm.length){audioWrites.recordDropped(packet.length);return;}
            synchronized(audioGate) {
                if(cancelled)return;
                try {
                    if(!audioEnabled) {
                        if(audio!=null && audioPlaying){audio.pause();audio.flush();audioPlaying=false;}
                        return;
                    }
                    if(audio==null) {
                        int min=AudioTrack.getMinBufferSize(8000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                        if(min<=0 || min>16384)throw new IllegalStateException("camera_audio_buffer");
                        audio=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                                .setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                                .setBufferSizeInBytes(Math.max(min,3200)).setTransferMode(AudioTrack.MODE_STREAM).build();
                        if(audio.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException("camera_audio_output");
                    }
                    if(lastAudioSequence!=-1 && packet.sequence!=((lastAudioSequence+1)&65535)){audio.pause();audio.flush();audioPlaying=false;}
                    lastAudioSequence=packet.sequence;
                    int length=G711.decode(packet.bytes,packet.offset,packet.length,pcm);
                    if(!audioWrites.beginWrite())return;
                    int accepted=0,rejected=0;
                    try {
                        if(cancelled||!audioEnabled||!audioWrites.mayWrite())return;
                        updateVolume();
                        // Construction may have overlapped a focus loss. A new
                        // track must not start until its current lease permits it.
                        if(cancelled||!audioEnabled||!audioWrites.mayWrite())return;
                        if(!audioPlaying){audio.play();audioPlaying=true;}
                        if(cancelled||!audioEnabled||!audioWrites.mayWrite())return;
                        int written=audio.write(pcm,0,length,AudioTrack.WRITE_NON_BLOCKING);
                        if(written<0)throw new IllegalStateException("camera_audio_write");
                        accepted=written;rejected=length-written;
                    }finally{audioWrites.finishWrite(accepted,rejected);}
                }catch(RuntimeException error) {
                    audioFailed=true;audioAvailable=false;releaseAudioLocked();
                    audioAvailability(token,false,ErrorCode.AUDIO_OUTPUT);
                }
            }
        }
        void updateVolume() {
            synchronized(gainGate) {
                AudioTrack own=audio;if(own==null)return;
                float desired=cancelled || !audioEnabled?0f:volume;
                if(own==appliedGainTrack && Float.compare(desired,appliedGain)==0)return;
                appliedGainTrack=null;appliedGain=Float.NaN;
                try {
                    if(own.setVolume(desired)==AudioTrack.SUCCESS){appliedGainTrack=own;appliedGain=desired;}
                }catch(RuntimeException ignored){}
            }
        }
        void cancel(){cancelled=true;audioWrites.close();RtspConnection c=connection;if(c!=null)c.cancel();updateVolume();}
        void release() {
            cancelled=true;audioWrites.close();if(watchdog!=null)watchdog.cancel(false);if(connection!=null)connection.close();
            synchronized(audioGate){releaseAudioLocked();}
            if(codec!=null) {
                try{codec.stop();}catch(RuntimeException ignored){}
                try{codec.release();}catch(RuntimeException ignored){}codec=null;
            }
            endpoint.close();lastAudioProgress=new AudioProgress(audioWrites.snapshot(),token);publishStats(this);pcm=null;assembler=null;
        }
        private void releaseAudioLocked() {
            if(audio!=null){try{audio.pause();audio.flush();}catch(RuntimeException ignored){}try{audio.release();}catch(RuntimeException ignored){}audio=null;audioPlaying=false;}
            synchronized(gainGate){appliedGainTrack=null;appliedGain=Float.NaN;}
        }
    }
    private boolean current(long token){synchronized(gate){return generation==token;}}
    private void state(final long token,final State state,final ErrorCode error){main.post(new Runnable(){public void run(){if(current(token))listener.onState(state,error);}});}
    private void videoSize(final long token,final int w,final int h){main.post(new Runnable(){public void run(){if(current(token))listener.onVideoSize(w,h);}});}
    private void audioAvailability(final long token,final boolean available,final ErrorCode error){main.post(new Runnable(){public void run(){if(current(token))listener.onAudioAvailability(available,error);}});}
    private void publishStats(Session s){final long token=s.token;final Stats stats=new Stats(s);synchronized(gate){if(generation==token)latestStats=stats;}main.post(new Runnable(){public void run(){if(current(token))listener.onStats(stats);}});}
    private static byte[] annex(byte[] nal){byte[] out=new byte[nal.length+4];out[3]=1;System.arraycopy(nal,0,out,4,nal.length);return out;}
    private static long now(){return System.nanoTime()/1000000L;}
    private static ErrorCode progressError(CameraProgress.Cause cause) {
        if(cause==CameraProgress.Cause.FIRST_FRAME_TIMEOUT)return ErrorCode.FIRST_FRAME_TIMEOUT;
        if(cause==CameraProgress.Cause.FRAME_STALLED || cause==CameraProgress.Cause.VIDEO_TIMEOUT)return ErrorCode.VIDEO_STALLED;
        return ErrorCode.TIMEOUT;
    }
    private static void checkVideoGap(long at,long lastVideo)throws VideoStalledFailure {
        if(at-lastVideo>8000)throw new VideoStalledFailure();
    }
    private static ErrorCode error(Exception e,Session session) {
        if(e instanceof VideoStalledFailure)return ErrorCode.VIDEO_STALLED;
        if(e instanceof SurfaceFailure || !session.surface.isValid())return ErrorCode.SURFACE_LOST;
        if(e instanceof FirstFrameFailure)return ErrorCode.FIRST_FRAME_TIMEOUT;
        if(e instanceof DecoderFailure)return ErrorCode.VIDEO_CODEC;
        if(e instanceof SocketTimeoutException)return ErrorCode.TIMEOUT;
        if(e instanceof RtspFailure) {
            switch(((RtspFailure)e).kind) {
                case AUTH:return ErrorCode.AUTHENTICATION;
                case AUTH_UNSUPPORTED:return ErrorCode.AUTH_UNSUPPORTED;
                case SDP_UNSUPPORTED:return ErrorCode.UNSUPPORTED_STREAM;
                case TIMEOUT:return ErrorCode.TIMEOUT;
                case TOO_LARGE:return ErrorCode.FRAME_TOO_LARGE;
                default:return ErrorCode.PROTOCOL;
            }
        }
        if(e instanceof MediaCodec.CodecException || e instanceof IllegalStateException)return ErrorCode.VIDEO_CODEC;
        return ErrorCode.CONNECTION;
    }
    private static final class SurfaceFailure extends Exception {private static final long serialVersionUID=1L;}
    private static final class VideoStalledFailure extends Exception {private static final long serialVersionUID=1L;}
    private static final class FirstFrameFailure extends Exception {private static final long serialVersionUID=1L;}
    private static final class DecoderFailure extends Exception {private static final long serialVersionUID=1L;}
}
