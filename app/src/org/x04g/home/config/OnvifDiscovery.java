package org.x04g.home.config;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Explicit bounded WS-Discovery only. No HTTP, RTSP, credentials, ARP or automatic config writes. */
public final class OnvifDiscovery {
    public interface Cancellation { boolean isCancelled(); }
    public enum Match { ONVIF_UUID, ADVERTISED_MAC, NONE }
    public static final class Candidate {
        public final String host,onvifUuid,advertisedMac;
        /** Always true: discovery is an identity hint, not authenticated camera validation. */
        public final boolean requiresAuthenticatedVerification=true;
        Candidate(String host,String uuid,String mac){this.host=host;onvifUuid=uuid;advertisedMac=mac;}
        @Override public String toString(){return "OnvifCandidate[redacted]";}
    }
    private OnvifDiscovery() { }
    public static Match match(CameraConfig camera,Candidate candidate) {
        if(!camera.onvifUuid.isEmpty())return camera.onvifUuid.equals(candidate.onvifUuid)?Match.ONVIF_UUID:Match.NONE;
        if(!camera.mac.isEmpty()&&!candidate.advertisedMac.isEmpty()&&camera.mac.equals(candidate.advertisedMac))return Match.ADVERTISED_MAC;
        return Match.NONE;
    }
    /** Bind to a caller-selected RFC1918 IPv4 interface. One probe, TTL1, <=3s/32 packets/12 results. */
    public static List<Candidate> discover(Inet4Address lan,Cancellation cancellation) throws ConfigException {
        try {
            ConfigRules.host(lan.getHostAddress());NetworkInterface nic=NetworkInterface.getByInetAddress(lan);
            if(nic==null||!nic.isUp()||nic.isLoopback())throw new ConfigException(ConfigException.Code.INVALID_INPUT);
            int prefix=-1;for(InterfaceAddress a:nic.getInterfaceAddresses())if(lan.equals(a.getAddress()))prefix=a.getNetworkPrefixLength();
            if(prefix<1||prefix>30)throw new ConfigException(ConfigException.Code.INVALID_INPUT);
            String message="urn:uuid:"+UUID.randomUUID().toString();
            byte[] probe=("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:n=\"http://www.onvif.org/ver10/network/wsdl\"><s:Header><a:MessageID>"+message+"</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action></s:Header><s:Body><d:Probe><d:Types>n:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
            List<Candidate> found=new ArrayList<Candidate>();Set<String> identities=new HashSet<String>();
            try(MulticastSocket socket=new MulticastSocket((SocketAddress)null)) {
                socket.setReuseAddress(false);socket.bind(new InetSocketAddress(lan,0));socket.setNetworkInterface(nic);socket.setTimeToLive(1);socket.setSoTimeout(250);
                cancelled(cancellation);socket.send(new DatagramPacket(probe,probe.length,InetAddress.getByAddress(new byte[]{(byte)239,(byte)255,(byte)255,(byte)250}),3702));
                long until=System.nanoTime()+3000000000L;byte[] buffer=new byte[32769];int packets=0;
                while(System.nanoTime()<until&&packets<32&&found.size()<ConfigRules.MAX_CAMERAS) {
                    cancelled(cancellation);DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
                    try {socket.receive(packet);}catch(SocketTimeoutException e){continue;}
                    packets++;if(!(packet.getAddress() instanceof Inet4Address)||packet.getLength()>32768||!sameSubnet(lan,packet.getAddress(),prefix))continue;
                    try {
                        for(Candidate c:parseResponse(Arrays.copyOf(packet.getData(),packet.getLength()),packet.getAddress().getHostAddress(),message)) {
                            String identity=c.onvifUuid+"|"+c.host;
                            if(identities.add(identity)&&found.size()<ConfigRules.MAX_CAMERAS)found.add(c);
                        }
                    }catch(ConfigException malformed){ /* Ignore bounded untrusted announcements; never log their text. */ }
                }
            }
            return Collections.unmodifiableList(found);
        }catch(ConfigException e){throw e;}
        catch(Exception e){throw new ConfigException(ConfigException.Code.DISCOVERY_FAILED);}
    }
    static List<Candidate> parseResponse(byte[] xml,String sourceIp,String expectedMessage) throws ConfigException {
        try {
            if(xml.length==0||xml.length>32768)throw new Exception();String source=ConfigRules.host(sourceIp);
            // UTF-8 only; reject DTD/ENTITY before parsing. Resolver also refuses any external resource.
            String text=new String(xml,StandardCharsets.UTF_8);String upper=text.toUpperCase(Locale.ROOT);
            if(text.indexOf('\0')>=0||upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY"))throw new Exception();
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
            DocumentBuilder builder=factory.newDocumentBuilder();builder.setEntityResolver(new EntityResolver(){public InputSource resolveEntity(String publicId,String systemId)throws SAXException{throw new SAXException();}});
            builder.setErrorHandler(new DefaultHandler(){@Override public void error(SAXParseException e)throws SAXException{throw e;}@Override public void fatalError(SAXParseException e)throws SAXException{throw e;}});
            Document doc=builder.parse(new InputSource(new StringReader(text)));
            NodeList relations=doc.getElementsByTagNameNS("*","RelatesTo");boolean related=false;
            for(int i=0;i<relations.getLength();i++)if(expectedMessage.equals(relations.item(i).getTextContent().trim()))related=true;
            if(!related)throw new Exception();
            NodeList matches=doc.getElementsByTagNameNS("*","ProbeMatch");if(matches.getLength()>12)throw new Exception();List<Candidate> result=new ArrayList<Candidate>();
            for(int i=0;i<matches.getLength();i++) {
                Element match=(Element)matches.item(i);String uuid="",mac="";NodeList addresses=match.getElementsByTagNameNS("*","Address");
                if(addresses.getLength()!=1)continue;uuid=ConfigRules.onvifUuid(addresses.item(0).getTextContent().trim());if(uuid.isEmpty())continue;
                NodeList xaddrs=match.getElementsByTagNameNS("*","XAddrs");if(xaddrs.getLength()!=1)continue;boolean same=false;
                for(String address:xaddrs.item(0).getTextContent().trim().split("\\s+")) {
                    URI uri=new URI(address);if(("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))&&uri.getRawUserInfo()==null&&source.equals(uri.getHost()))same=true;
                }
                if(!same)continue;
                NodeList scopes=match.getElementsByTagNameNS("*","Scopes");
                if(scopes.getLength()==1)for(String scope:scopes.item(0).getTextContent().split("\\s+")) {
                    String lower=scope.toLowerCase(Locale.ROOT),prefix="onvif://www.onvif.org/mac/";
                    if(lower.startsWith(prefix))mac=ConfigRules.mac(scope.substring(prefix.length()));
                }
                result.add(new Candidate(source,uuid,mac));
            }
            return result;
        }catch(Exception e){throw new ConfigException(ConfigException.Code.DISCOVERY_FAILED);}
    }
    private static void cancelled(Cancellation cancellation)throws ConfigException {
        if(Thread.currentThread().isInterrupted()||cancellation!=null&&cancellation.isCancelled())throw new ConfigException(ConfigException.Code.CANCELLED);
    }
    private static boolean sameSubnet(InetAddress a,InetAddress b,int prefix) {
        byte[] x=a.getAddress(),y=b.getAddress();if(x.length!=4||y.length!=4)return false;
        for(int i=0;i<4;i++){int bits=Math.min(8,Math.max(0,prefix-i*8)),mask=bits==0?0:(0xff<<(8-bits))&0xff;if((x[i]&mask)!=(y[i]&mask))return false;}return true;
    }
}
