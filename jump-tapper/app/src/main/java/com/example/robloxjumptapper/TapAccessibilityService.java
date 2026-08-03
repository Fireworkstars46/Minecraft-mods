package com.example.robloxjumptapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TapAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private TextView target;
    private LinearLayout control;
    private WindowManager.LayoutParams targetParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private String currentPackage = "";
    private long intervalMs = 30000L;
    private boolean robloxOnly = true;
    private Button startStop;

    private final Runnable tapLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!robloxOnly || "com.roblox.client".equals(currentPackage)) performTargetTap();
            handler.postDelayed(this, Math.max(1L, intervalMs));
        }
    };

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            loadSettings();
            if (running) {
                handler.removeCallbacks(tapLoop);
                handler.post(tapLoop);
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadSettings();
        createTarget();
        createControl();
        IntentFilter filter = new IntentFilter(MainActivity.ACTION_RELOAD);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(reloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(reloadReceiver, filter);
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        intervalMs = Math.max(1L, p.getLong("interval_ms", 30000L));
        robloxOnly = p.getBoolean("roblox_only", true);
    }

    private void createTarget() {
        if (target != null) return;
        target = new TextView(this);
        target.setText("JUMP");
        target.setTextColor(Color.WHITE);
        target.setTextSize(11);
        target.setTypeface(Typeface.DEFAULT_BOLD);
        target.setGravity(Gravity.CENTER);
        target.setBackgroundColor(0xCC1976D2);
        target.setAlpha(0.85f);

        int size = dp(58);
        targetParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        targetParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        targetParams.x = p.getInt("target_x", dp(250));
        targetParams.y = p.getInt("target_y", dp(650));
        target.setOnTouchListener(new DragTouchListener(target, targetParams, "target_x", "target_y"));
        windowManager.addView(target, targetParams);
    }

    private void createControl() {
        if (control != null) return;
        control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(dp(3), dp(2), dp(3), dp(2));
        control.setBackgroundColor(0xDD202124);

        TextView dragHandle = new TextView(this);
        dragHandle.setText("≡");
        dragHandle.setTextColor(Color.WHITE);
        dragHandle.setTextSize(24);
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setBackgroundColor(0xFF3C4043);
        control.addView(dragHandle, new LinearLayout.LayoutParams(dp(46), dp(46)));

        startStop = new Button(this);
        startStop.setText("START");
        startStop.setTextSize(11);
        startStop.setOnClickListener(v -> toggleRunning());
        control.addView(startStop, new LinearLayout.LayoutParams(dp(84), dp(46)));

        Button hide = new Button(this);
        hide.setText("–");
        hide.setTextSize(18);
        hide.setOnClickListener(v -> {
            boolean visible = target.getVisibility() == View.VISIBLE;
            target.setVisibility(visible ? View.INVISIBLE : View.VISIBLE);
            hide.setText(visible ? "+" : "–");
        });
        control.addView(hide, new LinearLayout.LayoutParams(dp(48), dp(46)));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        p.x = prefs.getInt("control_x", dp(12));
        p.y = prefs.getInt("control_y", dp(120));

        dragHandle.setOnTouchListener(new DragTouchListener(control, p, "control_x", "control_y"));
        windowManager.addView(control, p);
    }

    private void toggleRunning() {
        running = !running;
        if (running) {
            startStop.setText("STOP");
            handler.removeCallbacks(tapLoop);
            handler.post(tapLoop);
        } else {
            startStop.setText("START");
            handler.removeCallbacks(tapLoop);
        }
    }

    private void performTargetTap() {
        if (target == null || target.getVisibility() != View.VISIBLE) return;
        final float x = targetParams.x + target.getWidth() / 2f;
        final float y = targetParams.y + target.getHeight() / 2f;
        target.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 1);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { if (target != null) target.setVisibility(View.VISIBLE); }
                @Override public void onCancelled(GestureDescription g) { if (target != null) target.setVisibility(View.VISIBLE); }
            }, null);
        }, 1);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null) currentPackage = event.getPackageName().toString();
    }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(reloadReceiver); } catch (Exception ignored) { }
        if (windowManager != null) {
            if (target != null) try { windowManager.removeView(target); } catch (Exception ignored) { }
            if (control != null) try { windowManager.removeView(control); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private class DragTouchListener implements View.OnTouchListener {
        private final View movedView;
        private final WindowManager.LayoutParams params;
        private final String xKey, yKey;
        private int startX, startY;
        private float downX, downY;

        DragTouchListener(View movedView, WindowManager.LayoutParams params, String xKey, String yKey) {
            this.movedView = movedView;
            this.params = params;
            this.xKey = xKey;
            this.yKey = yKey;
        }

        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + Math.round(event.getRawX() - downX);
                    params.y = startY + Math.round(event.getRawY() - downY);
                    try { windowManager.updateViewLayout(movedView, params); } catch (Exception ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                            .putInt(xKey, params.x).putInt(yKey, params.y).apply();
                    return true;
            }
            return true;
        }
    }
}
