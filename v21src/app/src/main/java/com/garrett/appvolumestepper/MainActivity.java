package com.garrett.appvolumestepper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements SamsungAppVolumeManager.Listener {
    private SamsungAppVolumeManager manager;
    private TextView status;
    private TextView target;
    private TextView level;
    private TextView error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = App.get().manager();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(22), dp(22), dp(28));

        page.addView(text("App Volume Buttons v2.1", 28, Color.rgb(20, 20, 20)), matchWrap(dp(10)));
        page.addView(text(
                "Physical Volume Up/Down now changes Samsung's native per-app volume for whichever app is currently in front. It also asks Samsung to show the normal Media volume panel without changing the main Media level.",
                16,
                Color.rgb(45, 45, 45)
        ), matchWrap(dp(18)));

        status = text("", 17, Color.rgb(25, 25, 25));
        page.addView(status, matchWrap(dp(8)));

        target = text("", 17, Color.rgb(25, 25, 25));
        page.addView(target, matchWrap(dp(8)));

        level = text("", 21, Color.rgb(20, 20, 20));
        level.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(level, matchWrap(dp(16)));

        Button permission = button("Request / refresh Shizuku permission");
        permission.setOnClickListener(v -> manager.requestPermission());
        page.addView(permission, matchWrap(dp(8)));

        Button shizuku = button("Open Shizuku");
        shizuku.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
        });
        page.addView(shizuku, matchWrap(dp(8)));

        Button accessibility = button("Enable per-app volume-button service");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessibility, matchWrap(dp(16)));

        LinearLayout tests = new LinearLayout(this);
        tests.setOrientation(LinearLayout.HORIZONTAL);
        Button minus = button("Test −1%");
        Button plus = button("Test +1%");
        minus.setOnClickListener(v -> testStep(-1));
        plus.setOnClickListener(v -> testStep(1));
        tests.addView(minus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tests.addView(plus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(tests, matchWrap(dp(18)));

        page.addView(text(
                "Use it: open the app you want to control, then press the phone's volume buttons. The small popup shows that app's Sound Assistant percentage. Samsung's regular Media volume panel should also appear, but its main Media number should stay unchanged.",
                15,
                Color.rgb(60, 60, 60)
        ), matchWrap(dp(14)));

        page.addView(text(
                "Home screen and Samsung/System UI are left alone, so the volume buttons behave normally there.",
                15,
                Color.rgb(60, 60, 60)
        ), matchWrap(dp(14)));

        error = text("", 13, Color.rgb(155, 20, 20));
        page.addView(error, matchWrap(0));

        setContentView(page);
        render();
    }

    private void testStep(int direction) {
        String pkg = manager.getLastTargetPackage();
        if (pkg == null || pkg.isEmpty()) {
            error.setText("Open another app once, then come back here to use the test buttons.");
            return;
        }
        manager.step(pkg, direction);
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.addListener(this);
        String pkg = manager.getLastTargetPackage();
        if (pkg != null && !pkg.isEmpty()) manager.refreshPercent(pkg);
        render();
    }

    @Override
    protected void onPause() {
        manager.removeListener(this);
        super.onPause();
    }

    @Override
    public void onChanged() {
        runOnUiThread(this::render);
    }

    private void render() {
        String s;
        switch (manager.getStatus()) {
            case NOT_SAMSUNG: s = "Samsung native app-volume API: this is not a Samsung device"; break;
            case SHIZUKU_MISSING: s = "Shizuku: not installed"; break;
            case SHIZUKU_STOPPED: s = "Shizuku: installed but not running"; break;
            case PERMISSION_NEEDED: s = "Shizuku: permission needed"; break;
            case READY: s = "Samsung native app-volume API: READY"; break;
            default: s = "Samsung native app-volume API: ERROR"; break;
        }
        status.setText(s);

        String label = manager.getLastTargetLabel();
        String pkg = manager.getLastTargetPackage();
        target.setText(pkg == null || pkg.isEmpty()
                ? "Target app: open an app first"
                : "Last target: " + (label == null || label.isEmpty() ? pkg : label));

        int p = manager.getLastKnownPercent();
        level.setText(p >= 0 ? "Per-app volume: " + p + "%" : "Per-app volume: —");

        String e = manager.getLastError();
        if (e != null && !e.isEmpty()) error.setText(e);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String value, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(dp(2), 1f);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap(int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
