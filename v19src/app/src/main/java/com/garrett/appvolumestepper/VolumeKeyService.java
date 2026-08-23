package com.garrett.appvolumestepper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class VolumeKeyService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        App.get().manager().refreshPlayersNow();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No Samsung volume-panel inspection. This service only receives hardware keys.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }

        YouTubeVolumeManager manager = App.get().manager();
        if (!manager.isYouTubeActive()) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            manager.step(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
        }
        return true;
    }
}
