package com.fireworkstars46.signalmirror;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView serviceStatus;
    private TextView calibrationStatus;
    private Spinner intervalSpinner;
    private Bitmap calibrationScreenshot;
    private CalibrationView calibrationView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        showMainScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        if (serviceStatus != null) refreshStatusText();
    }

    private void showMainScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        root.addView(text("Signal Mirror", 30, true));
        TextView intro = text("A large home-screen copy of Samsung's cellular bars — including the bars shown when no SIM is active.", 17, false);
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        serviceStatus = text("", 17, true);
        root.addView(serviceStatus);
        calibrationStatus = text("", 16, false);
        calibrationStatus.setPadding(0, dp(5), 0, dp(14));
        root.addView(calibrationStatus);

        Button accessibility = button("1. Enable Signal Mirror accessibility service");
        accessibility.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) {
                Toast.makeText(this, "Open Settings > Accessibility > Installed apps > Signal Mirror", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessibility);

        Button calibrate = button("2. Calibrate by tapping the small signal bars");
        calibrate.setOnClickListener(v -> startCalibration());
        root.addView(calibrate);

        Button addWidget = button("3. Add the large widget");
        addWidget.setOnClickListener(v -> requestWidget());
        root.addView(addWidget);

        Button refresh = button("Refresh widget now");
        refresh.setOnClickListener(v -> requestManualRefresh());
        root.addView(refresh);

        TextView intervalLabel = text("Automatic refresh while the Home screen is visible", 16, true);
        intervalLabel.setPadding(0, dp(18), 0, dp(7));
        root.addView(intervalLabel);
        String[] choices = {"Every 5 seconds", "Every 15 seconds", "Every 30 seconds", "Every 60 seconds"};
        intervalSpinner = new Spinner(this);
        intervalSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        int saved = SignalUtils.prefs(this).getInt(SignalUtils.KEY_INTERVAL, 15);
        intervalSpinner.setSelection(saved == 5 ? 0 : saved == 30 ? 2 : saved == 60 ? 3 : 1);
        intervalSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int[] seconds = {5, 15, 30, 60};
                SignalUtils.prefs(MainActivity.this).edit().putInt(SignalUtils.KEY_INTERVAL, seconds[position]).apply();
                if (MirrorAccessibilityService.instance != null) MirrorAccessibilityService.instance.restartLoop();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(intervalSpinner, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView privacy = text("Privacy: this app has no Internet permission. Automatic updates happen only while your launcher/Home screen is visible. It keeps only the tiny signal-icon crop and immediately discards the full screenshot.", 14, false);
        privacy.setPadding(0, dp(24), 0, 0);
        root.addView(privacy);

        setContentView(scroll);
        refreshStatusText();
    }

    private void refreshStatusText() {
        boolean enabled = isAccessibilityEnabled();
        boolean calibrated = SignalUtils.prefs(this).getBoolean(SignalUtils.KEY_CALIBRATED, false);
        serviceStatus.setText(enabled ? "✓ Accessibility service enabled" : "○ Accessibility service is not enabled");
        calibrationStatus.setText(calibrated ? "✓ Signal icon calibrated" : "○ Calibration still needed");
    }

    private boolean isAccessibilityEnabled() {
        ComponentName target = new ComponentName(this, MirrorAccessibilityService.class);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                ComponentName component = ComponentName.unflattenFromString(splitter.next());
                if (target.equals(component)) return true;
            }
        }
        return MirrorAccessibilityService.instance != null;
    }

    private void startCalibration() {
        MirrorAccessibilityService service = MirrorAccessibilityService.instance;
        if (service == null) {
            Toast.makeText(this, "Enable the Signal Mirror accessibility service first.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Taking a screenshot…", Toast.LENGTH_SHORT).show();
        service.captureForCalibration(new SignalUtils.CaptureCallback() {
            @Override public void onSuccess(Bitmap bitmap) { runOnUiThread(() -> showCalibrationScreen(bitmap)); }
            @Override public void onError(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()); }
        });
    }

    private void showCalibrationScreen(Bitmap screenshot) {
        if (calibrationScreenshot != null && calibrationScreenshot != screenshot) calibrationScreenshot.recycle();
        calibrationScreenshot = screenshot;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(18));
        root.addView(text("Tap the cellular bars", 25, true));
        TextView help = text("Tap the small rising-bar icon near the top-right. The outlined box is what the widget will copy.", 15, false);
        help.setPadding(0, dp(5), 0, dp(10));
        root.addView(help);

        SharedPreferences p = SignalUtils.prefs(this);
        calibrationView = new CalibrationView(screenshot,
                p.getFloat(SignalUtils.KEY_X, SignalUtils.DEFAULT_X),
                p.getFloat(SignalUtils.KEY_Y, SignalUtils.DEFAULT_Y),
                p.getFloat(SignalUtils.KEY_W, SignalUtils.DEFAULT_W),
                p.getFloat(SignalUtils.KEY_H, SignalUtils.DEFAULT_H));
        root.addView(calibrationView, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(text("Selection width", 14, true));
        SeekBar width = new SeekBar(this);
        width.setMax(180);
        width.setProgress(Math.round((calibrationView.cropW - 0.020f) * 1000f));
        width.setOnSeekBarChangeListener(seek(progress -> {
            calibrationView.cropW = 0.020f + progress / 1000f;
            calibrationView.invalidate();
        }));
        root.addView(width);

        root.addView(text("Selection height", 14, true));
        SeekBar height = new SeekBar(this);
        height.setMax(90);
        height.setProgress(Math.round((calibrationView.cropH - 0.012f) * 1000f));
        height.setOnSeekBarChangeListener(seek(progress -> {
            calibrationView.cropH = 0.012f + progress / 1000f;
            calibrationView.invalidate();
        }));
        root.addView(height);

        LinearLayout buttons = new LinearLayout(this);
        Button cancel = button("Cancel");
        cancel.setOnClickListener(v -> showMainScreen());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button save = button("Save");
        save.setOnClickListener(v -> saveCalibration());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        saveParams.setMarginStart(dp(10));
        buttons.addView(save, saveParams);
        root.addView(buttons);
        setContentView(root);
    }

    private SeekBar.OnSeekBarChangeListener seek(ProgressConsumer consumer) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { consumer.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
    }

    private void saveCalibration() {
        if (calibrationView == null || calibrationScreenshot == null) return;
        SignalUtils.prefs(this).edit()
                .putFloat(SignalUtils.KEY_X, calibrationView.centerX)
                .putFloat(SignalUtils.KEY_Y, calibrationView.centerY)
                .putFloat(SignalUtils.KEY_W, calibrationView.cropW)
                .putFloat(SignalUtils.KEY_H, calibrationView.cropH)
                .putBoolean(SignalUtils.KEY_CALIBRATED, true).apply();
        Bitmap icon = SignalUtils.extractIcon(calibrationScreenshot, calibrationView.centerX,
                calibrationView.centerY, calibrationView.cropW, calibrationView.cropH);
        SignalUtils.saveIcon(this, icon);
        SignalUtils.updateAllWidgets(this, icon);
        icon.recycle();
        Toast.makeText(this, "Saved. The widget now mirrors that icon.", Toast.LENGTH_LONG).show();
        showMainScreen();
    }

    private void requestWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, SignalWidgetProvider.class);
        if (manager.isRequestPinAppWidgetSupported()) manager.requestPinAppWidget(provider, null, null);
        else Toast.makeText(this, "Hold an empty part of the Home screen, tap Widgets, then choose Signal Mirror.", Toast.LENGTH_LONG).show();
    }

    private void requestManualRefresh() {
        MirrorAccessibilityService service = MirrorAccessibilityService.instance;
        if (service == null) {
            Toast.makeText(this, "Enable the accessibility service first.", Toast.LENGTH_LONG).show();
            return;
        }
        service.captureAndUpdate(true, new SignalUtils.CaptureCallback() {
            @Override public void onSuccess(Bitmap ignored) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Widget refreshed.", Toast.LENGTH_SHORT).show()); }
            @Override public void onError(String message) { runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()); }
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(25, 25, 28));
        view.setTextSize(sp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.setMargins(0, dp(7), 0, dp(7));
        b.setLayoutParams(params);
        return b;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface ProgressConsumer { void accept(int progress); }

    private final class CalibrationView extends View {
        private final Bitmap screenshot;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float centerX, centerY, cropW, cropH;
        private float imageLeft, imageTop, imageWidth, imageHeight;

        CalibrationView(Bitmap screenshot, float x, float y, float w, float h) {
            super(MainActivity.this);
            this.screenshot = screenshot;
            centerX = x; centerY = y; cropW = w; cropH = h;
            boxPaint.setColor(Color.rgb(0, 190, 110));
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 3f);
            setBackgroundColor(Color.rgb(228, 228, 232));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / (float)screenshot.getWidth(), getHeight() / (float)screenshot.getHeight());
            imageWidth = screenshot.getWidth() * scale;
            imageHeight = screenshot.getHeight() * scale;
            imageLeft = (getWidth() - imageWidth) / 2f;
            imageTop = (getHeight() - imageHeight) / 2f;
            RectF imageRect = new RectF(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight);
            canvas.drawBitmap(screenshot, null, imageRect, imagePaint);
            float cx = imageLeft + centerX * imageWidth, cy = imageTop + centerY * imageHeight;
            float w = cropW * imageWidth, h = cropH * imageHeight;
            canvas.drawRect(cx-w/2f, cy-h/2f, cx+w/2f, cy+h/2f, boxPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                if (imageWidth > 0 && imageHeight > 0) {
                    centerX = SignalUtils.clamp((event.getX()-imageLeft)/imageWidth, 0f, 1f);
                    centerY = SignalUtils.clamp((event.getY()-imageTop)/imageHeight, 0f, 1f);
                    invalidate();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
