import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/** Host-only explicit JDI operator. Never launches an Android process or changes APKs. */
public final class JdiHomeSelect {
    private static final String ACTIVITY = "com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity";
    private static final String PROBE = "org.x04g.home.integration.OemCarousel";
    private static final String OWN_ID = "x04g.home.gate.v1";
    private static final String CAMERA_ID = "x04g.home.cameras.v1";
    private static final int SINGLE = ObjectReference.INVOKE_SINGLE_THREADED;
    private static final long EVENT_TIMEOUT_MS = 75_000;
    private static String stage = "arguments";
    private static final Map<String, Object> result = new LinkedHashMap<>();
    private static VirtualMachine vm;
    private static BreakpointRequest request;
    private static EventSet held;
    private static final List<ObjectReference> pinned = new ArrayList<>();

    private static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        Failure(String code) { super(code); }
    }

    public static void main(String[] argv) {
        int exit = 1;
        try {
            Map<String, String> args = arguments(argv);
            String mode = args.get("mode");
            result.put("mode", mode);
            attach(args.get("host"), args.get("port"));
            stage = "metadata";
            result.put("runtime_version", bounded(vm.version(), 160));
            result.put("can_be_modified", vm.canBeModified());
            List<ReferenceType> activities = vm.classesByName(ACTIVITY);
            List<ThreadReference> mains = new ArrayList<>();
            for (ThreadReference t : vm.allThreads()) if ("main".equals(t.name())) mains.add(t);
            result.put("activity_class_count", activities.size());
            result.put("main_thread_count", mains.size());
            result.put("probe_class_count", vm.classesByName(PROBE).size());
            require(activities.size() == 1, "ACTIVITY_CLASS_NOT_UNIQUE");
            require(mains.size() == 1, "MAIN_THREAD_NOT_UNIQUE");
            ReferenceType original = activities.get(0);
            require(original.isPrepared(), "ACTIVITY_NOT_PREPARED");
            Method dispatch = method(original, "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z");
            require(!dispatch.isNative() && !dispatch.isAbstract(), "DISPATCH_NOT_JAVA");
            Location location = dispatch.location();
            require(location != null && location.codeIndex() >= 0, "DISPATCH_LOCATION_UNAVAILABLE");
            result.put("dispatch_code_index", location.codeIndex());
            result.put("activity_class_object_id", Long.toUnsignedString(original.classObject().uniqueID()));
            require(vm.canBeModified(), "VM_READ_ONLY");
            result.put("apk_sha256_expected", args.get("apk-sha256"));
            result.put("apk_hash_verification", "CALLER_REQUIRED_NOT_PERFORMED_BY_JDI");
            mutate(mode, args.get("apk"), original, mains.get(0), location);
            exit = 0;
        } catch (Failure e) {
            result.put("error", e.getMessage());
        } catch (ClassNotLoadedException e) {
            result.put("error", "CLASS_NOT_LOADED");
            String name = e.className();
            Set<String> known = Set.of("android.app.Activity", "android.view.MotionEvent",
                    "java.lang.Class", "java.lang.String", "java.lang.ClassLoader",
                    "dalvik.system.DexClassLoader", PROBE);
            result.put("class_not_loaded", known.contains(name) ? name : "UNLISTED_CLASS");
        } catch (InvocationException e) {
            result.put("error", "TARGET_EXCEPTION");
            try { result.put("target_exception_type", bounded(e.exception().referenceType().name(), 180)); }
            catch (Exception ignored) { result.put("target_exception_type", "UNAVAILABLE"); }
        } catch (Exception e) {
            result.put("error", "JDI_FAILURE");
            result.put("exception_class", e.getClass().getSimpleName());
        } finally {
            result.put("last_stage", stage);
            boolean cleanup = true;
            if (vm != null) {
                if (request != null) {
                    try { vm.eventRequestManager().deleteEventRequest(request); }
                    catch (VMDisconnectedException ignored) { }
                    catch (RuntimeException e) { cleanup = false; }
                    request = null;
                }
                for (ObjectReference object : pinned) {
                    try { object.enableCollection(); }
                    catch (VMDisconnectedException ignored) { }
                    catch (RuntimeException e) { cleanup = false; }
                }
                if (held != null) {
                    try { held.resume(); }
                    catch (VMDisconnectedException ignored) { }
                    catch (RuntimeException e) { cleanup = false; }
                    held = null;
                }
                try { vm.dispose(); }
                catch (VMDisconnectedException ignored) { }
                catch (RuntimeException e) { cleanup = false; }
            }
            result.put("cleanup_commands_completed", cleanup);
            result.put("ok", exit == 0 && cleanup);
            emit(result);
            if (!cleanup) exit = 1;
        }
        System.exit(exit);
    }

    private static Map<String, String> arguments(String[] argv) throws Failure {
        Map<String, String> args = new HashMap<>();
        require(argv.length % 2 == 0, "ARGUMENTS");
        Set<String> allowed = Set.of("mode", "host", "port", "apk", "apk-sha256");
        for (int i = 0; i < argv.length; i += 2) {
            require(argv[i].startsWith("--"), "ARGUMENTS");
            String key = argv[i].substring(2);
            require(allowed.contains(key) && args.put(key, argv[i + 1]) == null, "ARGUMENTS");
        }
        require(Set.of("selectgate", "selectcamera", "selectclock", "status").contains(args.getOrDefault("mode", "")), "MODE");
        require("127.0.0.1".equals(args.get("host")), "HOST_NOT_LOOPBACK");
        String port = args.getOrDefault("port", "");
        require(port.matches("[0-9]{4,5}"), "PORT");
        int number = Integer.parseInt(port);
        require(number >= 1024 && number <= 65535, "PORT");
        if (!"probe".equals(args.get("mode"))) {
            String apk = args.getOrDefault("apk", "");
            require(apk.matches("/data/app/org[.]x04g[.]home-[A-Za-z0-9_=+~-]+/base[.]apk"), "APK_PATH");
            require(args.getOrDefault("apk-sha256", "").matches("[a-f0-9]{64}"), "APK_PIN");
        }
        return args;
    }

    private static void attach(String host, String port) throws Exception {
        stage = "attach";
        AttachingConnector chosen = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors())
            if ("com.sun.jdi.SocketAttach".equals(c.name())) chosen = c;
        require(chosen != null, "SOCKET_CONNECTOR_UNAVAILABLE");
        Map<String, Connector.Argument> args = chosen.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(port);
        args.get("timeout").setValue("10000");
        vm = chosen.attach(args);
    }

    private static void mutate(String mode, String apk, ReferenceType original,
                               ThreadReference main, Location location) throws Exception {
        stage = "arm";
        require(main.suspendCount() == 0, "MAIN_ALREADY_SUSPENDED");
        request = vm.eventRequestManager().createBreakpointRequest(location);
        request.addThreadFilter(main);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.addCountFilter(1); // Count LAST: another thread must not consume this one-off.
        request.enable();
        emit(Map.of("stage", "armed", "mode", mode, "event_timeout_seconds", 75,
                    "event", "original_dispatchTouchEvent", "suspend_policy", "EVENT_THREAD"));
        stage = "wait_touch";
        long deadline = System.nanoTime() + EVENT_TIMEOUT_MS * 1_000_000;
        BreakpointEvent hit = null;
        while (hit == null) {
            long left = deadline - System.nanoTime();
            require(left > 0, "TOUCH_EVENT_TIMEOUT");
            EventSet set = vm.eventQueue().remove(Math.max(1, left / 1_000_000));
            held = set; // Own every event set before validation so finally can resume it.
            require(set != null, "TOUCH_EVENT_TIMEOUT");
            for (Event event : set) {
                if (event instanceof VMDisconnectEvent || event instanceof VMDeathEvent)
                    throw new Failure("VM_DISCONNECTED");
                if (event instanceof BreakpointEvent && event.request().equals(request)) {
                    require(hit == null, "MULTIPLE_BREAKPOINT_EVENTS");
                    hit = (BreakpointEvent) event;
                }
            }
            if (hit == null) {
                // Only automatic non-suspending startup/death events are expected besides ours.
                require(set.suspendPolicy() == EventRequest.SUSPEND_NONE, "UNEXPECTED_SUSPENDING_EVENT");
                set.resume(); held = null;
            } else held = set;
        }
        stage = "event_validate";
        require(held.suspendPolicy() == EventRequest.SUSPEND_EVENT_THREAD, "EVENT_POLICY");
        require(hit.thread().equals(main) && hit.location().equals(location), "EVENT_IDENTITY");
        require(main.suspendCount() == 1, "MAIN_SUSPEND_COUNT");
        vm.eventRequestManager().deleteEventRequest(request);
        request = null; // Clear before any invoke, avoiding a nested breakpoint.
        ObjectReference activity = main.frame(0).thisObject();
        require(activity != null && activity.referenceType().equals(original)
                && ACTIVITY.equals(activity.referenceType().name()), "ACTIVITY_INSTANCE");
        pin(activity);
        ClassLoaderReference parent = original.classLoader();
        require(parent != null, "ORIGINAL_LOADER_MISSING");
        pin(parent);
        result.put("touch_event_received", true);
        result.put("activity_exact", true);
        stage = "existing_carousel";
        ClassType installer = existingInstaller(parent, apk);
        require(installer != null, "CAROUSEL_NOT_INSTALLED");
        resolveInstallerContract(installer, main);
        Scope scope = scope(installer, activity, main);
        diagnostics(activity, scope, main);
        int before = selectedIndex(scope.pager);
        result.put("selected_index_before", before);
        installer.invokeMethod(main, method(installer,"status","(Landroid/app/Activity;)V"),Collections.singletonList(activity),SINGLE);
        result.put("snapshot_before_json", snapshot(installer,main));
        if (!"status".equals(mode)) {
            int target = "selectgate".equals(mode) ? scope.gateIndex : "selectcamera".equals(mode) ? scope.cameraIndex : scope.clockIndex;
            require(target >= 0, "SELECT_TARGET_MISSING");
            stage = "select_original_pager";
            scope.pager.invokeMethod(main, method(scope.pager.referenceType(),"i","(IZ)V"),
                                     Arrays.asList(vm.mirrorOf(target),vm.mirrorOf(false)),SINGLE);
            Scope after = scope(installer,activity,main);
            require(scope.session.equals(after.session) && scope.models.equals(after.models)
                    && scope.pager.equals(after.pager) && scope.adapter.equals(after.adapter),"SELECT_BINDING_CHANGED");
            require(after.gateIndex==scope.gateIndex && after.cameraIndex==scope.cameraIndex
                    && after.clockIndex==scope.clockIndex,"SELECT_ORDER_CHANGED");
            require(selectedIndex(after.pager)==target,"SELECT_NOT_APPLIED");
            result.put("requested_index",target);
            result.put("selection_method","original_TileViewPager_i_nonanimated");
            result.put("selection_was_native_gesture",false);
        }
        stage = "snapshot";
        installer.invokeMethod(main,method(installer,"status","(Landroid/app/Activity;)V"),Collections.singletonList(activity),SINGLE);
        scope(installer,activity,main); // Status may discover a lifecycle fault: never hide it.
        result.put("selected_index_after",selectedIndex(scope.pager));
        result.put("snapshot_json",snapshot(installer,main));
        result.put("phase","status".equals(mode)?"status_returned":"programmatic_selection_returned");
        result.put("visible_card_verified",false);
        result.put("source_or_apk_changed",false);
    }

    private static final class Scope {
        ObjectReference session,home,pager,adapter,registry,models;
        int clockIndex=-1,gateIndex=-1,cameraIndex=-1;
    }

    private static Scope scope(ClassType installer,ObjectReference activity,ThreadReference main)throws Exception {
        Scope s=new Scope();
        s.session=object(installer.getValue(field(installer,"current")),"NO_CURRENT_SESSION");
        require((PROBE+"$Session").equals(s.session.referenceType().name()),"SESSION_TYPE");pin(s.session);
        require(activity.equals(value(s.session,"activity")),"SESSION_ACTIVITY");
        require(bool(value(s.session,"active")),"SESSION_NOT_ACTIVE");
        require(!bool(value(s.session,"cleaned")) && value(s.session,"fault")==null,"SESSION_FAULT_OR_CLEANED");
        s.home=object(value(s.session,"home"),"HOME");s.pager=object(value(s.session,"pager"),"PAGER");
        s.adapter=object(value(s.session,"adapter"),"ADAPTER");s.registry=object(value(s.session,"registry"),"REGISTRY");
        require("cat".equals(s.home.referenceType().name()) && "zb".equals(s.adapter.referenceType().name()),"HOME_ADAPTER_TYPE");
        require("com.google.assistant.surfaces.clock.home.TileViewPager".equals(s.pager.referenceType().name()),"PAGER_TYPE");
        require("com.google.assistant.surfaces.clock.castshell.CastWindowFragmentRegistry".equals(s.registry.referenceType().name()),"REGISTRY_TYPE");
        require(s.adapter.equals(value(s.home,"B")) && s.pager.equals(value(s.home,"y")) && s.registry.equals(value(s.home,"b")),"HOME_BINDING");
        require(s.adapter.equals(value(s.pager,"b")) && s.registry.equals(value(s.adapter,"f"))
                && s.registry.equals(value(activity,"q")),"PAGER_REGISTRY_BINDING");
        ObjectReference wrapper=object(value(s.session,"wrapper"),"OBSERVER_WRAPPER");
        require(wrapper.equals(value(s.adapter,"b")),"OBSERVER_CHANGED");
        require(value(s.session,"original") instanceof ObjectReference,"ORIGINAL_OBSERVER_MISSING");
        require(object(value(s.session,"manager"),"MANAGER").equals(value(s.registry,"d")),"MANAGER_BINDING");
        s.models=object(value(s.session,"models"),"MODELS");
        require(s.models.equals(value(s.adapter,"j")),"MODELS_BINDING");
        ObjectReference ids=object(value(s.session,"ids"),"IDS");
        require(ids.equals(value(s.registry,"g")),"IDS_BINDING");
        ObjectReference fragments=object(value(s.session,"fragments"),"FRAGMENTS");
        require(fragments.equals(value(s.registry,"f")),"FRAGMENTS_BINDING");
        require(object(value(s.session,"containers"),"CONTAINERS").equals(value(s.registry,"n")),"CONTAINERS_BINDING");
        require(value(s.session,"ownedIds") instanceof ArrayReference && value(s.session,"pages") instanceof ArrayReference,"OWN_ARRAYS");
        ArrayReference owned=(ArrayReference)value(s.session,"ownedIds"),pages=(ArrayReference)value(s.session,"pages");
        require(owned.length()==2&&pages.length()==2,"OWN_ARRAY_COUNT");
        String[] names={OWN_ID,CAMERA_ID};
        for(int i=0;i<2;i++) {
            Value id=owned.getValue(i);require(id instanceof StringReference&&names[i].equals(((StringReference)id).value()),"OWN_ID_REFERENCE");
            require(pages.getValue(i) instanceof ObjectReference,"OWN_PAGE_REFERENCE");
            Value mapped=fragments.invokeMethod(main,method(fragments.referenceType(),"get","(Ljava/lang/Object;)Ljava/lang/Object;"),Collections.singletonList(id),SINGLE);
            require(pages.getValue(i).equals(mapped),"OWN_FRAGMENT_BINDING");
            int matches=0,count=listSize(ids,main);
            for(int j=0;j<count;j++)if(id.equals(listGet(ids,j,main)))matches++;
            require(matches==1,"OWN_ID_MEMBERSHIP");
        }
        int count=listSize(s.models,main);
        for(int i=0;i<count;i++) {
            ObjectReference model=object(listGet(s.models,i,main),"MODEL");
            require("cbg".equals(model.referenceType().name()),"MODEL_TYPE");
            int type=integer(value(model,"a"));Value data=value(model,"b");
            if(type==0){require(s.clockIndex<0 && data==null,"CLOCK_NOT_UNIQUE");s.clockIndex=i;}
            if(type==8)for(int n=0;n<2;n++)if(owned.getValue(n).equals(data)) {
                if(n==0){require(s.gateIndex<0,"DUPLICATE_GATE");s.gateIndex=i;}
                else{require(s.cameraIndex<0,"DUPLICATE_CAMERA");s.cameraIndex=i;}
            }
        }
        require(s.clockIndex>=0 && s.gateIndex==s.clockIndex+1 && s.cameraIndex==s.clockIndex+2,"OWN_PRIORITY_CHANGED");
        int selected=selectedIndex(s.pager);require(selected>=0&&selected<count,"SELECTED_INDEX_RANGE");
        result.put("binding_preflight",true);result.put("model_count",count);
        result.put("clock_index",s.clockIndex);result.put("gate_index",s.gateIndex);result.put("camera_index",s.cameraIndex);
        result.put("session_resumed",bool(value(s.session,"resumed")));
        return s;
    }

    private static void diagnostics(ObjectReference activity,Scope scope,ThreadReference main)throws Exception {
        // Only public/static-shape booleans; never inspect alarm IDs, names or schedules.
        ObjectReference pager=scope.pager;
        ObjectReference operation=object(value(activity,"n"),"DEVICE_OPERATION");
        require("com.google.assistant.surfaces.clock.common.DeviceOperationManager".equals(operation.referenceType().name()),"DEVICE_OPERATION_TYPE");
        result.put("oem_screen_off_consumes_touch",bool(value(operation,"c")));
        ObjectReference alarm=object(value(pager,"h"),"ALARM_MANAGER");
        require("bus".equals(alarm.referenceType().name()),"ALARM_MANAGER_TYPE");
        result.put("oem_alarm_blocks_pager_touch",value(alarm,"g")!=null);
        result.put("pager_scroll_state",integer(value(pager,"S")));
        result.put("pager_dragging",bool(value(pager,"w")));
        result.put("pager_unable_to_drag",bool(value(pager,"x")));
        result.put("activity_window_focus",bool(activity.invokeMethod(main,
                method(activity.referenceType(),"hasWindowFocus","()Z"),Collections.emptyList(),SINGLE)));
        result.put("pager_is_shown",bool(pager.invokeMethod(main,
                method(pager.referenceType(),"isShown","()Z"),Collections.emptyList(),SINGLE)));
        ObjectReference lease=object(value(scope.session,"idleLease"),"IDLE_LEASE");
        result.put("lease_home_is_top",bool(lease.invokeMethod(main,
                method(lease.referenceType(),"homeIsTop","()Z"),Collections.emptyList(),SINGLE)));
        ObjectReference manager=object(value(scope.home,"h"),"VIEW_MANAGER");
        ObjectReference holder=object(value(manager,"c"),"VIEW_HOLDER");
        int children=integer(holder.invokeMethod(main,method(holder.referenceType(),"getChildCount","()I"),Collections.emptyList(),SINGLE));
        result.put("view_holder_child_count",children);
        if(children>0&&children<=16){
            ObjectReference top=object(holder.invokeMethod(main,method(holder.referenceType(),"getChildAt","(I)Landroid/view/View;"),
                    Collections.singletonList(vm.mirrorOf(children-1)),SINGLE),"TOP_CONTAINER");
            ObjectReference owner=object(value(top,"d"),"TOP_CONTROLLER");
            result.put("top_controller_class",owner.referenceType().name());
        }
    }
    private static Value value(ObjectReference object,String name)throws Failure{return object.getValue(field(object.referenceType(),name));}
    private static boolean bool(Value v)throws Failure{require(v instanceof BooleanValue,"BOOLEAN_FIELD");return((BooleanValue)v).value();}
    private static int integer(Value v)throws Failure{require(v instanceof IntegerValue,"INTEGER_FIELD");return((IntegerValue)v).value();}
    private static int listSize(ObjectReference list,ThreadReference main)throws Exception{
        require("java.util.ArrayList".equals(list.referenceType().name()),"LIST_TYPE");
        int n=integer(list.invokeMethod(main,method(list.referenceType(),"size","()I"),Collections.emptyList(),SINGLE));
        require(n>=0&&n<=64,"LIST_COUNT_CAP");return n;
    }
    private static Value listGet(ObjectReference list,int i,ThreadReference main)throws Exception{
        return list.invokeMethod(main,method(list.referenceType(),"get","(I)Ljava/lang/Object;"),Collections.singletonList(vm.mirrorOf(i)),SINGLE);
    }
    private static int selectedIndex(ObjectReference pager)throws Failure{return integer(value(pager,"c"));}
    private static String snapshot(ClassType installer, ThreadReference main) throws Exception {
        Value value = installer.invokeMethod(main, method(installer, "snapshot", "()Ljava/lang/String;"),
                                              Collections.emptyList(), SINGLE);
        require(value instanceof StringReference, "SNAPSHOT_NOT_STRING");
        String snapshot = ((StringReference) value).value();
        require(snapshot.length() <= 16_384 && snapshot.startsWith("{") && snapshot.endsWith("}"), "SNAPSHOT_FORMAT");
        return snapshot;
    }

    private static void resolveInstallerContract(ClassType installer, ThreadReference main) throws Exception {
        // A loaded boot class is not necessarily recorded as visible to this new initiating loader.
        // JDI Method.argumentTypes() validates arguments through that loader before invoking Java.
        ClassLoaderReference loader = installer.classLoader();
        require(loader != null, "INSTALLER_LOADER_MISSING");
        ClassType bootActivity = oneClass("android.app.Activity");
        require(bootActivity.classLoader() == null, "ACTIVITY_NOT_BOOT_CLASS");
        ClassType classType = oneClass("java.lang.Class");
        Value reflected = classType.invokeMethod(main,
                method(classType, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),
                Arrays.asList(vm.mirrorOf("android.app.Activity"), vm.mirrorOf(false), loader), SINGLE);
        require(reflected instanceof ClassObjectReference
                && bootActivity.equals(((ClassObjectReference) reflected).reflectedType()), "ACTIVITY_PARAMETER_IDENTITY");
        for (String entry : List.of("status")) {
            Method target = method(installer, entry, "(Landroid/app/Activity;)V");
            List<Type> types = target.argumentTypes();
            require(target.isStatic() && types.size() == 1 && bootActivity.equals(types.get(0)), "INSTALLER_PARAMETER_CONTRACT");
        }
        result.put("activity_parameter_resolved", true);
    }

    private static ClassType existingInstaller(ClassLoaderReference parent, String apk) throws Exception {
        List<ReferenceType> found = vm.classesByName(PROBE);
        require(found.size() <= 1, "INSTALLER_CLASS_NOT_UNIQUE");
        if (found.isEmpty()) return null;
        ReferenceType type = found.get(0);
        require(type instanceof ClassType && type.isPrepared(), "INSTALLER_NOT_PREPARED");
        ClassLoaderReference loader = type.classLoader();
        validateLoader(loader, parent, apk);
        Value ids = type.getValue(field(type, "IDS"));
        require(ids instanceof ArrayReference && ((ArrayReference)ids).length()==2, "INSTALLER_IDS");
        Value id=((ArrayReference)ids).getValue(0);
        require(id instanceof StringReference && OWN_ID.equals(((StringReference)id).value()), "INSTALLER_ID");
        Value camera=((ArrayReference)ids).getValue(1);
        require(camera instanceof StringReference && "x04g.home.cameras.v1".equals(((StringReference)camera).value()), "INSTALLER_CAMERA_ID");
        pin(loader);
        return (ClassType) type;
    }

    private static void validateLoader(ClassLoaderReference loader, ClassLoaderReference parent, String apk)
            throws Exception {
        require(loader != null && "dalvik.system.DexClassLoader".equals(loader.referenceType().name()), "INSTALLER_LOADER_TYPE");
        require(parent.equals(loader.getValue(field(loader.referenceType(), "parent"))), "INSTALLER_PARENT");
        ObjectReference pathList = object(loader.getValue(field(loader.referenceType(), "pathList")), "DEX_PATH_LIST");
        Value elementsValue = pathList.getValue(field(pathList.referenceType(), "dexElements"));
        require(elementsValue instanceof ArrayReference, "DEX_ELEMENTS");
        ArrayReference elements = (ArrayReference) elementsValue;
        require(elements.length() == 1, "DEX_ELEMENT_COUNT");
        ObjectReference element = object(elements.getValue(0), "DEX_ELEMENT");
        ObjectReference dexFile = object(element.getValue(field(element.referenceType(), "dexFile")), "DEX_FILE");
        Value name = dexFile.getValue(field(dexFile.referenceType(), "mFileName"));
        require(name instanceof StringReference && apk.equals(((StringReference) name).value()), "DEX_SOURCE_PATH_MISMATCH");
    }

    private static ClassType oneClass(String name) throws Failure {
        List<ReferenceType> classes = vm.classesByName(name);
        require(classes.size() == 1 && classes.get(0) instanceof ClassType, "BOOT_CLASS_NOT_UNIQUE");
        return (ClassType) classes.get(0);
    }
    private static Method method(ReferenceType type, String name, String signature) throws Failure {
        List<Method> methods = type.methodsByName(name, signature);
        require(methods.size() == 1, "METHOD_NOT_UNIQUE");
        return methods.get(0);
    }
    private static Field field(ReferenceType type, String name) throws Failure {
        for (ReferenceType t = type; t != null; t = t instanceof ClassType ? ((ClassType) t).superclass() : null)
            for (Field f : t.fields()) if (f.name().equals(name)) return f;
        throw new Failure("FIELD_MISSING");
    }
    private static ObjectReference object(Value value, String error) throws Failure {
        require(value instanceof ObjectReference, error);
        return (ObjectReference) value;
    }
    private static void pin(ObjectReference value) {
        value.disableCollection();
        pinned.add(value);
    }
    private static void require(boolean condition, String code) throws Failure {
        if (!condition) throw new Failure(code);
    }
    private static String bounded(String value, int cap) throws Failure {
        require(value != null && value.length() <= cap && value.indexOf('\0') < 0, "METADATA_FORMAT");
        return value;
    }
    private static String quote(Object value) {
        String s = String.valueOf(value), out = "\"";
        StringBuilder b = new StringBuilder(out);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static void emit(Map<String, ?> data) {
        StringBuilder b = new StringBuilder("{");
        boolean comma = false;
        for (Map.Entry<String, ?> e : data.entrySet()) {
            if (comma) b.append(',');
            b.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            b.append(v instanceof Boolean || v instanceof Number ? v : quote(v));
            comma = true;
        }
        System.out.println(b.append('}'));
        System.out.flush();
    }
}
