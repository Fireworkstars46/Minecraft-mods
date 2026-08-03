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
import android.graphics.drawable.GradientDrawable;
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
    private WindowManager.LayoutParams targetParams, controlParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private String currentPackage = "";
    private long intervalMs = 30000L;
    private boolean robloxOnly = true;
    private boolean targetVisible = true, controlVisible = true;
    private int targetSizeDp = 58, controlScale = 100;
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
            applyOverlaySettings();
            if (running) { handler.removeCallbacks(tapLoop); handler.post(tapLoop); }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadSettings();
        createTarget();
        createControl();
        applyOverlaySettings();
        IntentFilter filter = new IntentFilter(MainActivity.ACTION_RELOAD);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(reloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(reloadReceiver, filter);
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        intervalMs = Math.max(1L, p.getLong("interval_ms", 30000L));
        robloxOnly = p.getBoolean("roblox_only", true);
        targetSizeDp = Math.max(30, Math.min(140, p.getInt("target_size_dp", 58)));
        controlScale = Math.max(60, Math.min(160, p.getInt("control_scale", 100)));
        targetVisible = p.getBoolean("target_visible", true);
        controlVisible = p.getBoolean("control_visible", true);
    }

    private GradientDrawable circleBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xCC1976D2);
        d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
    }

    private void createTarget() {
        if (target != null) return;
        target = new TextView(this);
        target.setText("JUMP");
        target.setTextColor(Color.WHITE);
        target.setTypeface(Typeface.DEFAULT_BOLD);
        target.setGravity(Gravity.CENTER);
        target.setBackground(circleBackground());
        target.setAlpha(0.9f);

        int size = dp(targetSizeDp);
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

    private int scaled(int dp) { return this.dp(Math.max(1, Math.round(dp * controlScale / 100f))); }

    private void createControl() {
        if (control != null) return;
        control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setBackgroundColor(0xDD202124);

        TextView dragHandle = new TextView(this);
        dragHandle.setText("≡"); dragHandle.setTextColor(Color.WHITE); dragHandle.setGravity(Gravity.CENTER); dragHandle.setBackgroundColor(0xFF3C4043);
        control.addView(dragHandle, new LinearLayout.LayoutParams(scaled(46), scaled(46)));

        startStop = new Button(this);
        startStop.setText("START"); startStop.setOnClickListener(v -> toggleRunning());
        control.addView(startStop, new LinearLayout.LayoutParams(scaled(84), scaled(46)));

        Button targetToggle = new Button(this);
        targetToggle.setText("J");
        targetToggle.setOnClickListener(v -> {
            targetVisible = !targetVisible;
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("target_visible", targetVisible).apply();
            target.setVisibility(targetVisible ? View.VISIBLE : View.GONE);
        });
        control.addView(targetToggle, new LinearLayout.LayoutParams(scaled(46), scaled(46)));

        Button close = new Button(this);
        close.setText("×");
        close.setOnClickListener(v -> {
            controlVisible = false;
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("control_visible", false).apply();
            control.setVisibility(View.GONE);
        });
        control.addView(close, new LinearLayout.LayoutParams(scaled(46), scaled(46)));

        controlParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        controlParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        controlParams.x = prefs.getInt("control_x", dp(12));
        controlParams.y = prefs.getInt("control_y", dp(120));
        dragHandle.setOnTouchListener(new DragTouchListener(control, controlParams, "control_x", "control_y"));
        windowManager.addView(control, controlParams);
    }

    private void applyOverlaySettings() {
        if (target != null && targetParams != null) {
            int size = dp(targetSizeDp);
            targetParams.width = size; targetParams.height = size;
            target.setTextSize(Math.max(8, targetSizeDp / 5f));
            target.setVisibility(targetVisible ? View.VISIBLE : View.GONE);
            try { windowManager.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
        }
        if (control != null) {
            control.setVisibility(controlVisible ? View.VISIBLE : View.GONE);
            if (controlVisible) {
                try { windowManager.removeView(control); } catch (Exception ignored) { }
                control = null; startStop = null; createControl();
            }
        }
    }

    private void toggleRunning() {
        running = !running;
        if (running) { startStop.setText("STOP"); handler.removeCallbacks(tapLoop); handler.post(tapLoop); }
        else { startStop.setText("START"); handler.removeCallbacks(tapLoop); }
    }

    private void performTargetTap() {
        if (target == null || !targetVisible || target.getVisibility() != View.VISIBLE) return;
        final float x = targetParams.x + target.getWidth() / 2f;
        final float y = targetParams.y + target.getHeight() / 2f;
        target.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            Path path = new Path(); path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 1);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { if (target != null && targetVisible) target.setVisibility(View.VISIBLE); }
                @Override public void onCancelled(GestureDescription g) { if (target != null && targetVisible) target.setVisibility(View.VISIBLE); }
            }, null);
        }, 1);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { if (event.getPackageName() != null) currentPackage = event.getPackageName().toString(); }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        running = false; handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(reloadReceiver); } catch (Exception ignored) { }
        if (windowManager != null) {
            if (target != null) try { windowManager.removeView(target); } catch (Exception ignored) { }
            if (control != null) try { windowManager.removeView(control); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private class DragTouchListener implements View.OnTouchListener {
        private final View movedView; private final WindowManager.LayoutParams params; private final String xKey, yKey;
        private int startX, startY; private float downX, downY;
        DragTouchListener(View movedView, WindowManager.LayoutParams params, String xKey, String yKey) {
            this.movedView = movedView; this.params = params; this.xKey = xKey; this.yKey = yKey;
        }
        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x; startY = params.y; downX = event.getRawX(); downY = event.getRawY(); return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + Math.round(event.getRawX() - downX); params.y = startY + Math.round(event.getRawY() - downY);
                    try { windowManager.updateViewLayout(movedView, params); } catch (Exception ignored) { } return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putInt(xKey, params.x).putInt(yKey, params.y).apply(); return true;
            }
            return true;
        }
    }
}
