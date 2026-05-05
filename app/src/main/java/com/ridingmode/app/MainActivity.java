package com.ridingmode.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7001;

    private Button btnStartEngine;
    private TextView statusText;
    private View pulseRing;
    private MediaPlayer mediaPlayer;
    private ObjectAnimator pulseAnimator;
    private String[] requestedPermissions;
    private boolean pendingStartAfterNotificationSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF030712);
            getWindow().setNavigationBarColor(0xFF030712);
        }

        btnStartEngine = findViewById(R.id.btn_start_engine);
        statusText = findViewById(R.id.status_text);
        pulseRing = findViewById(R.id.pulse_ring);
        requestedPermissions = buildRequestedPermissions();

        btnStartEngine.setOnClickListener(v -> {
            if (!RidingForegroundService.isRiding) ensureReadyAndStart();
            else stopRidingMode();
        });
        syncUiWithService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUiWithService();
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
        turnOnEngineVisuals();
        Intent intent = new Intent(this, RidingForegroundService.class);
        intent.setAction(RidingForegroundService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            turnOffEngineVisuals();
            Toast.makeText(this, "Could not start riding service", Toast.LENGTH_LONG).show();
        }
    }

    private void stopRidingMode() {
        turnOffEngineVisuals();
        Intent intent = new Intent(this, RidingForegroundService.class);
        intent.setAction(RidingForegroundService.ACTION_STOP);
        try {
            startService(intent);
        } catch (Exception ignored) { }
    }

    private void syncUiWithService() {
        if (RidingForegroundService.isRiding) turnOnEngineVisualsWithoutSound();
        else turnOffEngineVisuals();
    }

    private void turnOnEngineVisuals() {
        turnOnEngineVisualsWithoutSound();
        playEngineSound();
    }

    private void turnOnEngineVisualsWithoutSound() {
        statusText.setText("ENGINE RUNNING");
        statusText.setTextColor(0xFF22C55E);
        btnStartEngine.setText("STOP\nENGINE");
        startPulseAnimation();
    }

    private void turnOffEngineVisuals() {
        stopPulseAnimation();
        statusText.setText("ENGINE OFF");
        statusText.setTextColor(0xFF94A3B8);
        btnStartEngine.setText("START\nENGINE");
    }

    private void playEngineSound() {
        try {
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
            }
        } catch (Exception ignored) { }
    }

    private void startPulseAnimation() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) return;
        pulseRing.setVisibility(View.VISIBLE);
        pulseRing.animate().alpha(0.55f).scaleX(1.14f).scaleY(1.14f).setDuration(240).start();
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                btnStartEngine,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.08f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.08f));
        pulseAnimator.setDuration(620);
        pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        btnStartEngine.setScaleX(1f);
        btnStartEngine.setScaleY(1f);
        pulseRing.animate().cancel();
        pulseRing.setAlpha(0f);
        pulseRing.setScaleX(1f);
        pulseRing.setScaleY(1f);
        pulseRing.setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
