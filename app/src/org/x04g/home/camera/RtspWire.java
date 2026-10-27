package org.x04g.home.camera;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** RTSP1.0 messages and RFC2326 '$' RTP/RTCP interleaving share one bounded reader. */
final class RtspWire {
    static final int MAX_HEADER=16384, MAX_BODY=65536, MAX_INTERLEAVED=65535;
    static final class Message {
        int status,channel=-1;
        byte[] body;
        final Map<String,List<String>> headers=new LinkedHashMap<>();
        String first(String name){List<String> v=headers.get(name.toLowerCase(Locale.US));return v==null?null:v.get(0);}
        List<String> all(String name){List<String> v=headers.get(name.toLowerCase(Locale.US));return v==null?new ArrayList<String>():v;}
        boolean interleaved(){return channel>=0;}
    }
    private final InputStream input;
    RtspWire(InputStream input){this.input=input;}
    Message next()throws IOException {
        int first=input.read();if(first<0)throw new EOFException();
        Message out=new Message();
        if(first=='$') {
            out.channel=one();int length=(one()<<8)|one();
            if(length<1 || length>MAX_INTERLEAVED)throw bad();
            out.body=exact(length);return out;
        }
        String status=line(first);int budget=status.length()+2;
        if(!status.matches("RTSP/1[.]0 [1-5][0-9]{2}( .*)?"))throw bad();
        out.status=Integer.parseInt(status.substring(9,12));
        while(true) {
            String line=line(-1);budget+=line.length()+2;
            if(budget>MAX_HEADER)throw bad();
            if(line.isEmpty())break;
            int colon=line.indexOf(':');
            if(colon<=0 || line.charAt(0)==' ' || line.charAt(0)=='\t')throw bad();
            String key=line.substring(0,colon).toLowerCase(Locale.US),value=line.substring(colon+1).trim();
            if(!key.matches("[a-z0-9-]{1,80}"))throw bad();
            List<String> values=out.headers.get(key);
            if(values==null){values=new ArrayList<>();out.headers.put(key,values);}
            if(values.size()>=8)throw bad();values.add(value);
            if(out.headers.size()>80)throw bad();
        }
        if(out.headers.containsKey("transfer-encoding"))throw bad();
        int length=0;
        if(out.headers.containsKey("content-length")) {
            if(out.all("content-length").size()!=1)throw bad();
            try{length=Integer.parseInt(out.first("content-length"));}catch(NumberFormatException e){throw bad();}
            if(length<0 || length>MAX_BODY)throw bad();
        }
        out.body=exact(length);return out;
    }
    private String line(int first)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(128);int previous=-1;
        if(first>=0){b.write(first);previous=first;}
        while(true) {
            int next=one();
            if(next=='\n') {
                if(previous!='\r')throw bad();
                byte[] raw=b.toByteArray();
                return new String(raw,0,raw.length-1,StandardCharsets.ISO_8859_1);
            }
            if(previous=='\r' || (next<32 && next!='\r' && next!='\t') || b.size()>=4096)throw bad();
            b.write(next);previous=next;
        }
    }
    private int one()throws IOException {int v=input.read();if(v<0)throw new EOFException();return v;}
    private byte[] exact(int length)throws IOException {
        byte[] b=new byte[length];int at=0;
        while(at<length){int n=input.read(b,at,length-at);if(n<0)throw new EOFException();if(n==0)throw bad();at+=n;}
        return b;
    }
    private static RtspFailure bad(){return new RtspFailure(RtspFailure.Kind.PROTOCOL);}
}
