package org.x04g.home.camera;

/** Bounded RTP v2 view. The caller retains the packet until its synchronous consumer returns. */
final class RtpPacket {
    final byte[] bytes;
    final int offset, length, sequence, payloadType;
    final long timestamp, ssrc;
    final boolean marker;
    private RtpPacket(byte[] bytes, int offset, int length, int sequence, int payloadType,
                      long timestamp, long ssrc, boolean marker) {
        this.bytes=bytes; this.offset=offset; this.length=length; this.sequence=sequence;
        this.payloadType=payloadType; this.timestamp=timestamp; this.ssrc=ssrc; this.marker=marker;
    }
    static RtpPacket parse(byte[] bytes, int length) throws RtspFailure {
        if (length < 12 || length > bytes.length || (bytes[0] & 0xc0) != 0x80) throw bad();
        int offset=12+4*(bytes[0]&15);
        if (offset>length) throw bad();
        if ((bytes[0]&0x10)!=0) {
            if (offset+4>length) throw bad();
            int extensionWords=u16(bytes,offset+2);
            offset+=4+4*extensionWords;
            if (offset>length) throw bad();
        }
        int end=length;
        if ((bytes[0]&0x20)!=0) {
            int pad=bytes[length-1]&255;
            if (pad==0 || pad>length-offset) throw bad();
            end-=pad;
        }
        if (end<=offset) throw bad();
        return new RtpPacket(bytes,offset,end-offset,u16(bytes,2),bytes[1]&127,
                u32(bytes,4),u32(bytes,8),(bytes[1]&0x80)!=0);
    }
    static int u16(byte[] p,int at) { return ((p[at]&255)<<8)|(p[at+1]&255); }
    static long u32(byte[] p,int at) { return ((long)(p[at]&255)<<24)|((long)(p[at+1]&255)<<16)|((long)(p[at+2]&255)<<8)|(p[at+3]&255); }
    private static RtspFailure bad() { return new RtspFailure(RtspFailure.Kind.PROTOCOL); }
}
