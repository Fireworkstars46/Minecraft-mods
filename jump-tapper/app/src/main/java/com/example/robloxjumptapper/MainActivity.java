package com.example.robloxjumptapper;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String PREFS="tap_prefs";
    public static final String ACTION_RELOAD="com.example.robloxjumptapper.RELOAD";
    public static final String DEBUG_FILE="jump_debug.log";
    private static final long DAY=86400000L;
    private static final int REQ_SAVE_LOG=1260;

    private EditText hours,minutes,seconds,millis,totalMs,tapHold,startDelay,targetSize,controlScale,targetOpacity,controlOpacity,hapticStrength;
    private LinearLayout splitRow;
    private RadioButton splitMode,totalMode;
    private CheckBox showTarget,showControls,autoCollapse,hideTargetRunning,haptic,debug;
    private Spinner overlayMode;
    private TextView comboLabel,masterState;
    private Button masterButton;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(TapAccessibilityService.ACTION_KEY_CAPTURED.equals(i.getAction())){
            overlayMode.setSelection(0);
            comboLabel.setText("Active custom combo: "+friendly(i.getStringExtra("keyNames"),i.getStringExtra("keyCodes")));
            Toast.makeText(MainActivity.this,"Overlay button combo saved",Toast.LENGTH_SHORT).show();
        }else if(TapAccessibilityService.ACTION_MASTER_STATE.equals(i.getAction())) updateMaster(i.getBooleanExtra("enabled",false));
    }};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        migrate(p);
        IntentFilter f=new IntentFilter();
        f.addAction(TapAccessibilityService.ACTION_KEY_CAPTURED);
        f.addAction(TapAccessibilityService.ACTION_MASTER_STATE);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);

        ScrollView scroll=new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(24),dp(18),dp(60));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            root.setPadding(dp(18),dp(24),dp(18),dp(80)+insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title=text("Jump Tapper v1.31",28,true);
        root.addView(title);
        root.addView(text("Advanced controls",14,false));
        Button acc=new Button(this);
        acc.setText("Open Accessibility Settings");
        acc.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        root.addView(head("Jump Tapper ON / OFF"));
        masterButton=new Button(this);
        masterButton.setText(p.getBoolean("master_enabled",true)?"ON":"OFF");
        masterButton.setOnClickListener(v->{
            Intent i=new Intent(TapAccessibilityService.ACTION_TOGGLE_MASTER);
            i.setPackage(getPackageName());
            sendBroadcast(i);
        });
        root.addView(masterButton);
        masterState=text("App state: "+(p.getBoolean("master_enabled",true)?"ON":"OFF"),13,false);
        root.addView(masterState);
        TextView offNote=text("The Settings button and floating overlay button show the same current ON/OFF state. OFF stops tapping and removes the target, but the floating ≡ handle, SET button, and saved hide/show keybind still work.",12,false);
        offNote.setPadding(0,0,0,dp(8));
        root.addView(offNote);

        root.addView(head("Tap timing"));
        RadioGroup rg=new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        splitMode=new RadioButton(this);splitMode.setText("H / M / S / ms");
        totalMode=new RadioButton(this);totalMode.setText("Total milliseconds");
        rg.addView(splitMode,new RadioGroup.LayoutParams(0,dp(48),1));
        rg.addView(totalMode,new RadioGroup.LayoutParams(0,dp(48),1));
        root.addView(rg);

        long saved=Math.max(0,Math.min(DAY,p.getLong("interval_ms",30000)));
        long hh=saved/3600000,r=saved%3600000,mm=r/60000;
        r%=60000;long ss=r/1000,mss=r%1000;
        splitRow=new LinearLayout(this);splitRow.setOrientation(LinearLayout.HORIZONTAL);
        hours=num(hh,4);minutes=num(mm,4);seconds=num(ss,4);millis=num(mss,4);
        cell(splitRow,hours,"h");cell(splitRow,minutes,"m");cell(splitRow,seconds,"s");cell(splitRow,millis,"ms");
        root.addView(splitRow);
        totalMs=num(saved,8);
        totalMs.setHint("0 = instant, max 86400000 (1 day)");
        root.addView(totalMs,new LinearLayout.LayoutParams(-1,dp(52)));

        boolean tm=p.getBoolean("millis_mode",false);
        if(tm)totalMode.setChecked(true);else splitMode.setChecked(true);
        showTiming(tm);
        rg.setOnCheckedChangeListener((g,id)->showTiming(totalMode.isChecked()));

        LinearLayout pr0=new LinearLayout(this);pr0.setOrientation(LinearLayout.HORIZONTAL);
        preset(pr0,"0 INSTANT",0);preset(pr0,"1s",1000);preset(pr0,"30s",30000);preset(pr0,"1m",60000);
        root.addView(pr0);
        LinearLayout pr1=new LinearLayout(this);pr1.setOrientation(LinearLayout.HORIZONTAL);
        preset(pr1,"1m30",90000);preset(pr1,"5m",300000);preset(pr1,"10m",600000);preset(pr1,"1h",3600000);
        root.addView(pr1);
        LinearLayout pr2=new LinearLayout(this);pr2.setOrientation(LinearLayout.HORIZONTAL);
        preset(pr2,"24h",DAY);
        root.addView(pr2);

        LinearLayout tune=new LinearLayout(this);tune.setOrientation(LinearLayout.HORIZONTAL);
        tapHold=numDay(p.getInt("tap_duration_ms",30));
        startDelay=numDay(p.getInt("start_delay_ms",0));
        cell(tune,tapHold,"Tap hold ms 0–86400000");
        cell(tune,startDelay,"Start delay ms 0–86400000");
        root.addView(tune);

        root.addView(head("Overlay appearance"));
        LinearLayout sr=new LinearLayout(this);sr.setOrientation(LinearLayout.HORIZONTAL);
        targetSize=num1000(p.getInt("target_size_dp",58));
        controlScale=num1000(p.getInt("control_scale",100));
        cell(sr,targetSize,"Target dp 0–1000");
        cell(sr,controlScale,"Controls % 0–1000");
        root.addView(sr);

        LinearLayout or=new LinearLayout(this);or.setOrientation(LinearLayout.HORIZONTAL);
        targetOpacity=num1000(p.getInt("target_opacity_1000",900));
        controlOpacity=num1000(p.getInt("control_opacity_1000",870));
        cell(or,targetOpacity,"Target opacity 0–1000");
        cell(or,controlOpacity,"Controls opacity 0–1000");
        root.addView(or);

        showTarget=check(root,"Show circular target",p.getBoolean("target_visible",true));
        showControls=check(root,"Show START/STOP controls",p.getBoolean("control_visible",true));
        autoCollapse=check(root,"Auto-collapse control box when START begins",p.getBoolean("auto_collapse_start",false));
        hideTargetRunning=check(root,"Hide target circle while auto-tapping",p.getBoolean("hide_target_running",false));

        root.addView(head("Tap feedback"));
        haptic=check(root,"Vibrate for START / STOP / overlay toggle",p.getBoolean("haptic_enabled",true));
        hapticStrength=num1000(p.getInt("haptic_strength_1000",314));
        root.addView(labeled(hapticStrength,"Haptic strength 0–1000"));

        root.addView(head("Overlay hide/show keybind"));
        overlayMode=new Spinner(this);
        String[]opts={"Custom recorded button/key combo","Disabled"};
        ArrayAdapter<String>ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,opts);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        overlayMode.setAdapter(ad);
        overlayMode.setSelection(p.getInt("overlay_hotkey",4)==3?0:1);
        root.addView(overlayMode,new LinearLayout.LayoutParams(-1,dp(52)));

        Button listen=new Button(this);
        listen.setText("LISTEN FOR OVERLAY BUTTON(S)");
        listen.setOnClickListener(v->{
            SharedPreferences q=getSharedPreferences(PREFS,MODE_PRIVATE);
            q.edit().putInt("overlay_hotkey",3).apply();
            overlayMode.setSelection(0);
            Intent i=new Intent(TapAccessibilityService.ACTION_CAPTURE_KEY);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            comboLabel.setText("Listening… hold button(s), then release all");
        });
        root.addView(listen);

        Button clearCombo=new Button(this);
        clearCombo.setText("CLEAR RECORDED OVERLAY BUTTON(S)");
        clearCombo.setOnClickListener(v->{
            SharedPreferences q=getSharedPreferences(PREFS,MODE_PRIVATE);
            q.edit().remove("custom_hotkey_codes").remove("custom_hotkey_names").putInt("overlay_hotkey",4).apply();
            overlayMode.setSelection(1);
            comboLabel.setText("No custom overlay combo recorded yet");
            Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);
            toast("Overlay keybind cleared");
        });
        root.addView(clearCombo);
        comboLabel=text(comboText(p),13,false);
        root.addView(comboLabel);
        root.addView(text("Saved overlay buttons pass through normally. The full saved combo hides/shows the floating overlay in both ON and OFF master states.",12,false));

        root.addView(head("Overlay controls"));
        root.addView(text("Tap ≡ to collapse/open. Hold ≡ about 0.3 seconds and drag to move. Tap SET to open Quick Settings directly over the game. You can edit interval, hold, delay, target/control size, opacity, haptic strength, auto-collapse, hide-target-while-running, and haptics without leaving Roblox.",13,false));

        LinearLayout rr=new LinearLayout(this);rr.setOrientation(LinearLayout.HORIZONTAL);
        Button rp=new Button(this);rp.setText("Reset positions");rp.setOnClickListener(v->resetPositions());
        rr.addView(rp,new LinearLayout.LayoutParams(0,dp(48),1));
        Button rd=new Button(this);rd.setText("Reset settings");rd.setOnClickListener(v->resetDefaults());
        rr.addView(rd,new LinearLayout.LayoutParams(0,dp(48),1));
        root.addView(rr);

        root.addView(head("Debugging"));
        debug=check(root,"Enable lightweight debug logging",p.getBoolean("debug_enabled",true));
        Button dl=new Button(this);dl.setText("DOWNLOAD LOG FILE");dl.setOnClickListener(v->downloadLog());
        root.addView(dl,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout dr=new LinearLayout(this);dr.setOrientation(LinearLayout.HORIZONTAL);
        Button sh=new Button(this);sh.setText("Share Log");sh.setOnClickListener(v->shareLog());
        dr.addView(sh,new LinearLayout.LayoutParams(0,dp(48),1));
        Button cl=new Button(this);cl.setText("Clear Log");cl.setOnClickListener(v->clearLog());
        dr.addView(cl,new LinearLayout.LayoutParams(0,dp(48),1));
        root.addView(dr);

        Button save=new Button(this);save.setText("Save Settings");save.setOnClickListener(v->save());
        root.addView(save);

        setContentView(scroll);
        scroll.requestApplyInsets();
        Intent reload=new Intent(ACTION_RELOAD);reload.setPackage(getPackageName());sendBroadcast(reload);
        requestMaster();
    }

    private void migrate(SharedPreferences p){
        SharedPreferences.Editor e=p.edit();
        boolean ch=false;
        if(!p.getBoolean("master_schema_v1",false)){
            e.putBoolean("master_enabled",true).putBoolean("master_schema_v1",true);
            int old=p.getInt("overlay_hotkey",0);
            if(old==0||old==1||old==2)e.putInt("overlay_hotkey",4);
            ch=true;
        }
        if(!p.contains("custom_hotkey_codes")&&p.getInt("custom_hotkey_keycode",0)!=0){
            e.putString("custom_hotkey_codes",String.valueOf(p.getInt("custom_hotkey_keycode",0)))
             .putString("custom_hotkey_names",p.getString("custom_hotkey_name",""));
            ch=true;
        }
        if(!p.getBoolean("numeric_schema_1000",false)){
            e.putInt("target_opacity_1000",Math.max(0,Math.min(1000,p.getInt("target_opacity",90)*10)));
            e.putInt("control_opacity_1000",Math.max(0,Math.min(1000,p.getInt("control_opacity",87)*10)));
            e.putInt("haptic_strength_1000",Math.max(0,Math.min(1000,Math.round(p.getInt("haptic_strength",80)*1000f/255f))));
            e.putBoolean("numeric_schema_1000",true);
            ch=true;
        }
        if(!p.getBoolean("volume_combo_cleanup_v1",false)){
            String codes=p.getString("custom_hotkey_codes","");
            if(csvContains(codes,24)||csvContains(codes,25)){
                e.remove("custom_hotkey_codes").remove("custom_hotkey_names").putInt("overlay_hotkey",4);
            }
            e.putBoolean("volume_combo_cleanup_v1",true);
            ch=true;
        }
        e.putInt("startstop_hotkey_mode",0);
        if(ch)e.apply();
    }

    private boolean csvContains(String csv,int code){
        if(csv==null||csv.trim().isEmpty())return false;
        for(String x:csv.split(","))try{if(Integer.parseInt(x.trim())==code)return true;}catch(Exception ignored){}
        return false;
    }

    private void requestMaster(){
        Intent i=new Intent(TapAccessibilityService.ACTION_REQUEST_MASTER_STATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void updateMaster(boolean on){
        masterButton.setText(on?"ON":"OFF");
        masterState.setText("App state: "+(on?"ON":"OFF"));
    }

    @Override protected void onResume(){
        super.onResume();
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        if(overlayMode!=null)overlayMode.setSelection(p.getInt("overlay_hotkey",4)==3?0:1);
        if(comboLabel!=null)comboLabel.setText(comboText(p));
        requestMaster();
    }

    private void save(){
        try{
            long interval;
            if(totalMode.isChecked())interval=val(totalMs);
            else interval=Math.addExact(
                    Math.addExact(Math.multiplyExact(val(hours),3600000L),Math.multiplyExact(val(minutes),60000L)),
                    Math.addExact(Math.multiplyExact(val(seconds),1000L),val(millis)));
            int th=(int)val(tapHold),sd=(int)val(startDelay),ts=(int)val(targetSize),cs=(int)val(controlScale),to=(int)val(targetOpacity),co=(int)val(controlOpacity),hs=(int)val(hapticStrength);
            if(interval<0||interval>DAY||th<0||th>DAY||sd<0||sd>DAY){toast("Timing values must be 0 through 1 day.");return;}
            if(!ok1000(ts)||!ok1000(cs)||!ok1000(to)||!ok1000(co)||!ok1000(hs)){toast("Size, opacity, and haptic values must be 0–1000.");return;}
            SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
            int hm=overlayMode.getSelectedItemPosition()==0?3:4;
            if(hm==3&&p.getString("custom_hotkey_codes","").trim().isEmpty()){toast("Record an overlay button first.");return;}
            p.edit()
                    .putLong("interval_ms",interval)
                    .putBoolean("millis_mode",totalMode.isChecked())
                    .putInt("tap_duration_ms",th)
                    .putInt("start_delay_ms",sd)
                    .putInt("target_size_dp",ts)
                    .putInt("control_scale",cs)
                    .putInt("target_opacity_1000",to)
                    .putInt("control_opacity_1000",co)
                    .putInt("haptic_strength_1000",hs)
                    .putBoolean("target_visible",showTarget.isChecked())
                    .putBoolean("control_visible",showControls.isChecked())
                    .putBoolean("auto_collapse_start",autoCollapse.isChecked())
                    .putBoolean("hide_target_running",hideTargetRunning.isChecked())
                    .putBoolean("haptic_enabled",haptic.isChecked())
                    .putBoolean("debug_enabled",debug.isChecked())
                    .putInt("overlay_hotkey",hm)
                    .apply();
            Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);
            toast(interval==0?"Saved — interval is INSTANT / 0":"Saved");
        }catch(Exception e){toast("Check the numbers.");}
    }

    private void resetPositions(){
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        p.edit().remove("target_x").remove("target_y").remove("control_x").remove("control_y").apply();
        Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);
        toast("Overlay positions reset");
    }

    private void resetDefaults(){
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        boolean on=p.getBoolean("master_enabled",true);
        p.edit().clear()
                .putBoolean("master_enabled",on)
                .putBoolean("master_schema_v1",true)
                .putBoolean("volume_combo_cleanup_v1",true)
                .putInt("overlay_hotkey",4)
                .putBoolean("numeric_schema_1000",true)
                .putInt("target_opacity_1000",900)
                .putInt("control_opacity_1000",870)
                .putInt("haptic_strength_1000",314)
                .apply();
        toast("Settings reset. Reopen Jump Tapper to refresh.");
        Intent i=new Intent(ACTION_RELOAD);i.setPackage(getPackageName());sendBroadcast(i);
    }

    private TextView head(String s){TextView t=text(s,18,true);t.setPadding(0,dp(18),0,dp(6));return t;}
    private TextView text(String s,int z,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private CheckBox check(LinearLayout root,String s,boolean v){CheckBox c=new CheckBox(this);c.setText(s);c.setChecked(v);root.addView(c);return c;}
    private EditText num(long v,int len){EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setGravity(Gravity.CENTER);e.setText(String.valueOf(v));e.setSelectAllOnFocus(true);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(len)});return e;}
    private EditText num1000(int v){return num(Math.max(0,Math.min(1000,v)),4);}
    private EditText numDay(int v){return num(Math.max(0,Math.min(86400000,v)),8);}
    private void cell(LinearLayout row,EditText e,String label){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));TextView t=text(label,11,false);t.setGravity(Gravity.CENTER);c.addView(t);row.addView(c,new LinearLayout.LayoutParams(0,dp(72),1));}
    private View labeled(EditText e,String label){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));TextView t=text(label,11,false);t.setGravity(Gravity.CENTER);c.addView(t);return c;}
    private long val(EditText e){String s=e.getText().toString().trim();return s.isEmpty()?0:Long.parseLong(s);}
    private boolean ok1000(int v){return v>=0&&v<=1000;}
    private void showTiming(boolean total){splitRow.setVisibility(total?View.GONE:View.VISIBLE);totalMs.setVisibility(total?View.VISIBLE:View.GONE);}
    private void preset(LinearLayout row,String s,long v){Button b=new Button(this);b.setText(s);b.setOnClickListener(x->setInterval(v));row.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}
    private void setInterval(long v){v=Math.max(0,Math.min(v,DAY));totalMs.setText(String.valueOf(v));long h=v/3600000,r=v%3600000,m=r/60000;r%=60000;long s=r/1000,ms=r%1000;hours.setText(String.valueOf(h));minutes.setText(String.valueOf(m));seconds.setText(String.valueOf(s));millis.setText(String.valueOf(ms));}

    private String comboText(SharedPreferences p){
        String c=p.getString("custom_hotkey_codes","");
        return c==null||c.trim().isEmpty()?"No custom overlay combo recorded yet":"Active custom combo: "+friendly(p.getString("custom_hotkey_names",""),c);
    }

    private String friendly(String names,String codes){
        if(names==null)names="";if(codes==null)codes="";
        String[]n=names.split(","),c=codes.split(",");
        StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.max(n.length,c.length);i++){
            String x=i<n.length?n[i]:"";
            String k=i<c.length?c[i]:"";
            if(x.startsWith("KEYCODE_"))x=x.substring(8);
            x=x.replace('_',' ');
            if(x.isEmpty())x="KEY "+k;
            if(b.length()>0)b.append(" + ");
            b.append(x);
            if(!k.isEmpty())b.append(" (").append(k).append(")");
        }
        return b.toString();
    }

    private String readLog(){
        try{
            File f=new File(getFilesDir(),DEBUG_FILE);
            if(!f.exists())return "No debug log yet.";
            if(Build.VERSION.SDK_INT>=26)return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
            java.io.FileInputStream in=new java.io.FileInputStream(f);
            byte[]b=new byte[(int)f.length()];
            int n=in.read(b);in.close();
            return new String(b,0,Math.max(0,n),StandardCharsets.UTF_8);
        }catch(Exception e){return "Could not read debug log: "+e.getMessage();}
    }

    private void downloadLog(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        String stamp=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE,"JumpTapper_Debug_"+stamp+".txt");
        startActivityForResult(i,REQ_SAVE_LOG);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQ_SAVE_LOG||resultCode!=RESULT_OK||data==null)return;
        Uri uri=data.getData();if(uri==null)return;
        try{
            OutputStream out=getContentResolver().openOutputStream(uri,"w");
            if(out==null){toast("Could not create log file.");return;}
            out.write(readLog().getBytes(StandardCharsets.UTF_8));
            out.flush();out.close();toast("Log file saved");
        }catch(Exception e){toast("Could not save log file.");}
    }

    private void shareLog(){
        Intent s=new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT,"Jump Tapper v1.31 Debug Log");
        s.putExtra(Intent.EXTRA_TEXT,readLog());
        startActivity(Intent.createChooser(s,"Share debug log"));
    }
    private void clearLog(){File f=new File(getFilesDir(),DEBUG_FILE);if(f.exists())f.delete();toast("Debug log cleared");}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}
