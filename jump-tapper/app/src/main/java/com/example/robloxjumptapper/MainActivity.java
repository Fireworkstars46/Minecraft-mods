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

    private EditText hoursField;
    private EditText minutesField;
    private EditText secondsField;
    private EditText millisField;
    private EditText totalMillisField;
    private LinearLayout splitFieldsRow;
    private RadioButton splitMode;
    private RadioButton millisMode;
    private CheckBox robloxOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(22));

        TextView title = new TextView(this);
        title.setText("Jump Tapper v1.2");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Enable the accessibility service, choose any interval up to 24 hours, then drag JUMP over Roblox's jump button and press START.");
        help.setTextSize(15);
        help.setPadding(0, dp(10), 0, dp(14));
        root.addView(help);

        Button accessibility = new Button(this);
        accessibility.setText("Open Accessibility Settings");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Tap interval");
        intervalLabel.setTextSize(18);
        intervalLabel.setTypeface(Typeface.DEFAULT_BOLD);
        intervalLabel.setPadding(0, dp(18), 0, dp(6));
        root.addView(intervalLabel);

        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        splitMode = new RadioButton(this);
        splitMode.setText("H / M / S / ms");
        millisMode = new RadioButton(this);
        millisMode.setText("Total milliseconds");
        modeGroup.addView(splitMode, new RadioGroup.LayoutParams(0, dp(48), 1));
        modeGroup.addView(millisMode, new RadioGroup.LayoutParams(0, dp(48), 1));
        root.addView(modeGroup);

        long savedInterval = Math.min(prefs.getLong("interval_ms", 30000L), MAX_INTERVAL_MS);
        long hours = savedInterval / 3600000L;
        long remainder = savedInterval % 3600000L;
        long minutes = remainder / 60000L;
        remainder %= 60000L;
        long seconds = remainder / 1000L;
        long millis = remainder % 1000L;

        splitFieldsRow = new LinearLayout(this);
        splitFieldsRow.setOrientation(LinearLayout.HORIZONTAL);
        splitFieldsRow.setGravity(Gravity.CENTER_VERTICAL);
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
        root.addView(totalMillisField, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        boolean useMillisMode = prefs.getBoolean("millis_mode", false);
        if (useMillisMode) millisMode.setChecked(true); else splitMode.setChecked(true);
        updateModeVisibility(useMillisMode);
        modeGroup.setOnCheckedChangeListener((group, checkedId) ->
                updateModeVisibility(millisMode.isChecked()));

        TextView limits = new TextView(this);
        limits.setText("24-hour maximum: 24 h • 1440 m • 86400 s • 86400000 ms");
        limits.setTextSize(13);
        limits.setPadding(0, dp(6), 0, dp(8));
        root.addView(limits);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        addPreset(presets, "1s", 1000L);
        addPreset(presets, "30s", 30000L);
        addPreset(presets, "1m", 60000L);
        addPreset(presets, "1m30", 90000L);
        root.addView(presets);

        LinearLayout presets2 = new LinearLayout(this);
        presets2.setOrientation(LinearLayout.HORIZONTAL);
        addPreset(presets2, "5m", 300000L);
        addPreset(presets2, "10m", 600000L);
        addPreset(presets2, "1h", 3600000L);
        addPreset(presets2, "24h", 86400000L);
        root.addView(presets2);

        robloxOnly = new CheckBox(this);
        robloxOnly.setText("Only tap while Roblox is the foreground app");
        robloxOnly.setChecked(prefs.getBoolean("roblox_only", true));
        robloxOnly.setPadding(0, dp(10), 0, dp(8));
        root.addView(robloxOnly);

        Button save = new Button(this);
        save.setText("Save Settings");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        TextView note = new TextView(this);
        note.setText("Minimum is 1 ms. Maximum is 24 hours. Very low values can overwhelm Roblox/Android, so 1000 ms or higher is usually smoother.");
        note.setTextSize(13);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        setContentView(root);
    }

    private EditText numberField(String value, int maxChars) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxChars)});
        return e;
    }

    private void addLabeledField(LinearLayout row, EditText field, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        cell.addView(t);
        row.addView(cell, new LinearLayout.LayoutParams(0, dp(70), 1));
    }

    private void updateModeVisibility(boolean totalMillisMode) {
        splitFieldsRow.setVisibility(totalMillisMode ? View.GONE : View.VISIBLE);
        totalMillisField.setVisibility(totalMillisMode ? View.VISIBLE : View.GONE);
    }

    private void addPreset(LinearLayout row, String label, long valueMs) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setOnClickListener(v -> setIntervalFields(valueMs));
        row.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1));
    }

    private void setIntervalFields(long valueMs) {
        valueMs = Math.min(valueMs, MAX_INTERVAL_MS);
        totalMillisField.setText(String.valueOf(valueMs));
        long h = valueMs / 3600000L;
        long r = valueMs % 3600000L;
        long m = r / 60000L;
        r %= 60000L;
        long s = r / 1000L;
        long ms = r % 1000L;
        hoursField.setText(String.valueOf(h));
        minutesField.setText(String.valueOf(m));
        secondsField.setText(String.valueOf(s));
        millisField.setText(String.valueOf(ms));
    }

    private long parseLong(EditText field) throws NumberFormatException {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) return 0L;
        return Long.parseLong(text);
    }

    private void saveSettings() {
        long interval;
        try {
            if (millisMode.isChecked()) {
                interval = parseLong(totalMillisField);
            } else {
                long h = parseLong(hoursField);
                long m = parseLong(minutesField);
                long s = parseLong(secondsField);
                long ms = parseLong(millisField);
                if (h > 24L || m > 1440L || s > 86400L || ms > 86400000L) {
                    Toast.makeText(this, "Each field is limited to its 24-hour equivalent.", Toast.LENGTH_LONG).show();
                    return;
                }
                interval = Math.addExact(
                        Math.addExact(Math.multiplyExact(h, 3600000L), Math.multiplyExact(m, 60000L)),
                        Math.addExact(Math.multiplyExact(s, 1000L), ms));
            }
        } catch (Exception e) {
            Toast.makeText(this, "That number is too large or invalid.", Toast.LENGTH_LONG).show();
            return;
        }

        if (interval < 1L) {
            Toast.makeText(this, "Minimum interval is 1 millisecond.", Toast.LENGTH_LONG).show();
            return;
        }
        if (interval > MAX_INTERVAL_MS) {
            Toast.makeText(this, "Maximum interval is 24 hours (86400000 ms).", Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("interval_ms", interval)
                .putBoolean("millis_mode", millisMode.isChecked())
                .putBoolean("roblox_only", robloxOnly.isChecked())
                .apply();

        Intent intent = new Intent(ACTION_RELOAD);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        Toast.makeText(this, "Saved: " + interval + " ms", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
