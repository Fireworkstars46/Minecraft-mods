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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TapAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private TextView target, restoreBubble;
    private LinearLayout control;
    private WindowManager.LayoutParams targetParams, controlParams, restoreParams;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running=false, moveMode=true, tapInProgress=false;
    private long intervalMs=30000L;
    private boolean targetVisible=true, controlVisible=true;
    private int targetSizeDp=58, controlScale=100;
    private Button startStop, moveButton;

    private final Runnable tapLoop=new Runnable(){@Override public void run(){if(!running)return;performTargetTap(false);handler.postDelayed(this,Math.max(1L,intervalMs));}};
    private final BroadcastReceiver reloadReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){loadSettings();applyOverlaySettings();if(running){handler.removeCallbacks(tapLoop);handler.post(tapLoop);}}};

    @Override protected void onServiceConnected(){super.onServiceConnected();windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);loadSettings();createTarget();createControl();createRestoreBubble();applyOverlaySettings();IntentFilter f=new IntentFilter(MainActivity.ACTION_RELOAD);if(Build.VERSION.SDK_INT>=33)registerReceiver(reloadReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(reloadReceiver,f);}
    private void loadSettings(){SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);intervalMs=Math.max(1L,p.getLong("interval_ms",30000L));targetSizeDp=Math.max(30,Math.min(140,p.getInt("target_size_dp",58)));controlScale=Math.max(60,Math.min(160,p.getInt("control_scale",100)));targetVisible=p.getBoolean("target_visible",true);controlVisible=p.getBoolean("control_visible",true);}
    private GradientDrawable circleBackground(int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);d.setStroke(dp(2),stroke);return d;}

    private void createTarget(){if(target!=null)return;target=new TextView(this);target.setText("+");target.setTextColor(Color.WHITE);target.setTypeface(Typeface.DEFAULT_BOLD);target.setGravity(Gravity.CENTER);target.setBackground(circleBackground(0x161976D2,0xCC42A5F5));target.setAlpha(0.9f);int size=dp(targetSizeDp);targetParams=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);targetParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);targetParams.x=p.getInt("target_x",dp(250));targetParams.y=p.getInt("target_y",dp(650));target.setOnTouchListener(new DragTouchListener(target,targetParams,"target_x","target_y"));windowManager.addView(target,targetParams);}
    private int scaled(int v){return dp(Math.max(1,Math.round(v*controlScale/100f)));}
    private void createControl(){if(control!=null)return;control=new LinearLayout(this);control.setOrientation(LinearLayout.HORIZONTAL);control.setGravity(Gravity.CENTER_VERTICAL);control.setBackgroundColor(0xDD202124);TextView h=new TextView(this);h.setText("≡");h.setTextColor(Color.WHITE);h.setGravity(Gravity.CENTER);h.setBackgroundColor(0xFF3C4043);control.addView(h,new LinearLayout.LayoutParams(scaled(46),scaled(46)));startStop=new Button(this);startStop.setText(running?"STOP":"START");startStop.setOnClickListener(v->toggleRunning());control.addView(startStop,new LinearLayout.LayoutParams(scaled(84),scaled(46)));moveButton=new Button(this);moveButton.setText(moveMode?"LOCK":"MOVE");moveButton.setOnClickListener(v->setMoveMode(!moveMode));control.addView(moveButton,new LinearLayout.LayoutParams(scaled(66),scaled(46)));Button tap=new Button(this);tap.setText("TAP");tap.setOnClickListener(v->performTargetTap(true));control.addView(tap,new LinearLayout.LayoutParams(scaled(58),scaled(46)));Button j=new Button(this);j.setText("J");j.setOnClickListener(v->{targetVisible=!targetVisible;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("target_visible",targetVisible).apply();if(target!=null)target.setVisibility(targetVisible?View.VISIBLE:View.GONE);});control.addView(j,new LinearLayout.LayoutParams(scaled(46),scaled(46)));Button x=new Button(this);x.setText("×");x.setOnClickListener(v->hideControls());control.addView(x,new LinearLayout.LayoutParams(scaled(46),scaled(46)));controlParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);controlParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);controlParams.x=p.getInt("control_x",dp(12));controlParams.y=p.getInt("control_y",dp(120));h.setOnTouchListener(new DragTouchListener(control,controlParams,"control_x","control_y"));windowManager.addView(control,controlParams);}
    private void createRestoreBubble(){if(restoreBubble!=null)return;restoreBubble=new TextView(this);restoreBubble.setText("+");restoreBubble.setTextColor(Color.WHITE);restoreBubble.setTextSize(22);restoreBubble.setGravity(Gravity.CENTER);restoreBubble.setTypeface(Typeface.DEFAULT_BOLD);restoreBubble.setBackground(circleBackground(0xDD3C4043,0xFFFFFFFF));int s=dp(44);restoreParams=new WindowManager.LayoutParams(s,s,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);restoreParams.gravity=Gravity.TOP|Gravity.START;SharedPreferences p=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE);restoreParams.x=p.getInt("restore_x",dp(8));restoreParams.y=p.getInt("restore_y",dp(180));restoreBubble.setOnTouchListener(new BubbleTouchListener());windowManager.addView(restoreBubble,restoreParams);}
    private void hideControls(){controlVisible=false;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("control_visible",false).apply();if(control!=null)control.setVisibility(View.GONE);if(restoreBubble!=null)restoreBubble.setVisibility(View.VISIBLE);}
    private void showControls(){controlVisible=true;getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putBoolean("control_visible",true).apply();if(control!=null)control.setVisibility(View.VISIBLE);if(restoreBubble!=null)restoreBubble.setVisibility(View.GONE);}
    private void applyOverlaySettings(){if(target!=null&&targetParams!=null){int s=dp(targetSizeDp);targetParams.width=s;targetParams.height=s;target.setTextSize(Math.max(12,targetSizeDp/3f));target.setVisibility(targetVisible?View.VISIBLE:View.GONE);applyTargetTouchability();}if(control!=null){try{windowManager.removeView(control);}catch(Exception ignored){}control=null;startStop=null;moveButton=null;createControl();control.setVisibility(controlVisible?View.VISIBLE:View.GONE);}if(restoreBubble!=null)restoreBubble.setVisibility(controlVisible?View.GONE:View.VISIBLE);}
    private void applyTargetTouchability(){if(target==null||targetParams==null||windowManager==null)return;if(moveMode&&!running)targetParams.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;else targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{windowManager.updateViewLayout(target,targetParams);}catch(Exception ignored){}}
    private void setMoveMode(boolean e){moveMode=e;if(moveButton!=null)moveButton.setText(moveMode?"LOCK":"MOVE");applyTargetTouchability();}
    private void toggleRunning(){running=!running;tapInProgress=false;if(startStop!=null)startStop.setText(running?"STOP":"START");handler.removeCallbacks(tapLoop);applyTargetTouchability();if(running)handler.post(tapLoop);}

    private void performTargetTap(boolean manual){if(target==null||targetParams==null||windowManager==null||tapInProgress)return;int[] loc=new int[2];target.getLocationOnScreen(loc);float x=loc[0]+target.getWidth()/2f,y=loc[1]+target.getHeight()/2f;tapInProgress=true;targetParams.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;try{windowManager.updateViewLayout(target,targetParams);}catch(Exception ignored){}Path p=new Path();p.moveTo(x,y);GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(p,0L,30L);GestureDescription g=new GestureDescription.Builder().addStroke(stroke).build();boolean ok=dispatchGesture(g,new GestureResultCallback(){@Override public void onCompleted(GestureDescription g){finishTap();}@Override public void onCancelled(GestureDescription g){finishTap();}},null);if(!ok)finishTap();}
    private void finishTap(){tapInProgress=false;if(!running)applyTargetTouchability();}

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){running=false;handler.removeCallbacksAndMessages(null);try{unregisterReceiver(reloadReceiver);}catch(Exception ignored){}if(windowManager!=null){if(target!=null)try{windowManager.removeView(target);}catch(Exception ignored){}if(control!=null)try{windowManager.removeView(control);}catch(Exception ignored){}if(restoreBubble!=null)try{windowManager.removeView(restoreBubble);}catch(Exception ignored){}}super.onDestroy();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private class DragTouchListener implements View.OnTouchListener{private final View moved;private final WindowManager.LayoutParams params;private final String xk,yk;private int sx,sy;private float dx,dy;DragTouchListener(View m,WindowManager.LayoutParams p,String x,String y){moved=m;params=p;xk=x;yk=y;}@Override public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=params.x;sy=params.y;dx=e.getRawX();dy=e.getRawY();return true;case MotionEvent.ACTION_MOVE:params.x=sx+Math.round(e.getRawX()-dx);params.y=sy+Math.round(e.getRawY()-dy);try{windowManager.updateViewLayout(moved,params);}catch(Exception ignored){}return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt(xk,params.x).putInt(yk,params.y).apply();return true;}return true;}}
    private class BubbleTouchListener implements View.OnTouchListener{private int sx,sy;private float dx,dy;private boolean moved;@Override public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:sx=restoreParams.x;sy=restoreParams.y;dx=e.getRawX();dy=e.getRawY();moved=false;return true;case MotionEvent.ACTION_MOVE:float x=e.getRawX()-dx,y=e.getRawY()-dy;if(Math.abs(x)>dp(4)||Math.abs(y)>dp(4))moved=true;restoreParams.x=sx+Math.round(x);restoreParams.y=sy+Math.round(y);try{windowManager.updateViewLayout(restoreBubble,restoreParams);}catch(Exception ignored){}return true;case MotionEvent.ACTION_UP:getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).edit().putInt("restore_x",restoreParams.x).putInt("restore_y",restoreParams.y).apply();if(!moved)showControls();return true;case MotionEvent.ACTION_CANCEL:return true;}return true;}}
}
