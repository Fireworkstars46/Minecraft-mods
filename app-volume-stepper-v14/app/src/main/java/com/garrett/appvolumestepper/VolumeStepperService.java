package com.garrett.appvolumestepper;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class VolumeStepperService extends AccessibilityService {
    private WindowManager wm; private LinearLayout box; private TextView label;
    private WindowManager.LayoutParams lp; private final Handler h = new Handler();
    private Rect selectedBounds; private String selectedLabel = "APP";

    @Override public void onServiceConnected() { wm=(WindowManager)getSystemService(WINDOW_SERVICE); buildOverlay(); refresh(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED || e.getEventType()==AccessibilityEvent.TYPE_VIEW_SELECTED) selectFromEvent(e);
        h.removeCallbacksAndMessages(null); h.postDelayed(this::refresh, 60); h.postDelayed(this::refresh, 220);
    }
    @Override public void onInterrupt() {}
    @Override public void onDestroy(){ if(box!=null) try{wm.removeView(box);}catch(Exception ignored){} super.onDestroy(); }

    private void buildOverlay(){
        box=new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setPadding(8,8,8,8); box.setBackgroundColor(0xEE222222);
        TextView minus=button("−"), plus=button("+"); label=button("APP"); label.setMinWidth(150);
        minus.setOnClickListener(v->step(-1)); plus.setOnClickListener(v->step(1));
        box.addView(minus); box.addView(label); box.addView(plus);
        SharedPreferences p=getSharedPreferences("p",0);
        lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT; lp.x=p.getInt("x",40); lp.y=p.getInt("y",1300);
        label.setOnTouchListener(new View.OnTouchListener(){ float sx,sy; int ox,oy; public boolean onTouch(View v, MotionEvent m){
            if(m.getAction()==MotionEvent.ACTION_DOWN){sx=m.getRawX();sy=m.getRawY();ox=lp.x;oy=lp.y;return true;}
            if(m.getAction()==MotionEvent.ACTION_MOVE){lp.x=ox+(int)(m.getRawX()-sx);lp.y=oy+(int)(m.getRawY()-sy);try{wm.updateViewLayout(box,lp);}catch(Exception ignored){}return true;}
            if(m.getAction()==MotionEvent.ACTION_UP){getSharedPreferences("p",0).edit().putInt("x",lp.x).putInt("y",lp.y).apply();return true;} return false; }});
        box.setVisibility(View.GONE); wm.addView(box,lp);
    }
    private TextView button(String s){ TextView t=new TextView(this); t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(24); t.setGravity(Gravity.CENTER); t.setPadding(26,16,26,16); return t; }

    private void selectFromEvent(AccessibilityEvent e){ AccessibilityNodeInfo n=e.getSource(); if(n==null)return; AccessibilityNodeInfo r=findRangeNear(n); if(r!=null && isAppSlider(r)){ selectedBounds=new Rect(); r.getBoundsInScreen(selectedBounds); selectedLabel=bestLabel(r); } }
    private AccessibilityNodeInfo findRangeNear(AccessibilityNodeInfo n){ if(n.getRangeInfo()!=null)return n; AccessibilityNodeInfo p=n; for(int i=0;i<4 && p!=null;i++){ for(int j=0;j<p.getChildCount();j++){AccessibilityNodeInfo c=p.getChild(j); if(c!=null&&c.getRangeInfo()!=null)return c;} p=p.getParent(); } return null; }

    private void refresh(){
        List<AccessibilityNodeInfo> sliders=getAllAppSliders();
        if(sliders.isEmpty()){ box.setVisibility(View.GONE); selectedBounds=null; return; }
        AccessibilityNodeInfo target=matchSelected(sliders);
        if(target==null){ target=sliders.get(sliders.size()-1); selectedBounds=new Rect(); target.getBoundsInScreen(selectedBounds); selectedLabel=bestLabel(target); }
        label.setText(selectedLabel+" "+percent(target)+"%"); box.setVisibility(View.VISIBLE);
    }
    private List<AccessibilityNodeInfo> getAllAppSliders(){ List<AccessibilityNodeInfo> out=new ArrayList<>(); for(AccessibilityNodeInfo root:getRoots()){collect(root,out);} return out; }
    private List<AccessibilityNodeInfo> getRoots(){ List<AccessibilityNodeInfo> r=new ArrayList<>(); if(getWindows()!=null) for(android.view.accessibility.AccessibilityWindowInfo w:getWindows()) if(w.getRoot()!=null)r.add(w.getRoot()); return r; }
    private void collect(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out){ if(n==null)return; if(n.getRangeInfo()!=null&&isAppSlider(n))out.add(n); for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),out); }
    private boolean isAppSlider(AccessibilityNodeInfo n){ Rect b=new Rect(); n.getBoundsInScreen(b); int sw=getResources().getDisplayMetrics().widthPixels; String s=(safe(n.getText())+" "+safe(n.getContentDescription())+" "+ancestorText(n)).toLowerCase(); boolean system=s.contains("media")||s.contains("ring")||s.contains("notification")||s.contains("system")||s.contains("alarm")||s.contains("call"); return b.centerX()>sw*0.72f && !system; }
    private String ancestorText(AccessibilityNodeInfo n){ StringBuilder s=new StringBuilder(); AccessibilityNodeInfo p=n; for(int i=0;i<3&&p!=null;i++,p=p.getParent()){s.append(' ').append(safe(p.getText())).append(' ').append(safe(p.getContentDescription()));} return s.toString(); }
    private String bestLabel(AccessibilityNodeInfo n){ String s=ancestorText(n).trim(); if(s.toLowerCase().contains("youtube"))return "YouTube"; return "APP"; }
    private String safe(CharSequence c){return c==null?"":c.toString();}
    private AccessibilityNodeInfo matchSelected(List<AccessibilityNodeInfo> list){ if(selectedBounds==null)return null; AccessibilityNodeInfo best=null; int d=Integer.MAX_VALUE; for(AccessibilityNodeInfo n:list){Rect b=new Rect();n.getBoundsInScreen(b);int x=Math.abs(b.centerX()-selectedBounds.centerX())+Math.abs(b.centerY()-selectedBounds.centerY());if(x<d){d=x;best=n;}} return d<250?best:null; }
    private int percent(AccessibilityNodeInfo n){ AccessibilityNodeInfo.RangeInfo r=n.getRangeInfo(); if(r==null||r.getMax()<=r.getMin())return 0; return Math.round((r.getCurrent()-r.getMin())*100f/(r.getMax()-r.getMin())); }
    private void step(int dir){ List<AccessibilityNodeInfo> l=getAllAppSliders(); AccessibilityNodeInfo n=matchSelected(l); if(n==null){refresh();return;} AccessibilityNodeInfo.RangeInfo r=n.getRangeInfo(); if(r==null)return; float step=(r.getMax()-r.getMin())/100f; float next=Math.max(r.getMin(),Math.min(r.getMax(),r.getCurrent()+dir*step)); Bundle b=new Bundle(); b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,next); boolean ok=n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b); if(ok){selectedBounds=new Rect();n.getBoundsInScreen(selectedBounds);label.setText(selectedLabel+" "+Math.round((next-r.getMin())*100f/(r.getMax()-r.getMin()))+"%"); h.postDelayed(this::refresh,100);} }
}
