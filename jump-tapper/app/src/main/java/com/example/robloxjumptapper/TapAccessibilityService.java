package com.example.robloxjumptapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TapAccessibilityService extends AccessibilityService {
    private WindowManager wm;
    private TextView target;
    private LinearLayout control, buttons;
    private WindowManager.LayoutParams targetParams, controlParams;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService logIo = Executors.newSingleThreadExecutor();
    private final StringBuilder logBuffer = new StringBuilder();
    private boolean running=false, tapInProgress=false, moveMode=true, targetVisible=true, controlVisible=true, collapsed=false, debug=true;
    private long intervalMs=30000L, seq=0, tapStart=0, nextTapUptime=0;
    private int targetSizeDp=58, controlScale=100;
    private float cachedTapX=0f, cachedTapY=0f;
    private Button startStop, moveButton, tapButton, targetButton;

    private final Runnable loop = new Runnable(){@Override public void run(){
        if(!running)return;
        long now=SystemClock.uptimeMillis();
        long late=Math.max(0L,now-nextTapUptime);
        if(late>10)log("SCHEDULE_LATE ms="+late);
        performTargetTap(false);
        nextTapUptime += Math.max(1L,intervalMs);
        if(nextTapUptime<=now){long missed=((now-nextTapUptime)/Math.max(1L,intervalMs))+1L;nextTapUptime+=missed*Math.max(1L,intervalMs);log("SCHEDULE_CATCHUP skippedSlots="+missed);}
        h.postAtTime(this,nextTapUptime);
    }};

    private final BroadcastReceiver reload=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(running){log("SETTINGS_RELOAD_IGNORED whileRunning=true");return;}
        load();log("SETTINGS_RELOAD intervalMs="+intervalMs+" debug="+debug);apply();
    }};

    @Override protected void onServiceConnected(){super.onServiceConnected();wm=(WindowManager)getSystemService(WINDOW_SERVICE);load();log("SERVICE_CONNECTED sdk="+Build.VERSION.SDK_INT);createTarget();createControl();apply();IntentFilter f=new IntentFilter(MainActivity.ACTION_RELOAD);if(Build.VERSION.SDK_INT>=33)registerReceiver(reload,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(reload,f);}
    private void load(){SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);intervalMs=Math.max(1L,p.getLong("interval_ms",30000L));targetSizeDp=Math.max(30,Math.min(140,p.getInt("target_size_dp",58)));controlScale=Math.max(60,Math.min(160,p.getInt("control_scale",100)));targetVisible=p.getBoolean("target_visible",true);controlVisible=p.getBoolean("control_visible",true);collapsed=p.getBoolean("control_collapsed",false);debug=p.getBoolean("debug_enabled",true);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private int scaled(int v){return dp(Math.max(1,Math.round(v*controlScale/100f)));}
    private GradientDrawable circle(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);d.setStroke(dp(2),stroke);return d;}

    private void createTarget(){if(target!=null)return;target=new TextView(this);target.setText("+");target.setTextColor(Color.WHITE);target.setTypeface(Typeface.DEFAULT_BOLD);target.setGravity(Gravity.CENTER);target.setBackground(circle(0x161976D2,0xCC42A5F5));target.setAlpha(.9f);int s=dp(targetSizeDp);targetParams=new WindowManager.LayoutParams(s,s,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);targetParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);targetParams.x=p.getInt("target_x",dp(250));targetParams.y=p.getInt("target_y",dp(650));target.setOnTouchListener(new DragListener(target,targetParams,"target_x","target_y"));wm.addView(target,targetParams);}

    private void createControl(){if(control!=null)return;control=new LinearLayout(this);control.setOrientation(LinearLayout.HORIZONTAL);control.setGravity(Gravity.CENTER_VERTICAL);control.setBackgroundColor(0xDD202124);TextView menu=new TextView(this);menu.setText("≡");menu.setTextColor(Color.WHITE);menu.setTextSize(22);menu.setGravity(Gravity.CENTER);menu.setBackgroundColor(0xFF3C4043);menu.setOnTouchListener(new MenuListener());control.addView(menu,new LinearLayout.LayoutParams(scaled(46),scaled(46)));buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);startStop=new Button(this);startStop.setText(running?"STOP":"START");startStop.setOnClickListener(v->toggle());buttons.addView(startStop,new LinearLayout.LayoutParams(scaled(84),scaled(46)));moveButton=new Button(this);moveButton.setText(moveMode?"LOCK":"MOVE");moveButton.setOnClickListener(v->setMove(!moveMode));buttons.addView(moveButton,new LinearLayout.LayoutParams(scaled(66),scaled(46)));tapButton=new Button(this);tapButton.setText("TAP");tapButton.setOnClickListener(v->performTargetTap(true));buttons.addView(tapButton,new LinearLayout.LayoutParams(scaled(58),scaled(46)));targetButton=new Button(this);targetButton.setText("J");targetButton.setOnClickListener(v->{if(running)return;targetVisible=!targetVisible;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("target_visible",targetVisible).apply();if(target!=null)target.setVisibility(targetVisible?View.VISIBLE:View.GONE);log("TARGET_VISIBLE="+targetVisible);});buttons.addView(targetButton,new LinearLayout.LayoutParams(scaled(46),scaled(46)));control.addView(buttons);controlParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);controlParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);controlParams.x=p.getInt("control_x",dp(12));controlParams.y=p.getInt("control_y",dp(120));wm.addView(control,controlParams);setCollapsed(collapsed,false);updateRunControlState();}

    private void setCollapsed(boolean c,boolean save){collapsed=c;if(buttons!=null)buttons.setVisibility(c?View.GONE:View.VISIBLE);if(save)getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("control_collapsed",c).apply();try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}log("CONTROL_COLLAPSED="+c);}
    private void apply(){if(running)return;if(target!=null){int s=dp(targetSizeDp);targetParams.width=s;targetParams.height=s;target.setTextSize(Math.max(12,targetSizeDp/3f));target.setVisibility(targetVisible?View.VISIBLE:View.GONE);touchability();}if(control!=null){try{wm.removeView(control);}catch(Exception ignored){}control=null;buttons=null;startStop=null;moveButton=null;tapButton=null;targetButton=null;createControl();control.setVisibility(controlVisible?View.VISIBLE:View.GONE);}}
    private void touchability(){if(target==null)return;if(moveMode&&!running)targetParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
    private void setMove(boolean e){if(running)return;moveMode=e;if(moveButton!=null)moveButton.setText(moveMode?"LOCK":"MOVE");touchability();log("MOVE_MODE="+moveMode);}
    private void updateRunControlState(){boolean enabled=!running;if(moveButton!=null)moveButton.setEnabled(enabled);if(tapButton!=null)tapButton.setEnabled(enabled);if(targetButton!=null)targetButton.setEnabled(enabled);}
    private void cacheTapCoordinates(){if(target==null)return;int[] loc=new int[2];target.getLocationOnScreen(loc);cachedTapX=loc[0]+target.getWidth()/2f;cachedTapY=loc[1]+target.getHeight()/2f;}

    private void toggle(){
        if(!running){
            running=true;tapInProgress=false;cacheTapCoordinates();
            if(startStop!=null)startStop.setText("STOP");
            h.removeCallbacks(loop);touchability();updateRunControlState();
            log("START intervalMs="+intervalMs+" cachedX="+Math.round(cachedTapX)+" cachedY="+Math.round(cachedTapY)+" performance=true");
            nextTapUptime=SystemClock.uptimeMillis();h.postAtTime(loop,nextTapUptime);
        }else{
            running=false;tapInProgress=false;h.removeCallbacks(loop);
            if(startStop!=null)startStop.setText("START");
            touchability();updateRunControlState();
            log("STOP intervalMs="+intervalMs);flushLogsNow();
        }
    }

    private void performTargetTap(boolean manual){
        long n=++seq;
        if(target==null||wm==null){log("TAP_SKIP seq="+n+" reason=noTarget");return;}
        if(tapInProgress){log("TAP_SKIP seq="+n+" reason=inProgress");return;}
        float x,y;
        if(running&&!manual){x=cachedTapX;y=cachedTapY;}else{int[] loc=new int[2];target.getLocationOnScreen(loc);x=loc[0]+target.getWidth()/2f;y=loc[1]+target.getHeight()/2f;targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
        tapInProgress=true;tapStart=SystemClock.elapsedRealtime();
        log("TAP_REQUEST seq="+n+" manual="+manual+" x="+Math.round(x)+" y="+Math.round(y));
        Path p=new Path();p.moveTo(x,y);GestureDescription.StrokeDescription s=new GestureDescription.StrokeDescription(p,0L,30L);GestureDescription g=new GestureDescription.Builder().addStroke(s).build();
        boolean accepted=dispatchGesture(g,new GestureResultCallback(){@Override public void onCompleted(GestureDescription gg){finish(n,false);}@Override public void onCancelled(GestureDescription gg){finish(n,true);}},null);
        log("DISPATCH seq="+n+" accepted="+accepted);if(!accepted)finish(n,true);
    }
    private void finish(long n,boolean cancelled){long d=Math.max(0L,SystemClock.elapsedRealtime()-tapStart);tapInProgress=false;log((cancelled?"TAP_CANCEL":"TAP_COMPLETE")+" seq="+n+" durationMs="+d);if(!running)touchability();}

    private void log(String m){
        if(!debug)return;
        String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());
        synchronized(logBuffer){logBuffer.append(ts).append(" | ").append(m).append('\n');}
        if(!running && logBuffer.length()>8192)flushLogsAsync();
    }
    private void flushLogsAsync(){final String batch;synchronized(logBuffer){if(logBuffer.length()==0)return;batch=logBuffer.toString();logBuffer.setLength(0);}logIo.execute(()->writeLogBatch(batch));}
    private void flushLogsNow(){flushLogsAsync();}
    private void writeLogBatch(String batch){try{File f=new File(getFilesDir(),MainActivity.DEBUG_FILE);if(f.exists()&&f.length()>250000)f.delete();FileWriter w=new FileWriter(f,true);w.write(batch);w.close();}catch(Exception ignored){}}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){log("SERVICE_INTERRUPT");flushLogsNow();}
    @Override public void onDestroy(){log("SERVICE_DESTROY");running=false;h.removeCallbacks(loop);flushLogsNow();try{unregisterReceiver(reload);}catch(Exception ignored){}if(wm!=null){if(target!=null)try{wm.removeView(target);}catch(Exception ignored){}if(control!=null)try{wm.removeView(control);}catch(Exception ignored){}}logIo.shutdown();super.onDestroy();}

    private class DragListener implements View.OnTouchListener{final View v;final WindowManager.LayoutParams p;final String xk,yk;int sx,sy;float dx,dy;DragListener(View v,WindowManager.LayoutParams p,String xk,String yk){this.v=v;this.p=p;this.xk=xk;this.yk=yk;}public boolean onTouch(View q,MotionEvent e){if(running)return true;switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=p.x;sy=p.y;dx=e.getRawX();dy=e.getRawY();return true;case MotionEvent.ACTION_MOVE:p.x=sx+Math.round(e.getRawX()-dx);p.y=sy+Math.round(e.getRawY()-dy);try{wm.updateViewLayout(v,p);}catch(Exception ignored){}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt(xk,p.x).putInt(yk,p.y).apply();return true;}return true;}}
    private class MenuListener implements View.OnTouchListener{int sx,sy;float dx,dy;long down;boolean drag;public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=controlParams.x;sy=controlParams.y;dx=e.getRawX();dy=e.getRawY();down=SystemClock.elapsedRealtime();drag=false;return true;case MotionEvent.ACTION_MOVE:float mx=e.getRawX()-dx,my=e.getRawY()-dy;if(SystemClock.elapsedRealtime()-down>=300L&&(Math.abs(mx)>dp(3)||Math.abs(my)>dp(3)))drag=true;if(drag){controlParams.x=sx+Math.round(mx);controlParams.y=sy+Math.round(my);try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}return true;case MotionEvent.ACTION_UP:if(drag){getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("control_x",controlParams.x).putInt("control_y",controlParams.y).apply();log("CONTROL_MOVED x="+controlParams.x+" y="+controlParams.y);}else setCollapsed(!collapsed,true);return true;case MotionEvent.ACTION_CANCEL:return true;}return true;}}
}
