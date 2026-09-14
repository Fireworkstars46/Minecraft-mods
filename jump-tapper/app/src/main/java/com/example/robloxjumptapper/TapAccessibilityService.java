package com.example.robloxjumptapper;

import android.accessibilityservice.AccessibilityService;
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
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    public static final String ACTION_CAPTURE_KEY = "com.example.robloxjumptapper.CAPTURE_KEY";
    public static final String ACTION_KEY_CAPTURED = "com.example.robloxjumptapper.KEY_CAPTURED";
    public static final String ACTION_CAPTURE_STARTSTOP_KEY = "com.example.robloxjumptapper.CAPTURE_STARTSTOP_KEY";
    public static final String ACTION_STARTSTOP_KEY_CAPTURED = "com.example.robloxjumptapper.STARTSTOP_KEY_CAPTURED";
    private static final int MAX_DAY_MS = 86400000;
    private static final long HOLD_SEGMENT_MS = 59000L;

    private WindowManager wm;
    private TextView target;
    private LinearLayout control, buttons;
    private WindowManager.LayoutParams targetParams, controlParams;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService logIo = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();

    private boolean running=false, tapInProgress=false, moveMode=true, targetVisible=true, controlVisible=true, collapsed=false, debug=true;
    private boolean hotkeyHidden=false, volUpDown=false, volDownDown=false, comboLatched=false;
    private boolean hapticEnabled=true, autoCollapseStart=false, hideTargetRunning=false;
    private boolean overlayComboLatched=false, startStopComboLatched=false;
    private int captureMode=0;
    private final LinkedHashSet<Integer> captureCodes=new LinkedHashSet<>();
    private final HashSet<Integer> captureDown=new HashSet<>();
    private final HashSet<Integer> downKeys=new HashSet<>();

    private long intervalMs=30000L, seq=0, tapStart=0, nextTapUptime=0, lastVolUpDown=0L, lastVolDownDown=0L;
    private int targetSizeDp=58, controlScale=100, hotkeyMode=0, comboFirstKey=0;
    private int startStopHotkeyMode=0;
    private int tapDurationMs=30, startDelayMs=0, targetOpacity1000=900, controlOpacity1000=870, hapticStrength1000=314;
    private int[] overlayComboCodes=new int[0], startStopComboCodes=new int[0];
    private float cachedTapX=0f, cachedTapY=0f;
    private Button startStop, moveButton, tapButton, targetButton;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            long late = Math.max(0L, now - nextTapUptime);
            if (late > 10) log("SCHEDULE_LATE ms=" + late);
            performTargetTap(false);
            nextTapUptime += Math.max(1L, intervalMs);
            if (nextTapUptime <= now) {
                long missed = ((now - nextTapUptime) / Math.max(1L, intervalMs)) + 1L;
                nextTapUptime += missed * Math.max(1L, intervalMs);
                log("SCHEDULE_CATCHUP skippedSlots=" + missed);
            }
            h.postAtTime(this, nextTapUptime);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_CAPTURE_KEY.equals(i.getAction())) { armCapture(1); return; }
            if (ACTION_CAPTURE_STARTSTOP_KEY.equals(i.getAction())) { armCapture(2); return; }
            if (MainActivity.ACTION_RELOAD.equals(i.getAction())) {
                if (running) { log("SETTINGS_RELOAD_IGNORED whileRunning=true"); return; }
                load();
                log("SETTINGS_RELOAD intervalMs="+intervalMs+" tapDurationMs="+tapDurationMs+" startDelayMs="+startDelayMs);
                apply();
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        migrateSettings(); load();
        log("SERVICE_CONNECTED sdk="+Build.VERSION.SDK_INT+" overlayCombo="+csv(overlayComboCodes)+" startStopCombo="+csv(startStopComboCodes));
        createTarget(); createControl(); apply();
        IntentFilter f=new IntentFilter();f.addAction(MainActivity.ACTION_RELOAD);f.addAction(ACTION_CAPTURE_KEY);f.addAction(ACTION_CAPTURE_STARTSTOP_KEY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);
    }

    private void migrateSettings(){
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);SharedPreferences.Editor e=p.edit();boolean changed=false;
        if(!p.getBoolean("hotkey_schema_v2",false)){int old=p.getInt("overlay_hotkey",0);if(old==3)old=4;e.putInt("overlay_hotkey",old).putBoolean("hotkey_schema_v2",true);changed=true;}
        if(!p.contains("custom_hotkey_codes")&&p.getInt("custom_hotkey_keycode",0)!=0){e.putString("custom_hotkey_codes",String.valueOf(p.getInt("custom_hotkey_keycode",0)));e.putString("custom_hotkey_names",p.getString("custom_hotkey_name",""));changed=true;}
        if(!p.contains("startstop_hotkey_codes")&&p.getInt("startstop_hotkey_keycode",0)!=0){e.putString("startstop_hotkey_codes",String.valueOf(p.getInt("startstop_hotkey_keycode",0)));e.putString("startstop_hotkey_names",p.getString("startstop_hotkey_name",""));changed=true;}
        if(!p.getBoolean("numeric_schema_1000",false)){e.putInt("target_opacity_1000",clamp(p.getInt("target_opacity",90)*10,0,1000));e.putInt("control_opacity_1000",clamp(p.getInt("control_opacity",87)*10,0,1000));e.putInt("haptic_strength_1000",clamp(Math.round(p.getInt("haptic_strength",80)*1000f/255f),0,1000));e.putBoolean("numeric_schema_1000",true);changed=true;}
        if(changed)e.apply();
    }

    private void load(){
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);
        intervalMs=Math.max(1L,Math.min(MAX_DAY_MS,p.getLong("interval_ms",30000L)));
        targetSizeDp=clamp(p.getInt("target_size_dp",58),0,1000);
        controlScale=clamp(p.getInt("control_scale",100),0,1000);
        tapDurationMs=clamp(p.getInt("tap_duration_ms",30),0,MAX_DAY_MS);
        startDelayMs=clamp(p.getInt("start_delay_ms",0),0,MAX_DAY_MS);
        targetOpacity1000=clamp(p.getInt("target_opacity_1000",900),0,1000);
        controlOpacity1000=clamp(p.getInt("control_opacity_1000",870),0,1000);
        hapticStrength1000=clamp(p.getInt("haptic_strength_1000",314),0,1000);
        targetVisible=p.getBoolean("target_visible",true);controlVisible=p.getBoolean("control_visible",true);collapsed=p.getBoolean("control_collapsed",false);debug=p.getBoolean("debug_enabled",true);
        hapticEnabled=p.getBoolean("haptic_enabled",true);autoCollapseStart=p.getBoolean("auto_collapse_start",false);hideTargetRunning=p.getBoolean("hide_target_running",false);
        hotkeyMode=p.getInt("overlay_hotkey",0);if(hotkeyMode<0||hotkeyMode>4)hotkeyMode=0;
        startStopHotkeyMode=p.getInt("startstop_hotkey_mode",0);if(startStopHotkeyMode<0||startStopHotkeyMode>1)startStopHotkeyMode=0;
        overlayComboCodes=parseCodes(p.getString("custom_hotkey_codes",""));
        startStopComboCodes=parseCodes(p.getString("startstop_hotkey_codes",""));
    }

    private int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private int effectiveTargetDp(){return Math.max(1,targetSizeDp);}
    private int scaled(int v){return dp(Math.max(1,Math.round(v*Math.max(1,controlScale)/100f)));}
    private float alpha1000(int v){return clamp(v,0,1000)/1000f;}
    private GradientDrawable circle(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);d.setStroke(dp(2),stroke);return d;}

    private void createTarget(){
        if(target!=null)return;target=new TextView(this);target.setText("+");target.setTextColor(Color.WHITE);target.setTypeface(Typeface.DEFAULT_BOLD);target.setGravity(Gravity.CENTER);target.setBackground(circle(0x161976D2,0xCC42A5F5));target.setAlpha(alpha1000(targetOpacity1000));
        int s=dp(effectiveTargetDp());targetParams=new WindowManager.LayoutParams(s,s,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);targetParams.gravity=Gravity.TOP|Gravity.START;
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);targetParams.x=p.getInt("target_x",dp(250));targetParams.y=p.getInt("target_y",dp(650));target.setOnTouchListener(new DragListener(target,targetParams,"target_x","target_y"));wm.addView(target,targetParams);
    }

    private void createControl(){
        if(control!=null)return;control=new LinearLayout(this);control.setOrientation(LinearLayout.HORIZONTAL);control.setGravity(Gravity.CENTER_VERTICAL);control.setBackgroundColor(0xDD202124);control.setAlpha(alpha1000(controlOpacity1000));
        TextView menu=new TextView(this);menu.setText("≡");menu.setTextColor(Color.WHITE);menu.setTextSize(22);menu.setGravity(Gravity.CENTER);menu.setBackgroundColor(0xFF3C4043);menu.setOnTouchListener(new MenuListener());control.addView(menu,new LinearLayout.LayoutParams(scaled(46),scaled(46)));
        buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        startStop=new Button(this);startStop.setText(running?"STOP":"START");startStop.setOnClickListener(v->toggle());buttons.addView(startStop,new LinearLayout.LayoutParams(scaled(84),scaled(46)));
        moveButton=new Button(this);moveButton.setText(moveMode?"LOCK":"MOVE");moveButton.setOnClickListener(v->setMove(!moveMode));buttons.addView(moveButton,new LinearLayout.LayoutParams(scaled(66),scaled(46)));
        tapButton=new Button(this);tapButton.setText("TAP");tapButton.setOnClickListener(v->{vibrate(10);performTargetTap(true);});buttons.addView(tapButton,new LinearLayout.LayoutParams(scaled(58),scaled(46)));
        targetButton=new Button(this);targetButton.setText("J");targetButton.setOnClickListener(v->{if(running)return;targetVisible=!targetVisible;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("target_visible",targetVisible).apply();if(target!=null)target.setVisibility(targetVisible?View.VISIBLE:View.GONE);vibrate(8);log("TARGET_VISIBLE="+targetVisible);});buttons.addView(targetButton,new LinearLayout.LayoutParams(scaled(46),scaled(46)));
        control.addView(buttons);controlParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);controlParams.gravity=Gravity.TOP|Gravity.START;
        SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);controlParams.x=p.getInt("control_x",dp(12));controlParams.y=p.getInt("control_y",dp(120));wm.addView(control,controlParams);setCollapsed(collapsed,false);updateRunControlState();applyControlVisibility();
    }

    private void setCollapsed(boolean c,boolean save){collapsed=c;if(buttons!=null)buttons.setVisibility(c?View.GONE:View.VISIBLE);if(save)getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("control_collapsed",c).apply();try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}log("CONTROL_COLLAPSED="+c);}
    private void apply(){if(running)return;if(target!=null){int s=dp(effectiveTargetDp());targetParams.width=s;targetParams.height=s;target.setTextSize(Math.max(1,effectiveTargetDp()/3f));target.setAlpha(alpha1000(targetOpacity1000));target.setVisibility(targetVisible?View.VISIBLE:View.GONE);touchability();}if(control!=null){try{wm.removeView(control);}catch(Exception ignored){}control=null;buttons=null;startStop=null;moveButton=null;tapButton=null;targetButton=null;createControl();}}
    private void applyControlVisibility(){if(control!=null)control.setVisibility(controlVisible&&!hotkeyHidden?View.VISIBLE:View.GONE);}
    private void touchability(){if(target==null)return;if(moveMode&&!running)targetParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
    private void setMove(boolean e){if(running)return;moveMode=e;if(moveButton!=null)moveButton.setText(moveMode?"LOCK":"MOVE");touchability();vibrate(8);log("MOVE_MODE="+moveMode);}
    private void updateRunControlState(){boolean enabled=!running;if(moveButton!=null)moveButton.setEnabled(enabled);if(tapButton!=null)tapButton.setEnabled(enabled);if(targetButton!=null)targetButton.setEnabled(enabled);}
    private void cacheTapCoordinates(){if(target==null)return;int[] loc=new int[2];target.getLocationOnScreen(loc);cachedTapX=loc[0]+target.getWidth()/2f;cachedTapY=loc[1]+target.getHeight()/2f;}

    private void toggle(){
        if(!running){running=true;tapInProgress=false;cacheTapCoordinates();if(startStop!=null)startStop.setText("STOP");h.removeCallbacks(loop);touchability();updateRunControlState();if(autoCollapseStart&&!collapsed)setCollapsed(true,true);if(hideTargetRunning&&target!=null)target.setVisibility(View.GONE);vibrate(18);log("START intervalMs="+intervalMs+" tapDurationMs="+tapDurationMs+" startDelayMs="+startDelayMs+" cachedX="+Math.round(cachedTapX)+" cachedY="+Math.round(cachedTapY));nextTapUptime=SystemClock.uptimeMillis()+startDelayMs;h.postAtTime(loop,nextTapUptime);}else{running=false;h.removeCallbacks(loop);if(startStop!=null)startStop.setText("START");if(hideTargetRunning&&target!=null&&targetVisible)target.setVisibility(View.VISIBLE);touchability();updateRunControlState();vibrate(24);log("STOP intervalMs="+intervalMs);flushLogsNow();}
    }
    private void toggleOverlayHotkey(){hotkeyHidden=!hotkeyHidden;applyControlVisibility();vibrate(14);log("HOTKEY_OVERLAY_HIDDEN="+hotkeyHidden+" combo="+csv(overlayComboCodes));}
    private void vibrate(long durationMs){if(!hapticEnabled||hapticStrength1000<=0)return;try{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v==null||!v.hasVibrator())return;int amplitude=clamp(Math.round(hapticStrength1000*255f/1000f),1,255);if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(Math.max(1,durationMs),amplitude));else v.vibrate(Math.max(1,durationMs));}catch(Exception ignored){}}

    private void armCapture(int mode){captureMode=mode;captureCodes.clear();captureDown.clear();log(mode==1?"OVERLAY_COMBO_CAPTURE_ARMED":"STARTSTOP_COMBO_CAPTURE_ARMED");}
    private boolean handleCapture(KeyEvent event){int code=event.getKeyCode();if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0){captureCodes.add(code);captureDown.add(code);}return true;}if(event.getAction()==KeyEvent.ACTION_UP){captureDown.remove(code);if(!captureCodes.isEmpty()&&captureDown.isEmpty())finishCapture();return true;}return true;}
    private void finishCapture(){int[] codes=new int[captureCodes.size()];StringBuilder names=new StringBuilder();int i=0;for(int code:captureCodes){codes[i++]=code;if(names.length()>0)names.append(',');names.append(KeyEvent.keyCodeToString(code));}String codeCsv=csv(codes),nameCsv=names.toString();SharedPreferences.Editor e=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit();Intent result;if(captureMode==1){overlayComboCodes=codes;hotkeyMode=3;e.putString("custom_hotkey_codes",codeCsv).putString("custom_hotkey_names",nameCsv).putInt("overlay_hotkey",3);result=new Intent(ACTION_KEY_CAPTURED);log("OVERLAY_COMBO_CAPTURED codes="+codeCsv);}else{startStopComboCodes=codes;startStopHotkeyMode=1;e.putString("startstop_hotkey_codes",codeCsv).putString("startstop_hotkey_names",nameCsv).putInt("startstop_hotkey_mode",1);result=new Intent(ACTION_STARTSTOP_KEY_CAPTURED);log("STARTSTOP_COMBO_CAPTURED codes="+codeCsv);}e.apply();captureMode=0;captureCodes.clear();captureDown.clear();vibrate(18);result.setPackage(getPackageName());result.putExtra("keyCodes",codeCsv);result.putExtra("keyNames",nameCsv);sendBroadcast(result);}

    private int[] parseCodes(String s){if(s==null||s.trim().isEmpty())return new int[0];String[] parts=s.split(",");ArrayList<Integer> out=new ArrayList<>();for(String p:parts){try{int v=Integer.parseInt(p.trim());if(v>0&&!out.contains(v))out.add(v);}catch(Exception ignored){}}int[] r=new int[out.size()];for(int i=0;i<out.size();i++)r[i]=out.get(i);return r;}
    private String csv(int[] a){StringBuilder b=new StringBuilder();for(int v:a){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}
    private boolean contains(int[] a,int code){for(int v:a)if(v==code)return true;return false;}
    private boolean allDown(int[] combo){if(combo.length==0)return false;for(int v:combo)if(!downKeys.contains(v))return false;return true;}
    private void compensateFirstComboVolumeKey(){AudioManager a=(AudioManager)getSystemService(AUDIO_SERVICE);if(a==null)return;if(comboFirstKey==KeyEvent.KEYCODE_VOLUME_UP)a.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_LOWER,0);else if(comboFirstKey==KeyEvent.KEYCODE_VOLUME_DOWN)a.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_RAISE,0);}

    @Override protected boolean onKeyEvent(KeyEvent event){
        if(captureMode!=0)return handleCapture(event);int code=event.getKeyCode(),action=event.getAction();if(action==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)downKeys.add(code);else if(action==KeyEvent.ACTION_UP)downKeys.remove(code);
        if(startStopHotkeyMode==1&&startStopComboCodes.length>0){boolean match=allDown(startStopComboCodes);if(match&&!startStopComboLatched){startStopComboLatched=true;log("BACKGROUND_STARTSTOP_COMBO runningBefore="+running+" codes="+csv(startStopComboCodes));toggle();}if(!match)startStopComboLatched=false;if(contains(startStopComboCodes,code))return true;}
        if(hotkeyMode==3&&overlayComboCodes.length>0){boolean match=allDown(overlayComboCodes);if(match&&!overlayComboLatched){overlayComboLatched=true;toggleOverlayHotkey();}if(!match)overlayComboLatched=false;if(contains(overlayComboCodes,code))return true;}
        if(hotkeyMode==4)return false;if(hotkeyMode==1&&code==KeyEvent.KEYCODE_VOLUME_UP){if(action==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)toggleOverlayHotkey();return true;}if(hotkeyMode==2&&code==KeyEvent.KEYCODE_VOLUME_DOWN){if(action==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)toggleOverlayHotkey();return true;}
        if(hotkeyMode==0&&(code==KeyEvent.KEYCODE_VOLUME_UP||code==KeyEvent.KEYCODE_VOLUME_DOWN)){long now=SystemClock.uptimeMillis();if(action==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(code==KeyEvent.KEYCODE_VOLUME_UP){volUpDown=true;lastVolUpDown=now;if(!volDownDown)comboFirstKey=KeyEvent.KEYCODE_VOLUME_UP;}else{volDownDown=true;lastVolDownDown=now;if(!volUpDown)comboFirstKey=KeyEvent.KEYCODE_VOLUME_DOWN;}boolean together=volUpDown&&volDownDown&&Math.abs(lastVolUpDown-lastVolDownDown)<=350L;if(together&&!comboLatched){comboLatched=true;compensateFirstComboVolumeKey();toggleOverlayHotkey();return true;}}else if(action==KeyEvent.ACTION_UP){if(code==KeyEvent.KEYCODE_VOLUME_UP)volUpDown=false;else volDownDown=false;if(!volUpDown&&!volDownDown){comboLatched=false;comboFirstKey=0;}}return false;}return false;
    }

    private void performTargetTap(boolean manual){
        long n=++seq;if(target==null||wm==null){log("TAP_SKIP seq="+n+" reason=noTarget");return;}if(tapInProgress){log("TAP_SKIP seq="+n+" reason=inProgress");return;}
        float x,y;if(running&&!manual){x=cachedTapX;y=cachedTapY;}else{int[] loc=new int[2];target.getLocationOnScreen(loc);x=loc[0]+target.getWidth()/2f;y=loc[1]+target.getHeight()/2f;targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
        tapInProgress=true;tapStart=SystemClock.elapsedRealtime();long hold=Math.max(1L,tapDurationMs);log("TAP_REQUEST seq="+n+" manual="+manual+" x="+Math.round(x)+" y="+Math.round(y)+" holdMs="+tapDurationMs);dispatchHoldSegment(n,manual,x,y,hold,null);
    }

    private void dispatchHoldSegment(final long n, final boolean manual, final float x, final float y, final long remaining, final GestureDescription.StrokeDescription previous){
        if(!tapInProgress)return;
        if(!manual&&!running&&previous!=null){finish(n,false);return;}
        final long segment=Math.min(HOLD_SEGMENT_MS,Math.max(1L,remaining));
        final boolean more=remaining>segment;
        Path p=new Path();p.moveTo(x,y);
        final GestureDescription.StrokeDescription stroke = previous==null ? new GestureDescription.StrokeDescription(p,0L,segment,more) : previous.continueStroke(p,0L,segment,more);
        GestureDescription g=new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted=dispatchGesture(g,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription gg){if(more && (manual||running))dispatchHoldSegment(n,manual,x,y,remaining-segment,stroke);else finish(n,false);}
            @Override public void onCancelled(GestureDescription gg){finish(n,true);}
        },null);
        log("DISPATCH_SEGMENT seq="+n+" accepted="+accepted+" segmentMs="+segment+" remainingMs="+remaining+" more="+more);
        if(!accepted)finish(n,true);
    }

    private void finish(long n,boolean cancelled){long d=Math.max(0L,SystemClock.elapsedRealtime()-tapStart);tapInProgress=false;log((cancelled?"TAP_CANCEL":"TAP_COMPLETE")+" seq="+n+" durationMs="+d);if(!running)touchability();}
    private void log(String m){if(!debug)return;String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());synchronized(logBuffer){logBuffer.append(ts).append(" | ").append(m).append('\n');}if(!running&&logBuffer.length()>8192)flushLogsAsync();}
    private void flushLogsAsync(){final String batch;synchronized(logBuffer){if(logBuffer.length()==0)return;batch=logBuffer.toString();logBuffer.setLength(0);}logIo.execute(()->writeLogBatch(batch));}
    private void flushLogsNow(){flushLogsAsync();}
    private void writeLogBatch(String batch){try{File f=new File(getFilesDir(),MainActivity.DEBUG_FILE);if(f.exists()&&f.length()>250000)f.delete();FileWriter w=new FileWriter(f,true);w.write(batch);w.close();}catch(Exception ignored){}}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){log("SERVICE_INTERRUPT");flushLogsNow();}
    @Override public void onDestroy(){log("SERVICE_DESTROY");running=false;h.removeCallbacks(loop);flushLogsNow();try{unregisterReceiver(receiver);}catch(Exception ignored){}if(wm!=null){if(target!=null)try{wm.removeView(target);}catch(Exception ignored){}if(control!=null)try{wm.removeView(control);}catch(Exception ignored){}}logIo.shutdown();super.onDestroy();}

    private class DragListener implements View.OnTouchListener{
        final View v;final WindowManager.LayoutParams p;final String xk,yk;int sx,sy;float dx,dy;
        DragListener(View v,WindowManager.LayoutParams p,String xk,String yk){this.v=v;this.p=p;this.xk=xk;this.yk=yk;}
        @Override public boolean onTouch(View q,MotionEvent e){if(running)return true;switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=p.x;sy=p.y;dx=e.getRawX();dy=e.getRawY();return true;case MotionEvent.ACTION_MOVE:p.x=sx+Math.round(e.getRawX()-dx);p.y=sy+Math.round(e.getRawY()-dy);try{wm.updateViewLayout(v,p);}catch(Exception ignored){}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt(xk,p.x).putInt(yk,p.y).apply();return true;}return true;}
    }
    private class MenuListener implements View.OnTouchListener{
        int sx,sy;float dx,dy;long down;boolean drag;
        @Override public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=controlParams.x;sy=controlParams.y;dx=e.getRawX();dy=e.getRawY();down=SystemClock.elapsedRealtime();drag=false;return true;case MotionEvent.ACTION_MOVE:float mx=e.getRawX()-dx,my=e.getRawY()-dy;if(SystemClock.elapsedRealtime()-down>=300L&&(Math.abs(mx)>dp(3)||Math.abs(my)>dp(3)))drag=true;if(drag){controlParams.x=sx+Math.round(mx);controlParams.y=sy+Math.round(my);try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}return true;case MotionEvent.ACTION_UP:if(drag){getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("control_x",controlParams.x).putInt("control_y",controlParams.y).apply();log("CONTROL_MOVED x="+controlParams.x+" y="+controlParams.y);}else{setCollapsed(!collapsed,true);vibrate(8);}return true;case MotionEvent.ACTION_CANCEL:return true;}return true;}
    }
}
