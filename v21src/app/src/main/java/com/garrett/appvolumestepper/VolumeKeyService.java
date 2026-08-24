package com.garrett.appvolumestepper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class VolumeKeyService extends AccessibilityService {
    private static final long TRANSIENT_FALLBACK_MS = 6000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastForegroundPackage = "";
    private long lastForegroundSeenAt = 0L;
    private int consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN;

    private WindowManager windowManager;
    private LinearLayout popup;
    private ImageView popupIcon;
    private TextView popupText;
    private ProgressBar popupProgress;
    private final Runnable hidePopup = () -> {
        if (popup != null) popup.setVisibility(View.GONE);
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        createPopup();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(getPackageName()) || isTransientSystemPackage(pkg)) return;
        if (isHomePackage(pkg)) {
            lastForegroundPackage = "";
            lastForegroundSeenAt = 0L;
            return;
        }
        if (isValidTargetPackage(pkg)) {
            rememberForeground(pkg);
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (consumedKeyCode == keyCode) {
                consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                return true;
            }
            return false;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        String target = findForegroundTarget();
        if (target == null || target.isEmpty()) return false;

        SamsungAppVolumeManager manager = App.get().manager();
        if (!manager.isReady()) return false;

        int value = manager.step(target, keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
        if (value < 0) return false;

        consumedKeyCode = keyCode;
        showPopup(target, value);
        showSamsungVolumePanelWithoutChangingMainVolume();
        return true;
    }

    private String findForegroundTarget() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence packageName = root.getPackageName();
                String pkg = packageName == null ? "" : packageName.toString();
                root.recycle();

                if (!pkg.isEmpty() && !pkg.equals(getPackageName()) && !isTransientSystemPackage(pkg)) {
                    if (isHomePackage(pkg)) {
                        lastForegroundPackage = "";
                        lastForegroundSeenAt = 0L;
                        return null;
                    }
                    if (isValidTargetPackage(pkg)) {
                        rememberForeground(pkg);
                        return pkg;
                    }
                    return null;
                }

                if (isTransientSystemPackage(pkg)
                        && !lastForegroundPackage.isEmpty()
                        && SystemClock.elapsedRealtime() - lastForegroundSeenAt <= TRANSIENT_FALLBACK_MS) {
                    return lastForegroundPackage;
                }
            }
        } catch (Throwable ignored) { }

        if (!lastForegroundPackage.isEmpty()
                && SystemClock.elapsedRealtime() - lastForegroundSeenAt <= TRANSIENT_FALLBACK_MS) {
            return lastForegroundPackage;
        }
        return null;
    }

    private void rememberForeground(String pkg) {
        lastForegroundPackage = pkg;
        lastForegroundSeenAt = SystemClock.elapsedRealtime();
        App.get().manager().rememberTargetOnly(pkg);
    }

    private boolean isValidTargetPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return false;
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            return info.uid > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isHomePackage(String pkg) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false;
            String home = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;
            return pkg.equals(home);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isTransientSystemPackage(String pkg) {
        return "com.android.systemui".equals(pkg)
                || "com.samsung.android.soundassistant".equals(pkg)
                || "com.samsung.android.app.soundassistant".equals(pkg)
                || "android".equals(pkg);
    }

    private void showSamsungVolumePanelWithoutChangingMainVolume() {
        try {
            AudioManager audio = getSystemService(AudioManager.class);
            if (audio != null) {
                int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        current,
                        AudioManager.FLAG_SHOW_UI
                );
            }
        } catch (Throwable ignored) { }
    }

    private void createPopup() {
        if (popup != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(14), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 38, 38, 42));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);

        popupIcon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.rightMargin = dp(10);
        card.addView(popupIcon, iconParams);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        popupText = new TextView(this);
        popupText.setTextColor(Color.WHITE);
        popupText.setTextSize(16);
        popupText.setSingleLine(true);
        right.addView(popupText, new LinearLayout.LayoutParams(dp(180), dp(24)));

        popupProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        popupProgress.setMax(100);
        right.addView(popupProgress, new LinearLayout.LayoutParams(dp(180), dp(8)));

        card.addView(right);
        card.setVisibility(View.GONE);
        popup = card;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(14);
        params.y = dp(74);
        windowManager.addView(card, params);
    }

    private void showPopup(String packageName, int percent) {
        if (popup == null) createPopup();
        if (popup == null) return;

        String label = packageName;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence appLabel = pm.getApplicationLabel(info);
            if (appLabel != null) label = appLabel.toString();
            popupIcon.setImageDrawable(pm.getApplicationIcon(info));
        } catch (Throwable ignored) { }

        popupText.setText(label + "  " + percent + "%");
        popupProgress.setProgress(percent);
        popup.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePopup);
        handler.postDelayed(hidePopup, 1400);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(hidePopup);
        if (windowManager != null && popup != null) {
            try { windowManager.removeView(popup); } catch (Throwable ignored) { }
        }
        popup = null;
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
