import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/** Host-only explicit JDI operator. Never launches an Android process or changes APKs. */
public final class JdiHome {
    private static final String ACTIVITY = "com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity";
    private static final String PROBE = "org.x04g.home.integration.OemCarousel";
    private static final String OWN_ID = "x04g.home.gate.v1";
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
            if ("probe".equals(mode)) {
                result.put("protocol_read_only", true);
                result.put("phase", "metadata_ready");
                exit = 0;
            } else {
                require(vm.canBeModified(), "VM_READ_ONLY");
                result.put("apk_sha256_expected", args.get("apk-sha256"));
                // Host wrapper must verify the installed APK hash before calling us.
                result.put("apk_hash_verification", "CALLER_REQUIRED_NOT_PERFORMED_BY_JDI");
                mutate(mode, args.get("apk"), original, mains.get(0), location);
                exit = 0;
            }
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
        require(Set.of("probe", "install", "status", "remove", "rebuild").contains(args.getOrDefault("mode", "")), "MODE");
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
            held = set; // Own the event before validation so finally can resume it.
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
        stage = "installer_class";
        ClassType installer = existingInstaller(parent, apk);
        if (installer == null && "rebuild".equals(mode)) throw new Failure("REBUILD_NOT_INSTALLED");
        if (installer == null && !"install".equals(mode)) {
            result.put("phase", "probe_class_not_loaded");
            return;
        }
        if (installer == null) installer = loadInstaller(main, parent, apk);
        stage = "resolve_activity_parameter";
        resolveInstallerContract(installer, main);
        stage = "invoke_" + mode;
        if ("rebuild".equals(mode)) {
            Field currentField = field(installer, "current");
            ObjectReference session = object(installer.getValue(currentField), "REBUILD_NO_SESSION");
            require((PROBE + "$Session").equals(session.referenceType().name()), "REBUILD_SESSION_TYPE");
            require(activity.equals(session.getValue(field(session.referenceType(), "activity"))), "REBUILD_ACTIVITY_MISMATCH");
            Value active = session.getValue(field(session.referenceType(), "active"));
            require(active instanceof BooleanValue && ((BooleanValue) active).value(), "REBUILD_NOT_ACTIVE");
            ObjectReference adapter = object(session.getValue(field(session.referenceType(), "adapter")), "REBUILD_ADAPTER");
            require("zb".equals(adapter.referenceType().name()), "REBUILD_ADAPTER_TYPE");
            ObjectReference home = object(session.getValue(field(session.referenceType(), "home")), "REBUILD_HOME");
            ObjectReference pager = object(session.getValue(field(session.referenceType(), "pager")), "REBUILD_PAGER");
            ObjectReference wrapper = object(session.getValue(field(session.referenceType(), "wrapper")), "REBUILD_WRAPPER");
            require(adapter.equals(home.getValue(field(home.referenceType(), "B"))), "REBUILD_HOME_ADAPTER_CHANGED");
            require(pager.equals(home.getValue(field(home.referenceType(), "y"))), "REBUILD_HOME_PAGER_CHANGED");
            require(adapter.equals(pager.getValue(field(pager.referenceType(), "b"))), "REBUILD_PAGER_ADAPTER_CHANGED");
            require(wrapper.equals(adapter.getValue(field(adapter.referenceType(), "b"))), "REBUILD_OBSERVER_CHANGED");
            result.put("rebuild_binding_preflight", true);
            pin(session);
            installer.invokeMethod(main, method(installer, "status", "(Landroid/app/Activity;)V"),
                                   Collections.singletonList(activity), SINGLE);
            result.put("snapshot_before_json", snapshot(installer, main));
            adapter.invokeMethod(main, method(adapter.referenceType(), "e", "()V"), Collections.emptyList(), SINGLE);
            require(session.equals(installer.getValue(currentField)), "REBUILD_SESSION_CHANGED");
            installer.invokeMethod(main, method(installer, "status", "(Landroid/app/Activity;)V"),
                                   Collections.singletonList(activity), SINGLE);
        } else {
            installer.invokeMethod(main, method(installer, mode, "(Landroid/app/Activity;)V"),
                                   Collections.singletonList(activity), SINGLE);
        }
        stage = "snapshot";
        result.put("snapshot_json", snapshot(installer, main));
        result.put("phase", "rebuild".equals(mode) ? "rebuild_returned" : "invocation_returned");
        result.put("visible_card_verified", false);
    }

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
        for (String entry : List.of("install", "status", "remove")) {
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

    private static ClassType loadInstaller(ThreadReference main, ClassLoaderReference parent, String apk)
            throws Exception {
        ClassType classType = oneClass("java.lang.Class");
        Value dexObject = classType.invokeMethod(main,
                method(classType, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),
                Arrays.asList(vm.mirrorOf("dalvik.system.DexClassLoader"), vm.mirrorOf(true), null), SINGLE);
        require(dexObject instanceof ClassObjectReference, "DEX_CLASS_OBJECT");
        ReferenceType dex = ((ClassObjectReference) dexObject).reflectedType();
        require(dex instanceof ClassType && "dalvik.system.DexClassLoader".equals(dex.name()), "DEX_CLASS_TYPE");
        ObjectReference loaderObject = ((ClassType) dex).newInstance(main,
                method(dex, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V"),
                Arrays.asList(vm.mirrorOf(apk), null, null, parent), SINGLE);
        require(loaderObject instanceof ClassLoaderReference, "DEX_LOADER_INSTANCE");
        ClassLoaderReference loader = (ClassLoaderReference) loaderObject;
        pin(loader);
        validateLoader(loader, parent, apk);
        Value classObject = loader.invokeMethod(main,
                method(loader.referenceType(), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"),
                Collections.singletonList(vm.mirrorOf(PROBE)), SINGLE);
        require(classObject instanceof ClassObjectReference, "INSTALLER_CLASS_OBJECT");
        ReferenceType type = ((ClassObjectReference) classObject).reflectedType();
        require(type instanceof ClassType && PROBE.equals(type.name()) && loader.equals(type.classLoader()), "INSTALLER_CLASS_IDENTITY");
        // Call Class.forName with the same loader to initialize static Handler/report before install.
        classType.invokeMethod(main,
                method(classType, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),
                Arrays.asList(vm.mirrorOf(PROBE), vm.mirrorOf(true), loader), SINGLE);
        return existingInstaller(parent, apk);
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
