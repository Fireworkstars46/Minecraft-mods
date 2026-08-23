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
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity implements YouTubeVolumeManager.Listener {
    private TextView status;
    private TextView level;
    private TextView activity;
    private TextView error;
    private SeekBar seek;
    private YouTubeVolumeManager manager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manager = App.get().manager();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(22), dp(22), dp(28));

        TextView title = text("YouTube Volume Buttons v1.9", 28, Color.rgb(20, 20, 20));
        page.addView(title, matchWrap(dp(10)));

        TextView intro = text(
                "This version does NOT open Samsung's volume panel. It uses Shizuku to change YouTube's active audio player directly. When YouTube is playing, each physical volume-button press changes YouTube by exactly 1%. Otherwise the buttons work normally.",
                16,
                Color.rgb(50, 50, 50)
        );
        page.addView(intro, matchWrap(dp(18)));

        status = text("", 17, Color.rgb(25, 25, 25));
        page.addView(status, matchWrap(dp(6)));
        activity = text("", 16, Color.rgb(50, 50, 50));
        page.addView(activity, matchWrap(dp(16)));

        Button shizuku = new Button(this);
        shizuku.setText("Request / refresh Shizuku permission");
        shizuku.setAllCaps(false);
        shizuku.setOnClickListener(v -> manager.requestPermission());
        page.addView(shizuku, matchWrap(dp(8)));

        Button openShizuku = new Button(this);
        openShizuku.setText("Open Shizuku");
        openShizuku.setAllCaps(false);
        openShizuku.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
        });
        page.addView(openShizuku, matchWrap(dp(8)));

        Button accessibility = new Button(this);
        accessibility.setText("Enable physical volume-button service");
        accessibility.setAllCaps(false);
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(accessibility, matchWrap(dp(20)));

        level = text("", 19, Color.rgb(20, 20, 20));
        level.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(level, matchWrap(dp(4)));

        seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(manager.getPercent());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) manager.setPercent(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        page.addView(seek, matchWrap(dp(16)));

        TextView note = text(
                "Set Sound Assistant's YouTube app-volume slider to 100% once. v1.9 controls a separate direct YouTube gain, so Samsung's slider itself will not move.",
                15,
                Color.rgb(70, 70, 70)
        );
        page.addView(note, matchWrap(dp(16)));

        error = text("", 13, Color.rgb(150, 20, 20));
        page.addView(error, matchWrap(0));

        setContentView(page);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.addListener(this);
        manager.refreshPlayersNow();
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
        YouTubeVolumeManager.Status s = manager.getStatus();
        String statusText;
        switch (s) {
            case SHIZUKU_MISSING: statusText = "Shizuku: not installed"; break;
            case SHIZUKU_STOPPED: statusText = "Shizuku: installed but not running"; break;
            case PERMISSION_NEEDED: statusText = "Shizuku: permission needed"; break;
            case READY: statusText = "Shizuku: ready"; break;
            default: statusText = "Shizuku: error"; break;
        }
        status.setText(statusText);
        activity.setText(manager.isYouTubeActive() ? "YouTube audio: ACTIVE — volume buttons are redirected" : "YouTube audio: not active — volume buttons stay normal");
        int p = manager.getPercent();
        level.setText("YouTube direct level: " + p + "%");
        if (seek.getProgress() != p) seek.setProgress(p);
        String e = manager.getLastError();
        error.setText(e == null || e.isEmpty() ? "" : e);
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
