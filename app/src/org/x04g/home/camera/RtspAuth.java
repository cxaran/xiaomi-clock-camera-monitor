package org.x04g.home.camera;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** RTSP Basic and RFC7616 Digest auth/authless. No headers or credentials are logged. */
final class RtspAuth {
    private final boolean basic;
    private final Map<String,String> fields;
    private final String cnonce;
    private int nonceCount;
    private RtspAuth(boolean basic,Map<String,String> fields,String cnonce) {
        this.basic=basic;this.fields=fields;this.cnonce=cnonce;
    }
    static RtspAuth choose(List<String> challenges)throws RtspFailure {
        String basic=null;
        for(String line:challenges) {
            if(line.regionMatches(true,0,"Digest ",0,7))return parse(line,randomNonce());
            if(line.regionMatches(true,0,"Basic",0,5))basic=line;
        }
        if(basic!=null)return parse(basic,randomNonce());
        throw new RtspFailure(RtspFailure.Kind.AUTH_UNSUPPORTED);
    }
    static RtspAuth parse(String challenge,String cnonce)throws RtspFailure {
        if(challenge==null || challenge.length()>8192)throw unsupported();
        int space=challenge.indexOf(' ');
        String scheme=space<0?challenge:challenge.substring(0,space);
        boolean basic=scheme.equalsIgnoreCase("Basic");
        if(!basic && !scheme.equalsIgnoreCase("Digest"))throw unsupported();
        Map<String,String> values=params(space<0?"":challenge.substring(space+1));
        if(!basic) {
            if(!values.containsKey("realm") || !values.containsKey("nonce"))throw unsupported();
            String algorithm=values.containsKey("algorithm")?values.get("algorithm"):"MD5";
            if(!algorithm.equalsIgnoreCase("MD5") && !algorithm.equalsIgnoreCase("MD5-sess")
                    && !algorithm.equalsIgnoreCase("SHA-256") && !algorithm.equalsIgnoreCase("SHA-256-sess"))throw unsupported();
            if(values.containsKey("qop")) {
                boolean auth=false;
                for(String q:values.get("qop").split(","))if(q.trim().equalsIgnoreCase("auth"))auth=true;
                if(!auth)throw unsupported();
            }
            if("true".equalsIgnoreCase(values.get("userhash")))throw unsupported();
        }
        return new RtspAuth(basic,values,cnonce);
    }
    String authorization(String method,String uri,CameraEndpoint endpoint)throws RtspFailure {
        char[] password=endpoint.passwordCopy();
        ByteBuffer encoded=StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);
        try {
            if(basic) {
                byte[] prefix=(endpoint.username+":").getBytes(StandardCharsets.UTF_8);
                byte[] joined=new byte[prefix.length+bytes.length];
                try {
                    System.arraycopy(prefix,0,joined,0,prefix.length);
                    System.arraycopy(bytes,0,joined,prefix.length,bytes.length);
                    return "Basic "+Base64.getEncoder().encodeToString(joined);
                }finally{Arrays.fill(prefix,(byte)0);Arrays.fill(joined,(byte)0);}
            }
            if(nonceCount==Integer.MAX_VALUE)throw unsupported();
            nonceCount++;
            String algorithm=fields.containsKey("algorithm")?fields.get("algorithm"):"MD5";
            String hash=algorithm.toUpperCase(Locale.US).startsWith("SHA-256")?"SHA-256":"MD5";
            String realm=fields.get("realm"),nonce=fields.get("nonce");
            MessageDigest digest=MessageDigest.getInstance(hash);
            digest.update((endpoint.username+":"+realm+":").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
            String ha1=hex(digest.digest());
            if(algorithm.toLowerCase(Locale.US).endsWith("-sess"))ha1=hash(hash,ha1+":"+nonce+":"+cnonce);
            String ha2=hash(hash,method+":"+uri),nc=String.format(Locale.US,"%08x",nonceCount);
            String response=fields.containsKey("qop")?hash(hash,ha1+":"+nonce+":"+nc+":"+cnonce+":auth:"+ha2)
                    :hash(hash,ha1+":"+nonce+":"+ha2);
            String value="Digest username="+quote(endpoint.username)+", realm="+quote(realm)
                    +", nonce="+quote(nonce)+", uri="+quote(uri)+", response="+quote(response);
            // Match an implicit MD5 challenge without adding an optional attribute.
            if(fields.containsKey("algorithm"))value+=", algorithm="+algorithm;
            if(fields.containsKey("qop"))value+=", qop=auth, nc="+nc+", cnonce="+quote(cnonce);
            else if(algorithm.toLowerCase(Locale.US).endsWith("-sess"))value+=", cnonce="+quote(cnonce);
            if(fields.containsKey("opaque"))value+=", opaque="+quote(fields.get("opaque"));
            return value;
        }catch(RtspFailure e){throw e;}
        catch(Exception e){throw unsupported();}
        finally {
            Arrays.fill(password,'\0');Arrays.fill(bytes,(byte)0);
            if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);
        }
    }
    static Map<String,String> params(String text)throws RtspFailure {
        Map<String,String> out=new LinkedHashMap<>();int pos=0;
        while(pos<text.length()) {
            while(pos<text.length() && (text.charAt(pos)==',' || Character.isWhitespace(text.charAt(pos))))pos++;
            if(pos==text.length())break;
            int start=pos;
            while(pos<text.length() && text.charAt(pos)!='=' && text.charAt(pos)!=',')pos++;
            if(pos==text.length() || text.charAt(pos)!='=')throw unsupported();
            String key=text.substring(start,pos).trim().toLowerCase(Locale.US);pos++;
            if(!key.matches("[a-z0-9_-]{1,64}"))throw unsupported();
            while(pos<text.length() && Character.isWhitespace(text.charAt(pos)))pos++;
            String value;
            if(pos<text.length() && text.charAt(pos)=='"') {
                pos++;StringBuilder b=new StringBuilder();boolean closed=false;
                while(pos<text.length()) {
                    char c=text.charAt(pos++);
                    if(c=='"'){closed=true;break;}
                    if(c=='\\'){if(pos==text.length())throw unsupported();c=text.charAt(pos++);}
                    if(c=='\r' || c=='\n' || c<32)throw unsupported();b.append(c);
                }
                if(!closed)throw unsupported();value=b.toString();
                while(pos<text.length() && Character.isWhitespace(text.charAt(pos)))pos++;
                if(pos<text.length() && text.charAt(pos)!=',')throw unsupported();
            }else {
                start=pos;while(pos<text.length() && text.charAt(pos)!=',')pos++;
                value=text.substring(start,pos).trim();
                if(value.indexOf('\r')>=0 || value.indexOf('\n')>=0)throw unsupported();
            }
            if(out.put(key,value)!=null)throw unsupported();
        }
        return out;
    }
    private static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String hash(String algorithm,String value)throws Exception {return hex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] b){StringBuilder out=new StringBuilder(b.length*2);for(byte x:b)out.append(String.format(Locale.US,"%02x",x&255));return out.toString();}
    private static String randomNonce(){byte[] b=new byte[16];new SecureRandom().nextBytes(b);return hex(b);}
    private static RtspFailure unsupported(){return new RtspFailure(RtspFailure.Kind.AUTH_UNSUPPORTED);}
}
