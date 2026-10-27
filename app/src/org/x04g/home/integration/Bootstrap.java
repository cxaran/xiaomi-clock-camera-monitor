package org.x04g.home.integration;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;

/** Deferred lifecycle registration for a narrowly targeted Zygisk loader or explicit lab invocation. */
public final class Bootstrap {
    private static final String PACKAGE="com.google.assistant.launcher",SOURCE="/product/app/com.google.assistant.launcher/app.apk";
    private static final String SHA="1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21";
    private static final Object LOCK=new Object();private static boolean starting,rebindQueued;private static volatile boolean registered,verified,enabled=true;private static Handler main;private static WeakReference<Activity> candidate=new WeakReference<Activity>(null);private static volatile String code="NOT_STARTED";
    private static volatile String errorFields="\"error_type\":null,\"error_code\":null";
    private Bootstrap(){}
    /** Only fixed local codes and fixed Java exception types may cross the bridge. Never inspect causes. */
    private static void recordFailure(String stage,Exception failure){
        String type=safeErrorType(failure),message="OtherException".equals(type)?null:failure.getMessage();
        String safe=ownErrorCode(message);
        if(safe==null){switch(stage){case "verify_failed":case "register_failed":case "retry_failed":case "find_activity_failed":safe=stage;break;default:safe="diagnostic_failed";}}
        errorFields="\"error_type\":\""+type+"\",\"error_code\":\""+safe+"\"";
    }
    private static void clearFailure(){errorFields="\"error_type\":null,\"error_code\":null";}
    private static String safeErrorType(Exception failure){
        if(failure==null)return "OtherException";
        switch(failure.getClass().getName()){
            case "java.lang.Exception":return "Exception";
            case "java.lang.RuntimeException":return "RuntimeException";
            case "java.lang.IllegalArgumentException":return "IllegalArgumentException";
            case "java.lang.IllegalStateException":return "IllegalStateException";
            case "java.lang.NullPointerException":return "NullPointerException";
            case "java.lang.ClassCastException":return "ClassCastException";
            case "java.lang.SecurityException":return "SecurityException";
            case "java.lang.ClassNotFoundException":return "ClassNotFoundException";
            case "java.lang.NoSuchMethodException":return "NoSuchMethodException";
            case "java.lang.NoSuchFieldException":return "NoSuchFieldException";
            case "java.lang.IllegalAccessException":return "IllegalAccessException";
            case "java.lang.reflect.InvocationTargetException":return "InvocationTargetException";
            case "java.io.IOException":return "IOException";
            case "java.io.FileNotFoundException":return "FileNotFoundException";
            case "java.security.NoSuchAlgorithmException":return "NoSuchAlgorithmException";
            default:return "OtherException";
        }
    }
    private static String ownErrorCode(String value){
        if(value==null)return null;
        switch(value){
            case "active_source_changed":case "launcher_version_changed":case "companion_rejected":case "activity_rejected":case "install_not_active":
            case "main_thread_required":case "other_activity_active":case "previous_cleanup_incomplete":case "rebuild_fault":case "install_failure":
            case "observer_missing":case "order_failed":case "selection_failed":case "visibility_failed":case "activity_mismatch":case "home_not_ready":
            case "foreign_home":case "home_not_attached":case "oem_shape_changed":case "pager_not_ready":case "owner_mismatch":case "fragment_state_unavailable":
            case "collections_changed":case "id_collision":case "model_shape_changed":case "model_class_changed":case "model_id_collision":case "clock_not_unique":
            case "ordering_changed":case "tile_block_unavailable":case "fragment_tree_bound":case "support_manager_changed":case "support_store_changed":case "fragment_list_bound":
            case "duplicate_own":case "clock_missing":case "own_model_missing":case "ownership_lost":case "priority_failed":case "observer_ownership_lost":
            case "home_transition_pending":case "replacement_binding_changed":case "replacement_own_models_remain":case "cleanup_incomplete":case "oem_field_missing":case "oem_method_missing":return value;
            default:return null;
        }
    }
    public static String diagnosticState(){return "{\"state\":\""+state()+"\","+errorFields+"}";}
    /** May be called before Looper/Application exist. No disk/network operation on the zygote thread. */
    public static void start(){synchronized(LOCK){if(starting||registered)return;starting=true;}Thread t=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);try{Application app=null;for(int i=0;i<240;i++){Object current=Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null);if(current instanceof Application&&Looper.getMainLooper()!=null){app=(Application)current;break;}Thread.sleep(250);}if(app==null){code="APPLICATION_NOT_READY";return;}verify(app);final Application ready=app;main=new Handler(Looper.getMainLooper());main.post(()->register(ready));}catch(Exception e){code="BOOTSTRAP_REJECTED";}finally{synchronized(LOCK){starting=false;}}},"X04G-Bootstrap");t.start();}
    public static void attach(Activity a){if(a==null||!OemCarousel.ACTIVITY.equals(a.getClass().getName()))return;enabled=true;candidate=new WeakReference<Activity>(a);if(verified&&registered){main.post(()->retry(a,0));return;}synchronized(LOCK){if(starting)return;starting=true;}new Thread(()->{try{verify(a.getApplication());main=new Handler(Looper.getMainLooper());main.post(()->{register(a.getApplication());retry(a,0);});}catch(Exception e){code="STOCK_VERSION_REJECTED";}finally{synchronized(LOCK){starting=false;}}},"X04G-Verify").start();}
    /** Explicit removal remains disabled across resume/recreation until attach/install. */
    static void disable(Activity a){if(a!=null&&OemCarousel.ACTIVITY.equals(a.getClass().getName())&&(candidate.get()==a||candidate.get()==null)){enabled=false;candidate.clear();code="EXPLICITLY_DISABLED";}}
    /** Event-driven request; retry is bounded and never runs a permanent polling loop. */
    static void rebind(Activity a){if(main==null||!enabled||candidate.get()!=a)return;synchronized(LOCK){if(rebindQueued)return;rebindQueued=true;}main.post(()->{synchronized(LOCK){rebindQueued=false;}retry(a,0);});}
    private static void verify(Application app)throws Exception{try{ApplicationInfo active=app.getPackageManager().getApplicationInfo(PACKAGE,0);if(!SOURCE.equals(active.sourceDir))throw new Exception("active_source_changed");MessageDigest md=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(active.sourceDir)){byte[] b=new byte[32768];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:md.digest())out.append(String.format(java.util.Locale.US,"%02x",b&255));if(!SHA.equals(out.toString()))throw new Exception("launcher_version_changed");try{Class<?> helper=Class.forName("org.x04g.bootstrap.StockBootstrap",false,Bootstrap.class.getClassLoader());if(!Boolean.TRUE.equals(helper.getMethod("companionIsStock",Context.class).invoke(null,app)))throw new Exception("companion_rejected");}catch(ClassNotFoundException labOnly){/* Explicit host-pinned lab DexClassLoader has no persistent bridge. */}verified=true;code="STOCK_VERIFIED";clearFailure();}catch(Exception e){recordFailure("verify_failed",e);throw e;}}
    private static void register(Application app){try{if(registered)return;registered=true;app.registerComponentCallbacks(new ComponentCallbacks2(){public void onConfigurationChanged(Configuration ignored){}public void onLowMemory(){OemCarousel.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);}public void onTrimMemory(int level){OemCarousel.onTrimMemory(level);}});app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){if(enabled&&OemCarousel.ACTIVITY.equals(a.getClass().getName())){candidate=new WeakReference<Activity>(a);retry(a,0);OemCarousel.resume(a,true);}}public void onActivityPaused(Activity a){OemCarousel.resume(a,false);}public void onActivityStopped(Activity a){OemCarousel.resume(a,false);}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityPreSaveInstanceState(Activity a,Bundle b){OemCarousel.teardown(a);}public void onActivityPreDestroyed(Activity a){OemCarousel.teardown(a);}public void onActivityDestroyed(Activity a){OemCarousel.teardown(a);if(candidate.get()==a)candidate.clear();}});if(!enabled)return;Activity a=candidate.get();if(a!=null)retry(a,0);else findAlreadyResumed(0);}catch(RuntimeException e){recordFailure("register_failed",e);throw e;}}
    /** Read only, on main: lifecycle registration can occur after the first resume. */
    private static void findAlreadyResumed(int attempt){if(!enabled)return;try{Class<?> thread=Class.forName("android.app.ActivityThread");Object current=thread.getDeclaredMethod("currentActivityThread").invoke(null);java.lang.reflect.Field records=thread.getDeclaredField("mActivities");records.setAccessible(true);Object map=records.get(current);if(map instanceof java.util.Map){for(Object record:((java.util.Map<?,?>)map).values()){java.lang.reflect.Field f=record.getClass().getDeclaredField("activity");f.setAccessible(true);Object value=f.get(record);if(value instanceof Activity&&OemCarousel.ACTIVITY.equals(value.getClass().getName())){Activity a=(Activity)value;if(!a.isFinishing()&&!a.isDestroyed()){candidate=new WeakReference<Activity>(a);retry(a,0);return;}}}}}catch(Exception e){recordFailure("find_activity_failed",e);code="WAITING_FOR_ACTIVITY_CALLBACK";}if(attempt<120&&candidate.get()==null)main.postDelayed(()->findAlreadyResumed(attempt+1),500);}
    private static void retry(Activity a,int attempt){if(main==null||!enabled||!verified||a.isDestroyed()||a.isFinishing()||candidate.get()!=a)return;try{try{Class<?> helper=Class.forName("org.x04g.bootstrap.StockBootstrap",false,Bootstrap.class.getClassLoader());if(!Boolean.TRUE.equals(helper.getMethod("activityIsStock",Activity.class).invoke(null,a)))throw new Exception("activity_rejected");}catch(ClassNotFoundException labOnly){}if(!OemCarousel.installedFor(a))OemCarousel.installVerified(a);if(!OemCarousel.installedFor(a))throw new Exception("install_not_active");code="INSTALLED";clearFailure();}catch(Exception e){recordFailure("retry_failed",e);code="WAITING_FOR_ORIGINAL_HOME";if(attempt<120)main.postDelayed(()->retry(a,attempt+1),500);else code="ORIGINAL_HOME_UNAVAILABLE";}}
    public static String state(){return enabled?code:"EXPLICITLY_DISABLED";}
}
