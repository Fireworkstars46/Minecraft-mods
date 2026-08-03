package com.example.robloxjumptapper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "tap_prefs";
    public static final String ACTION_RELOAD = "com.example.robloxjumptapper.RELOAD";
    private static final long MAX_INTERVAL_MS = 86400000L;

    private EditText hoursField, minutesField, secondsField, millisField, totalMillisField;
    private EditText targetSizeField, controlScaleField;
    private LinearLayout splitFieldsRow;
    private RadioButton splitMode, millisMode;
    private CheckBox robloxOnly, showTarget, showControls;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(22));

        TextView title = new TextView(this);
        title.setText("Jump Tapper v1.5");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        Button accessibility = new Button(this);
        accessibility.setText("Open Accessibility Settings");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        TextView intervalLabel = heading("Tap interval");
        root.addView(intervalLabel);

        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        splitMode = new RadioButton(this); splitMode.setText("H / M / S / ms");
        millisMode = new RadioButton(this); millisMode.setText("Total milliseconds");
        modeGroup.addView(splitMode, new RadioGroup.LayoutParams(0, dp(48), 1));
        modeGroup.addView(millisMode, new RadioGroup.LayoutParams(0, dp(48), 1));
        root.addView(modeGroup);

        long savedInterval = Math.min(prefs.getLong("interval_ms", 30000L), MAX_INTERVAL_MS);
        long hours = savedInterval / 3600000L;
        long r = savedInterval % 3600000L;
        long minutes = r / 60000L; r %= 60000L;
        long seconds = r / 1000L; long millis = r % 1000L;

        splitFieldsRow = new LinearLayout(this);
        splitFieldsRow.setOrientation(LinearLayout.HORIZONTAL);
        hoursField = numberField(String.valueOf(hours), 2);
        minutesField = numberField(String.valueOf(minutes), 4);
        secondsField = numberField(String.valueOf(seconds), 5);
        millisField = numberField(String.valueOf(millis), 8);
        addLabeledField(splitFieldsRow, hoursField, "h");
        addLabeledField(splitFieldsRow, minutesField, "m");
        addLabeledField(splitFieldsRow, secondsField, "s");
        addLabeledField(splitFieldsRow, millisField, "ms");
        root.addView(splitFieldsRow);

        totalMillisField = numberField(String.valueOf(savedInterval), 8);
        totalMillisField.setHint("Max 86400000");
        root.addView(totalMillisField, new LinearLayout.LayoutParams(-1, dp(52)));

        boolean useMillisMode = prefs.getBoolean("millis_mode", false);
        if (useMillisMode) millisMode.setChecked(true); else splitMode.setChecked(true);
        updateModeVisibility(useMillisMode);
        modeGroup.setOnCheckedChangeListener((g, id) -> updateModeVisibility(millisMode.isChecked()));

        LinearLayout presets = new LinearLayout(this); presets.setOrientation(LinearLayout.HORIZONTAL);
        addPreset(presets, "1s", 1000L); addPreset(presets, "30s", 30000L);
        addPreset(presets, "1m", 60000L); addPreset(presets, "1m30", 90000L);
        root.addView(presets);
        LinearLayout presets2 = new LinearLayout(this); presets2.setOrientation(LinearLayout.HORIZONTAL);
        addPreset(presets2, "5m", 300000L); addPreset(presets2, "10m", 600000L);
        addPreset(presets2, "1h", 3600000L); addPreset(presets2, "24h", 86400000L);
        root.addView(presets2);

        root.addView(heading("Floating overlay size"));
        LinearLayout sizeRow = new LinearLayout(this); sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        targetSizeField = numberField(String.valueOf(prefs.getInt("target_size_dp", 58)), 3);
        controlScaleField = numberField(String.valueOf(prefs.getInt("control_scale", 100)), 3);
        addLabeledField(sizeRow, targetSizeField, "Target dp (30–140)");
        addLabeledField(sizeRow, controlScaleField, "Controls % (60–160)");
        root.addView(sizeRow);

        showTarget = new CheckBox(this);
        showTarget.setText("Show circular JUMP target");
        showTarget.setChecked(prefs.getBoolean("target_visible", true));
        root.addView(showTarget);

        showControls = new CheckBox(this);
        showControls.setText("Show START/STOP controls");
        showControls.setChecked(prefs.getBoolean("control_visible", true));
        root.addView(showControls);

        Button showAll = new Button(this);
        showAll.setText("Show Both Overlays");
        showAll.setOnClickListener(v -> {
            showTarget.setChecked(true); showControls.setChecked(true); saveSettings();
        });
        root.addView(showAll);

        robloxOnly = new CheckBox(this);
        robloxOnly.setText("Only tap while Roblox is the foreground app");
        robloxOnly.setChecked(prefs.getBoolean("roblox_only", true));
        root.addView(robloxOnly);

        Button save = new Button(this);
        save.setText("Save Settings");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        setContentView(root);
    }

    private TextView heading(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(18); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6)); return t;
    }
    private EditText numberField(String value, int maxChars) {
        EditText e = new EditText(this); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setGravity(Gravity.CENTER);
        e.setText(value); e.setSelectAllOnFocus(true); e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxChars)}); return e;
    }
    private void addLabeledField(LinearLayout row, EditText field, String label) {
        LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
        cell.addView(field, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView t = new TextView(this); t.setText(label); t.setGravity(Gravity.CENTER); t.setTextSize(11); cell.addView(t);
        row.addView(cell, new LinearLayout.LayoutParams(0, dp(72), 1));
    }
    private void updateModeVisibility(boolean total) {
        splitFieldsRow.setVisibility(total ? View.GONE : View.VISIBLE);
        totalMillisField.setVisibility(total ? View.VISIBLE : View.GONE);
    }
    private void addPreset(LinearLayout row, String label, long valueMs) {
        Button b = new Button(this); b.setText(label); b.setTextSize(11); b.setOnClickListener(v -> setIntervalFields(valueMs));
        row.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1));
    }
    private void setIntervalFields(long valueMs) {
        valueMs = Math.min(valueMs, MAX_INTERVAL_MS); totalMillisField.setText(String.valueOf(valueMs));
        long h = valueMs / 3600000L; long r = valueMs % 3600000L; long m = r / 60000L; r %= 60000L;
        long s = r / 1000L; long ms = r % 1000L;
        hoursField.setText(String.valueOf(h)); minutesField.setText(String.valueOf(m)); secondsField.setText(String.valueOf(s)); millisField.setText(String.valueOf(ms));
    }
    private long parseLong(EditText field) { String text = field.getText().toString().trim(); return text.isEmpty() ? 0L : Long.parseLong(text); }

    private void saveSettings() {
        long interval;
        int targetSize, controlScale;
        try {
            if (millisMode.isChecked()) interval = parseLong(totalMillisField);
            else {
                long h = parseLong(hoursField), m = parseLong(minutesField), s = parseLong(secondsField), ms = parseLong(millisField);
                interval = Math.addExact(Math.addExact(Math.multiplyExact(h, 3600000L), Math.multiplyExact(m, 60000L)), Math.addExact(Math.multiplyExact(s, 1000L), ms));
            }
            targetSize = Integer.parseInt(targetSizeField.getText().toString().trim());
            controlScale = Integer.parseInt(controlScaleField.getText().toString().trim());
        } catch (Exception e) { Toast.makeText(this, "Check the numbers.", Toast.LENGTH_LONG).show(); return; }

        if (interval < 1L || interval > MAX_INTERVAL_MS) { Toast.makeText(this, "Interval must be 1 ms to 24 hours.", Toast.LENGTH_LONG).show(); return; }
        if (targetSize < 30 || targetSize > 140) { Toast.makeText(this, "Target size must be 30–140 dp.", Toast.LENGTH_LONG).show(); return; }
        if (controlScale < 60 || controlScale > 160) { Toast.makeText(this, "Control size must be 60–160%.", Toast.LENGTH_LONG).show(); return; }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("interval_ms", interval).putBoolean("millis_mode", millisMode.isChecked())
                .putBoolean("roblox_only", robloxOnly.isChecked()).putInt("target_size_dp", targetSize)
                .putInt("control_scale", controlScale).putBoolean("target_visible", showTarget.isChecked())
                .putBoolean("control_visible", showControls.isChecked()).apply();
        Intent i = new Intent(ACTION_RELOAD); i.setPackage(getPackageName()); sendBroadcast(i);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
