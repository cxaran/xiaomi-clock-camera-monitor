package org.x04g.bootstrap;
import org.json.JSONObject;

/** HOST JVM filtering tests only; never Android scheduling or runtime acceptance. */
public final class DiagnosticRulesHostTest {
    private static int checks;
    private static final String SECRET = "PRIVATE_PAYLOAD_SENTINEL";
    private static void need(boolean v) { checks++; if (!v) throw new AssertionError("host_rule_failed"); }
    private interface Work { void run() throws Exception; }
    private static void rejects(Work w) { boolean rejected = false; try { w.run(); } catch (Exception e) { rejected = true; } need(rejected); }
    public static void main(String[] args) throws Exception {
        JSONObject bootstrap = DiagnosticRules.bootstrap("{\"state\":\"INSTALLED\",\"error_type\":null,\"error_code\":null,\"password\":\""+SECRET+"\"}");
        need(bootstrap.length() == 3 && bootstrap.isNull("error_type") && bootstrap.isNull("error_code"));
        need(!bootstrap.toString().contains(SECRET));
        String[] states = {"NOT_STARTED", "APPLICATION_NOT_READY", "BOOTSTRAP_REJECTED", "STOCK_VERSION_REJECTED",
            "EXPLICITLY_DISABLED", "STOCK_VERIFIED", "WAITING_FOR_ACTIVITY_CALLBACK", "INSTALLED",
            "WAITING_FOR_ORIGINAL_HOME", "ORIGINAL_HOME_UNAVAILABLE"};
        for (String state : states) need(state.equals(DiagnosticRules.bootstrap(new JSONObject().put("state",state).toString()).getString("state")));
        JSONObject unknown = DiagnosticRules.bootstrap(new JSONObject().put("state",SECRET).put("error_type",SECRET).put("error_code",SECRET).toString());
        need(!unknown.toString().contains(SECRET)); need("UNKNOWN".equals(unknown.getString("state")));
        need("InvocationTargetException".equals(DiagnosticRules.bootstrap("{\"error_type\":\"InvocationTargetException\"}").getString("error_type")));
        need("home_not_ready".equals(DiagnosticRules.bootstrap("{\"error_code\":\"home_not_ready\"}").getString("error_code")));
        need("diagnostic_failed".equals(DiagnosticRules.bootstrap("{\"error_code\":\"diagnostic_failed\"}").getString("error_code")));
        rejects(()->DiagnosticRules.bootstrap("{\"state\":true}"));
        rejects(()->DiagnosticRules.bootstrap("{\"error_type\":42}"));
        rejects(()->DiagnosticRules.bootstrap("{\"error_code\":{}}"));
        rejects(()->DiagnosticRules.bootstrap(null)); rejects(()->DiagnosticRules.bootstrap(""));
        rejects(()->DiagnosticRules.bootstrap("[]")); rejects(()->DiagnosticRules.bootstrap("{}{}"));
        rejects(()->DiagnosticRules.bootstrap("{'state':'INSTALLED'}"));
        rejects(()->DiagnosticRules.bootstrap("\ufeff{}")); rejects(()->DiagnosticRules.bootstrap("{\"state\":\"\0\"}"));
        rejects(()->DiagnosticRules.bootstrap("{\"a\":{\"b\":{\"c\":{\"d\":{}}}}}"));
        final StringBuilder large = new StringBuilder(); while(large.length() <= 16384) large.append(' ');
        rejects(()->DiagnosticRules.bootstrap(large.toString()));
        JSONObject gates = new JSONObject().put("oem_screen_off_consumes_touch",false).put("oem_alarm_blocks_pager_touch",JSONObject.NULL).put("alarm_label",SECRET);
        JSONObject pager = new JSONObject().put("selected_index",-1).put("scroll_state",0).put("model_count",6)
            .put("dragging",false).put("unable_to_drag",true).put("uri",SECRET);
        JSONObject input = new JSONObject().put("phase","installed").put("active",true).put("gates",gates)
            .put("pager",pager).put("runtime",JSONObject.NULL).put("config",SECRET);
        JSONObject carousel = DiagnosticRules.carousel(input.toString());
        need(!carousel.toString().contains(SECRET)); need(carousel.length() == 5);
        need(carousel.getJSONObject("gates").length() == 2 && carousel.getJSONObject("gates").isNull("oem_alarm_blocks_pager_touch"));
        need(carousel.getJSONObject("pager").length() == 5 && carousel.getJSONObject("pager").getInt("selected_index") == -1);
        need(carousel.isNull("runtime"));
        for (String phase : new String[]{"not_installed","wrong_thread","installed","inactive","diagnostic_unavailable"})
            need(phase.equals(DiagnosticRules.carousel(new JSONObject().put("phase",phase).toString()).getString("phase")));
        need("UNKNOWN".equals(DiagnosticRules.carousel(new JSONObject().put("phase",SECRET).toString()).getString("phase")));
        need(DiagnosticRules.carousel("{\"gates\":null,\"pager\":null,\"runtime\":null}").length() == 3);
        rejects(()->DiagnosticRules.carousel("{\"active\":1}"));
        rejects(()->DiagnosticRules.carousel("{\"gates\":[]}"));
        rejects(()->DiagnosticRules.carousel("{\"gates\":{\"oem_screen_off_consumes_touch\":\"false\"}}"));
        rejects(()->DiagnosticRules.carousel("{\"pager\":{\"scroll_state\":1.2}}"));
        rejects(()->DiagnosticRules.carousel("{\"pager\":{\"model_count\":2147483648}}"));
        rejects(()->DiagnosticRules.carousel("{\"pager\":{\"dragging\":0}}"));
        JSONObject runtime = new JSONObject();
        String[] booleans = {"camera_visible", "gate_visible", "admin_open", "preview_active", "memory_paused",
            "audio_focus_granted", "config_loaded", "camera_surface_valid", "home_task_active", "player_running",
            "gate_configured", "gate_control_physical_verified", "hardware_decoder"};
        for (String key : booleans) runtime.put(key, true);
        String[] counters = {"io_queue", "io_active", "camera_count", "configuration_revision", "frames_rendered",
            "video_packets", "video_drops", "audio_samples_written"};
        for (String key : counters) runtime.put(key, 1L);
        runtime.put("camera_provision","SAVED").put("gate_provision","NOT_REQUESTED")
            .put("stream_state","PLAYING").put("stream_error","NONE").put("preview_last_code","VIDEO_CODEC").put("host",SECRET).put("web_admin_state",SECRET);
        JSONObject filtered = DiagnosticRules.carousel(new JSONObject().put("runtime",runtime).toString()).getJSONObject("runtime");
        need(filtered.length() == 26 && !filtered.toString().contains(SECRET));
        for (String key : booleans) need(filtered.getBoolean(key));
        for (String key : counters) need(filtered.getLong(key) == 1);
        need("PLAYING".equals(filtered.getString("stream_state")));
        need("VIDEO_CODEC".equals(filtered.getString("preview_last_code")));
        need("UNKNOWN".equals(DiagnosticRules.carousel("{\"runtime\":{\"preview_last_code\":\""+SECRET+"\"}}").getJSONObject("runtime").getString("preview_last_code")));
        need("UNKNOWN".equals(DiagnosticRules.carousel("{\"runtime\":{\"stream_error\":\""+SECRET+"\"}}").getJSONObject("runtime").getString("stream_error")));
        need(DiagnosticRules.carousel("{\"runtime\":{\"stream_state\":null,\"frames_rendered\":null,\"camera_visible\":null}}").getJSONObject("runtime").length() == 3);
        rejects(()->DiagnosticRules.carousel("{\"runtime\":{\"frames_rendered\":-1}}"));
        rejects(()->DiagnosticRules.carousel("{\"runtime\":{\"frames_rendered\":1.5}}"));
        rejects(()->DiagnosticRules.carousel("{\"runtime\":{\"player_running\":1}}"));
        rejects(()->DiagnosticRules.carousel("{\"runtime\":{\"stream_state\":[]}}"));
        rejects(()->DiagnosticRules.carousel("{\"runtime\":[]} "));
        JSONObject supplied = new JSONObject().put("password",SECRET).put("state","INSTALLED");
        need(!DiagnosticRules.record(0,1,"OK",supplied,null).toString().contains(SECRET));
        JSONObject line = DiagnosticRules.record(0,0,"OK",bootstrap,carousel);
        need(line.getInt("sample") == 1 && line.getLong("elapsed_ms") == 0 && !line.toString().contains(SECRET));
        need(DiagnosticRules.record(24,119999,"MAIN_TIMEOUT",null,null).getInt("sample") == 25);
        need("UNKNOWN".equals(DiagnosticRules.record(0,1,SECRET,null,null).getString("read_state")));
        rejects(()->DiagnosticRules.record(-1,0,"OK",null,null)); rejects(()->DiagnosticRules.record(25,0,"OK",null,null));
        rejects(()->DiagnosticRules.record(0,-1,"OK",null,null)); rejects(()->DiagnosticRules.record(0,120000,"OK",null,null));
        System.out.println("{\"pass\":true,\"HOST_assertions\":"+checks+",\"Android_scheduling_or_runtime_executed\":false}");
    }
}
