package com.ridingmode.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.ContactsContract;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;
import java.util.Locale;

public class RidingForegroundService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.ridingmode.app.action.START";
    public static final String ACTION_STOP = "com.ridingmode.app.action.STOP";
    public static final String ACTION_READ_NOTIFICATION = "com.ridingmode.app.action.READ_NOTIF";

    private static final int NOTIF_ID = 12345;
    private static final String CHANNEL_ID = "riding_mode_channel";

    public static boolean isRiding = false;

    private AudioManager audioManager;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Handler mainHandler;
    private Runnable restartRunnable;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdownRidingMode(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_READ_NOTIFICATION.equals(action)) {
            String text = intent == null ? null : intent.getStringExtra("text");
            if (text != null && isRiding) speak(text);
            return START_STICKY;
        }
        startForegroundMode();
        return START_STICKY;
    }

    private void startForegroundMode() {
        if (isRiding) return;
        createNotificationChannel();
        Notification notification = buildNotification();
        if (!beginForegroundSafely(notification)) {
            isRiding = false;
            stopSelf();
            return;
        }
        isRiding = true;
        enableBluetoothSco();
        registerPhoneListener();
        initSpeechRecognizer();
        startListening();
        speak("Riding mode active");
    }

    private boolean beginForegroundSafely(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            return true;
        } catch (Exception firstFailure) {
            try {
                startForeground(NOTIF_ID, notification);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private void shutdownRidingMode(boolean announce) {
        if (!isRiding && speechRecognizer == null) return;
        isRiding = false;
        stopListening();
        unregisterPhoneListener();
        disableBluetoothSco();
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
            speechIntent = null;
        }
        if (announce) speak("Riding mode stopped");
    }

    private void registerPhoneListener() {
        if (telephonyManager == null || phoneStateListener != null || !hasPermission(Manifest.permission.READ_PHONE_STATE)) return;
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                if (!isRiding || state != TelephonyManager.CALL_STATE_RINGING) return;
                final String name = resolveContactName(phoneNumber);
                mainHandler.postDelayed(() -> speak("Incoming call from " + name), 1200);
            }
        };
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException ignored) { }
    }

    private void unregisterPhoneListener() {
        if (telephonyManager == null || phoneStateListener == null) return;
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        } catch (Exception ignored) { }
        phoneStateListener = null;
    }

    private void initSpeechRecognizer() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            speak("Microphone permission missing");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available");
            return;
        }
        if (speechRecognizer != null) speechRecognizer.destroy();
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        speechRecognizer.setRecognitionListener(new RiderRecognitionListener(this));
    }

    public void restartListening() {
        if (!isRiding || mainHandler == null) return;
        if (restartRunnable != null) mainHandler.removeCallbacks(restartRunnable);
        restartRunnable = this::startListening;
        mainHandler.postDelayed(restartRunnable, 500);
    }

    private void startListening() {
        if (!isRiding || speechRecognizer == null || speechIntent == null) return;
        try {
            speechRecognizer.cancel();
            speechRecognizer.startListening(speechIntent);
        } catch (Exception ignored) { }
    }

    private void stopListening() {
        if (mainHandler != null && restartRunnable != null) mainHandler.removeCallbacks(restartRunnable);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
            } catch (Exception ignored) { }
        }
    }

    public void handleCommand(String rawCommand) {
        if (rawCommand == null) return;
        String command = rawCommand.toLowerCase(Locale.US).trim();
        if (command.startsWith("call ")) {
            callContact(command.substring(5).trim());
        } else if (command.contains("answer") || command.contains("accept")) {
            answerCall();
        } else if (command.contains("hang up") || command.contains("reject") || command.contains("end call")) {
            endCall();
        } else if ((command.contains("play") && command.contains("music")) || command.equals("play")) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing music");
        } else if (command.contains("pause") || command.contains("stop music")) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_PAUSE, "Music paused");
        } else if (command.contains("next")) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_NEXT, "Next track");
        } else if (command.contains("previous") || command.contains("back")) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track");
        } else if (command.contains("ride off") || command.contains("motor off")) {
            speak("Goodbye");
            shutdownRidingMode(false);
            stopSelf();
        }
    }

    private void callContact(String nameOrNumber) {
        if (nameOrNumber.length() == 0) return;
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            speak("Call permission missing");
            return;
        }
        String number = nameOrNumber.matches("[+0-9 ()-]+") ? nameOrNumber : lookupPhoneNumber(nameOrNumber);
        if (number == null || number.trim().length() == 0) {
            speak("Contact not found");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            speak("Calling");
        } catch (Exception e) {
            speak("Call failed");
        }
    }

    private String lookupPhoneNumber(String nameQuery) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + nameQuery + "%"},
                    null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private String resolveContactName(String number) {
        if (number == null || number.length() == 0) return "Unknown caller";
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return "Caller";
        Cursor cursor = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            cursor = getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return number;
    }

    private void answerCall() {
        if (Build.VERSION.SDK_INT < 26) {
            speak("Answer is not supported");
            return;
        }
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            speak("Answer call permission missing");
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) tm.acceptRingingCall();
            speak("Answering");
        } catch (Exception e) {
            speak("Answer failed");
        }
    }

    private void endCall() {
        if (Build.VERSION.SDK_INT < 28) {
            speak("Hang up is not supported");
            return;
        }
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            speak("Call control permission missing");
            return;
        }
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) tm.endCall();
            speak("Ending call");
        } catch (Exception e) {
            speak("End call failed");
        }
    }

    private void controlMusic(int keyCode, String confirmation) {
        MediaController controller = MyNotificationListener.getActiveController();
        if (controller != null && controller.getTransportControls() != null) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) controller.getTransportControls().play();
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) controller.getTransportControls().pause();
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) controller.getTransportControls().skipToNext();
            else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) controller.getTransportControls().skipToPrevious();
            speak(confirmation);
            return;
        }
        dispatchMediaKey(keyCode);
        speak(confirmation);
    }

    private void dispatchMediaKey(int keyCode) {
        if (audioManager == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void enableBluetoothSco() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return;
        try {
            if (audioManager.isBluetoothScoAvailableOffCall() && !audioManager.isBluetoothScoOn()) {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            }
        } catch (Exception ignored) { }
    }

    private void disableBluetoothSco() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return;
        try {
            if (audioManager.isBluetoothScoOn()) {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        } catch (Exception ignored) { }
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null) return;
        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs)
                            .build();
                    audioManager.requestAudioFocus(request);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "RidingModeUtterance");
        } catch (Exception ignored) { }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Riding Mode", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps voice riding mode active");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, RidingForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                7002,
                stopIntent,
                pendingIntentFlags());

        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this,
                7003,
                contentIntent,
                pendingIntentFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Riding Mode")
                .setContentText("Listening: play, pause, next, call number, ride off")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }


    private int pendingIntentFlags() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            tts.setLanguage(Locale.US);
            ttsReady = true;
            if (isRiding) speak("Voice ready");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shutdownRidingMode(false);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }
}
