package org.x04g.home.camera;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Only the single H264 video + optional PCMA/8000/1 audio profile is selected. */
final class SdpSession {
    static final class Track {
        final String type;final List<Integer> payloads=new ArrayList<>();
        final Map<Integer,String> codecs=new HashMap<>(),fmtps=new HashMap<>();
        String control;int payloadType=-1;byte[] sps,pps;
        Track(String type){this.type=type;}
    }
    final Track video,audio;final String playUri;
    private SdpSession(Track video,Track audio,String playUri){this.video=video;this.audio=audio;this.playUri=playUri;}
    static SdpSession parse(byte[] bytes,String requestUri,String contentBase)throws RtspFailure {
        if(bytes.length==0 || bytes.length>65536)throw unsupported();
        String body=new String(bytes,StandardCharsets.UTF_8);
        if(body.indexOf('\0')>=0)throw unsupported();
        List<Track> tracks=new ArrayList<>();Track current=null;String sessionControl=null;
        String[] lines=body.split("\\r?\\n");
        if(lines.length>1024)throw unsupported();
        for(String line:lines) {
            if(line.length()>4096)throw unsupported();
            if(line.startsWith("m=")) {
                String[] p=line.substring(2).trim().split("\\s+");
                if(p.length<4 || tracks.size()>=8 || !p[2].equalsIgnoreCase("RTP/AVP"))throw unsupported();
                current=new Track(p[0]);tracks.add(current);
                for(int i=3;i<p.length;i++)current.payloads.add(number(p[i],127));
            }else if(line.startsWith("a=control:")) {
                String value=line.substring(10).trim();
                if(current==null)sessionControl=value;else current.control=value;
            }else if(current!=null && (line.startsWith("a=rtpmap:") || line.startsWith("a=fmtp:"))) {
                int colon=line.indexOf(':'),space=line.indexOf(' ',colon+1);
                if(space<0)throw unsupported();
                int pt=number(line.substring(colon+1,space),127);
                if(line.startsWith("a=rtpmap:"))current.codecs.put(pt,line.substring(space+1).trim());
                else current.fmtps.put(pt,line.substring(space+1).trim());
            }
        }
        Track video=null,audio=null;
        for(Track t:tracks)for(int pt:t.payloads) {
            String codec=t.codecs.get(pt);
            if(codec==null && pt==8)codec="PCMA/8000/1";
            if(codec==null)continue;
            if(video==null && t.type.equalsIgnoreCase("video") && codec.equalsIgnoreCase("H264/90000")) {
                t.payloadType=pt;video=t;parameters(t);
            }else if(audio==null && t.type.equalsIgnoreCase("audio")
                    && (codec.equalsIgnoreCase("PCMA/8000") || codec.equalsIgnoreCase("PCMA/8000/1"))) {
                t.payloadType=pt;audio=t;
            }
        }
        if(video==null || video.control==null)throw unsupported();
        String base=contentBase==null?requestUri:contentBase;
        // Validate Content-Base itself as well as every resolved track. Even
        // absolute same-origin controls must not legitimize an alien base.
        resolve(requestUri,requestUri,base);
        video.control=resolve(requestUri,base,video.control);
        if(audio!=null) {
            if(audio.control==null)audio=null;
            else audio.control=resolve(requestUri,base,audio.control);
        }
        String play=sessionControl==null || sessionControl.equals("*")?requestUri:resolve(requestUri,base,sessionControl);
        return new SdpSession(video,audio,play);
    }
    private static void parameters(Track t)throws RtspFailure {
        String value=t.fmtps.get(t.payloadType);if(value==null)return;
        Map<String,String> params=new HashMap<>();
        for(String part:value.split(";")) {
            int eq=part.indexOf('=');if(eq>0)params.put(part.substring(0,eq).trim().toLowerCase(Locale.US),part.substring(eq+1).trim());
        }
        String mode=params.get("packetization-mode");
        if(mode!=null && !mode.equals("0") && !mode.equals("1"))throw unsupported();
        String sets=params.get("sprop-parameter-sets");
        if(sets!=null) {
            if(sets.length()>180000)throw unsupported();
            try {
                for(String set:sets.split(",")) {
                    byte[] nal=Base64.getDecoder().decode(set);
                    if(nal.length<1 || nal.length>H264Assembler.MAX_PARAMETER_SET || (nal[0]&0x80)!=0)throw unsupported();
                    if((nal[0]&31)==7 && t.sps==null){H264Sps.parse(nal);t.sps=nal;}
                    else if((nal[0]&31)==8 && t.pps==null)t.pps=nal;
                }
            }catch(IllegalArgumentException e){throw unsupported();}
        }
    }
    static String resolve(String original,String base,String control)throws RtspFailure {
        try {
            URI root=new URI(original),resolved=new URI(base).resolve(control);
            int rootPort=root.getPort()<0?554:root.getPort(),port=resolved.getPort()<0?554:resolved.getPort();
            // Never send camera credentials to a server-selected different authority.
            if(!"rtsp".equalsIgnoreCase(resolved.getScheme()) || resolved.getHost()==null
                    || !resolved.getHost().equalsIgnoreCase(root.getHost()) || port!=rootPort
                    || resolved.getRawUserInfo()!=null || resolved.getRawFragment()!=null)throw unsupported();
            return resolved.toASCIIString();
        }catch(RtspFailure e){throw e;}catch(Exception e){throw unsupported();}
    }
    private static int number(String s,int max)throws RtspFailure {
        try {int n=Integer.parseInt(s);if(n<0 || n>max)throw unsupported();return n;}
        catch(NumberFormatException e){throw unsupported();}
    }
    private static RtspFailure unsupported(){return new RtspFailure(RtspFailure.Kind.SDP_UNSUPPORTED);}
}
