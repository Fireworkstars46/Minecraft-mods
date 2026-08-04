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
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TapAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private TextView target;
    private LinearLayout control, controlButtons;
    private WindowManager.LayoutParams targetParams, controlParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running, tapInProgress;
    private boolean moveMode = true, targetVisible = true, controlVisible = true, controlCollapsed, debugEnabled = true;
    private long intervalMs = 30000L, tapSequence, activeTapStartedElapsed;
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
            log("SETTINGS_RELOAD intervalMs=" + intervalMs + " debug=" + debugEnabled);
            applyOverlaySettings();
            if (running) { handler.removeCallbacks(tapLoop); handler.post(tapLoop); }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadSettings();
        log("SERVICE_CONNECTED sdk=" + Build.VERSION.SDK_INT);
        createTarget(); createControl(); applyOverlaySettings();
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
        controlCollapsed = p.getBoolean("control_collapsed", false);
        debugEnabled = p.getBoolean("debug_enabled", true);
    }

    private GradientDrawable circleBackground(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setShape(GradientDrawable.OVAL);
        d.setColor(fill); d.setStroke(dp(2), stroke); return d;
    }

    private void createTarget() {
        if (target != null) return;
        target = new TextView(this); target.setText("+"); target.setTextColor(Color.WHITE);
        target.setTypeface(Typeface.DEFAULT_BOLD); target.setGravity(Gravity.CENTER);
        target.setBackground(circleBackground(0x161976D2, 0xCC42A5F5)); target.setAlpha(0.9f);
        int size = dp(targetSizeDp);
        targetParams = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        targetParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        targetParams.x = p.getInt("target_x", dp(250)); targetParams.y = p.getInt("target_y", dp(650));
        target.setOnTouchListener(new DragTouchListener(target, targetParams, "target_x", "target_y"));
        windowManager.addView(target, targetParams);
    }

    private int scaled(int value) { return dp(Math.max(1, Math.round(value * controlScale / 100f))); }

    private void createControl() {
        if (control != null) return;
        control = new LinearLayout(this); control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL); control.setBackgroundColor(0xDD202124);

        TextView menuHandle = new TextView(this);
        menuHandle.setText("≡"); menuHandle.setTextColor(Color.WHITE); menuHandle.setTextSize(22);
        menuHandle.setGravity(Gravity.CENTER); menuHandle.setBackgroundColor(0xFF3C4043);
        menuHandle.setOnTouchListener(new MenuHandleTouchListener());
        control.addView(menuHandle, new LinearLayout.LayoutParams(scaled(46), scaled(46)));

        controlButtons = new LinearLayout(this); controlButtons.setOrientation(LinearLayout.HORIZONTAL);
        controlButtons.setGravity(Gravity.CENTER_VERTICAL);

        startStop = new Button(this); startStop.setText(running ? "STOP" : "START");
        startStop.setOnClickListener(v -> toggleRunning());
        controlButtons.addView(startStop, new LinearLayout.LayoutParams(scaled(84), scaled(46)));

        moveButton = new Button(this); moveButton.setText(moveMode ? "LOCK" : "MOVE");
        moveButton.setOnClickListener(v -> setMoveMode(!moveMode));
        controlButtons.addView(moveButton, new LinearLayout.LayoutParams(scaled(66), scaled(46)));

        Button tapNow = new Button(this); tapNow.setText("TAP");
        tapNow.setOnClickListener(v -> performTargetTap(true));
        controlButtons.addView(tapNow, new LinearLayout.LayoutParams(scaled(58), scaled(46)));

        Button targetToggle = new Button(this); targetToggle.setText("J");
        targetToggle.setOnClickListener(v -> {
            targetVisible = !targetVisible;
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("target_visible", targetVisible).apply();
            if (target != null) target.setVisibility(targetVisible ? View.VISIBLE : View.GONE);
            log("TARGET_VISIBLE=" + targetVisible);
        });
        controlButtons.addView(targetToggle, new LinearLayout.LayoutParams(scaled(46), scaled(46)));
        control.addView(controlButtons);

        controlParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        controlParams.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        controlParams.x = prefs.getInt("control_x", dp(12)); controlParams.y = prefs.getInt("control_y", dp(120));
        windowManager.addView(control, controlParams);
        setCollapsed(controlCollapsed, false);
    }

    private void setCollapsed(boolean collapsed, boolean save) {
        controlCollapsed = collapsed;
        if (controlButtons != null) controlButtons.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (save) getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putBoolean("control_collapsed", collapsed).apply();
        if (control != null && controlParams != null) try { windowManager.updateViewLayout(control, controlParams); } catch (Exception ignored) { }
        log("CONTROL_COLLAPSED=" + collapsed);
    }

    private void applyOverlaySettings() {
        if (target != null && targetParams != null) {
            int size = dp(targetSizeDp); targetParams.width = size; targetParams.height = size;
            target.setTextSize(Math.max(12, targetSizeDp / 3f));
            target.setVisibility(targetVisible ? View.VISIBLE : View.GONE); applyTargetTouchability();
        }
        if (control != null) {
            try { windowManager.removeView(control); } catch (Exception ignored) { }
            control = null; controlButtons = null; startStop = null; moveButton = null;
            createControl(); control.setVisibility(controlVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void applyTargetTouchability() {
        if (target == null || targetParams == null || windowManager == null) return;
        if (moveMode && !running) targetParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else targetParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { windowManager.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
    }

    private void setMoveMode(boolean enabled) {
        moveMode = enabled;
        if (moveButton != null) moveButton.setText(moveMode ? "LOCK" : "MOVE");
        applyTargetTouchability(); log("MOVE_MODE=" + moveMode);
    }

    private void toggleRunning() {
        running = !running; tapInProgress = false;
        if (startStop != null) startStop.setText(running ? "STOP" : "START");
        handler.removeCallbacks(tapLoop); applyTargetTouchability();
        log((running ? "START" : "STOP") + " intervalMs=" + intervalMs);
        if (running) handler.post(tapLoop);
    }

    private void performTargetTap(boolean manual) {
        long seq = ++tapSequence;
        if (target == null || targetParams == null || windowManager == null) { log("TAP_SKIP seq=" + seq + " reason=noTarget"); return; }
        if (tapInProgress) { log("TAP_SKIP seq=" + seq + " reason=inProgress"); return; }
        int[] location = new int[2]; target.getLocationOnScreen(location);
        final float x = location[0] + target.getWidth() / 2f, y = location[1] + target.getHeight() / 2f;
        tapInProgress = true; activeTapStartedElapsed = SystemClock.elapsedRealtime();
        targetParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { windowManager.updateViewLayout(target, targetParams); } catch (Exception ignored) { }
        log("TAP_REQUEST seq=" + seq + " manual=" + manual + " x=" + Math.round(x) + " y=" + Math.round(y));

        Path path = new Path(); path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0L, 30L);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { finishTap(seq, false); }
            @Override public void onCancelled(GestureDescription g) { finishTap(seq, true); }
        }, null);
        log("DISPATCH seq=" + seq + " accepted=" + accepted);
        if (!accepted) finishTap(seq, true);
    }

    private void finishTap(long seq, boolean cancelled) {
        long duration = Math.max(0L, SystemClock.elapsedRealtime() - activeTapStartedElapsed);
        tapInProgress = false;
        log((cancelled ? "TAP_CANCEL" : "TAP_COMPLETE") + " seq=" + seq + " durationMs=" + duration);
        if (!running) applyTargetTouchability();
    }

    private void log(String message) {
        if (!debugEnabled) return;
        try {
            File f = new File(getFilesDir(), MainActivity.DEBUG_FILE);
            if (f.exists() && f.length() > 250000) f.delete();
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            FileWriter w = new FileWriter(f, true); w.write(ts + " | " + message + "\n"); w.close();
        } catch (Exception ignored) { }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { log("SERVICE_INTERRUPT"); }
    @Override public void onDestroy() {
        log("SERVICE_DESTROY"); running = false; handler.removeCallbacksAndMessages(null);
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
        DragTouchListener(View movedView, WindowManager.LayoutParams params, String xKey, String yKey) { this.movedView=movedView;this.params=params;this.xKey=xKey;this.yKey=yKey; }
        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: startX=params.x;startY=params.y;downX=event.getRawX();downY=event.getRawY();return true;
                case MotionEvent.ACTION_MOVE: params.x=startX+Math.round(event.getRawX()-downX);params.y=startY+Math.round(event.getRawY()-downY);try{windowManager.updateViewLayout(movedView,params);}catch(Exception ignored){}return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt(xKey,params.x).putInt(yKey,params.y).apply();return true;
            } return true;
        }
    }

    private class MenuHandleTouchListener implements View.OnTouchListener {
        private int startX, startY; private float downX, downY; private long downTime; private boolean dragging;
        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX=controlParams.x; startY=controlParams.y; downX=event.getRawX(); downY=event.getRawY(); downTime=SystemClock.elapsedRealtime(); dragging=false; return true;
                case MotionEvent.ACTION_MOVE:
                    long held=SystemClock.elapsedRealtime()-downTime;
                    float dx=event.getRawX()-downX, dy=event.getRawY()-downY;
                    if (held >= 300L && (Math.abs(dx)>dp(3) || Math.abs(dy)>dp(3))) dragging=true;
                    if (dragging) { controlParams.x=startX+Math.round(dx); controlParams.y=startY+Math.round(dy); try{windowManager.updateViewLayout(control,controlParams);}catch(Exception ignored){} }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("control_x",controlParams.x).putInt("control_y",controlParams.y).apply();
                        log("CONTROL_MOVED x="+controlParams.x+" y="+controlParams.y);
                    } else setCollapsed(!controlCollapsed, true);
                    return true;
                case MotionEvent.ACTION_CANCEL: return true;
            } return true;
        }
    }
}
