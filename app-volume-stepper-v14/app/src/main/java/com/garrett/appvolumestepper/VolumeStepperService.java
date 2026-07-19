package com.garrett.appvolumestepper;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class VolumeStepperService extends AccessibilityService {
    private static final String PREFS = "app_volume_stepper";
    private static final String PREF_X = "overlay_x_v15";
    private static final String PREF_Y = "overlay_y_v15";
    private static final Pattern PERCENT_PATTERN = Pattern.compile(
            ".*\\b(?:100|[0-9]{1,2})\\s*(?:%|percent\\b).*",
            Pattern.CASE_INSENSITIVE
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            refreshPanelNow();
            handler.postDelayed(this, isOverlayVisible() ? 160 : 600);
        }
    };
    private final Runnable eventRefreshRunnable = this::refreshPanelNow;

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlay;
    private TextView status;

    private boolean adjustmentBusy;
    private boolean draggingOverlay;
    private int queuedDelta;
    private int displayedPercent = -1;
    private int selectedAppCenterX = -1;
    private int stableDetectionCount;
    private String lastPanelSignature = "";
    private long lastPanelSeenAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createOverlay();
        handler.removeCallbacks(heartbeatRunnable);
        handler.post(heartbeatRunnable);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event != null && event.getPackageName() != null
                    && getPackageName().contentEquals(event.getPackageName())) {
                return;
            }
            maybeRememberTouchedAppSlider(event);
            handler.removeCallbacks(eventRefreshRunnable);
            handler.postDelayed(eventRefreshRunnable, 45);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        hideOverlayAndResetPanel();
    }

    private void createOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(4), dp(2), dp(4), dp(2));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF0202124);
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0x557A7A7A);
        overlay.setBackground(background);
        overlay.setElevation(dp(10));

        Button minus = makeButton("−");
        minus.setContentDescription("Decrease selected app volume by one percent");
        minus.setOnClickListener(v -> requestAdjustment(-1));

        status = new TextView(this);
        status.setText("APP --%");
        status.setTextSize(14);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setMinWidth(dp(86));
        status.setPadding(dp(7), dp(10), dp(7), dp(10));
        status.setContentDescription("Selected app volume. Drag to move controls");
        status.setOnTouchListener(new DragTouchListener());

        Button plus = makeButton("+");
        plus.setContentDescription("Increase selected app volume by one percent");
        plus.setOnClickListener(v -> requestAdjustment(1));

        overlay.addView(minus, wrapWrap());
        overlay.addView(status, wrapWrap());
        overlay.addView(plus, wrapWrap());

        overlayParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int defaultX = Math.max(dp(10), getResources().getDisplayMetrics().widthPixels / 2 - dp(105));
        int defaultY = Math.max(dp(80), getResources().getDisplayMetrics().heightPixels / 2);
        overlayParams.x = prefs.getInt(PREF_X, defaultX);
        overlayParams.y = prefs.getInt(PREF_Y, defaultY);
        overlayParams.setTitle("App volume stepper");

        overlay.setVisibility(View.GONE);
        try {
            windowManager.addView(overlay, overlayParams);
        } catch (Throwable ignored) {
            overlay = null;
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(22);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(dp(50));
        button.setMinimumHeight(dp(46));
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void refreshPanelNow() {
        if (draggingOverlay) {
            return;
        }
        try {
            PanelSnapshot panel = findAppPanel();
            if (panel == null) {
                stableDetectionCount = 0;
                lastPanelSignature = "";
                if (!adjustmentBusy || SystemClock.uptimeMillis() - lastPanelSeenAt > 260) {
                    hideOverlayOnly();
                }
                return;
            }

            lastPanelSeenAt = SystemClock.uptimeMillis();
            String signature = panel.signature();
            if (signature.equals(lastPanelSignature)) {
                stableDetectionCount++;
            } else {
                lastPanelSignature = signature;
                stableDetectionCount = 1;
            }

            selectedAppCenterX = panel.target.bounds.centerX();
            if (stableDetectionCount >= 2) {
                showOverlay();
            }

            if (!adjustmentBusy && queuedDelta == 0) {
                displayedPercent = panel.target.percent();
                setStatus("APP " + displayedPercent + "%");
            }
        } catch (Throwable ignored) {
            stableDetectionCount = 0;
            hideOverlayOnly();
        }
    }

    private boolean isOverlayVisible() {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    private void showOverlay() {
        if (overlay != null && overlay.getVisibility() != View.VISIBLE) {
            overlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideOverlayOnly() {
        if (overlay != null && overlay.getVisibility() != View.GONE) {
            overlay.setVisibility(View.GONE);
        }
        if (!adjustmentBusy) {
            queuedDelta = 0;
            displayedPercent = -1;
        }
    }

    private void hideOverlayAndResetPanel() {
        hideOverlayOnly();
        adjustmentBusy = false;
        queuedDelta = 0;
        displayedPercent = -1;
        selectedAppCenterX = -1;
        stableDetectionCount = 0;
        lastPanelSignature = "";
    }

    private void requestAdjustment(int direction) {
        try {
            PanelSnapshot panel = findAppPanel();
            if (panel == null) {
                hideOverlayAndResetPanel();
                return;
            }

            queuedDelta = clamp(queuedDelta + direction, -100, 100);
            int base = displayedPercent >= 0 ? displayedPercent : panel.target.percent();
            int preview = clamp(base + queuedDelta, 0, 100);
            setStatus("APP " + preview + "%");

            if (!adjustmentBusy) {
                processQueuedAdjustment();
            }
        } catch (Throwable ignored) {
            setStatus("APP --%");
        }
    }

    private void processQueuedAdjustment() {
        PanelSnapshot panel = findAppPanel();
        if (panel == null) {
            hideOverlayAndResetPanel();
            return;
        }

        int delta = queuedDelta;
        queuedDelta = 0;
        if (delta == 0) {
            adjustmentBusy = false;
            displayedPercent = panel.target.percent();
            setStatus("APP " + displayedPercent + "%");
            return;
        }

        adjustmentBusy = true;
        int current = panel.target.percent();
        int desired = clamp(current + delta, 0, 100);
        displayedPercent = desired;
        setStatus("APP " + desired + "%");
        setProgressAndVerify(panel.target, desired, delta, 0);
    }

    private void setProgressAndVerify(SliderCandidate target, int desired, int originalDelta, int retry) {
        float raw = target.rawForPercent(desired);
        Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, raw);

        boolean accepted = false;
        try {
            accepted = target.node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                    args
            );
        } catch (Throwable ignored) {
        }

        if (accepted) {
            handler.postDelayed(() -> verifyProgress(desired, originalDelta, retry), 150);
            return;
        }

        int action = originalDelta > 0
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean scrolled = false;
        try {
            scrolled = target.node.performAction(action);
        } catch (Throwable ignored) {
        }

        if (scrolled) {
            int remaining = originalDelta - (originalDelta > 0 ? 1 : -1);
            queuedDelta = clamp(queuedDelta + remaining, -100, 100);
            handler.postDelayed(this::finishAfterFallback, 170);
        } else {
            handler.postDelayed(this::finishAdjustmentFromCurrent, 80);
        }
    }

    private void verifyProgress(int desired, int originalDelta, int retry) {
        PanelSnapshot panel = findAppPanel();
        if (panel == null) {
            hideOverlayAndResetPanel();
            return;
        }

        int actual = panel.target.percent();
        if (actual == desired) {
            displayedPercent = actual;
            setStatus("APP " + actual + "%");
            finishAdjustment();
            return;
        }

        if (retry < 1) {
            setProgressAndVerify(panel.target, desired, originalDelta, retry + 1);
            return;
        }

        displayedPercent = actual;
        setStatus("APP " + actual + "%");
        finishAdjustment();
    }

    private void finishAfterFallback() {
        PanelSnapshot panel = findAppPanel();
        if (panel != null) {
            displayedPercent = panel.target.percent();
            setStatus("APP " + displayedPercent + "%");
        }
        finishAdjustment();
    }

    private void finishAdjustmentFromCurrent() {
        PanelSnapshot panel = findAppPanel();
        if (panel != null) {
            displayedPercent = panel.target.percent();
            setStatus("APP " + displayedPercent + "%");
        }
        finishAdjustment();
    }

    private void finishAdjustment() {
        adjustmentBusy = false;
        if (queuedDelta != 0) {
            handler.postDelayed(this::processQueuedAdjustment, 35);
        } else {
            handler.postDelayed(this::refreshPanelNow, 100);
        }
    }

    private void maybeRememberTouchedAppSlider(AccessibilityEvent event) {
        if (event == null || event.getSource() == null || event.getPackageName() == null) {
            return;
        }
        if (!isAllowedPanelPackage(event.getPackageName())) {
            return;
        }

        AccessibilityNodeInfo source = event.getSource();
        AccessibilityNodeInfo.RangeInfo range;
        try {
            range = source.getRangeInfo();
        } catch (Throwable ignored) {
            range = null;
        }
        if (range == null) {
            return;
        }

        Rect sourceBounds = new Rect();
        try {
            source.getBoundsInScreen(sourceBounds);
        } catch (Throwable ignored) {
            return;
        }

        PanelSnapshot panel = findAppPanel();
        if (panel == null) {
            return;
        }
        for (SliderCandidate app : panel.appCandidates) {
            if (Math.abs(app.bounds.centerX() - sourceBounds.centerX()) <= dp(28)) {
                selectedAppCenterX = app.bounds.centerX();
                break;
            }
        }
    }

    private PanelSnapshot findAppPanel() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return null;
        }

        PanelSnapshot best = null;
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root;
            try {
                root = window.getRoot();
            } catch (Throwable ignored) {
                root = null;
            }
            if (root == null || !isAllowedPanelPackage(root.getPackageName())) {
                continue;
            }

            List<SliderCandidate> rawSliders = new ArrayList<>();
            List<TextMarker> textMarkers = new ArrayList<>();
            collectPanelNodes(root, rawSliders, textMarkers);

            List<SliderCandidate> columns = collapseIntoColumns(rawSliders);
            PanelSnapshot candidate = buildPanelSnapshot(columns, textMarkers);
            if (candidate != null && (best == null
                    || candidate.target.bounds.centerX() > best.target.bounds.centerX())) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isAllowedPanelPackage(CharSequence packageName) {
        if (packageName == null) {
            return false;
        }
        String value = packageName.toString().toLowerCase(Locale.US);
        return value.equals("com.android.systemui") || value.contains("soundassistant");
    }

    private PanelSnapshot buildPanelSnapshot(
            List<SliderCandidate> columns,
            List<TextMarker> textMarkers
    ) {
        if (columns.size() < 6) {
            return null;
        }

        columns.sort(Comparator.comparingInt(c -> c.bounds.centerX()));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        List<SliderCandidate> validApps = new ArrayList<>();

        for (SliderCandidate app : columns) {
            if (app.bounds.centerX() < screenWidth * 0.78f
                    || app.bounds.right < screenWidth * 0.86f
                    || !hasVisiblePercentNear(app, textMarkers)) {
                continue;
            }

            List<SliderCandidate> leftAligned = new ArrayList<>();
            for (SliderCandidate slider : columns) {
                if (slider.bounds.centerX() >= app.bounds.centerX()) {
                    continue;
                }
                if (Math.abs(slider.bounds.top - app.bounds.top) <= dp(75)
                        && Math.abs(slider.bounds.bottom - app.bounds.bottom) <= dp(75)) {
                    leftAligned.add(slider);
                }
            }
            leftAligned.sort(Comparator.comparingInt(c -> c.bounds.centerX()));
            if (leftAligned.size() < 5) {
                continue;
            }

            List<SliderCandidate> systems = new ArrayList<>(
                    leftAligned.subList(leftAligned.size() - 5, leftAligned.size())
            );
            if (!hasRegularSystemSpacing(systems)) {
                continue;
            }

            SliderCandidate nearestSystem = systems.get(systems.size() - 1);
            int finalGap = app.bounds.centerX() - nearestSystem.bounds.centerX();
            if (finalGap < dp(26) || finalGap > dp(105)) {
                continue;
            }

            float medianHeight = medianHeight(systems);
            float medianSpan = medianSpan(systems);
            boolean clearlyShorter = app.bounds.height() <= medianHeight * 0.92f;
            boolean clearlySmallerRange = medianSpan > 0f && app.span() <= medianSpan * 0.86f;
            if (!clearlyShorter && !clearlySmallerRange) {
                continue;
            }

            validApps.add(app);
        }

        if (validApps.isEmpty()) {
            return null;
        }

        SliderCandidate target = validApps.get(validApps.size() - 1);
        if (selectedAppCenterX >= 0) {
            for (SliderCandidate app : validApps) {
                if (Math.abs(app.bounds.centerX() - selectedAppCenterX)
                        < Math.abs(target.bounds.centerX() - selectedAppCenterX)) {
                    target = app;
                }
            }
        }
        return new PanelSnapshot(columns, validApps, target);
    }

    private boolean hasVisiblePercentNear(SliderCandidate slider, List<TextMarker> markers) {
        Rect expanded = new Rect(slider.bounds);
        expanded.left -= dp(35);
        expanded.right += dp(35);
        expanded.top -= dp(90);
        expanded.bottom += dp(20);

        for (TextMarker marker : markers) {
            if (PERCENT_PATTERN.matcher(marker.value).matches()
                    && Rect.intersects(expanded, marker.bounds)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRegularSystemSpacing(List<SliderCandidate> systems) {
        if (systems.size() != 5) {
            return false;
        }
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < systems.size(); i++) {
            int gap = systems.get(i).bounds.centerX() - systems.get(i - 1).bounds.centerX();
            if (gap < dp(28) || gap > dp(100)) {
                return false;
            }
            gaps.add(gap);
        }
        Collections.sort(gaps);
        int median = gaps.get(gaps.size() / 2);
        for (int gap : gaps) {
            if (Math.abs(gap - median) > median * 0.42f) {
                return false;
            }
        }
        return true;
    }

    private List<SliderCandidate> collapseIntoColumns(List<SliderCandidate> source) {
        List<SliderCandidate> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingInt((SliderCandidate c) -> c.bounds.centerX())
                .thenComparingInt(c -> c.bounds.top));

        List<List<SliderCandidate>> groups = new ArrayList<>();
        for (SliderCandidate candidate : sorted) {
            List<SliderCandidate> chosen = null;
            for (List<SliderCandidate> group : groups) {
                int center = groupCenterX(group);
                if (Math.abs(candidate.bounds.centerX() - center) <= dp(22)) {
                    chosen = group;
                    break;
                }
            }
            if (chosen == null) {
                chosen = new ArrayList<>();
                groups.add(chosen);
            }
            chosen.add(candidate);
        }

        List<SliderCandidate> columns = new ArrayList<>();
        for (List<SliderCandidate> group : groups) {
            SliderCandidate best = group.get(0);
            for (SliderCandidate candidate : group) {
                if (candidate.qualityScore() > best.qualityScore()) {
                    best = candidate;
                }
            }
            columns.add(best);
        }
        return columns;
    }

    private int groupCenterX(List<SliderCandidate> group) {
        int total = 0;
        for (SliderCandidate slider : group) {
            total += slider.bounds.centerX();
        }
        return total / Math.max(1, group.size());
    }

    private void collectPanelNodes(
            AccessibilityNodeInfo node,
            List<SliderCandidate> sliders,
            List<TextMarker> textMarkers
    ) {
        if (node == null) {
            return;
        }

        boolean visible;
        try {
            visible = node.isVisibleToUser();
        } catch (Throwable ignored) {
            visible = false;
        }
        if (!visible) {
            return;
        }

        Rect bounds = new Rect();
        try {
            node.getBoundsInScreen(bounds);
        } catch (Throwable ignored) {
        }

        addTextMarker(node.getText(), bounds, textMarkers);
        addTextMarker(node.getContentDescription(), bounds, textMarkers);

        try {
            AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
            if (range != null && range.getMax() > range.getMin()) {
                int width = bounds.width();
                int height = bounds.height();
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                boolean vertical = width > 0
                        && height >= dp(125)
                        && height > width * 1.8f
                        && width <= dp(190)
                        && bounds.top >= 0
                        && bounds.bottom <= screenHeight;
                if (vertical) {
                    sliders.add(new SliderCandidate(node, bounds, range));
                }
            }
        } catch (Throwable ignored) {
        }

        int count;
        try {
            count = node.getChildCount();
        } catch (Throwable ignored) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
                child = null;
            }
            if (child != null) {
                collectPanelNodes(child, sliders, textMarkers);
            }
        }
    }

    private void addTextMarker(CharSequence value, Rect bounds, List<TextMarker> output) {
        if (value == null) {
            return;
        }
        String text = value.toString().trim();
        if (!text.isEmpty()) {
            output.add(new TextMarker(text, bounds));
        }
    }

    private float medianHeight(List<SliderCandidate> sliders) {
        List<Integer> values = new ArrayList<>();
        for (SliderCandidate slider : sliders) {
            values.add(slider.bounds.height());
        }
        Collections.sort(values);
        return values.isEmpty() ? 0f : values.get(values.size() / 2);
    }

    private float medianSpan(List<SliderCandidate> sliders) {
        List<Float> values = new ArrayList<>();
        for (SliderCandidate slider : sliders) {
            values.add(slider.span());
        }
        Collections.sort(values);
        return values.isEmpty() ? 0f : values.get(values.size() / 2);
    }

    private void setStatus(String value) {
        if (status != null) {
            status.setText(value);
        }
    }

    private void moveOverlayTo(int x, int y) {
        if (overlay == null || overlayParams == null || windowManager == null) {
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int overlayWidth = overlay.getWidth() > 0 ? overlay.getWidth() : dp(200);
        int overlayHeight = overlay.getHeight() > 0 ? overlay.getHeight() : dp(56);

        overlayParams.x = clamp(x, 0, Math.max(0, screenWidth - overlayWidth));
        overlayParams.y = clamp(y, 0, Math.max(0, screenHeight - overlayHeight));

        try {
            windowManager.updateViewLayout(overlay, overlayParams);
        } catch (Throwable ignored) {
        }
    }

    private void saveOverlayPosition() {
        if (overlayParams == null) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(PREF_X, overlayParams.x)
                .putInt(PREF_Y, overlayParams.y)
                .apply();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private float startRawX;
        private float startRawY;
        private int startWindowX;
        private int startWindowY;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    startWindowX = overlayParams.x;
                    startWindowY = overlayParams.y;
                    dragging = true;
                    draggingOverlay = true;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        return false;
                    }
                    int x = startWindowX + Math.round(event.getRawX() - startRawX);
                    int y = startWindowY + Math.round(event.getRawY() - startRawY);
                    moveOverlayTo(x, y);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        draggingOverlay = false;
                        saveOverlayPosition();
                        handler.postDelayed(VolumeStepperService.this::refreshPanelNow, 50);
                    }
                    return true;

                default:
                    return false;
            }
        }
    }

    private static final class PanelSnapshot {
        final List<SliderCandidate> allColumns;
        final List<SliderCandidate> appCandidates;
        final SliderCandidate target;

        PanelSnapshot(
                List<SliderCandidate> allColumns,
                List<SliderCandidate> appCandidates,
                SliderCandidate target
        ) {
            this.allColumns = allColumns;
            this.appCandidates = appCandidates;
            this.target = target;
        }

        String signature() {
            return target.bounds.centerX() + ":" + target.bounds.top + ":" + target.bounds.bottom;
        }
    }

    private static final class TextMarker {
        final String value;
        final Rect bounds;

        TextMarker(String value, Rect bounds) {
            this.value = value;
            this.bounds = new Rect(bounds);
        }
    }

    private static final class SliderCandidate {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final int rangeType;
        final float min;
        final float max;
        final float current;
        final boolean supportsSetProgress;

        SliderCandidate(
                AccessibilityNodeInfo node,
                Rect bounds,
                AccessibilityNodeInfo.RangeInfo range
        ) {
            this.node = node;
            this.bounds = new Rect(bounds);
            this.rangeType = range.getType();
            this.min = range.getMin();
            this.max = range.getMax();
            this.current = range.getCurrent();
            boolean found = false;
            try {
                for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                    if (action.getId()
                            == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                        found = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            this.supportsSetProgress = found;
        }

        int percent() {
            float span = max - min;
            if (span <= 0f) {
                return 0;
            }
            return clamp(Math.round(((current - min) / span) * 100f), 0, 100);
        }

        float span() {
            return max - min;
        }

        float rawForPercent(int percent) {
            float value = min + ((max - min) * clamp(percent, 0, 100) / 100f);
            if (rangeType == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) {
                value = Math.round(value);
            }
            return value;
        }

        int qualityScore() {
            int score = bounds.height();
            if (supportsSetProgress) {
                score += 10000;
            }
            score -= bounds.width();
            return score;
        }
    }
}
