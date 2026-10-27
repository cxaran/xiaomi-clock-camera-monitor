package org.x04g.bootstrap;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Version-pinned bridge; optional diagnostics are bounded and do not alter the carousel. */
public final class StockBootstrap {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final String ACTIVITY = "com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity";
    private static final File APK = new File("/product/app/com.google.assistant.launcher/app.apk");
    private static final String SHA = "1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21";
    private static final String OEM_PACKAGE = "com.google.assistant.launcher";
    private static final String OEM_SIGNER = "e4f68be9a363883745e97766e83760d80cbd22e7705a38fbd2f322c0683ea152";
    private static final long OEM_VERSION_CODE = 111706;
    private static final String OEM_VERSION_NAME = "11.51.6.416171605";
    private static volatile int runtimeExpectedUid = -1;
    private static volatile boolean verified;
    private static volatile boolean oemIdentityVerified;
    private static volatile boolean companionVerified;
    private StockBootstrap() {}

    public static synchronized void start(int expectedUid) {
        if (expectedUid < 10000 || expectedUid > 19999 || Process.myUid() != expectedUid
                || runtimeExpectedUid != -1 || !STARTED.compareAndSet(false, true)) return;
        runtimeExpectedUid = expectedUid;
        Thread checker = new Thread(new Runnable() { public void run() {
            try {
                // postAppSpecialize precedes ActivityThread/Application startup. Do not
                // perform libcore/Conscrypt file or digest work in that early window.
                if (!awaitApplicationReady()) { Log.i("X04GBootstrap", "stage=application_ready result=timeout"); return; }
                if (!stockHashMatches()) { Log.i("X04GBootstrap", "stage=stock_hash result=rejected"); return; }
                verified = true;
                Class<?> target = Class.forName("org.x04g.home.integration.Bootstrap", true, StockBootstrap.class.getClassLoader());
                java.lang.reflect.Method method = target.getMethod("start");
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Void.TYPE)
                    throw new NoSuchMethodException();
                method.invoke(null);
                Log.i("X04GBootstrap", "stage=app_bootstrap result=dispatched");
                RuntimeDiagnostics.start();
            } catch (Throwable failure) {
                // Fixed stage + exception type only; never print messages, source paths or nested causes.
                Log.i("X04GBootstrap", "stage=app_bootstrap result=failed type=" + failure.getClass().getSimpleName());
            }
        }}, "X04G-stock-check");
        checker.setDaemon(true); checker.start();
    }

    private static boolean awaitApplicationReady() throws Exception {
        for (int attempt = 0; attempt < 240; attempt++) {
            if (Looper.getMainLooper() != null) {
                Object app = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null);
                if (app instanceof android.app.Application) return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** Root integration must call this (or equivalent) before touching an actual Activity's carousel. */
    public static boolean activityIsStock(Activity activity) {
        try {
            boolean accepted = verified && oemIdentityVerified && companionVerified && uidMatches() && activity != null
                    && ACTIVITY.equals(activity.getClass().getName()) && !activity.isFinishing()
                    && OEM_PACKAGE.equals(activity.getPackageName())
                    && activity.getApplicationInfo().uid == runtimeExpectedUid
                    && new File(activity.getApplicationInfo().sourceDir).getCanonicalFile().equals(APK.getCanonicalFile())
                    && activeOemMatches(activity.getPackageManager());
            if (accepted) OptionalProvisioning.observe(activity);
            return accepted;
        } catch (Exception failure) { return false; }
    }

    /** Blocking worker-only check. Integration calls this before loading installed companion assets. */
    public static boolean companionIsStock(Context context) {
        companionVerified = false;
        oemIdentityVerified = false;
        if (context == null || !uidMatches() || context.getApplicationInfo().uid != runtimeExpectedUid
                || Looper.myLooper() == Looper.getMainLooper() || BuildPins.OWN_APK_SHA.isEmpty()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            if (!activeOemMatches(pm) || !stockHashMatches()) return false;
            PackageInfo oem = pm.getPackageInfo(OEM_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            if (!oemVersionMatches(oem) || !signerMatches(oem, OEM_SIGNER)) return false;
            ApplicationInfo info = pm.getApplicationInfo("org.x04g.home", 0);
            File source = new File(info.sourceDir).getCanonicalFile();
            if (!source.isFile() || source.length() != BuildPins.OWN_APK_BYTES
                    || !BuildPins.OWN_APK_SHA.equals(hash(source))) return false;
            PackageInfo signing = pm.getPackageInfo("org.x04g.home", PackageManager.GET_SIGNING_CERTIFICATES);
            if (signing.getLongVersionCode() != 1 || !"0.1.0-candidate".equals(signing.versionName)
                    || !signerMatches(signing, BuildPins.OWN_SIGNER_SHA)) return false;
            // Fail closed if an update changed the active path during the file/hash checks.
            File after = new File(pm.getApplicationInfo("org.x04g.home", 0).sourceDir).getCanonicalFile();
            if (!source.equals(after) || !activeOemMatches(pm)) return false;
            oemIdentityVerified = true;
            companionVerified = true;
            return true;
        } catch (Exception failure) { return false; }
    }

    private static boolean uidMatches() {
        return runtimeExpectedUid >= 10000 && runtimeExpectedUid <= 19999
                && Process.myUid() == runtimeExpectedUid;
    }

    private static boolean activeOemMatches(PackageManager pm) throws Exception {
        ApplicationInfo info = pm.getApplicationInfo(OEM_PACKAGE, 0);
        PackageInfo version = pm.getPackageInfo(OEM_PACKAGE, 0);
        return uidMatches() && info.uid == runtimeExpectedUid && oemVersionMatches(version)
                && new File(info.sourceDir).getCanonicalFile().equals(APK.getCanonicalFile());
    }

    private static boolean oemVersionMatches(PackageInfo info) {
        return info.getLongVersionCode() == OEM_VERSION_CODE && OEM_VERSION_NAME.equals(info.versionName);
    }

    private static boolean signerMatches(PackageInfo info, String expected) throws Exception {
        if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) return false;
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        return signers != null && signers.length == 1 && expected.equals(hex(
                MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())));
    }

    private static boolean stockHashMatches() throws Exception {
        if (!APK.isFile() || APK.length() != 33126600) return false;
        return SHA.equals(hash(APK));
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (FileInputStream stream = new FileInputStream(file)) {
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] digest) {
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest) {
            int n = value & 255;
            actual.append(Character.forDigit(n >>> 4, 16)).append(Character.forDigit(n & 15, 16));
        }
        return actual.toString();
    }
}
