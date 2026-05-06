package com.ridingmode.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.speech.tts.UtteranceProgressListener;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class RidingForegroundService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_START = "com.ridingmode.app.action.START";
    public static final String ACTION_STOP = "com.ridingmode.app.action.STOP";

    private static final int NOTIF_ID = 12345;
    private static final String CHANNEL_ID = "riding_mode_channel";
    private static final int CONTACT_PAGE_SIZE = 2;
    private static final long RECOGNITION_SYSTEM_SILENCE_MS = 900L;
    private static final long NOISE_RESTART_DELAY_MS = 1700L;

    public static boolean isRiding = false;
    private static RidingForegroundService activeInstance;

    private AudioManager audioManager;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Handler mainHandler;
    private Runnable restartRunnable;
    private Runnable voiceWatchdogRunnable;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private boolean mediaDuckingActive;
    private int originalMusicVolume = -1;
    private int originalSystemVolume = -1;
    private int originalNotificationVolume = -1;
    private boolean systemPromptSilenced;
    private boolean mediaWasPlayingBeforeListen;
    private boolean stopAfterCurrentSpeech;
    private boolean speechOutputActive;
    private boolean foregroundPromoted;
    private boolean serviceListening;
    private int recognitionErrorCount;
    private long utteranceCounter;
    private int currentCallState = TelephonyManager.CALL_STATE_IDLE;
    private String lastHandledCommand = "";
    private long lastHandledCommandAt;
    private long suppressListeningUntilMillis;

    private List<ContactMatch> pendingContactMatches;
    private int pendingContactPage;
    private PendingCallRequest pendingCallRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRidingModeWithSpeech("Ride mode off");
            return START_NOT_STICKY;
        }
        startForegroundMode();
        return START_STICKY;
    }


    public static void deliverNotificationFromListener(String text) {
        RidingForegroundService service = activeInstance;
        if (service == null || text == null || text.trim().length() == 0) return;
        if (!isRiding || UserPreferences.isMuted(service) || !UserPreferences.areNotificationsEnabled(service)) return;
        Handler handler = service.mainHandler;
        if (handler != null) {
            handler.post(() -> service.speakNotification(text));
        } else {
            service.speakNotification(text);
        }
    }

    private void startForegroundMode() {
        createNotificationChannel();
        Notification notification = buildNotification();
        if (!foregroundPromoted && !promoteToForegroundSafely(notification)) {
            isRiding = false;
            stopSelf();
            return;
        }
        if (isRiding) {
            startVoiceWatchdog();
            scheduleListeningRestart(500L);
            return;
        }
        isRiding = true;
        recognitionErrorCount = 0;
        // Microphone ownership is centralized here: the foreground service is
        // the only component that creates and owns SpeechRecognizer.
        registerPhoneListener();
        initSpeechRecognizer();
        startVoiceWatchdog();
        suppressListeningUntilMillis = System.currentTimeMillis() + 1800L;
        scheduleListeningRestart(1800L);
    }

    private boolean promoteToForegroundSafely(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            foregroundPromoted = true;
            return true;
        } catch (SecurityException e) {
            try {
                startForeground(NOTIF_ID, notification);
                foregroundPromoted = true;
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } catch (RuntimeException e) {
            try {
                startForeground(NOTIF_ID, notification);
                foregroundPromoted = true;
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void shutdownRidingMode(boolean destroySpeech) {
        isRiding = false;
        foregroundPromoted = false;
        serviceListening = false;
        speechOutputActive = false;
        lastHandledCommand = "";
        lastHandledCommandAt = 0L;
        suppressListeningUntilMillis = 0L;
        clearPendingContactChoice();
        clearPendingCallRequest();
        stopVoiceWatchdog();
        stopListening();
        unregisterPhoneListener();
        restoreManualAudioState();
        if (destroySpeech && speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
            speechIntent = null;
        }
    }

    private void registerPhoneListener() {
        if (telephonyManager == null || phoneStateListener != null || !hasPermission(Manifest.permission.READ_PHONE_STATE)) return;
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                currentCallState = state;
                if (!isRiding) return;
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    final String name = resolveContactName(phoneNumber);
                    if (mainHandler != null) {
                        mainHandler.postDelayed(() -> {
                            if (currentCallState == TelephonyManager.CALL_STATE_RINGING && !UserPreferences.isMuted(RidingForegroundService.this)) {
                                speakSystem("Incoming call from " + name);
                            }
                        }, 900);
                    }
                } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    clearPendingContactChoice();
                    clearPendingCallRequest();
                }
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
        currentCallState = TelephonyManager.CALL_STATE_IDLE;
    }

    private void initSpeechRecognizer() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            speakSystem("Microphone permission missing");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakSystem("Speech recognition is not available");
            return;
        }
        try {
            if (speechRecognizer != null) {
                try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
            speechIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
            speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
            speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            // Best-effort vendor-specific flags. Some recognizers ignore these,
            // but they reduce audible prompt/beep behavior on several devices.
            speechIntent.putExtra("android.speech.extra.SUPPRESS_BEEP", true);
            speechIntent.putExtra("android.speech.extra.NO_BEEP", true);
            speechIntent.putExtra("android.speech.extra.DICTATION_MODE", true);
            speechRecognizer.setRecognitionListener(new RiderRecognitionListener(this));
            serviceListening = false;
            recognitionErrorCount = 0;
        } catch (Exception e) {
            speechRecognizer = null;
            speechIntent = null;
            serviceListening = false;
            speakSystem("Voice recognizer failed to start");
        }
    }


    private void startVoiceWatchdog() {
        if (mainHandler == null) return;
        if (voiceWatchdogRunnable != null) mainHandler.removeCallbacks(voiceWatchdogRunnable);
        voiceWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRiding || mainHandler == null) return;
                if (!speechOutputActive && !stopAfterCurrentSpeech && !serviceListening) {
                    startListening();
                }
                mainHandler.postDelayed(this, 3500L);
            }
        };
        mainHandler.postDelayed(voiceWatchdogRunnable, 3500L);
    }

    private void stopVoiceWatchdog() {
        if (mainHandler != null && voiceWatchdogRunnable != null) {
            mainHandler.removeCallbacks(voiceWatchdogRunnable);
        }
        voiceWatchdogRunnable = null;
    }

    public void restartListening() {
        scheduleListeningRestart(700L);
    }

    private void scheduleListeningRestart(long delayMs) {
        if (!isRiding || mainHandler == null || stopAfterCurrentSpeech || speechOutputActive) return;
        if (restartRunnable != null) mainHandler.removeCallbacks(restartRunnable);
        restartRunnable = this::startListening;
        mainHandler.postDelayed(restartRunnable, Math.max(250L, delayMs));
    }

    private void startListening() {
        // Single microphone owner: never gate listening on Activity state.
        if (!isRiding || stopAfterCurrentSpeech || speechOutputActive) return;
        long nowForListen = System.currentTimeMillis();
        if (nowForListen < suppressListeningUntilMillis) {
            scheduleListeningRestart(Math.max(250L, suppressListeningUntilMillis - nowForListen));
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return;
        if (speechRecognizer == null || speechIntent == null) initSpeechRecognizer();
        if (speechRecognizer == null || speechIntent == null || serviceListening) return;
        try {
            serviceListening = true;
            mediaWasPlayingBeforeListen = isMusicPlaying();
            silenceRecognitionSystemPrompt();
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            serviceListening = false;
            recognitionErrorCount++;
            if (recognitionErrorCount >= 3) {
                initSpeechRecognizer();
                recognitionErrorCount = 0;
            }
            scheduleListeningRestart(1200L);
        }
    }

    private void stopListening() {
        if (mainHandler != null && restartRunnable != null) mainHandler.removeCallbacks(restartRunnable);
        serviceListening = false;
        restoreRecognitionSystemPrompt();
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) { }
        }
    }

    public void onRecognizerReady() {
        serviceListening = true;
        recognitionErrorCount = 0;
        scheduleRecognitionSystemPromptRestore();
    }

    public void onRecognizerSpeechStarted() {
        serviceListening = true;
        scheduleRecognitionSystemPromptRestore();
        restoreMediaPlaybackIfRecognitionPausedIt();
    }

    public void onRecognizerSpeechEnded() {
        serviceListening = false;
        restoreRecognitionSystemPrompt();
        restoreMediaPlaybackIfRecognitionPausedIt();
    }

    public void onRecognizerResults(ArrayList<String> matches) {
        serviceListening = false;
        restoreRecognitionSystemPrompt();
        restoreMediaPlaybackIfRecognitionPausedIt();
        recognitionErrorCount = 0;
        if (matches != null && !matches.isEmpty()) {
            for (String match : matches) {
                if (match == null) continue;
                if (looksLikeKnownCommand(match)) {
                    handleCommand(match);
                    restartListening();
                    return;
                }
            }
            // In a motorcycle/noisy environment, never execute unknown recognizer text.
            // Only known commands are handled; random traffic/noise must be ignored.
        }
        restartListening();
    }

    public void onRecognizerError(int error) {
        serviceListening = false;
        restoreRecognitionSystemPrompt();
        restoreMediaPlaybackIfRecognitionPausedIt();
        if (!isRiding || stopAfterCurrentSpeech || speechOutputActive) return;
        recognitionErrorCount++;
        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || recognitionErrorCount >= 5) {
            initSpeechRecognizer();
            recognitionErrorCount = 0;
        }
        long delay = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ? NOISE_RESTART_DELAY_MS : 1400L;
        scheduleListeningRestart(delay);
    }

    private boolean looksLikeKnownCommand(String rawCommand) {
        String command = normalizeCommand(rawCommand);
        if (command.length() == 0) return false;
        if (command.startsWith("call ")) return true;
        return isRideOffCommand(command)
                || isCancelCommand(command)
                || isEndCallCommand(command)
                || isMusicPlayCommand(command)
                || isMusicPauseCommand(command)
                || isMusicNextCommand(command)
                || isMusicPreviousCommand(command)
                || isVolumeMaxCommand(command)
                || isVolumeUpCommand(command)
                || isVolumeDownCommand(command)
                || isMuteOnCommand(command)
                || isMuteOffCommand(command)
                || isNotificationOffCommand(command)
                || isNotificationOnCommand(command)
                || parseSelection(command) != null
                || parseSimSelection(command) != null
                || command.equals("answer")
                || command.equals("accept");
    }

    private void silenceRecognitionSystemPrompt() {
        if (audioManager == null || systemPromptSilenced) return;
        try {
            originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM);
            originalNotificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            systemPromptSilenced = true;
            scheduleRecognitionSystemPromptRestore();
        } catch (Exception ignored) {
            systemPromptSilenced = false;
        }
    }

    private void scheduleRecognitionSystemPromptRestore() {
        if (mainHandler == null) return;
        mainHandler.postDelayed(this::restoreRecognitionSystemPrompt, RECOGNITION_SYSTEM_SILENCE_MS);
    }

    private void restoreRecognitionSystemPrompt() {
        if (audioManager == null || !systemPromptSilenced) return;
        try {
            if (originalSystemVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
            if (originalNotificationVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotificationVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
        } catch (Exception ignored) { }
        systemPromptSilenced = false;
        originalSystemVolume = -1;
        originalNotificationVolume = -1;
    }

    private boolean isMusicPlaying() {
        try {
            MediaController controller = MyNotificationListener.getActiveController();
            if (controller != null && controller.getPlaybackState() != null) {
                int state = controller.getPlaybackState().getState();
                return state == android.media.session.PlaybackState.STATE_PLAYING
                        || state == android.media.session.PlaybackState.STATE_BUFFERING
                        || state == android.media.session.PlaybackState.STATE_FAST_FORWARDING
                        || state == android.media.session.PlaybackState.STATE_REWINDING;
            }
        } catch (Exception ignored) { }
        try {
            return audioManager != null && audioManager.isMusicActive();
        } catch (Exception ignored) { }
        return false;
    }

    private void restoreMediaPlaybackIfRecognitionPausedIt() {
        if (!mediaWasPlayingBeforeListen) return;
        if (mainHandler == null) return;
        mainHandler.postDelayed(() -> {
            if (!isRiding || UserPreferences.isMuted(RidingForegroundService.this)) return;
            if (!mediaWasPlayingBeforeListen || isMusicPlaying()) return;
            try {
                MediaController controller = MyNotificationListener.getActiveController();
                if (controller != null && controller.getTransportControls() != null) {
                    controller.getTransportControls().play();
                    return;
                }
            } catch (Exception ignored) { }
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        }, 350L);
    }

    public void handleCommand(String rawCommand) {
        if (rawCommand == null) return;
        String command = normalizeCommand(rawCommand);
        if (command.length() == 0 || isSelfEchoCommand(command)) return;
        if (isDebouncedCommand(command)) return;
        rememberHandledCommand(command);

        if (currentCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (isEndCallCommand(command)) endCall();
            return;
        }

        if (handlePendingSimSelection(command)) return;
        if (handlePendingContactCommand(command)) return;

        if (isMuteOnCommand(command)) {
            enableMuteMode();
            return;
        }
        if (isMuteOffCommand(command)) {
            disableMuteMode();
            return;
        }

        if (isNotificationOffCommand(command)) {
            UserPreferences.setNotificationsEnabled(this, false);
            speakSystem("Notification turned off");
            return;
        }
        if (isNotificationOnCommand(command)) {
            UserPreferences.setNotificationsEnabled(this, true);
            speakSystem("Notification turned on");
            return;
        }

        if (isCancelCommand(command)) {
            clearPendingContactChoice();
            clearPendingCallRequest();
            speakSystem("Cancelled");
            return;
        }

        if (isRideOffCommand(command)) {
            stopRidingModeWithSpeech("Ride mode off");
            return;
        }

        if (currentCallState == TelephonyManager.CALL_STATE_RINGING) {
            if (command.contains("answer") || command.contains("accept")) {
                answerCall();
            } else if (isEndCallCommand(command)) {
                endCall();
            }
            return;
        }

        if (command.startsWith("call ")) {
            callContact(command.substring(5).trim());
        } else if (isMusicPlayCommand(command)) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_PLAY, "", false);
        } else if (isMusicPauseCommand(command)) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_PAUSE, "", false);
        } else if (isMusicNextCommand(command)) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_NEXT, "", false);
        } else if (isMusicPreviousCommand(command)) {
            controlMusic(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "", false);
        } else if (isVolumeMaxCommand(command)) {
            setMusicVolumeToMax();
        } else if (isVolumeUpCommand(command)) {
            adjustMusicVolume(true);
        } else if (isVolumeDownCommand(command)) {
            adjustMusicVolume(false);
        }
    }

    private boolean isSelfEchoCommand(String command) {
        return command.equals("ride mode active")
                || command.equals("voice ready")
                || command.equals("playing music")
                || command.equals("music paused")
                || command.equals("next track")
                || command.equals("previous track")
                || command.equals("volume up") && lastHandledCommand.equals("volume up")
                || command.equals("volume down") && lastHandledCommand.equals("volume down")
                || command.equals("volume max") && lastHandledCommand.equals("volume max");
    }

    private boolean isDebouncedCommand(String command) {
        long now = System.currentTimeMillis();
        long windowMs = isMediaOrVolumeCommand(command) ? 2500L : 1200L;
        return command.equals(lastHandledCommand) && now - lastHandledCommandAt < windowMs;
    }

    private void rememberHandledCommand(String command) {
        lastHandledCommand = command;
        lastHandledCommandAt = System.currentTimeMillis();
    }

    private boolean isMediaOrVolumeCommand(String command) {
        return isMusicPlayCommand(command)
                || isMusicPauseCommand(command)
                || isMusicNextCommand(command)
                || isMusicPreviousCommand(command)
                || isVolumeMaxCommand(command)
                || isVolumeUpCommand(command)
                || isVolumeDownCommand(command);
    }

    private String normalizeCommand(String raw) {
        String command = raw.toLowerCase(Locale.US).trim();
        command = command.replaceAll("[^a-z0-9+ ]", " ").replaceAll("\\s+", " ").trim();
        command = command.replace("right off", "ride off");
        command = command.replace("right of", "ride off");
        command = command.replace("ride of", "ride off");
        command = command.replace("rider off", "ride off");
        command = command.replace("rider of", "ride off");
        command = command.replace("riding off", "ride off");
        command = command.replace("riding of", "ride off");
        command = command.replace("rideof", "ride off");
        command = command.replace("rightof", "ride off");
        command = command.replace("notification on on", "notification on");
        command = command.replace("reed off", "ride off");
        command = command.replace("read off", "ride off");
        command = command.replace("write off", "ride off");
        command = command.replace("write of", "ride off");
        command = command.replace("night off", "ride off");
        command = command.replace("bike off", "ride off");
        command = command.replace("play songs", "play song");
        command = command.replace("play the music", "play music");
        command = command.replace("play my music", "play music");
        command = command.replace("resume song", "play song");
        command = command.replace("resume music", "play music");
        command = command.replace("start music", "play music");
        command = command.replace("start song", "play song");
        command = command.replace("next songs", "next song");
        command = command.replace("next music", "next song");
        command = command.replace("free song", "pre song");
        command = command.replace("pre-song", "pre song");
        command = command.replace("prev song", "pre song");
        command = command.replace("prev music", "pre music");
        command = command.replace("prev track", "pre track");
        command = command.replace("notification of", "notification off");
        command = command.replace("notifications off", "notification off");
        command = command.replace("notifications on", "notification on");
        command = command.replace("notify off", "notif off");
        command = command.replace("notify on", "notif on");
        command = command.replaceAll("\\b(please|hey|ok|okay|now)\\b", " ").replaceAll("\\s+", " ").trim();
        return command;
    }

    private boolean isRideOffCommand(String command) {
        return command.equals("ride off")
                || command.contains(" ride off")
                || command.contains("ride off ")
                || command.equals("motor off")
                || command.equals("engine off")
                || command.equals("stop riding")
                || command.equals("stop riding mode")
                || command.equals("turn off riding mode")
                || command.equals("turn off ride mode")
                || command.equals("turn off engine")
                || command.equals("shutdown riding mode")
                || command.equals("shut down riding mode")
                || command.equals("shut off ride mode")
                || command.equals("bike off");
    }

    private boolean isCancelCommand(String command) {
        return command.equals("cancel")
                || command.equals("cancel call")
                || command.equals("never mind")
                || command.equals("nevermind");
    }

    private boolean isEndCallCommand(String command) {
        return command.equals("finish call")
                || command.equals("end call")
                || command.equals("hang up");
    }

    private boolean isMusicPlayCommand(String command) {
        return command.equals("play music")
                || command.equals("play song")
                || command.equals("play track")
                || command.equals("resume music")
                || command.equals("resume song")
                || command.equals("start music")
                || command.equals("start song");
    }

    private boolean isMusicPauseCommand(String command) {
        return command.equals("pause")
                || command.equals("pause music")
                || command.equals("pause song")
                || command.equals("stop music")
                || command.equals("stop song");
    }

    private boolean isMusicNextCommand(String command) {
        return command.equals("next")
                || command.equals("next song")
                || command.equals("next track")
                || command.equals("next music");
    }

    private boolean isMusicPreviousCommand(String command) {
        return command.equals("previous")
                || command.equals("previous song")
                || command.equals("previous track")
                || command.equals("previous music")
                || command.equals("pre song")
                || command.equals("pre music")
                || command.equals("pre track")
                || command.equals("prev song")
                || command.equals("prev music")
                || command.equals("prev track");
    }

    private boolean isVolumeUpCommand(String command) {
        return command.equals("volume up")
                || command.equals("increase volume")
                || command.equals("sound up")
                || command.equals("turn volume up")
                || command.equals("raise volume")
                || command.equals("louder");
    }

    private boolean isVolumeDownCommand(String command) {
        return command.equals("volume down")
                || command.equals("decrease volume")
                || command.equals("sound down")
                || command.equals("turn volume down")
                || command.equals("lower volume")
                || command.equals("quieter");
    }

    private boolean isVolumeMaxCommand(String command) {
        return command.equals("volume max")
                || command.equals("max volume")
                || command.equals("maximum volume")
                || command.equals("increase to max volume")
                || command.equals("full volume");
    }

    private boolean isMuteOnCommand(String command) {
        return command.equals("mute on");
    }

    private boolean isMuteOffCommand(String command) {
        return command.equals("mute off");
    }

    private boolean isNotificationOffCommand(String command) {
        return command.equals("notif off") || command.equals("notification off");
    }

    private boolean isNotificationOnCommand(String command) {
        return command.equals("notif on") || command.equals("notification on");
    }

    private boolean handlePendingContactCommand(String command) {
        if (pendingContactMatches == null || pendingContactMatches.isEmpty()) return false;

        if (isCancelCommand(command)) {
            clearPendingContactChoice();
            speakSystem("Cancelled");
            return true;
        }

        if (command.equals("find more") || command.equals("more") || command.equals("show more") || command.equals("next results")) {
            int nextStart = (pendingContactPage + 1) * CONTACT_PAGE_SIZE;
            if (nextStart >= pendingContactMatches.size()) {
                speakSystem("No more matches. Say first one, second one, or cancel.");
            } else {
                pendingContactPage++;
                announcePendingContactChoices();
            }
            return true;
        }

        Integer selected = parseSelection(command);
        if (selected != null) {
            int index = pendingContactPage * CONTACT_PAGE_SIZE + selected;
            if (index >= 0 && index < pendingContactMatches.size()) {
                ContactMatch match = pendingContactMatches.get(index);
                clearPendingContactChoice();
                routeCall(match.number, match.name);
            } else {
                speakSystem("That option is not available. Say find more or cancel.");
            }
            return true;
        }

        if (command.startsWith("call ")) {
            clearPendingContactChoice();
            callContact(command.substring(5).trim());
            return true;
        }
        return false;
    }

    private boolean handlePendingSimSelection(String command) {
        if (pendingCallRequest == null) return false;

        if (isCancelCommand(command)) {
            clearPendingCallRequest();
            speakSystem("Cancelled");
            return true;
        }

        Integer simIndex = parseSimSelection(command);
        if (simIndex == null) return false;
        if (simIndex < 0 || simIndex >= pendingCallRequest.phoneAccounts.size()) {
            speakSystem("That sim is not available. Say sim one, sim two, or cancel.");
            return true;
        }

        PendingCallRequest request = pendingCallRequest;
        clearPendingCallRequest();
        placeCall(request.number, request.displayName, request.phoneAccounts.get(simIndex), simIndex);
        return true;
    }

    private Integer parseSelection(String command) {
        if (command.equals("first") || command.equals("first one") || command.equals("one") || command.equals("number one") || command.equals("option one") || command.equals("call first") || command.equals("call one")) return 0;
        if (command.equals("second") || command.equals("second one") || command.equals("two") || command.equals("number two") || command.equals("option two") || command.equals("call second") || command.equals("call two")) return 1;
        return null;
    }

    private Integer parseSimSelection(String command) {
        if (command.equals("sim 1") || command.equals("sim one") || command.equals("simcard one") || command.equals("first sim") || command.equals("first simcard") || command.equals("first")) return 0;
        if (command.equals("sim 2") || command.equals("sim two") || command.equals("simcard two") || command.equals("second sim") || command.equals("second simcard") || command.equals("second")) return 1;
        return null;
    }

    private void callContact(String nameOrNumber) {
        if (nameOrNumber == null || nameOrNumber.trim().length() == 0) return;
        String query = nameOrNumber.trim();
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            speakSystem("Call permission missing");
            return;
        }
        if (query.matches("[+0-9 ()-]+")) {
            routeCall(query, null);
            return;
        }
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            speakSystem("Contacts permission missing");
            return;
        }

        List<ContactMatch> matches = findContactMatches(query);
        if (matches.isEmpty()) {
            speakSystem("Contact not found");
            return;
        }

        if (isConfidentSingleMatch(query, matches)) {
            ContactMatch match = matches.get(0);
            routeCall(match.number, match.name);
            return;
        }

        pendingContactMatches = matches;
        pendingContactPage = 0;
        announcePendingContactChoices();
    }

    private boolean isConfidentSingleMatch(String query, List<ContactMatch> matches) {
        ContactMatch top = matches.get(0);
        String normalizedQuery = normalizeForMatch(query);
        int exactCount = 0;
        for (ContactMatch match : matches) {
            if (match.normalizedName.equals(normalizedQuery)) exactCount++;
        }
        if (top.customPriority && top.normalizedName.equals(normalizedQuery)) return true;
        if (top.normalizedName.equals(normalizedQuery) && exactCount == 1) return true;
        if (matches.size() == 1 && top.score >= 600) return true;
        if (matches.size() > 1) {
            ContactMatch second = matches.get(1);
            return top.score >= 960 && top.score - second.score >= 160;
        }
        return false;
    }

    private void announcePendingContactChoices() {
        if (pendingContactMatches == null || pendingContactMatches.isEmpty()) return;
        int start = pendingContactPage * CONTACT_PAGE_SIZE;
        int end = Math.min(start + CONTACT_PAGE_SIZE, pendingContactMatches.size());
        if (start >= end) {
            speakSystem("No more matches. Say cancel.");
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("I found ");
        for (int i = start; i < end; i++) {
            if (i > start) builder.append(i == end - 1 ? " and " : ", ");
            builder.append(pendingContactMatches.get(i).name);
        }
        builder.append(". Say ");
        builder.append(end - start == 1 ? "first one" : "first one or second one");
        if (end < pendingContactMatches.size()) builder.append(", find more");
        builder.append(", or cancel.");
        speakSystem(builder.toString());
    }

    private void clearPendingContactChoice() {
        pendingContactMatches = null;
        pendingContactPage = 0;
    }

    private void routeCall(String number, String displayName) {
        List<PhoneAccountHandle> phoneAccounts = getCallCapablePhoneAccounts();
        if (phoneAccounts.size() > 1) {
            pendingCallRequest = new PendingCallRequest(displayName, number, phoneAccounts);
            String target = displayName == null || displayName.trim().length() == 0 ? "this number" : displayName;
            speakSystem("I am ready to call " + target + ". Say sim one or sim two.");
            return;
        }
        placeCall(number, displayName, phoneAccounts.isEmpty() ? null : phoneAccounts.get(0), 0);
    }

    private void clearPendingCallRequest() {
        pendingCallRequest = null;
    }

    private List<ContactMatch> findContactMatches(String query) {
        List<ContactMatch> matches = new ArrayList<>();
        String normalizedQuery = normalizeForMatch(query);
        if (normalizedQuery.length() == 0) return matches;

        for (UserPreferences.CustomContact contact : UserPreferences.getCustomContacts(this)) {
            int score = scoreContactName(contact.name, normalizedQuery) + 150;
            if (score > 0) matches.add(new ContactMatch(contact.name, contact.number, score, true));
        }

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    String number = cursor.getString(1);
                    if (name == null || number == null) continue;
                    int score = scoreContactName(name, normalizedQuery);
                    if (score > 0) matches.add(new ContactMatch(name, number, score, false));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        Collections.sort(matches, new Comparator<ContactMatch>() {
            @Override
            public int compare(ContactMatch left, ContactMatch right) {
                if (right.score != left.score) return right.score - left.score;
                if (left.customPriority != right.customPriority) return right.customPriority ? 1 : -1;
                return left.name.length() - right.name.length();
            }
        });
        return dedupeContacts(matches);
    }

    private List<ContactMatch> dedupeContacts(List<ContactMatch> input) {
        List<ContactMatch> output = new ArrayList<>();
        for (ContactMatch match : input) {
            boolean exists = false;
            String normalizedNumber = match.number.replaceAll("[^+0-9]", "");
            for (ContactMatch seen : output) {
                if (seen.number.replaceAll("[^+0-9]", "").equals(normalizedNumber)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) output.add(match);
            if (output.size() >= 12) break;
        }
        return output;
    }

    private int scoreContactName(String displayName, String normalizedQuery) {
        String normalizedName = normalizeForMatch(displayName);
        if (normalizedName.length() == 0) return 0;
        if (normalizedName.equals(normalizedQuery)) return 1000;
        String[] tokens = normalizedName.split(" ");
        for (String token : tokens) {
            if (token.equals(normalizedQuery)) return 940;
        }
        if (normalizedName.startsWith(normalizedQuery + " ")) return 900;
        for (String token : tokens) {
            if (token.startsWith(normalizedQuery)) return 840;
        }
        if (normalizedName.contains(" " + normalizedQuery + " ") || normalizedName.endsWith(" " + normalizedQuery)) return 760;
        if (normalizedName.contains(normalizedQuery)) return 640;
        return 0;
    }

    private String normalizeForMatch(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9+ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void placeCall(String number, String displayName, PhoneAccountHandle phoneAccountHandle, int simIndex) {
        if (number == null || number.trim().length() == 0) {
            speakSystem("Contact not found");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (phoneAccountHandle != null && Build.VERSION.SDK_INT >= 23) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
                intent.putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", phoneAccountHandle);
            }
            startActivity(intent);
            String label = displayName == null || displayName.trim().length() == 0 ? "number" : displayName;
            if (phoneAccountHandle != null && getCallCapablePhoneAccounts().size() > 1) {
                speakSystem("Calling " + label + " on sim " + (simIndex + 1));
            } else {
                speakSystem("Calling " + label);
            }
        } catch (Exception e) {
            speakSystem("Call failed");
        }
    }

    private List<PhoneAccountHandle> getCallCapablePhoneAccounts() {
        List<PhoneAccountHandle> result = new ArrayList<>();
        try {
            TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (telecomManager != null) {
                List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
                if (accounts != null) result.addAll(accounts);
            }
        } catch (Exception ignored) { }
        if (result.isEmpty() && Build.VERSION.SDK_INT >= 22) {
            try {
                SubscriptionManager subscriptionManager = getSystemService(SubscriptionManager.class);
                if (subscriptionManager != null && hasPermission(Manifest.permission.READ_PHONE_STATE) && subscriptionManager.getActiveSubscriptionInfoList() != null) {
                }
            } catch (Exception ignored) { }
        }
        return result;
    }

    private String resolveContactName(String number) {
        if (number == null || number.length() == 0) return "Unknown caller";
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return "Caller";
        for (UserPreferences.CustomContact contact : UserPreferences.getCustomContacts(this)) {
            String cleanedStored = contact.number.replaceAll("[^+0-9]", "");
            String cleanedInput = number.replaceAll("[^+0-9]", "");
            if (cleanedStored.equals(cleanedInput) || cleanedStored.endsWith(cleanedInput) || cleanedInput.endsWith(cleanedStored)) {
                return contact.name;
            }
        }
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
            speakSystem("Answer is not supported");
            return;
        }
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            speakSystem("Answer call permission missing");
            return;
        }
        boolean attempted = false;
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                tm.acceptRingingCall();
                attempted = true;
            }
        } catch (Exception ignored) { }
        try {
            dispatchMediaKey(KeyEvent.KEYCODE_HEADSETHOOK);
            attempted = true;
        } catch (Exception ignored) { }
        speakSystem(attempted ? "Answering" : "Answer failed");
    }

    private void endCall() {
        if (Build.VERSION.SDK_INT < 28) {
            speakSystem("Hang up is not supported");
            return;
        }
        if (!hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)) {
            speakSystem("Call control permission missing");
            return;
        }
        boolean attempted = false;
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null) {
                tm.endCall();
                attempted = true;
            }
        } catch (Exception ignored) { }
        try {
            dispatchMediaKey(KeyEvent.KEYCODE_HEADSETHOOK);
            attempted = true;
        } catch (Exception ignored) { }
        speakSystem(attempted ? "Ending call" : "End call failed");
    }

    private void controlMusic(int keyCode, String confirmation, boolean speakConfirmation) {
        boolean handledByController = false;
        try {
            MediaController controller = MyNotificationListener.getActiveController();
            if (controller != null && controller.getTransportControls() != null) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) controller.getTransportControls().play();
                else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) controller.getTransportControls().pause();
                else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) controller.getTransportControls().skipToNext();
                else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) controller.getTransportControls().skipToPrevious();
                handledByController = true;
            }
        } catch (Exception ignored) {
            handledByController = false;
        }
        if (!handledByController) dispatchMediaKey(keyCode);
        if (speakConfirmation) speakSystem(confirmation);
    }

    private void pauseMusicSilently() {
        controlMusic(KeyEvent.KEYCODE_MEDIA_PAUSE, "", false);
    }

    private void dispatchMediaKey(int keyCode) {
        if (audioManager == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private void adjustMusicVolume(boolean increase) {
        if (audioManager == null) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    increase ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            // Keep media/volume commands silent so TTS does not interrupt or get re-recognized.
        } catch (Exception ignored) { }
    }

    private void setMusicVolumeToMax() {
        if (audioManager == null) return;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            // Keep media/volume commands silent so TTS does not interrupt or get re-recognized.
        } catch (Exception ignored) { }
    }

    private void enableMuteMode() {
        UserPreferences.setMuted(this, true);
        clearPendingContactChoice();
        clearPendingCallRequest();
        pauseMusicSilently();
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) { }
        stopAfterCurrentSpeech = false;
        speechOutputActive = false;
        restoreManualAudioState();
        restartListening();
    }

    private void disableMuteMode() {
        UserPreferences.setMuted(this, false);
        speakSystem("Mute off");
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void speakSystem(String text) {
        if (UserPreferences.isMuted(this)) return;
        speakInternal(text, true, false);
    }

    private void speakNotification(String text) {
        if (UserPreferences.isMuted(this) || !UserPreferences.areNotificationsEnabled(this)) return;
        speakInternal(text, false, false);
    }

    private void stopRidingModeWithSpeech(String message) {
        clearPendingContactChoice();
        clearPendingCallRequest();
        if (!ttsReady || tts == null || UserPreferences.isMuted(this)) {
            shutdownRidingMode(true);
            stopSelf();
            return;
        }
        speakInternal(message, true, true);
    }

    private void speakInternal(String text, boolean flush, boolean stopAfterSpeech) {
        if (!ttsReady || tts == null || text == null || text.trim().length() == 0) return;
        try {
            stopAfterCurrentSpeech = stopAfterSpeech;
            speechOutputActive = true;
            stopListening();
            beginManualDucking();
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            String utteranceId = "RidingModeUtterance" + (++utteranceCounter);
            int result = tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, utteranceId);
            if (result == TextToSpeech.ERROR) {
                onSpeechFinished();
            } else if (mainHandler != null) {
                long watchdogMs = Math.max(1800L, Math.min(7000L, text.length() * 75L + 1200L));
                mainHandler.postDelayed(() -> {
                    if (speechOutputActive) onSpeechFinished();
                }, watchdogMs);
            }
        } catch (Exception ignored) {
            onSpeechFinished();
        }
    }

    private void beginManualDucking() {
        if (audioManager == null || mediaDuckingActive) return;
        try {
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int ducked = Math.max(1, Math.min(originalMusicVolume, Math.round(max * 0.35f)));
            if (originalMusicVolume > ducked) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, ducked, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                mediaDuckingActive = true;
            }
        } catch (Exception ignored) {
            mediaDuckingActive = false;
            originalMusicVolume = -1;
        }
    }

    private void restoreManualAudioState() {
        restoreRecognitionSystemPrompt();
        if (audioManager != null && mediaDuckingActive && originalMusicVolume >= 0) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            } catch (Exception ignored) { }
        }
        mediaDuckingActive = false;
        originalMusicVolume = -1;
    }

    private void onSpeechFinished() {
        speechOutputActive = false;
        restoreManualAudioState();
        if (stopAfterCurrentSpeech) {
            stopAfterCurrentSpeech = false;
            shutdownRidingMode(true);
            stopSelf();
            return;
        }
        scheduleListeningRestart(1200L);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Ride mode", NotificationManager.IMPORTANCE_LOW);
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
                .setContentTitle("Ride mode")
                .setContentText("Listening for music, call, sim, mute, notification, and ride off commands")
                .setSmallIcon(R.drawable.ic_stat_riding)
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
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    if (mainHandler != null) mainHandler.post(() -> onSpeechFinished());
                }

                @Override
                public void onError(String utteranceId) {
                    if (mainHandler != null) mainHandler.post(() -> onSpeechFinished());
                }
            });
            ttsReady = true;
            if (isRiding && !UserPreferences.isMuted(this)) speakSystem("Voice ready");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        shutdownRidingMode(true);
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
            speechRecognizer = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
        foregroundPromoted = false;
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    private static class ContactMatch {
        final String name;
        final String number;
        final int score;
        final String normalizedName;
        final boolean customPriority;

        ContactMatch(String name, String number, int score, boolean customPriority) {
            this.name = name;
            this.number = number;
            this.score = score;
            this.customPriority = customPriority;
            this.normalizedName = Normalizer.normalize(name, Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                    .toLowerCase(Locale.US)
                    .replaceAll("[^a-z0-9+ ]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    private static class PendingCallRequest {
        final String displayName;
        final String number;
        final List<PhoneAccountHandle> phoneAccounts;

        PendingCallRequest(String displayName, String number, List<PhoneAccountHandle> phoneAccounts) {
            this.displayName = displayName;
            this.number = number;
            this.phoneAccounts = new ArrayList<>(phoneAccounts);
        }
    }
}
