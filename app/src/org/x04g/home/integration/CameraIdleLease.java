package org.x04g.home.integration;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

/**
 * Version-specific, main-thread-only lease of the OEM timeout query for an own visible task.
 * The historical class name covers both real video and explicitly opened own administration.
 * The caller must already have verified the stock Launcher APK and current Home bindings.
 * No controller, Fragment, View or Proxy remains replaced after a call returns.
 */
public final class CameraIdleLease implements AutoCloseable {
    private static final String ACTIVITY="com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity";
    private static final String VIEW_MANAGER="com.google.assistant.surfaces.clock.common.ViewManager";
    private static final String HOLDER="com.google.assistant.surfaces.clock.common.GlanceableContainerHolder";
    private static final String PAGER="com.google.assistant.surfaces.clock.home.TileViewPager";
    private final Activity activity;
    private final Object home,viewManager,proxy;
    private final View homeView,pager,base;
    private final ViewGroup holder;
    private final Handler handler;
    private final Field homeManager,homePager,managerHolder,managerHandler,controller;
    private final Method reschedule;
    private final BooleanSupplier ownTaskActive;
    private boolean closed,requested,effective,leased;
    private long queryCount,appliedCount,releaseCount;
    private String lastCode="CREATED";

    /** Construction only resolves/checks identities and creates a dormant Java Proxy. */
    public static CameraIdleLease create(Activity activity,Object home,View pager,
            BooleanSupplier ownTaskActive)throws Exception {
        return new CameraIdleLease(activity,home,pager,ownTaskActive);
    }

    private CameraIdleLease(Activity a,Object h,View p,BooleanSupplier eligible)throws Exception {
        requireMain();require(a!=null&&h!=null&&p!=null&&eligible!=null,"ARGUMENTS");
        require(ACTIVITY.equals(a.getClass().getName())&&"cat".equals(h.getClass().getName())
                &&PAGER.equals(p.getClass().getName()),"OEM_TYPES");
        activity=a;home=h;pager=p;ownTaskActive=eligible;
        ClassLoader loader=h.getClass().getClassLoader();
        Class<?> contract=Class.forName("bvz",false,loader),homeContract=Class.forName("bwe",false,loader);
        require(contract.isInterface()&&homeContract.isInterface()
                &&Modifier.isPublic(contract.getModifiers())&&Modifier.isPublic(homeContract.getModifiers())
                &&contract.isAssignableFrom(homeContract)&&homeContract.isInstance(home),"OEM_INTERFACES");
        require(method(homeContract,"c").getReturnType()==long.class,"TIMEOUT_CONTRACT");
        homeManager=field(h.getClass(),"h");homePager=field(h.getClass(),"y");
        viewManager=homeManager.get(home);
        require(viewManager!=null&&VIEW_MANAGER.equals(viewManager.getClass().getName()),"VIEW_MANAGER");
        managerHolder=field(viewManager.getClass(),"c");managerHandler=field(viewManager.getClass(),"q");
        Object container=managerHolder.get(viewManager),timer=managerHandler.get(viewManager);
        require(container instanceof ViewGroup&&HOLDER.equals(container.getClass().getName())
                &&timer instanceof Handler,"MANAGER_FIELDS");
        holder=(ViewGroup)container;handler=(Handler)timer;
        require(holder.getChildCount()>=1&&holder.getChildCount()<=16,"CONTAINER_COUNT");
        base=holder.getChildAt(0);require(base!=null&&"bwa".equals(base.getClass().getName()),"BASE_CONTAINER");
        controller=field(base.getClass(),"d");require(controller.getType()==contract,"CONTROLLER_FIELD");
        reschedule=method(viewManager.getClass(),"x");
        require(reschedule.getReturnType()==void.class&&Modifier.isFinal(reschedule.getModifiers()),"SCHEDULER_METHOD");
        require(method(home.getClass(),"x").invoke(home)==home,"ORIGINAL_FRAGMENT");
        Object hv=method(home.getClass(),"getView").invoke(home);
        require(hv instanceof View,"HOME_VIEW");homeView=(View)hv;
        proxy=Proxy.newProxyInstance(loader,new Class<?>[]{homeContract},(ignored,target,args)->{
            if("c".equals(target.getName())&&target.getParameterTypes().length==0&&target.getReturnType()==long.class){
                requireMain();require(leased,"QUERY_OUTSIDE_LEASE");queryCount++;return Long.MAX_VALUE;
            }
            try{return target.invoke(home,args);}
            catch(InvocationTargetException error){throw error.getCause();}
        });
        guard();
    }

