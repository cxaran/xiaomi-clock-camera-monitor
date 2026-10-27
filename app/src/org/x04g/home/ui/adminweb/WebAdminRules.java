package org.x04g.home.ui.adminweb;

import java.net.URI;
import java.util.Locale;

/** Display boundary: only public LAN URLs may enter the QR; no query/code/token. */
final class WebAdminRules {
    private WebAdminRules(){}
    static String publicUrl(String value) {
        try {
            if(value==null || value.length()>128)throw new IllegalArgumentException();
            for(int i=0;i<value.length();i++)if(value.charAt(i)<=32 || value.charAt(i)>=127)throw new IllegalArgumentException();
            URI uri=new URI(value);
            if(!"http".equals(uri.getScheme()) || uri.getHost()==null || uri.getPort()<1 || uri.getPort()>65535
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !"/".equals(uri.getRawPath())
                    || !(uri.getHost()+":"+uri.getPort()).equals(uri.getRawAuthority()))throw new IllegalArgumentException();
            String[] parts=uri.getHost().split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException();int[] n=new int[4];
            for(int i=0;i<4;i++){if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException();n[i]=Integer.parseInt(parts[i]);if(n[i]>255)throw new IllegalArgumentException();}
            if(!(n[0]==10 || n[0]==172&&n[1]>=16&&n[1]<=31 || n[0]==192&&n[1]==168))throw new IllegalArgumentException();
            return uri.toASCIIString();
        }catch(Exception error){throw new IllegalArgumentException("adminweb_public_url_required");}
    }
    static long remainingSeconds(long expiry,long now){return expiry<=now?0:(expiry-now+999)/1000;}
    static String countdown(long expiry,long now){long seconds=remainingSeconds(expiry,now);return String.format(Locale.US,"%d:%02d",seconds/60,seconds%60);}
}
