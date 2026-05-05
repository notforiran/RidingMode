package com.ridingmode.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7001;

    private Button engineButton;
    private Button contactsButton;
    private Button commandsButton;
    private TextView engineStatus;
    private TextView engineSymbol;
    private View contactsDrawer;
    private View commandsDrawer;
    private EditText inputContactName;
    private EditText inputContactNumber;
    private LinearLayout contactsListContainer;
    private TextView commandsText;
    private MediaPlayer mediaPlayer;
    private SpeechRecognizer activitySpeechRecognizer;
    private Intent activitySpeechIntent;
    private boolean activityListening;
    private Runnable activityRestartRunnable;
    private Runnable engineStopRunnable;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String[] requestedPermissions;
    private boolean pendingStartAfterNotificationSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        applyApprovedFullScreenDesign();
        setContentView(R.layout.activity_main);

        engineButton = findViewById(R.id.engine_button);
        contactsButton = findViewById(R.id.contacts_button);
        commandsButton = findViewById(R.id.commands_button);
        engineStatus = findViewById(R.id.engine_status);
        engineSymbol = findViewById(R.id.engine_symbol);
        contactsDrawer = findViewById(R.id.contacts_drawer);
        commandsDrawer = findViewById(R.id.commands_drawer);
        inputContactName = findViewById(R.id.input_contact_name);
        inputContactNumber = findViewById(R.id.input_contact_number);
        contactsListContainer = findViewById(R.id.contacts_list_container);
        commandsText = findViewById(R.id.commands_text);
        requestedPermissions = buildRequestedPermissions();

        if (commandsText != null) commandsText.setText(buildCommandsPreview());
        if (inputContactName != null) inputContactName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (inputContactNumber != null) inputContactNumber.setInputType(InputType.TYPE_CLASS_PHONE);

        View.OnClickListener engineClickListener = v -> {
            if (!RidingForegroundService.isRiding) ensureReadyAndStart();
            else stopRidingMode();
        };
        if (engineButton != null) engineButton.setOnClickListener(engineClickListener);
        if (engineSymbol != null) engineSymbol.setOnClickListener(engineClickListener);
        if (commandsButton != null) commandsButton.setOnClickListener(v -> toggleDrawer(commandsDrawer, true));
        if (contactsButton != null) contactsButton.setOnClickListener(v -> toggleDrawer(contactsDrawer, false));

        View closeCommands = findViewById(R.id.close_commands);
        View closeContacts = findViewById(R.id.close_contacts);
        View addContact = findViewById(R.id.btn_add_contact);
        if (closeCommands != null) closeCommands.setOnClickListener(v -> hideDrawer(commandsDrawer, true));
        if (closeContacts != null) closeContacts.setOnClickListener(v -> hideDrawer(contactsDrawer, false));
        if (addContact != null) addContact.setOnClickListener(v -> savePriorityContact());

        syncUiWithService();
        refreshContactList();
    }

    private void applyApprovedFullScreenDesign() {
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF262B31);
            getWindow().setNavigationBarColor(0xFF262B31);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyApprovedFullScreenDesign();
        syncUiWithService();
        refreshContactList();
        if (RidingForegroundService.isRiding && hasCriticalPermissions()) {
            RidingForegroundService.setActivityVoiceActive(true);
            initActivityVoiceRecognizer();
            scheduleActivityVoiceRestart(700L);
        }
        if (pendingStartAfterNotificationSettings) {
            pendingStartAfterNotificationSettings = false;
            if (hasCriticalPermissions()) {
                if (!isNotificationServiceEnabled()) {
                    Toast.makeText(this, "Notification access is still off. Music control will use fallback media keys.", Toast.LENGTH_LONG).show();
                }
                startRidingMode();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopActivityVoiceRecognition();
        RidingForegroundService.setActivityVoiceActive(false);
    }

    private String[] buildRequestedPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.CALL_PHONE);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 26) permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
        if (Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        return permissions.toArray(new String[0]);
    }

    private void ensureReadyAndStart() {
        if (Build.VERSION.SDK_INT >= 23 && !hasAllRuntimePermissions()) {
            requestPermissions(requestedPermissions, REQUEST_PERMISSIONS);
            return;
        }
        if (!isNotificationServiceEnabled()) {
            pendingStartAfterNotificationSettings = true;
            Toast.makeText(this, "Enable notification access for reliable music control, then return to Ride mode.", Toast.LENGTH_LONG).show();
            if (openNotificationSettingsSafely()) return;
            startRidingMode();
            return;
        }
        startRidingMode();
    }

    private boolean hasAllRuntimePermissions() {
        for (String permission : requestedPermissions) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean hasCriticalPermissions() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (!hasCriticalPermissions()) {
            Toast.makeText(this, "Microphone permission is required for Ride mode", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasAllRuntimePermissions()) {
            Toast.makeText(this, "Some optional permissions were denied. Calls, Bluetooth or notifications may be limited.", Toast.LENGTH_LONG).show();
        }
        if (!isNotificationServiceEnabled()) {
            pendingStartAfterNotificationSettings = true;
            Toast.makeText(this, "Enable notification access for reliable music control, then return to Ride mode.", Toast.LENGTH_LONG).show();
            if (openNotificationSettingsSafely()) return;
            startRidingMode();
            return;
        }
        startRidingMode();
    }

    private boolean openNotificationSettingsSafely() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Notification access settings not found. Ride mode will start with fallback music control.", Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            Toast.makeText(this, "Could not open notification access settings. Ride mode will start with fallback music control.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean isNotificationServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.toLowerCase(Locale.US).contains(getPackageName().toLowerCase(Locale.US));
    }

    private void startRidingMode() {
        setEngineOnVisuals(true);
        Intent intent = new Intent(this, RidingForegroundService.class);
        intent.setAction(RidingForegroundService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            RidingForegroundService.setActivityVoiceActive(true);
            initActivityVoiceRecognizer();
            scheduleActivityVoiceRestart(1200L);
            playEngineSound();
        } catch (Exception e) {
            setEngineOnVisuals(false);
            Toast.makeText(this, "Could not start Ride mode service", Toast.LENGTH_LONG).show();
        }
    }

    private void stopRidingMode() {
        setEngineOnVisuals(false);
        RidingForegroundService.setActivityVoiceActive(false);
        stopActivityVoiceRecognition();
        Intent intent = new Intent(this, RidingForegroundService.class);
        intent.setAction(RidingForegroundService.ACTION_STOP);
        try {
            startService(intent);
        } catch (Exception ignored) { }
    }

    private void syncUiWithService() {
        setEngineOnVisuals(RidingForegroundService.isRiding);
    }

    private void setEngineOnVisuals(boolean isOn) {
        int color = isOn ? 0xFF6BFFAF : 0xFFFF5F75;
        if (engineButton != null) engineButton.setBackgroundResource(isOn ? R.drawable.bg_engine_button_on : R.drawable.bg_engine_button_off);
        if (engineStatus != null) {
            engineStatus.setText(isOn ? "Engine on" : "Engine off");
            engineStatus.setTextColor(color);
        }
        if (engineSymbol != null) engineSymbol.setTextColor(color);
    }

    private void playEngineSound() {
        if (UserPreferences.isMuted(this)) return;
        try {
            if (engineStopRunnable != null) {
                uiHandler.removeCallbacks(engineStopRunnable);
                engineStopRunnable = null;
            }
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            mediaPlayer = MediaPlayer.create(this, R.raw.engine_start);
            if (mediaPlayer != null) {
                mediaPlayer.setOnCompletionListener(mp -> {
                    try { mp.release(); } catch (Exception ignored) { }
                    if (mediaPlayer == mp) mediaPlayer = null;
                });
                mediaPlayer.start();
                engineStopRunnable = () -> {
                    try {
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.stop();
                            mediaPlayer.release();
                            mediaPlayer = null;
                        }
                    } catch (Exception ignored) { }
                };
                uiHandler.postDelayed(engineStopRunnable, 1800L);
            }
        } catch (Exception ignored) { }
    }

    private void toggleDrawer(View drawer, boolean fromRight) {
        if (drawer == null) return;
        if (drawer.getVisibility() == View.VISIBLE) hideDrawer(drawer, fromRight);
        else showDrawer(drawer, fromRight);
    }

    private void showDrawer(View drawer, boolean fromRight) {
        if (drawer == null) return;
        hideDrawer(fromRight ? contactsDrawer : commandsDrawer, !fromRight);
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0f).setDuration(220).start();
    }

    private void hideDrawer(View drawer, boolean fromRight) {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        float target = fromRight ? drawer.getWidth() : -drawer.getWidth();
        if (target == 0f) target = fromRight ? 320f : -320f;
        drawer.animate().translationX(target).setDuration(200).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void savePriorityContact() {
        if (inputContactName == null || inputContactNumber == null) return;
        String name = inputContactName.getText() == null ? "" : inputContactName.getText().toString().trim();
        String number = inputContactNumber.getText() == null ? "" : inputContactNumber.getText().toString().trim();
        if (name.length() == 0 || number.length() == 0) {
            Toast.makeText(this, "Enter both a contact name and a phone number", Toast.LENGTH_SHORT).show();
            return;
        }
        UserPreferences.upsertCustomContact(this, name, number);
        inputContactName.setText("");
        inputContactNumber.setText("");
        refreshContactList();
        Toast.makeText(this, "Priority contact saved", Toast.LENGTH_SHORT).show();
    }

    private void refreshContactList() {
        if (contactsListContainer == null) return;
        contactsListContainer.removeAllViews();
        List<UserPreferences.CustomContact> contacts = UserPreferences.getCustomContacts(this);
        if (contacts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No priority contacts yet.");
            empty.setTextColor(0xFFA6ADB7);
            empty.setTextSize(13f);
            contactsListContainer.addView(empty);
            return;
        }
        for (int i = 0; i < contacts.size(); i++) {
            final int index = i;
            UserPreferences.CustomContact contact = contacts.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(10);
            row.setLayoutParams(rowParams);
            row.setBackgroundResource(R.drawable.bg_panel);

            LinearLayout textWrap = new LinearLayout(this);
            textWrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textWrapParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textWrap.setLayoutParams(textWrapParams);

            TextView name = new TextView(this);
            name.setText(contact.name);
            name.setTextColor(0xFFF7F8FA);
            name.setTextSize(16f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView number = new TextView(this);
            number.setText(contact.number);
            number.setTextColor(0xFFCBD0D7);
            number.setTextSize(13f);

            textWrap.addView(name);
            textWrap.addView(number);

            Button remove = new Button(this);
            remove.setText("Remove");
            remove.setTextSize(12f);
            remove.setTextColor(0xFFF7F8FA);
            remove.setBackgroundResource(R.drawable.bg_small_button);
            remove.setOnClickListener(v -> {
                UserPreferences.removeCustomContact(MainActivity.this, index);
                refreshContactList();
            });

            row.addView(textWrap);
            row.addView(remove);
            contactsListContainer.addView(row);
        }
    }

    private void initActivityVoiceRecognizer() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (activitySpeechRecognizer != null && activitySpeechIntent != null) return;
        try {
            activitySpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            activitySpeechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
            activitySpeechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            activitySpeechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { activityListening = true; }
                @Override public void onBeginningOfSpeech() { activityListening = true; }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { activityListening = false; }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    activityListening = false;
                    if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        rebuildActivityVoiceRecognizer();
                        scheduleActivityVoiceRestart(1200L);
                    } else {
                        scheduleActivityVoiceRestart(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? 450L : 900L);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    activityListening = false;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        for (String match : matches) {
                            if (match == null) continue;
                            String normalized = normalizeActivityCommand(match);
                            if (looksLikeActivityCommand(normalized)) {
                                RidingForegroundService.deliverVoiceCommand(match);
                                scheduleActivityVoiceRestart(700L);
                                return;
                            }
                        }
                        RidingForegroundService.deliverVoiceCommand(matches.get(0));
                    }
                    scheduleActivityVoiceRestart(700L);
                }
            });
        } catch (Exception ignored) {
            activitySpeechRecognizer = null;
            activitySpeechIntent = null;
            activityListening = false;
        }
    }

    private void rebuildActivityVoiceRecognizer() {
        try {
            if (activitySpeechRecognizer != null) activitySpeechRecognizer.destroy();
        } catch (Exception ignored) { }
        activitySpeechRecognizer = null;
        activitySpeechIntent = null;
        activityListening = false;
        initActivityVoiceRecognizer();
    }

    private void scheduleActivityVoiceRestart(long delayMs) {
        if (!RidingForegroundService.isRiding || uiHandler == null) return;
        if (activityRestartRunnable != null) uiHandler.removeCallbacks(activityRestartRunnable);
        activityRestartRunnable = this::startActivityVoiceRecognition;
        uiHandler.postDelayed(activityRestartRunnable, Math.max(250L, delayMs));
    }

    private void startActivityVoiceRecognition() {
        if (!RidingForegroundService.isRiding || activityListening) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        initActivityVoiceRecognizer();
        if (activitySpeechRecognizer == null || activitySpeechIntent == null) return;
        try {
            RidingForegroundService.setActivityVoiceActive(true);
            activityListening = true;
            activitySpeechRecognizer.startListening(activitySpeechIntent);
        } catch (Exception e) {
            activityListening = false;
            rebuildActivityVoiceRecognizer();
            scheduleActivityVoiceRestart(1200L);
        }
    }

    private void stopActivityVoiceRecognition() {
        if (uiHandler != null && activityRestartRunnable != null) uiHandler.removeCallbacks(activityRestartRunnable);
        activityListening = false;
        if (activitySpeechRecognizer != null) {
            try { activitySpeechRecognizer.cancel(); } catch (Exception ignored) { }
        }
    }

    private void destroyActivityVoiceRecognizer() {
        stopActivityVoiceRecognition();
        if (activitySpeechRecognizer != null) {
            try { activitySpeechRecognizer.destroy(); } catch (Exception ignored) { }
        }
        activitySpeechRecognizer = null;
        activitySpeechIntent = null;
    }

    private String normalizeActivityCommand(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9+ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean looksLikeActivityCommand(String command) {
        if (command.length() == 0) return false;
        if (command.startsWith("call ")) return true;
        return command.equals("play")
                || command.contains("play music")
                || command.contains("play song")
                || command.contains("pause")
                || command.contains("next")
                || command.contains("previous")
                || command.contains("pre song")
                || command.contains("pre music")
                || command.contains("pre track")
                || command.contains("volume")
                || command.contains("ride off")
                || command.contains("right off")
                || command.contains("engine off")
                || command.contains("motor off")
                || command.contains("notif")
                || command.contains("notification")
                || command.equals("mute on")
                || command.equals("mute off")
                || command.equals("answer")
                || command.equals("accept")
                || command.equals("finish call")
                || command.equals("end call")
                || command.equals("hang up")
                || command.equals("first")
                || command.equals("second")
                || command.equals("first one")
                || command.equals("second one")
                || command.contains("sim one")
                || command.contains("sim two")
                || command.contains("find more")
                || command.equals("cancel");
    }

    private String buildCommandsPreview() {
        return "Ride off\n"
                + "Play music\n"
                + "Play song\n"
                + "Pause music\n"
                + "Next song\n"
                + "Next track\n"
                + "Pre song\n"
                + "Pre music\n"
                + "Pre track\n"
                + "Volume up\n"
                + "Volume down\n"
                + "Volume max\n"
                + "Call [name]\n"
                + "First one\n"
                + "Second one\n"
                + "Find more\n"
                + "Cancel\n"
                + "Sim 1\n"
                + "Sim 2\n"
                + "First\n"
                + "Second\n"
                + "Answer\n"
                + "Accept\n"
                + "Finish call\n"
                + "End call\n"
                + "Hang up\n"
                + "Notif off\n"
                + "Notif on\n"
                + "Mute on\n"
                + "Mute off\n";
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    protected void onDestroy() {
        destroyActivityVoiceRecognizer();
        RidingForegroundService.setActivityVoiceActive(false);
        if (engineStopRunnable != null) {
            uiHandler.removeCallbacks(engineStopRunnable);
            engineStopRunnable = null;
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
