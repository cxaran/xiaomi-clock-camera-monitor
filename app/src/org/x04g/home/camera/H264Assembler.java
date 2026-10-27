package org.x04g.home.camera;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** RFC6184 non-interleaved mode: single NAL, STAP-A and FU-A, Annex-B output. */
final class H264Assembler {
    static final int MAX_ACCESS_UNIT=1024*1024, MAX_PARAMETER_SET=65536;
    private static final byte[] START={0,0,0,1};
    interface Sink { void accessUnit(byte[] annexB,long timeUs,boolean idr) throws Exception; }
    private final Sink sink;
    private final ByteArrayOutputStream au=new ByteArrayOutputStream(32768);
    private final ByteArrayOutputStream fragment=new ByteArrayOutputStream(8192);
    private byte[] sps,pps;
    private long currentTimestamp=-1,ssrc=-1,lastTimestamp,ptsTicks;
    private int lastSequence=-1,fragmentType,fragmentNri;
    private boolean damaged,fragmentOpen,hasIdr,waitingIdr=true,haveClock;
    long lostPackets,discardedAccessUnits;
    H264Assembler(Sink sink,byte[] sps,byte[] pps) { this.sink=sink;this.sps=cloneOrNull(sps);this.pps=cloneOrNull(pps); }
    private static byte[] cloneOrNull(byte[] b){return b==null?null:b.clone();}
    byte[] parameterSps(){return cloneOrNull(sps);}
    byte[] parameterPps(){return cloneOrNull(pps);}
    void packet(RtpPacket p) throws Exception {
        if (ssrc!=-1 && ssrc!=p.ssrc) reset();
        ssrc=p.ssrc;
        if(lastSequence!=-1 && p.sequence!=((lastSequence+1)&65535)) {
            // TCP preserves packet order; duplicates/gaps invalidate this AU.
            lostPackets++;damaged=true;waitingIdr=true;fragmentOpen=false;fragment.reset();
        }
        lastSequence=p.sequence;
        if(currentTimestamp!=-1 && currentTimestamp!=p.timestamp) {
            // A missing marker must never merge two pictures.
            if(au.size()!=0 || fragmentOpen){discardedAccessUnits++;waitingIdr=true;}
            au.reset();fragment.reset();fragmentOpen=false;hasIdr=false;damaged=false;
        }
        currentTimestamp=p.timestamp;
        int at=p.offset,end=at+p.length,type=p.bytes[at]&31;
        if ((p.bytes[at]&0x80)!=0) throw bad();
        if(type>=1 && type<=23) {
            if(fragmentOpen) { damaged=true;fragmentOpen=false;fragment.reset(); }
            nal(p.bytes,at,p.length);
        } else if(type==24) {
            if(p.length<4)throw bad();
            if(fragmentOpen) {damaged=true;fragmentOpen=false;fragment.reset();}
            at++;
            while(at<end) {
                if(at+2>end)throw bad();
                int n=RtpPacket.u16(p.bytes,at);at+=2;
                if(n<1 || at+n>end || (p.bytes[at]&31)<1 || (p.bytes[at]&31)>23)throw bad();
                nal(p.bytes,at,n);at+=n;
            }
        } else if(type==28) {
            if(p.length<3)throw bad();
            int header=p.bytes[at+1]&255,nalType=header&31;
            if((header&0x20)!=0 || nalType<1 || nalType>23 || (header&0xc0)==0xc0)throw bad();
            if((header&0x80)!=0) {
                if(fragmentOpen)damaged=true;
                fragment.reset();fragmentOpen=true;fragmentType=nalType;fragmentNri=p.bytes[at]&0xe0;
                fragment.write((p.bytes[at]&0xe0)|nalType);
            } else if(!fragmentOpen || fragmentType!=nalType || fragmentNri!=(p.bytes[at]&0xe0)) {damaged=true;waitingIdr=true;return;}
            boundedWrite(fragment,p.bytes,at+2,p.length-2);
            if((header&0x40)!=0) {
                byte[] complete=fragment.toByteArray();fragmentOpen=false;fragment.reset();
                nal(complete,0,complete.length);
            }
        } else throw new RtspFailure(RtspFailure.Kind.SDP_UNSUPPORTED);
        if(p.marker)finish(p.timestamp);
    }
    private void nal(byte[] b,int at,int n)throws RtspFailure {
        int type=b[at]&31;
        if((b[at]&0x80)!=0)throw bad();
        if(type==7 || type==8) {
            if(n>MAX_PARAMETER_SET)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
            if(type==7){byte[] candidate=Arrays.copyOfRange(b,at,at+n);H264Sps.parse(candidate);sps=candidate;}
            else pps=Arrays.copyOfRange(b,at,at+n);
        }
        if(type==5)hasIdr=true;
        boundedWrite(au,START,0,4);boundedWrite(au,b,at,n);
    }
    private void finish(long timestamp)throws Exception {
        if(fragmentOpen)damaged=true;
        if(!damaged && au.size()>0 && (!waitingIdr || hasIdr)) {
            byte[] bytes=au.toByteArray();
            if(waitingIdr) {
                if(sps==null || pps==null) {discardedAccessUnits++;clearPicture();return;}
                int size=8+sps.length+pps.length+bytes.length;
                if(size>MAX_ACCESS_UNIT)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
                byte[] joined=new byte[size];int pos=0;
                for(byte[] part:new byte[][]{START,sps,START,pps,bytes}) {System.arraycopy(part,0,joined,pos,part.length);pos+=part.length;}
                bytes=joined;waitingIdr=false;
            }
            if(!haveClock){lastTimestamp=timestamp;haveClock=true;ptsTicks=0;}
            else {ptsTicks+=(int)(timestamp-lastTimestamp);lastTimestamp=timestamp;}
            sink.accessUnit(bytes,Math.max(0,ptsTicks*1000000L/90000L),hasIdr);
        } else {discardedAccessUnits++;if(damaged)waitingIdr=true;}
        clearPicture();
    }
    private void clearPicture(){au.reset();fragment.reset();fragmentOpen=false;hasIdr=false;damaged=false;currentTimestamp=-1;}
    void reset(){clearPicture();waitingIdr=true;lastSequence=-1;ssrc=-1;haveClock=false;ptsTicks=0;}
    private static void boundedWrite(ByteArrayOutputStream out,byte[] b,int at,int n)throws RtspFailure {
        if(n<0 || out.size()+n>MAX_ACCESS_UNIT)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
        out.write(b,at,n);
    }
    private static RtspFailure bad(){return new RtspFailure(RtspFailure.Kind.PROTOCOL);}
}
