package org.x04g.bootstrap;

import org.json.JSONObject;
import org.json.JSONTokener;

/** Second independent allowlist. Never retain arbitrary strings or unknown object fields. */
final class DiagnosticRules {
    private DiagnosticRules() {}
    static final int MAX_INPUT_CHARS = 16384;

    private static JSONObject bounded(String raw) throws Exception {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_INPUT_CHARS
                || raw.charAt(0) == '\ufeff' || raw.indexOf('\0') >= 0) throw new Exception();
        boolean quoted = false, escaped = false; int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '\'') throw new Exception();
            else if (c == '{' || c == '[') { if (++depth > 4) throw new Exception(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new Exception(); }
        }
        if (quoted || depth != 0) throw new Exception();
        JSONTokener tokener = new JSONTokener(raw);
        Object object = tokener.nextValue();
        if (!(object instanceof JSONObject) || tokener.nextClean() != 0) throw new Exception();
        return (JSONObject)object;
    }

    private static void bool(JSONObject out, JSONObject in, String key) throws Exception {
        if (!in.has(key)) return;
        Object v = in.get(key);
        if (!(v instanceof Boolean)) throw new Exception();
        out.put(key, v);
    }
    private static void count(JSONObject out, JSONObject in, String key, long minimum, long maximum) throws Exception {
        if (!in.has(key)) return;
        Object v = in.get(key);
        if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long)) throw new Exception();
        long n = ((Number)v).longValue();
        if (n < minimum || n > maximum) throw new Exception();
        out.put(key, n);
    }
    private static void code(JSONObject out, JSONObject in, String key, String... allowed) throws Exception {
        if (!in.has(key)) return;
        Object v = in.get(key);
        if (!(v instanceof String)) throw new Exception();
        for (String fixed : allowed) if (fixed.equals(v)) { out.put(key, fixed); return; }
        out.put(key, "UNKNOWN");
    }

    private static void nullableBool(JSONObject out, JSONObject in, String key) throws Exception {
        if (in.has(key) && in.isNull(key)) out.put(key, JSONObject.NULL);
        else bool(out, in, key);
    }
    private static void nullableInt(JSONObject out, JSONObject in, String key) throws Exception {
        if (in.has(key) && in.isNull(key)) out.put(key, JSONObject.NULL);
        else count(out, in, key, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }
    private static JSONObject object(JSONObject in, String key) throws Exception {
        Object value = in.get(key);
        if (!(value instanceof JSONObject)) throw new Exception();
        return (JSONObject)value;
    }

    static JSONObject carousel(String raw) throws Exception {
        JSONObject in = bounded(raw), out = new JSONObject();
        code(out, in, "phase", "not_installed", "wrong_thread", "installed", "inactive", "diagnostic_unavailable");
        bool(out, in, "active");
        if (in.has("gates")) {
            if (in.isNull("gates")) out.put("gates", JSONObject.NULL);
            else {
                JSONObject src = object(in, "gates"), gates = new JSONObject();
                nullableBool(gates, src, "oem_screen_off_consumes_touch");
                nullableBool(gates, src, "oem_alarm_blocks_pager_touch");
                out.put("gates", gates);
            }
        }
        if (in.has("pager")) {
            if (in.isNull("pager")) out.put("pager", JSONObject.NULL);
            else {
                JSONObject src = object(in, "pager"), pager = new JSONObject();
                nullableInt(pager, src, "selected_index"); nullableInt(pager, src, "scroll_state");
                nullableInt(pager, src, "model_count");
                nullableBool(pager, src, "dragging"); nullableBool(pager, src, "unable_to_drag");
                out.put("pager", pager);
            }
        }
        if (in.has("runtime")) {
            if (in.isNull("runtime")) out.put("runtime", JSONObject.NULL);
            else out.put("runtime", runtime(object(in, "runtime")));
        }
        return out;
    }

    private static void nullableCode(JSONObject out, JSONObject in, String key, String... allowed) throws Exception {
        if (in.has(key) && in.isNull(key)) out.put(key, JSONObject.NULL);
        else code(out, in, key, allowed);
    }
    static JSONObject bootstrap(String raw) throws Exception {
        JSONObject in = bounded(raw), out = new JSONObject();
        code(out, in, "state", "NOT_STARTED", "APPLICATION_NOT_READY", "BOOTSTRAP_REJECTED",
            "STOCK_VERSION_REJECTED", "EXPLICITLY_DISABLED", "STOCK_VERIFIED", "WAITING_FOR_ACTIVITY_CALLBACK",
            "INSTALLED", "WAITING_FOR_ORIGINAL_HOME", "ORIGINAL_HOME_UNAVAILABLE");
        nullableCode(out, in, "error_type", "Exception", "RuntimeException", "IllegalArgumentException",
            "IllegalStateException", "NullPointerException", "ClassCastException", "SecurityException",
            "ClassNotFoundException", "NoSuchMethodException", "NoSuchFieldException", "IllegalAccessException",
            "InvocationTargetException", "IOException", "FileNotFoundException", "NoSuchAlgorithmException", "OtherException");
        nullableCode(out, in, "error_code", "active_source_changed", "launcher_version_changed", "companion_rejected",
            "activity_rejected", "install_not_active", "main_thread_required", "other_activity_active",
            "previous_cleanup_incomplete", "rebuild_fault", "install_failure", "observer_missing", "order_failed",
            "selection_failed", "visibility_failed", "activity_mismatch", "home_not_ready", "foreign_home",
            "home_not_attached", "oem_shape_changed", "pager_not_ready", "owner_mismatch", "fragment_state_unavailable",
            "collections_changed", "id_collision", "model_shape_changed", "model_class_changed", "model_id_collision",
            "clock_not_unique", "ordering_changed", "tile_block_unavailable", "fragment_tree_bound", "support_manager_changed",
            "support_store_changed", "fragment_list_bound", "duplicate_own", "clock_missing", "own_model_missing",
            "ownership_lost", "priority_failed", "observer_ownership_lost", "home_transition_pending", "replacement_binding_changed",
            "replacement_own_models_remain", "cleanup_incomplete", "oem_field_missing", "oem_method_missing",
            "verify_failed", "register_failed", "retry_failed", "find_activity_failed", "diagnostic_failed");
        return out;
    }
    private static JSONObject runtime(JSONObject in) throws Exception {
        JSONObject out = new JSONObject();
        for (String key : new String[]{"camera_visible", "gate_visible", "admin_open", "preview_active", "memory_paused",
                "audio_focus_granted", "config_loaded", "camera_surface_valid", "home_task_active", "player_running",
                "gate_configured", "gate_control_physical_verified", "hardware_decoder"}) nullableBool(out, in, key);
        for (String key : new String[]{"io_queue", "io_active", "camera_count", "configuration_revision", "frames_rendered",
                "video_packets", "video_drops", "audio_samples_written"}) {
            if (in.has(key) && in.isNull(key)) out.put(key, JSONObject.NULL);
            else count(out, in, key, 0, Long.MAX_VALUE);
        }
        nullableCode(out, in, "camera_provision", "NOT_REQUESTED", "PENDING", "SAVED", "REJECTED", "BUSY");
        nullableCode(out, in, "gate_provision", "NOT_REQUESTED", "PENDING", "SAVED", "REJECTED", "BUSY");
        nullableCode(out, in, "stream_state", "STOPPED", "CONNECTING", "BUFFERING", "PLAYING", "ERROR");
        nullableCode(out, in, "stream_error", "NONE", "INVALID_CONFIGURATION", "CONNECTION", "TIMEOUT", "AUTHENTICATION",
            "AUTH_UNSUPPORTED", "PROTOCOL", "UNSUPPORTED_STREAM", "FRAME_TOO_LARGE", "VIDEO_CODEC", "AUDIO_OUTPUT",
            "SURFACE_LOST", "FIRST_FRAME_TIMEOUT", "VIDEO_STALLED");
        nullableCode(out, in, "preview_last_code", "NOT_REQUESTED", "VIDEO_AUDIO_OK", "PREVIEW_INTERRUPTED", "PREVIEW_UNAVAILABLE",
            "PREVIEW_TIMEOUT", "AUDIO_BUSY", "CONFIGURATION", "BUSY", "MEMORY_PRESSURE", "NONE", "INVALID_CONFIGURATION",
            "CONNECTION", "TIMEOUT", "AUTHENTICATION", "AUTH_UNSUPPORTED", "PROTOCOL", "UNSUPPORTED_STREAM", "FRAME_TOO_LARGE",
            "VIDEO_CODEC", "AUDIO_OUTPUT", "SURFACE_LOST", "FIRST_FRAME_TIMEOUT", "VIDEO_STALLED", "INTERNAL_ERROR");
        return out;
    }

    static JSONObject record(int index, long elapsed, String readState,
                             JSONObject bootstrap, JSONObject carousel) throws Exception {
        if (index < 0 || index >= 25 || elapsed < 0 || elapsed >= 120000) throw new Exception();
        JSONObject source = new JSONObject().put("read_state", readState), result = new JSONObject();
        result.put("sample", index + 1); result.put("elapsed_ms", elapsed);
        code(result, source, "read_state", "OK", "MAIN_NOT_READY", "MAIN_BUSY", "MAIN_POST_REJECTED",
             "MAIN_TIMEOUT", "READ_FAILED");
        if (bootstrap != null) result.put("bootstrap", bootstrap(bootstrap.toString()));
        if (carousel != null) result.put("carousel", carousel(carousel.toString()));
        if (result.toString().length() > 3500) throw new Exception();
        return result;
    }
}
