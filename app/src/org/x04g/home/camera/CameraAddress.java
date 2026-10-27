package org.x04g.home.camera;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded LAN-only address resolution; numeric IPv4 never enters DNS. */
final class CameraAddress {
    interface Cancelled {boolean cancelled();}
    private static final ThreadPoolExecutor DNS=new ThreadPoolExecutor(1,1,20,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1),new ThreadFactory(){public Thread newThread(Runnable r){
                Thread t=new Thread(r,"X04G-CameraDNS");t.setDaemon(true);return t;
            }},new ThreadPoolExecutor.AbortPolicy());
    static {DNS.allowCoreThreadTimeOut(true);}
    private CameraAddress(){}
    static InetAddress resolve(final String host,Cancelled stop)throws IOException {
        if(host.matches("[0-9.]+"))return numeric(host);
        // Config v1 accepts LAN single-label names and .local only.
        if(host.indexOf('.')>=0 && !host.toLowerCase(java.util.Locale.US).endsWith(".local"))throw protocol();
        Future<InetAddress> future;
        try {future=DNS.submit(new Callable<InetAddress>(){public InetAddress call()throws Exception {
            for(InetAddress address:InetAddress.getAllByName(host))if(lan(address))return address;
            throw protocol();
        }});}catch(RejectedExecutionException e){throw new RtspFailure(RtspFailure.Kind.TIMEOUT);}
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(2500);
        try {
            while(true) {
                if(stop.cancelled())throw new IOException("camera_cancelled");
                long left=end-System.nanoTime();if(left<=0)throw new RtspFailure(RtspFailure.Kind.TIMEOUT);
                try{return future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException waiting){}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("camera_cancelled");}
                catch(java.util.concurrent.ExecutionException e){throw new IOException("camera_resolution_failed");}
            }
        }finally {
            // A platform DNS call may ignore interruption; at most one daemon
            // lookup and one queued item can exist, never an unbounded pool.
            future.cancel(true);DNS.remove((Runnable)future);
        }
    }
    static InetAddress numeric(String host)throws IOException {
        String[] parts=host.split("\\.",-1);if(parts.length!=4)throw protocol();byte[] raw=new byte[4];
        for(int i=0;i<4;i++)try {
            if(!parts[i].matches("[0-9]{1,3}"))throw protocol();int n=Integer.parseInt(parts[i]);if(n>255)throw protocol();raw[i]=(byte)n;
        }catch(NumberFormatException e){throw protocol();}
        InetAddress address=InetAddress.getByAddress(raw);if(!lan(address))throw protocol();return address;
    }
    private static boolean lan(InetAddress a){return a.getAddress().length==4 && a.isSiteLocalAddress() && !a.isMulticastAddress() && !a.isLoopbackAddress();}
    private static RtspFailure protocol(){return new RtspFailure(RtspFailure.Kind.PROTOCOL);}
}
