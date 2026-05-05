package com.ridingmode.app;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MyNotificationListener extends NotificationListenerService {
    private static final String TAG = "NotifListener";
    private static final long DUPLICATE_COOLDOWN_MS = 120000L;
    private static final int MAX_RECENT_NOTIFICATIONS = 60;
    private static final LinkedHashMap<String, Long> recentNotifications = new LinkedHashMap<String, Long>(MAX_RECENT_NOTIFICATIONS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_RECENT_NOTIFICATIONS;
        }
    };

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
        try {
            if (!RidingForegroundService.isRiding || sbn == null || sbn.getNotification() == null) return;
            if (!UserPreferences.areNotificationsEnabled(this) || UserPreferences.isMuted(this)) return;
            if (getPackageName().equals(sbn.getPackageName())) return;
            if (shouldIgnoreNotification(sbn)) return;

            String message = buildReadableMessage(sbn);
            if (message.length() == 0) return;
            if (wasRecentlyRead(sbn.getPackageName() + "|" + message)) return;

            RidingForegroundService.deliverNotificationFromListener(message);
        } catch (Exception e) {
            Log.w(TAG, "Ignoring notification after safe failure", e);
        }
    }

    private boolean shouldIgnoreNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (sbn.isOngoing()) return true;
        String category = notification.category;
        if (Notification.CATEGORY_TRANSPORT.equals(category)) return true;
        if (Notification.CATEGORY_PROGRESS.equals(category)) return true;
        if (Notification.CATEGORY_SERVICE.equals(category)) return true;
        if (notification.extras != null && notification.extras.containsKey("android.mediaSession")) return true;
        return isLikelyMediaPackage(sbn.getPackageName());
    }

    private boolean isLikelyMediaPackage(String packageName) {
        if (packageName == null) return false;
        String p = packageName.toLowerCase(Locale.US);
        return p.contains("spotify")
                || p.contains("youtube.music")
                || p.equals("com.google.android.youtube")
                || p.contains("music")
                || p.contains("soundcloud")
                || p.contains("deezer")
                || p.contains("anghami")
                || p.contains("podcast")
                || p.contains("player");
    }

    private String buildReadableMessage(StatusBarNotification sbn) {
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return "";

        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);

        String titleText = clean(title == null ? "" : title.toString());
        String bodyText = "";
        if (textLines != null && textLines.length > 0) {
            CharSequence latest = textLines[textLines.length - 1];
            if (latest != null) bodyText = clean(latest.toString());
        }
        if (bodyText.length() == 0 && bigText != null) bodyText = clean(bigText.toString());
        if (bodyText.length() == 0 && text != null) bodyText = clean(text.toString());

        if (bodyText.length() == 0) return titleText;
        if (titleText.length() == 0) return bodyText;
        if (bodyText.toLowerCase(Locale.US).contains(titleText.toLowerCase(Locale.US))) return bodyText;
        return titleText + " says " + bodyText;
    }

    private String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean wasRecentlyRead(String fingerprint) {
        long now = System.currentTimeMillis();
        synchronized (recentNotifications) {
            Long previous = recentNotifications.get(fingerprint);
            if (previous != null && now - previous < DUPLICATE_COOLDOWN_MS) return true;
            recentNotifications.put(fingerprint, now);
            return false;
        }
    }

    public static MediaController getActiveController() {
        if (instance == null) return null;
        try {
            MediaSessionManager manager = (MediaSessionManager) instance.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName component = new ComponentName(instance, MyNotificationListener.class);
            List<MediaController> controllers = manager.getActiveSessions(component);
            if (controllers == null || controllers.isEmpty()) return null;
            for (MediaController controller : controllers) {
                if (controller != null && controller.getPlaybackState() != null) return controller;
            }
            return controllers.get(0);
        } catch (SecurityException e) {
            Log.w(TAG, "Media session access missing", e);
        } catch (Exception e) {
            Log.w(TAG, "Media session lookup failed", e);
        }
        return null;
    }
}
