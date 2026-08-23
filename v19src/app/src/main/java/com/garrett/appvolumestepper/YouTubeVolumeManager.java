package com.garrett.appvolumestepper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.IBinder;

import org.joor.Reflect;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.ShizukuProvider;

public final class YouTubeVolumeManager {
    public enum Status {
        SHIZUKU_MISSING,
        SHIZUKU_STOPPED,
        PERMISSION_NEEDED,
        READY,
        ERROR
    }

    public interface Listener {
        void onChanged();
    }

    private static final String YOUTUBE = "com.google.android.youtube";
    private static final int REQUEST_CODE = 4601;

    private final Context context;
    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Status status = Status.SHIZUKU_STOPPED;
    private volatile String lastError = "";
    private volatile boolean initialized;
    private volatile boolean youtubeActive;
    private volatile int youtubeUid = -1;
    private volatile int percent;

    private final AudioManager.AudioPlaybackCallback callback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            inspectAndApply(configs);
        }
    };

    public YouTubeVolumeManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = this.context.getSystemService(AudioManager.class);
        this.prefs = this.context.getSharedPreferences("youtube_volume", Context.MODE_PRIVATE);
        this.percent = clamp(prefs.getInt("percent", 100), 0, 100);

        try {
            ApplicationInfo info = this.context.getPackageManager().getApplicationInfo(YOUTUBE, 0);
            youtubeUid = info.uid;
        } catch (Throwable ignored) {
            youtubeUid = -1;
        }

        Shizuku.addBinderReceivedListenerSticky(this::refreshShizukuState);
        Shizuku.addBinderDeadListener(() -> {
            initialized = false;
            youtubeActive = false;
            status = Status.SHIZUKU_STOPPED;
            notifyListeners();
        });
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == REQUEST_CODE) {
                refreshShizukuState();
            }
        });

        ShizukuProvider.requestBinderForNonProviderProcess(this.context);
        refreshShizukuState();
    }

    public Status getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isYouTubeActive() {
        return status == Status.READY && youtubeActive;
    }

    public int getPercent() {
        return percent;
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void requestPermission() {
        try {
            if (!isShizukuInstalled()) {
                status = Status.SHIZUKU_MISSING;
                notifyListeners();
                return;
            }
            if (!Shizuku.pingBinder()) {
                status = Status.SHIZUKU_STOPPED;
                notifyListeners();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                initializePrivilegedAudio();
            } else {
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    public synchronized void setPercent(int value) {
        percent = clamp(value, 0, 100);
        prefs.edit().putInt("percent", percent).apply();
        if (status == Status.READY) {
            refreshPlayersNow();
        }
        notifyListeners();
    }

    public synchronized void step(int direction) {
        setPercent(percent + (direction >= 0 ? 1 : -1));
    }

    public void refreshPlayersNow() {
        if (status != Status.READY) return;
        try {
            inspectAndApply(audioManager.getActivePlaybackConfigurations());
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void refreshShizukuState() {
        try {
            if (!isShizukuInstalled()) {
                initialized = false;
                status = Status.SHIZUKU_MISSING;
                notifyListeners();
                return;
            }
            if (!Shizuku.pingBinder()) {
                initialized = false;
                status = Status.SHIZUKU_STOPPED;
                notifyListeners();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                initialized = false;
                status = Status.PERMISSION_NEEDED;
                notifyListeners();
                return;
            }
            initializePrivilegedAudio();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private boolean isShizukuInstalled() {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private synchronized void initializePrivilegedAudio() {
        if (initialized) {
            status = Status.READY;
            refreshPlayersNow();
            notifyListeners();
            return;
        }

        try {
            Object service = Reflect.onClass(AudioManager.class).call("getService").get();
            Reflect serviceReflect = Reflect.on(service);
            IBinder remote = serviceReflect.get("mRemote");
            if (!(remote instanceof ShizukuBinderWrapper)) {
                serviceReflect.set("mRemote", new ShizukuBinderWrapper(remote));
            }

            audioManager.registerAudioPlaybackCallback(callback, null);
            initialized = true;
            status = Status.READY;
            lastError = "";
            inspectAndApply(audioManager.getActivePlaybackConfigurations());
            notifyListeners();
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void inspectAndApply(List<AudioPlaybackConfiguration> configs) {
        boolean active = false;
        if (configs != null && youtubeUid >= 0) {
            float multiplier = percent / 100f;
            for (AudioPlaybackConfiguration config : configs) {
                try {
                    Reflect r = Reflect.on(config);
                    int uid;
                    try {
                        uid = r.call("getClientUid").get();
                    } catch (Throwable ignored) {
                        uid = r.get("mClientUid");
                    }
                    if (uid != youtubeUid) continue;

                    int state;
                    try {
                        state = r.call("getPlayerState").get();
                    } catch (Throwable ignored) {
                        state = r.get("mPlayerState");
                    }
                    if (state == 2) active = true;

                    Object player = r.call("getIPlayer").get();
                    if (player != null) {
                        try {
                            Reflect.on(player).call("setVolume", multiplier);
                        } catch (Throwable ignored) {
                            // A player can disappear between the callback and this call.
                        }
                    }
                } catch (Throwable ignored) {
                    // Ignore one malformed/dead player and keep processing the others.
                }
            }
        }
        youtubeActive = active;
        notifyListeners();
    }

    private void fail(Throwable t) {
        initialized = false;
        youtubeActive = false;
        status = Status.ERROR;
        lastError = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            try {
                listener.onChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
