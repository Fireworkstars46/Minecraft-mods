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
    private TextView restoreBubble;
    private WindowManager.LayoutParams targetParams, controlParams, restoreParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean moveMode = true;
    private boolean tapInProgress = false;
    private long intervalMs = 30000L;
    private boolean targetVisible = true, controlVisible = true;
    private int targetSizeDp = 58, controlScale = 100;
    private Button startStop, moveButton;

    private final Runnable tapLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            performTargetTap(false);
            handler.postDelayed(this, Math.max(1L, intervalMs));
        }
    };

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            loadSettings();
            applyOverlaySettings();
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
        createRestoreBubble();
        applyOverlaySettings();
        IntentFilter filter = new IntentFilter(MainActivity.ACTION_RELOAD);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(reloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(reloadReceiver, filter);
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        intervalMs = Math.max(1L, p.getLong("interval_ms", 30000L));
        targetSizeDp = Math.max(30, Math.min(140, p.getInt("target_size_dp", 58)));
        controlScale = Math.max(60, Math.min(160, p.getInt("control_scale", 100)));
        targetVisible = p.getBoolean("target_visible", true);
        controlVisible = p.getBoolean("control_visible", true);
    }

    private GradientDrawable circleBackground(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        d.setStroke(dp(2), stroke);
        return d;
    }

    private void createTarget() {
        if (target != null) return;
        target = new TextView(this);
        target.setText("+");
        target.setTextColor(Color.WHITE);
        target.setTypeface(Typeface.DEFAULT_BOLD);
        target.setGravity(Gravity.CENTER);
        target.setBackground(circleBackground(0x161976D2, 0xCC42A5F5));
        target.setAlpha(0.9f);

        int size = dp(targetSizeDp);
        targetParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        targetParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        targetParams.x = p.getInt("target_x", dp(250));
        targetParams.y = p.getInt("target_y", dp(650));
        windowManager.addView(target, targetParams);
    }

    private int scaled(int value) { return dp(Math.max(1, Math.round(value * controlScale / 100f))); }

    private void createControl() {
        if (control != null) return;
        control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setBackgroundColor(0xDD202124);

        TextView dragHandle = new TextView(this);
        dragHandle.setText("≡");
        dragHandle.setTextColor(Color.WHITE);
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setBackgroundColor(0xFF3C4043);
        control.addView(dragHandle, new LinearLayout.LayoutParams(scaled(42), scaled(46)));

        startStop = new Button(this);
        startStop.setText(running ? "STOP" : "START");
        startStop.setOnClickListener(v -> toggleRunning());
        control.addView(startStop, new LinearLayout.LayoutParams(scaled(78), scaled(46)));

        moveButton = new Button(this);
        moveButton.setText(moveMode ? "LOCK" : "MOVE");
        moveButton.setOnClickListener(v -> setMoveMode(!moveMode));
        control.addView(moveButton, new LinearLayout.LayoutParams(scaled(62), scaled(46)));

        TextView targetMover = new TextView(this);
        targetMover.setText("◎");
        targetMover.setTextColor(Color.WHITE);
        targetMover.setTextSize(20);
        targetMover.setGravity(Gravity.CENTER);
        targetMover.setBackgroundColor(0xFF3C4043);
        targetMover.setOnTouchListener(new TargetMoveTouchListener());
        control.addView(targetMover, new LinearLayout.LayoutParams(scaled(44), scaled(46)));

        Button tapNow = new Button(this);
        tapNow.setText("TAP");
        tapNow.setOnClickListener(v -> performTargetTap(true));
        control.addView(tapNow, new LinearLayout.LayoutParams(scaled(54), scaled(46)));

        Button targetToggle = new Button(this);
        targetToggle.setText("J");
        targetToggle.setOnClickListener(v -> {
            targetVisible = !targetVisible;
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("target_visible", targetVisible).apply();
            if (target != null) target.setVisibility(targetVisible ? View.VISIBLE : View.GONE);
        });
        control.addView(targetToggle, new LinearLayout.LayoutParams(scaled(42), scaled(46)));

        Button close = new Button(this);
        close.setText("×");
        close.setOnClickListener(v -> hideControls());
        control.addView(close, new LinearLayout.LayoutParams(scaled(42), scaled(46)));

        controlParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
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

    private void createRestoreBubble() {
        if (restoreBubble != null) return;
        restoreBubble = new TextView(this);
        restoreBubble.setText("+");
        restoreBubble.setTextColor(Color.WHITE);
        restoreBubble.setTextSize(22);
        restoreBubble.setGravity(Gravity.CENTER);
        restoreBubble.setTypeface(Typeface.DEFAULT_BOLD);
        restoreBubble.setBackground(circleBackground(0xDD3C4043, 0xFFFFFFFF));

        int size = dp(44);
        restoreParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        restoreParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        restoreParams.x = prefs.getInt("restore_x", dp(8));
        restoreParams.y = prefs.getInt("restore_y", dp(180));
        restoreBubble.setOnTouchListener(new BubbleTouchListener());
        windowManager.addView(restoreBubble, restoreParams);
    }

    private void hideControls() {
        controlVisible = false;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("control_visible", false).apply();
        if (control != null) control.setVisibility(View.GONE);
        if (restoreBubble != null) restoreBubble.setVisibility(View.VISIBLE);
    }

    private void showControls() {
        controlVisible = true;
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("control_visible", true).apply();
        if (control != null) control.setVisibility(View.VISIBLE);
        if (restoreBubble != null) restoreBubble.setVisibility(View.GONE);
    }

    private void applyOverlaySettings() {
        if (target != null && targetParams != null) {
            int size = dp(targetSizeDp);
            targetParams.width = size;
            targetParams.height = size;
            target.setTextSize(Math.max(12, targetSizeDp / 3f));
            target.setVisibility(targetVisible ? View.VISIBLE : View.GONE);
            targetParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { windowManager.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
        }
        if (control != null) {
            try { windowManager.removeView(control); } catch (Exception ignored) { }
            control = null;
            startStop = null;
            moveButton = null;
            createControl();
            control.setVisibility(controlVisible ? View.VISIBLE : View.GONE);
        }
        if (restoreBubble != null) restoreBubble.setVisibility(controlVisible ? View.GONE : View.VISIBLE);
    }

    private void setMoveMode(boolean enabled) {
        moveMode = enabled;
        if (moveButton != null) moveButton.setText(moveMode ? "LOCK" : "MOVE");
    }

    private void toggleRunning() {
        running = !running;
        tapInProgress = false;
        if (startStop != null) startStop.setText(running ? "STOP" : "START");
        handler.removeCallbacks(tapLoop);
        if (running) handler.postDelayed(tapLoop, 150L);
    }

    private void performTargetTap(boolean manual) {
        if (target == null || targetParams == null || windowManager == null || tapInProgress) return;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        final float x = location[0] + target.getWidth() / 2f;
        final float y = location[1] + target.getHeight() / 2f;
        tapInProgress = true;

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0L, 10L);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { tapInProgress = false; }
            @Override public void onCancelled(GestureDescription g) { tapInProgress = false; }
        }, null);
        if (!accepted) tapInProgress = false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(reloadReceiver); } catch (Exception ignored) { }
        if (windowManager != null) {
            if (target != null) try { windowManager.removeView(target); } catch (Exception ignored) { }
            if (control != null) try { windowManager.removeView(control); } catch (Exception ignored) { }
            if (restoreBubble != null) try { windowManager.removeView(restoreBubble); } catch (Exception ignored) { }
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

    private class TargetMoveTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;

        @Override public boolean onTouch(View v, MotionEvent event) {
            if (!moveMode || running || targetParams == null || target == null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = targetParams.x;
                    startY = targetParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    targetParams.x = startX + Math.round(event.getRawX() - downX);
                    targetParams.y = startY + Math.round(event.getRawY() - downY);
                    try { windowManager.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                            .putInt("target_x", targetParams.x).putInt("target_y", targetParams.y).apply();
                    return true;
            }
            return true;
        }
    }

    private class BubbleTouchListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = restoreParams.x;
                    startY = restoreParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                    restoreParams.x = startX + Math.round(dx);
                    restoreParams.y = startY + Math.round(dy);
                    try { windowManager.updateViewLayout(restoreBubble, restoreParams); } catch (Exception ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                    getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                            .putInt("restore_x", restoreParams.x).putInt("restore_y", restoreParams.y).apply();
                    if (!moved) showControls();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }
    }
}
