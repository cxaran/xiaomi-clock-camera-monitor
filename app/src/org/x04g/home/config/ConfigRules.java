package org.x04g.home.config;

import java.util.Locale;
import java.util.UUID;

/** Pure validation; never resolves DNS, contacts a host or logs rejected input. */
public final class ConfigRules {
    public static final int MAX_CAMERAS=12, MAX_IMPORT_BYTES=65536, MAX_FILE_BYTES=262144;
    private ConfigRules() { }
    static void check(boolean ok) throws ConfigException {
        if(!ok) throw new ConfigException(ConfigException.Code.INVALID_INPUT);
    }
    private static String text(String s,int min,int max) throws ConfigException {
        check(s!=null); s=s.trim(); check(s.length()>=min&&s.length()<=max);
        for(int i=0;i<s.length();i++) check(!Character.isISOControl(s.charAt(i)));
        return s;
    }
    public static CameraConfig validate(CameraDraft input,boolean hasPassword) throws ConfigException {
        check(input!=null); CameraDraft d=new CameraDraft();
        d.id=text(input.id,0,64); if(d.id.isEmpty()) d.id=UUID.randomUUID().toString();
        check(d.id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"));
        d.label=text(input.label,1,48); d.host=host(input.host);
        check(input.port>=1&&input.port<=65535); d.port=input.port;
        d.path=text(input.path,1,512);
        check(d.path.startsWith("/")&&!d.path.startsWith("//"));
        for(int i=0;i<d.path.length();i++) {
            char c=d.path.charAt(i);
            check(!Character.isWhitespace(c)&&c!='\\'&&c!='#'&&c!='?'&&c!='@');
            if(c=='%') { check(i+2<d.path.length()&&hex(d.path.charAt(i+1))&&hex(d.path.charAt(i+2))); i+=2; }
        }
        d.username=text(input.username,1,128);
        check(d.username.indexOf(':')<0&&d.username.indexOf('"')<0&&d.username.indexOf('\\')<0);
        d.mac=mac(input.mac); d.onvifUuid=onvifUuid(input.onvifUuid);
        d.model=text(input.model,0,64); d.enabled=input.enabled;
        check(input.verifiedAtMillis>=0);d.needsVerification=input.needsVerification;
        d.verifiedAtMillis=input.verifiedAtMillis;
        check(d.needsVerification||d.verifiedAtMillis>0);
        return new CameraConfig(d,hasPassword);
    }
    public static String host(String input) throws ConfigException {
        String s=text(input,1,253).toLowerCase(Locale.ROOT);
        check(s.indexOf('/')<0&&s.indexOf(':')<0&&s.indexOf('@')<0&&s.indexOf('%')<0);
        if(s.matches("[0-9.]+")) {
            String[] parts=s.split("\\.",-1); check(parts.length==4); int[] n=new int[4];
            for(int i=0;i<4;i++) {
                check(parts[i].matches("[0-9]{1,3}")); n[i]=Integer.parseInt(parts[i]); check(n[i]<=255);
            }
            check(n[0]==10 || (n[0]==172&&n[1]>=16&&n[1]<=31) || (n[0]==192&&n[1]==168));
            return n[0]+"."+n[1]+"."+n[2]+"."+n[3];
        }
        if(s.endsWith(".")) s=s.substring(0,s.length()-1);
        // v1 permits LAN single-label names and .local, not WAN names or IPv6 guessing.
        check(s.indexOf('.')<0||s.endsWith(".local"));
        for(String label:s.split("\\.",-1)) check(label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"));
        check(!s.equals("localhost")); return s;
    }
    public static String mac(String input) throws ConfigException {
        String s=text(input,0,32); if(s.isEmpty()) return "";
        s=s.replace(":","").replace("-","").toUpperCase(Locale.ROOT);
        check(s.matches("[0-9A-F]{12}")&&!s.equals("000000000000")&&!s.equals("FFFFFFFFFFFF"));
        check((Integer.parseInt(s.substring(0,2),16)&1)==0);
        StringBuilder b=new StringBuilder(); for(int i=0;i<12;i+=2) { if(i>0)b.append(':');b.append(s.substring(i,i+2)); } return b.toString();
    }
    public static String onvifUuid(String input) throws ConfigException {
        String s=text(input,0,80).toLowerCase(Locale.ROOT); if(s.isEmpty())return "";
        if(s.startsWith("urn:uuid:"))s=s.substring(9);else if(s.startsWith("uuid:"))s=s.substring(5);
        check(s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        return "urn:uuid:"+s;
    }
    public static void password(char[] password) throws ConfigException {
        check(password!=null&&password.length>0&&password.length<=512);
        for(char c:password)check(c!='\0'&&c!='\r'&&c!='\n'&&!Character.isSurrogate(c));
    }
    private static boolean hex(char c) { return c>='0'&&c<='9'||c>='a'&&c<='f'||c>='A'&&c<='F'; }
}
