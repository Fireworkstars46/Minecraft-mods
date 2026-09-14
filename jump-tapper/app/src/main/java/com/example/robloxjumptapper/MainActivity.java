package com.example.robloxjumptapper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MainActivity extends Activity {
    public static final String PREFS = "tap_prefs";
    public static final String ACTION_RELOAD = "com.example.robloxjumptapper.RELOAD";
    public static final String DEBUG_FILE = "jump_debug.log";
    private static final long MAX_INTERVAL_MS = 86400000L;

    private EditText hoursField, minutesField, secondsField, millisField, totalMillisField;
    private EditText targetSizeField, controlScaleField, tapDurationField, startDelayField;
    private EditText targetOpacityField, controlOpacityField, hapticStrengthField;
    private LinearLayout splitFieldsRow;
    private RadioButton splitMode, millisMode;
    private CheckBox showTarget, showControls, debugEnabled, hapticEnabled, autoCollapseStart, hideTargetRunning;
    private Spinner hotkeySpinner, startStopHotkeySpinner;
    private TextView customKeyLabel, startStopCustomKeyLabel;

    private final BroadcastReceiver keyCapturedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String name = intent.getStringExtra("keyName");
            int code = intent.getIntExtra("keyCode", 0);
            if (TapAccessibilityService.ACTION_KEY_CAPTURED.equals(action)) {
                hotkeySpinner.setSelection(3);
                customKeyLabel.setText("Recorded: " + friendlyKeyName(name, code));
                Toast.makeText(MainActivity.this, "Overlay keybind recorded", Toast.LENGTH_SHORT).show();
            } else if (TapAccessibilityService.ACTION_STARTSTOP_KEY_CAPTURED.equals(action)) {
                startStopHotkeySpinner.setSelection(1);
                startStopCustomKeyLabel.setText("Recorded: " + friendlyKeyName(name, code));
                Toast.makeText(MainActivity.this, "Start/Stop keybind recorded", Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateHotkeySettings(prefs);

        IntentFilter resultFilter = new IntentFilter();
        resultFilter.addAction(TapAccessibilityService.ACTION_KEY_CAPTURED);
        resultFilter.addAction(TapAccessibilityService.ACTION_STARTSTOP_KEY_CAPTURED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(keyCapturedReceiver, resultFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(keyCapturedReceiver, resultFilter);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(52));
        scroll.addView(root);

        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottomInset = insets.getSystemWindowInsetBottom();
            root.setPadding(dp(18), dp(24), dp(18), dp(52) + bottomInset + dp(24));
            return insets;
        });

        TextView title = new TextView(this); title.setText("Jump Tapper v1.19"); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);
        TextView subtitle = new TextView(this); subtitle.setText("Advanced controls"); subtitle.setTextSize(14); subtitle.setPadding(0,0,0,dp(8)); root.addView(subtitle);
        Button accessibility = new Button(this); accessibility.setText("Open Accessibility Settings"); accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); root.addView(accessibility);

        root.addView(heading("Tap timing"));
        RadioGroup modeGroup = new RadioGroup(this); modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        splitMode = new RadioButton(this); splitMode.setText("H / M / S / ms"); millisMode = new RadioButton(this); millisMode.setText("Total milliseconds");
        modeGroup.addView(splitMode, new RadioGroup.LayoutParams(0, dp(48), 1)); modeGroup.addView(millisMode, new RadioGroup.LayoutParams(0, dp(48), 1)); root.addView(modeGroup);

        long savedInterval = Math.min(prefs.getLong("interval_ms", 30000L), MAX_INTERVAL_MS);
        long hours=savedInterval/3600000L, r=savedInterval%3600000L, minutes=r/60000L; r%=60000L; long seconds=r/1000L, millis=r%1000L;
        splitFieldsRow = new LinearLayout(this); splitFieldsRow.setOrientation(LinearLayout.HORIZONTAL);
        hoursField=numberField(String.valueOf(hours),2); minutesField=numberField(String.valueOf(minutes),4); secondsField=numberField(String.valueOf(seconds),5); millisField=numberField(String.valueOf(millis),8);
        addLabeledField(splitFieldsRow,hoursField,"h"); addLabeledField(splitFieldsRow,minutesField,"m"); addLabeledField(splitFieldsRow,secondsField,"s"); addLabeledField(splitFieldsRow,millisField,"ms"); root.addView(splitFieldsRow);
        totalMillisField=numberField(String.valueOf(savedInterval),8); totalMillisField.setHint("Max 86400000"); root.addView(totalMillisField,new LinearLayout.LayoutParams(-1,dp(52)));
        boolean useMillisMode=prefs.getBoolean("millis_mode",false); if(useMillisMode)millisMode.setChecked(true);else splitMode.setChecked(true); updateModeVisibility(useMillisMode); modeGroup.setOnCheckedChangeListener((g,id)->updateModeVisibility(millisMode.isChecked()));

        LinearLayout presets=new LinearLayout(this);presets.setOrientation(LinearLayout.HORIZONTAL);addPreset(presets,"1s",1000L);addPreset(presets,"30s",30000L);addPreset(presets,"1m",60000L);addPreset(presets,"1m30",90000L);root.addView(presets);
        LinearLayout presets2=new LinearLayout(this);presets2.setOrientation(LinearLayout.HORIZONTAL);addPreset(presets2,"5m",300000L);addPreset(presets2,"10m",600000L);addPreset(presets2,"1h",3600000L);addPreset(presets2,"24h",86400000L);root.addView(presets2);

        LinearLayout tapTuneRow = new LinearLayout(this); tapTuneRow.setOrientation(LinearLayout.HORIZONTAL);
        tapDurationField = numberField(String.valueOf(prefs.getInt("tap_duration_ms",30)),3);
        startDelayField = numberField(String.valueOf(prefs.getInt("start_delay_ms",0)),5);
        addLabeledField(tapTuneRow,tapDurationField,"Tap hold ms (5–250)"); addLabeledField(tapTuneRow,startDelayField,"Start delay ms (0–10000)"); root.addView(tapTuneRow);

        root.addView(heading("Overlay appearance"));
        LinearLayout sizeRow=new LinearLayout(this);sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        targetSizeField=numberField(String.valueOf(prefs.getInt("target_size_dp",58)),3);controlScaleField=numberField(String.valueOf(prefs.getInt("control_scale",100)),3);
        addLabeledField(sizeRow,targetSizeField,"Target dp (30–140)");addLabeledField(sizeRow,controlScaleField,"Controls % (60–160)");root.addView(sizeRow);
        LinearLayout opacityRow=new LinearLayout(this);opacityRow.setOrientation(LinearLayout.HORIZONTAL);
        targetOpacityField=numberField(String.valueOf(prefs.getInt("target_opacity",90)),3);controlOpacityField=numberField(String.valueOf(prefs.getInt("control_opacity",87)),3);
        addLabeledField(opacityRow,targetOpacityField,"Target opacity %");addLabeledField(opacityRow,controlOpacityField,"Controls opacity %");root.addView(opacityRow);

        showTarget=new CheckBox(this);showTarget.setText("Show circular target");showTarget.setChecked(prefs.getBoolean("target_visible",true));root.addView(showTarget);
        showControls=new CheckBox(this);showControls.setText("Show START/STOP controls");showControls.setChecked(prefs.getBoolean("control_visible",true));root.addView(showControls);
        autoCollapseStart=new CheckBox(this);autoCollapseStart.setText("Auto-collapse control box when START begins");autoCollapseStart.setChecked(prefs.getBoolean("auto_collapse_start",false));root.addView(autoCollapseStart);
        hideTargetRunning=new CheckBox(this);hideTargetRunning.setText("Hide target circle while auto-tapping");hideTargetRunning.setChecked(prefs.getBoolean("hide_target_running",false));root.addView(hideTargetRunning);

        root.addView(heading("Tap feedback"));
        hapticEnabled=new CheckBox(this);hapticEnabled.setText("Vibrate for START / STOP / overlay toggle");hapticEnabled.setChecked(prefs.getBoolean("haptic_enabled",true));root.addView(hapticEnabled);
        hapticStrengthField=numberField(String.valueOf(prefs.getInt("haptic_strength",80)),3);root.addView(labeledBlock(hapticStrengthField,"Haptic strength 1–255"));

        root.addView(heading("Background Start/Stop keybind"));
        startStopHotkeySpinner = new Spinner(this);
        String[] startStopOptions = new String[]{"Disabled", "Custom recorded button/key"};
        ArrayAdapter<String> startStopAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, startStopOptions);
        startStopAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        startStopHotkeySpinner.setAdapter(startStopAdapter);
        int savedStartStopMode = prefs.getInt("startstop_hotkey_mode", 0); if(savedStartStopMode<0||savedStartStopMode>1)savedStartStopMode=0; startStopHotkeySpinner.setSelection(savedStartStopMode);
        root.addView(startStopHotkeySpinner, new LinearLayout.LayoutParams(-1, dp(52)));

        Button recordStartStopKey = new Button(this); recordStartStopKey.setText("Record Start/Stop button / key");
        recordStartStopKey.setOnClickListener(v -> {
            Intent i = new Intent(TapAccessibilityService.ACTION_CAPTURE_STARTSTOP_KEY); i.setPackage(getPackageName()); sendBroadcast(i);
            startStopCustomKeyLabel.setText("Listening… press the button/key you want now");
            Toast.makeText(this, "Press your Start/Stop button/key now", Toast.LENGTH_LONG).show();
        });
        root.addView(recordStartStopKey);
        startStopCustomKeyLabel = new TextView(this);
        String savedStartStopName = prefs.getString("startstop_hotkey_name", "");
        int savedStartStopCode = prefs.getInt("startstop_hotkey_keycode", 0);
        startStopCustomKeyLabel.setText(savedStartStopCode == 0 ? "No Start/Stop button recorded yet" : "Recorded: " + friendlyKeyName(savedStartStopName, savedStartStopCode));
        startStopCustomKeyLabel.setTextSize(13); startStopCustomKeyLabel.setPadding(0,dp(5),0,dp(5)); root.addView(startStopCustomKeyLabel);
        TextView startStopNote = new TextView(this);
        startStopNote.setText("When enabled, this button starts or stops auto-tapping even while Roblox is in front and even if the floating control box is hidden. If you assign the same key as the overlay keybind, Start/Stop takes priority.");
        startStopNote.setTextSize(12); startStopNote.setPadding(0,dp(5),0,dp(8)); root.addView(startStopNote);

        root.addView(heading("Overlay hide/show keybind"));
        hotkeySpinner = new Spinner(this);
        String[] hotkeyOptions = new String[]{"Volume Up + Volume Down together", "Volume Up only", "Volume Down only", "Custom recorded button/key", "Disabled"};
        ArrayAdapter<String> hotkeyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, hotkeyOptions);
        hotkeyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        hotkeySpinner.setAdapter(hotkeyAdapter);
        int savedHotkey = prefs.getInt("overlay_hotkey", 0); if(savedHotkey<0||savedHotkey>4)savedHotkey=0; hotkeySpinner.setSelection(savedHotkey);
        root.addView(hotkeySpinner, new LinearLayout.LayoutParams(-1, dp(52)));

        Button recordKey = new Button(this); recordKey.setText("Record overlay button / key");
        recordKey.setOnClickListener(v -> {
            Intent i = new Intent(TapAccessibilityService.ACTION_CAPTURE_KEY); i.setPackage(getPackageName()); sendBroadcast(i);
            customKeyLabel.setText("Listening… press the button/key you want now");
            Toast.makeText(this, "Press your overlay button/key now", Toast.LENGTH_LONG).show();
        });
        root.addView(recordKey);
        customKeyLabel = new TextView(this);
        String savedKeyName = prefs.getString("custom_hotkey_name", "");
        int savedKeyCode = prefs.getInt("custom_hotkey_keycode", 0);
        customKeyLabel.setText(savedKeyCode == 0 ? "No custom overlay button recorded yet" : "Recorded: " + friendlyKeyName(savedKeyName, savedKeyCode));
        customKeyLabel.setTextSize(13); customKeyLabel.setPadding(0, dp(5), 0, dp(5)); root.addView(customKeyLabel);

        TextView hotkeyNote = new TextView(this);
        hotkeyNote.setText("Custom recording can use many buttons from the phone, USB/Bluetooth keyboards, and controllers. Android may keep some system buttons such as Power/Home for itself.");
        hotkeyNote.setTextSize(12); hotkeyNote.setPadding(0, dp(5), 0, dp(8)); root.addView(hotkeyNote);

        root.addView(heading("Overlay controls"));
        TextView note=new TextView(this);note.setText("Tap ≡ to collapse/open. Hold ≡ about 0.3 seconds and drag to move the control box. Use MOVE to drag the target, then LOCK when positioned.");note.setTextSize(13);note.setPadding(0,dp(4),0,dp(8));root.addView(note);
        LinearLayout resetRow=new LinearLayout(this); resetRow.setOrientation(LinearLayout.HORIZONTAL);
        Button resetPositions=new Button(this); resetPositions.setText("Reset positions"); resetPositions.setOnClickListener(v->resetPositions()); resetRow.addView(resetPositions,new LinearLayout.LayoutParams(0,dp(48),1));
        Button resetDefaults=new Button(this); resetDefaults.setText("Reset settings"); resetDefaults.setOnClickListener(v->resetDefaults()); resetRow.addView(resetDefaults,new LinearLayout.LayoutParams(0,dp(48),1)); root.addView(resetRow);

        root.addView(heading("Debugging"));
        debugEnabled=new CheckBox(this);debugEnabled.setText("Enable lightweight debug logging");debugEnabled.setChecked(prefs.getBoolean("debug_enabled",true));root.addView(debugEnabled);
        LinearLayout debugRow=new LinearLayout(this);debugRow.setOrientation(LinearLayout.HORIZONTAL);
        Button copy=new Button(this);copy.setText("Copy Log");copy.setOnClickListener(v->copyLog());debugRow.addView(copy,new LinearLayout.LayoutParams(0,dp(48),1));
        Button share=new Button(this);share.setText("Share Log");share.setOnClickListener(v->shareLog());debugRow.addView(share,new LinearLayout.LayoutParams(0,dp(48),1));
        Button clear=new Button(this);clear.setText("Clear Log");clear.setOnClickListener(v->clearLog());debugRow.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(debugRow);
        TextView debugNote=new TextView(this);debugNote.setText("Performance mode still keeps logs in memory while START is running, then writes them after STOP.");debugNote.setTextSize(12);root.addView(debugNote);

        Button save=new Button(this);save.setText("Save Settings");save.setOnClickListener(v->saveSettings());root.addView(save);
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private void migrateHotkeySettings(SharedPreferences prefs) {
        if (!prefs.getBoolean("hotkey_schema_v2", false)) {
            int old = prefs.getInt("overlay_hotkey", 0);
            if (old == 3) old = 4;
            prefs.edit().putInt("overlay_hotkey", old).putBoolean("hotkey_schema_v2", true).apply();
        }
    }

    private String friendlyKeyName(String name, int code) {
        if (name == null || name.isEmpty()) name = "KEYCODE_" + code;
        if (name.startsWith("KEYCODE_")) name = name.substring(8);
        return name.replace('_', ' ') + " (" + code + ")";
    }

    private TextView heading(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(18);t.setTypeface(Typeface.DEFAULT_BOLD);t.setPadding(0,dp(18),0,dp(6));return t;}
    private EditText numberField(String value,int maxChars){EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setGravity(Gravity.CENTER);e.setText(value);e.setSelectAllOnFocus(true);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxChars)});return e;}
    private View labeledBlock(EditText field,String label){LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.addView(field,new LinearLayout.LayoutParams(-1,dp(48)));TextView t=new TextView(this);t.setText(label);t.setGravity(Gravity.CENTER);t.setTextSize(11);cell.addView(t);return cell;}
    private void addLabeledField(LinearLayout row,EditText field,String label){LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);cell.addView(field,new LinearLayout.LayoutParams(-1,dp(48)));TextView t=new TextView(this);t.setText(label);t.setGravity(Gravity.CENTER);t.setTextSize(11);cell.addView(t);row.addView(cell,new LinearLayout.LayoutParams(0,dp(72),1));}
    private void updateModeVisibility(boolean total){splitFieldsRow.setVisibility(total?View.GONE:View.VISIBLE);totalMillisField.setVisibility(total?View.VISIBLE:View.GONE);}
    private void addPreset(LinearLayout row,String label,long valueMs){Button b=new Button(this);b.setText(label);b.setTextSize(11);b.setOnClickListener(v->setIntervalFields(valueMs));row.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}
    private void setIntervalFields(long valueMs){valueMs=Math.min(valueMs,MAX_INTERVAL_MS);totalMillisField.setText(String.valueOf(valueMs));long h=valueMs/3600000L,r=valueMs%3600000L,m=r/60000L;r%=60000L;long s=r/1000L,ms=r%1000L;hoursField.setText(String.valueOf(h));minutesField.setText(String.valueOf(m));secondsField.setText(String.valueOf(s));millisField.setText(String.valueOf(ms));}
    private long parseLong(EditText field){String text=field.getText().toString().trim();return text.isEmpty()?0L:Long.parseLong(text);}
    private int parseInt(EditText field){String text=field.getText().toString().trim();return text.isEmpty()?0:Integer.parseInt(text);}

    private void saveSettings(){
        long interval; int targetSize,controlScale,tapDuration,startDelay,targetOpacity,controlOpacity,hapticStrength;
        try{
            if(millisMode.isChecked())interval=parseLong(totalMillisField);else{long h=parseLong(hoursField),m=parseLong(minutesField),s=parseLong(secondsField),ms=parseLong(millisField);interval=Math.addExact(Math.addExact(Math.multiplyExact(h,3600000L),Math.multiplyExact(m,60000L)),Math.addExact(Math.multiplyExact(s,1000L),ms));}
            targetSize=parseInt(targetSizeField);controlScale=parseInt(controlScaleField);tapDuration=parseInt(tapDurationField);startDelay=parseInt(startDelayField);targetOpacity=parseInt(targetOpacityField);controlOpacity=parseInt(controlOpacityField);hapticStrength=parseInt(hapticStrengthField);
        }catch(Exception e){Toast.makeText(this,"Check the numbers.",Toast.LENGTH_LONG).show();return;}
        if(interval<1L||interval>MAX_INTERVAL_MS){Toast.makeText(this,"Interval must be 1 ms to 24 hours.",Toast.LENGTH_LONG).show();return;}
        if(targetSize<30||targetSize>140||controlScale<60||controlScale>160){Toast.makeText(this,"Check overlay size limits.",Toast.LENGTH_LONG).show();return;}
        if(tapDuration<5||tapDuration>250){Toast.makeText(this,"Tap hold must be 5–250 ms.",Toast.LENGTH_LONG).show();return;}
        if(startDelay<0||startDelay>10000){Toast.makeText(this,"Start delay must be 0–10000 ms.",Toast.LENGTH_LONG).show();return;}
        if(targetOpacity<10||targetOpacity>100||controlOpacity<10||controlOpacity>100){Toast.makeText(this,"Opacity must be 10–100%.",Toast.LENGTH_LONG).show();return;}
        if(hapticStrength<1||hapticStrength>255){Toast.makeText(this,"Haptic strength must be 1–255.",Toast.LENGTH_LONG).show();return;}
        int hotkeyMode = hotkeySpinner.getSelectedItemPosition();
        int startStopMode = startStopHotkeySpinner.getSelectedItemPosition();
        SharedPreferences current = getSharedPreferences(PREFS,MODE_PRIVATE);
        if(hotkeyMode==3 && current.getInt("custom_hotkey_keycode",0)==0){Toast.makeText(this,"Record a custom overlay button/key first.",Toast.LENGTH_LONG).show();return;}
        if(startStopMode==1 && current.getInt("startstop_hotkey_keycode",0)==0){Toast.makeText(this,"Record a Start/Stop button/key first.",Toast.LENGTH_LONG).show();return;}
        current.edit()
                .putLong("interval_ms",interval).putBoolean("millis_mode",millisMode.isChecked())
                .putInt("target_size_dp",targetSize).putInt("control_scale",controlScale)
                .putInt("tap_duration_ms",tapDuration).putInt("start_delay_ms",startDelay)
                .putInt("target_opacity",targetOpacity).putInt("control_opacity",controlOpacity)
                .putBoolean("target_visible",showTarget.isChecked()).putBoolean("control_visible",showControls.isChecked())
                .putBoolean("auto_collapse_start",autoCollapseStart.isChecked()).putBoolean("hide_target_running",hideTargetRunning.isChecked())
                .putBoolean("haptic_enabled",hapticEnabled.isChecked()).putInt("haptic_strength",hapticStrength)
                .putBoolean("debug_enabled",debugEnabled.isChecked()).putInt("overlay_hotkey",hotkeyMode)
                .putInt("startstop_hotkey_mode",startStopMode).putBoolean("hotkey_schema_v2",true).apply();
        Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show();
    }

    private void resetPositions(){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove("target_x").remove("target_y").remove("control_x").remove("control_y").apply();
        Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);Toast.makeText(this,"Overlay positions reset",Toast.LENGTH_SHORT).show();
    }

    private void resetDefaults(){
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        int customCode=p.getInt("custom_hotkey_keycode",0);String customName=p.getString("custom_hotkey_name","");
        int startStopCode=p.getInt("startstop_hotkey_keycode",0);String startStopName=p.getString("startstop_hotkey_name","");
        p.edit().clear().putInt("custom_hotkey_keycode",customCode).putString("custom_hotkey_name",customName)
                .putInt("startstop_hotkey_keycode",startStopCode).putString("startstop_hotkey_name",startStopName)
                .putBoolean("hotkey_schema_v2",true).apply();
        Toast.makeText(this,"Settings reset. Reopen Jump Tapper to refresh the page.",Toast.LENGTH_LONG).show();
        Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);
    }

    private String readLog(){try{File f=new File(getFilesDir(),DEBUG_FILE);if(!f.exists())return "No debug log yet.";if(Build.VERSION.SDK_INT>=26)return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);java.io.FileInputStream in=new java.io.FileInputStream(f);byte[] b=new byte[(int)f.length()];int n=in.read(b);in.close();return new String(b,0,Math.max(0,n),StandardCharsets.UTF_8);}catch(Exception e){return "Could not read debug log: "+e.getMessage();}}
    private void copyLog(){ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Jump Tapper Debug Log",readLog()));Toast.makeText(this,"Debug log copied",Toast.LENGTH_SHORT).show();}
    private void shareLog(){Intent s=new Intent(Intent.ACTION_SEND);s.setType("text/plain");s.putExtra(Intent.EXTRA_SUBJECT,"Jump Tapper v1.19 Debug Log");s.putExtra(Intent.EXTRA_TEXT,readLog());startActivity(Intent.createChooser(s,"Share debug log"));}
    private void clearLog(){File f=new File(getFilesDir(),DEBUG_FILE);if(f.exists())f.delete();Toast.makeText(this,"Debug log cleared",Toast.LENGTH_SHORT).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy(){try{unregisterReceiver(keyCapturedReceiver);}catch(Exception ignored){}super.onDestroy();}
}
