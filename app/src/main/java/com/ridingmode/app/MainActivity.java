package com.ridingmode.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7001;

    private ImageView backgroundScreen;
    private View engineHotspot;
    private View contactsHotspot;
    private View commandsHotspot;
    private View contactsDrawer;
    private View commandsDrawer;
    private EditText inputContactName;
    private EditText inputContactNumber;
    private LinearLayout contactsListContainer;
    private TextView commandsText;
    private MediaPlayer mediaPlayer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String[] requestedPermissions;
    private boolean pendingStartAfterNotificationSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lockApprovedFullScreenDesign();
        setContentView(R.layout.activity_main);

        backgroundScreen = findViewById(R.id.background_screen);
        engineHotspot = findViewById(R.id.engine_hotspot);
        contactsHotspot = findViewById(R.id.contacts_hotspot);
        commandsHotspot = findViewById(R.id.commands_hotspot);
        contactsDrawer = findViewById(R.id.contacts_drawer);
        commandsDrawer = findViewById(R.id.commands_drawer);
        inputContactName = findViewById(R.id.input_contact_name);
        inputContactNumber = findViewById(R.id.input_contact_number);
        contactsListContainer = findViewById(R.id.contacts_list_container);
        commandsText = findViewById(R.id.commands_text);
        requestedPermissions = buildRequestedPermissions();

        commandsText.setText(buildCommandsPreview());
        inputContactName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        inputContactNumber.setInputType(InputType.TYPE_CLASS_PHONE);

        engineHotspot.setOnClickListener(v -> {
            if (!RidingForegroundService.isRiding) ensureReadyAndStart();
            else stopRidingMode();
        });
        commandsHotspot.setOnClickListener(v -> toggleDrawer(commandsDrawer, true));
        contactsHotspot.setOnClickListener(v -> toggleDrawer(contactsDrawer, false));
        findViewById(R.id.close_commands).setOnClickListener(v -> hideDrawer(commandsDrawer, true));
        findViewById(R.id.close_contacts).setOnClickListener(v -> hideDrawer(contactsDrawer, false));
        findViewById(R.id.btn_add_contact).setOnClickListener(v -> savePriorityContact());

        syncUiWithService();
        refreshContactList();
    }

    private void lockApprovedFullScreenDesign() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF000000);
            getWindow().setNavigationBarColor(0xFF000000);
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
        lockApprovedFullScreenDesign();
        syncUiWithService();
        refreshContactList();
        if (pendingStartAfterNotificationSettings) {
            pendingStartAfterNotificationSettings = false;
            if (hasCriticalPermissions()) {
                if (!isNotificationServiceEnabled()) {
                    Toast.makeText(this, "Notification Access is still off. Music control will use fallback media keys.", Toast.LENGTH_LONG).show();
                }
                startRidingMode();
            }
        }
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
            Toast.makeText(this, "Enable Notification Access for reliable music control, then return to Riding Mode.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
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
            Toast.makeText(this, "Microphone permission is required for riding mode", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasAllRuntimePermissions()) {
            Toast.makeText(this, "Some optional permissions were denied. Calls, Bluetooth or notifications may be limited.", Toast.LENGTH_LONG).show();
        }
        if (!isNotificationServiceEnabled()) {
            pendingStartAfterNotificationSettings = true;
            Toast.makeText(this, "Enable Notification Access for reliable music control, then return to Riding Mode.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return;
        }
        startRidingMode();
    }

    private boolean isNotificationServiceEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.toLowerCase(Locale.US).contains(getPackageName().toLowerCase(Locale.US));
    }

    private void startRidingMode() {
        setEngineOnVisuals(true);
        playEngineSound();
        Intent intent = new Intent(this, RidingForegroundService.class);
        intent.setAction(RidingForegroundService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            setEngineOnVisuals(false);
            Toast.makeText(this, "Could not start riding service", Toast.LENGTH_LONG).show();
        }
    }

    private void stopRidingMode() {
        setEngineOnVisuals(false);
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
        if (backgroundScreen == null) return;
        backgroundScreen.setImageResource(isOn ? R.drawable.main_screen_on : R.drawable.main_screen_off);
    }

    private void playEngineSound() {
        try {
            uiHandler.removeCallbacksAndMessages(null);
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
                uiHandler.postDelayed(() -> {
                    try {
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.stop();
                            mediaPlayer.release();
                            mediaPlayer = null;
                        }
                    } catch (Exception ignored) { }
                }, 1800L);
            }
        } catch (Exception ignored) { }
    }

    private void toggleDrawer(View drawer, boolean fromRight) {
        if (drawer.getVisibility() == View.VISIBLE) hideDrawer(drawer, fromRight);
        else showDrawer(drawer, fromRight);
    }

    private void showDrawer(View drawer, boolean fromRight) {
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
        contactsListContainer.removeAllViews();
        List<UserPreferences.CustomContact> contacts = UserPreferences.getCustomContacts(this);
        if (contacts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No priority contacts yet.");
            empty.setTextColor(0xFF64748B);
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
            name.setTextColor(0xFFF8FAFC);
            name.setTextSize(16f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView number = new TextView(this);
            number.setText(contact.number);
            number.setTextColor(0xFF94A3B8);
            number.setTextSize(13f);

            textWrap.addView(name);
            textWrap.addView(number);

            Button remove = new Button(this);
            remove.setText("Remove");
            remove.setTextSize(12f);
            remove.setTextColor(0xFFF8FAFC);
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

    private String buildCommandsPreview() {
        return "Ride control\n"
                + "• ride off\n\n"
                + "Music\n"
                + "• play music / play song\n"
                + "• pause music\n"
                + "• next song / next track\n"
                + "• pre song / pre music / pre track\n"
                + "• volume up / volume down / volume max\n\n"
                + "Calls\n"
                + "• call [name]\n"
                + "• first one / second one\n"
                + "• find more / cancel\n"
                + "• sim 1 / sim 2 / first / second\n"
                + "• answer / accept\n"
                + "• finish call / end call / hang up\n\n"
                + "Alerts\n"
                + "• notif off / notification off\n"
                + "• notif on / notification on\n"
                + "• mute on / mute off\n";
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
