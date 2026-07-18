package com.fireworkstars46.signalmirror;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.Executor;

public class MirrorAccessibilityService extends AccessibilityService {
    static volatile MirrorAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String foregroundPackage = "";
    private boolean captureRunning;
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            int seconds = SignalUtils.prefs(MirrorAccessibilityService.this)
                    .getInt(SignalUtils.KEY_INTERVAL, 15);
            if (isLauncher(foregroundPackage)) captureAndUpdate(false, null);
            handler.postDelayed(this, Math.max(5, seconds) * 1000L);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        restartLoop();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            foregroundPackage = event.getPackageName().toString();
            if (isLauncher(foregroundPackage))
                handler.postDelayed(() -> captureAndUpdate(false, null), 450L);
        }
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    void restartLoop() {
        handler.removeCallbacks(periodic);
        handler.postDelayed(periodic, 1200L);
    }

    void captureForCalibration(SignalUtils.CaptureCallback callback) {
        captureRaw(callback);
    }

    void captureAndUpdate(boolean force, SignalUtils.CaptureCallback callback) {
        if (!force && !isLauncher(foregroundPackage)) return;
        captureRaw(new SignalUtils.CaptureCallback() {
            @Override public void onSuccess(Bitmap screenshot) {
                SharedPreferences p = SignalUtils.prefs(MirrorAccessibilityService.this);
                Bitmap icon = SignalUtils.extractIcon(screenshot,
                        p.getFloat(SignalUtils.KEY_X, SignalUtils.DEFAULT_X),
                        p.getFloat(SignalUtils.KEY_Y, SignalUtils.DEFAULT_Y),
                        p.getFloat(SignalUtils.KEY_W, SignalUtils.DEFAULT_W),
                        p.getFloat(SignalUtils.KEY_H, SignalUtils.DEFAULT_H));
                screenshot.recycle();
                SignalUtils.saveIcon(MirrorAccessibilityService.this, icon);
                SignalUtils.updateAllWidgets(MirrorAccessibilityService.this, icon);
                icon.recycle();
                if (callback != null) callback.onSuccess(null);
            }
            @Override public void onError(String message) {
                if (callback != null) callback.onError(message);
            }
        });
    }

    private void captureRaw(SignalUtils.CaptureCallback callback) {
        if (captureRunning) {
            if (callback != null) callback.onError("A capture is already running. Try again in a moment.");
            return;
        }
        captureRunning = true;
        Executor executor = getMainExecutor();
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                captureRunning = false;
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                Bitmap copy = wrapped == null ? null : wrapped.copy(Bitmap.Config.ARGB_8888, false);
                buffer.close();
                if (copy == null) {
                    if (callback != null) callback.onError("Android returned an empty screenshot.");
                } else if (callback != null) callback.onSuccess(copy);
                else copy.recycle();
            }
            @Override public void onFailure(int errorCode) {
                captureRunning = false;
                if (callback != null) callback.onError(
                        "Could not capture the status bar (error " + errorCode + "). Wait a second and try again.");
            }
        });
    }

    private static boolean isLauncher(String packageName) {
        if (packageName == null) return false;
        return packageName.equals("com.sec.android.app.launcher")
                || packageName.equals("com.android.launcher3")
                || packageName.equals("com.google.android.apps.nexuslauncher")
                || packageName.endsWith(".launcher");
    }
}
