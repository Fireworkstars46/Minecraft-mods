package com.garrett.appvolumestepper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import org.joor.Reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.ShizukuProvider;

public final class SamsungAppVolumeManager {
    public enum Status {
        NOT_SAMSUNG,
        SHIZUKU_MISSING,
        SHIZUKU_STOPPED,
        PERMISSION_NEEDED,
        READY,
        ERROR
    }

    public interface Listener {
        void onChanged();
    }

    private static final int REQUEST_CODE = 4603;

    private final Context context;
    private final AudioManager audioManager;
    private final Object audioBinderLock = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Status status = Status.SHIZUKU_STOPPED;
    private volatile String lastError = "";
    private volatile String lastTargetPackage = "";
    private volatile String lastTargetLabel = "";
    private volatile int lastKnownPercent = -1;

    private Method getAppVolumeMethod;
    private Method setAppVolumeMethod;
    private Object audioServiceProxy;

    public SamsungAppVolumeManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = this.context.getSystemService(AudioManager.class);

        Shizuku.addBinderReceivedListenerSticky(this::refreshState);
        Shizuku.addBinderDeadListener(() -> {
            status = Status.SHIZUKU_STOPPED;
            notifyListeners();
        });
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == REQUEST_CODE) refreshState();
        });

        ShizukuProvider.requestBinderForNonProviderProcess(this.context);
        refreshState();
    }

    public Status getStatus() { return status; }
    public String getLastError() { return lastError; }
    public int getLastKnownPercent() { return lastKnownPercent; }
    public String getLastTargetPackage() { return lastTargetPackage; }
    public String getLastTargetLabel() { return lastTargetLabel; }
    public boolean isReady() { return status == Status.READY; }

    public void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void requestPermission() {
        try {
            if (!isSamsung()) {
                status = Status.NOT_SAMSUNG;
                notifyListeners();
                return;
            }
            if (!isShizukuInstalled()) {
                status = Status.SHIZUKU_MISSING;
                notifyListeners();
                return;
            }
            if (!Shizuku.pingBinder()) {
                status = Status.SHIZUKU_STOPPED;
                notifyListeners();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                initializeNativeApi();
            } else {
                status = Status.PERMISSION_NEEDED;
                notifyListeners();
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    public synchronized int refreshPercent(String packageName) {
        if (status != Status.READY || packageName == null || packageName.isEmpty()) return -1;
        try {
            int uid = resolveUid(packageName);
            int value = callGetAppVolume(uid);
            if (value < 0 || value > 100) {
                throw new IllegalStateException("Samsung returned app volume " + value);
            }
            rememberTarget(packageName, value);
            lastError = "";
            notifyListeners();
            return value;
        } catch (Throwable t) {
            fail(t);
            return -1;
        }
    }

    public synchronized int step(String packageName, int direction) {
        if (status != Status.READY || packageName == null || packageName.isEmpty()) return -1;
        try {
            int uid = resolveUid(packageName);
            int current = callGetAppVolume(uid);
            if (current < 0 || current > 100) {
                throw new IllegalStateException("Samsung returned app volume " + current);
            }
            int target = clamp(current + (direction >= 0 ? 1 : -1), 0, 100);
            if (target != current) callSetAppVolume(uid, target);
            int verified = callGetAppVolume(uid);
            if (verified < 0 || verified > 100) verified = target;
            rememberTarget(packageName, verified);
            lastError = "";
            notifyListeners();
            return verified;
        } catch (Throwable t) {
            fail(t);
            return -1;
        }
    }

    public synchronized int setPercent(String packageName, int percent) {
        if (status != Status.READY || packageName == null || packageName.isEmpty()) return -1;
        try {
            int uid = resolveUid(packageName);
            int target = clamp(percent, 0, 100);
            callSetAppVolume(uid, target);
            int verified = callGetAppVolume(uid);
            if (verified < 0 || verified > 100) verified = target;
            rememberTarget(packageName, verified);
            lastError = "";
            notifyListeners();
            return verified;
        } catch (Throwable t) {
            fail(t);
            return -1;
        }
    }

    public synchronized void rememberTargetOnly(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            resolveUid(packageName);
            lastTargetPackage = packageName;
            lastTargetLabel = labelFor(packageName);
            notifyListeners();
        } catch (Throwable ignored) {
        }
    }

    private void refreshState() {
        try {
            if (!isSamsung()) {
                status = Status.NOT_SAMSUNG;
                notifyListeners();
                return;
            }
            if (!isShizukuInstalled()) {
                status = Status.SHIZUKU_MISSING;
                notifyListeners();
                return;
            }
            if (!Shizuku.pingBinder()) {
                status = Status.SHIZUKU_STOPPED;
                notifyListeners();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status = Status.PERMISSION_NEEDED;
                notifyListeners();
                return;
            }
            initializeNativeApi();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private synchronized void initializeNativeApi() {
        try {
            getAppVolumeMethod = findMethod(AudioManager.class, "getAppVolume", int.class);
            setAppVolumeMethod = findMethod(AudioManager.class, "setAppVolume", int.class, int.class);
            getAppVolumeMethod.setAccessible(true);
            setAppVolumeMethod.setAccessible(true);

            Method getService = findMethod(AudioManager.class, "getService");
            getService.setAccessible(true);
            audioServiceProxy = getService.invoke(null);
            if (audioServiceProxy == null) throw new IllegalStateException("Audio service unavailable");

            status = Status.READY;
            lastError = "";
            notifyListeners();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private int callGetAppVolume(int uid) throws Throwable {
        Object result = withShizukuAudioBinder(() -> invokeUnwrapped(getAppVolumeMethod, audioManager, uid));
        if (!(result instanceof Number)) throw new IllegalStateException("getAppVolume returned no number");
        return ((Number) result).intValue();
    }

    private void callSetAppVolume(int uid, int value) throws Throwable {
        withShizukuAudioBinder(() -> {
            invokeUnwrapped(setAppVolumeMethod, audioManager, uid, value);
            return null;
        });
    }

    private Object withShizukuAudioBinder(ThrowingSupplier operation) throws Throwable {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shizuku is not ready");
        }
        synchronized (audioBinderLock) {
            Reflect service = Reflect.on(audioServiceProxy);
            IBinder original = service.get("mRemote");
            boolean replace = !(original instanceof ShizukuBinderWrapper);
            if (replace) service.set("mRemote", new ShizukuBinderWrapper(original));
            try {
                return operation.get();
            } finally {
                if (replace) service.set("mRemote", original);
            }
        }
    }

    private int resolveUid(String packageName) throws PackageManager.NameNotFoundException {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
        return info.uid;
    }

    private void rememberTarget(String packageName, int percent) {
        lastTargetPackage = packageName;
        lastTargetLabel = labelFor(packageName);
        lastKnownPercent = percent;
    }

    private String labelFor(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (Throwable ignored) {
            return packageName;
        }
    }

    private boolean isSamsung() {
        String manufacturer = Build.MANUFACTURER;
        return manufacturer != null && manufacturer.toLowerCase().contains("samsung");
    }

    private boolean isShizukuInstalled() {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void fail(Throwable t) {
        status = Status.ERROR;
        lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try { listener.onChanged(); } catch (Throwable ignored) { }
        }
    }

    private static Object invokeUnwrapped(Method method, Object receiver, Object... args) throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause == null ? e : cause;
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... types) throws NoSuchMethodException {
        Class<?> current = cls;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private interface ThrowingSupplier {
        Object get() throws Throwable;
    }
}
