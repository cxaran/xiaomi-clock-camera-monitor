package org.x04g.bootstrap;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Startup-only read-only diagnostics. One MAIN callback at a time, never a permanent poller. */
final class RuntimeDiagnostics {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicReference<Sample> PENDING = new AtomicReference<Sample>();
    static final long WINDOW_MS = 120000, PERIOD_MS = 5000, MAIN_TIMEOUT_MS = 2000;
    static final int MAX_SAMPLES = 25;
    private RuntimeDiagnostics() {}

    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread worker = new Thread(new Runnable() { public void run() { collect(); } }, "X04G-runtime-diagnostic");
        worker.setDaemon(true); worker.start();
    }

    private static void collect() {
        final long began = SystemClock.elapsedRealtime(), deadline = began + WINDOW_MS;
        long next = began;
        try {
            for (int index = 0; index < MAX_SAMPLES; index++) {
                long now = SystemClock.elapsedRealtime();
                if (now < next) Thread.sleep(Math.min(next - now, Math.max(0, deadline - now)));
                now = SystemClock.elapsedRealtime();
                if (now >= deadline) break;
                next = now + PERIOD_MS; // No catch-up bursts if a sample or MAIN was slow.
                Looper looper = Looper.getMainLooper();
                if (looper == null) { emit(index, now - began, "MAIN_NOT_READY", null, null); continue; }
                Handler handler = new Handler(looper);
                Sample sample = new Sample(handler, deadline);
                if (!PENDING.compareAndSet(null, sample)) {
                    emit(index, now - began, "MAIN_BUSY", null, null); continue;
                }
                if (!handler.post(sample)) {
                    sample.cancel(); emit(index, now - began, "MAIN_POST_REJECTED", null, null); continue;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                boolean completed = remaining > 0 && sample.done.await(Math.min(MAIN_TIMEOUT_MS, remaining), TimeUnit.MILLISECONDS);
                if (!completed) sample.cancel();
                if (SystemClock.elapsedRealtime() >= deadline) break;
                emit(index, SystemClock.elapsedRealtime() - began,
                    completed ? sample.readState : "MAIN_TIMEOUT", sample.bootstrap, sample.carousel);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // No exception names, messages, causes, object dumps or fallback JSON.
        } finally {
            Sample sample = PENDING.get();
            if (sample != null) sample.cancel();
        }
    }

    private static void emit(int index, long elapsed, String state, JSONObject bootstrap, JSONObject carousel) {
        try { Log.i("X04GRuntime", DiagnosticRules.record(index, elapsed, state, bootstrap, carousel).toString()); }
        catch (Exception ignored) { /* Never log raw input, failures or partial records. */ }
    }

    private static final class Sample implements Runnable {
        final Handler handler;
        final long deadline;
        final AtomicBoolean valid = new AtomicBoolean(true), entered = new AtomicBoolean();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String readState = "READ_FAILED";
        volatile JSONObject bootstrap, carousel;
        Sample(Handler handler, long deadline) { this.handler = handler; this.deadline = deadline; }
        public void run() {
            entered.set(true);
            try {
                if (!allowed() || Looper.myLooper() != Looper.getMainLooper()) return;
                bootstrap = DiagnosticRules.bootstrap(invoke("org.x04g.home.integration.Bootstrap", "diagnosticState"));
                if (!allowed()) return;
                carousel = DiagnosticRules.carousel(invoke("org.x04g.home.integration.OemCarousel", "diagnosticSnapshot"));
                if (allowed()) readState = "OK";
            } catch (Throwable ignored) {
                bootstrap = null; carousel = null; readState = "READ_FAILED";
            } finally {
                PENDING.compareAndSet(this, null); done.countDown();
            }
        }
        boolean allowed() { return valid.get() && SystemClock.elapsedRealtime() < deadline; }
        void cancel() {
            valid.set(false); handler.removeCallbacks(this);
            // Keep a running getter reserved until finally, so a timed-out callback cannot accumulate.
            if (!entered.get()) PENDING.compareAndSet(this, null);
        }
        String invoke(String className, String name) throws Exception {
            if (!allowed()) throw new IllegalStateException();
            Class<?> target = Class.forName(className, false, StockBootstrap.class.getClassLoader());
            Method method = target.getMethod(name);
            if (method.getReturnType() != String.class || !Modifier.isStatic(method.getModifiers()))
                throw new NoSuchMethodException();
            Object value = method.invoke(null);
            if (!(value instanceof String)) throw new IllegalStateException();
            return (String)value;
        }
    }
}
