package com.ridingmode.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.List;

public class MyNotificationListener extends NotificationListenerService {
    private static final String TAG = "NotifListener";
    private static MyNotificationListener instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.d(TAG, "Notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!RidingForegroundService.isRiding || sbn == null || sbn.getNotification() == null) return;
        if (getPackageName().equals(sbn.getPackageName())) return;
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;
        CharSequence title = extras.getCharSequence("android.title");
        CharSequence text = extras.getCharSequence("android.text");
        String message = ((title == null ? "" : title.toString()) + " " + (text == null ? "" : text.toString())).trim();
        if (message.length() == 0) return;
        Intent intent = new Intent(this, RidingForegroundService.class);
        intent.setAction(RidingForegroundService.ACTION_READ_NOTIFICATION);
        intent.putExtra("text", message);
        startService(intent);
    }

    public static MediaController getActiveController() {
        if (instance == null) return null;
        try {
            MediaSessionManager manager = (MediaSessionManager) instance.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName component = new ComponentName(instance, MyNotificationListener.class);
            List<MediaController> controllers = manager.getActiveSessions(component);
            if (controllers != null && !controllers.isEmpty()) return controllers.get(0);
        } catch (SecurityException e) {
            Log.w(TAG, "Media session access missing", e);
        }
        return null;
    }
}
