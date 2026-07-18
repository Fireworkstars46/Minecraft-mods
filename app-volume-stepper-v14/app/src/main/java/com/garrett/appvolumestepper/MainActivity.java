package com.garrett.appvolumestepper;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48,48,48,48);
        root.setGravity(Gravity.TOP);
        TextView title = new TextView(this); title.setText("App Volume Stepper"); title.setTextSize(30);
        TextView body = new TextView(this); body.setText("v1.4\n\nThe floating − / + box appears only when Samsung's separate app-volume slider is visible. Tap an app slider to select it, then use − or + for exactly 1%. Drag the middle label to move the box."); body.setTextSize(18); body.setPadding(0,30,0,30);
        Button button = new Button(this); button.setText("Open accessibility settings");
        button.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(title); root.addView(body); root.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }
}
