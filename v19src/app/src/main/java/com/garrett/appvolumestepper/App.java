package com.garrett.appvolumestepper;

import android.app.Application;
import android.content.Context;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class App extends Application {
    private static App instance;
    private YouTubeVolumeManager manager;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        HiddenApiBypass.addHiddenApiExemptions("");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        manager = new YouTubeVolumeManager(this);
    }

    public static App get() {
        return instance;
    }

    public YouTubeVolumeManager manager() {
        return manager;
    }
}
