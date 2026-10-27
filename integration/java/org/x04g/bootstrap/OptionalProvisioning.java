package org.x04g.bootstrap;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Optional initial import from two explicit root-staged files. Never tests RTSP or actuates gate. */
final class OptionalProvisioning {
    private static final AtomicBoolean ONCE = new AtomicBoolean();
    private static volatile WeakReference<Activity> candidate = new WeakReference<Activity>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String CAMERAS = "x04g-provision-cameras.json";
    private static final String GATE = "x04g-provision-gate.json";
    private OptionalProvisioning() {}

    private static void report(String kind, String result) {
        Log.i("X04GProvision", "kind=" + kind + " result=" + result);
    }

    static void observe(Activity activity) {
        candidate = new WeakReference<Activity>(activity);
        if (!ONCE.compareAndSet(false, true)) return;
        Thread worker = new Thread(new Runnable() { public void run() { provision(); } }, "X04G-Provision");
        worker.setDaemon(true);
        worker.start();
    }

    private static Class<?> integration(String name) throws Exception {
        return Class.forName("org.x04g.home.integration." + name, false, StockBootstrap.class.getClassLoader());
    }

    private static boolean installed() throws Exception {
        return "INSTALLED".equals(integration("Bootstrap").getMethod("state").invoke(null));
    }

    private static final class Stage {
        final File path;
        final int limit;
        final StructStat original;
        final FileDescriptor fd;
        final byte[] digest;
        String json;
        int count;
        boolean dispatched;
        Stage(File path, int limit, StructStat original, FileDescriptor fd, byte[] digest, String json) {
            this.path = path; this.limit = limit; this.original = original; this.fd = fd;
            this.digest = digest; this.json = json;
        }
        void close() { json = null; try { Os.close(fd); } catch (Exception ignored) {} }
    }

    private static boolean allowed(StructStat info, int limit) {
        return ProvisionRules.metadata(info.st_uid, info.st_gid, info.st_mode, info.st_nlink,
                info.st_size, Process.myUid(), limit);
    }

    private static boolean identity(StructStat first, StructStat after) {
        return ProvisionRules.sameIdentity(first.st_dev, first.st_ino, first.st_size,
                after.st_dev, after.st_ino, after.st_size);
    }

    private static byte[] read(FileDescriptor fd, int bytes) throws Exception {
        byte[] result = new byte[bytes];
        int done = 0;
        while (done < bytes) {
            int count = Os.read(fd, result, done, bytes - done);
            if (count <= 0) throw new Exception();
            done += count;
        }
        if (Os.read(fd, new byte[1], 0, 1) != 0) throw new Exception();
        return result;
    }

