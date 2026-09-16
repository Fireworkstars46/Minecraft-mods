package com.example.robloxjumptapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TapAccessibilityService extends AccessibilityService {
    public static final String ACTION_CAPTURE_KEY="com.example.robloxjumptapper.CAPTURE_KEY";
    public static final String ACTION_KEY_CAPTURED="com.example.robloxjumptapper.KEY_CAPTURED";
    public static final String ACTION_TOGGLE_MASTER="com.example.robloxjumptapper.TOGGLE_MASTER";
    public static final String ACTION_REQUEST_MASTER_STATE="com.example.robloxjumptapper.REQUEST_MASTER_STATE";
    public static final String ACTION_MASTER_STATE="com.example.robloxjumptapper.MASTER_STATE";

    private static final int MAX_DAY_MS=86400000;
    private static final long HOLD_SEGMENT_MS=59000L;

    private WindowManager wm;
    private TextView target;
    private LinearLayout control,topRow,buttons,settingsContent;
    private ScrollView settingsScroll;
    private WindowManager.LayoutParams targetParams,controlParams;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService logIo=Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer=new StringBuilder();

    private boolean masterEnabled=true,running=false,tapInProgress=false,moveMode=true,targetVisible=true,controlVisible=true,collapsed=false,debug=true;
    private boolean hotkeyHidden=false,hapticEnabled=true,autoCollapseStart=false,hideTargetRunning=false,overlayComboLatched=false,settingsOpen=false;
    private boolean ovUseTotal=false;
    private int captureMode=0;
    private final LinkedHashSet<Integer> captureCodes=new LinkedHashSet<>();
    private final HashSet<Integer> captureDown=new HashSet<>();
    private final HashSet<Integer> downKeys=new HashSet<>();

    private long intervalMs=30000L,seq=0,tapStart=0,nextTapUptime=0;
    private int targetSizeDp=58,controlScale=100,hotkeyMode=4,tapDurationMs=30,startDelayMs=0,targetOpacity1000=900,controlOpacity1000=870,hapticStrength1000=314;
    private int[] overlayComboCodes=new int[0];
    private float cachedTapX=0f,cachedTapY=0f;

    private Button startStop,moveButton,tapButton,targetButton,masterOverlayButton,settingsButton;
    private EditText ovDays,ovHours,ovMinutes,ovSeconds,ovMillis,ovTotal,ovHold,ovDelay;
    private EditText ovTargetSize,ovControlScale,ovTargetOpacity,ovControlOpacity,ovHaptic;
    private Button ovMode,ovShowTarget,ovShowControls,ovAutoCollapse,ovHideTarget,ovHapticToggle,ovHotkeyMode,ovDebug;
    private TextView ovComboLabel;

    private final Runnable loop=new Runnable(){@Override public void run(){
        if(!masterEnabled||!running)return;
        long now=SystemClock.uptimeMillis();
        long late=Math.max(0L,now-nextTapUptime);
        if(late>10&&intervalMs>0)log("SCHEDULE_LATE ms="+late);
        performTargetTap(false);
        long step=intervalMs<=0L?1L:intervalMs;
        nextTapUptime+=step;
        if(nextTapUptime<=now){
            long missed=((now-nextTapUptime)/step)+1L;
            nextTapUptime+=missed*step;
            if(intervalMs>0L)log("SCHEDULE_CATCHUP skippedSlots="+missed);
        }
        h.postAtTime(this,nextTapUptime);
    }};

    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(ACTION_TOGGLE_MASTER.equals(a)){setMasterEnabled(!masterEnabled,true);return;}
        if(ACTION_REQUEST_MASTER_STATE.equals(a)){broadcastMasterState();return;}
        if(ACTION_CAPTURE_KEY.equals(a)){armCapture();return;}
        if(MainActivity.ACTION_RELOAD.equals(a)){
            load();updateKeyFilterState();rebuildControl(settingsOpen);
            if(masterEnabled&&target==null)createTarget();if(!masterEnabled)removeTarget();applyTargetOnly();refreshUi();
        }
    }};

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        migrateSettings();load();updateKeyFilterState();createControl();
        if(masterEnabled)createTarget();refreshUi();
        log("SERVICE_CONNECTED sdk="+Build.VERSION.SDK_INT+" masterEnabled="+masterEnabled+" hotkeyMode="+hotkeyMode+" keyFilter="+needsKeyFilter());
        IntentFilter f=new IntentFilter();f.addAction(MainActivity.ACTION_RELOAD);f.addAction(ACTION_CAPTURE_KEY);f.addAction(ACTION_TOGGLE_MASTER);f.addAction(ACTION_REQUEST_MASTER_STATE);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);
        broadcastMasterState();
    }

    private void migrateSettings(){
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);SharedPreferences.Editor e=p.edit();boolean changed=false;
        if(!p.getBoolean("master_schema_v1",false)){e.putBoolean("master_enabled",true).putBoolean("master_schema_v1",true);int old=p.getInt("overlay_hotkey",0);if(old==0||old==1||old==2)e.putInt("overlay_hotkey",4);changed=true;}
        if(!p.contains("custom_hotkey_codes")&&p.getInt("custom_hotkey_keycode",0)!=0){e.putString("custom_hotkey_codes",String.valueOf(p.getInt("custom_hotkey_keycode",0))).putString("custom_hotkey_names",p.getString("custom_hotkey_name",""));changed=true;}
        if(!p.getBoolean("numeric_schema_1000",false)){e.putInt("target_opacity_1000",clamp(p.getInt("target_opacity",90)*10,0,1000)).putInt("control_opacity_1000",clamp(p.getInt("control_opacity",87)*10,0,1000)).putInt("haptic_strength_1000",clamp(Math.round(p.getInt("haptic_strength",80)*1000f/255f),0,1000)).putBoolean("numeric_schema_1000",true);changed=true;}
        e.putInt("startstop_hotkey_mode",0);if(changed)e.apply();
    }

    private void load(){
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);
        masterEnabled=p.getBoolean("master_enabled",true);intervalMs=Math.max(0L,Math.min(MAX_DAY_MS,p.getLong("interval_ms",30000L)));
        targetSizeDp=clamp(p.getInt("target_size_dp",58),0,1000);controlScale=clamp(p.getInt("control_scale",100),0,1000);
        tapDurationMs=clamp(p.getInt("tap_duration_ms",30),0,MAX_DAY_MS);startDelayMs=clamp(p.getInt("start_delay_ms",0),0,MAX_DAY_MS);
        targetOpacity1000=clamp(p.getInt("target_opacity_1000",900),0,1000);controlOpacity1000=clamp(p.getInt("control_opacity_1000",870),0,1000);hapticStrength1000=clamp(p.getInt("haptic_strength_1000",314),0,1000);
        targetVisible=p.getBoolean("target_visible",true);controlVisible=p.getBoolean("control_visible",true);collapsed=p.getBoolean("control_collapsed",false);debug=p.getBoolean("debug_enabled",true);hapticEnabled=p.getBoolean("haptic_enabled",true);
        autoCollapseStart=p.getBoolean("auto_collapse_start",false);hideTargetRunning=p.getBoolean("hide_target_running",false);hotkeyMode=p.getInt("overlay_hotkey",4);if(hotkeyMode!=3&&hotkeyMode!=4)hotkeyMode=4;
        overlayComboCodes=parseCodes(p.getString("custom_hotkey_codes",""));ovUseTotal=p.getBoolean("millis_mode",false);
    }

    private boolean needsKeyFilter(){return captureMode!=0||(hotkeyMode==3&&overlayComboCodes.length>0);}
    private void updateKeyFilterState(){try{AccessibilityServiceInfo info=getServiceInfo();if(info==null)return;boolean need=needsKeyFilter();int old=info.flags;if(need)info.flags|=AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;else info.flags&=~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;if(info.flags!=old)setServiceInfo(info);log("KEY_FILTER="+(need?"ON":"OFF"));}catch(Exception e){log("KEY_FILTER_ERROR "+e.getClass().getSimpleName());}}

    private int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private int effectiveTargetDp(){return Math.max(1,targetSizeDp);}
    private int scaled(int v){return dp(Math.max(1,Math.round(v*Math.max(1,controlScale)/100f)));}
    private float alpha1000(int v){return clamp(v,0,1000)/1000f;}
    private GradientDrawable circle(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);d.setStroke(dp(2),stroke);return d;}

    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private void setMasterEnabled(boolean enabled,boolean save){
        if(save)getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("master_enabled",enabled).putBoolean("master_schema_v1",true).apply();
        masterEnabled=enabled;
        if(!enabled){running=false;tapInProgress=false;h.removeCallbacks(loop);removeTarget();}else if(target==null)createTarget();
        refreshUi();updateKeyFilterState();broadcastMasterState();log("MASTER_STATE="+(enabled?"ON":"OFF")+" source=overlay_or_settings");flushLogsNow();
    }

    private void broadcastMasterState(){Intent s=new Intent(ACTION_MASTER_STATE);s.setPackage(getPackageName());s.putExtra("enabled",masterEnabled);sendBroadcast(s);}

    private void createTarget(){
        if(!masterEnabled||target!=null||wm==null)return;
        target=new TextView(this);target.setText("+");target.setTextColor(Color.WHITE);target.setTypeface(Typeface.DEFAULT_BOLD);target.setGravity(Gravity.CENTER);target.setBackground(circle(0x161976D2,0xCC42A5F5));target.setAlpha(alpha1000(targetOpacity1000));
        int s=dp(effectiveTargetDp());targetParams=new WindowManager.LayoutParams(s,s,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);targetParams.gravity=Gravity.TOP|Gravity.START;
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);targetParams.x=p.getInt("target_x",dp(250));targetParams.y=p.getInt("target_y",dp(650));target.setOnTouchListener(new DragListener(target,targetParams,"target_x","target_y"));wm.addView(target,targetParams);
    }
    private void removeTarget(){if(wm!=null&&target!=null)try{wm.removeView(target);}catch(Exception ignored){}target=null;targetParams=null;}

    private Button smallButton(String text,int w){Button b=new Button(this);b.setText(text);b.setTextSize(11);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(2),0,dp(2),0);buttons.addView(b,new LinearLayout.LayoutParams(scaled(w),scaled(46)));return b;}
    private Button panelButton(String text){Button b=new Button(this);b.setText(text);b.setTextSize(11);b.setMinWidth(0);b.setMinimumWidth(0);return b;}

    private void createControl(){
        if(control!=null||wm==null)return;
        control=new LinearLayout(this);control.setOrientation(LinearLayout.VERTICAL);control.setBackgroundColor(0xDD202124);control.setAlpha(alpha1000(controlOpacity1000));
        topRow=new LinearLayout(this);topRow.setOrientation(LinearLayout.HORIZONTAL);topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView menu=new TextView(this);menu.setText("≡");menu.setTextColor(Color.WHITE);menu.setTextSize(22);menu.setGravity(Gravity.CENTER);menu.setBackgroundColor(0xFF3C4043);menu.setOnTouchListener(new MenuListener());topRow.addView(menu,new LinearLayout.LayoutParams(scaled(46),scaled(46)));
        buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        masterOverlayButton=smallButton(masterEnabled?"ON":"OFF",58);masterOverlayButton.setOnClickListener(v->setMasterEnabled(!masterEnabled,true));
        startStop=smallButton("START",80);startStop.setOnClickListener(v->toggle());
        moveButton=smallButton(moveMode?"LOCK":"MOVE",66);moveButton.setOnClickListener(v->setMove(!moveMode));
        tapButton=smallButton("TAP",54);tapButton.setOnClickListener(v->{vibrate(10);performTargetTap(true);});
        targetButton=smallButton("J",42);targetButton.setOnClickListener(v->{if(running||!masterEnabled)return;targetVisible=!targetVisible;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("target_visible",targetVisible).apply();if(target!=null)target.setVisibility(targetVisible?View.VISIBLE:View.GONE);vibrate(8);});
        settingsButton=smallButton("SET",54);settingsButton.setOnClickListener(v->setSettingsOpen(!settingsOpen));
        topRow.addView(buttons);control.addView(topRow);
        settingsScroll=buildSettingsPanel();settingsScroll.setVisibility(View.GONE);
        int maxW=(int)(getResources().getDisplayMetrics().widthPixels*0.90f);int maxH=(int)(getResources().getDisplayMetrics().heightPixels*0.72f);
        control.addView(settingsScroll,new LinearLayout.LayoutParams(Math.min(dp(390),maxW),Math.max(dp(220),maxH)));
        controlParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);controlParams.gravity=Gravity.TOP|Gravity.START;
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);controlParams.x=p.getInt("control_x",dp(12));controlParams.y=p.getInt("control_y",dp(120));controlParams.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;wm.addView(control,controlParams);
    }

    private ScrollView buildSettingsPanel(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setBackgroundColor(0xF5303134);
        settingsContent=new LinearLayout(this);settingsContent.setOrientation(LinearLayout.VERTICAL);settingsContent.setPadding(dp(8),dp(6),dp(8),dp(12));scroll.addView(settingsContent);
        TextView title=panelText("ALL OVERLAY SETTINGS",15,true);settingsContent.addView(title);
        settingsContent.addView(panelText("Everything here saves without leaving the game. Timing accepts 0 = instant.",11,false));

        addSection("Tap timing");
        ovMode=panelButton(ovUseTotal?"MODE: TOTAL MS":"MODE: D/H/M/S/MS");ovMode.setOnClickListener(v->{ovUseTotal=!ovUseTotal;updateModeUi();});settingsContent.addView(ovMode,new LinearLayout.LayoutParams(-1,dp(42)));
        LinearLayout timeRow=new LinearLayout(this);timeRow.setOrientation(LinearLayout.HORIZONTAL);
        ovDays=overlayNumber(0,2);ovHours=overlayNumber(0,2);ovMinutes=overlayNumber(0,2);ovSeconds=overlayNumber(0,2);ovMillis=overlayNumber(0,4);
        timingCell(timeRow,"d",ovDays);timingCell(timeRow,"h",ovHours);timingCell(timeRow,"m",ovMinutes);timingCell(timeRow,"s",ovSeconds);timingCell(timeRow,"ms",ovMillis);settingsContent.addView(timeRow);
        ovTotal=overlayNumber(intervalMs,8);settingsContent.addView(settingRow("Total ms 0–86400000",ovTotal));
        LinearLayout p1=new LinearLayout(this);p1.setOrientation(LinearLayout.HORIZONTAL);preset(p1,"0",0);preset(p1,"1s",1000);preset(p1,"30s",30000);preset(p1,"1m",60000);settingsContent.addView(p1);
        LinearLayout p2=new LinearLayout(this);p2.setOrientation(LinearLayout.HORIZONTAL);preset(p2,"5m",300000);preset(p2,"10m",600000);preset(p2,"1h",3600000);preset(p2,"24h",86400000);settingsContent.addView(p2);
        ovHold=overlayNumber(tapDurationMs,8);settingsContent.addView(settingRow("Tap hold ms 0–86400000",ovHold));
        ovDelay=overlayNumber(startDelayMs,8);settingsContent.addView(settingRow("Start delay ms 0–86400000",ovDelay));

        addSection("Overlay appearance");
        ovTargetSize=overlayNumber(targetSizeDp,4);settingsContent.addView(settingRow("Target size 0–1000",ovTargetSize));
        ovControlScale=overlayNumber(controlScale,4);settingsContent.addView(settingRow("Controls % 0–1000",ovControlScale));
        ovTargetOpacity=overlayNumber(targetOpacity1000,4);settingsContent.addView(settingRow("Target opacity 0–1000",ovTargetOpacity));
        ovControlOpacity=overlayNumber(controlOpacity1000,4);settingsContent.addView(settingRow("Controls opacity 0–1000",ovControlOpacity));
        ovShowTarget=toggleButton("Show circular target",targetVisible);ovShowTarget.setOnClickListener(v->{targetVisible=!targetVisible;setToggleText(ovShowTarget,"Show circular target",targetVisible);});settingsContent.addView(ovShowTarget);
        ovShowControls=toggleButton("Show START/STOP controls",controlVisible);ovShowControls.setOnClickListener(v->{controlVisible=!controlVisible;setToggleText(ovShowControls,"Show START/STOP controls",controlVisible);});settingsContent.addView(ovShowControls);
        ovAutoCollapse=toggleButton("Auto-collapse on START",autoCollapseStart);ovAutoCollapse.setOnClickListener(v->{autoCollapseStart=!autoCollapseStart;setToggleText(ovAutoCollapse,"Auto-collapse on START",autoCollapseStart);});settingsContent.addView(ovAutoCollapse);
        ovHideTarget=toggleButton("Hide target while auto-tapping",hideTargetRunning);ovHideTarget.setOnClickListener(v->{hideTargetRunning=!hideTargetRunning;setToggleText(ovHideTarget,"Hide target while auto-tapping",hideTargetRunning);});settingsContent.addView(ovHideTarget);

        addSection("Tap feedback");
        ovHapticToggle=toggleButton("Vibrate START/STOP/overlay",hapticEnabled);ovHapticToggle.setOnClickListener(v->{hapticEnabled=!hapticEnabled;setToggleText(ovHapticToggle,"Vibrate START/STOP/overlay",hapticEnabled);});settingsContent.addView(ovHapticToggle);
        ovHaptic=overlayNumber(hapticStrength1000,4);settingsContent.addView(settingRow("Haptic strength 0–1000",ovHaptic));

        addSection("Overlay hide/show keybind");
        ovHotkeyMode=toggleButton("Custom keybind enabled",hotkeyMode==3);ovHotkeyMode.setOnClickListener(v->{hotkeyMode=hotkeyMode==3?4:3;setToggleText(ovHotkeyMode,"Custom keybind enabled",hotkeyMode==3);});settingsContent.addView(ovHotkeyMode);
        ovComboLabel=panelText(comboLabelText(),11,false);settingsContent.addView(ovComboLabel);
        LinearLayout keyRow=new LinearLayout(this);keyRow.setOrientation(LinearLayout.HORIZONTAL);
        Button record=panelButton("RECORD BUTTON(S)");record.setOnClickListener(v->{hotkeyMode=3;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("overlay_hotkey",3).apply();armCapture();toast("Hold the button(s), then release all");});keyRow.addView(record,new LinearLayout.LayoutParams(0,dp(44),1));
        Button clear=panelButton("CLEAR KEYBIND");clear.setOnClickListener(v->clearOverlayKeybind());keyRow.addView(clear,new LinearLayout.LayoutParams(0,dp(44),1));settingsContent.addView(keyRow);

        addSection("Overlay controls");
        LinearLayout resetRow=new LinearLayout(this);resetRow.setOrientation(LinearLayout.HORIZONTAL);
        Button rp=panelButton("RESET POSITIONS");rp.setOnClickListener(v->resetPositions());resetRow.addView(rp,new LinearLayout.LayoutParams(0,dp(44),1));
        Button rs=panelButton("RESET SETTINGS");rs.setOnClickListener(v->resetSettings());resetRow.addView(rs,new LinearLayout.LayoutParams(0,dp(44),1));settingsContent.addView(resetRow);

        addSection("Debugging");
        ovDebug=toggleButton("Lightweight debug logging",debug);ovDebug.setOnClickListener(v->{debug=!debug;setToggleText(ovDebug,"Lightweight debug logging",debug);});settingsContent.addView(ovDebug);
        Button clearLog=panelButton("CLEAR DEBUG LOG");clearLog.setOnClickListener(v->{File f=new File(getFilesDir(),MainActivity.DEBUG_FILE);if(f.exists())f.delete();synchronized(logBuffer){logBuffer.setLength(0);}toast("Debug log cleared");});settingsContent.addView(clearLog,new LinearLayout.LayoutParams(-1,dp(44)));

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button apply=panelButton("APPLY ALL");apply.setOnClickListener(v->applyOverlaySettings());actions.addView(apply,new LinearLayout.LayoutParams(0,dp(48),1));
        Button close=panelButton("CLOSE");close.setOnClickListener(v->setSettingsOpen(false));actions.addView(close,new LinearLayout.LayoutParams(0,dp(48),1));settingsContent.addView(actions);
        syncOverlayFields();updateModeUi();return scroll;
    }

    private TextView panelText(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);t.setPadding(dp(2),dp(3),dp(2),dp(3));return t;}
    private void addSection(String s){TextView t=panelText(s,13,true);t.setPadding(dp(2),dp(10),dp(2),dp(3));settingsContent.addView(t);}
    private Button toggleButton(String label,boolean on){Button b=panelButton("");setToggleText(b,label,on);return b;}
    private void setToggleText(Button b,String label,boolean on){b.setText(label+": "+(on?"ON":"OFF"));}
    private EditText overlayNumber(long value,int len){EditText e=new EditText(this);e.setText(String.valueOf(value));e.setTextColor(Color.WHITE);e.setHintTextColor(0xFFAAAAAA);e.setTextSize(14);e.setGravity(Gravity.CENTER);e.setSingleLine(true);e.setSelectAllOnFocus(true);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(len)});return e;}
    private LinearLayout settingRow(String label,EditText field){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);TextView t=panelText(label,11,false);t.setGravity(Gravity.CENTER_VERTICAL);row.addView(t,new LinearLayout.LayoutParams(0,dp(44),1));row.addView(field,new LinearLayout.LayoutParams(dp(125),dp(44)));return row;}
    private void timingCell(LinearLayout row,String label,EditText e){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.addView(e,new LinearLayout.LayoutParams(-1,dp(40)));TextView t=panelText(label,10,false);t.setGravity(Gravity.CENTER);c.addView(t,new LinearLayout.LayoutParams(-1,dp(24)));row.addView(c,new LinearLayout.LayoutParams(0,dp(64),1));}
    private void preset(LinearLayout row,String label,long value){Button b=panelButton(label);b.setOnClickListener(v->setOverlayInterval(value));row.addView(b,new LinearLayout.LayoutParams(0,dp(40),1));}

    private void setOverlayInterval(long v){v=Math.max(0L,Math.min(MAX_DAY_MS,v));if(ovTotal!=null)ovTotal.setText(String.valueOf(v));long r=v;long d=r/86400000L;r%=86400000L;long hr=r/3600000L;r%=3600000L;long min=r/60000L;r%=60000L;long sec=r/1000L;long ms=r%1000L;if(ovDays!=null)ovDays.setText(String.valueOf(d));if(ovHours!=null)ovHours.setText(String.valueOf(hr));if(ovMinutes!=null)ovMinutes.setText(String.valueOf(min));if(ovSeconds!=null)ovSeconds.setText(String.valueOf(sec));if(ovMillis!=null)ovMillis.setText(String.valueOf(ms));}
    private long field(EditText e){try{String s=e.getText().toString().trim();return s.isEmpty()?0L:Long.parseLong(s);}catch(Exception ex){return -1L;}}
    private long overlayIntervalValue(){if(ovUseTotal)return field(ovTotal);long d=field(ovDays),hr=field(ovHours),m=field(ovMinutes),s=field(ovSeconds),ms=field(ovMillis);if(d<0||hr<0||m<0||s<0||ms<0)return -1L;try{return Math.addExact(Math.addExact(Math.multiplyExact(d,86400000L),Math.multiplyExact(hr,3600000L)),Math.addExact(Math.multiplyExact(m,60000L),Math.addExact(Math.multiplyExact(s,1000L),ms)));}catch(Exception e){return -1L;}}
    private void updateModeUi(){if(ovMode!=null)ovMode.setText(ovUseTotal?"MODE: TOTAL MS":"MODE: D/H/M/S/MS");if(ovTotal!=null)ovTotal.setEnabled(ovUseTotal);boolean split=!ovUseTotal;if(ovDays!=null)ovDays.setEnabled(split);if(ovHours!=null)ovHours.setEnabled(split);if(ovMinutes!=null)ovMinutes.setEnabled(split);if(ovSeconds!=null)ovSeconds.setEnabled(split);if(ovMillis!=null)ovMillis.setEnabled(split);}

    private void applyOverlaySettings(){
        long iv=overlayIntervalValue(),hold=field(ovHold),delay=field(ovDelay),ts=field(ovTargetSize),cs=field(ovControlScale),to=field(ovTargetOpacity),co=field(ovControlOpacity),hs=field(ovHaptic);
        if(iv<0||iv>MAX_DAY_MS||hold<0||hold>MAX_DAY_MS||delay<0||delay>MAX_DAY_MS||ts<0||ts>1000||cs<0||cs>1000||to<0||to>1000||co<0||co>1000||hs<0||hs>1000){toast("Check values: timing 0–1 day, others 0–1000");return;}
        intervalMs=iv;tapDurationMs=(int)hold;startDelayMs=(int)delay;targetSizeDp=(int)ts;controlScale=(int)cs;targetOpacity1000=(int)to;controlOpacity1000=(int)co;hapticStrength1000=(int)hs;
        SharedPreferences.Editor e=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit();
        e.putLong("interval_ms",intervalMs).putBoolean("millis_mode",ovUseTotal).putInt("tap_duration_ms",tapDurationMs).putInt("start_delay_ms",startDelayMs)
         .putInt("target_size_dp",targetSizeDp).putInt("control_scale",controlScale).putInt("target_opacity_1000",targetOpacity1000).putInt("control_opacity_1000",controlOpacity1000)
         .putInt("haptic_strength_1000",hapticStrength1000).putBoolean("target_visible",targetVisible).putBoolean("control_visible",controlVisible).putBoolean("auto_collapse_start",autoCollapseStart)
         .putBoolean("hide_target_running",hideTargetRunning).putBoolean("haptic_enabled",hapticEnabled).putBoolean("debug_enabled",debug).putInt("overlay_hotkey",hotkeyMode).apply();
        if(running){nextTapUptime=SystemClock.uptimeMillis()+startDelayMs;h.removeCallbacks(loop);h.postAtTime(loop,nextTapUptime);}
        applyTargetOnly();updateKeyFilterState();
        boolean reopen=settingsOpen;rebuildControl(reopen);refreshUi();
        toast("All overlay settings applied");log("OVERLAY_ALL_SETTINGS_APPLIED intervalMs="+intervalMs+" holdMs="+tapDurationMs+" delayMs="+startDelayMs);
    }

    private String comboLabelText(){SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);String names=p.getString("custom_hotkey_names","");String codes=p.getString("custom_hotkey_codes","");if(codes==null||codes.trim().isEmpty())return "Recorded keybind: none";StringBuilder b=new StringBuilder("Recorded keybind: ");String[] ns=names==null?new String[0]:names.split(",");String[] cs=codes.split(",");for(int i=0;i<cs.length;i++){if(i>0)b.append(" + ");String n=i<ns.length?ns[i]:"";if(n.startsWith("KEYCODE_"))n=n.substring(8);n=n.replace('_',' ');b.append(n.isEmpty()?"KEY "+cs[i]:n);}return b.toString();}
    private void clearOverlayKeybind(){overlayComboCodes=new int[0];hotkeyMode=4;captureMode=0;downKeys.clear();overlayComboLatched=false;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().remove("custom_hotkey_codes").remove("custom_hotkey_names").putInt("overlay_hotkey",4).apply();updateKeyFilterState();if(ovHotkeyMode!=null)setToggleText(ovHotkeyMode,"Custom keybind enabled",false);if(ovComboLabel!=null)ovComboLabel.setText("Recorded keybind: none");toast("Overlay keybind cleared");}
    private void resetPositions(){getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().remove("target_x").remove("target_y").remove("control_x").remove("control_y").apply();if(controlParams!=null){controlParams.x=dp(12);controlParams.y=dp(120);try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}if(targetParams!=null){targetParams.x=dp(250);targetParams.y=dp(650);try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}toast("Positions reset");}
    private void resetSettings(){
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);boolean on=masterEnabled;
        p.edit().clear().putBoolean("master_enabled",on).putBoolean("master_schema_v1",true).putBoolean("volume_combo_cleanup_v1",true).putInt("overlay_hotkey",4).putBoolean("numeric_schema_1000",true).putInt("target_opacity_1000",900).putInt("control_opacity_1000",870).putInt("haptic_strength_1000",314).putLong("interval_ms",30000L).putInt("tap_duration_ms",30).putInt("start_delay_ms",0).apply();
        load();updateKeyFilterState();removeTarget();if(masterEnabled)createTarget();rebuildControl(true);refreshUi();toast("Settings reset");
    }

    private void setSettingsOpen(boolean open){
        settingsOpen=open;if(settingsScroll!=null)settingsScroll.setVisibility(open?View.VISIBLE:View.GONE);
        if(open){collapsed=false;if(buttons!=null)buttons.setVisibility(View.VISIBLE);syncOverlayFields();if(controlParams!=null){controlParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}}
        else if(controlParams!=null){controlParams.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}
    }

    private void syncOverlayFields(){
        setOverlayInterval(intervalMs);if(ovHold!=null)ovHold.setText(String.valueOf(tapDurationMs));if(ovDelay!=null)ovDelay.setText(String.valueOf(startDelayMs));if(ovTargetSize!=null)ovTargetSize.setText(String.valueOf(targetSizeDp));if(ovControlScale!=null)ovControlScale.setText(String.valueOf(controlScale));if(ovTargetOpacity!=null)ovTargetOpacity.setText(String.valueOf(targetOpacity1000));if(ovControlOpacity!=null)ovControlOpacity.setText(String.valueOf(controlOpacity1000));if(ovHaptic!=null)ovHaptic.setText(String.valueOf(hapticStrength1000));
        if(ovShowTarget!=null)setToggleText(ovShowTarget,"Show circular target",targetVisible);if(ovShowControls!=null)setToggleText(ovShowControls,"Show START/STOP controls",controlVisible);if(ovAutoCollapse!=null)setToggleText(ovAutoCollapse,"Auto-collapse on START",autoCollapseStart);if(ovHideTarget!=null)setToggleText(ovHideTarget,"Hide target while auto-tapping",hideTargetRunning);if(ovHapticToggle!=null)setToggleText(ovHapticToggle,"Vibrate START/STOP/overlay",hapticEnabled);if(ovHotkeyMode!=null)setToggleText(ovHotkeyMode,"Custom keybind enabled",hotkeyMode==3);if(ovDebug!=null)setToggleText(ovDebug,"Lightweight debug logging",debug);if(ovComboLabel!=null)ovComboLabel.setText(comboLabelText());updateModeUi();
    }

    private void rebuildControl(boolean reopen){
        int x=controlParams!=null?controlParams.x:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).getInt("control_x",dp(12));int y=controlParams!=null?controlParams.y:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).getInt("control_y",dp(120));
        if(wm!=null&&control!=null)try{wm.removeView(control);}catch(Exception ignored){}
        control=null;topRow=null;buttons=null;settingsScroll=null;settingsContent=null;startStop=null;moveButton=null;tapButton=null;targetButton=null;masterOverlayButton=null;settingsButton=null;controlParams=null;settingsOpen=false;
        createControl();if(controlParams!=null){controlParams.x=x;controlParams.y=y;try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}if(reopen)setSettingsOpen(true);
    }

    private void refreshUi(){
        if(control==null)createControl();if(control==null)return;control.setAlpha(alpha1000(controlOpacity1000));
        if(masterOverlayButton!=null)masterOverlayButton.setText(masterEnabled?"ON":"OFF");
        if(startStop!=null){startStop.setText(running?"STOP":"START");startStop.setVisibility(masterEnabled?View.VISIBLE:View.GONE);}
        if(moveButton!=null){moveButton.setText(moveMode?"LOCK":"MOVE");moveButton.setVisibility(masterEnabled?View.VISIBLE:View.GONE);}
        if(tapButton!=null)tapButton.setVisibility(masterEnabled?View.VISIBLE:View.GONE);if(targetButton!=null)targetButton.setVisibility(masterEnabled?View.VISIBLE:View.GONE);if(settingsButton!=null)settingsButton.setVisibility(View.VISIBLE);
        if(masterEnabled&&target==null)createTarget();if(!masterEnabled)removeTarget();applyTargetOnly();setCollapsed(collapsed,false);updateRunControlState();applyControlVisibility();try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}
    }

    private void setCollapsed(boolean c,boolean save){collapsed=c;if(buttons!=null)buttons.setVisibility(c?View.GONE:View.VISIBLE);if(settingsScroll!=null&&c)settingsScroll.setVisibility(View.GONE);if(c)settingsOpen=false;if(save)getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("control_collapsed",c).apply();if(controlParams!=null&&c)controlParams.flags|=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;if(control!=null&&controlParams!=null)try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}
    private void applyTargetOnly(){if(!masterEnabled||target==null||targetParams==null)return;int s=dp(effectiveTargetDp());targetParams.width=s;targetParams.height=s;target.setTextSize(Math.max(1,effectiveTargetDp()/3f));target.setAlpha(alpha1000(targetOpacity1000));target.setVisibility(targetVisible?View.VISIBLE:View.GONE);touchability();}
    private void applyControlVisibility(){if(control!=null)control.setVisibility(controlVisible&&!hotkeyHidden?View.VISIBLE:View.GONE);}
    private void touchability(){if(target==null||targetParams==null)return;if(moveMode&&!running)targetParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
    private void setMove(boolean e){if(!masterEnabled||running)return;moveMode=e;if(moveButton!=null)moveButton.setText(moveMode?"LOCK":"MOVE");touchability();vibrate(8);}
    private void updateRunControlState(){boolean enabled=masterEnabled&&!running;if(moveButton!=null)moveButton.setEnabled(enabled);if(tapButton!=null)tapButton.setEnabled(enabled);if(targetButton!=null)targetButton.setEnabled(enabled);if(masterOverlayButton!=null)masterOverlayButton.setEnabled(true);if(settingsButton!=null)settingsButton.setEnabled(true);}
    private void cacheTapCoordinates(){if(target==null)return;int[]loc=new int[2];target.getLocationOnScreen(loc);cachedTapX=loc[0]+target.getWidth()/2f;cachedTapY=loc[1]+target.getHeight()/2f;}

    private void toggle(){
        if(!masterEnabled)return;
        if(!running){running=true;tapInProgress=false;cacheTapCoordinates();if(startStop!=null)startStop.setText("STOP");h.removeCallbacks(loop);touchability();updateRunControlState();if(autoCollapseStart&&!collapsed)setCollapsed(true,true);if(hideTargetRunning&&target!=null)target.setVisibility(View.GONE);vibrate(18);log("START intervalMs="+intervalMs+" tapDurationMs="+tapDurationMs+" startDelayMs="+startDelayMs);nextTapUptime=SystemClock.uptimeMillis()+startDelayMs;h.postAtTime(loop,nextTapUptime);}
        else{running=false;h.removeCallbacks(loop);if(startStop!=null)startStop.setText("START");if(hideTargetRunning&&target!=null&&targetVisible)target.setVisibility(View.VISIBLE);touchability();updateRunControlState();vibrate(24);log("STOP intervalMs="+intervalMs);flushLogsNow();}
    }

    private void toggleOverlayHotkey(){hotkeyHidden=!hotkeyHidden;applyControlVisibility();vibrate(14);log("HOTKEY_OVERLAY_HIDDEN="+hotkeyHidden+" combo="+csv(overlayComboCodes)+" master="+(masterEnabled?"ON":"OFF"));}
    private void vibrate(long durationMs){if(!hapticEnabled||hapticStrength1000<=0)return;try{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v==null||!v.hasVibrator())return;int amplitude=clamp(Math.round(hapticStrength1000*255f/1000f),1,255);if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(Math.max(1,durationMs),amplitude));else v.vibrate(Math.max(1,durationMs));}catch(Exception ignored){}}

    private void armCapture(){captureMode=1;captureCodes.clear();captureDown.clear();downKeys.clear();updateKeyFilterState();log("OVERLAY_COMBO_CAPTURE_ARMED");}
    private boolean handleCapture(KeyEvent event){int code=event.getKeyCode();if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0){captureCodes.add(code);captureDown.add(code);}return true;}if(event.getAction()==KeyEvent.ACTION_UP){captureDown.remove(code);if(!captureCodes.isEmpty()&&captureDown.isEmpty())finishCapture();return true;}return true;}
    private void finishCapture(){
        int[]codes=new int[captureCodes.size()];StringBuilder names=new StringBuilder();int i=0;for(int code:captureCodes){codes[i++]=code;if(names.length()>0)names.append(',');names.append(KeyEvent.keyCodeToString(code));}
        String codeCsv=csv(codes),nameCsv=names.toString();overlayComboCodes=codes;hotkeyMode=3;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putString("custom_hotkey_codes",codeCsv).putString("custom_hotkey_names",nameCsv).putInt("overlay_hotkey",3).apply();
        captureMode=0;captureCodes.clear();captureDown.clear();downKeys.clear();updateKeyFilterState();vibrate(18);Intent result=new Intent(ACTION_KEY_CAPTURED);result.setPackage(getPackageName());result.putExtra("keyCodes",codeCsv);result.putExtra("keyNames",nameCsv);sendBroadcast(result);if(ovHotkeyMode!=null)setToggleText(ovHotkeyMode,"Custom keybind enabled",true);if(ovComboLabel!=null)ovComboLabel.setText(comboLabelText());toast("Overlay keybind recorded");log("OVERLAY_COMBO_CAPTURED codes="+codeCsv);
    }
    private int[]parseCodes(String s){if(s==null||s.trim().isEmpty())return new int[0];String[]parts=s.split(",");ArrayList<Integer>out=new ArrayList<>();for(String p:parts){try{int v=Integer.parseInt(p.trim());if(v>0&&!out.contains(v))out.add(v);}catch(Exception ignored){}}int[]r=new int[out.size()];for(int i=0;i<out.size();i++)r[i]=out.get(i);return r;}
    private String csv(int[]a){StringBuilder b=new StringBuilder();for(int v:a){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}
    private boolean allDown(int[]combo){if(combo.length==0)return false;for(int v:combo)if(!downKeys.contains(v))return false;return true;}

    @Override protected boolean onKeyEvent(KeyEvent event){if(captureMode!=0)return handleCapture(event);if(hotkeyMode!=3||overlayComboCodes.length==0)return false;int code=event.getKeyCode(),action=event.getAction();if(action==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)downKeys.add(code);else if(action==KeyEvent.ACTION_UP)downKeys.remove(code);boolean match=allDown(overlayComboCodes);if(match&&!overlayComboLatched){overlayComboLatched=true;toggleOverlayHotkey();log("HOTKEY_PASS_THROUGH combo="+csv(overlayComboCodes));}if(!match)overlayComboLatched=false;return false;}

    private void performTargetTap(boolean manual){
        if(!masterEnabled)return;long n=++seq;if(target==null||wm==null){log("TAP_SKIP seq="+n+" reason=noTarget");return;}if(tapInProgress){if(intervalMs>0)log("TAP_SKIP seq="+n+" reason=inProgress");return;}
        float x,y;if(running&&!manual){x=cachedTapX;y=cachedTapY;}else{int[]loc=new int[2];target.getLocationOnScreen(loc);x=loc[0]+target.getWidth()/2f;y=loc[1]+target.getHeight()/2f;targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
        tapInProgress=true;tapStart=SystemClock.elapsedRealtime();long hold=Math.max(1L,tapDurationMs);log("TAP_REQUEST seq="+n+" manual="+manual+" holdMs="+tapDurationMs);dispatchHoldSegment(n,manual,x,y,hold,null);
    }
    private void dispatchHoldSegment(final long n,final boolean manual,final float x,final float y,final long remaining,final GestureDescription.StrokeDescription previous){if(!masterEnabled||!tapInProgress){tapInProgress=false;return;}if(!manual&&!running&&previous!=null){finish(n,false);return;}final long segment=Math.min(HOLD_SEGMENT_MS,Math.max(1L,remaining));final boolean more=remaining>segment;Path p=new Path();p.moveTo(x,y);final GestureDescription.StrokeDescription stroke=previous==null?new GestureDescription.StrokeDescription(p,0L,segment,more):previous.continueStroke(p,0L,segment,more);GestureDescription g=new GestureDescription.Builder().addStroke(stroke).build();boolean accepted=dispatchGesture(g,new GestureResultCallback(){@Override public void onCompleted(GestureDescription gg){if(masterEnabled&&more&&(manual||running))dispatchHoldSegment(n,manual,x,y,remaining-segment,stroke);else finish(n,false);}@Override public void onCancelled(GestureDescription gg){finish(n,true);}},null);if(!accepted)finish(n,true);}
    private void finish(long n,boolean cancelled){long d=Math.max(0L,SystemClock.elapsedRealtime()-tapStart);tapInProgress=false;log((cancelled?"TAP_CANCEL":"TAP_COMPLETE")+" seq="+n+" durationMs="+d);if(!running)touchability();}

    private void log(String m){if(!debug)return;String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());synchronized(logBuffer){logBuffer.append(ts).append(" | ").append(m).append('\n');}if(!running&&logBuffer.length()>8192)flushLogsAsync();}
    private void flushLogsAsync(){final String batch;synchronized(logBuffer){if(logBuffer.length()==0)return;batch=logBuffer.toString();logBuffer.setLength(0);}logIo.execute(()->writeLogBatch(batch));}
    private void flushLogsNow(){flushLogsAsync();}
    private void writeLogBatch(String batch){try{File f=new File(getFilesDir(),MainActivity.DEBUG_FILE);if(f.exists()&&f.length()>250000)f.delete();FileWriter w=new FileWriter(f,true);w.write(batch);w.close();}catch(Exception ignored){}}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){flushLogsNow();}
    @Override public void onDestroy(){running=false;h.removeCallbacks(loop);captureMode=0;try{unregisterReceiver(receiver);}catch(Exception ignored){}if(wm!=null){if(target!=null)try{wm.removeView(target);}catch(Exception ignored){}if(control!=null)try{wm.removeView(control);}catch(Exception ignored){}}flushLogsNow();logIo.shutdown();super.onDestroy();}

    private class DragListener implements View.OnTouchListener{
        final View v;final WindowManager.LayoutParams p;final String xk,yk;int sx,sy;float dx,dy;
        DragListener(View v,WindowManager.LayoutParams p,String xk,String yk){this.v=v;this.p=p;this.xk=xk;this.yk=yk;}
        @Override public boolean onTouch(View q,MotionEvent e){if(!masterEnabled||running)return true;switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=p.x;sy=p.y;dx=e.getRawX();dy=e.getRawY();return true;case MotionEvent.ACTION_MOVE:p.x=sx+Math.round(e.getRawX()-dx);p.y=sy+Math.round(e.getRawY()-dy);try{wm.updateViewLayout(v,p);}catch(Exception ignored){}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt(xk,p.x).putInt(yk,p.y).apply();return true;}return true;}
    }

    private class MenuListener implements View.OnTouchListener{
        int sx,sy;float dx,dy;long down;boolean drag;
        @Override public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=controlParams.x;sy=controlParams.y;dx=e.getRawX();dy=e.getRawY();down=SystemClock.elapsedRealtime();drag=false;return true;case MotionEvent.ACTION_MOVE:float mx=e.getRawX()-dx,my=e.getRawY()-dy;if(SystemClock.elapsedRealtime()-down>=300L&&(Math.abs(mx)>dp(3)||Math.abs(my)>dp(3)))drag=true;if(drag){controlParams.x=sx+Math.round(mx);controlParams.y=sy+Math.round(my);try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}return true;case MotionEvent.ACTION_UP:if(drag){getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("control_x",controlParams.x).putInt("control_y",controlParams.y).apply();}else{setCollapsed(!collapsed,true);vibrate(8);}return true;case MotionEvent.ACTION_CANCEL:return true;}return true;}
    }
}