    /**
     * Call on a real state/frame, own administration state, visibility/focus transition,
     * or a pending timeout. Eligibility means either selected visible video which has
     * actually rendered (not CONNECTING/BUFFERING/isRunning alone), or our own editor/
     * temporary administration panel explicitly opened by the user and still active.
     * Return false for pause, departure, foreign overlays, stopped/expired web sessions
     * and faults. An open but expired web panel alone is not an active task.
     * Repeated calls do not refresh any timer unless OEM has queued message 1 again
     * or eligibility has changed. No user input or media state is fabricated.
     */
    public boolean reevaluate(){
        if(closed){lastCode="CLOSED";return false;}
        if(Looper.myLooper()!=Looper.getMainLooper()){lastCode="WRONG_THREAD";return false;}
        if(leased){lastCode="REENTRANT_REFUSED";return false;}
        try {
            guard();boolean eligible=ownTaskActive.getAsBoolean();
            if(!baseIsTop()){lastCode="OVERLAY_UNTOUCHED";return false;}
            if(!eligible){
                if(requested){requested=false;effective=false;invokeOriginal();releaseCount++;lastCode="ORIGINAL_REEVALUATED";}
                else lastCode="INACTIVE";
                return false;
            }
            if(requested&&!handler.hasMessages(1)){lastCode=effective?"HELD_NO_PENDING_TIMEOUT":"WAITING_NATIVE_SCHEDULER";return effective;}
            requested=true;
            long before=queryCount;
            leased=true;
            try {
                require(controller.get(base)==home,"CONTROLLER_OWNERSHIP");
                controller.set(base,proxy);
                reschedule.invoke(viewManager);
            } finally {
                // Critical: GlanceableContainerHolder casts removed controllers to bwn.
                // Restore before returning to the Looper, including invocation failures.
                try {
                    Object current=controller.get(base);
                    if(current==proxy)controller.set(base,home);
                    else require(current==home,"CONTROLLER_CHANGED_DURING_LEASE");
                } finally {leased=false;}
            }
            require(controller.get(base)==home,"RESTORATION_UNCONFIRMED");
            effective=queryCount>before;
            if(effective){appliedCount++;lastCode="TIMEOUT_QUERY_LEASED_AND_RESTORED";}
            else lastCode="WAITING_NATIVE_SCHEDULER";
            return effective;
        } catch(Exception error){
            // Fail closed: attempt only the unchanged Home's normal rescheduler.
            requested=false;effective=false;closed=true;lastCode="LEASE_FAILED";
            try{if(!leased&&controller.get(base)==home&&baseIsTop())invokeOriginal();}
            catch(Exception ignored){lastCode="LEASE_FAILED_RESTORE_SCHEDULER_UNCONFIRMED";}
            return false;
        }
    }

    /** No timer/Looper scheduling or background worker is created by this class. */
    public String lastCode(){return lastCode;}
    public long queryCount(){return queryCount;}
    public long appliedCount(){return appliedCount;}
    public long releaseCount(){return releaseCount;}
    public boolean requested(){return requested;}
    public boolean closed(){return closed;}
    /** Same-Activity OEM overlays can obscure Home without changing Window focus. */
    public boolean homeIsTop(){
        if(closed||Looper.myLooper()!=Looper.getMainLooper())return false;
        try{guard();return baseIsTop();}catch(Exception error){return false;}
    }

    /** Restore normal timeout once on departure/stop; never reschedule an overlay. */
    @Override public void close(){
        if(closed)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){lastCode="CLOSE_REQUIRES_MAIN";return;}
        if(leased){lastCode="CLOSE_REENTRANT_REFUSED";return;}
        try {
            Object current=controller.get(base);
            require(current==home,"CONTROLLER_OWNERSHIP");
            if(requested&&managerHolder.get(viewManager)==holder&&baseIsTop()){
                invokeOriginal();releaseCount++;
            }
            lastCode="CLOSED_ORIGINAL_CONTROLLER";
        }catch(Exception error){lastCode="CLOSE_FAILED";}
        finally{closed=true;requested=false;effective=false;}
    }

    private void guard()throws Exception {
        requireMain();require(!activity.isFinishing()&&!activity.isDestroyed(),"ACTIVITY_GONE");
        require(method(home.getClass(),"getActivity").invoke(home)==activity
                &&method(home.getClass(),"getView").invoke(home)==homeView,"HOME_RECREATED");
        require(homeManager.get(home)==viewManager&&homePager.get(home)==pager
                &&managerHolder.get(viewManager)==holder&&managerHandler.get(viewManager)==handler,"BINDING_CHANGED");
        require(handler.getLooper()==Looper.getMainLooper(),"HANDLER_THREAD");
        require(holder.getChildCount()>=1&&holder.getChildCount()<=16&&holder.getChildAt(0)==base
                &&base.getParent()==holder&&controller.get(base)==home,"BASE_OWNERSHIP");
        require(homeView.getWindowToken()!=null&&pager.getWidth()==800&&pager.getHeight()==480,"VIEW_NOT_READY");
        View ancestor=homeView;int depth=0;
        while(ancestor!=base&&ancestor.getParent() instanceof View&&depth++<64)ancestor=(View)ancestor.getParent();
        require(ancestor==base,"HOME_OUTSIDE_BASE");
    }
    private boolean baseIsTop(){return holder.getChildCount()==1&&holder.getChildAt(0)==base;}
    private void invokeOriginal()throws Exception {
        require(controller.get(base)==home&&!leased,"ORIGINAL_CONTROLLER_REQUIRED");reschedule.invoke(viewManager);
    }
    private static Field field(Class<?> type,String name)throws Exception {
        for(Class<?> t=type;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);return f;}
        catch(NoSuchFieldException missing){}
        throw new IllegalStateException("OEM_FIELD_MISSING");
    }
    private static Method method(Class<?> type,String name)throws Exception {
        for(Class<?> t=type;t!=null;t=t.getSuperclass())try{Method m=t.getDeclaredMethod(name);m.setAccessible(true);return m;}
        catch(NoSuchMethodException missing){}
        return type.getMethod(name);
    }
    private static void requireMain(){require(Looper.myLooper()==Looper.getMainLooper(),"MAIN_THREAD_REQUIRED");}
    private static void require(boolean condition,String code){if(!condition)throw new IllegalStateException(code);}
}
