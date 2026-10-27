package org.x04g.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.x04g.home.adminweb.TemporaryAdminWeb;
import org.x04g.home.config.ConfigRepository;
import org.x04g.home.ui.adminweb.WebAdminPanel;

/** Native owner of one temporary web session. No listener exists until the user presses Start. */
final class AdminWebSession implements WebAdminPanel.Listener,AutoCloseable {
    interface Listener {void onBack();void onManualTestApproved(String id,long revision);void onStateChanged();boolean canStartNetwork();}
    private final Activity activity;
    private final ConfigRepository repository;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=newIo();
    private static ExecutorService newIo(){ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,5L,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(1));executor.allowCoreThreadTimeOut(true);return executor;}
    private final WebAdminPanel panel;
    private TemporaryAdminWeb server;
    private boolean closed;private long generation;

    AdminWebSession(Activity activity,ConfigRepository repository,Listener listener){
        this.activity=activity;this.repository=repository;this.listener=listener;
        panel=new WebAdminPanel(activity,this);
    }
    WebAdminPanel view(){return panel;}
    void open(){if(!closed)panel.open();}
    void setActive(boolean active){if(!closed)panel.setActive(active);}
    String state(){return server==null?"STOPPED":server.state().name();}
    boolean taskActive(){String value=state();return !closed&&!("STOPPED".equals(value)||"FAILED".equals(value)||"NEW".equals(value));}
    @Override public void onStartHttp(){
        if(closed)return;stopNetwork();if(!listener.canStartNetwork()){panel.showState("FAILED");return;}final long token=generation;
        try{io.execute(()->{
            String found=null;try{ActivityManager am=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();if(am!=null){am.getMemoryInfo(memory);if(!memory.lowMemory)found=wifiOrEthernetAddress(activity);}}catch(Exception ignored){}
            final String address=found;
            main.post(()->{
                if(closed||generation!=token)return;
                if(address==null){panel.showState("FAILED");return;}
                TemporaryAdminWeb candidate=new TemporaryAdminWeb(activity,repository,new TemporaryAdminWeb.Listener(){
                    private boolean current(){return !closed&&generation==token;}
                    public void onState(TemporaryAdminWeb.State state){if(current()){
                        // Automatic idle/TTL completion owns the same cleanup as
                        // a user stop. Invalidate queued callbacks from this run
                        // before allowing the panel to start another session.
                        if(state==TemporaryAdminWeb.State.STOPPED||state==TemporaryAdminWeb.State.FAILED){server=null;generation++;}
                        panel.showState(state.name());listener.onStateChanged();
                    }}
                    public void onReady(TemporaryAdminWeb.Descriptor d){if(current())panel.showReady(d.url,d.expiresAtElapsedRealtime);}
                    public void onPairingCode(String code){if(current())panel.showPairingCode(code);}
                    public void onManualTestRequested(String id,long revision){if(current())panel.showManualTestRequest(id,revision);}
                });
                server=candidate;if(!candidate.startHttp(address)){server=null;generation++;candidate.close();panel.showState("FAILED");}listener.onStateChanged();
            });
        });}catch(RejectedExecutionException busy){panel.showState("FAILED");}
    }
    @Override public void onStop(){stopNetwork();}
    @Override public void onBack(){if(!closed)listener.onBack();}
    @Override public void onManualTestDecision(String id,long revision,boolean approved){
        if(server!=null)server.acknowledgeManualTestRequest();
        if(!closed&&approved){stopNetwork();listener.onManualTestApproved(id,revision);}
    }
    private void stopNetwork(){generation++;TemporaryAdminWeb prior=server;server=null;if(prior!=null)prior.close();listener.onStateChanged();}
    @Override public void close(){if(closed)return;closed=true;stopNetwork();panel.close();io.shutdownNow();main.removeCallbacksAndMessages(null);}
    /** Does not choose VPN/cellular/radio bridge interfaces or a WAN address. */
    private static String wifiOrEthernetAddress(Context context)throws Exception{
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try{if(manager!=null){
            Network network=manager.getActiveNetwork();NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
            if(caps!=null&&(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))){
                LinkProperties properties=manager.getLinkProperties(network);
                if(properties!=null)for(LinkAddress link:properties.getLinkAddresses())if(privateIpv4(link.getAddress()))return link.getAddress().getHostAddress();
            }
        }}catch(SecurityException unavailable){/* Static interface fallback does not require network-state permission. */}
        // A static LAN address can exist before Android declares that network validated.
        for(String name:new String[]{"wlan0","eth0"}){
            NetworkInterface net=NetworkInterface.getByName(name);if(net==null||!net.isUp()||net.isLoopback())continue;
            for(Enumeration<InetAddress> addresses=net.getInetAddresses();addresses.hasMoreElements();){InetAddress address=addresses.nextElement();if(privateIpv4(address))return address.getHostAddress();}
        }
        return null;
    }
    private static boolean privateIpv4(InetAddress address){
        if(!(address instanceof Inet4Address)||address.isLoopbackAddress()||address.isMulticastAddress())return false;
        byte[] value=address.getAddress();int a=value[0]&255,b=value[1]&255;
        return a==10||a==172&&b>=16&&b<=31||a==192&&b==168;
    }
}
