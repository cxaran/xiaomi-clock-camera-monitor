import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** LAB only: load the actual merged module DEX and call its unmodified start(uid). */
public final class JdiBridgeProbe {
    private static final String ACTIVITY = "com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity";
    private static final String BRIDGE = "org.x04g.bootstrap.StockBootstrap";
    private static final String BOOTSTRAP = "org.x04g.home.integration.Bootstrap";
    private static final String CAROUSEL = "org.x04g.home.integration.OemCarousel";
    private static final int SINGLE = ObjectReference.INVOKE_SINGLE_THREADED;
    private static final Map<String,Object> result = new LinkedHashMap<>();
    private static final List<ObjectReference> pinned = new ArrayList<>();
    private static VirtualMachine vm;
    private static BreakpointRequest request;
    private static EventSet held;
    private static String stage="arguments";
    private static ThreadReference main;
    private static ClassType bridge;
    private static ClassLoaderReference loader;
    private static final class Failure extends Exception { Failure(String code){super(code);} }

    public static void main(String[] argv) {
        boolean passed=false;
        try {
            Map<String,String> args=new HashMap<>();
            require(argv.length==8,"ARGUMENTS");
            for(int i=0;i<argv.length;i+=2)require(args.put(argv[i],argv[i+1])==null,"ARGUMENTS");
            require(args.keySet().equals(Set.of("--port","--uid","--dex","--dex-sha256")),"ARGUMENTS");
            require(args.get("--port").matches("[0-9]{4,5}"),"PORT");
            int port=Integer.parseInt(args.get("--port")),uid=Integer.parseInt(args.get("--uid"));
            require(port>=1024&&port<=65535&&uid>=10000&&uid<=19999,"PORT_OR_UID");
            Path dex=Path.of(args.get("--dex"));
            require(Files.isRegularFile(dex)&&!Files.isSymbolicLink(dex)&&Files.size(dex)>=112&&Files.size(dex)<=4*1024*1024,"DEX_FILE");
            byte[] payload=Files.readAllBytes(dex);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            require(hash.equals(args.get("--dex-sha256")),"DEX_HASH");
            result.put("payload_sha256",hash);result.put("payload_bytes",payload.length);
            attach(port);
            require(vm.classesByName(BRIDGE).isEmpty()&&vm.classesByName(BOOTSTRAP).isEmpty()
                    &&vm.classesByName(CAROUSEL).isEmpty(),"OWN_CLASSES_ALREADY_LOADED");
            List<ThreadReference> mains=new ArrayList<>();
            for(ThreadReference t:vm.allThreads())if("main".equals(t.name()))mains.add(t);
            require(mains.size()==1,"MAIN_NOT_UNIQUE");main=mains.get(0);
            arm("load");
            stage="load_actual_module_dex";
            ClassType cls=one("java.lang.Class");
            ClassType bufferType=resolve(cls,"java.nio.ByteBuffer",null);
            ClassType memoryType=resolve(cls,"dalvik.system.InMemoryDexClassLoader",null);
            ClassType cl=one("java.lang.ClassLoader");
            ObjectReference parent=object(cl.invokeMethod(main,method(cl,"getSystemClassLoader","()Ljava/lang/ClassLoader;"),List.of(),SINGLE));pin(parent);
            List<ReferenceType> arrays=vm.classesByName("byte[]");
            require(arrays.size()==1&&arrays.get(0) instanceof ArrayType,"BYTE_ARRAY_TYPE");
            ArrayReference bytes=((ArrayType)arrays.get(0)).newInstance(payload.length);pin(bytes);
            List<Value> values=new ArrayList<>(payload.length);
            for(byte b:payload)values.add(vm.mirrorOf(b));
            bytes.setValues(values);
            ObjectReference buffer=object(bufferType.invokeMethod(main,method(bufferType,"wrap","([B)Ljava/nio/ByteBuffer;"),List.of(bytes),SINGLE));pin(buffer);
            ObjectReference created=memoryType.newInstance(main,method(memoryType,"<init>","(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V"),List.of(buffer,parent),SINGLE);
            require(created instanceof ClassLoaderReference,"MEMORY_LOADER_TYPE");loader=(ClassLoaderReference)created;pin(loader);
            bridge=resolve(cls,BRIDGE,loader);
            require(bridge.classLoader().equals(loader),"BRIDGE_LOADER");
            ClassType pins=resolve(cls,"org.x04g.bootstrap.BuildPins",loader);
            result.put("pins_version_code",((LongValue)pins.getValue(field(pins,"OWN_VERSION_CODE"))).value());
            result.put("pins_version_name",((StringReference)pins.getValue(field(pins,"OWN_VERSION_NAME"))).value());
            result.put("pins_apk_sha256",((StringReference)pins.getValue(field(pins,"OWN_APK_SHA"))).value());
            stage="invoke_real_start";
            bridge.invokeMethod(main,method(bridge,"start","(I)V"),List.of(vm.mirrorOf(uid)),SINGLE);
            result.put("actual_start_called",true);result.put("loader_type",loader.referenceType().name());
            releaseEvent();
            stage="wait_real_worker";
            long deadline=System.nanoTime()+90_000_000_000L;
            while(System.nanoTime()<deadline){
                readFlags(uid);
                List<ReferenceType> bs=vm.classesByName(BOOTSTRAP);
                if(bs.size()==1&&Boolean.TRUE.equals(result.get("companionVerified"))){
                    Value code=bs.get(0).getValue(field(bs.get(0),"code"));
                    if(code instanceof StringReference&&"INSTALLED".equals(((StringReference)code).value()))break;
                }
                Thread.sleep(500);
            }
            arm("diagnostic");
            stage="read_real_diagnostics";
            ClassType bootstrap=one(BOOTSTRAP),carousel=one(CAROUSEL);
            result.put("same_loader_bridge_bootstrap",loader.equals(bootstrap.classLoader())&&loader.equals(carousel.classLoader()));
            Value diagnostic=bootstrap.invokeMethod(main,method(bootstrap,"diagnosticState","()Ljava/lang/String;"),List.of(),SINGLE);
            Value snapshot=carousel.invokeMethod(main,method(carousel,"diagnosticSnapshot","()Ljava/lang/String;"),List.of(),SINGLE);
            require(diagnostic instanceof StringReference&&snapshot instanceof StringReference,"DIAGNOSTIC_TYPES");
            result.put("bootstrap_diagnostic_json",((StringReference)diagnostic).value());
            result.put("carousel_diagnostic_json",((StringReference)snapshot).value());
            readFlags(uid);
            passed=Boolean.TRUE.equals(result.get("verified"))&&Boolean.TRUE.equals(result.get("oemIdentityVerified"))
                    &&Boolean.TRUE.equals(result.get("companionVerified"))&&Boolean.TRUE.equals(result.get("runtime_uid_matches"))
                    &&Boolean.TRUE.equals(result.get("same_loader_bridge_bootstrap"));
        }catch(Failure e){result.put("error",e.getMessage());}
        catch(InvocationException e){result.put("error","TARGET_EXCEPTION");result.put("exception_type",e.exception().referenceType().name());}
        catch(Exception e){result.put("error","PROBE_EXCEPTION");result.put("exception_type",e.getClass().getSimpleName());}
        finally{
            boolean cleaned=true;
            try{releaseEvent();}catch(Exception e){cleaned=false;}
            if(vm!=null){for(ObjectReference o:pinned)try{o.enableCollection();}catch(Exception ignored){}
                try{vm.dispose();}catch(Exception e){cleaned=false;}}
            result.put("stage",stage);result.put("debugger_cleanup_completed",cleaned);result.put("ok",passed&&cleaned);emit(result);
            System.exit(passed&&cleaned?0:1);
        }
    }
    private static void attach(int port)throws Exception{
        stage="attach";AttachingConnector c=Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(x->"com.sun.jdi.SocketAttach".equals(x.name())).findFirst().orElseThrow();
        Map<String,Connector.Argument>a=c.defaultArguments();a.get("hostname").setValue("127.0.0.1");a.get("port").setValue(Integer.toString(port));a.get("timeout").setValue("10000");vm=c.attach(a);
    }
    private static void arm(String action)throws Exception{
        stage="arm_"+action;require(main.suspendCount()==0,"MAIN_ALREADY_SUSPENDED");
        ClassType activity=one(ACTIVITY);Method dispatch=method(activity,"dispatchTouchEvent","(Landroid/view/MotionEvent;)Z");
        request=vm.eventRequestManager().createBreakpointRequest(dispatch.location());request.addThreadFilter(main);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.addCountFilter(1);request.enable();
        emit(Map.of("stage","armed","action",action));long end=System.nanoTime()+45_000_000_000L;
        while(System.nanoTime()<end){
            held=vm.eventQueue().remove(1000);if(held==null)continue;
            boolean found=false;for(Event e:held){if(e instanceof VMDeathEvent||e instanceof VMDisconnectEvent)throw new Failure("VM_DISCONNECTED");if(e instanceof BreakpointEvent&&e.request().equals(request))found=true;}
            if(found){require(main.suspendCount()==1,"SUSPEND_COUNT");vm.eventRequestManager().deleteEventRequest(request);request=null;return;}
            require(held.suspendPolicy()==EventRequest.SUSPEND_NONE,"UNEXPECTED_SUSPEND");held.resume();held=null;
        }
        throw new Failure("TOUCH_TIMEOUT");
    }
    private static void releaseEvent(){
        if(request!=null){vm.eventRequestManager().deleteEventRequest(request);request=null;}
        if(held!=null){held.resume();held=null;}
    }
    private static void readFlags(int uid)throws Exception{
        for(String f:List.of("verified","oemIdentityVerified","companionVerified"))result.put(f,((BooleanValue)bridge.getValue(field(bridge,f))).value());
        result.put("runtime_uid_matches",((IntegerValue)bridge.getValue(field(bridge,"runtimeExpectedUid"))).value()==uid);
    }
    private static ClassType resolve(ClassType cls,String name,ClassLoaderReference cl)throws Exception{
        Value v=cls.invokeMethod(main,method(cls,"forName","(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),Arrays.asList(vm.mirrorOf(name),vm.mirrorOf(true),cl),SINGLE);
        require(v instanceof ClassObjectReference,"CLASS_OBJECT");ReferenceType t=((ClassObjectReference)v).reflectedType();require(t instanceof ClassType,"CLASS_TYPE");return(ClassType)t;
    }
    private static ClassType one(String name)throws Failure{List<ReferenceType>x=vm.classesByName(name);require(x.size()==1&&x.get(0)instanceof ClassType,"CLASS_NOT_UNIQUE");return(ClassType)x.get(0);}
    private static Method method(ReferenceType t,String n,String sig)throws Failure{List<Method>x=t.methodsByName(n,sig);require(x.size()==1,"METHOD_NOT_UNIQUE");return x.get(0);}
    private static Field field(ReferenceType t,String n)throws Failure{Field f=t.fieldByName(n);require(f!=null,"FIELD_MISSING");return f;}
    private static ObjectReference object(Value v)throws Failure{require(v instanceof ObjectReference,"OBJECT_REQUIRED");return(ObjectReference)v;}
    private static void pin(ObjectReference o){o.disableCollection();pinned.add(o);}
    private static void require(boolean b,String c)throws Failure{if(!b)throw new Failure(c);}
    private static String quote(Object v){String s=String.valueOf(v);return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    private static void emit(Map<String,?>m){StringJoiner j=new StringJoiner(",","{","}");for(var e:m.entrySet()){Object v=e.getValue();j.add(quote(e.getKey())+":"+(v instanceof Number||v instanceof Boolean?v:quote(v)));}System.out.println(j);System.out.flush();}
}
