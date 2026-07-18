package com.fireworkstars46.signalmirror;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.telephony.TelephonyManager;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class SignalUtils {
    static final String PREFS = "signal_mirror";
    static final String KEY_X = "crop_center_x";
    static final String KEY_Y = "crop_center_y";
    static final String KEY_W = "crop_width";
    static final String KEY_H = "crop_height";
    static final String KEY_CALIBRATED = "calibrated";
    static final String KEY_INTERVAL = "interval_seconds";
    static final String ICON_FILE = "signal_widget.png";
    static final float DEFAULT_X = 0.842f;
    static final float DEFAULT_Y = 0.020f;
    static final float DEFAULT_W = 0.060f;
    static final float DEFAULT_H = 0.032f;

    interface CaptureCallback {
        void onSuccess(Bitmap bitmap);
        void onError(String message);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void saveIcon(Context context, Bitmap icon) {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), ICON_FILE))) {
            icon.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) { }
    }

    static Bitmap loadIcon(Context context) {
        File file = new File(context.getFilesDir(), ICON_FILE);
        return file.exists() ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
    }

    static void updateAllWidgets(Context context, Bitmap icon) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, SignalWidgetProvider.class));
        updateWidgets(context, manager, ids, icon);
    }

    static void updateWidgets(Context context, AppWidgetManager manager, int[] ids, Bitmap icon) {
        if (ids == null || ids.length == 0) return;
        String label = hasActiveSim(context) ? "Cellular signal" : "Emergency signal • no SIM";
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_signal);
            if (icon != null) views.setImageViewBitmap(R.id.signal_image, icon);
            views.setTextViewText(R.id.signal_label, label);
            views.setOnClickPendingIntent(R.id.widget_root, pending);
            manager.updateAppWidget(id, views);
        }
    }

    private static boolean hasActiveSim(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            int count = Math.max(1, tm.getActiveModemCount());
            for (int slot = 0; slot < count; slot++) {
                int state = tm.getSimState(slot);
                if (state == TelephonyManager.SIM_STATE_READY
                        || state == TelephonyManager.SIM_STATE_PIN_REQUIRED
                        || state == TelephonyManager.SIM_STATE_PUK_REQUIRED
                        || state == TelephonyManager.SIM_STATE_NETWORK_LOCKED) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    static Bitmap extractIcon(Bitmap screenshot, float centerX, float centerY, float cropW, float cropH) {
        int sw = screenshot.getWidth(), sh = screenshot.getHeight();
        int width = Math.max(8, Math.round(sw * clamp(cropW, 0.01f, 0.25f)));
        int height = Math.max(8, Math.round(sh * clamp(cropH, 0.008f, 0.15f)));
        int left = Math.round(sw * clamp(centerX, 0f, 1f) - width / 2f);
        int top = Math.round(sh * clamp(centerY, 0f, 1f) - height / 2f);
        left = Math.max(0, Math.min(sw - width, left));
        top = Math.max(0, Math.min(sh - height, top));
        width = Math.min(width, sw - left);
        height = Math.min(height, sh - top);

        Bitmap crop = Bitmap.createBitmap(screenshot, left, top, width, height);
        int background = averageCorners(crop);
        int br = Color.red(background), bg = Color.green(background), bb = Color.blue(background);
        Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        crop.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int diff = Math.max(Math.abs(Color.red(c) - br),
                    Math.max(Math.abs(Color.green(c) - bg), Math.abs(Color.blue(c) - bb)));
            int alpha = diff <= 18 ? 0 : Math.min(255, (diff - 18) * 5);
            pixels[i] = Color.argb(alpha, 18, 18, 20);
        }
        mask.setPixels(pixels, 0, width, 0, 0, width, height);
        crop.recycle();

        Bitmap output = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        float scale = Math.min(232f / width, 232f / height);
        float drawW = width * scale, drawH = height * scale;
        RectF dst = new RectF((256f - drawW) / 2f, (256f - drawH) / 2f,
                (256f + drawW) / 2f, (256f + drawH) / 2f);
        canvas.drawBitmap(mask, null, dst, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        mask.recycle();
        return output;
    }

    private static int averageCorners(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int sampleW = Math.max(1, w / 8), sampleH = Math.max(1, h / 8);
        long r = 0, g = 0, b = 0, count = 0;
        int[][] areas = {{0,0,sampleW,sampleH},{w-sampleW,0,w,sampleH},
                {0,h-sampleH,sampleW,h},{w-sampleW,h-sampleH,w,h}};
        for (int[] a : areas) for (int y = a[1]; y < a[3]; y++) for (int x = a[0]; x < a[2]; x++) {
            int c = bitmap.getPixel(x, y);
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++;
        }
        return Color.rgb((int)(r/count), (int)(g/count), (int)(b/count));
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private SignalUtils() { }
}
