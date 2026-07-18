package com.fireworkstars46.signalmirror;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.graphics.Bitmap;

public class SignalWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        Bitmap icon = SignalUtils.loadIcon(context);
        SignalUtils.updateWidgets(context, manager, appWidgetIds, icon);
        if (icon != null) icon.recycle();
    }

    @Override public void onEnabled(Context context) {
        super.onEnabled(context);
        MirrorAccessibilityService service = MirrorAccessibilityService.instance;
        if (service != null) service.captureAndUpdate(false, null);
    }
}