    private static Stage open(File parent, String name, int limit, boolean cameras) throws Exception {
        File path = new File(parent, name);
        FileDescriptor fd = Os.open(path.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
        byte[] raw = null;
        try {
            StructStat before = Os.fstat(fd);
            if (!allowed(before, limit)) throw new Exception();
            raw = read(fd, (int) before.st_size);
            StructStat after = Os.fstat(fd);
            if (!allowed(after, limit) || !identity(before, after)) throw new Exception();
            String text = ProvisionRules.decode(raw, limit);
            JSONObject object = new JSONObject(text);
            Stage result = new Stage(path, limit, before, fd,
                    MessageDigest.getInstance("SHA-256").digest(raw), text);
            if (cameras) result.count = object.getJSONArray("cameras").length();
            return result;
        } catch (Exception failure) { Os.close(fd); throw failure; }
        finally { if (raw != null) Arrays.fill(raw, (byte) 0); }
    }

    private static final class Sample {
        boolean valid, loaded, gate;
        String cameraState, gateState;
        long revision;
        int count;
    }

    private static Sample mainSample(final AtomicBoolean active, final Stage cameras,
                                     final Stage gate, final boolean dispatch) throws Exception {
        final CountDownLatch finished = new CountDownLatch(1);
        final Sample result = new Sample();
        MAIN.post(new Runnable() { public void run() {
            try {
                Activity activity = candidate.get();
                if (!active.get() || activity == null || !StockBootstrap.activityIsStock(activity) || !installed()) return;
                Class<?> carousel = integration("OemCarousel");
                Method status = carousel.getMethod("status", Activity.class);
                Method snapshotMethod = carousel.getMethod("snapshot");
                Object previousSnapshot = snapshotMethod.invoke(null);
                status.invoke(null, activity);
                Object currentSnapshot = snapshotMethod.invoke(null);
                // In pinned633f, status publishes a new String only for its current Activity.
                if (currentSnapshot == previousSnapshot) return;
                JSONObject snapshot = new JSONObject((String) currentSnapshot);
                if (!"installed".equals(snapshot.optString("phase")) || !snapshot.optBoolean("active")) return;
                JSONObject runtime = snapshot.getJSONObject("runtime");
                result.loaded = runtime.optBoolean("config_loaded");
                result.revision = runtime.optLong("configuration_revision", -1);
                result.count = runtime.optInt("camera_count", -1);
                result.gate = runtime.optBoolean("gate_configured");
                result.cameraState = runtime.optString("camera_provision", "NONE");
                result.gateState = runtime.optString("gate_provision", "NONE");
                if (dispatch) {
                    if (!result.loaded || !active.get()) return;
                    if (cameras != null && !cameras.dispatched && (result.revision != 0 || result.count != 0)) {
                        cameras.dispatched = true;
                        result.cameraState = "REJECTED";
                    } else if (cameras != null && !cameras.dispatched) {
                        if (!active.get()) return;
                        cameras.dispatched = true;
                        carousel.getMethod("provisionCameras", Activity.class, String.class).invoke(null, activity, cameras.json);
                        cameras.json = null;
                        result.cameraState = "PENDING";
                    }
                    if (gate != null && !gate.dispatched && result.gate) {
                        gate.dispatched = true;
                        result.gateState = "REJECTED";
                    } else if (gate != null && !gate.dispatched) {
                        if (!active.get()) return;
                        gate.dispatched = true;
                        carousel.getMethod("provisionGate", Activity.class, String.class).invoke(null, activity, gate.json);
                        gate.json = null;
                        result.gateState = "PENDING";
                    }
                }
                result.valid = true;
            } catch (Exception ignored) { /* Preserve files; never print nested messages or JSON. */ }
            finally { finished.countDown(); }
        }});
        if (!finished.await(2, TimeUnit.SECONDS)) throw new Exception();
        return result;
    }

    /** Claim before identity/hash checks, so replacement staged files are never blindly deleted. */
    private static boolean removeAccepted(Stage stage, File parent) {
        File claimDirectory = new File(parent, ".x04g-provision-claim-" + UUID.randomUUID().toString());
        File claim = new File(claimDirectory, stage.path.getName());
        FileDescriptor check = null;
        byte[] raw = null;
        try {
            Os.mkdir(claimDirectory.getPath(), 0700);
            StructStat directory = Os.lstat(claimDirectory.getPath());
            if (!OsConstants.S_ISDIR(directory.st_mode) || directory.st_uid != Process.myUid()
                    || (directory.st_mode & 0777) != 0700) return false;
            Os.rename(stage.path.getPath(), claim.getPath());
            check = Os.open(claim.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, 0);
            StructStat info = Os.fstat(check);
            if (!allowed(info, stage.limit) || !identity(stage.original, info)) return false;
            raw = read(check, (int) info.st_size);
            if (!MessageDigest.isEqual(stage.digest, MessageDigest.getInstance("SHA-256").digest(raw))) return false;
            StructStat current = Os.lstat(claim.getPath());
            if (!allowed(current, stage.limit) || !identity(info, current)) return false;
            // The random exclusive directory is private to this helper. A new fixed-name staged file is untouched.
            Os.remove(claim.getPath());
            Os.remove(claimDirectory.getPath());
            return true;
        } catch (Exception ignored) { return false; }
        finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
            if (check != null) try { Os.close(check); } catch (Exception ignored) {}
            // Any mismatch/failure leaves the claimed file in the private directory for root recovery.
        }
    }

    private static void provision() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        AtomicBoolean active = new AtomicBoolean(true);
        Stage cameras = null, gate = null;
        try {
            boolean ready = false;
            for (int attempt = 0; attempt < 40; ++attempt) {
                if (installed()) { ready = true; break; }
                Thread.sleep(250);
            }
            if (!ready) return;
            Activity activity = candidate.get();
            if (activity == null) return;
            File parent = activity.getFilesDir().getCanonicalFile();
            File gateStore = new File(activity.getNoBackupFilesDir(), "x04g-home/gate.v1.enc");
            activity = null;
            try { cameras = open(parent, CAMERAS, 65536, true); } catch (Exception ignored) {}
            if (!gateStore.exists()) try { gate = open(parent, GATE, 98304, false); } catch (Exception ignored) {}
            if (cameras == null && gate == null) return;
            boolean dispatched = false;
            for (int attempt = 0; attempt < 40; ++attempt) {
                Sample sample = mainSample(active, cameras, gate, !dispatched);
                if (sample.valid) {
                    if (!dispatched) dispatched = true;
                    if (cameras != null) {
                        int result = ProvisionRules.cameraResult(sample.cameraState, sample.loaded, sample.revision,
                                sample.count, cameras.count);
                        if (result == ProvisionRules.SAVED) {
                            report("cameras", removeAccepted(cameras, parent) ? "CONSUMED" : "SAVED_STAGED_RETAINED");
                            cameras.close(); cameras = null;
                        } else if (result == ProvisionRules.FAIL) {
                            report("cameras", "REJECTED_STAGED_RETAINED"); cameras.close(); cameras = null;
                        }
                    }
                    if (gate != null) {
                        int result = ProvisionRules.gateResult(sample.gateState, sample.gate);
                        if (result == ProvisionRules.SAVED) {
                            report("gate", removeAccepted(gate, parent) ? "CONSUMED" : "SAVED_STAGED_RETAINED");
                            gate.close(); gate = null;
                        } else if (result == ProvisionRules.FAIL) {
                            report("gate", "REJECTED_STAGED_RETAINED"); gate.close(); gate = null;
                        }
                    }
                    if (cameras == null && gate == null) return;
                }
                Thread.sleep(500);
            }
        } catch (Exception ignored) { /* Bounded failure keeps staged content; no secret output. */ }
        finally {
            active.set(false);
            if (cameras != null) { report("cameras", "UNCONFIRMED_STAGED_RETAINED"); cameras.close(); }
            if (gate != null) { report("gate", "UNCONFIRMED_STAGED_RETAINED"); gate.close(); }
        }
    }
}
