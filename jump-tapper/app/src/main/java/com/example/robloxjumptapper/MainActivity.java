package com.example.robloxjumptapper;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "tap_prefs";
    public static final String ACTION_RELOAD = "com.example.robloxjumptapper.RELOAD";

    private EditText minutesField;
    private EditText secondsField;
    private CheckBox robloxOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(22));

        TextView title = new TextView(this);
        title.setText("Jump Tapper");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("1. Enable the accessibility service.\n2. Set the interval.\n3. In Roblox, drag the JUMP target over the jump button.\n4. Tap START on the floating control.");
        help.setTextSize(16);
        help.setPadding(0, dp(14), 0, dp(18));
        root.addView(help);

        Button accessibility = new Button(this);
        accessibility.setText("Open Accessibility Settings");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Tap interval");
        intervalLabel.setTextSize(18);
        intervalLabel.setTypeface(Typeface.DEFAULT_BOLD);
        intervalLabel.setPadding(0, dp(20), 0, dp(8));
        root.addView(intervalLabel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        minutesField = numberField(String.valueOf(prefs.getInt("minutes", 0)));
        secondsField = numberField(String.valueOf(prefs.getInt("seconds", 30)));
        row.addView(minutesField, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView minText = new TextView(this);
        minText.setText(" min   ");
        minText.setTextSize(16);
        row.addView(minText);
        row.addView(secondsField, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView secText = new TextView(this);
        secText.setText(" sec");
        secText.setTextSize(16);
        row.addView(secText);
        root.addView(row);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(8), 0, 0);
        addPreset(presets, "30s", 0, 30);
        addPreset(presets, "1m", 1, 0);
        addPreset(presets, "1m30", 1, 30);
        addPreset(presets, "5m", 5, 0);
        root.addView(presets);

        robloxOnly = new CheckBox(this);
        robloxOnly.setText("Only tap while Roblox is the foreground app");
        robloxOnly.setChecked(prefs.getBoolean("roblox_only", true));
        robloxOnly.setPadding(0, dp(14), 0, dp(10));
        root.addView(robloxOnly);

        Button save = new Button(this);
        save.setText("Save Settings");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save);

        TextView note = new TextView(this);
        note.setText("Tip: 30–90 seconds is much gentler than rapid tapping. The target briefly hides while each tap is sent so it does not block the jump button.");
        note.setTextSize(14);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private EditText numberField(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        e.setText(value);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private void addPreset(LinearLayout row, String label, int min, int sec) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setOnClickListener(v -> {
            minutesField.setText(String.valueOf(min));
            secondsField.setText(String.valueOf(sec));
        });
        row.addView(b, new LinearLayout.LayoutParams(0, dp(46), 1));
    }

    private void saveSettings() {
        int min;
        int sec;
        try {
            min = Integer.parseInt(minutesField.getText().toString().trim());
            sec = Integer.parseInt(secondsField.getText().toString().trim());
        } catch (Exception e) {
            Toast.makeText(this, "Enter a valid number.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (min < 0 || sec < 0 || sec > 59 || (min == 0 && sec == 0)) {
            Toast.makeText(this, "Use at least 1 second. Seconds must be 0–59.", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("minutes", min)
                .putInt("seconds", sec)
                .putBoolean("roblox_only", robloxOnly.isChecked())
                .apply();
        Intent intent = new Intent(ACTION_RELOAD);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
