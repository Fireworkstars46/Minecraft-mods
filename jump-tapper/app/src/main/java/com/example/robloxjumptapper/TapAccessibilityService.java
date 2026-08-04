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

public class TapAccessibilityService extends AccessibilityService {
    private WindowManager wm;
    private TextView target;
    private LinearLayout control, buttons;
    private WindowManager.LayoutParams targetParams, controlParams;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running=false, tapInProgress=false, moveMode=true, targetVisible=true, controlVisible=true, collapsed=false, debug=true;
    private long intervalMs=30000L, seq=0, tapStart=0, nextTapUptime=0;
    private int targetSizeDp=58, controlScale=100;
    private Button startStop, moveButton;

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

    private final BroadcastReceiver reload=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){load();log("SETTINGS_RELOAD intervalMs="+intervalMs+" debug="+debug);apply();if(running){h.removeCallbacks(loop);nextTapUptime=SystemClock.uptimeMillis();h.postAtTime(loop,nextTapUptime);}}};

    @Override protected void onServiceConnected(){super.onServiceConnected();wm=(WindowManager)getSystemService(WINDOW_SERVICE);load();log("SERVICE_CONNECTED sdk="+Build.VERSION.SDK_INT);createTarget();createControl();apply();IntentFilter f=new IntentFilter(MainActivity.ACTION_RELOAD);if(Build.VERSION.SDK_INT>=33)registerReceiver(reload,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(reload,f);}
    private void load(){SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);intervalMs=Math.max(1L,p.getLong("interval_ms",30000L));targetSizeDp=Math.max(30,Math.min(140,p.getInt("target_size_dp",58)));controlScale=Math.max(60,Math.min(160,p.getInt("control_scale",100)));targetVisible=p.getBoolean("target_visible",true);controlVisible=p.getBoolean("control_visible",true);collapsed=p.getBoolean("control_collapsed",false);debug=p.getBoolean("debug_enabled",true);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private int scaled(int v){return dp(Math.max(1,Math.round(v*controlScale/100f)));}
    private GradientDrawable circle(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);d.setStroke(dp(2),stroke);return d;}

    private void createTarget(){if(target!=null)return;target=new TextView(this);target.setText("+");target.setTextColor(Color.WHITE);target.setTypeface(Typeface.DEFAULT_BOLD);target.setGravity(Gravity.CENTER);target.setBackground(circle(0x161976D2,0xCC42A5F5));target.setAlpha(.9f);int s=dp(targetSizeDp);targetParams=new WindowManager.LayoutParams(s,s,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);targetParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);targetParams.x=p.getInt("target_x",dp(250));targetParams.y=p.getInt("target_y",dp(650));target.setOnTouchListener(new DragListener(target,targetParams,"target_x","target_y"));wm.addView(target,targetParams);}

    private void createControl(){if(control!=null)return;control=new LinearLayout(this);control.setOrientation(LinearLayout.HORIZONTAL);control.setGravity(Gravity.CENTER_VERTICAL);control.setBackgroundColor(0xDD202124);TextView menu=new TextView(this);menu.setText("≡");menu.setTextColor(Color.WHITE);menu.setTextSize(22);menu.setGravity(Gravity.CENTER);menu.setBackgroundColor(0xFF3C4043);menu.setOnTouchListener(new MenuListener());control.addView(menu,new LinearLayout.LayoutParams(scaled(46),scaled(46)));buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);startStop=new Button(this);startStop.setText(running?"STOP":"START");startStop.setOnClickListener(v->toggle());buttons.addView(startStop,new LinearLayout.LayoutParams(scaled(84),scaled(46)));moveButton=new Button(this);moveButton.setText(moveMode?"LOCK":"MOVE");moveButton.setOnClickListener(v->setMove(!moveMode));buttons.addView(moveButton,new LinearLayout.LayoutParams(scaled(66),scaled(46)));Button tap=new Button(this);tap.setText("TAP");tap.setOnClickListener(v->performTargetTap(true));buttons.addView(tap,new LinearLayout.LayoutParams(scaled(58),scaled(46)));Button j=new Button(this);j.setText("J");j.setOnClickListener(v->{targetVisible=!targetVisible;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("target_visible",targetVisible).apply();if(target!=null)target.setVisibility(targetVisible?View.VISIBLE:View.GONE);log("TARGET_VISIBLE="+targetVisible);});buttons.addView(j,new LinearLayout.LayoutParams(scaled(46),scaled(46)));control.addView(buttons);controlParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);controlParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);controlParams.x=p.getInt("control_x",dp(12));controlParams.y=p.getInt("control_y",dp(120));wm.addView(control,controlParams);setCollapsed(collapsed,false);}

    private void setCollapsed(boolean c,boolean save){collapsed=c;if(buttons!=null)buttons.setVisibility(c?View.GONE:View.VISIBLE);if(save)getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("control_collapsed",c).apply();try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}log("CONTROL_COLLAPSED="+c);}
    private void apply(){if(target!=null){int s=dp(targetSizeDp);targetParams.width=s;targetParams.height=s;target.setTextSize(Math.max(12,targetSizeDp/3f));target.setVisibility(targetVisible?View.VISIBLE:View.GONE);touchability();}if(control!=null){try{wm.removeView(control);}catch(Exception ignored){}control=null;buttons=null;startStop=null;moveButton=null;createControl();control.setVisibility(controlVisible?View.VISIBLE:View.GONE);}}
    private void touchability(){if(target==null)return;if(moveMode&&!running)targetParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
    private void setMove(boolean e){moveMode=e;if(moveButton!=null)moveButton.setText(moveMode?"LOCK":"MOVE");touchability();log("MOVE_MODE="+moveMode);}

    private void toggle(){running=!running;tapInProgress=false;if(startStop!=null)startStop.setText(running?"STOP":"START");h.removeCallbacks(loop);touchability();log((running?"START":"STOP")+" intervalMs="+intervalMs);if(running){nextTapUptime=SystemClock.uptimeMillis();h.postAtTime(loop,nextTapUptime);}}

    private void performTargetTap(boolean manual){long n=++seq;if(target==null||wm==null){log("TAP_SKIP seq="+n+" reason=noTarget");return;}if(tapInProgress){log("TAP_SKIP seq="+n+" reason=inProgress");return;}int[] loc=new int[2];target.getLocationOnScreen(loc);float x=loc[0]+target.getWidth()/2f,y=loc[1]+target.getHeight()/2f;tapInProgress=true;tapStart=SystemClock.elapsedRealtime();targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{wm.updateViewLayout(target,targetParams);}catch(Exception ignored){}log("TAP_REQUEST seq="+n+" manual="+manual+" x="+Math.round(x)+" y="+Math.round(y));Path p=new Path();p.moveTo(x,y);GestureDescription.StrokeDescription s=new GestureDescription.StrokeDescription(p,0L,30L);GestureDescription g=new GestureDescription.Builder().addStroke(s).build();boolean accepted=dispatchGesture(g,new GestureResultCallback(){@Override public void onCompleted(GestureDescription gg){finish(n,false);}@Override public void onCancelled(GestureDescription gg){finish(n,true);}},null);log("DISPATCH seq="+n+" accepted="+accepted);if(!accepted)finish(n,true);}
    private void finish(long n,boolean cancelled){long d=Math.max(0L,SystemClock.elapsedRealtime()-tapStart);tapInProgress=false;log((cancelled?"TAP_CANCEL":"TAP_COMPLETE")+" seq="+n+" durationMs="+d);if(!running)touchability();}
    private void log(String m){if(!debug)return;try{File f=new File(getFilesDir(),MainActivity.DEBUG_FILE);if(f.exists()&&f.length()>250000)f.delete();String ts=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());FileWriter w=new FileWriter(f,true);w.write(ts+" | "+m+"\n");w.close();}catch(Exception ignored){}}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){log("SERVICE_INTERRUPT");}
    @Override public void onDestroy(){log("SERVICE_DESTROY");running=false;h.removeCallbacksAndMessages(null);try{unregisterReceiver(reload);}catch(Exception ignored){}if(wm!=null){if(target!=null)try{wm.removeView(target);}catch(Exception ignored){}if(control!=null)try{wm.removeView(control);}catch(Exception ignored){}}super.onDestroy();}

    private class DragListener implements View.OnTouchListener{final View v;final WindowManager.LayoutParams p;final String xk,yk;int sx,sy;float dx,dy;DragListener(View v,WindowManager.LayoutParams p,String xk,String yk){this.v=v;this.p=p;this.xk=xk;this.yk=yk;}public boolean onTouch(View q,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=p.x;sy=p.y;dx=e.getRawX();dy=e.getRawY();return true;case MotionEvent.ACTION_MOVE:p.x=sx+Math.round(e.getRawX()-dx);p.y=sy+Math.round(e.getRawY()-dy);try{wm.updateViewLayout(v,p);}catch(Exception ignored){}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt(xk,p.x).putInt(yk,p.y).apply();return true;}return true;}}
    private class MenuListener implements View.OnTouchListener{int sx,sy;float dx,dy;long down;boolean drag;public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=controlParams.x;sy=controlParams.y;dx=e.getRawX();dy=e.getRawY();down=SystemClock.elapsedRealtime();drag=false;return true;case MotionEvent.ACTION_MOVE:float mx=e.getRawX()-dx,my=e.getRawY()-dy;if(SystemClock.elapsedRealtime()-down>=300L&&(Math.abs(mx)>dp(3)||Math.abs(my)>dp(3)))drag=true;if(drag){controlParams.x=sx+Math.round(mx);controlParams.y=sy+Math.round(my);try{wm.updateViewLayout(control,controlParams);}catch(Exception ignored){}}return true;case MotionEvent.ACTION_UP:if(drag){getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("control_x",controlParams.x).putInt("control_y",controlParams.y).apply();log("CONTROL_MOVED x="+controlParams.x+" y="+controlParams.y);}else setCollapsed(!collapsed,true);return true;case MotionEvent.ACTION_CANCEL:return true;}return true;}}
}
