package org.x04g.home.camera;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One blocking worker owns protocol/decoder calls; cancellation closes only its Socket. */
final class RtspConnection implements AutoCloseable {
    static final ScheduledThreadPoolExecutor DEADLINES=new ScheduledThreadPoolExecutor(1,new ThreadFactory(){
        public Thread newThread(Runnable task){Thread t=new Thread(task,"X04G-CameraDeadline");t.setDaemon(true);return t;}
    });
    static {DEADLINES.setRemoveOnCancelPolicy(true);DEADLINES.setKeepAliveTime(20,TimeUnit.SECONDS);DEADLINES.allowCoreThreadTimeOut(true);}
    interface Sink {
        void packet(int channel,byte[] bytes)throws Exception;
        default void controlPacket(int channel,byte[] bytes)throws Exception {packet(channel,bytes);}
    }
    static final class Setup {
        final SdpSession sdp;final int videoChannel,audioChannel;
        Setup(SdpSession sdp,int videoChannel,int audioChannel){this.sdp=sdp;this.videoChannel=videoChannel;this.audioChannel=audioChannel;}
    }
    private final CameraEndpoint endpoint;
    private final Sink sink;
    private final CameraProgress progress;
    private volatile Socket socket;
    private volatile boolean cancelled;
    private volatile boolean expired;
    private OutputStream output;
    private RtspWire wire;
    private RtspAuth auth;
    private String session,playUri;
    private int cseq=1;
    private long keepaliveMs=20000;
    private boolean optionsKeepalive;
    // Fixed verbs and counters only: never retain URI, credentials or media.
    volatile String lastRequestVerb="";
    volatile boolean controlWaitActive,requestDeadlineExpired;
    volatile long droppedWhileControl;
    private static final long CONTROL_SINK_BUDGET_NS=100000000L;
    RtspConnection(CameraEndpoint endpoint,Sink sink,CameraProgress progress){this.endpoint=endpoint;this.sink=sink;this.progress=progress;}
    Setup connect()throws Exception {
        check();Socket own=new Socket();socket=own;
        if(cancelled){own.close();check();}
        own.setTcpNoDelay(true);own.setReceiveBufferSize(65536);own.setSendBufferSize(8192);
        java.net.InetAddress address=CameraAddress.resolve(endpoint.host,new CameraAddress.Cancelled(){public boolean cancelled(){return cancelled;}});
        check();own.connect(new InetSocketAddress(address,endpoint.port),4000);own.setSoTimeout(3000);
        check();wire=new RtspWire(new BufferedInputStream(own.getInputStream(),16384));output=own.getOutputStream();
        Map<String,String> headers=new LinkedHashMap<>();headers.put("Accept","application/sdp");
        RtspWire.Message describe=ok(request("DESCRIBE",endpoint.uri(),headers));
        String type=describe.first("content-type");
        if(type!=null && !type.toLowerCase(Locale.US).startsWith("application/sdp"))throw protocol();
        String base=describe.first("content-base");
        SdpSession sdp=SdpSession.parse(describe.body,endpoint.uri(),base);
        int video=setup(sdp.video.control,0);
        int audio=-1;
        if(sdp.audio!=null)audio=setup(sdp.audio.control,2);
        if(audio>=0 && (audio==video || audio==video+1 || audio+1==video))throw protocol();
        playUri=sdp.playUri;
        return new Setup(sdp,video,audio);
    }
    void play()throws Exception {
        Map<String,String> h=new LinkedHashMap<>();h.put("Range","npt=0.000-");
        ok(request("PLAY",playUri,h));
    }
    void run()throws Exception {
        long lastKeepalive=now();
        while(!cancelled) {
            check();
            if(now()-lastKeepalive>=keepaliveMs) {
                RtspWire.Message response=request(optionsKeepalive?"OPTIONS":"GET_PARAMETER",playUri,new LinkedHashMap<String,String>(),true);
                if(!optionsKeepalive && (response.status==405 || response.status==501)) {
                    optionsKeepalive=true;response=request("OPTIONS",playUri,new LinkedHashMap<String,String>(),true);
                }
                ok(response);lastKeepalive=now();
            }
            try {
                RtspWire.Message message=nextMessage();
                if(!message.interleaved())throw protocol();
                sink.packet(message.channel,message.body);
            }catch(SocketTimeoutException e) {
                // Fail this connection, including partial messages. No retry can
                // accidentally resynchronize in the middle of a packet.
                throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
            }catch(IOException e){if(expired)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);throw e;}
        }
    }
    private int setup(String uri,int requested)throws Exception {
        Map<String,String> h=new LinkedHashMap<>();h.put("Transport","RTP/AVP/TCP;unicast;interleaved="+requested+"-"+(requested+1));
        RtspWire.Message response=ok(request("SETUP",uri,h));
        String value=response.first("session");
        if(value!=null) {
            String token=value.split(";",2)[0].trim();
            if(!token.matches("[A-Za-z0-9._~-]{1,256}") || (session!=null && !session.equals(token)))throw protocol();
            session=token;
            Matcher timeout=Pattern.compile("(?i)(?:^|;)\\s*timeout=([0-9]+)").matcher(value);
            if(timeout.find())try {int seconds=Integer.parseInt(timeout.group(1));if(seconds>=6)keepaliveMs=Math.min(20000,seconds*500L);}catch(NumberFormatException ignored){}
        }
        if(session==null)throw protocol();
        String transport=response.first("transport");
        if(transport==null || !transport.toUpperCase(Locale.US).contains("RTP/AVP/TCP")
                || transport.toLowerCase(Locale.US).contains("multicast"))throw protocol();
        Matcher interleaved=Pattern.compile("(?i)(?:^|;)\\s*interleaved=([0-9]{1,3})-([0-9]{1,3})(?:;|$)").matcher(transport);
        if(!interleaved.find())throw protocol();
        int first=Integer.parseInt(interleaved.group(1)),second=Integer.parseInt(interleaved.group(2));
        if(first>254 || second!=first+1)throw protocol();return first;
    }
    private RtspWire.Message request(String method,String uri,Map<String,String> headers)throws Exception {
        return request(method,uri,headers,false);
    }
    private RtspWire.Message request(String method,String uri,Map<String,String> headers,boolean keepalive)throws Exception {
        lastRequestVerb=method;requestDeadlineExpired=false;
        long sinkWorkNanos=0;
        for(int attempt=0;attempt<2;attempt++) {
            check();int sequence=cseq++;
            StringBuilder b=new StringBuilder();
            b.append(method).append(' ').append(uri).append(" RTSP/1.0\r\nCSeq: ").append(sequence)
                    .append("\r\nUser-Agent: X04GHome/1\r\n");
            if(session!=null)b.append("Session: ").append(session).append("\r\n");
            if(auth!=null)b.append("Authorization: ").append(auth.authorization(method,uri,endpoint)).append("\r\n");
            for(Map.Entry<String,String> h:headers.entrySet())b.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            b.append("\r\n");byte[] bytes=b.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>16384)throw protocol();
            // SO_TIMEOUT does not limit OutputStream.write. A per-request
            // deadline closes only this exact Socket, also bounding a stalled
            // write without a second transport or a thread per request.
            final Socket requestSocket=socket;
            final AtomicBoolean outstanding=new AtomicBoolean(true),expired=new AtomicBoolean(false);
            controlWaitActive=keepalive;
            ScheduledFuture<?> alarm=DEADLINES.schedule(new Runnable(){public void run(){
                if(outstanding.compareAndSet(true,false)){
                    expired.set(true);requestDeadlineExpired=true;try{requestSocket.close();}catch(IOException ignored){}
                }
            }},5000,TimeUnit.MILLISECONDS);
            RtspWire.Message response;
            try {
                output.write(bytes);output.flush();
                long deadline=now()+5000;
                while(true) {
                    check();if(now()>deadline || expired.get()){
                        requestDeadlineExpired=true;throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
                    }
                    response=nextMessage();
                    if(response.interleaved()) {
                        if(keepalive && sinkWorkNanos>=CONTROL_SINK_BUDGET_NS) {
                            sink.controlPacket(response.channel,response.body);droppedWhileControl++;
                        }else {
                            long began=System.nanoTime();
                            try{sink.packet(response.channel,response.body);}
                            finally{if(keepalive)sinkWorkNanos+=System.nanoTime()-began;}
                        }
                        continue;
                    }
                    String seq=response.first("cseq");
                    if(seq==null || response.all("cseq").size()!=1 || !seq.equals(Integer.toString(sequence)))throw protocol();
                    break;
                }
                if(expired.get())throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
            }catch(IOException e){if(expired.get() || this.expired)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);throw e;}
            finally {outstanding.set(false);alarm.cancel(false);controlWaitActive=false;java.util.Arrays.fill(bytes,(byte)0);}
            if(response.status!=401)return response;
            if(attempt==1)throw new RtspFailure(RtspFailure.Kind.AUTH);
            auth=RtspAuth.choose(response.all("www-authenticate"));
        }
        throw new RtspFailure(RtspFailure.Kind.AUTH);
    }
    private static RtspWire.Message ok(RtspWire.Message response)throws RtspFailure {
        if(response.status==401 || response.status==403)throw new RtspFailure(RtspFailure.Kind.AUTH);
        if(response.status!=200)throw protocol();return response;
    }
    void cancel(){cancelled=true;Socket own=socket;if(own!=null)try{own.close();}catch(IOException ignored){}}
    // Called only by this session's watchdog. No codec/audio release and no
    // reference to another Session's connection or future Socket.
    void expire(){expired=true;Socket own=socket;if(own!=null)try{own.close();}catch(IOException ignored){}}
    private RtspWire.Message nextMessage()throws IOException {
        progress.beginMessage(now());
        try {
            check();RtspWire.Message message=wire.next();
            // Reject a late complete message too, even if it completed between
            // two250ms watchdog ticks. The watchdog bounds blocked reads.
            if(progress.expired(now())==CameraProgress.Cause.MESSAGE_TIMEOUT){expire();throw new RtspFailure(RtspFailure.Kind.TIMEOUT);}
            check();return message;
        }finally{progress.endMessage();}
    }
    private void check()throws IOException {if(expired)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);if(cancelled)throw new IOException("camera_cancelled");}
    @Override public void close(){cancel();session=null;auth=null;}
    private static long now(){return System.nanoTime()/1000000L;}
    private static RtspFailure protocol(){return new RtspFailure(RtspFailure.Kind.PROTOCOL);}
}
