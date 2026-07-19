package com.garrett.appvolumestepper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        page.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("App Volume Stepper v1.5");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(20, 20, 20));
        page.addView(title, matchWrap(dp(16)));

        TextView instructions = new TextView(this);
        instructions.setText(
                "This version stays hidden unless Samsung's separate app-volume slider is really visible.\n\n" +
                "1. Enable the accessibility service.\n" +
                "2. Play an app such as YouTube.\n" +
                "3. Open Sound Assistant's expanded volume panel.\n" +
                "4. When the separate app slider with a percentage appears on the far right, the − / APP % / + box appears.\n" +
                "5. Each tap requests exactly 1%. Drag APP % to move the box.\n\n" +
                "The controls will not appear for the normal five Media/Ringtone sliders."
        );
        instructions.setTextSize(17);
        instructions.setTextColor(Color.rgb(50, 50, 50));
        instructions.setLineSpacing(dp(2), 1.0f);
        page.addView(instructions, matchWrap(dp(24)));

        Button button = new Button(this);
        button.setText("Enable accessibility service");
        button.setAllCaps(false);
        button.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        page.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView privacy = new TextView(this);
        privacy.setText("Works locally. It has no internet permission and does not save or send screen content.");
        privacy.setTextSize(14);
        privacy.setTextColor(Color.rgb(100, 100, 100));
        LinearLayout.LayoutParams privacyParams = matchWrap(0);
        privacyParams.topMargin = dp(24);
        page.addView(privacy, privacyParams);

        scroll.addView(page);
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        p.bottomMargin = bottomMargin;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
